package com.jemcik.jemrec.diag.ops

import android.app.NotificationManager
import android.content.Context
import android.content.Intent

// Every notification of JemRec's taken down: a check that
// looks for one of its own - "Recording stopped early",
// one id for every call - is not answered by the check
// before's (emulator_checks.py, stopped_early and
// early_end; review round 9). A service's own is not
// taken: cancelAll leaves a foreground service's.
internal object ClearNotesOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        appContext.getSystemService(NotificationManager::class.java).cancelAll()
        report(appContext, "clearnotes", "taken down")
    }
}
