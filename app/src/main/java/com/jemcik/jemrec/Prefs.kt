package com.jemcik.jemrec

import android.content.Context
import android.content.SharedPreferences

/**
 * The one preferences file.
 *
 * Every setting and flag in the app lives in it - the recorder switch, the
 * recording mode, the setup flags, the daemon token, the chosen folder - so a
 * Start fresh or a future migration has exactly one file to reason about. The
 * keys stay with the code that owns them; only the file is shared, and it used
 * to be named in eight places.
 */
internal object Prefs {
    const val FILE = "jemrec"

    fun of(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
