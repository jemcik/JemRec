package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent

// The calls since [since] that the missed-recording check and
// the self-test take for phone calls that went off-hook here,
// for tools/emulator_checks.py:
//   --es op answered --el since 1793016000000
internal object AnsweredOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val since = intent.getLongExtra("since", 0L)
        val calls = com.jemcik.jemrec.calls.CallLogLookup.answeredCallsSince(appContext, since)
        report(
            appContext, "answered",
            calls.joinToString("\n") { "${it.at} ${if (it.incoming) "in" else "out"} ${it.label}" }
                .ifEmpty { "none" },
        )
    }
}
