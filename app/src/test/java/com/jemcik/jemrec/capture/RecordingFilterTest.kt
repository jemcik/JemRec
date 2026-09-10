package com.jemcik.jemrec.capture

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.mockito.Mockito
import java.util.Calendar
import java.util.Date

/**
 * The list's filtering, which is shared by the visible list and select-all -
 * so a wrong answer here is a wrong row AND a wrong bulk delete.
 *
 * Recordings are built with the exact names RecordingStore gives them, so
 * startedAtMillis parses the same way it does in the app. A Uri mock stands in
 * for the one thing the JVM cannot make.
 */
class RecordingFilterTest {

    private val uri: Uri = Mockito.mock(Uri::class.java)
    private val now = System.currentTimeMillis()
    private val day = 24L * 60 * 60 * 1000

    private fun rec(
        at: Long? = now,
        incoming: Boolean = true,
        caller: String? = null,
        number: String? = null,
        favorite: Boolean = false,
        whenLabel: String = "7 Sep, 16:53",
        name: String = if (at == null) "milestone1.txt" else RecordingStore.nameFor(Date(at), incoming),
    ) = Recording(
        name = name, uri = uri, whenLabel = whenLabel, incoming = incoming,
        durationLabel = "1:02", sizeLabel = "339 KB",
        caller = caller, number = number, callerFavorite = favorite,
    )

    private fun List<Recording>.names() = map { it.name }

    @Test fun allKeepsEverythingInOrder() {
        val list = listOf(rec(now), rec(now - day), rec(null))
        assertEquals(list.names(), filterRecordings(list, "", RecordingFilter.ALL, false).names())
    }

    @Test fun todayKeepsOnlyTodaysCalls() {
        val today = rec(now)
        val list = listOf(today, rec(now - day), rec(now + day), rec(null))
        assertEquals(listOf(today.name), filterRecordings(list, "", RecordingFilter.TODAY, false).names())
    }

    @Test fun thisWeekIsARollingWindowThatExcludesTheFuture() {
        val recent = rec(now - 6 * day)
        val list = listOf(rec(now + day), recent, rec(now - 8 * day), rec(null))
        assertEquals(listOf(recent.name), filterRecordings(list, "", RecordingFilter.THIS_WEEK, false).names())
    }

    @Test fun unparseableNamesNeverPassATimePreset() {
        assertNull(rec(null).startedAtMillis)
        val list = listOf(rec(null))
        assertEquals(emptyList<String>(), filterRecordings(list, "", RecordingFilter.TODAY, false).names())
        assertEquals(emptyList<String>(), filterRecordings(list, "", RecordingFilter.THIS_WEEK, false).names())
        assertEquals(list.names(), filterRecordings(list, "", RecordingFilter.ALL, false).names())
    }

    @Test fun favouritesNarrowOnTopOfTheTimeRange() {
        val favToday = rec(now, caller = "Alice", favorite = true)
        val list = listOf(favToday, rec(now, caller = "Bob"), rec(now - day, caller = "Carol", favorite = true))
        assertEquals(listOf(favToday.name), filterRecordings(list, "", RecordingFilter.TODAY, true).names())
        assertEquals(2, filterRecordings(list, "", RecordingFilter.ALL, true).size)
    }

    @Test fun queryMatchesCallerCaseInsensitivelyAndIsTrimmed() {
        val alice = rec(caller = "Alice Smith")
        val list = listOf(alice, rec(caller = "Bob"))
        assertEquals(listOf(alice.name), filterRecordings(list, "  alice ", RecordingFilter.ALL, false).names())
    }

    @Test fun queryMatchesNumberAndWhenLabelButNumberIsNotNormalised() {
        val withNumber = rec(caller = "Alice", number = "+380501234567", whenLabel = "Today 16:53")
        val list = listOf(withNumber, rec(caller = "Bob", whenLabel = "3 Sep, 09:10"))
        assertEquals(listOf(withNumber.name), filterRecordings(list, "0501", RecordingFilter.ALL, false).names())
        assertEquals(listOf(withNumber.name), filterRecordings(list, "today", RecordingFilter.ALL, false).names())
        // Digits with a space in between do not match: search is a raw substring.
        assertEquals(emptyList<String>(), filterRecordings(list, "380 50", RecordingFilter.ALL, false).names())
    }

    @Test fun nameForAndStartedAtMillisAgree() {
        val at = Calendar.getInstance().apply { clear(); set(2026, Calendar.SEPTEMBER, 7, 16, 53, 31) }.timeInMillis
        val name = RecordingStore.nameFor(Date(at), incoming = true)
        assertEquals("20260907_165331_in.ogg", name)
        assertEquals(at, rec(name = name).startedAtMillis)
        assertEquals("20260907_165331_out.ogg", RecordingStore.nameFor(Date(at), incoming = false))
    }

    @Test fun callerTakesTheHeadlineAndTimeStepsDown() {
        val anonymous = rec(whenLabel = "Today 16:53")
        assertEquals("Today 16:53", anonymous.title)
        assertEquals("1:02 · 339 KB", anonymous.subtitle)
        val known = rec(caller = "Alice", whenLabel = "Today 16:53")
        assertEquals("Alice", known.title)
        assertEquals("Today 16:53 · 1:02", known.subtitle)
    }
}
