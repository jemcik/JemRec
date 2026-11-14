package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.LastCallReport
import com.jemcik.jemrec.ui.appVersion

// What Copy a report on Diagnostics copies (LastCallReport).
internal object CallReportOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val recorder = CaptureDaemon.callReport(appContext)
        report(appContext, "callreport", LastCallReport.text(appContext, appVersion(appContext), recorder))
    }
}
