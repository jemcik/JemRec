package com.jemcik.jemrec.adb

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Writes the last report somewhere `adb shell` can read it without root.
 *
 * logcat alone is not enough: it is a ring buffer, it interleaves with every
 * other process, and a report that scrolls away has to be reproduced by running
 * the test again. Under getExternalFilesDir the file survives, is readable from
 * a USB shell at /sdcard/Android/data/<pkg>/files/, and is deleted with the app.
 */
internal object ResultFile {

    private const val TAG = "JemRec"
    const val NAME = "diagnostics.txt"

    fun write(context: Context, text: String) {
        try {
            val dir = context.getExternalFilesDir(null) ?: run {
                Log.w(TAG, "no external files dir; report not written")
                return
            }
            File(dir, NAME).writeText(text)
            Log.i(TAG, "report written to ${File(dir, NAME).absolutePath}")
        } catch (t: Throwable) {
            Log.w(TAG, "could not write report", t)
        }
    }
}
