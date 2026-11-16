package com.jemcik.jemrec.playback

import android.content.Context
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs

/**
 * Settings' "Play recordings" (PlayThrough, AtTheEar): where a recording
 * plays. The loudspeaker with the earpiece at the ear is the default, as the
 * messengers that do this have it; the loudspeaker alone is for a proximity
 * sensor that a hand on the phone sets off; the earpiece is never switched
 * to the loudspeaker by itself. Stored by the choice's name.
 */
internal object PlayThroughSetting {

    private const val KEY = "play_through"

    fun of(context: Context): PlayThrough =
        Prefs.of(context).getString(KEY, null)
            ?.let { name -> PlayThrough.entries.firstOrNull { it.name == name } }
            ?: PlayThrough.LOUDSPEAKER_AND_EAR

    fun set(context: Context, playThrough: PlayThrough) {
        Prefs.of(context).edit { putString(KEY, playThrough.name) }
    }
}
