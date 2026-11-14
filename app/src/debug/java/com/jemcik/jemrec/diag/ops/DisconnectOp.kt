package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.adb.AdbTransport

// The ADB session dropped, as Wireless debugging going off
// drops it: for a `finish` with none to run over.
internal object DisconnectOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        AdbTransport.close()
        report(appContext, "disconnect", "closed")
    }
}
