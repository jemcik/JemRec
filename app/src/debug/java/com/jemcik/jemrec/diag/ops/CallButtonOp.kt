package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.calls.CallButton
import com.jemcik.jemrec.recorder.DaemonClient

// Ask first's button over calls, as Settings' switch turns it on or off: the
// preference, and the recorder asked to allow or take back "Display over
// other apps". --ez on true, the default, or false; with --ez ask false the
// recorder is not asked, as where it is down. Says what it answered, and what
// Android now says.
internal object CallButtonOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val on = intent.getBooleanExtra("on", true)
        CallButton.setEnabled(appContext, on)
        val answer = if (intent.getBooleanExtra("ask", true)) DaemonClient.setOverlay(appContext, on) else "not asked"
        report(appContext, "callbutton", "${if (on) "on" else "off"}: recorder $answer, allowed ${CallButton.allowed(appContext)}")
    }
}
