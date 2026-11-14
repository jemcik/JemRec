package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.adb.AdbTransport

internal object ConnectOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val port = intent.getIntExtra("port", -1)
        val result = if (port > 0) {
            AdbTransport.connect(appContext, AdbTransport.LOOPBACK, port)
        } else {
            AdbTransport.autoConnect(appContext, timeoutMs = 4_000)
        }
        report(
            appContext, "connect",
            result.fold({ "connected" }, { "connect failed: ${it.message}" }),
        )
    }
}
