package com.jemcik.jemrec.recordings

import android.content.Context
import android.util.Log
import com.jemcik.jemrec.calls.AfterCall
import com.jemcik.jemrec.rethrowingCancellation
import com.jemcik.jemrec.calls.CallLedger
import com.jemcik.jemrec.calls.MissedCalls
import com.jemcik.jemrec.recordings.RecordingSaver.Outcome

/**
 * Do what [by]'s row of the table (AfterSave.row) says for [outcome] of
 * [recfile], a call [incoming] or not; [tell] is how the user hears that a
 * call went unrecorded - the hang-up's own notification says so as well
 * (CallMonitorService). Blocking: the after-call notice decodes the file.
 */
internal suspend fun AfterSave.react(
    context: Context,
    outcome: Outcome,
    recfile: String,
    incoming: Boolean,
    by: AfterSave.Collector,
    tell: (String) -> Unit,
) {
    val row = row(kindOf(outcome), by)
    if (row.failing == AfterSave.Failing.CLEAR) RecordingSaver.noLongerFailing(context, recfile)
    row.log?.let { line -> if (row.warn) Log.w(TAG, line(recfile)) else Log.i(TAG, line(recfile)) }
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
    when (row.tell) {
        AfterSave.Tell.REASON -> tell((outcome as Outcome.Failed).reason)
        AfterSave.Tell.NO_AUDIO -> tell(AfterCall.NO_AUDIO)
        AfterSave.Tell.GREETING_FAILED -> tell(AfterCall.GREETING_FAILED)
        null -> Unit
    }
    if (row.noteEmpty) RecordingSaver.startMillis(recfile)?.let { CallLedger.noteRecording(context, it, incoming, 0) }
    if (row.failing == AfterSave.Failing.COUNT) RecordingSaver.failedAgain(context, recfile, (outcome as Outcome.Failed).reason)
}

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
