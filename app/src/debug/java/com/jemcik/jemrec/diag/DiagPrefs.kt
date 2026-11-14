package com.jemcik.jemrec.diag

import android.content.Context
import android.content.SharedPreferences

/** The debug build's own preferences, apart from the app's: the receiver's
 *  token, the invented recordings, acting as if Android hid two settings.
 *  tools/shoot.py writes the file by its name with run-as. */
internal object DiagPrefs {

    private const val FILE = "jemrec_diag"

    fun of(context: Context): SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
}
