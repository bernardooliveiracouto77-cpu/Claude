package com.dictee.boucle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dictee.boucle.core.TapDetector
import com.dictee.boucle.core.TapGesture
import com.dictee.boucle.core.TextSplitter
import com.dictee.boucle.core.gestureFor
import java.util.Locale

/**
 * Service de premier plan (mediaPlayback) : lecture TTS en boucle de la partie en cours
 * et détection des taps au dos du téléphone, y compris écran éteint.
 */
class PlayerService : Service(), TextToSpeech.OnInitListener, SensorEventListener {

    interface Listener {
        fun onPlayerState() {}
        fun onTapLevel(level: Float, threshold: Float) {}
        fun onTapSeries(count: Int) {}
    }

    companion object {
        const val ACTION_ATTACH = "com.dictee.boucle.ATTACH"
        const val ACTION_TOGGLE = "com.dictee.boucle.TOGGLE"
        const val ACTION_PLAY = "com.dictee.boucle.PLAY"
        const val ACTION_PAUSE = "com.dictee.boucle.PAUSE"
        const val ACTION_NEXT = "com.dictee.boucle.NEXT"
        const val ACTION_PREV = "com.dictee.boucle.PREV"
        const val ACTION_FIRST = "com.dictee.boucle.FIRST"
        const val ACTION_GOTO = "com.dictee.boucle.GOTO"
        const val EXTRA_INDEX = "index"

        private const val CHANNEL_ID = "lecture"
        private const val NOTIF_ID = 1
        private const val IDLE_STOP_MS = 10 * 60 * 1000L

        private val mainHandler = Handler(Looper.getMainLooper())
        private val listeners = LinkedHashSet<Listener>()

        var instance: PlayerService? = null
            private set

        /** Lecture en cours ? */
        var playing = false
            private set

        /** Numéro de la lecture de la partie en cours (1, 2, 3…). */
        var loopNumber = 1
            private set

        /** Nombre d'écrans visibles qui ont besoin du service (lecture, mains libres, réglages). */
        private var clients = 0

        /**
         * Pendant le réglage de sensibilité : la jauge est alimentée et les gestes sont affichés
         * sans changer de partie.
         */
        @Volatile
        var calibrating = false

        fun addListener(l: Listener) {
            listeners += l
        }

        fun removeListener(l: Listener) {
            listeners -= l
        }

        private fun notifyState() {
            for (l in listeners.toList()) l.onPlayerState()
        }

        /** Envoie une commande au service (le démarre si besoin ; à appeler depuis l'application au premier plan). */
        fun command(context: Context, action: String, index: Int = -1) {
            val s = instance
            if (s != null) {
                s.handle(action, index)
            } else {
                val intent = Intent(context, PlayerService::class.java).setAction(action)
                if (index >= 0) intent.putExtra(EXTRA_INDEX, index)
                try {
                    ContextCompat.startForegroundService(context, intent)
                } catch (e: Exception) {
                    // Démarrage refusé (application en arrière-plan) : rien à faire.
                }
            }
        }

        fun attach(context: Context) {
            clients++
            command(context, ACTION_ATTACH)
        }

        fun detach() {
            clients = (clients - 1).coerceAtLeast(0)
            instance?.updateLifecycle()
        }

        /** L'utilisateur touche l'écran : on ignore les taps au dos pendant 400 ms. */
        fun notifyTouch() {
            instance?.suppressTaps()
        }
    }

    private lateinit var prefs: Prefs
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var ttsFailed = false
    private var pendingStart = false
    private var appliedVoice: String? = "\u0000"
    private var generation = 0

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var sensorThread: HandlerThread? = null
    private var sensorHandler: Handler? = null
    private var sensorRegistered = false
    private lateinit var detector: TapDetector

    private var levelPeak = 0f
    private var lastLevelPost = 0L

    private var screenReceiverRegistered = false

    /**
     * Écran éteint/allumé : certains téléphones cessent d'envoyer les mesures de l'accéléromètre
     * à l'extinction de l'écran ; on réenregistre le capteur pour continuer à détecter les taps.
     */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!sensorRegistered) return
            mainHandler.postDelayed({
                if (sensorRegistered) {
                    unregisterSensor()
                    registerSensor()
                }
            }, 300)
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var toast: Toast? = null

    private val loopRunnable = Runnable { if (playing) enqueuePart(flush = true) }
    private val idleStopRunnable = Runnable { if (!playing && clients == 0) stopEverything() }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.K_SENSITIVITY) {
            val thr = prefs.tapThreshold
            sensorHandler?.post { detector.threshold = thr }
        }
        if (key == Prefs.K_CONTENT || key == Prefs.K_PART_SIZE) updateNotification()
    }

    // ------------------------------------------------------------------ cycle de vie

    override fun onCreate() {
        super.onCreate()
        instance = this
        prefs = Prefs(this)
        createChannel()
        startInForeground()

        detector = TapDetector(prefs.tapThreshold) { count -> mainHandler.post { onTapSeries(count) } }
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        sensorThread = HandlerThread("taps").also { it.start() }
        sensorHandler = Handler(sensorThread!!.looper)
        prefs.sp.registerOnSharedPreferenceChangeListener(prefsListener)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        screenReceiverRegistered = true

        tts = TextToSpeech(applicationContext, this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        handle(intent?.action ?: ACTION_ATTACH, intent?.getIntExtra(EXTRA_INDEX, -1) ?: -1)
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!playing) stopEverything()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        generation++
        playing = false
        mainHandler.removeCallbacks(loopRunnable)
        mainHandler.removeCallbacks(idleStopRunnable)
        prefs.sp.unregisterOnSharedPreferenceChangeListener(prefsListener)
        if (screenReceiverRegistered) {
            unregisterReceiver(screenReceiver)
            screenReceiverRegistered = false
        }
        unregisterSensor()
        sensorThread?.quitSafely()
        tts?.stop()
        tts?.shutdown()
        tts = null
        releaseWakeLock()
        instance = null
        notifyState()
        super.onDestroy()
    }

    private fun stopEverything() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** Capteur actif seulement si quelqu'un en a besoin ; arrêt différé du service si inutilisé. */
    fun updateLifecycle() {
        val needed = playing || clients > 0
        if (needed) registerSensor() else unregisterSensor()
        mainHandler.removeCallbacks(idleStopRunnable)
        if (!needed) mainHandler.postDelayed(idleStopRunnable, IDLE_STOP_MS)
    }

    // ------------------------------------------------------------------ commandes

    fun handle(action: String, index: Int) {
        when (action) {
            ACTION_TOGGLE -> if (playing) pause() else play()
            ACTION_PLAY -> play()
            ACTION_PAUSE -> pause()
            ACTION_NEXT -> next()
            ACTION_PREV -> previous()
            ACTION_FIRST -> goTo(0)
            ACTION_GOTO -> if (index >= 0) goTo(index)
        }
        updateLifecycle()
        updateNotification()
        notifyState()
    }

    private fun partCount(): Int = DocumentStore.get(this).parts.size

    private fun currentIndex(): Int {
        val n = partCount()
        return if (n == 0) 0 else prefs.partIndex.coerceIn(0, n - 1)
    }

    fun play() {
        if (partCount() == 0) return
        playing = true
        acquireWakeLock()
        startPart()
    }

    fun pause() {
        playing = false
        generation++
        pendingStart = false
        mainHandler.removeCallbacks(loopRunnable)
        tts?.stop()
        releaseWakeLock()
    }

    fun next() {
        val n = partCount()
        if (n == 0) return
        val i = currentIndex()
        if (i >= n - 1) {
            showToast(getString(R.string.toast_last_part))
            return
        }
        goTo(i + 1)
    }

    fun previous() {
        goTo((currentIndex() - 1).coerceAtLeast(0))
    }

    fun goTo(index: Int) {
        val n = partCount()
        if (n == 0) return
        prefs.partIndex = index.coerceIn(0, n - 1)
        loopNumber = 1
        if (playing) startPart()
    }

    // ------------------------------------------------------------------ synthèse vocale

    override fun onInit(status: Int) {
        val t = tts
        if (status != TextToSpeech.SUCCESS || t == null) {
            ttsFailed = true
            showToast(getString(R.string.tts_unavailable))
            if (playing) pause()
            handle("", -1)
            return
        }
        ttsReady = true
        t.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        val lang = t.setLanguage(Locale.FRANCE)
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            showToast(getString(R.string.tts_no_french))
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = onUtteranceEnd(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = onUtteranceEnd(utteranceId)
            override fun onError(utteranceId: String?, errorCode: Int) = onUtteranceEnd(utteranceId)
        })
        if (pendingStart && playing) {
            pendingStart = false
            enqueuePart(flush = true, firstLoop = true)
        }
    }

    /** Fin du dernier morceau de la partie : pause, puis nouvelle lecture. */
    private fun onUtteranceEnd(id: String?) {
        mainHandler.post {
            if (playing && id == "g$generation:last") {
                mainHandler.removeCallbacks(loopRunnable)
                mainHandler.postDelayed(loopRunnable, prefs.loopPauseMs.toLong())
            }
        }
    }

    private fun startPart() {
        generation++
        mainHandler.removeCallbacks(loopRunnable)
        loopNumber = 1
        if (ttsReady) {
            enqueuePart(flush = true, firstLoop = true)
        } else if (ttsFailed) {
            showToast(getString(R.string.tts_unavailable))
            pause()
        } else {
            pendingStart = true
        }
    }

    private fun enqueuePart(flush: Boolean, firstLoop: Boolean = false) {
        val t = tts ?: return
        if (!playing) return
        val doc = DocumentStore.get(this)
        if (doc.parts.isEmpty()) {
            pause()
            notifyState()
            return
        }
        if (!firstLoop && flush) {
            // Nouvelle lecture de la même partie.
            loopNumber++
            updateNotification()
            notifyState()
        }
        generation++
        val gen = generation
        acquireWakeLock() // renouvelé à chaque lecture : la boucle peut durer très longtemps
        applyVoice(t)
        t.setSpeechRate(prefs.rate)

        val part = doc.parts[currentIndex()]
        val commaOn = prefs.commaPauseEnabled
        val commaMs = prefs.commaPauseMs.toLong()
        val sentenceMs = prefs.sentencePauseMs.toLong()

        // Liste des éléments : texte (String) ou silence (Long, en ms).
        val items = ArrayList<Any>()
        part.sentences.forEachIndexed { si, s ->
            val chunks = TextSplitter.speechChunks(doc.plain.substring(s.start, s.end), commaOn)
            chunks.forEachIndexed { ci, c ->
                if (ci > 0 && commaMs > 0) items += commaMs
                items += c
            }
            if (si < part.sentences.size - 1 && sentenceMs > 0) items += sentenceMs
        }
        val lastText = items.indexOfLast { it is String }
        if (lastText < 0) {
            mainHandler.postDelayed(loopRunnable, prefs.loopPauseMs.toLong().coerceAtLeast(500))
            return
        }
        var mode = if (flush) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        for (k in 0..lastText) {
            val item = items[k]
            val id = if (k == lastText) "g$gen:last" else "g$gen:$k"
            if (item is String) {
                t.speak(item, mode, null, id)
            } else {
                t.playSilentUtterance(item as Long, mode, id)
            }
            mode = TextToSpeech.QUEUE_ADD
        }
    }

    private fun applyVoice(t: TextToSpeech) {
        val wanted = prefs.voiceName
        if (wanted == appliedVoice) return
        appliedVoice = wanted
        var voice: Voice? = null
        if (wanted != null) {
            voice = try {
                t.voices?.firstOrNull { it.name == wanted }
            } catch (e: Exception) {
                null
            }
        }
        if (voice != null) t.voice = voice else t.setLanguage(Locale.FRANCE)
    }

    // ------------------------------------------------------------------ taps au dos

    private fun registerSensor() {
        if (sensorRegistered) return
        val sm = sensorManager ?: return
        val acc = accelerometer ?: return
        val h = sensorHandler ?: return
        h.post { detector.reset() }
        // maxReportLatency = 0 : pas de regroupement des mesures (sinon, écran éteint,
        // certains téléphones les livrent par paquets et le comptage des taps est faussé).
        sensorRegistered = sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_FASTEST, 0, h)
    }

    private fun unregisterSensor() {
        if (!sensorRegistered) return
        sensorManager?.unregisterListener(this)
        sensorRegistered = false
    }

    fun suppressTaps() {
        sensorHandler?.post { detector.suppressForTouch() }
    }

    /** Appelé sur le thread « taps ». */
    override fun onSensorChanged(event: SensorEvent) {
        val tMs = event.timestamp / 1_000_000L
        val level = detector.process(tMs, event.values[2])
        if (calibrating) {
            if (level > levelPeak) levelPeak = level
            val now = SystemClock.uptimeMillis()
            if (now - lastLevelPost >= 40) {
                lastLevelPost = now
                val peak = levelPeak
                val thr = detector.threshold
                levelPeak = 0f
                mainHandler.post { for (l in listeners.toList()) l.onTapLevel(peak, thr) }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun onTapSeries(count: Int) {
        for (l in listeners.toList()) l.onTapSeries(count)
        if (calibrating) return
        when (gestureFor(count)) {
            TapGesture.NEXT -> {
                val before = currentIndex()
                next()
                if (currentIndex() != before) showToast(getString(R.string.toast_next))
            }
            TapGesture.BACK -> {
                if (prefs.tripleGoesToFirst) {
                    goTo(0)
                    showToast(getString(R.string.toast_first))
                } else {
                    previous()
                    showToast(getString(R.string.toast_previous))
                }
            }
            TapGesture.NONE -> return
        }
        updateNotification()
        notifyState()
    }

    // ------------------------------------------------------------------ notification

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.channel_desc)
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun startInForeground() {
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
    }

    private fun servicePending(action: String, code: Int): PendingIntent {
        val intent = Intent(this, PlayerService::class.java).setAction(action)
        return PendingIntent.getForegroundService(
            this, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildNotification(): Notification {
        val doc = DocumentStore.get(this)
        val n = doc.parts.size
        val text = if (n == 0) getString(R.string.no_parts)
        else getString(R.string.status_format, currentIndex() + 1, n, loopNumber)
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, ReaderActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val preview = if (n == 0) "" else doc.partText(currentIndex()).replace(Regex("\\s+"), " ")
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(text)
            .setContentText(preview)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(playing)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_previous, getString(R.string.previous), servicePending(ACTION_PREV, 1))
            .addAction(
                if (playing) R.drawable.ic_pause else R.drawable.ic_play,
                getString(if (playing) R.string.pause else R.string.play),
                servicePending(ACTION_TOGGLE, 2)
            )
            .addAction(R.drawable.ic_next, getString(R.string.next), servicePending(ACTION_NEXT, 3))
            .build()
    }

    private fun updateNotification() {
        if (instance == null) return
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, buildNotification())
        } catch (e: SecurityException) {
            // Permission de notification refusée : le service continue sans notification visible.
        }
    }

    // ------------------------------------------------------------------ divers

    /** Wake lock non compté : chaque appel le (re)prend pour 4 h, ce qui prolonge son délai. */
    private fun acquireWakeLock() {
        val lock = wakeLock ?: (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DicteeEnBoucle:lecture")
            .also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        lock.acquire(4 * 60 * 60 * 1000L)
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun showToast(text: String) {
        toast?.cancel()
        toast = Toast.makeText(applicationContext, text, Toast.LENGTH_SHORT).also { it.show() }
    }
}
