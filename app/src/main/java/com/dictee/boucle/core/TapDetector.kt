package com.dictee.boucle.core

import kotlin.math.abs

/**
 * Détection de taps au dos du téléphone à partir de l'axe Z de l'accéléromètre.
 *
 * - filtre passe-haut : lp += (z - lp) * 0.2 ; hp = z - lp
 * - un tap est compté si |hp| > seuil ET si la médiane des 14 échantillons précédents
 *   est inférieure à 0,4 × seuil (rejette la marche et les gestes)
 * - période réfractaire de 90 ms après un tap
 * - une série se termine 450 ms après le dernier tap ; [onSeries] reçoit alors le nombre de taps
 * - après un contact avec l'écran, les taps sont ignorés pendant 400 ms
 *
 * Les temps sont en millisecondes, dans une base de temps quelconque mais croissante.
 * Non thread-safe : à appeler depuis un seul thread.
 */
class TapDetector(threshold: Float, private val onSeries: (Int) -> Unit) {

    companion object {
        const val HISTORY = 14
        const val REFRACTORY_MS = 90L
        const val SERIES_END_MS = 450L
        const val TOUCH_SUPPRESS_MS = 400L
        const val ALPHA = 0.2f
        const val QUIET_RATIO = 0.4f

        /** Seuil en m/s² pour une sensibilité de 1 à 10. */
        fun thresholdFor(sensitivity: Int): Float = 5.6f - 0.46f * sensitivity.coerceIn(1, 10)
    }

    @Volatile
    var threshold: Float = threshold

    private var lp = 0f
    private var initialized = false
    private val history = FloatArray(HISTORY)
    private val sorted = FloatArray(HISTORY)
    private var historyCount = 0
    private var historyPos = 0

    private var lastTapT = Long.MIN_VALUE / 4
    private var count = 0
    private var suppressUntil = Long.MIN_VALUE / 4
    private var lastT = 0L

    /** Traite un échantillon ; renvoie |hp| (utile pour la jauge). */
    fun process(tMs: Long, z: Float): Float {
        lastT = tMs
        if (!initialized) {
            lp = z
            initialized = true
        }
        lp += (z - lp) * ALPHA
        val hp = z - lp
        val a = abs(hp)

        if (count > 0 && tMs - lastTapT > SERIES_END_MS) {
            val c = count
            count = 0
            onSeries(c)
        }

        val thr = threshold
        if (historyCount == HISTORY &&
            a > thr &&
            tMs >= suppressUntil &&
            tMs - lastTapT >= REFRACTORY_MS &&
            median() < QUIET_RATIO * thr
        ) {
            lastTapT = tMs
            count++
        }

        history[historyPos] = a
        historyPos = (historyPos + 1) % HISTORY
        if (historyCount < HISTORY) historyCount++
        return a
    }

    /** L'utilisateur touche l'écran : on annule la série en cours et on ignore les taps 400 ms. */
    fun suppressForTouch() {
        suppressUntil = lastT + TOUCH_SUPPRESS_MS
        count = 0
    }

    fun reset() {
        initialized = false
        historyCount = 0
        historyPos = 0
        count = 0
        lastTapT = Long.MIN_VALUE / 4
        suppressUntil = Long.MIN_VALUE / 4
    }

    private fun median(): Float {
        System.arraycopy(history, 0, sorted, 0, HISTORY)
        sorted.sort()
        return (sorted[HISTORY / 2 - 1] + sorted[HISTORY / 2]) / 2f
    }
}

/** Action associée à une série de taps. */
enum class TapGesture { NONE, NEXT, BACK }

/** 2 taps : suivant ; 3 taps : retour. 1 tap est ignoré, 4 ou plus aussi (rafale = secousse, pas un geste). */
fun gestureFor(count: Int): TapGesture = when (count) {
    2 -> TapGesture.NEXT
    3 -> TapGesture.BACK
    else -> TapGesture.NONE
}
