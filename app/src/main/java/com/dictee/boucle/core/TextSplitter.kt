package com.dictee.boucle.core

import java.text.BreakIterator
import java.util.Locale

/** Une phrase : intervalle [start, end) dans le texte brut. */
data class Sentence(val start: Int, val end: Int)

/** Une partie à dicter : quelques phrases consécutives d'un même paragraphe. */
data class Part(val start: Int, val end: Int, val sentences: List<Sentence>)

/** Nombre de phrases par partie. */
enum class PartSize(val key: String, val max: Int) {
    ONE("1", 1),
    TWO("2", 2),
    TWO_THREE("2-3", 3),
    THREE_FOUR("3-4", 4);

    companion object {
        fun fromKey(key: String?): PartSize = values().firstOrNull { it.key == key } ?: TWO_THREE
    }
}

object TextSplitter {

    /** Abréviations après lesquelles un point ne termine pas la phrase. */
    private val ABBREVIATIONS = setOf(
        "m", "mm", "mme", "mmes", "mlle", "mlles", "dr", "drs", "pr", "me", "mgr",
        "st", "ste", "p", "pp", "cf", "ex", "n°", "no", "nos", "chap", "vol", "fig",
        "art", "env", "coll", "éd", "ed", "t", "vs", "c.-à-d", "ibid", "op", "etc",
        "av", "apr", "hab", "min", "max", "approx", "réf", "ref", "tél", "tel", "sq", "sqq"
    )

    fun isSpace(c: Char): Boolean =
        Character.isWhitespace(c) || c == ' ' || c == ' ' || c == ' ' || c == '￼'

    private fun isLineBreak(c: Char): Boolean = c == '\n' || c == ' ' || c == ' '

    /**
     * Découpe le texte en paragraphes, chacun étant une liste de phrases.
     * Une ligne vide sépare deux paragraphes ; un retour à la ligne simple termine une phrase.
     */
    fun paragraphs(text: String): List<List<Sentence>> {
        val result = mutableListOf<List<Sentence>>()
        var current = mutableListOf<Sentence>()
        var lineStart = 0
        val n = text.length
        while (lineStart <= n) {
            var lineEnd = lineStart
            while (lineEnd < n && !isLineBreak(text[lineEnd])) lineEnd++
            val sentences = sentencesInLine(text, lineStart, lineEnd)
            if (sentences.isEmpty()) {
                // Ligne vide (ou sans mot) : fin de paragraphe.
                if (current.isNotEmpty()) {
                    result += current
                    current = mutableListOf()
                }
            } else {
                current.addAll(sentences)
            }
            lineStart = lineEnd + 1
        }
        if (current.isNotEmpty()) result += current
        return result
    }

    /** Phrases d'une seule ligne [from, to). */
    fun sentencesInLine(text: String, from: Int, to: Int): List<Sentence> {
        if (from >= to) return emptyList()
        val line = text.substring(from, to)
        val bi = BreakIterator.getSentenceInstance(Locale.FRENCH)
        bi.setText(line)
        val raw = mutableListOf<Sentence>()
        var s = bi.first()
        var e = bi.next()
        while (e != BreakIterator.DONE) {
            var a = s
            var b = e
            while (a < b && isSpace(line[a])) a++
            while (b > a && isSpace(line[b - 1])) b--
            if (a < b) {
                val hasWord = (a until b).any { Character.isLetterOrDigit(line[it]) }
                if (hasWord) {
                    raw += Sentence(from + a, from + b)
                } else if (raw.isNotEmpty()) {
                    // Ponctuation isolée (guillemet fermant, etc.) : rattachée à la phrase précédente.
                    raw[raw.size - 1] = Sentence(raw.last().start, from + b)
                }
            }
            s = e
            e = bi.next()
        }
        // Recolle les fausses coupures (abréviations, initiales, minuscule après le point).
        val merged = mutableListOf<Sentence>()
        for (snt in raw) {
            val prev = merged.lastOrNull()
            if (prev != null && shouldJoin(text, prev, snt)) {
                merged[merged.size - 1] = Sentence(prev.start, snt.end)
            } else {
                merged += snt
            }
        }
        return merged
    }

    private fun shouldJoin(text: String, prev: Sentence, next: Sentence): Boolean {
        val firstChar = (next.start until next.end).map { text[it] }.firstOrNull { Character.isLetterOrDigit(it) }
            ?: return true
        if (Character.isLetter(firstChar) && Character.isLowerCase(firstChar)) return true
        val prevText = text.substring(prev.start, prev.end)
        if (!prevText.endsWith(".") || prevText.endsWith("..")) return false
        val body = prevText.dropLast(1)
        var i = body.length
        while (i > 0 && !isSpace(body[i - 1]) && body[i - 1] != '(' && body[i - 1] != '«' &&
            body[i - 1] != '"' && body[i - 1] != '\'' && body[i - 1] != '’' && body[i - 1] != '['
        ) i--
        val token = body.substring(i)
        if (token.isEmpty()) return false
        // Initiale : « J. Dupont ».
        if (token.length == 1 && Character.isUpperCase(token[0])) return true
        val lower = token.lowercase(Locale.FRENCH)
        if (lower == "etc") return Character.isDigit(firstChar)
        return lower in ABBREVIATIONS
    }

    /** Tailles équilibrées des groupes : 4 → 2+2, 5 → 3+2, 7 → 3+2+2 (pour un maximum de 3). */
    fun groupSizes(count: Int, max: Int): List<Int> {
        if (count <= 0) return emptyList()
        val groups = (count + max - 1) / max
        val base = count / groups
        val extra = count % groups
        return List(groups) { i -> base + if (i < extra) 1 else 0 }
    }

    fun split(text: String, size: PartSize): List<Part> {
        val parts = mutableListOf<Part>()
        for (para in paragraphs(text)) {
            var i = 0
            for (g in groupSizes(para.size, size.max)) {
                val sentences = para.subList(i, i + g).toList()
                parts += Part(sentences.first().start, sentences.last().end, sentences)
                i += g
            }
        }
        return parts
    }

    private val SPACES = Regex("[\\s\\u00A0\\u202F\\u2007\\uFFFC]+")
    private val AFTER_COMMA = Regex("(?<=[,;:])\\s+")

    /** Morceaux à prononcer pour une phrase, coupés aux virgules si demandé. */
    fun speechChunks(sentence: String, splitAtCommas: Boolean): List<String> {
        val clean = sentence.replace(SPACES, " ").trim()
        if (clean.isEmpty()) return emptyList()
        val pieces = if (splitAtCommas) clean.split(AFTER_COMMA) else listOf(clean)
        val out = mutableListOf<String>()
        for (p in pieces) {
            val t = p.trim()
            if (t.isEmpty()) continue
            if (t.any { Character.isLetterOrDigit(it) } || out.isEmpty()) out += t
            else out[out.size - 1] = out.last() + " " + t
        }
        return out.filter { s -> s.any { Character.isLetterOrDigit(it) } }
    }
}
