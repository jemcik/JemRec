package com.jemcik.jemrec.ui

import android.app.Application
import android.content.IntentSender
import com.jemcik.jemrec.calls.AfterCall
import com.jemcik.jemrec.recordings.BulkDeleteResult
import com.jemcik.jemrec.recordings.DeleteResult
import com.jemcik.jemrec.recordings.Recording
import com.jemcik.jemrec.recordings.Trash
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Recently deleted's buttons, and Android's question for what it wants
 * confirmed before JemRec moves, restores or deletes it - asked here for
 * the list's delete and auto-delete too.
 */
internal class TrashActions(
    private val app: Application,
    private val state: MutableStateFlow<UiState>,
    private val log: ActionLog,
    private val actions: Actions,
    private val reading: Reading,
) {
    /** What Android's question is moving or deleting: the answer says only yes
     *  or no, and a yes takes their notifications with them. Not a restore. */
    private var leavingOnYes: List<String> = emptyList()

    /** Back out of the trash, as it was. */
    fun restore(recording: Recording) = actions.run(Action.RESTORE) {
        when (val result = withContext(Dispatchers.IO) { Trash.restore(app, recording) }) {
            is DeleteResult.Deleted -> log.say("restored ${recording.name}")
            is DeleteResult.NeedsConsent -> askAndroid(result.request)
            is DeleteResult.Failed -> log.say("could not restore ${recording.name}: ${result.error.message}")
        }
        reading.now()
    }

    /** Recently deleted's Restore all: every recording in it back in the list. */
    fun restoreAll() = actions.run(Action.RESTORE_ALL) {
        val all = state.value.trashed
        if (all.isEmpty()) return@run
        val result = withContext(Dispatchers.IO) { Trash.restoreAll(app, all) }
        report(all.size, result, done = { "restored $it" })
        reading.now()
    }

    fun askEmptyTrash() = state.update { it.copy(confirmingEmptyTrash = true) }

    fun cancelEmptyTrash() = state.update { it.copy(confirmingEmptyTrash = false) }

    /** Delete all for good, once asked: what is in Recently deleted is gone,
     *  and cannot be restored. When free, as the dialog's yes is not a tap
     *  to drop. */
    fun confirmEmptyTrash() {
        state.update { it.copy(confirmingEmptyTrash = false) }
        // What the question counted, read as it is answered: read after the
        // wait for busy, a recording trashed meanwhile went for good unasked.
        // Less what a yes still waiting its turn has: said yes to again
        // while busy, the second found its files gone, "could not" (Claims).
        val all = claims.take(state.value.trashed)
        if (all.isEmpty()) return
        actions.run(Action.DELETE_FOR_GOOD) {
            try {
                val result = withContext(Dispatchers.IO) { Trash.deleteForGood(app, all) }
                report(all.size, result, done = { "deleted for good $it" }, leaving = true)
                reading.now()
            } finally {
                claims.letGo(all)
            }
        }
    }

    private val claims = Claims()

    /** How a bulk restore or delete went - Recently deleted's and the list's
     *  own Delete - in the log, a failure in a snackbar too, and Android's
     *  question next for what it wants confirmed: counted as asked, not as
     *  failed. [done] and [couldNot] word each count; [leaving], the question
     *  takes their notifications with a yes. */
    fun report(
        of: Int,
        result: BulkDeleteResult,
        done: (Int) -> String,
        couldNot: (Int) -> String = { "could not do $it" },
        leaving: Boolean = false,
    ) {
        val asked = of - result.deleted - result.failed
        val summary = listOfNotNull(
            done(result.deleted).takeIf { result.deleted > 0 },
            "asking Android about $asked".takeIf { result.needsConsent != null && asked > 0 },
            couldNot(result.failed).takeIf { result.failed > 0 },
        ).joinToString(", ")
        if (result.failed > 0) log.tell(summary) else if (summary.isNotEmpty()) log.say(summary)
        result.needsConsent?.let { askAndroid(it, if (leaving) result.disputed else emptyList()) }
    }

    /** Android's question for [request], over whichever screen is up
     *  (MainScreen launches it); a yes takes [leaving]'s notifications. */
    fun askAndroid(request: IntentSender, leaving: List<String> = emptyList()) {
        leavingOnYes = leaving
        state.update { it.copy(deleteConsent = request) }
    }

    /** Android's dialog is up: the request is spent. Left set, a new screen -
     *  rotated, re-themed behind the dialog - launched it a second time. */
    fun onDeleteConsentShown() = state.update { it.copy(deleteConsent = null) }

    /** The system delete dialog has closed, one way or the other. */
    fun onDeleteConsentResult(granted: Boolean) {
        state.update { it.copy(deleteConsent = null) }
        if (!granted) log.say("deletion cancelled")
        reading.refresh()
        val left = leavingOnYes
        leavingOnYes = emptyList()
        if (granted) left.forEach { AfterCall.gone(app, it) }
    }
}
