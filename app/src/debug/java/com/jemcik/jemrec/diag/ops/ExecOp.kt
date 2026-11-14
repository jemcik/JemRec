package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.adb.AdbTransport

internal object ExecOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val cmd = intent.getStringExtra("cmd").orEmpty()
        report(
            appContext, "exec",
            AdbTransport.exec(cmd).fold({ it }, { "exec failed: ${it.message}" }),
        )
    }
}
