package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.capture.CaptureDaemon

// Start without Wi-Fi, as this process sees it: the
// port property read with getprop in the APP's SELinux
// domain (sepolicy app.te allows it; run-as is another
// domain and proves nothing), whether the port answers,
// and what a revive would do about it.
internal object PortOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val sw = com.jemcik.jemrec.recorder.StartWithoutWifi
        // What one read costs the screen's refresh, which
        // makes it each time (ui.Reading).
        val readStart = System.nanoTime()
        val configured = sw.configuredPort()
        val readMs = (System.nanoTime() - readStart) / 1_000_000.0
        report(
            appContext, "port",
            "switch=${sw.enabled(appContext)} configured=$configured " +
                "(getprop ${"%.1f".format(java.util.Locale.ROOT, readMs)} ms) " +
                "answers=${sw.answers()} opened=${sw.openedThisBoot(appContext)} " +
                "failed=${sw.failedThisBoot(appContext)} step=${sw.step(appContext)} " +
                "due now=${CaptureDaemon.portChangeDue(appContext)}",
        )
    }
}
