package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.capture.CaptureDaemon

// Start the shell-side daemon from the jar bundled in this
// APK. The one operation that genuinely needs ADB.
internal object BootstrapOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val text = CaptureDaemon.ensureRunning(appContext).fold(
            { "daemon running on 127.0.0.1:${CaptureDaemon.PORT}" },
            { "bootstrap failed: ${it.message}" },
        )
        report(appContext, "bootstrap", text + "\n---\n" + CaptureDaemon.readLog())
    }
}
