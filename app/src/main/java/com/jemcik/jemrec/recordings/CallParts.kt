package com.jemcik.jemrec.recordings

import android.content.Context
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs

/**
 * The recorder's files that are one call's parts - a recorder lost mid-call
 * having left more than one - noted by the call's service as it learns of
 * them (CallMonitorService), for whichever collector takes them: each saved
 * as a part, no short call (RecordingSaver.save's part), and a reconcile
 * saving the ones it finds together and telling the call once (reconcile).
 * Left to a reconcile one by one - the call's watch ending the call with no
 * recorder to say it, a process killed mid-save - each was told as a call
 * of its own and judged a short call (review round 14). A group goes once no
 * file of it is on the recorder.
 */
internal object CallParts {
    private const val KEY = "call_parts"

    /** Every group noted. */
    fun groups(context: Context): List<Set<String>> =
        Prefs.of(context).getStringSet(KEY, emptySet()).orEmpty().map { it.split('|').toSet() }

    /** [files], one call's: noted with more than one - or [alone], one by
     *  itself, what a recorder lost mid-call left of a call that went on
     *  unrecorded: a part, no short call (review round 20). */
    @Synchronized
    fun note(context: Context, files: Collection<String>, alone: Boolean = false) {
        if (files.isEmpty() || files.size < 2 && !alone) return
        val entry = files.toSortedSet().joinToString("|")
        val now = Prefs.of(context).getStringSet(KEY, emptySet()).orEmpty()
        // One group per call: a file in an older group is this call's now.
        val kept = now.filterNot { group -> group.split('|').any { it in files } }
        Prefs.of(context).edit { putStringSet(KEY, kept.toSet() + entry) }
    }

    fun contains(context: Context, recfile: String): Boolean = groups(context).any { recfile in it }

    /** The groups with no file on the recorder ([listed], known whole) go. */
    @Synchronized
    fun prune(context: Context, listed: Collection<String>) {
        val now = Prefs.of(context).getStringSet(KEY, emptySet()).orEmpty()
        val kept = now.filter { group -> group.split('|').any { it in listed } }.toSet()
        if (kept != now) Prefs.of(context).edit { putStringSet(KEY, kept) }
    }
}

/**
 * Messages whose recorder was lost while the answering machine took them
 * (CallMonitorService): the recorder names a message's file only as its
 * call ends (Calls.endCall), which a killed recorder never does, so the file
 * was saved as an ordinary call - no "Message from", the short-call rule
 * taking the caller's words, a test call's recording kept as the user's
 * (review round 14). Noted by when the greeting began and the call's own
 * start where Telecom gave it: the recorder's file begun between the two is
 * that message's - saved whole, the greeting at its start, its message
 * start unknown. Kept a week, as the ledger.
 */
internal object LostMessages {
    private const val KEY = "lost_messages"
    private const val KEEP_MS = 7L * 24 * 60 * 60 * 1000

    /** How long before the greeting a message's recording can have begun,
     *  where the call's own start is not known: the machine answers after
     *  its delay, and its greeting starts on the off-hook. */
    private const val BEFORE_MS = 60_000L

    /** [counted] false: a test call's other file - the one the recorder
     *  that replaced the lost one began in its call - dropped as the test's,
     *  the test call counted once already, by the message's own file
     *  (CallMonitorService.messageLostUnseen; review round 33). */
    data class Lost(val greetingAt: Long, val callAt: Long?, val test: Boolean, val counted: Boolean = true)

    @Synchronized
    fun note(context: Context, lost: Lost) {
        val now = System.currentTimeMillis()
        val kept = Prefs.of(context).getStringSet(KEY, emptySet()).orEmpty()
            .filter { (it.substringBefore(':').toLongOrNull() ?: 0L) > now - KEEP_MS }
        val entry = "${lost.greetingAt}:${lost.callAt ?: -1}:${if (lost.test) "t" else "m"}" +
            if (lost.counted) "" else ":n"
        Prefs.of(context).edit { putStringSet(KEY, kept.toSet() + entry) }
    }

    fun all(context: Context): List<Lost> =
        Prefs.of(context).getStringSet(KEY, emptySet()).orEmpty().mapNotNull { entry ->
            val p = entry.split(':')
            val at = p.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
            Lost(at, p.getOrNull(1)?.toLongOrNull()?.takeIf { it >= 0 }, p.getOrNull(2) == "t", p.getOrNull(3) != "n")
        }

    /** The lost message a recording begun at [startedAt] is, if any: the
     *  latest file begun before its greeting - no other known recording
     *  ([others]' starts) between the two; an earlier call's file, retried
     *  later, was taken for it, saved as a message or deleted as a test
     *  (review round 15). Pure. */
    fun of(lost: List<Lost>, startedAt: Long, others: Collection<Long> = emptyList()): Lost? =
        lost.filter { l ->
            startedAt <= l.greetingAt && startedAt >= (l.callAt ?: (l.greetingAt - BEFORE_MS)) &&
                others.none { it in (startedAt + 1)..l.greetingAt }
        }.minByOrNull { it.greetingAt - startedAt }

    /** [lost] was found and saved, or dropped: no other file is it. */
    @Synchronized
    fun used(context: Context, lost: Lost) {
        val now = Prefs.of(context).getStringSet(KEY, emptySet()).orEmpty()
        val gone = now.filter { it.substringBefore(':').toLongOrNull() == lost.greetingAt }.toSet()
        if (gone.isNotEmpty()) Prefs.of(context).edit { putStringSet(KEY, now - gone) }
    }
}
