package com.dictee.boucle

import android.view.MotionEvent
import androidx.appcompat.app.AppCompatActivity

/**
 * Écran relié au lecteur : écoute son état, le maintient actif tant qu'il est visible
 * et signale chaque contact avec l'écran (les taps au dos sont alors ignorés 400 ms).
 */
abstract class BaseActivity : AppCompatActivity(), PlayerService.Listener {

    /** true si l'écran a besoin du service (lecture, mains libres, réglages). */
    protected open val needsPlayer: Boolean = true

    override fun onStart() {
        super.onStart()
        PlayerService.addListener(this)
        if (needsPlayer) PlayerService.attach(this)
    }

    override fun onStop() {
        PlayerService.removeListener(this)
        if (needsPlayer) PlayerService.detach()
        super.onStop()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val a = ev.actionMasked
        if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_POINTER_DOWN) {
            PlayerService.notifyTouch()
        }
        return super.dispatchTouchEvent(ev)
    }
}
