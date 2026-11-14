package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent

// Run the after-call audio check on any file, and post the
// notification a call with that verdict would get. For
// trying the thresholds on real recordings, on the phone,
// without the recording ever leaving it.
internal object AudioCheckOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val file = java.io.File(intent.getStringExtra("path").orEmpty())
        val uri = android.net.Uri.fromFile(file)
        val verdict = com.jemcik.jemrec.capture.AudioCheck.check(appContext, uri)
        // Only with --ez notify true: run over a folder of
        // recordings, one notification each is noise.
        if (intent.getBooleanExtra("notify", false)) com.jemcik.jemrec.calls.AfterCall.show(
            appContext,
            com.jemcik.jemrec.calls.AfterCall.Call(
                file.name, uri, file.absolutePath, 60.0, true,
                System.currentTimeMillis(), "Test call", verdict,
            ),
        )
        report(appContext, "audiocheck", "${file.name}: $verdict (levels in files/audiocheck.log)")
    }
}
