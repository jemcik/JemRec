package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.recorder.KeepPaired

// Keep JemRec paired, as the switch in Settings sets it:
// --ez on true|false. What became of it, and Android's
// timeout after: the emulator check reads both.
internal object KeepPairedOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val on = intent.getBooleanExtra("on", true)
        val outcome = KeepPaired.choose(appContext, on)
        report(appContext, "keeppaired", "keep paired $on: $outcome, timeout ${KeepPaired.timeoutMs(appContext)}")
    }
}
