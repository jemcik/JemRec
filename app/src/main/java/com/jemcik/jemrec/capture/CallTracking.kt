package com.jemcik.jemrec.capture

import android.content.Context
import com.jemcik.jemrec.Prefs
import kotlin.math.abs

/**
 * Exact call tracking: Telecom's own account of each phone call, kept so a
 * recording can be cut to the call and named for it.
 *
 * WHAT IT IS. Telecom binds a background in-call service (CallTracker) for an
 * app whose MANAGE_ONGOING_CALLS permission is allowed for its uid - which the
 * daemon, as shell, can do (Main: the 'T' command and JEMREC_TRACK). It is
 * then told, as they happen, each call's direction, number, the moment it was
 * answered and the moment it ended. Measured on an Honor (MagicOS 11 /
 * Android 17), 2026-10-20: an outgoing call created 22:33:30.806, answered
 * 22:33:34.486 - the daemon, which starts at off-hook, had recorded 3.6 s of
 * ringing by then. Allowing it per PACKAGE was not enough there; per uid was.
 *
 * WHAT IT IS FOR
 *   - A recording starts when the call is answered: the ringing of an
 *     outgoing call is cut, and one nobody answered is not saved at all.
 *   - Call waiting: one recording of two calls is saved as one per call.
 *   - Who it was, exactly and at once, without waiting for the call log -
 *     but only with the call-log permission, the same consent as before.
 *   - Whose calls to keep can leave a call out before it is recorded.
 *   - The end of a call reaches the app directly, a second way to save it.
 *
 * A SWITCH, ON UNLESS TURNED OFF. The permission would let an app see every
 * call and control it; JemRec only reads. Off is exactly how JemRec worked
 * before this: nothing is bound and nothing here is used.
 *
 * Kept in the app's preferences, the last [KEEP] calls, numbers only with the
 * call-log permission (the call log has them anyway).
 */
object CallTracking {

    private const val KEY_ON = "call_tracking"
    private const val KEY_CALLS = "tracked_calls"
    private const val KEEP = 40

    /** One packet of a recording: Opus frames are 20 ms (OpusOggWriter). */
    internal const val PACKET_MS = 20L

    fun enabled(context: Context): Boolean = Prefs.of(context).getBoolean(KEY_ON, true)

    fun setEnabled(context: Context, on: Boolean) {
        val edit = Prefs.of(context).edit().putBoolean(KEY_ON, on)
        if (!on) edit.remove(KEY_CALLS)
        edit.apply()
    }

    /** One call as Telecom told it. Times are wall-clock millis, 0 for not yet. */
    data class Tracked(
        /** When Telecom created the call - ring start or dialling. */
        val id: Long,
        val incoming: Boolean,
        val number: String?,
        /** Each moment it became active: answered, and again after a hold. */
        val activations: List<Long>,
        val endedAt: Long,
    ) {
        val answered: Boolean get() = activations.isNotEmpty()
        val answeredAt: Long get() = activations.firstOrNull() ?: 0L
        /** Seconds from answer to end, as the call log counts them. */
        val seconds: Int get() = if (answered && endedAt > answeredAt) ((endedAt - answeredAt) / 1000).toInt() else 0
    }

    // --- the record -------------------------------------------------------------

    fun calls(context: Context): List<Tracked> =
        (Prefs.of(context).getStringSet(KEY_CALLS, emptySet()) ?: emptySet()).mapNotNull(::decode)

    /** Change one call's entry, creating it if new. */
    @Synchronized
    fun update(context: Context, id: Long, change: (Tracked?) -> Tracked) {
        val all = calls(context).associateBy { it.id }.toMutableMap()
        all[id] = change(all[id])
        val kept = all.values.sortedBy { it.id }.takeLast(KEEP)
        Prefs.of(context).edit().putStringSet(KEY_CALLS, kept.map(::encode).toSet()).apply()
    }

    /** Not in any phone number, and plain in the preferences' XML - which
     *  cannot hold a NUL, the separator this began with, and wrote it as
     *  `&#0;`, a character reference XML 1.0 does not allow. */
    private const val SEP = '|'

    internal fun encode(t: Tracked): String = listOf(
        t.id.toString(),
        if (t.incoming) "in" else "out",
        t.number ?: "",
        t.activations.joinToString(","),
        t.endedAt.toString(),
    ).joinToString(SEP.toString())

    internal fun decode(s: String): Tracked? {
        val p = s.split(SEP)
        if (p.size != 5) return null
        return Tracked(
            id = p[0].toLongOrNull() ?: return null,
            incoming = p[1] == "in",
            number = p[2].ifEmpty { null },
            activations = p[3].split(',').mapNotNull { it.toLongOrNull() },
            endedAt = p[4].toLongOrNull() ?: return null,
        )
    }

    // --- a recording against the record: pure, for the tests ----------------------

    /** How long before a call's start Telecom can have created it: the ring of
     *  an incoming call, or dialling for an outgoing one. */
    private const val BEFORE_MS = 3 * 60 * 1000L
    /** The daemon starts at off-hook, a beat after Telecom's own record. */
    private const val SLACK_MS = 3_000L
    /** How close to the recording's start a call of the other direction must
     *  have gone off-hook to be its own. Measured: 4 to 66 ms. */
    private const val OFF_HOOK_MS = 1_000L
    /** The daemon confirms a call's end this long after telephony's idle
     *  (CallStates.END_CHECK_MS); a call that goes off-hook before then is
     *  recorded into the same file. */
    private const val JOIN_MS = 2_000L

    /**
     * The calls a recording that began at [start] holds: the one that went
     * off-hook as it started, of its direction, and every call the daemon
     * went on recording into the same file. The recording's own call comes
     * first. Empty when Telecom told us nothing, and the recording is then
     * saved as it always was.
     *
     * The daemon ends a file only when the phone has gone idle for two
     * seconds (CallStates). So a file holds, besides its own call:
     *   - a call answered while those were up - call waiting;
     *   - a call that rang while they were up, answered after they ended -
     *     the phone went from the call to ringing, never idle;
     *   - a call that went off-hook within two seconds of their end - a
     *     redial, after a call nobody answered.
     * Each one joined stretches the file's end, so the chain is followed
     * until no more join. Leaving any of them out drops its audio - or, when
     * the own call was never answered, deletes the whole file as unanswered.
     *
     * The own call is the one whose off-hook - its answer, or its dialling -
     * is closest to the start: a second call created a moment after it
     * would otherwise be taken for it, and the call that was recorded left
     * out.
     *
     * The direction is the daemon's, from whether the phone rang first, and
     * can be wrong; Telecom's is not. So when no call of that direction fits,
     * the one that went off-hook as the recording started is taken whichever
     * way it went - its name and the ledger then say what Telecom said.
     *
     * Also empty for a call Telecom has said neither was answered nor ended:
     * its account is incomplete - a missed callback, or the end not noted yet
     * - and a recording is deleted as unanswered only on Telecom's word. A
     * lost recording cannot be made again.
     */
    internal fun callsIn(all: List<Tracked>, start: Long, incoming: Boolean): List<Tracked> {
        // Not one that had ended before the recording began: that is the
        // call before, and taking it for this one deletes this one as
        // unanswered when the call before was never answered.
        val candidates = all.filter { it.endedAt == 0L || it.endedAt >= start }
        val closest = compareBy<Tracked>({ offHookAt(it)?.let { at -> abs(at - start) } ?: Long.MAX_VALUE }, { -it.id })
        val own = candidates
            .filter { it.incoming == incoming && it.id in (start - BEFORE_MS)..(start + SLACK_MS) }
            .minWithOrNull(closest)
            ?: candidates
                .filter { it.incoming != incoming && offHookAt(it)?.let { at -> abs(at - start) <= OFF_HOOK_MS } == true }
                .minWithOrNull(closest)
            ?: return emptyList()
        if (!own.answered && own.endedAt == 0L) return emptyList()

        val joined = mutableListOf(own)
        var end = endOf(own)
        while (true) {
            val next = all.filter { call ->
                call !in joined && call.answered && call.answeredAt >= start && (
                    call.answeredAt <= end ||
                        call.id <= end ||
                        offHookAt(call)?.let { it <= end + JOIN_MS } == true
                    )
            }
            if (next.isEmpty()) break
            joined += next
            end = maxOf(end, next.maxOf { endOf(it) })
        }
        // Own first, whatever the ids: a call created earlier can still be
        // answered later, during this one.
        return listOf(own) + (joined - own).sortedBy { it.id }
    }

    /** When a call ended, or never (still up, as far as anyone said). */
    private fun endOf(call: Tracked): Long = if (call.endedAt == 0L) Long.MAX_VALUE else call.endedAt

    /**
     * Which call each packet of the recording belongs to, as packet ranges:
     * a packet goes to whichever call was answered or taken off hold most
     * recently by then. Packets from before the first answer - the ringing -
     * belong to none and are not saved, and neither are those after a call
     * ended - the second or two the daemon takes to notice. One call gives one
     * range from its answer to its end; call waiting gives one per stretch.
     */
    internal fun segments(start: Long, calls: List<Tracked>): List<Pair<Tracked, LongRange>> {
        val switches = calls
            .flatMap { call -> call.activations.map { at -> at to call } }
            .sortedBy { it.first }
        if (switches.isEmpty()) return emptyList()
        val out = mutableListOf<Pair<Tracked, LongRange>>()
        for ((i, switch) in switches.withIndex()) {
            val (at, call) = switch
            val from = packetAt(start, at)
            val next = switches.getOrNull(i + 1)?.let { packetAt(start, it.first) } ?: Long.MAX_VALUE
            // Cut at the end Telecom gave, never before the stretch's start -
            // a clock change mid-call must not leave a call with nothing.
            val ended = if (call.endedAt > 0) packetAt(start, call.endedAt) else Long.MAX_VALUE
            val until = if (ended > from) minOf(next, ended) else next
            if (until <= from) continue
            // The same call again straight after itself: one stretch.
            val last = out.lastOrNull()
            if (last != null && last.first == call && last.second.last + 1 == from) {
                out[out.size - 1] = call to (last.second.first until until)
            } else {
                out += call to (from until until)
            }
        }
        return out
    }

    /** When a call went off-hook, which is when the daemon starts recording
     *  it: an incoming call at its answer, an outgoing one as it is dialled. */
    private fun offHookAt(call: Tracked): Long? =
        if (call.incoming) call.activations.firstOrNull() else call.id

    private fun packetAt(start: Long, at: Long): Long = ((at - start).coerceAtLeast(0)) / PACKET_MS
}
