package com.dictee.boucle

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.dictee.boucle.core.PartSize
import com.dictee.boucle.core.TapDetector
import java.util.Locale
import kotlin.math.roundToInt

class SettingsActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private lateinit var gauge: TapGaugeView
    private lateinit var lastTap: TextView
    private lateinit var sensitivityLabel: TextView
    private lateinit var voiceSpinner: Spinner

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var voices: List<Voice> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)
        gauge = findViewById(R.id.gauge)
        lastTap = findViewById(R.id.lastTap)
        sensitivityLabel = findViewById(R.id.sensitivityLabel)
        voiceSpinner = findViewById(R.id.voiceSpinner)

        setupPartSize()
        setupVoiceAndRhythm()
        setupTaps()

        tts = TextToSpeech(applicationContext) { status ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (status == TextToSpeech.SUCCESS) {
                    ttsReady = true
                    tts?.setLanguage(Locale.FRANCE)
                    loadVoices()
                } else {
                    Toast.makeText(this, R.string.tts_unavailable, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onStart() {
        PlayerService.calibrating = true
        super.onStart()
    }

    override fun onStop() {
        PlayerService.calibrating = false
        tts?.stop()
        super.onStop()
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    // ---------------------------------------------------------------- découpage

    private fun setupPartSize() {
        val group = findViewById<RadioGroup>(R.id.sizeGroup)
        val ids = mapOf(
            PartSize.ONE to R.id.size1,
            PartSize.TWO to R.id.size2,
            PartSize.TWO_THREE to R.id.size23,
            PartSize.THREE_FOUR to R.id.size34
        )
        group.check(ids.getValue(prefs.partSize))
        group.setOnCheckedChangeListener { _, checkedId ->
            val size = ids.entries.firstOrNull { it.value == checkedId }?.key ?: return@setOnCheckedChangeListener
            if (size == prefs.partSize) return@setOnCheckedChangeListener
            // Garde la position : nouvelle partie contenant le début de l'ancienne.
            val old = DocumentStore.get(this)
            val oldStart = old.parts.getOrNull(prefs.partIndex.coerceAtLeast(0))?.start ?: 0
            prefs.partSize = size
            val doc = DocumentStore.get(this)
            val newIndex = doc.partAt(oldStart)
            PlayerService.command(this, PlayerService.ACTION_GOTO, newIndex)
            if (PlayerService.instance == null) prefs.partIndex = newIndex
        }
    }

    // ---------------------------------------------------------------- voix et rythme

    private fun fmt(v: Float, decimals: Int = 1): String = String.format(Locale.FRANCE, "%.${decimals}f", v)

    /** Curseur : valeur = min + progress × step. */
    private fun bindSeek(
        barId: Int, labelId: Int, min: Float, max: Float, step: Float, initial: Float,
        label: (Float) -> String, onChange: (Float) -> Unit
    ) {
        val bar = findViewById<SeekBar>(barId)
        val text = findViewById<TextView>(labelId)
        bar.max = ((max - min) / step).roundToInt()
        bar.progress = ((initial - min) / step).roundToInt().coerceIn(0, bar.max)
        text.text = label(initial)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val v = min + progress * step
                text.text = label(v)
                if (fromUser) onChange(v)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })
    }

    private fun setupVoiceAndRhythm() {
        bindSeek(R.id.speedBar, R.id.speedLabel, 0.4f, 1.2f, 0.05f, prefs.rate,
            { getString(R.string.speed_format, fmt(it, 2)) }) { prefs.rate = it }
        bindSeek(R.id.sentencePauseBar, R.id.sentencePauseLabel, 0f, 5f, 0.1f, prefs.sentencePauseMs / 1000f,
            { getString(R.string.sentence_pause_format, fmt(it)) }) { prefs.sentencePauseMs = (it * 1000).roundToInt() }
        bindSeek(R.id.commaPauseBar, R.id.commaPauseLabel, 0.1f, 2f, 0.1f, prefs.commaPauseMs / 1000f,
            { getString(R.string.comma_pause_format, fmt(it)) }) { prefs.commaPauseMs = (it * 1000).roundToInt() }
        bindSeek(R.id.loopPauseBar, R.id.loopPauseLabel, 0f, 10f, 0.5f, prefs.loopPauseMs / 1000f,
            { getString(R.string.loop_pause_format, fmt(it)) }) { prefs.loopPauseMs = (it * 1000).roundToInt() }

        val comma = findViewById<CheckBox>(R.id.commaCheck)
        val commaBar = findViewById<SeekBar>(R.id.commaPauseBar)
        comma.isChecked = prefs.commaPauseEnabled
        commaBar.isEnabled = comma.isChecked
        comma.setOnCheckedChangeListener { _, checked ->
            prefs.commaPauseEnabled = checked
            commaBar.isEnabled = checked
        }

        voiceSpinner.adapter = ArrayAdapter(
            this, android.R.layout.simple_spinner_dropdown_item, listOf(getString(R.string.voice_default))
        )
        findViewById<Button>(R.id.testVoice).setOnClickListener { testVoice() }
    }

    private fun loadVoices() {
        val all = try {
            tts?.voices?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        voices = all.filter { it.locale.language == "fr" }
            .sortedWith(compareBy<Voice>({ it.isNetworkConnectionRequired }, { it.locale.country != "FR" }, { it.name }))
        val labels = mutableListOf(getString(R.string.voice_default))
        voices.forEachIndexed { i, v ->
            val country = v.locale.getDisplayCountry(Locale.FRENCH).ifEmpty { v.locale.toString() }
            val net = if (v.isNetworkConnectionRequired) " " + getString(R.string.voice_network) else ""
            labels += "Voix ${i + 1} · $country$net  (${v.name})"
        }
        if (voices.isEmpty()) Toast.makeText(this, R.string.tts_no_french, Toast.LENGTH_LONG).show()
        voiceSpinner.onItemSelectedListener = null
        voiceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val selected = voices.indexOfFirst { it.name == prefs.voiceName }
        voiceSpinner.setSelection(if (selected >= 0) selected + 1 else 0, false)
        voiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                val name = if (position == 0) null else voices.getOrNull(position - 1)?.name
                if (name != prefs.voiceName) prefs.voiceName = name
            }

            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun testVoice() {
        val t = tts
        if (t == null || !ttsReady) {
            Toast.makeText(this, R.string.tts_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        PlayerService.instance?.let { if (PlayerService.playing) it.handle(PlayerService.ACTION_PAUSE, -1) }
        val v = voices.firstOrNull { it.name == prefs.voiceName }
        if (v != null) t.voice = v else t.setLanguage(Locale.FRANCE)
        t.setSpeechRate(prefs.rate)
        t.speak(getString(R.string.test_sentence), TextToSpeech.QUEUE_FLUSH, null, "test")
    }

    // ---------------------------------------------------------------- taps

    private fun setupTaps() {
        val bar = findViewById<SeekBar>(R.id.sensitivityBar)
        bar.max = 9
        bar.progress = prefs.sensitivity - 1
        updateSensitivityLabel(prefs.sensitivity)
        gauge.setThreshold(prefs.tapThreshold)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                val s = progress + 1
                updateSensitivityLabel(s)
                gauge.setThreshold(TapDetector.thresholdFor(s))
                if (fromUser) prefs.sensitivity = s
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
        })

        val group = findViewById<RadioGroup>(R.id.tripleGroup)
        group.check(if (prefs.tripleGoesToFirst) R.id.tripleFirst else R.id.triplePrev)
        group.setOnCheckedChangeListener { _, id -> prefs.tripleGoesToFirst = id == R.id.tripleFirst }

        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        if (sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null) {
            lastTap.setText(R.string.no_accelerometer)
        }
    }

    private fun updateSensitivityLabel(s: Int) {
        sensitivityLabel.text = getString(R.string.sensitivity_format, s, fmt(TapDetector.thresholdFor(s), 2))
    }

    override fun onTapLevel(level: Float, threshold: Float) {
        gauge.setLevel(level, threshold)
    }

    override fun onTapSeries(count: Int) {
        val what = when (count) {
            1 -> getString(R.string.tap_single)
            2 -> getString(R.string.tap_double)
            3 -> getString(R.string.tap_triple)
            else -> getString(R.string.tap_many, count)
        }
        lastTap.text = getString(R.string.last_tap_format, what)
    }
}
