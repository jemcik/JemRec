package com.jemcik.jemrec.capture

import android.content.Context
import com.jemcik.jemrec.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A line, with the time, for each step of bringing the recorder back -
 * files/revive.log, in the debug and shrunk builds only; a release build keeps
 * nothing (R.bool.keeps_revive_log).
 *
 * Why it exists: on the Honor logcat's main buffer rolls over in about a
 * minute unless it is enlarged, and the daemon's own log ends where the daemon
 * is killed, so what the app does after a cable takes the recorder down, with
 * no one watching, was lost. Measured there, 2026-10-24 23:20, screen off: adbd
 * back 7 s after the unplug, the recorder only 4 minutes later, and nothing to
 * say where those minutes went. With this log the gap sat inside one quick
 * step, and the system's own log (HnFreezerController, PG_ash) showed the
 * phone freezing the app there (2026-10-25). Read it with
 * `run-as com.jemcik.jemrec cat files/revive.log` - or, in the shrunk build,
 * which is not debuggable, as root on an emulator.
 */
internal object ReviveLog {

    private const val FILE = "revive.log"
    private const val MAX_BYTES = 64 * 1024L
    private val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    /** Whether this build keeps the log at all. */
    fun on(context: Context): Boolean = context.resources.getBoolean(R.bool.keeps_revive_log)

    @Synchronized
    fun note(context: Context, line: String) {
        if (!on(context)) return
        runCatching {
            val file = File(context.filesDir, FILE)
            if (file.length() > MAX_BYTES) file.renameTo(File(context.filesDir, "$FILE.1"))
            file.appendText("${time.format(Date())} $line\n")
        }
    }
}
