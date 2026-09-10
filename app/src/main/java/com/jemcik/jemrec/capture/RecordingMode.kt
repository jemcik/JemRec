package com.jemcik.jemrec.capture

import android.content.Context
import com.jemcik.jemrec.Prefs

/**
 * Whether calls are recorded without asking, or only when the user says so.
 *
 * ON_DEMAND is honest about what it costs. Nothing is captured until the user
 * taps Record - the daemon is not connected to, no file is opened - so the
 * opening seconds of the call are genuinely not saved. The alternative would be
 * to record from the first moment and throw it away if the user declines, which
 * would keep those seconds but would mean the app records every call regardless
 * of what the switch says. For a call recorder that is the wrong trade: a
 * setting that says "ask me first" has to mean it.
 */
enum class RecordingMode {
    AUTOMATIC,
    ON_DEMAND;

    companion object {
        private const val KEY = "recording_mode"

        /** Automatic by default: it is what a call recorder is for, and the
         *  user chose to install one. */
        fun of(context: Context): RecordingMode = runCatching {
            val stored = Prefs.of(context)
                .getString(KEY, AUTOMATIC.name)
            valueOf(stored ?: AUTOMATIC.name)
        }.getOrDefault(AUTOMATIC)

        fun set(context: Context, mode: RecordingMode) {
            Prefs.of(context)
                .edit().putString(KEY, mode.name).apply()
        }
    }
}
