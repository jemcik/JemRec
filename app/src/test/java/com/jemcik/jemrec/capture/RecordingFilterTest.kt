package com.jemcik.jemrec.capture

import android.net.Uri
import org.junit.Assert.assertEquals
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
        name: String = if (at == null) "not-one-of-ours.txt" else RecordingStore.nameFor(Date(at), incoming),
    ) = Recording(
        name = name, uri = uri, whenLabel = whenLabel, incoming = incoming,
        durationLabel = "1:02", sizeLabel = "339 KB",
        caller = caller, number = number, callerFavorite = favorite,
    )

    private fun List<Recording>.names() = map { it.name }

    @Test fun allKeepsEverythingInOrder() {
        val list = listOf(rec(now), rec(now - day), rec(null))
        assertEquals(list.names(), filterRecordings(list, "", false).names())
    }

    @Test fun favouritesNarrowToStarredContacts() {
        val alice = rec(now, caller = "Alice", favorite = true)
        val list = listOf(alice, rec(now, caller = "Bob"))
        assertEquals(listOf(alice.name), filterRecordings(list, "", true).names())
        assertEquals(2, filterRecordings(list, "", false).size)
    }

    @Test fun queryMatchesCallerCaseInsensitivelyAndIsTrimmed() {
        val alice = rec(caller = "Alice Smith")
        val list = listOf(alice, rec(caller = "Bob"))
        assertEquals(listOf(alice.name), filterRecordings(list, "  alice ", false).names())
    }

    @Test fun queryMatchesNumberAndWhenLabelButNumberIsNotNormalised() {
        val withNumber = rec(caller = "Alice", number = "+380501234567", whenLabel = "Today 16:53")
        val list = listOf(withNumber, rec(caller = "Bob", whenLabel = "3 Sep, 09:10"))
        assertEquals(listOf(withNumber.name), filterRecordings(list, "0501", false).names())
        assertEquals(listOf(withNumber.name), filterRecordings(list, "today", false).names())
        // Digits with a space in between do not match: search is a raw substring.
        assertEquals(emptyList<String>(), filterRecordings(list, "380 50", false).names())
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

/** The Kept toggle: its own axis, like favourites. */
class KeptFilterTest {

    private val uri: Uri = Mockito.mock(Uri::class.java)

    private fun rec(name: String, kept: Boolean, favorite: Boolean = false) = Recording(
        name = name, uri = uri, whenLabel = "7 Sep, 16:53", incoming = true,
        durationLabel = "1:02", sizeLabel = "339 KB", callerFavorite = favorite, kept = kept,
    )

    @Test fun keptOnlyShowsOnlyKept() {
        val list = listOf(rec("a", kept = true), rec("b", kept = false))
        assertEquals(listOf("a"), filterRecordings(list, "", false, keptOnly = true).map { it.name })
        assertEquals(listOf("a", "b"), filterRecordings(list, "", false, keptOnly = false).map { it.name })
    }

    @Test fun keptAndFavouritesNarrowTogether() {
        val list = listOf(
            rec("both", kept = true, favorite = true),
            rec("kept", kept = true),
            rec("favourite", kept = false, favorite = true),
        )
        assertEquals(listOf("both"), filterRecordings(list, "", true, keptOnly = true).map { it.name })
    }

    @Test fun aSourceLeavesOnlyTheCallsThatCameThatWayAndSourcesCombine() {
        // Named as the recorder names them: a messenger's tag after the
        // direction; none for a phone call.
        val whatsapp = rec("20261021_152400_out_whatsapp.ogg", kept = false)
        val telegram = rec("20261021_141000_in_telegram.ogg", kept = false)
        val phone = rec("20261021_101100_in.ogg", kept = false)
        val list = listOf(whatsapp, telegram, phone)
        fun names(vararg sources: String) = filterRecordings(list, "", false, sources = sources.toSet()).map { it.name }
        assertEquals(listOf(whatsapp.name), names("whatsapp"))
        assertEquals(listOf(phone.name), names(PHONE_SOURCE))
        // Two sources: calls that came either way - a second can only widen.
        assertEquals(listOf(whatsapp.name, telegram.name), names("whatsapp", "telegram"))
        assertEquals(list.map { it.name }, names())
        // Still narrowed by the other chips: none of these is kept.
        assertEquals(emptyList<String>(), filterRecordings(list, "", false, keptOnly = true, sources = setOf("whatsapp", "telegram")))
    }
}
