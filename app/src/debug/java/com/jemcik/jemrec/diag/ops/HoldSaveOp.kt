package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.recordings.SaveHold

// The next save held at a point of it - --es at
// transfer|publish|publishing|published|done - for a
// minute, so the emulator checks can kill the app's
// process there (killed_mid_save); --ez die true ends
// the process there at once instead. This process
// only, once.
internal object HoldSaveOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val at = intent.getStringExtra("at").orEmpty()
        val point = SaveHold.At.entries.firstOrNull { it.name.equals(at, ignoreCase = true) }
        SaveHold.at = point
        SaveHold.die = intent.getBooleanExtra("die", false)
        report(appContext, "holdsave", if (point == null) "unknown point '$at'" else "the next save held at $at")
    }
}
