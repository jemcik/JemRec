package com.jemcik.jemrec.recordings

import android.app.PendingIntent
import android.content.Context
import android.util.Log
import com.jemcik.jemrec.DeepLink
import com.jemcik.jemrec.calls.AfterCall
import com.jemcik.jemrec.calls.CallTracking

/**
 * A saved call whose recording stopped well before the call did, said in a
 * notification - where until 1.2 it was found out only by listening: the
 * capture failing or stalling and never coming back (RestartPolicy,
 * StallWatch), the encoder ended, or the recorder ended. Known two ways: a
 * phone call's last packet against the end Telecom gave it (check: any
 * cause), or the recorder's own mark in the file's name, where the recording
 * ended or stopped for good before the call (RecordingLoss; marked: a
 * messenger call, a message, a phone recording holding more than one
 * tracked call, or a phone call with call tracking off, which
 * Telecom tells nothing of - review, 2026-11-18). A recorder ended mid-call
 * leaves no mark, so for those it goes unsaid. Not "Copy a
 * report says why": a recorder ended mid-call writes no report of the call,
 * and Copy a report gave the call before's. One saved long after its call -
 * collected days on, once the recorder was back - is named by its time, not
 * called the last call.
 */
internal object StoppedEarly {

    private const val TAG = "JemRec"

    /** Among the problems' ids (FolderAccess' 36 and those before it). */
    private const val NOTIFICATION_ID = 37

    /** Past this since the call ended, it is named by its time. As
     *  AfterCall's own notice's (a while ago, history, not news). */
    private const val FRESH_MS = 30L * 60 * 1000

    /** A recording that stopped early, to be said once it is saved: its
     *  call's [start], how much it [missed], when its call [endedAt]. Decided
     *  before the save publishes - so a save cut short there, finished by
     *  the next (SaveJournal), says it too (review round 9). */
    data class Early(
        val start: Long,
        val missed: Long,
        val endedAt: Long,
        /** Another recording carries the call on: not said - but the
         *  recording is a part of the call, no short call (review round
         *  15: one carried on was judged one). */
        val carried: Boolean = false,
    )

    /** [others]: the starts of the other recordings known - saved, or
     *  waiting on the recorder - one of which may carry the call on
     *  (SavePlan.carriedOn). */
    fun check(start: Long, lastSlot: Long, endedAt: Long, others: Collection<Long>): Early? {
        val missed = SavePlan.stoppedEarlyMs(start, lastSlot, endedAt) ?: return null
        if (SavePlan.carriedOn(start, lastSlot, endedAt, others)) {
            Log.i(TAG, "save: the recording stopped ${missed} ms before the call ended; another recording carries it on")
            return Early(start, missed, endedAt, carried = true)
        }
        return Early(start, missed, endedAt)
    }

    /** One the recorder marked, ended or stopped for good [missedMs] before
     *  the recording was stopped (DaemonFile.missedMs), over STOPPED_EARLY_MS
     *  (SavePlan.markedEarlyMs); [lastSlot] its last packet, from which the
     *  call's end is reckoned. No other recording can carry such a call on:
     *  the recorder stays in it, holding it as its own, until it ends. */
    fun marked(start: Long, lastSlot: Long, missedMs: Long): Early =
        Early(start, missedMs, start + (lastSlot + 1) * CallTracking.PACKET_MS + missedMs)

    /** Said, [now] on the saver's clock - not one carried on
     *  ([Early.carried]). */
    fun tell(context: Context, early: Early, now: Long) = if (early.carried) Unit else say(context, early.start, early.missed, early.endedAt, now)

    private fun say(context: Context, start: Long, missed: Long, endedAt: Long, now: Long) {
        val seconds = (missed + 500) / 1000
        Log.w(TAG, "save: the recording stopped ${missed} ms before the call ended")
        runCatching {
            val tap = PendingIntent.getActivity(context, 0, DeepLink.home(context), PendingIntent.FLAG_IMMUTABLE)
            val which = if (now - endedAt <= FRESH_MS) {
                "your last call"
            } else {
                "your call of " + android.text.format.DateUtils.formatDateTime(
                    context, start,
                    android.text.format.DateUtils.FORMAT_SHOW_DATE or android.text.format.DateUtils.FORMAT_SHOW_TIME,
                )
            }
            val text = "The recording of $which stopped about ${seconds} s before the call ended, so its " +
                "last part was not recorded: the recorder was ended, or the call's sound could not " +
                "be taken."
            AfterCall.notifyProblem(
                context, NOTIFICATION_ID,
                AfterCall.problem(context, "Recording stopped early", text, tap).build(),
                "save",
            )
        }.onFailure { Log.w(TAG, "save: could not say the recording stopped early", it) }
    }
}
