package com.jemcik.jemrec.recordings

import android.util.Log
import com.jemcik.jemrec.calls.CallLedger
import com.jemcik.jemrec.calls.CallLogLookup
import com.jemcik.jemrec.calls.CallMatch
import com.jemcik.jemrec.calls.CallTracking
import com.jemcik.jemrec.calls.CoverageHistory
import com.jemcik.jemrec.calls.PhoneNumbers

/**
 * What becomes of a daemon recording of a phone call: which calls it holds,
 * who each was with, what each file is called, which the rules leave out.
 * Decided here, apart from doing it, so it can be tested (SavePlanTest):
 * RecordingSaver asks, with the phone behind [Lookups], and does what comes
 * back.
 *
 * TWO WAYS TO KNOW A CALL, ONE RULE FOR WHO IT WAS. A call is known from
 * Telecom's own account (exact call tracking, CallTracking) or from the call
 * log. Both end in [identified]. 0.14 had a second rule for the first way,
 * and it lost the call log's name for a number in no contact - files named
 * by digits while the list beside them showed the name (issue #30). The tests
 * hold both ways to the same answer for the same call.
 */
internal object SavePlan {

    private const val TAG = "JemRec"

    /** What the plan asks of the phone. */
    interface Lookups {
        /** Whether the call log may be read: the consent that knowing who a
         *  call was with needs, whichever way it is known. */
        val callLogAllowed: Boolean

        /** Who [number] is in the contacts. */
        fun contact(number: String?): CallLogLookup.Contact

        /** The call log's entry for the call that went off-hook at [start],
         *  if the log has it yet. */
        fun logged(start: Long, incoming: Boolean): CallLogLookup.Entry?

        /** Every call the log dates from [from] to [to]. */
        fun loggedBetween(from: Long, to: Long): List<CallLogLookup.Entry>

        /** Where the phone is now, for a call the log gives no country: what
         *  the number format reads a number dialled without its code in. */
        val country: String?

        /** A second's wait for the call log to write a call down. */
        suspend fun pause()
    }

    /** Who a call was with: for its file name, and for the rules. [loggedAt]:
     *  its call-log entry's date, when that is how it was known. */
    class Who(val party: FileNames.Party, val contactIds: List<Long>, val seconds: Int, val loggedAt: Long? = null)

    /**
     * THE rule for who a call was with, whichever way it is known: a
     * contact's name; else the call log's own name for the number - the
     * dialer's caller ID, or the network's; else the number alone. Not known
     * at all when the contacts could not be asked, which the rules must not
     * take for a stranger. [country]: where the call was, as the log has it,
     * else where the phone is now.
     */
    fun identified(
        number: String?,
        contact: CallLogLookup.Contact,
        loggedName: String?,
        seconds: Int,
        country: String?,
        loggedAt: Long? = null,
    ): Who? {
        if (contact.failed) return null
        val name = contact.name ?: loggedName?.takeIf { it.isNotBlank() }
        return Who(FileNames.Party(number, name, country), contact.ids, seconds, loggedAt)
    }

    /** Who, from the call log alone - the way without exact call tracking.
     *  The log is given [tries] seconds to write the call down. */
    suspend fun fromCallLog(lookups: Lookups, start: Long, incoming: Boolean, tries: Int): Who? {
        if (!lookups.callLogAllowed) return null
        repeat(tries) { attempt ->
            lookups.logged(start, incoming)?.let { entry ->
                return identified(entry.number, lookups.contact(entry.number), entry.name, entry.seconds, entry.country ?: lookups.country, entry.at)
            }
            if (attempt < tries - 1) lookups.pause()
        }
        Log.w(TAG, "save: the call log has no entry for the call at $start")
        return null
    }

    /**
     * Who, from Telecom's number - and, for a file name with the caller in it
     * ([named]), as the call log has the call: its spelling of the number,
     * which Honor's Phone app finds its recordings by, and its name for a
     * number in no contact. That is how such names were always made; 0.14
     * made them from Telecom alone, and lost both (issue #30). The log's
     * entry counts only if it has this call's number, and is given [tries]
     * seconds to be written; without it, Telecom's number stands in.
     */
    suspend fun fromTelecom(
        lookups: Lookups,
        call: CallTracking.Tracked,
        start: Long,
        named: Boolean,
        tries: Int,
    ): Who? {
        if (!lookups.callLogAllowed) return null
        val contact = lookups.contact(call.number)
        if (contact.failed) return null
        val entry = if (named && call.number != null) loggedEntry(lookups, call, start, tries) else null
        return identified(entry?.number ?: call.number, contact, entry?.name, call.seconds, entry?.country ?: lookups.country)
    }

    private suspend fun loggedEntry(
        lookups: Lookups,
        call: CallTracking.Tracked,
        start: Long,
        tries: Int,
    ): CallLogLookup.Entry? {
        repeat(tries) { attempt ->
            val entry = lookups.logged(start, call.incoming)
            if (entry != null && PhoneNumbers.sameNumber(entry.number, call.number)) return entry
            if (attempt < tries - 1) lookups.pause()
        }
        return null
    }

    /**
     * Whether the call log has another answered call inside this recording -
     * call waiting, with no Telecom account to cut the file by. The file then
     * holds a call the rules may keep, so it is not left out whole: kept
     * when unsure. A recording [joined] mid-call holds too any other call up
     * at its start - call waiting's held call, dated before the one taken
     * for it (loggedDuring), whose audio after it went with the file
     * (review round 12).
     */
    private fun anotherCallDuring(lookups: Lookups, startedAt: Long, who: Who, joined: Boolean): Boolean {
        val own = who.loggedAt ?: startedAt
        // A joined recording's call ends where its own span does, not its
        // whole length on from the revive - calls well after it counted
        // (review round 13); a ringing's length on, the log's leaves it out.
        val end = WAITING_SLACK_MS + if (joined) {
            minOf(startedAt, own + CallMatch.BEFORE_MS) + who.seconds * 1000L
        } else {
            startedAt + who.seconds * 1000L
        }
        if (lookups.loggedBetween(own, end).any { it.seconds > 0 && it.at != own }) return true
        return joined && lookups.loggedBetween(startedAt - CallTracking.LONGEST_CALL_MS, startedAt)
            .any { it.seconds > 0 && it.at != own && startedAt <= it.at + it.seconds * 1000L + CallMatch.BEFORE_MS }
    }

    /** Past the end the log gives a call, where the next can still be in its file. */
    private const val WAITING_SLACK_MS = 5_000L

    /**
     * The call-log entry for the call that was up at [at], whichever way it
     * went - a recording joined mid-call: of those dated before [at] whose
     * span holds it - from its date to its end and [CallMatch.BEFORE_MS] on,
     * as an incoming call's length in the log leaves its ringing out - one
     * holding it without that slack first, then the latest. The log is given
     * [tries] seconds to write the call down, and only the last takes one
     * holding it by the slack alone: the call's own entry not written yet,
     * the call before - ended up to three minutes before - was taken at once,
     * named and ruled by it (review round 12).
     */
    suspend fun loggedDuring(lookups: Lookups, at: Long, tries: Int): CallLogLookup.Entry? {
        repeat(tries) { attempt ->
            // Begun before it, by definition - a later call (a waiting one)
            // is never it - and one whose span holds it without the slack
            // first: the slack only covers an incoming call's ringing, which
            // the log's length leaves out (review round 11).
            val last = attempt == tries - 1
            lookups.loggedBetween(at - CallTracking.LONGEST_CALL_MS, at)
                .filter { at <= it.at + it.seconds * 1000L + (if (last) CallMatch.BEFORE_MS else 0L) }
                .sortedWith(compareByDescending<CallLogLookup.Entry> { at <= it.at + it.seconds * 1000L }.thenByDescending { it.at })
                .firstOrNull()
                ?.let { return it }
            if (!last) lookups.pause()
        }
        Log.w(TAG, "save: the call log has no call up at $at")
        return null
    }

    /** The settings that bear on it. */
    class Settings(
        val format: FileNames.Format,
        /** Whether the rules on whose calls to keep get a say in this one. */
        val ruled: Boolean,
        /** Whether the rules keep a call with these contacts (none: a number
         *  in no contact). */
        val keeps: (List<Long>) -> Boolean,
    )

    /** A call the rules left out, for the ledger; [callAt] as a part's
     *  (RecordingSaver.Part.callAt). */
    data class LeftOut(val start: Long, val incoming: Boolean, val seconds: Int, val callAt: Long = -1)

    /** What to do with the recording. */
    sealed interface Decision {
        /** Only ringing, by Telecom's account: delete it, and note a call of
         *  no length at [start] so the missed-call check does not ask. */
        data class NotAnswered(val start: Long, val incoming: Boolean) : Decision

        /** Every call in it left out by the rules: delete it, noting each. */
        data class LeaveOut(val leftOut: List<LeftOut>) : Decision

        /** Save [parts]; note [leftOut] - calls in it the rules left out. */
        data class Save(val parts: List<RecordingSaver.Part>, val leftOut: List<LeftOut>) : Decision
    }

    /**
     * Decide. [tracked] is what Telecom said about the calls this recording
     * holds (CallTracking.callsIn), its own call first; empty, and the call
     * log is the one way to know. [tries]: seconds the call log may still need.
     */
    suspend fun decide(
        lookups: Lookups,
        settings: Settings,
        startedAt: Long,
        incoming: Boolean,
        tracked: List<CallTracking.Tracked>,
        tries: Int,
        joined: Boolean = false,
    ): Decision {
        val named = settings.format != FileNames.Format.DATE
        val wantWho = settings.ruled || named
        if (tracked.isEmpty()) {
            // Joined mid-call (DaemonFile.joined): its call is the log's
            // entry up at its start, whichever way - asked even when nothing
            // needs who it was, as its way, in every name, is otherwise the
            // recorder's guess (review round 10).
            val during = if (joined && lookups.callLogAllowed) loggedDuring(lookups, startedAt, tries) else null
            val way = during?.incoming ?: incoming
            val who = when {
                !wantWho -> null
                joined -> during?.let {
                    identified(it.number, lookups.contact(it.number), it.name, it.seconds, it.country ?: lookups.country, it.at)
                }
                else -> fromCallLog(lookups, startedAt, incoming, tries)
            }
            // Matched to its call by the call's start, its own minutes in
            // (CallLedger.Recorded.callAt).
            val callAt = during?.at ?: -1
            if (settings.ruled && who != null && !settings.keeps(who.contactIds) &&
                !anotherCallDuring(lookups, startedAt, who, joined)
            ) {
                // Joined, what is left of the call from the revive: its whole
                // length on held calls after it (review round 13).
                val seconds = if (callAt >= 0) maxOf(0, who.seconds - ((startedAt - callAt) / 1000).toInt()) else who.seconds
                return Decision.LeaveOut(listOf(LeftOut(startedAt, way, seconds, callAt)))
            }
            val name = FileNames.build(settings.format, startedAt, way, who?.party)
            return Decision.Save(listOf(RecordingSaver.Part.whole(name, way, startedAt, callAt)), emptyList())
        }

        // Cut to the calls: from the answer (an outgoing call's ringing is not
        // kept), and with call waiting one file per call.
        val segments = CallTracking.segments(startedAt, tracked)
        if (segments.isEmpty()) return Decision.NotAnswered(startedAt, incoming)
        val own = tracked.first()
        val parts = mutableListOf<RecordingSaver.Part>()
        val leftOut = mutableListOf<LeftOut>()
        for (call in segments.map { it.first }.distinct()) {
            val packets = segments.filter { it.first === call }.map { it.second }
            // The recording's own call keeps the recording's start in its
            // name, as every name always has (and as Honor's dialer matches);
            // a call that came in during it starts where it was answered. In
            // a joined recording a call answered before it began starts at
            // its first packet: dated by its answer, it took recording 1's
            // part's name, "(1)", minutes before its audio (review round 12).
            // A second on at least: at the file's own second, its name was the
            // own part's, and the ledger knew one of them (review round 13).
            val start = when {
                call === own -> startedAt
                joined -> maxOf(startedAt + 1_000L, startedAt + packets.first().first * CallTracking.PACKET_MS)
                else -> call.answeredAt
            }
            // A joined recording's calls are looked up in the log by their own
            // start, Telecom's id - the log's date - not the recording's or a
            // part's, minutes into the call, where the log has nothing in its
            // window (review rounds 11 and 13).
            val lookupAt = if (joined) call.id else start
            val who = if (wantWho) fromTelecom(lookups, call, lookupAt, named, tries) else null
            if (settings.ruled && who != null && !settings.keeps(who.contactIds)) {
                // The own call at the file's start: what the next collection
                // knows the file by (drop) - noted at its call's, a kept part's
                // file outliving its save was saved again, "(1)" (review
                // round 13).
                // Joined, its own call's seconds from the revive, not its
                // answer: the entry ran past the call's end and held calls
                // after it (review round 14).
                val seconds = if (joined && call === own && call.endedAt > 0) {
                    ((call.endedAt - startedAt) / 1000).toInt().coerceAtLeast(0)
                } else {
                    call.seconds
                }
                leftOut += LeftOut(if (call === own) startedAt else call.id, call.incoming, seconds, if (joined) call.id else -1)
                continue
            }
            parts += RecordingSaver.Part(
                name = FileNames.build(settings.format, start, call.incoming, who?.party),
                incoming = call.incoming,
                startMillis = start,
                packets = packets,
                // Joined: each part matched to its call by the call's start.
                callAt = if (joined) call.id else -1,
            )
        }
        return if (parts.isEmpty()) Decision.LeaveOut(leftOut) else Decision.Save(parts, leftOut)
    }

    /**
     * Whether a recording is one of a call's parts, known before it is
     * written - so its tags carry it (JEMREC_PART): one a call's service
     * saves among others ([part]), one joined mid-call, one the recorder
     * marked as stopped well before its call, or one noted as a call's part
     * ([inGroup], CallParts). No short call, any of them (Retention; review
     * rounds 13 and 14). One Telecom's end says stopped early is marked as
     * it is published, after the tags.
     */
    fun isPart(part: Boolean, file: DaemonFile, inGroup: Boolean): Boolean =
        part || file.joined || markedEarlyMs(file.missedMs) != null || inGroup

    /** Why a recording is dropped before it is fetched. */
    enum class Drop {
        /** Saved once already, and the recorder's copy outlived it - a delete
         *  that did not get through. Not again: the second would be "(1)". So
         *  too for a call the answering machine had and kept nothing of: told
         *  once, not at every pass while a delete does not get through. */
        DUPLICATE,

        /** A message whose caller left before the beep: nothing said. */
        GREETING_HANG_UP,

        /** A message whose greeting could not be played: the recorder hung up. */
        GREETING_FAILED,
    }

    /**
     * Whether a recording is dropped before anything is fetched, and why: by
     * the calls the ledger says were saved before ([accounted]: the phone's
     * for a phone call, the messengers' own for a messenger's), any one of
     * which starting at [startedAt] is this recording's, then by what its
     * name says of a message (the recorder names a phone call's file with
     * where the message starts, or "none" or "fail" - see DaemonFile).
     */
    fun drop(file: DaemonFile, startedAt: Long, accounted: List<CallLedger.Recorded>): Drop? = when {
        accounted.any { it.startMillis == startedAt } -> Drop.DUPLICATE
        file.message == DaemonFile.Message.Failed -> Drop.GREETING_FAILED
        file.message == DaemonFile.Message.None -> Drop.GREETING_HANG_UP
        else -> null
    }

    /**
     * Whether recording was switched off when the call ended - switched off
     * and on again before then, it is kept - by Telecom's end of its own call ([tracked], CallTracking.callsIn) and
     * the switch's [history]. The hang-up drops such a recording
     * (CallMonitorService), and so does this, when the hang-up's wake did
     * not land - the two must agree. Without Telecom's end the recording's
     * own decides, once it is read ([offAt]).
     */
    fun switchedOff(tracked: List<CallTracking.Tracked>, history: List<Pair<Long, CoverageHistory.State>>): Boolean {
        val ended = telecomEnded(tracked) ?: return false
        return offAt(history, ended)
    }

    /** When Telecom says the recording's own call ended, if it said. */
    fun telecomEnded(tracked: List<CallTracking.Tracked>): Long? = tracked.firstOrNull()?.endedAt?.takeIf { it > 0 }

    /**
     * How much of the call a recording missed at its end - its last packet,
     * [lastSlot] from its [start], short of when Telecom says the call ended
     * - when that is more than STOPPED_EARLY_MS; else null. The recorder
     * stops a second or two after a call (its hang-up check), so a recording
     * that ends this far before one did stopped early: the capture failing
     * more than it may be made anew (the recorder's RestartPolicy), the
     * encoder ending, the recorder itself ended mid-call. Pure.
     *
     * On a clock from the first packet (Cut.fromFirst: a takeover's cut),
     * [lastSlot] counts from that packet as if it came at [start]: it came
     * as late as the capture took to start, which for a call the answering
     * machine had already answered - the capture live - is milliseconds,
     * far under STOPPED_EARLY_MS (review, 2026-11-18).
     */
    fun stoppedEarlyMs(start: Long, lastSlot: Long, endedAt: Long): Long? =
        (endedAt - (start + (lastSlot + 1) * CallTracking.PACKET_MS)).takeIf { it > STOPPED_EARLY_MS }

    /**
     * Whether a recording that stopped at [lastSlot] was carried on by
     * another of the same call: one of [others] - the starts of the other
     * recordings known, saved or waiting on the recorder - begins after it
     * stopped and before the call ended at [endedAt]. A recorder ended
     * mid-call and brought back while the call is up starts a second
     * recording of it; the first then did not lose the call's last part,
     * only the gap, and saying so was wrong (review, 2026-11-18). Pure.
     */
    fun carriedOn(start: Long, lastSlot: Long, endedAt: Long, others: Collection<Long>): Boolean {
        val stopped = start + (lastSlot + 1) * CallTracking.PACKET_MS
        return others.any { it in stopped..endedAt }
    }

    /**
     * The recorder's own mark - its encoder ended [missedMs] before the
     * recording was stopped (DaemonFile.missedMs) - when that is more than
     * STOPPED_EARLY_MS, for a call Telecom gave no end of: a messenger's, or
     * a phone call with call tracking off. The recorder stops a second or two
     * after a call, so the mark runs that much over the call's loss, as the
     * reckoning from Telecom's end does not; within "about". A message's
     * too: a takeover, which ends one on purpose, stops the recording before
     * its encoder can end early (Calls.splitOffMessage), so a marked message
     * is one whose capture was lost under the caller - Telecom's end, which a
     * takeover does run past, is what leaves messages out (review,
     * 2026-11-18). Pure.
     */
    fun markedEarlyMs(missedMs: Long?): Long? = missedMs?.takeIf { it > STOPPED_EARLY_MS }

    /** Short of the call's end by more than this, a recording stopped early:
     *  well past what the capture may lag at a call's end. */
    const val STOPPED_EARLY_MS = 5_000L

    /**
     * Whether recording was off at [at], by the switch's [history]. Asked
     * at the end of a recording read in full (RecordingSaver.write) where
     * Telecom gave no end: the recorder stops a second or two after the
     * call, and its wake to the hang-up comes then, where the hang-up reads
     * the switch - the same moment. Not known before the history began:
     * kept.
     */
    fun offAt(history: List<Pair<Long, CoverageHistory.State>>, at: Long): Boolean =
        CoverageHistory.stateAt(history, at) == CoverageHistory.State.OFF

    /**
     * What a kept recording is written as: [parts], [message] or not, the
     * clocks' [clockOffset] the packets are placed by, the [orWhole] saved
     * when no part catches a packet, and how many calls a message did not
     * keep ([notKept]).
     */
    class Cut(
        val parts: List<RecordingSaver.Part>,
        val message: Boolean,
        val clockOffset: Long?,
        val orWhole: RecordingSaver.Part?,
        val notKept: Int,
        /** Packets placed by their stamps from the first packet's
         *  (PacketClock), as a message's start is reckoned. */
        val fromFirst: Boolean = false,
    )

    /**
     * Cut [decision] to what is written, by what the recording's [file] name
     * says and Telecom's account ([tracked]) of the call that began at
     * [startedAt].
     *
     * A message is saved from its start, without the silence of the caller
     * listening to the greeting; a call the user took over, from just before
     * they took it, or the caller's first words if earlier
     * (AnswerMachine.takenStart) - an ordinary recording. A message is the
     * answered call's alone. A call answered during it - a waiting one, which
     * takes the message over - has its first moments in the file, recorded
     * before the recorder heard of it: no message, and in Ask first never
     * asked for (Calls.splitOffMessage).
     *
     * Placed by the packets' own stamps, with the clocks' offset from
     * Telecom's account (PacketClock, issue #40). A message is cut from its
     * first packet, which is where the recorder measured its start from, by
     * the packets' stamps from that one's (PacketClock.fromFirst).
     */
    fun cut(decision: Decision.Save, file: DaemonFile, startedAt: Long, tracked: List<CallTracking.Tracked>): Cut {
        val messageStart = (file.message as? DaemonFile.Message.Starts)?.ms
        val trimFrom = messageStart ?: file.takenFromMs
        val own = decision.parts.filter { it.startMillis == startedAt }
        val kept = if (messageStart != null && own.isNotEmpty()) own else decision.parts
        return Cut(
            parts = if (trimFrom != null) kept.map { it.from(trimFrom / CallTracking.PACKET_MS) } else kept,
            message = messageStart != null,
            clockOffset = if (trimFrom == null) tracked.firstOrNull()?.clockOffset else null,
            orWhole = if (trimFrom == null) wholeIfNothingFits(decision, tracked) else null,
            notKept = decision.parts.size - kept.size,
            fromFirst = trimFrom != null,
        )
    }

    /**
     * What a recording cut to its call is saved as when the cut catches none
     * of its packets, though it has some: whole, under the call's name,
     * ringing and all. Telecom's account and the packets then disagree about
     * when the call was, and a recording deleted as one with no audio cannot
     * be made again - issue #40 deleted calls that way, a cut placed by
     * counting packets that had not come while the call rang. Only for a
     * recording of one call, kept: with more, the cut is what keeps them
     * apart, and the rules may have left one of them out.
     */
    fun wholeIfNothingFits(decision: Decision.Save, tracked: List<CallTracking.Tracked>): RecordingSaver.Part? {
        if (tracked.size != 1 || decision.leftOut.isNotEmpty()) return null
        val part = decision.parts.singleOrNull() ?: return null
        return RecordingSaver.Part.whole(part.name, part.incoming, part.startMillis, part.callAt)
    }
}
