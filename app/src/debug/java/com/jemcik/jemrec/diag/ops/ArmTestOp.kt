package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent

// The answering machine's test call, straight to the
// recorder as the screen's Test call asks it, and what it
// found - no result or route of the screen's changes; the
// call itself is noted as it ends, as any test's is
// (tools/emulator_checks.py, test_call_wifi):
//   --es op armtest --el arming 42
//   --es op testresult
internal object ArmTestOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        report(
            appContext, "armtest",
            "${com.jemcik.jemrec.capture.CaptureDaemon.armTest(
                appContext, incoming = true, arming = intent.getLongExtra("arming", 0),
            )}",
        )
    }
}
