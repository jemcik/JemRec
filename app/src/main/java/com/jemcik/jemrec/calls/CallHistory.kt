package com.jemcik.jemrec.calls

import android.content.Context
import android.net.Uri
import android.provider.CallLog
import android.util.Log
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.recordings.Recording

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
        // A part joined mid-call - its start minutes into its call - by its
        // call's start alone (Recording.callAt): by any start in a call's
        // span a redial's recording played for the call before (review round
        // 12), and by its own start, call waiting's other call took it
        // (round 13).
        fun isJoined(r: Recording) = r.callAt != null
        fun assign(of: List<Recording>) =
            CallMatch.assign(of.filterNot(::isJoined), connectable, { it.at }, { it.kind == Kind.INCOMING })
        fun assignJoined(of: List<Recording>) =
            CallMatch.assign(of.filter(::isJoined), connectable, { it.at }, { it.kind == Kind.INCOMING }) { it.callAt }
        val recorded = assign(recordings)
        val binned = assign(trashed)
        val joinedIn = assignJoined(recordings)
        val binnedJoined = assignJoined(trashed)
        // Each entry its own call, and any begun while it ran (CallMatch.accounted).
        fun accounted(entries: List<CallLedger.Recorded>) =
            CallMatch.accounted(entries, connectable, { it.at }, { it.seconds }, { it.kind == Kind.INCOMING })
        val pendingFor = accounted(pending)
        val hungUpFor = accounted(ledger.greetingHangUps)
        val failedFor = accounted(ledger.greetingFailures)
        val testFor = accounted(ledger.testCalls)
        val skippedFor = accounted(ledger.skipped)
        val deletedFor = accounted(ledger.recordings)
        return calls.map { call ->
            val canHaveOne = call.kind == Kind.INCOMING || call.kind == Kind.OUTGOING
            val recording = if (canHaveOne) {
                recorded[call] ?: CallMatch.during(recordings, call.at) ?: joinedIn[call] ?:
                    // Call waiting's other call, up at the revive: in the part.
                    CallMatch.joinedDuring(recordings, call.at, call.seconds)
            } else {
                null
            }
            val status = when {
                recording != null -> Status.RECORDED
                !canHaveOne -> Status.NOT_CONNECTED
                call.kind == Kind.OUTGOING && call.seconds == 0 -> Status.NOT_CONNECTED
                binned[call] != null || binnedJoined[call] != null || CallMatch.during(trashed, call.at) != null ||
                    CallMatch.joinedDuring(trashed, call.at, call.seconds) != null -> Status.IN_TRASH
                call in pendingFor -> Status.PENDING
                call in hungUpFor -> Status.GREETING_HANGUP
                call in failedFor -> Status.GREETING_FAILED
                call in testFor -> Status.TEST_CALL
                call in skippedFor -> Status.SKIPPED
                call in deletedFor -> Status.DELETED
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
