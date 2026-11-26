package com.dictee.boucle

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import kotlin.math.abs
import kotlin.math.max

/** Écran « Lecture » : tout le texte, partie en cours surlignée, barre de commandes. */
class ReaderActivity : BaseActivity() {

    private lateinit var prefs: Prefs
    private lateinit var scroll: ScrollView
    private lateinit var textView: TextView
    private lateinit var status: TextView
    private lateinit var btnPlay: ImageButton

    private var doc: DocumentStore.Doc? = null
    private var spannable: Spannable? = null
    private val highlightSpan by lazy { BackgroundColorSpan(ContextCompat.getColor(this, R.color.highlight)) }
    private val highlightTextSpan by lazy { ForegroundColorSpan(ContextCompat.getColor(this, R.color.highlight_text)) }
    private val fadeBefore by lazy { ForegroundColorSpan(ContextCompat.getColor(this, R.color.ink_faded)) }
    private val fadeAfter by lazy { ForegroundColorSpan(ContextCompat.getColor(this, R.color.ink_faded)) }
    private var shownIndex = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_reader)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        prefs = Prefs(this)
        scroll = findViewById(R.id.scroll)
        textView = findViewById(R.id.text)
        status = findViewById(R.id.status)
        btnPlay = findViewById(R.id.btnPlay)

        findViewById<ImageButton>(R.id.btnFirst).setOnClickListener { cmd(PlayerService.ACTION_FIRST) }
        findViewById<ImageButton>(R.id.btnPrev).setOnClickListener { cmd(PlayerService.ACTION_PREV) }
        findViewById<ImageButton>(R.id.btnNext).setOnClickListener { cmd(PlayerService.ACTION_NEXT) }
        btnPlay.setOnClickListener { onPlayPressed() }

        setupSentenceTouch()
    }

    override fun onStart() {
        super.onStart()
        val d = DocumentStore.get(this)
        if (d.parts.isEmpty()) {
            Toast.makeText(this, R.string.text_empty, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        if (d !== doc) {
            doc = d
            textView.setText(SpannableStringBuilder(d.spanned), TextView.BufferType.SPANNABLE)
            spannable = textView.text as Spannable
            shownIndex = -1
        }
        refresh()
    }

    override fun onPlayerState() = refresh()

    private fun cmd(action: String, index: Int = -1) = PlayerService.command(this, action, index)

    /**
     * Lire/Pause. Au premier lancement de la lecture, propose une fois d'exclure l'app de
     * l'optimisation de la batterie, pour que tout continue écran éteint. La lecture démarre
     * de toute façon tout de suite.
     */
    private fun onPlayPressed() {
        val starting = !PlayerService.playing
        cmd(PlayerService.ACTION_TOGGLE)
        if (starting && !prefs.askedBattery && !Battery.isIgnoringOptimizations(this)) {
            prefs.askedBattery = true
            AlertDialog.Builder(this)
                .setTitle(R.string.battery_title)
                .setMessage(R.string.battery_message)
                .setPositiveButton(R.string.battery_allow) { _, _ -> Battery.requestIgnore(this) }
                .setNegativeButton(R.string.battery_later, null)
                .show()
        }
    }

    private fun refresh() {
        val d = doc ?: return
        val sp = spannable ?: return
        val n = d.parts.size
        val index = prefs.partIndex.coerceIn(0, n - 1)
        val part = d.parts[index]

        status.text = getString(R.string.status_format, index + 1, n, PlayerService.loopNumber)
        val playing = PlayerService.playing
        btnPlay.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        btnPlay.contentDescription = getString(if (playing) R.string.pause else R.string.play)

        if (index != shownIndex) {
            for (s in listOf(highlightSpan, highlightTextSpan, fadeBefore, fadeAfter)) sp.removeSpan(s)
            val flags = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            if (part.start > 0) sp.setSpan(fadeBefore, 0, part.start, flags)
            if (part.end < sp.length) sp.setSpan(fadeAfter, part.end, sp.length, flags)
            sp.setSpan(highlightSpan, part.start, part.end, flags)
            sp.setSpan(highlightTextSpan, part.start, part.end, flags)
            val smooth = shownIndex >= 0
            shownIndex = index
            scrollToPart(part.start, smooth)
        }
    }

    private fun scrollToPart(offset: Int, smooth: Boolean) {
        textView.post {
            val layout = textView.layout ?: return@post
            val line = layout.getLineForOffset(offset)
            val y = textView.top + textView.paddingTop + layout.getLineTop(line)
            val target = max(0, y - scroll.height / 4)
            if (smooth) scroll.smoothScrollTo(0, target) else scroll.scrollTo(0, target)
        }
    }

    /** Toucher une phrase : aller à sa partie (sans gêner le défilement). */
    @SuppressLint("ClickableViewAccessibility")
    private fun setupSentenceTouch() {
        val slop = ViewConfiguration.get(this).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var downTime = 0L
        textView.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.x; downY = ev.y; downTime = ev.eventTime
                }
                MotionEvent.ACTION_UP -> {
                    val still = abs(ev.x - downX) < slop && abs(ev.y - downY) < slop
                    if (still && ev.eventTime - downTime < 600) onTextTapped(ev.x, ev.y)
                }
            }
            true
        }
    }

    private fun onTextTapped(x: Float, y: Float) {
        val d = doc ?: return
        val offset = textView.getOffsetForPosition(x, y)
        if (offset < 0) return
        val index = d.partAt(offset)
        if (index != prefs.partIndex.coerceIn(0, d.parts.size - 1)) cmd(PlayerService.ACTION_GOTO, index)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.reader, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_hands_free -> startActivity(Intent(this, HandsFreeActivity::class.java))
            R.id.menu_settings -> startActivity(Intent(this, SettingsActivity::class.java))
            R.id.menu_edit, android.R.id.home -> openEditor()
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun openEditor() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        )
        finish()
    }
}
