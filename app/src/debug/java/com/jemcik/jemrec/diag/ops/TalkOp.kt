package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent

// Talk to them, as the "Taking a message" notification's
// button does, for tools/emulator_checks.py:
//   --es op talk
internal object TalkOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val ok = com.jemcik.jemrec.capture.CaptureDaemon.handOver(appContext, true)
        report(appContext, "talk", "talk to them -> $ok")
    }
}
