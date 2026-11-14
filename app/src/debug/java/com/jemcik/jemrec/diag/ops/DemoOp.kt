package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.diag.DemoRecordings

// The README's screenshots: swap the real list for invented
// calls, or put it back. See DemoRecordings.
internal object DemoOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val on = intent.getBooleanExtra("on", false)
        DemoRecordings.set(appContext, on)
        report(
            appContext, "demo",
            if (on) "invented recordings on - reopen the app to see them"
            else "invented recordings off",
        )
    }
}
