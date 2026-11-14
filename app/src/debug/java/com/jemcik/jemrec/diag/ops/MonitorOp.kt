package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.capture.RecorderKeepAlive

// Arm the keep-alive job that revives the daemon, the way
// app-open and boot do. The permanent monitor service is
// gone; the daemon watches for calls itself.
internal object MonitorOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        RecorderKeepAlive.kickNow(appContext)
        kotlinx.coroutines.delay(2_000)
        report(appContext, "monitor", "keep-alive kicked; see the log")
    }
}
