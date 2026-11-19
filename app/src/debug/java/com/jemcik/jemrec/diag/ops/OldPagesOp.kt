package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs

// The pass that gives recordings saved before 1.2 their Ogg Opus page
// positions (OldRecordingPages), run now and from the start: what each
// keep-alive run runs, for tools/emulator_checks.py's opus_valid.
//   --es op oldpages
internal object OldPagesOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        Prefs.of(appContext).edit { remove("old_pages_done_in") }
        com.jemcik.jemrec.recordings.OldRecordingPages.runIfDue(appContext)
        val through = com.jemcik.jemrec.recordings.OldRecordingPages.goneThrough(appContext)
        report(appContext, "oldpages", if (through) "every recording gone through" else "more left for the next turn")
    }
}
