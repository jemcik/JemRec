package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent

internal object TestResultOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        report(
            appContext, "testresult",
            "${com.jemcik.jemrec.capture.CaptureDaemon.testResult(appContext)}",
        )
    }
}
