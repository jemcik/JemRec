package com.jemcik.jemrec

import android.content.Context
import android.content.SharedPreferences

/**
 * The app's preferences file.
 *
 * Every setting and flag in the app lives in it - the recorder switch, the
 * recording mode, the setup flags, the daemon token, the chosen folder - so a
 * Start fresh or a future migration has one file to reason about. The keys
 * stay with the code that owns them; only the file is shared, and it used to
 * be named in eight places. Two files are kept apart from it, neither a
 * setting: the callers' colours (CallerColours) and the recordings whose tags
 * have been read (RecordingTags).
 */
internal object Prefs {
    const val FILE = "jemrec"

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /**
     * Calls [onChange] whenever [key] changes, from anywhere in the process -
     * for a screen that must hear of what the Quick Settings tile changes over
     * it without pausing (RecorderSwitch, DebugSwitchesOff).
     *
     * Returns the way to stop, which also holds the listener: preferences keep
     * only a weak reference to it. A no-op where there are no preferences, as
     * in the JVM tests.
     */
    fun watch(context: Context, key: String, onChange: () -> Unit): () -> Unit = runCatching {
        val prefs = of(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, changed ->
            if (changed == key) onChange()
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        val stop: () -> Unit = { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        stop
    }.getOrElse { {} }
}
