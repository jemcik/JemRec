package com.jemcik.jemrec.capture

import android.content.Context
import com.jemcik.jemrec.Prefs

/**
 * Every call the app already knows about, for the missed-call check.
 *
 * WHY NOT JUST LOOK AT THE RECORDINGS ON DISK
 *
 * Because the user deletes them. A call recorded at 14:05 and deleted from
 * the after-call notification at 14:06 is not on disk by the time the check
 * runs at 14:15, and a check that read the disk would then report it as a call
 * that went unrecorded - an alarm about a recording the user threw away on
 * purpose. So the check asks this instead, and this is written at the moment a
 * call is saved or reported, before anyone has had the chance to delete
 * anything.
 *
 * Three kinds of entry:
 *
 *   - a RECORDING: when the daemon started it, which way the call went, and
 *     how long it ran. Written for every save, and for every call the app
 *     already told the user it could not save - one notification per failure
 *     is enough.
 *   - a REPORTED call: the call log's own date for a call the missed-call
 *     check has already told the user about, so it is never told twice.
 *   - a SKIPPED call: one the user's rules on whose calls to keep left out
 *     (see CallRules). Recorded, and deleted before it was saved, so it is
 *     not a missed call - and the Calls tab can say why it has nothing.
 *
 * Kept for [KEEP_MS] and no longer: the check never looks further back than
 * that, so older entries are dead weight in a preferences file that is read
 * on every call.
 */
internal object CallLedger {

    private const val KEY = "call_ledger"

    /** How far back the missed-call check looks, and so how long this keeps. */
    const val KEEP_MS = 7L * 24 * 60 * 60 * 1000

    data class Recorded(val startMillis: Long, val incoming: Boolean, val seconds: Int)

    data class Ledger(
        val recordings: List<Recorded>,
        val reported: Set<Long>,
        val skipped: List<Recorded> = emptyList(),
    )

    fun read(context: Context): Ledger =
        parse(Prefs.of(context).getStringSet(KEY, emptySet()) ?: emptySet())

    /** A call the daemon recorded, saved or not. */
    fun noteRecording(context: Context, startMillis: Long, incoming: Boolean, seconds: Int) =
        add(context, encodeRecording(Recorded(startMillis, incoming, seconds)))

    /** A call the rules left out: recorded, and deleted unsaved. */
    fun noteSkipped(context: Context, startMillis: Long, incoming: Boolean, seconds: Int) =
        add(context, encodeRecording(Recorded(startMillis, incoming, seconds), kind = "s"))

    /** A call log entry the user has been told was not recorded. */
    fun noteReported(context: Context, callDate: Long) = add(context, "c:$callDate")

    @Synchronized
    private fun add(context: Context, entry: String) {
        val prefs = Prefs.of(context)
        val now = System.currentTimeMillis()
        val kept = (prefs.getStringSet(KEY, emptySet()) ?: emptySet())
            .filter { (timeOf(it) ?: 0L) > now - KEEP_MS }
            .toMutableSet()
        kept += entry
        prefs.edit().putStringSet(KEY, kept).apply()
    }

    /** "r" for a recording, "s" for a call the rules skipped. */
    internal fun encodeRecording(r: Recorded, kind: String = "r"): String =
        "$kind:${r.startMillis}:${if (r.incoming) "in" else "out"}:${r.seconds}"

    /** Tolerant of anything it does not understand: an unreadable entry is
     *  dropped, never a reason to stop checking. */
    internal fun parse(entries: Set<String>): Ledger {
        val recordings = mutableListOf<Recorded>()
        val skipped = mutableListOf<Recorded>()
        val reported = mutableSetOf<Long>()
        for (entry in entries) {
            val parts = entry.split(':')
            when (val kind = parts.firstOrNull()) {
                "r", "s" -> if (parts.size == 4) {
                    val start = parts[1].toLongOrNull() ?: continue
                    val seconds = parts[3].toIntOrNull() ?: continue
                    val recorded = Recorded(start, parts[2] == "in", seconds)
                    if (kind == "r") recordings += recorded else skipped += recorded
                }
                "c" -> parts.getOrNull(1)?.toLongOrNull()?.let { reported += it }
            }
        }
        return Ledger(recordings, reported, skipped)
    }

    private fun timeOf(entry: String): Long? = entry.split(':').getOrNull(1)?.toLongOrNull()
}
