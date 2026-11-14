package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent

// How long a waveform takes to work out, one way or
// another, on the phone - nothing cached, nothing kept:
//   --es op waveform --es name <file name>
//   [--ei workers N] [--ez join false]
// One way per broadcast: a long call's can take longer
// than a broadcast may.
internal object WaveformOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val name = intent.getStringExtra("name").orEmpty()
        val recording = com.jemcik.jemrec.recordings.Recordings.list(appContext, withCallers = false)
            .firstOrNull { it.name == name }
        if (recording == null) {
            report(appContext, "waveform", "no recording named '$name'")
        } else {
            val started = android.os.SystemClock.elapsedRealtime()
            val levels = com.jemcik.jemrec.recordings.Waveforms.measure(
                appContext, recording.name, recording.uri,
                workers = intent.getIntExtra("workers", 0),
                join = intent.getBooleanExtra("join", true),
            )
            report(
                appContext, "waveform",
                // The levels themselves, to compare the ways of working them out.
                "$name: ${if (levels == null) "could not be worked out" else "levels " + com.jemcik.jemrec.recordings.Waveforms.toBytes(levels).joinToString(",")} " +
                    "in ${android.os.SystemClock.elapsedRealtime() - started} ms (timings in files/waveform.log)",
            )
        }
    }
}
