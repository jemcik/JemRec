package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent

// Recently deleted's emulator checks (tools/emulator_checks.py,
// recently_deleted and recently_deleted_folder): a recording
// moved to the trash, as the list's delete moves it, and
// where it is now - in the list, in Recently deleted, or in
// neither - after the daily pass removes what has waited its
// 30 days, if asked:
//   --es op trash --es name <file name> [--ez move true] [--ez purge true]
internal object TrashOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val name = intent.getStringExtra("name").orEmpty()
        val recordings = com.jemcik.jemrec.recordings.Recordings
        if (intent.getBooleanExtra("purge", false)) com.jemcik.jemrec.recordings.Trash.purgeExpired(appContext)
        val moved = if (intent.getBooleanExtra("move", false)) {
            recordings.list(appContext, withCallers = false).firstOrNull { it.name == name }
                ?.let { com.jemcik.jemrec.recordings.Trash.trashAll(appContext, listOf(it)) }
                ?.let { " (moved ${it.deleted}, failed ${it.failed}, asks ${it.needsConsent != null})" }
                ?: " (no recording named so)"
        } else {
            ""
        }
        val listed = recordings.list(appContext, withCallers = false).any { it.name == name }
        val waiting = com.jemcik.jemrec.recordings.Trash.list(appContext).filter { it.name == name }
        val trashed = waiting.isNotEmpty()
        val expires = waiting.joinToString(",") { "${it.expiresAtMillis}" }
        report(appContext, "trash", "$name: listed=$listed trashed=$trashed expires=$expires$moved")
    }
}
