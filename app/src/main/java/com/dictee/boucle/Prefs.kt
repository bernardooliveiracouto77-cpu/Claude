package com.dictee.boucle

import android.content.Context
import android.content.SharedPreferences
import com.dictee.boucle.core.PartSize
import com.dictee.boucle.core.TapDetector

/** Texte, réglages et position, sauvegardés dans les SharedPreferences. */
class Prefs(context: Context) {

    val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences("dictee_en_boucle", Context.MODE_PRIVATE)

    companion object {
        const val K_CONTENT = "content"
        const val K_IS_HTML = "is_html"
        const val K_PART_INDEX = "part_index"
        const val K_PART_SIZE = "part_size"
        const val K_RATE = "rate"
        const val K_SENTENCE_PAUSE = "sentence_pause_ms"
        const val K_COMMA_ENABLED = "comma_pause_enabled"
        const val K_COMMA_PAUSE = "comma_pause_ms"
        const val K_LOOP_PAUSE = "loop_pause_ms"
        const val K_VOICE = "voice_name"
        const val K_TRIPLE_FIRST = "triple_goes_to_first"
        const val K_SENSITIVITY = "sensitivity"
        const val K_ASKED_NOTIF = "asked_notifications"
    }

    /** Texte source : HTML (si collé depuis une source riche) ou texte brut. */
    var content: String
        get() = sp.getString(K_CONTENT, "") ?: ""
        set(v) = sp.edit().putString(K_CONTENT, v).apply()

    var isHtml: Boolean
        get() = sp.getBoolean(K_IS_HTML, false)
        set(v) = sp.edit().putBoolean(K_IS_HTML, v).apply()

    fun setText(content: String, isHtml: Boolean) {
        sp.edit().putString(K_CONTENT, content).putBoolean(K_IS_HTML, isHtml).apply()
    }

    var partIndex: Int
        get() = sp.getInt(K_PART_INDEX, 0)
        set(v) = sp.edit().putInt(K_PART_INDEX, v).apply()

    var partSize: PartSize
        get() = PartSize.fromKey(sp.getString(K_PART_SIZE, PartSize.TWO_THREE.key))
        set(v) = sp.edit().putString(K_PART_SIZE, v.key).apply()

    var rate: Float
        get() = sp.getFloat(K_RATE, 0.7f)
        set(v) = sp.edit().putFloat(K_RATE, v).apply()

    var sentencePauseMs: Int
        get() = sp.getInt(K_SENTENCE_PAUSE, 1500)
        set(v) = sp.edit().putInt(K_SENTENCE_PAUSE, v).apply()

    var commaPauseEnabled: Boolean
        get() = sp.getBoolean(K_COMMA_ENABLED, true)
        set(v) = sp.edit().putBoolean(K_COMMA_ENABLED, v).apply()

    var commaPauseMs: Int
        get() = sp.getInt(K_COMMA_PAUSE, 600)
        set(v) = sp.edit().putInt(K_COMMA_PAUSE, v).apply()

    var loopPauseMs: Int
        get() = sp.getInt(K_LOOP_PAUSE, 3000)
        set(v) = sp.edit().putInt(K_LOOP_PAUSE, v).apply()

    /** Nom de la voix choisie, ou null pour la voix française par défaut. */
    var voiceName: String?
        get() = sp.getString(K_VOICE, null)
        set(v) = sp.edit().putString(K_VOICE, v).apply()

    var tripleGoesToFirst: Boolean
        get() = sp.getBoolean(K_TRIPLE_FIRST, false)
        set(v) = sp.edit().putBoolean(K_TRIPLE_FIRST, v).apply()

    var sensitivity: Int
        get() = sp.getInt(K_SENSITIVITY, 6).coerceIn(1, 10)
        set(v) = sp.edit().putInt(K_SENSITIVITY, v.coerceIn(1, 10)).apply()

    val tapThreshold: Float get() = TapDetector.thresholdFor(sensitivity)

    var askedBattery: Boolean
        get() = sp.getBoolean("asked_battery", false)
        set(v) = sp.edit().putBoolean("asked_battery", v).apply()

    var askedNotifications: Boolean
        get() = sp.getBoolean(K_ASKED_NOTIF, false)
        set(v) = sp.edit().putBoolean(K_ASKED_NOTIF, v).apply()
}
