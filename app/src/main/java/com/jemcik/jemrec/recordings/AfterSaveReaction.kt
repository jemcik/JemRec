package com.jemcik.jemrec.recordings

import android.content.Context
import android.util.Log
import com.jemcik.jemrec.calls.AfterCall
import com.jemcik.jemrec.calls.CallLedger
import com.jemcik.jemrec.calls.MissedCalls
import com.jemcik.jemrec.recordings.RecordingSaver.Outcome
import com.jemcik.jemrec.rethrowingCancellation

/**
 * Do what [by]'s row of the table (AfterSave.row) says for [outcome] of
 * [recfile], a call [incoming] or not; [tell] is how the user hears that a
 * call went unrecorded - the hang-up's own notification says so as well
 * (CallMonitorService). Blocking: the after-call notice decodes the file.
 * [alertUp]: "Call not recorded" posted already by this call's reaction.
 * True when this row told.
 */
internal suspend fun AfterSave.react(
    context: Context,
    outcome: Outcome,
    recfile: String,
    incoming: Boolean,
    by: AfterSave.Collector,
    alertUp: Boolean = false,
    tell: (String) -> Unit,
): Boolean {
    val row = row(kindOf(outcome), by)
    if (row.failing == AfterSave.Failing.CLEAR) RecordingSaver.noLongerFailing(context, recfile)
    row.log?.let { line -> if (row.warn) Log.w(TAG, line(recfile)) else Log.i(TAG, line(recfile)) }
    // Saved after all: a failure told of this file goes (review round 15:
    // "Call not recorded" stayed up after a later collection saved it).
    if (outcome is Outcome.Saved) AfterCall.savedAfterAll(context, recfile)
    // Dropped: never to be saved, so no longer among them either (review
    // round 19). Not Gone - another collector's save, whose own
    // savedAfterAll takes it out and the alert down - nor Busy.
    if (outcome in DROPPED) AfterCall.neverSaved(context, recfile)
    if (row.notice && outcome is Outcome.Saved) {
        // The saved part's own call - with call waiting, the last of them -
        // not the recording's.
        runCatching { AfterCall.saved(context, outcome, outcome.incoming, outcome.startMillis) }
            .onFailure { Log.w(TAG, "${by.tag}: after-call notice failed", it) }
    }
    if (row.missedCheck) {
        runCatching { MissedCalls.check(context) }.rethrowingCancellation()
            .onFailure { Log.w(TAG, "${by.tag}: missed-call check failed", it) }
    }
    // What a failure told is about: taken down if it is saved after all.
    if (row.tell != null) {
        AfterCall.notRecordedAbout(context, recfile, retried = row.tell == AfterSave.Tell.REASON, upAlready = alertUp)
    }
    when (row.tell) {
        AfterSave.Tell.REASON -> tell((outcome as Outcome.Failed).reason)
        AfterSave.Tell.NO_AUDIO -> tell(AfterCall.NO_AUDIO)
        AfterSave.Tell.GREETING_FAILED -> tell(AfterCall.GREETING_FAILED)
        null -> Unit
    }
    if (row.noteEmpty) RecordingSaver.startMillis(recfile)?.let { CallLedger.noteRecording(context, it, incoming, 0) }
    if (row.failing == AfterSave.Failing.COUNT) RecordingSaver.failedAgain(context, recfile, outcome as Outcome.Failed)
    return row.tell != null
}

/**
 * A call's files saved together (RecordingSaver.saveAll), reacted to as one
 * call: the one told (AfterSave.toldOf) by [by]'s rows, last - its notice is
 * the call's - and the rest by [partBy]'s (PART, a failure said by
 * [partTell]; PART_LATER, said nowhere). Told one by one, an empty part said "Call not recorded" of a call
 * with minutes saved, and the notice was whichever finished last (review
 * rounds 12 to 14). [outcomes]: each file, its way, its outcome.
 */
internal suspend fun AfterSave.reactToCall(
    context: Context,
    outcomes: List<Triple<String, Boolean, Outcome>>,
    own: String?,
    by: AfterSave.Collector,
    tell: (String) -> Unit,
    partBy: AfterSave.Collector = AfterSave.Collector.PART,
    partTell: (String) -> Unit = {},
) {
    if (outcomes.isEmpty()) return
    val told = AfterSave.toldOf(
        outcomes.map { (file, _, outcome) -> Triple(file, kindOf(outcome), (outcome as? Outcome.Saved)?.seconds ?: 0.0) },
        own,
    )
    // After a tell the alert is up, listed by Android or not yet (react).
    var posted = false
    outcomes.sortedBy { it.first == told }.forEach { (file, way, outcome) ->
        val said = if (file == told) {
            react(context, outcome, file, way, by, posted, tell)
        } else {
            react(context, outcome, file, way, partBy, posted, partTell)
        }
        posted = posted || said
    }
}

/** What ends a file unsaved, its copy dropped from the recorder. */
private val DROPPED = setOf(Outcome.Empty, Outcome.Skipped, Outcome.NotAnswered, Outcome.GreetingHangUp)

internal fun kindOf(outcome: Outcome): AfterSave.Kind = when (outcome) {
    is Outcome.Saved -> AfterSave.Kind.SAVED
    Outcome.Empty -> AfterSave.Kind.EMPTY
    Outcome.Gone -> AfterSave.Kind.GONE
    Outcome.Busy -> AfterSave.Kind.BUSY
    Outcome.Skipped -> AfterSave.Kind.SKIPPED
    Outcome.NotAnswered -> AfterSave.Kind.NOT_ANSWERED
    Outcome.GreetingHangUp -> AfterSave.Kind.GREETING_HANG_UP
    Outcome.GreetingFailed -> AfterSave.Kind.GREETING_FAILED
    is Outcome.Failed -> AfterSave.Kind.FAILED
}

private const val TAG = "JemRec"
