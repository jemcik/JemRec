package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.capture.CaptureDaemon

internal object DaemonOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        report(
            appContext, "daemon",
            if (CaptureDaemon.isRunning(appContext)) "running" else "not running",
        )
    }
}
