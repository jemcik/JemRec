package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.capture.CallMonitorService

internal object MonitorStopOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        CallMonitorService.stop(appContext)
        kotlinx.coroutines.delay(1_000)
        report(appContext, "monitorstop", "monitor stop requested")
    }
}
