package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import com.jemcik.jemrec.adb.AdbTransport

// Act as a session that is not the phone's shell: every new adb session
// JemRec makes fails its proof (AdbTransport.prove). --es as wrong (answers
// at once, not with the value), silent (answers nothing before the proof's
// time is up) or none. For this process only.
internal object StandInOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val mode = intent.getStringExtra("as") ?: "wrong"
        AdbTransport.standIn = when (mode) {
            "wrong" -> "echo stand-in"
            "silent" -> "sleep ${AdbTransport.PROOF_MS / 1_000 + 5}"
            else -> null
        }
        report(appContext, "standin", "sessions acting as a stand-in: ${AdbTransport.standIn != null} ($mode)")
    }
}
