package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent

// The answering machine, for tools/emulator_checks.py: a
// greeting from a raw 48 kHz mono PCM file the shell put
// where this app can read it, the switch on, and both
// handed to the recorder.
//   --es op answer --es greeting /data/local/tmp/jemrec-test-greeting.pcm
internal object AnswerOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val path = intent.getStringExtra("greeting")
        val text = runCatching {
            if (path != null) {
                com.jemcik.jemrec.answering.Greeting.save(appContext, java.io.File(path).readBytes())
            }
            com.jemcik.jemrec.answering.AnsweringMachine.setEnabled(appContext, intent.getBooleanExtra("on", true))
            // All of it: the recording mode a check set too.
            com.jemcik.jemrec.capture.CaptureDaemon.pushMode(appContext)
            val set = com.jemcik.jemrec.capture.CaptureDaemon.setAnswering(appContext)
            "greeting ${com.jemcik.jemrec.answering.Greeting.seconds(appContext)} s, " +
                "wire ${com.jemcik.jemrec.answering.AnsweringMachine.wire(appContext)}, recorder took it: $set, " +
                "recorder has ${com.jemcik.jemrec.capture.CaptureDaemon.greetingBytes(appContext)} bytes"
        }.getOrElse { "failed: $it" }
        report(appContext, "answer", text)
    }
}
