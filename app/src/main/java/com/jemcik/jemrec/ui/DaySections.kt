package com.jemcik.jemrec.ui

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/*
 * The headings the Recordings and Calls lists are split under: Today,
 * Yesterday, Earlier - the way a phone app's call history reads. Kept out of
 * the screens so they can be tested (DaySectionsTest).
 *
 * With the day said once in a heading, a row needs only the time: "16:02"
 * where it said "Today 16:02", which left the date to be cut off first on a
 * messenger call ("WhatsApp · To...").
 */

/**
 * The headings. OTHER is for a recording whose name carries no time - a file
 * another app named, in the folder: the lists sort it last, and under
 * "Earlier" its row (dated by the file, which can say "Today 16:02") would
 * contradict the heading above it.
 */
internal enum class Day(val title: String) {
    TODAY("Today"), YESTERDAY("Yesterday"), EARLIER("Earlier"), OTHER("Other recordings"),
}

/** Which heading a moment goes under: no moment, OTHER; a moment later today
 *  (a clock moved back) is Today. */
internal fun dayOf(at: Long?, now: Long): Day {
    if (at == null) return Day.OTHER
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
 *  already says the day, the date as well under the others. */
internal fun timeUnder(day: Day, at: Long): String {
    val formats = formats()
    return if (day == Day.TODAY || day == Day.YESTERDAY) {
        formats.time.format(Date(at))
    } else {
        formats.date.format(Date(at))
    }
}

/**
 * The two formatters, made once per thread and remade only when the phone's
 * language or time zone changes: rows compose three times a second while a
 * recording plays, and a SimpleDateFormat is not cheap to build - nor safe to
 * share across threads, hence one per thread.
 */
private class Formats(val locale: Locale, val zone: TimeZone) {
    val time = SimpleDateFormat("HH:mm", Locale.US).apply { timeZone = zone }
    val date = SimpleDateFormat("d MMM, HH:mm", locale).apply { timeZone = zone }
}

private val formatsHere = ThreadLocal<Formats>()

private fun formats(): Formats {
    val locale = Locale.getDefault()
    // getDefault() hands back a fresh copy each time, so compared by id.
    val zone = TimeZone.getDefault()
    return formatsHere.get()?.takeIf { it.locale == locale && it.zone.id == zone.id }
        ?: Formats(locale, zone).also(formatsHere::set)
}

private fun sameDay(a: Calendar, b: Calendar): Boolean =
    a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
