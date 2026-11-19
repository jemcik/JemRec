package com.jemcik.jemrec.playback

import android.content.Context
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs

/**
 * Settings' "Both sides in both ears" (BothEars): on until switched off, for
 * an update too - a call heard as a call, not one person an ear (discussion
 * #92). The recording itself keeps the sides apart either way. PlaybackService
 * watches [KEY], so a change moves what is playing at once.
 */
internal object BothEarsSetting {

    const val KEY = "both_ears"

    fun of(context: Context): Boolean = Prefs.of(context).getBoolean(KEY, true)

    fun set(context: Context, on: Boolean) {
        Prefs.of(context).edit { putBoolean(KEY, on) }
    }
}
