package com.jemcik.jemrec.ui

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/** The Today / Yesterday / Earlier headings over the lists. */
class DaySectionsTest {

    /** 21 Oct 2026, 00:30 local - half an hour into a day, where "the last
     *  24 hours" and "yesterday" disagree. */
    private val now = Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, 21, 0, 30, 0) }.timeInMillis

    private fun at(day: Int, hour: Int, minute: Int = 0): Long =
        Calendar.getInstance().apply { set(2026, Calendar.OCTOBER, day, hour, minute, 0) }.timeInMillis

    @Test fun headingsFollowTheCalendarNotTheClock() {
        assertEquals(Day.TODAY, dayOf(at(21, 0, 5), now))
        // An hour ago, but before midnight: yesterday, not today.
        assertEquals(Day.YESTERDAY, dayOf(at(20, 23, 30), now))
        assertEquals(Day.YESTERDAY, dayOf(at(20, 0, 1), now))
        assertEquals(Day.EARLIER, dayOf(at(19, 23, 59), now))
    }

    @Test fun unknownIsOtherAndTheFutureIsToday() {
        // A name that is not one of ours has no time; the list sorts it last,
        // under a heading of its own - not "Earlier", which its row's file
        // date ("Today 16:02") could contradict.
        assertEquals(Day.OTHER, dayOf(null, now))
        // A clock set back after a call: still today, not dropped under Earlier.
        assertEquals(Day.TODAY, dayOf(at(22, 9), now))
    }

    @Test fun runsKeepTheListsOrder() {
        val times = listOf(at(21, 0, 10), at(20, 18), at(20, 9), at(12, 11), null)
        val runs = byDay(times, now) { it }
        assertEquals(listOf(Day.TODAY, Day.YESTERDAY, Day.EARLIER, Day.OTHER), runs.map { it.first })
        assertEquals(listOf(1, 2, 1, 1), runs.map { it.second.size })
        // Nothing is lost or reordered.
        assertEquals(times, runs.flatMap { it.second })
    }

    @Test fun rowsUnderADayShowOnlyTheTime() {
        // The date follows the phone's language; pinned here so the test does
        // not depend on the machine running it.
        val before = Locale.getDefault()
        Locale.setDefault(Locale.UK)
        try {
            assertEquals("09:05", timeUnder(Day.TODAY, at(21, 9, 5)))
            assertEquals("18:00", timeUnder(Day.YESTERDAY, at(20, 18)))
            // Under Earlier the date is the only place the day is said.
            assertEquals("12 Oct, 11:00", timeUnder(Day.EARLIER, at(12, 11)))
            // The cached formatters follow a change of language.
            Locale.setDefault(Locale.GERMANY)
            assertEquals(true, timeUnder(Day.EARLIER, at(12, 11)).startsWith("12 Okt"))
        } finally {
            Locale.setDefault(before)
        }
    }

    @Test fun aDayIsKeyedByItsYearAndItsDayOfTheYear() {
        // 21 October 2026 is the 294th day of 2026.
        assertEquals(2026 * 1000 + 294, dayStamp(at(21, 12)))
    }

    @Test fun groupingIsRedoneWhenTheDayTurns() {
        assertEquals(dayStamp(at(21, 0, 1)), dayStamp(at(21, 23, 59)))
        assertEquals(false, dayStamp(at(20, 23, 59)) == dayStamp(at(21, 0, 1)))
    }
}
