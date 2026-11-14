package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.diag.ReviveLater

// A routine revive once adbd stops (ReviveLater), for the
// emulator check that stops it.
internal object ReviveLaterOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        ReviveLater.schedule(appContext)
        report(appContext, "revivelater", "scheduled: a revive once adbd reads stopped")
    }
}
