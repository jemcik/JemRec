package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.capture.CaptureDaemon

internal object CheckOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        CaptureDaemon.revive(appContext)
        report(
            appContext, "check",
            if (CaptureDaemon.isRunning(appContext)) "daemon is running"
            else "daemon still down",
        )
    }
}
