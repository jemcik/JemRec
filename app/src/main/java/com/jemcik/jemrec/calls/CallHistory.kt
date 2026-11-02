package com.jemcik.jemrec.calls

import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.util.Log
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.Recording

/**
 * The phone's call log, each call matched to its recording or told why it has
 * none: the Calls tab.
 *
 * The Recordings tab lists files. This lists calls, which is what a person
 * remembers - "the call with the bank on Tuesday" - and is the only place a
 * call that was NOT recorded can show up at all. Matching a call to a
 * recording is the same time-and-direction rule the recordings list uses to
 * name a caller (CallMatch.sameCall), in reverse.
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
        /** Recorded, and still on the recorder: not collected yet (a reboot
         *  off Wi-Fi, a wake that did not land). Not a failure. */
        PENDING,
        /** Missed, declined, blocked, voicemail, or an outgoing call nobody answered. */
        NOT_CONNECTED,
        /** The answering machine took it, and the caller left before the beep. */
        GREETING_HANGUP,
        /** The answering machine took it and hung up: the greeting would not
         *  play. Said in the error colour, like NOT_RECORDED. */
        GREETING_FAILED,
        /** A test call of the answering machine. */
        TEST_CALL,
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
    suspend fun load(context: Context, recordings: List<Recording>, trashed: List<Recording>): List<Call> {
        override?.let { return it(context) }
        if (!CallLogLookup.granted(context)) return emptyList()
        CoverageHistory.start(context)
        // The daemon's files not collected yet: their calls were recorded.
        // Without them, each read as "Not recorded", in red.
        val pending = CallMatch.pendingRecorded(CaptureDaemon.listPendingOrNull(context).orEmpty())
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
            pending = pending,
        )
        // Names and photos from the contacts provider, one lookup per number.
        val contacts = mutableMapOf<String, CallLogLookup.Contact>()
        return matched.map { call ->
            val contact = CallLogLookup.contactFor(context, call.number, contacts)
            call.copy(
                label = contact.name ?: call.label,
                photo = contact.photo,
                favorite = contact.starred,
            )
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
        pending: List<CallLedger.Recorded> = emptyList(),
    ): List<Call> {
        val calls = logged.sortedByDescending { it.at }
        val connectable = calls.filter { it.kind == Kind.INCOMING || it.kind == Kind.OUTGOING }
        // Messenger calls are not in the call log; their recordings belong to
        // none of these calls, however close in time.
        @Suppress("NAME_SHADOWING") val recordings = recordings.filter { it.parsedName?.app == null }
        @Suppress("NAME_SHADOWING") val trashed = trashed.filter { it.parsedName?.app == null }
        val recorded = CallMatch.assign(recordings, connectable, { it.at }, { it.kind == Kind.INCOMING })
        val binned = CallMatch.assign(trashed, connectable, { it.at }, { it.kind == Kind.INCOMING })
        return calls.map { call ->
            val incoming = call.kind != Kind.OUTGOING
            val canHaveOne = call.kind == Kind.INCOMING || call.kind == Kind.OUTGOING
            val recording = if (canHaveOne) recorded[call] ?: CallMatch.during(recordings, call.at) else null
            val status = when {
                recording != null -> Status.RECORDED
                !canHaveOne -> Status.NOT_CONNECTED
                call.kind == Kind.OUTGOING && call.seconds == 0 -> Status.NOT_CONNECTED
                binned[call] != null || CallMatch.during(trashed, call.at) != null -> Status.IN_TRASH
                pending.any { CallMatch.accounts(it, call.at, incoming) } -> Status.PENDING
                ledger.greetingHangUps.any { CallMatch.accounts(it, call.at, incoming) } -> Status.GREETING_HANGUP
                ledger.greetingFailures.any { CallMatch.accounts(it, call.at, incoming) } -> Status.GREETING_FAILED
                ledger.testCalls.any { CallMatch.accounts(it, call.at, incoming) } -> Status.TEST_CALL
                ledger.skipped.any { CallMatch.accounts(it, call.at, incoming) } -> Status.SKIPPED
                ledger.recordings.any { CallMatch.accounts(it, call.at, incoming) } -> Status.DELETED
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

    private fun query(context: Context): List<Logged> =
        CallLogRows.read(
            context,
            arrayOf(
                CallLog.Calls.DATE,
                CallLog.Calls.DURATION,
                CallLog.Calls.TYPE,
                CallLog.Calls.NUMBER,
                CallLog.Calls.CACHED_NAME,
                CallLog.Calls.PHONE_ACCOUNT_COMPONENT_NAME,
            ),
            // Every row: Meet's, in the call log too, are under Messenger
            // calls, and are left out with every other app's (CallLogRows).
            null, null,
            "${CallLog.Calls.DATE} DESC",
        ).map { Logged(it.at, it.seconds, kindOf(it.type), it.label, it.number) }

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
