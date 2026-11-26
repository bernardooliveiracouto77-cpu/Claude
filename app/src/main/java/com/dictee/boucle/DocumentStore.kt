package com.dictee.boucle

import android.content.Context
import android.text.Html
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AbsoluteSizeSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.ImageSpan
import com.dictee.boucle.core.Part
import com.dictee.boucle.core.PartSize
import com.dictee.boucle.core.TextSplitter

/** Texte mis en forme + découpage, partagé par les écrans et le service. */
object DocumentStore {

    class Doc(
        val content: String,
        val isHtml: Boolean,
        val size: PartSize,
        val spanned: Spanned,
        val plain: String,
        val parts: List<Part>
    ) {
        fun partText(i: Int): String {
            val p = parts.getOrNull(i) ?: return ""
            return plain.substring(p.start, p.end)
        }

        /** Partie contenant (ou suivant) la position [offset] du texte. */
        fun partAt(offset: Int): Int {
            if (parts.isEmpty()) return 0
            val i = parts.indexOfFirst { offset < it.end }
            return if (i < 0) parts.size - 1 else i
        }
    }

    @Volatile
    private var cached: Doc? = null

    @Synchronized
    fun get(context: Context): Doc {
        val prefs = Prefs(context)
        val content = prefs.content
        val isHtml = prefs.isHtml
        val size = prefs.partSize
        cached?.let { c ->
            if (c.isHtml == isHtml && c.size == size && c.content == content) return c
        }
        val spanned: Spanned = if (isHtml) fromHtml(content) else SpannableString(content)
        val plain = spanned.toString()
        val doc = Doc(content, isHtml, size, spanned, plain, TextSplitter.split(plain, size))
        cached = doc
        return doc
    }

    private val DROP_BLOCKS = Regex("(?is)<(style|script|head|title|noscript)\\b[^>]*>.*?</\\1\\s*>")
    private val DROP_TAGS = Regex("(?is)<(img|meta|link|input|svg)\\b[^>]*/?>")

    /** HTML du presse-papiers → texte mis en forme, sans couleurs ni images (lisible en thème sombre). */
    fun fromHtml(html: String): Spanned {
        val clean = html.replace(DROP_BLOCKS, "").replace(DROP_TAGS, "")
        val parsed = Html.fromHtml(clean, Html.FROM_HTML_MODE_LEGACY)
        val ssb = SpannableStringBuilder(parsed)
        for (cls in listOf(
            ForegroundColorSpan::class.java,
            BackgroundColorSpan::class.java,
            AbsoluteSizeSpan::class.java,
            ImageSpan::class.java
        )) {
            for (span in ssb.getSpans(0, ssb.length, cls)) ssb.removeSpan(span)
        }
        // Retire les caractères de remplacement d'objets éventuels.
        var i = ssb.length - 1
        while (i >= 0) {
            if (ssb[i] == '￼') ssb.delete(i, i + 1)
            i--
        }
        // Retire les retours à la ligne superflus en fin de texte.
        var end = ssb.length
        while (end > 0 && TextSplitter.isSpace(ssb[end - 1])) end--
        if (end < ssb.length) ssb.delete(end, ssb.length)
        return ssb
    }
}
