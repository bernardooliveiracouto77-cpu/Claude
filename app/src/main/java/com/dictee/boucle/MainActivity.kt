package com.dictee.boucle

import android.Manifest
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.Html
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.BulletSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.view.Menu
import android.view.MenuItem
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Écran « Texte » : coller, effacer, découper et lire. */
class MainActivity : BaseActivity() {

    override val needsPlayer = false

    private lateinit var prefs: Prefs
    private lateinit var editor: EditText

    /** Le contenu de l'éditeur diffère du texte enregistré (modifié à la main ou par un collage partiel). */
    private var dirty = false
    private var ignoreWatcher = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = Prefs(this)
        editor = findViewById(R.id.editor)

        loadEditor()
        editor.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!ignoreWatcher) dirty = true
            }
        })

        findViewById<Button>(R.id.btnPaste).setOnClickListener { paste() }
        findViewById<Button>(R.id.btnClear).setOnClickListener { confirmClear() }
        findViewById<Button>(R.id.btnRead).setOnClickListener { splitAndRead() }

        askNotificationPermission()
    }

    override fun onPause() {
        save()
        super.onPause()
    }

    private fun loadEditor() {
        val doc = DocumentStore.get(this)
        ignoreWatcher = true
        editor.setText(doc.spanned)
        ignoreWatcher = false
        dirty = false
    }

    private fun paste() {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = cm.primaryClip
        if (clip == null || clip.itemCount == 0) {
            toast(getString(R.string.clipboard_empty))
            return
        }
        val item = clip.getItemAt(0)
        val html = item.htmlText
        val plain = item.coerceToText(this)?.toString() ?: ""
        if (html.isNullOrBlank() && plain.isBlank()) {
            toast(getString(R.string.clipboard_empty))
            return
        }
        val editorEmpty = editor.text.toString().isBlank()
        if (editorEmpty) {
            // Éditeur vide : on garde la source telle quelle (HTML d'origine = meilleure fidélité).
            if (!html.isNullOrBlank()) savedText(html, true) else savedText(plain, false)
            loadEditor()
            editor.setSelection(0)
        } else {
            // Insertion à l'endroit du curseur.
            val insert: CharSequence = if (!html.isNullOrBlank()) DocumentStore.fromHtml(html) else plain
            val e = editor.text
            val a = editor.selectionStart.coerceAtLeast(0)
            val b = editor.selectionEnd.coerceAtLeast(0)
            e.replace(minOf(a, b), maxOf(a, b), insert)
            dirty = true
            save()
        }
    }

    private fun savedText(content: String, isHtml: Boolean) {
        if (content != prefs.content || isHtml != prefs.isHtml) {
            prefs.setText(content, isHtml)
            prefs.partIndex = 0
        }
    }

    /** Enregistre l'éditeur s'il a été modifié (HTML si mis en forme, sinon texte brut). */
    private fun save() {
        if (!dirty) return
        val text: Spanned = editor.text
        if (hasFormatting(text)) {
            savedText(Html.toHtml(text, Html.TO_HTML_PARAGRAPH_LINES_CONSECUTIVE), true)
        } else {
            savedText(text.toString(), false)
        }
        dirty = false
    }

    private fun hasFormatting(text: Spanned): Boolean =
        text.getSpans(0, text.length, StyleSpan::class.java).isNotEmpty() ||
            text.getSpans(0, text.length, RelativeSizeSpan::class.java).isNotEmpty() ||
            text.getSpans(0, text.length, UnderlineSpan::class.java).isNotEmpty() ||
            text.getSpans(0, text.length, BulletSpan::class.java).isNotEmpty()

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle(R.string.clear_confirm_title)
            .setMessage(R.string.clear_confirm_message)
            .setPositiveButton(R.string.clear) { _, _ ->
                PlayerService.instance?.pause()
                savedText("", false)
                loadEditor()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun splitAndRead() {
        save()
        val doc = DocumentStore.get(this)
        if (doc.parts.isEmpty()) {
            toast(getString(R.string.text_empty))
            return
        }
        if (prefs.partIndex >= doc.parts.size) prefs.partIndex = 0
        startActivity(Intent(this, ReaderActivity::class.java))
    }

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) return
        if (prefs.askedNotifications) return
        prefs.askedNotifications = true
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.menu_settings) {
            save()
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
