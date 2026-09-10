package com.jemcik.jemrec.capture

import java.util.Calendar

/** The time presets shown as chips above the list: All / Today / This week.
 *  These are mutually exclusive - a recording is in one time range - and pick
 *  the WHEN. Favourites is a separate axis (WHO) and rides its own toggle. */
enum class RecordingFilter { ALL, TODAY, THIS_WEEK }

/**
 * Apply the search text, the time preset, and the favourites toggle to a list.
 *
 * The time preset and favourites are different questions - WHEN a call was and
 * WHO it was with - so they combine rather than compete: favouritesOnly ANDs on
 * top of whatever time range is chosen. "Favourite calls this week" is a thing
 * a person reasonably wants to narrow to.
 *
 * Shared by the list (what to show) and select-all (what "all" means now), so
 * the two can never disagree about which rows are on screen. Pure and cheap;
 * it runs on every keystroke and chip tap.
 */
fun filterRecordings(
    recordings: List<Recording>,
    query: String,
    filter: RecordingFilter,
    favoritesOnly: Boolean,
): List<Recording> {
    val q = query.trim()
    val now = System.currentTimeMillis()
    return recordings.filter { r ->
        val passesTime = when (filter) {
            RecordingFilter.ALL -> true
            RecordingFilter.TODAY -> r.startedAtMillis?.let { sameDay(it, now) } ?: false
            RecordingFilter.THIS_WEEK -> r.startedAtMillis?.let { withinDays(it, now, 7) } ?: false
        }
        val passesFavorite = !favoritesOnly || r.callerFavorite
        val passesQuery = q.isEmpty() ||
            r.caller?.contains(q, ignoreCase = true) == true ||
            r.number?.contains(q) == true ||
            r.whenLabel.contains(q, ignoreCase = true)
        passesTime && passesFavorite && passesQuery
    }
}

private fun sameDay(a: Long, b: Long): Boolean {
    val ca = Calendar.getInstance().apply { timeInMillis = a }
    val cb = Calendar.getInstance().apply { timeInMillis = b }
    return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR) &&
        ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR)
}

/** A rolling window: within the last N days, and not in the future. */
private fun withinDays(at: Long, now: Long, days: Int): Boolean {
    val ago = now - days.toLong() * 24 * 60 * 60 * 1000
    return at in ago..now
}
