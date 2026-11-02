package com.jemcik.jemrec.calls

import com.jemcik.jemrec.capture.FileNames
import com.jemcik.jemrec.capture.Recording
import com.jemcik.jemrec.recordings.DaemonFile

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
     * with the wrong person.
     */
    fun <T> closest(items: List<T>, at: Long, incoming: Boolean, time: (T) -> Long, incomingOf: (T) -> Boolean): T? =
        items
            .filter { incomingOf(it) == incoming && sameCall(time(it), at) }
            .minByOrNull { kotlin.math.abs(time(it) - at) }

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
        val claimed = others
            .filter { it.startMillis != at }
            .mapNotNullTo(HashSet()) { closest(entries, it.startMillis, it.incoming) }
        return closest(entries.filterNot { it in claimed }, at, incoming)
    }

    /**
     * The call with [number] that a recording starting at [at] fell inside:
     * from the log's date to its end, the way Honor's Phone app matches its
     * own recordings (see FileNames.build). Compared digit for digit, as
     * FileNames.digits wrote the number into the name.
     */
    fun withNumber(entries: List<CallLogLookup.Entry>, number: String, at: Long): CallLogLookup.Entry? {
        val wanted = FileNames.digits(number) ?: return null
        return entries
            .filter { FileNames.digits(it.number) == wanted }
            .filter { at in (it.at - AFTER_MS)..(it.at + it.seconds * 1000L + AFTER_MS) }
            .minByOrNull { kotlin.math.abs(it.at - at) }
    }

    /**
     * Whether a ledger entry [r] is the call at [at] that went the way
     * [incoming] says: its own, by [sameCall]; or a call that began while it
     * ran - call waiting puts the second call into the first one's
     * recording - from its first millisecond to its end and [SPAN_SLACK_MS]
     * on. A pending one (length unknown) accounts by its start only.
     */
    fun accounts(r: CallLedger.Recorded, at: Long, incoming: Boolean): Boolean =
        (r.incoming == incoming && sameCall(at, r.startMillis)) ||
            (r.seconds > 0 && at in r.startMillis..(r.startMillis + r.seconds * 1000L + SPAN_SLACK_MS))

    /** A recording that was running when a call began at [at]: from just
     *  after its first millisecond - unlike a ledger entry ([accounts]) - to
     *  its end and [SPAN_SLACK_MS] on. */
    fun during(recordings: List<Recording>, at: Long): Recording? =
        recordings.firstOrNull { r ->
            val start = r.startedAtMillis ?: return@firstOrNull false
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
    ): Map<T, Recording> {
        val out = mutableMapOf<T, Recording>()
        recordings.forEach { r ->
            val start = r.startedAtMillis ?: return@forEach
            val call = closest(calls, start, r.incoming, time, incomingOf) ?: return@forEach
            out.merge(call, r) { old, new -> if (new.message && !old.message) new else old }
        }
        return out
    }

    /** The recorder's files not collected yet, as the ledger's entries their
     *  calls would have: phone calls only, length unknown. A messenger's
     *  file is no phone call's recording. */
    fun pendingRecorded(names: List<String>): List<CallLedger.Recorded> =
        names.map { DaemonFile.parse(it) }
            .filter { it.messenger == null }
            .mapNotNull { file -> file.start?.let { CallLedger.Recorded(it, file.incoming, -1) } }
}
