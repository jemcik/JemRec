package com.jemcik.jemrec.calls

import com.jemcik.jemrec.recordings.DaemonFile
import com.jemcik.jemrec.recordings.Recording

/**
 * Which call a recording is, and which recording a call has: the rules the
 * call log's names for recordings (CallLogLookup), the Calls tab
 * (CallHistory) and the missed-call check (MissedCalls) share, each written
 * once. No phone in it: what to match against is handed in.
 */
internal object CallMatch {

    /** Ringing can be long. Being answered cannot happen before dialling. */
    const val BEFORE_MS = 3 * 60 * 1000L
    const val AFTER_MS = 30 * 1000L

    /** A call during a recording - call waiting - lands in that recording. */
    const val SPAN_SLACK_MS = 5_000L

    /** Whether a call the log dates at [callAt] can be the one a recording
     *  started at [recordingAt] belongs to. The same window labels the list. */
    fun sameCall(callAt: Long, recordingAt: Long): Boolean =
        callAt in (recordingAt - BEFORE_MS)..(recordingAt + AFTER_MS)

    /**
     * Of [items], the closest to [at] that went the same way and is inside
     * the window, [time] and [incomingOf] saying when each was and which way.
     * Direction matters: two calls a minute apart are common, and one of
     * them being the wrong way round is the obvious way to label a recording
     * with the wrong person. A call dated after [at] - [LATE_MS] on - only
     * when none dated before it is in the window: the log dates a call by
     * its creation (AOSP CallLogManager: the creation time, or a connect
     * time only when that is earlier), before its recording begins - an
     * incoming call's ring start. Nearest alone, a waiting call ringing 15 s
     * after the answer took the entry of a call answered after a 25 s ring,
     * the call then "not recorded" (review round 13).
     */
    fun <T> closest(items: List<T>, at: Long, incoming: Boolean, time: (T) -> Long, incomingOf: (T) -> Boolean): T? =
        items
            .filter { incomingOf(it) == incoming && sameCall(time(it), at) }
            .minWithOrNull(compareBy<T>({ time(it) > at + LATE_MS }, { kotlin.math.abs(time(it) - at) }))

    /** How far after a recording's start its own call's date can be: the
     *  clocks' noise, and a name's start cut to the second. */
    const val LATE_MS = 3_000L

    /** The closest call-log entry for a recording at [at] (see [closest]). */
    fun closest(entries: List<CallLogLookup.Entry>, at: Long, incoming: Boolean): CallLogLookup.Entry? =
        closest(entries, at, incoming, { it.at }, { it.incoming })

    /**
     * Closest entry for a recording at [at], leaving out the entries that are
     * other recordings' calls.
     *
     * Asked right after a hang-up, the log may not have this call yet, and
     * then the closest entry inside the window is the call BEFORE it - up to
     * three minutes earlier. Naming this call after that one, or keeping or
     * deleting it by that one's rule, is the mistake: so an entry another
     * recording's start matches better is not this call's, and with nothing
     * else in the window the answer is "not yet".
     */
    fun unclaimed(
        entries: List<CallLogLookup.Entry>,
        at: Long,
        incoming: Boolean,
        others: List<CallLedger.Recorded>,
    ): CallLogLookup.Entry? {
        // Claimed only by a start nearer the entry than this one is, as said
        // above: closest alone, a recording joined mid-call - the rest of
        // this very call, saved before it - took this call's entry from the
        // recording of its start, kept then unnamed and unruled (review
        // round 11).
        // A part joined mid-call claims from its call's start, as it
        // accounts for it (review round 14) - but nothing from a recording
        // begun inside that call, before it: the call's own first part,
        // which it beat from 0 ms off the log's date (round 11's bug, back
        // in round 14; review round 15).
        val claimed = others
            .filter { it.startMillis != at }
            .filterNot { it.callAt >= 0 && at in (it.callAt - LATE_MS)..it.startMillis }
            .mapNotNullTo(HashSet()) { other ->
                val from = if (other.callAt >= 0) other.callAt else other.startMillis
                closest(entries, from, other.incoming)
                    ?.takeIf { kotlin.math.abs(it.at - from) < kotlin.math.abs(it.at - at) }
            }
        return closest(entries.filterNot { it in claimed }, at, incoming)
    }

    /**
     * The call with [number] that a recording starting at [at] fell inside:
     * from the log's date to its end, the way Honor's Phone app matches its
     * own recordings (see FileNames.build). Compared digit for digit, as
     * PhoneNumbers.digits wrote the number into the name.
     */
    fun withNumber(entries: List<CallLogLookup.Entry>, number: String, at: Long): CallLogLookup.Entry? {
        val wanted = PhoneNumbers.digits(number) ?: return null
        return entries
            .filter { PhoneNumbers.digits(it.number) == wanted }
            .filter { at in (it.at - AFTER_MS)..(it.at + it.seconds * 1000L + AFTER_MS) }
            .minByOrNull { kotlin.math.abs(it.at - at) }
    }

    /**
     * The calls of [calls] that ledger entries [entries] account for, [time],
     * [seconds] and [incomingOf] saying when each call was, how long the log
     * says it lasted and which way. Each entry its own call: the closest of
     * its way inside the window ([closest]) - from the call's start where
     * the saver found one (a part joined mid-call,
     * [CallLedger.Recorded.callAt]), else its own - one call, as [assign]
     * gives a recording one. Taking every call of its way inside the window,
     * a redial's entry accounted for the call before it, two minutes
     * earlier, and so did any entry begun in a call's span: its status
     * taken, its missed-call alarm silenced (review round 12). And any call
     * that began while an entry ran ([holds]).
     */
    fun <T> accounted(
        entries: List<CallLedger.Recorded>,
        calls: List<T>,
        time: (T) -> Long,
        seconds: (T) -> Int,
        incomingOf: (T) -> Boolean,
    ): Set<T> {
        // Not a joined file still waiting: its way a guess, its start no
        // call's - it holds the call it began in, either way (holds).
        val out = entries.filterNot { it.joined }.mapNotNullTo(HashSet<T>()) { r ->
            closest(calls, if (r.callAt >= 0) r.callAt else r.startMillis, r.incoming, time, incomingOf)
        }
        calls.filterTo(out) { call -> entries.any { holds(it, time(call), seconds(call)) } }
        return out
    }

    /**
     * Whether ledger entry [r] holds a call the log dates [at], [callSeconds]
     * long, that is not its own: one that began while it ran - call waiting
     * puts the second call into the first one's recording - from its first
     * millisecond to its end and [SPAN_SLACK_MS] on; or, joined mid-call -
     * a recorder's file not saved yet ([CallLedger.Recorded.joined]), or a
     * part saved from one ([CallLedger.Recorded.callAt]) - a call it began
     * during, either way: call waiting's other call, up at the revive, is in
     * the file too (review round 13).
     */
    fun holds(r: CallLedger.Recorded, at: Long, callSeconds: Int): Boolean =
        (r.seconds > 0 && at in r.startMillis..(r.startMillis + r.seconds * 1000L + SPAN_SLACK_MS)) ||
            (r.joined && begunDuring(r.startMillis, at, callSeconds)) ||
            // Saved, by the log's span alone: kept a week, the slack took a
            // call over before the revive for a held one (review round 14).
            (r.callAt >= 0 && begunDuring(r.startMillis, at, callSeconds, slackMs = 0))

    /**
     * Whether a recording begun at [start] began inside a call the log dates
     * [at], [callSeconds] long - [AFTER_MS] past the log's end, as an
     * incoming call's length in the log leaves its ringing out. Only for a
     * recorder's file joined mid-call and not saved yet, whose call is not
     * decided: within that slack a redial's could be taken for it, for the
     * minutes it waits (review round 12) - and for a saved part joined
     * mid-call, the other calls up at its start (holds).
     */
    fun begunDuring(start: Long, at: Long, callSeconds: Int, slackMs: Long = AFTER_MS): Boolean =
        callSeconds > 0 && start > at && start <= at + callSeconds * 1000L + slackMs

    /** Where [r] is matched to its call from: its call's start for a part
     *  joined mid-call ([Recording.callAt], noted with the file and in its
     *  tags - kept by the ledger alone, a week on or after a reinstall it
     *  was matched by its own start again; review round 14), else its own. */
    fun matchedFrom(r: Recording): Long? = r.callAt ?: r.matchedStart

    /** A part joined mid-call that began inside the call the log dates
     *  [at], [callSeconds] long - call waiting's other call, up at the
     *  revive (holds): its audio is in it (review round 14). By the log's
     *  span alone, no slack: a call over before the revive is not it. */
    fun joinedDuring(recordings: List<Recording>, at: Long, callSeconds: Int): Recording? =
        recordings.firstOrNull { r ->
            r.callAt != null && r.matchedStart?.let { begunDuring(it, at, callSeconds, slackMs = 0) } == true
        }

    /** A recording that was running when a call began at [at]: from just
     *  after its first millisecond - unlike a ledger entry ([holds]) - to
     *  its end and [SPAN_SLACK_MS] on. */
    fun during(recordings: List<Recording>, at: Long): Recording? =
        recordings.firstOrNull { r ->
            val start = r.matchedStart ?: return@firstOrNull false
            r.durationMillis > 0 && at > start && at <= start + r.durationMillis + SPAN_SLACK_MS
        }

    /**
     * Each recording to the closest of [calls] of its direction inside the
     * window, [time] and [incomingOf] saying when each call was and which way.
     * A call that two recordings both choose keeps the first - or the
     * message, which the user has not heard: a call taken over in Ask first,
     * and recorded at the prompt, has both.
     */
    fun <T> assign(
        recordings: List<Recording>,
        calls: List<T>,
        time: (T) -> Long,
        incomingOf: (T) -> Boolean,
        startOf: (Recording) -> Long? = { it.matchedStart },
    ): Map<T, Recording> {
        val out = mutableMapOf<T, Recording>()
        recordings.forEach { r ->
            val start = startOf(r) ?: return@forEach
            val call = closest(calls, start, r.incoming, time, incomingOf) ?: return@forEach
            out.merge(call, r) { old, new -> if (new.message && !old.message) new else old }
        }
        return out
    }

    /** The recorder's files not collected yet, as the ledger's entries their
     *  calls would have: phone calls only, length unknown. A messenger's
     *  file is no phone call's recording. One joined mid-call is marked so
     *  ([CallLedger.Recorded.joined]): its way a guess, it is no call's to
     *  claim in a save - but it is still its call's recording, waiting
     *  (review round 12: "A call was not recorded" for a call whose only
     *  recording waited to be saved). */
    fun pendingRecorded(names: List<String>): List<CallLedger.Recorded> =
        names.map { DaemonFile.parse(it) }
            .filter { it.messenger == null }
            .mapNotNull { file -> file.start?.let { CallLedger.Recorded(it, file.incoming, -1, joined = file.joined) } }
}
