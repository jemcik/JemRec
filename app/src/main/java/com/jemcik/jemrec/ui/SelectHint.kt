package com.jemcik.jemrec.ui

import android.content.Context
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs

/** The list's tip that a photo's tap selects: shown until selection has been
 *  used once, or the tip closed. The owner of its one setting. */
internal object SelectHint {

    private const val KEY = "select_hint_seen"

    fun seen(context: Context): Boolean = Prefs.of(context).getBoolean(KEY, false)

    fun markSeen(context: Context) {
        Prefs.of(context).edit { putBoolean(KEY, true) }
    }
}
