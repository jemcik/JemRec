package com.jemcik.jemrec.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/*
 * The headings the Recordings and Calls lists are split under: Today,
 * Yesterday, Earlier - the way a phone app's call history reads. Kept out of
 * the screens so they can be tested (DaySectionsTest).
 *
 * With the day said once in a heading, a row needs only the time: "16:02"
 * where it said "Today 16:02", which left the date to be cut off first on a
 * messenger call ("WhatsApp · To...").
 */

internal enum class Day(val title: String) { TODAY("Today"), YESTERDAY("Yesterday"), EARLIER("Earlier") }

/** Which heading a moment goes under. Unknown goes under Earlier, where the
 *  lists already sort it; a moment later today (a clock moved back) is Today. */
internal fun dayOf(at: Long?, now: Long): Day {
    if (at == null) return Day.EARLIER
    val day = Calendar.getInstance().apply { timeInMillis = at }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    if (at > now || sameDay(day, today)) return Day.TODAY
    today.add(Calendar.DAY_OF_YEAR, -1)
    return if (sameDay(day, today)) Day.YESTERDAY else Day.EARLIER
}

/**
 * The list cut into runs under their headings, in its own order. The lists are
 * newest first, so each heading is one run; were they not, a heading would
 * repeat rather than reorder anything.
 */
internal fun <T> byDay(items: List<T>, now: Long, at: (T) -> Long?): List<Pair<Day, List<T>>> {
    val runs = mutableListOf<Pair<Day, MutableList<T>>>()
    for (item in items) {
        val day = dayOf(at(item), now)
        if (runs.lastOrNull()?.first == day) runs.last().second += item else runs += day to mutableListOf(item)
    }
    return runs
}

/** Which calendar day [now] is in, as one number: what a remembered
 *  grouping is keyed on, so it is redone when the day turns, not only when
 *  the list changes. */
internal fun dayStamp(now: Long): Int =
    Calendar.getInstance().apply { timeInMillis = now }.let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }

/** When, as a row under [day] shows it: the time alone where the heading
 *  already says the day, the date as well under Earlier. */
internal fun timeUnder(day: Day, at: Long): String =
    if (day == Day.EARLIER) {
        SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(at))
    } else {
        SimpleDateFormat("HH:mm", Locale.US).format(Date(at))
    }

private fun sameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
