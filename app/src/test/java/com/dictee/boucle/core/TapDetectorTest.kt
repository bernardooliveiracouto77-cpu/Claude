package com.dictee.boucle.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

class TapDetectorTest {

    private val dtMs = 5L // 200 Hz
    private val gravity = 9.81f

    /** Signal de base : téléphone immobile, léger bruit. */
    private fun quiet(durationMs: Long, seed: Long = 1): FloatArray {
        val r = Random(seed)
        val n = (durationMs / dtMs).toInt()
        return FloatArray(n) { gravity + (r.nextGaussian() * 0.05).toFloat() }
    }

    /** Ajoute un tap bref (impulsion amortie) à l'instant donné. */
    private fun addTap(signal: FloatArray, atMs: Long, amplitude: Float = 8f) {
        val i0 = (atMs / dtMs).toInt()
        val shape = floatArrayOf(1f, -0.65f, 0.3f, -0.12f, 0.05f)
        for (k in shape.indices) if (i0 + k < signal.size) signal[i0 + k] += amplitude * shape[k]
    }

    private fun run(
        signal: FloatArray,
        sensitivity: Int = 6,
        touchAtMs: Long? = null
    ): List<Int> {
        val series = mutableListOf<Int>()
        val d = TapDetector(TapDetector.thresholdFor(sensitivity)) { series += it }
        for (i in signal.indices) {
            val t = i * dtMs
            if (touchAtMs != null && t == touchAtMs) d.suppressForTouch()
            d.process(t, signal[i])
        }
        return series
    }

    @Test
    fun thresholdFormula() {
        assertEquals(2.84f, TapDetector.thresholdFor(6), 1e-4f)
        assertEquals(5.14f, TapDetector.thresholdFor(1), 1e-4f)
        assertEquals(1.0f, TapDetector.thresholdFor(10), 1e-4f)
    }

    @Test
    fun doubleTap() {
        val s = quiet(3000)
        addTap(s, 1000)
        addTap(s, 1230)
        assertEquals(listOf(2), run(s))
        assertEquals(TapGesture.NEXT, gestureFor(2))
    }

    @Test
    fun tripleTap() {
        val s = quiet(3000)
        addTap(s, 1000)
        addTap(s, 1200)
        addTap(s, 1410)
        assertEquals(listOf(3), run(s))
        assertEquals(TapGesture.BACK, gestureFor(3))
    }

    @Test
    fun singleTapIsIgnored() {
        val s = quiet(3000)
        addTap(s, 1000)
        val series = run(s)
        assertEquals(listOf(1), series)
        assertEquals(TapGesture.NONE, gestureFor(series.single()))
    }

    @Test
    fun twoSeparateDoubleTaps() {
        val s = quiet(5000)
        addTap(s, 1000); addTap(s, 1200)
        addTap(s, 3000); addTap(s, 3250)
        assertEquals(listOf(2, 2), run(s))
    }

    @Test
    fun tapsTooFarApartAreTwoSingles() {
        val s = quiet(4000)
        addTap(s, 1000)
        addTap(s, 1700)
        assertEquals(listOf(1, 1), run(s))
    }

    @Test
    fun weakTapsNeedHigherSensitivity() {
        val s = quiet(3000)
        addTap(s, 1000, amplitude = 2.5f)
        addTap(s, 1200, amplitude = 2.5f)
        assertTrue(run(s, sensitivity = 1).isEmpty())
        assertEquals(listOf(2), run(s, sensitivity = 10))
    }

    @Test
    fun touchOnScreenSuppressesTaps() {
        val s = quiet(3000)
        addTap(s, 1000)
        addTap(s, 1200)
        // Contact écran juste après le premier tap : la série est annulée et le 2e tap ignoré.
        assertTrue(run(s, touchAtMs = 1020).isEmpty())
    }

    @Test
    fun fourTapsOrMoreAreIgnored() {
        val s = quiet(3000)
        addTap(s, 1000); addTap(s, 1180); addTap(s, 1360); addTap(s, 1540)
        val series = run(s)
        assertEquals(listOf(4), series)
        assertEquals(TapGesture.NONE, gestureFor(4))
    }

    /**
     * Marche, téléphone tenu en main : balancement du corps (~1,9 Hz), vibration continue et
     * petits chocs de talon. L'agitation permanente maintient la médiane au-dessus de 0,4 × seuil,
     * donc aucun geste ne doit être détecté.
     */
    @Test
    fun walkingIsRejected() {
        for (seed in listOf(1L, 7L, 42L, 99L)) {
            val r = Random(seed)
            val n = (20_000L / dtMs).toInt()
            val s = FloatArray(n)
            for (i in 0 until n) {
                val t = i * dtMs.toDouble()
                var z = gravity + 3.0 * sin(2 * PI * t / 520.0) + 4.0 * sin(2 * PI * t / 45.0) +
                    r.nextGaussian() * 0.3
                val sinceStep = t % 520.0
                z += 3.0 * exp(-sinceStep / 35.0) * sin(2 * PI * sinceStep / 60.0)
                s[i] = z.toFloat()
            }
            val series = run(s)
            assertTrue("faux gestes pendant la marche (graine $seed) : $series",
                series.none { gestureFor(it) != TapGesture.NONE })
        }
    }

    @Test
    fun handMovementIsRejected() {
        // Geste ample (on prend / repose le téléphone) avec tremblement : pas de geste.
        val s = quiet(4000)
        for (i in s.indices) {
            val t = i * dtMs
            if (t in 1000..1600) {
                val env = sin(PI * (t - 1000) / 600.0).let { it * it }
                s[i] += (env * (6 * sin(2 * PI * (t - 1000) / 600.0) + 2.5 * sin(2 * PI * t / 40.0))).toFloat()
            }
        }
        val series = run(s)
        assertTrue("faux gestes : $series", series.none { gestureFor(it) != TapGesture.NONE })
    }

    @Test
    fun tripleTapBehavesLikeSpotify() {
        // Lecture bien entamée : retour au début de la partie en cours.
        assertEquals(BackAction.RESTART_PART, backAction(5, 8000, playing = true))
        // Dans les premières secondes : partie précédente.
        assertEquals(BackAction.PREVIOUS_PART, backAction(5, 1200, playing = true))
        assertEquals(BackAction.PREVIOUS_PART, backAction(5, RESTART_WINDOW_MS, playing = true))
        // Première partie : on ne peut que la relire.
        assertEquals(BackAction.RESTART_PART, backAction(0, 500, playing = true))
        // En pause : on relance la partie en cours.
        assertEquals(BackAction.RESTART_PART, backAction(5, 500, playing = false))
    }
}
