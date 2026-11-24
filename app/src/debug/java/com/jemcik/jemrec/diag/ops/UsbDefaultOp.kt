package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.recorder.DaemonClient

// The recorder's answer about Developer options' Default USB configuration:
// --ez set false (the default) reads it, --ez set true has it set to none.
// The emulator check reads the answer; a set may end the recorder after it.
internal object UsbDefaultOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val set = intent.getBooleanExtra("set", false)
        val reply = if (set) DaemonClient.usbDefaultNone(appContext) else DaemonClient.usbDefault(appContext)
        report(appContext, "usbdefault", "${if (set) "set" else "read"}: ${reply ?: "no answer"}")
    }
}
