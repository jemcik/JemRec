package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.jemcik.jemrec.diag.DiagPrefs
import com.jemcik.jemrec.diag.EARPIECE_KEY

// Act as if the phone had an earpiece - an emulator has
// none - so a recording can be raised to an ear there:
// --ez on true|false. Kept across restarts; see
// DemoProvider. The screen reads it as it opens: open
// JemRec again after.
internal object EarpieceOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val on = intent.getBooleanExtra("on", true)
        DiagPrefs.of(appContext).edit { putBoolean(EARPIECE_KEY, on) }
        com.jemcik.jemrec.ui.EarWatch.actAsEarpiece = on
        report(appContext, "earpiece", "acting as if the phone had an earpiece: $on")
    }
}
