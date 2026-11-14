package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent

// What THIS process reads of init's notes on adbd, to set
// against `getprop` from a shell: whether an app may read
// them at all is sepolicy's to say (AdbdState).
internal object AdbdOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val sp = com.jemcik.jemrec.system.SystemProperty
        val readStart = System.nanoTime()
        val state = com.jemcik.jemrec.system.AdbdState.read()
        val readMs = (System.nanoTime() - readStart) / 1_000_000.0
        report(
            appContext, "adbd",
            "init.svc.adbd=[${sp.read("init.svc.adbd")}] init.svc.zygote=[${sp.read("init.svc.zygote")}] " +
                "state=$state (${"%.1f".format(java.util.Locale.ROOT, readMs)} ms)",
        )
    }
}
