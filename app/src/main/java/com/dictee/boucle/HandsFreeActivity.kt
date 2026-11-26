package com.dictee.boucle

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlin.math.abs

/**
 * Mode mains libres : plein écran, écran toujours allumé, partie en cours en très gros.
 * Double tap sur l'écran : partie suivante ; triple tap : début de la partie, ou partie précédente
 * si la lecture vient de commencer (comme les taps au dos).
 */
class HandsFreeActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private lateinit var statusView: TextView
    private lateinit var textView: TextView
    private lateinit var scroll: ScrollView
    private lateinit var playButton: ImageButton

    private val handler = Handler(Looper.getMainLooper())
    private var tapCount = 0
    private var lastTapTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var downTime = 0L
    private var downOnControl = false
    private var shownIndex = -1

    private val resolveTaps = Runnable {
        val c = tapCount
        tapCount = 0
        when (c) {
            2 -> PlayerService.command(this, PlayerService.ACTION_GESTURE_NEXT)
            3 -> PlayerService.command(this, PlayerService.ACTION_GESTURE_BACK)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_hands_free)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        prefs = Prefs(this)
        statusView = findViewById(R.id.hfStatus)
        textView = findViewById(R.id.hfText)
        scroll = findViewById(R.id.hfScroll)
        playButton = findViewById(R.id.hfPlay)
        playButton.setOnClickListener { PlayerService.command(this, PlayerService.ACTION_TOGGLE) }
        findViewById<ImageButton>(R.id.hfClose).setOnClickListener { finish() }
        hideSystemBars()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    override fun onStart() {
        super.onStart()
        shownIndex = -1
        refresh()
    }

    override fun onStop() {
        handler.removeCallbacks(resolveTaps)
        tapCount = 0
        super.onStop()
    }

    override fun onPlayerState() = refresh()

    private fun refresh() {
        val doc = DocumentStore.get(this)
        val n = doc.parts.size
        if (n == 0) {
            finish()
            return
        }
        val index = prefs.partIndex.coerceIn(0, n - 1)
        statusView.text = getString(R.string.status_format, index + 1, n, PlayerService.loopNumber)
        val playing = PlayerService.playing
        playButton.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        playButton.contentDescription = getString(if (playing) R.string.pause else R.string.play)
        if (index != shownIndex) {
            shownIndex = index
            val part = doc.parts[index]
            textView.text = doc.spanned.subSequence(part.start, part.end)
            scroll.scrollTo(0, 0)
        }
    }

    /** Comptage des taps sur l'écran (hors boutons). */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; downTime = ev.eventTime
                downOnControl = isInside(playButton, ev) || isInside(findViewById(R.id.hfClose), ev)
            }
            MotionEvent.ACTION_UP -> {
                val slop = ViewConfiguration.get(this).scaledTouchSlop
                val still = abs(ev.rawX - downX) < slop && abs(ev.rawY - downY) < slop
                if (!downOnControl && still && ev.eventTime - downTime < 400) onScreenTap(ev.eventTime)
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    private fun onScreenTap(time: Long) {
        tapCount = if (time - lastTapTime <= 400) tapCount + 1 else 1
        lastTapTime = time
        handler.removeCallbacks(resolveTaps)
        handler.postDelayed(resolveTaps, 420)
    }

    private fun isInside(v: View, ev: MotionEvent): Boolean {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return ev.rawX >= loc[0] && ev.rawX <= loc[0] + v.width && ev.rawY >= loc[1] && ev.rawY <= loc[1] + v.height
    }
}
