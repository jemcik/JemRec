package com.jemcik.jemrec.capture

import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.util.Log

/**
 * The phone's call log, each call matched to its recording or told why it has
 * none: the Calls tab.
 *
 * The Recordings tab lists files. This lists calls, which is what a person
 * remembers - "the call with the bank on Tuesday" - and is the only place a
 * call that was NOT recorded can show up at all. Matching a call to a
 * recording is the same time-and-direction rule the recordings list uses to
 * name a caller (CallLogLookup.sameCall), in reverse.
 *
 * WHY A CALL HAS NO RECORDING, AND ONLY WHERE THAT IS KNOWN:
 *   - the recording is in Recently deleted (Trash);
 *   - it was recorded and then deleted - known for a week, from the ledger
 *     the missed-call check keeps (CallLedger);
 *   - the user's rules on whose calls to keep left it out (CallRules) -
 *     known for a week, from the same ledger;
 *   - it was too short to count (the missed-call check's own threshold);
 *   - recording was off, or in ask-first mode, at the time (CoverageHistory);
 *   - none of those, with recording on and automatic, within that week: NOT
 *     RECORDED, the one the user should worry about, said in the error
 *     colour. Older than the ledger, a recording deleted or a call left out
 *     looks exactly like a call that was missed, so it is not said;
 *   - before any of that was being kept, or older: "No recording", and no
 *     guess why.
 * Missed, declined, blocked and unanswered calls never went off-hook, so
 * nothing about them is a recording question.
 */
object CallHistory {

    private const val TAG = "JemRec"

    enum class Kind { INCOMING, OUTGOING, MISSED, DECLINED, BLOCKED, VOICEMAIL, OTHER }

    enum class Status {
        RECORDED, IN_TRASH, DELETED, SKIPPED, TOO_SHORT, RECORDING_OFF, ASK_FIRST, NOT_RECORDED, NO_RECORDING,
        /** Missed, declined, blocked, voicemail, or an outgoing call nobody answered. */
        NOT_CONNECTED,
    }

    data class Call(
        val at: Long,
        val seconds: Int,
        val kind: Kind,
        val label: String,
        val number: String?,
        val photo: Uri? = null,
        val favorite: Boolean = false,
        /** The recording to play, when there is one. */
        val recording: Recording? = null,
        val status: Status,
    ) {
        val incoming: Boolean get() = kind != Kind.OUTGOING
    }

    /** One row of the call log, before it is matched. */
    internal data class Logged(val at: Long, val seconds: Int, val kind: Kind, val label: String, val number: String?)

    /**
     * Debug builds only: invented calls for screenshots, the way
     * Recordings.override invents recordings. Nothing in a release sets it.
     */
    @Volatile
    var override: ((Context) -> List<Call>)? = null

    /** Every call the log holds, newest first, matched. Blocking: call off the main thread. */
    fun load(context: Context, recordings: List<Recording>, trashed: List<Recording>): List<Call> {
        override?.let { return it(context) }
        if (!CallLogLookup.granted(context)) return emptyList()
        CoverageHistory.start(context)
        val logged = runCatching { query(context) }
            .onFailure { Log.w(TAG, "calls: could not read the call log", it) }
            .getOrDefault(emptyList())
        val matched = classify(
            logged = logged,
            recordings = recordings,
            trashed = trashed,
            ledger = CallLedger.read(context),
            history = CoverageHistory.read(context),
            now = System.currentTimeMillis(),
        )
        // Names and photos from the contacts provider, one lookup per number.
        val contacts = mutableMapOf<String, CallLogLookup.Contact>()
        return matched.map { call ->
            val contact = CallLogLookup.contactFor(context, call.number, contacts)
            call.copy(label = contact.name ?: call.label, photo = contact.photo, favorite = contact.starred)
        }
    }

    /**
     * Match and explain. Pure, for the tests.
     *
     * Each recording goes to ONE call: the closest of the same direction
     * inside the window, chosen from the recording's side, as the recordings
     * list names its caller. Matching from the call's side let a missed call
     * a minute before an answered one claim the answered call's recording.
     * Then, as the missed-call check allows, a call that began DURING a
     * recording (call waiting) shares it. Missed, declined and blocked calls
     * never went off-hook and are never matched.
     */
    internal fun classify(
        logged: List<Logged>,
        recordings: List<Recording>,
        trashed: List<Recording>,
        ledger: CallLedger.Ledger,
        history: List<Pair<Long, CoverageHistory.State>>,
        now: Long,
    ): List<Call> {
        val calls = logged.sortedByDescending { it.at }
        val connectable = calls.filter { it.kind == Kind.INCOMING || it.kind == Kind.OUTGOING }
        val recorded = assign(recordings, connectable)
        val binned = assign(trashed, connectable)
        return calls.map { call ->
            val incoming = call.kind != Kind.OUTGOING
            val canHaveOne = call.kind == Kind.INCOMING || call.kind == Kind.OUTGOING
            val recording = if (canHaveOne) recorded[call] ?: during(recordings, call.at) else null
            val status = when {
                recording != null -> Status.RECORDED
                !canHaveOne -> Status.NOT_CONNECTED
                call.kind == Kind.OUTGOING && call.seconds == 0 -> Status.NOT_CONNECTED
                binned[call] != null || during(trashed, call.at) != null -> Status.IN_TRASH
                ledger.skipped.any { accounts(it, call, incoming) } -> Status.SKIPPED
                ledger.recordings.any { accounts(it, call, incoming) } -> Status.DELETED
                call.seconds < MissedCalls.MIN_SECONDS -> Status.TOO_SHORT
                else -> when (CoverageHistory.stateAt(history, call.at)) {
                    CoverageHistory.State.OFF -> Status.RECORDING_OFF
                    CoverageHistory.State.ASK_FIRST -> Status.ASK_FIRST
                    // Only while the ledger still covers it: see above.
                    CoverageHistory.State.AUTOMATIC ->
                        if (call.at > now - CallLedger.KEEP_MS) Status.NOT_RECORDED else Status.NO_RECORDING
                    null -> Status.NO_RECORDING
                }
            }
            Call(call.at, call.seconds, call.kind, call.label, call.number, recording = recording, status = status)
        }
    }

    /** Whether a ledger entry is this call's, the way the missed-call check
     *  decides it (MissedCalls.unrecorded). */
    private fun accounts(r: CallLedger.Recorded, call: Logged, incoming: Boolean): Boolean =
        (r.incoming == incoming && CallLogLookup.sameCall(call.at, r.startMillis)) ||
            (r.seconds > 0 && call.at in r.startMillis..(r.startMillis + r.seconds * 1000L + MissedCalls.SPAN_SLACK_MS))

    /** Each recording to the closest call of its direction inside the window.
     *  A call that two recordings both choose keeps the first. */
    private fun assign(recordings: List<Recording>, calls: List<Logged>): Map<Logged, Recording> {
        val out = mutableMapOf<Logged, Recording>()
        recordings.forEach { r ->
            val start = r.startedAtMillis ?: return@forEach
            val call = calls
                .filter { (it.kind == Kind.INCOMING) == r.incoming && CallLogLookup.sameCall(it.at, start) }
                .minByOrNull { kotlin.math.abs(it.at - start) }
                ?: return@forEach
            out.putIfAbsent(call, r)
        }
        return out
    }

    /** A recording that was running when a call began: call waiting puts the
     *  second call in the first one's file. */
    private fun during(recordings: List<Recording>, at: Long): Recording? =
        recordings.firstOrNull { r ->
            val start = r.startedAtMillis ?: return@firstOrNull false
            r.durationMillis > 0 && at > start && at <= start + r.durationMillis + MissedCalls.SPAN_SLACK_MS
        }

    private fun query(context: Context): List<Logged> {
        val out = mutableListOf<Logged>()
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.TYPE,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
            ),
            null, null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val name = cursor.getString(4)?.takeIf { it.isNotBlank() }
                val number = cursor.getString(3)?.takeIf { it.isNotBlank() }
                out += Logged(
                    at = cursor.getLong(0),
                    seconds = cursor.getInt(1),
                    kind = kindOf(cursor.getInt(2)),
                    label = name ?: number ?: "Unknown number",
                    number = number,
                )
            }
        }
        return out
    }

    internal fun kindOf(type: Int): Kind = when (type) {
        CallLog.Calls.INCOMING_TYPE -> Kind.INCOMING
        CallLog.Calls.OUTGOING_TYPE -> Kind.OUTGOING
        CallLog.Calls.MISSED_TYPE -> Kind.MISSED
        CallLog.Calls.REJECTED_TYPE -> Kind.DECLINED
        CallLog.Calls.BLOCKED_TYPE -> Kind.BLOCKED
        CallLog.Calls.VOICEMAIL_TYPE -> Kind.VOICEMAIL
        // Answered on another device signed into the same number: not here.
        else -> Kind.OTHER
    }
}
