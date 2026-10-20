package com.jemcik.jemrec.capture

import android.content.Context
import android.util.Log
import com.jemcik.jemrec.Prefs

/**
 * Auto-delete: recordings older than so many days, and calls too short to
 * have been a conversation. Both off until the user turns them on.
 *
 * NOTHING HERE DELETES. Everything it removes goes to Recently deleted (see
 * Trash) and waits there 30 days, because this runs while nobody is looking,
 * and a rule set to 30 days by someone who meant 90 should cost a restore,
 * not a call. Recordings marked to keep (KeepMarks) are never touched.
 *
 * Two moments:
 *   - a short call is caught as it is saved ([dropIfTooShort]), before its
 *     after-call notification - nobody wants "Recorded, 3 s" for a wrong number;
 *   - age, and short calls from before the rule was on, in a pass at most once
 *     a day from the keep-alive job ([runIfDue]), and at once when a setting
 *     changes ([run]). The same pass empties what has expired from the trash
 *     in the places Android does not do it itself.
 */
object Retention {

    private const val TAG = "JemRec"
    private const val KEY_MAX_AGE_DAYS = "auto_delete_after_days"
    private const val KEY_MIN_SECONDS = "auto_delete_shorter_than"
    private const val KEY_LAST_RUN = "auto_delete_last_run"
    private const val DAY_MS = 24L * 60 * 60 * 1000

    /** The choices the settings offer; 0 is off. */
    val AGE_CHOICES = listOf(0, 30, 90, 180, 365)
    val SHORT_CHOICES = listOf(0, 5, 10, 30)

    fun maxAgeDays(context: Context): Int = Prefs.of(context).getInt(KEY_MAX_AGE_DAYS, 0)

    fun minSeconds(context: Context): Int = Prefs.of(context).getInt(KEY_MIN_SECONDS, 0)

    fun setMaxAgeDays(context: Context, days: Int) = save(context, KEY_MAX_AGE_DAYS, days)

    fun setMinSeconds(context: Context, seconds: Int) = save(context, KEY_MIN_SECONDS, seconds)

    private fun save(context: Context, key: String, value: Int) {
        // A changed rule is applied now, not at the next daily pass.
        Prefs.of(context).edit().putInt(key, value).putLong(KEY_LAST_RUN, 0L).apply()
    }

    /**
     * Which of these the rules remove. Pure, for the tests.
     *
     * A recording whose date or length cannot be read is left alone: a rule
     * that guessed would be deleting on a guess.
     */
    internal fun select(
        recordings: List<Recording>,
        now: Long,
        maxAgeDays: Int,
        minSeconds: Int,
    ): List<Recording> = recordings.filter { r ->
        if (r.kept) return@filter false
        val old = maxAgeDays > 0 &&
            r.startedAtMillis?.let { now - it > maxAgeDays * DAY_MS } == true
        val short = minSeconds > 0 && r.durationMillis in 1 until minSeconds * 1000L
        old || short
    }

    /** From the keep-alive job: the pass, if a day has gone by since the last. */
    fun runIfDue(context: Context) {
        val last = Prefs.of(context).getLong(KEY_LAST_RUN, 0L)
        if (System.currentTimeMillis() - last < DAY_MS) return
        run(context)
    }

    /**
     * The pass: expired trash out, then the rules.
     *
     * Recordings Android will not let this app trash without the user's yes -
     * typically ones made before a reinstall - come back as one request in
     * [BulkDeleteResult.needsConsent]. The keep-alive job, with nobody to ask,
     * drops it and tries again tomorrow; the settings screen, where the user
     * has just changed a rule, puts it in front of them.
     */
    fun run(context: Context): BulkDeleteResult {
        // The debug build's invented recordings are not files.
        if (Recordings.override != null) return BulkDeleteResult(0, 0, null)
        val now = System.currentTimeMillis()
        Prefs.of(context).edit().putLong(KEY_LAST_RUN, now).apply()
        Trash.purgeExpired(context, now)

        val maxAge = maxAgeDays(context)
        val minSeconds = minSeconds(context)
        if (maxAge == 0 && minSeconds == 0) return BulkDeleteResult(0, 0, null)
        val doomed = select(Recordings.list(context, withCallers = false), now, maxAge, minSeconds)
        if (doomed.isEmpty()) return BulkDeleteResult(0, 0, null)
        val result = Trash.trashAll(context, doomed)
        Log.i(
            TAG,
            "retention: ${doomed.size} due (older than $maxAge days / under ${minSeconds}s): " +
                "moved ${result.deleted}, failed ${result.failed}, " +
                "${if (result.needsConsent != null) "some need the user's yes" else "none need asking"}",
        )
        return result
    }

    /**
     * A call just saved that is shorter than the rule allows goes straight to
     * the trash, and the caller announces nothing. True if it did.
     *
     * After the save has noted it in the call ledger, so the missed-call check
     * still counts it as recorded: it was, and the user's rule removed it.
     */
    fun dropIfTooShort(context: Context, saved: RecordingSaver.Outcome.Saved): Boolean {
        val min = minSeconds(context)
        if (min == 0 || saved.seconds <= 0 || saved.seconds >= min) return false
        val result = Trash.trash(context, saved.uri, saved.filePath, saved.name)
        Log.i(TAG, "retention: ${saved.name} is %.1fs, under ${min}s -> ${result.javaClass.simpleName}".format(saved.seconds))
        if (result !is DeleteResult.Deleted) return false
        // The list refreshed when the save was announced, a moment ago, and
        // showed it; this has it look again.
        RecordingSaver.announce(saved.name)
        return true
    }
}
