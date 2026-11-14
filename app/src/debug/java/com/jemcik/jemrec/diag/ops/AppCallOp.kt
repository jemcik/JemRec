package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.diag.FakeAppCall

// A stand-in for a Google Meet call, for tools/emulator_checks.py:
//   --es op appcall --es do start|end
internal object AppCallOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val text = runCatching {
            if (intent.getStringExtra("do") == "end") FakeAppCall.end() else FakeAppCall.start(appContext)
        }.getOrElse { "failed: $it" }
        report(appContext, "appcall", text)
    }
}
