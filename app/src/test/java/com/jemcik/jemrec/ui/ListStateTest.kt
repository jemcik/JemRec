package com.jemcik.jemrec.ui

import android.net.Uri
import com.jemcik.jemrec.capture.Recording
import com.jemcik.jemrec.capture.RecordingFilter
import com.jemcik.jemrec.capture.RecordingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.mockito.Mockito
import java.util.Date

/** The recordings list's small decisions: each once acted on the wrong rows. */
class ListStateTest {

    private val uri: Uri = Mockito.mock(Uri::class.java)
    private val now = System.currentTimeMillis()
    private val day = 24L * 60 * 60 * 1000

    private fun rec(daysAgo: Long, kept: Boolean = false) = Recording(
        name = RecordingStore.nameFor(Date(now - daysAgo * day - 60_000), true),
        uri = uri, whenLabel = "", incoming = true, durationLabel = "", sizeLabel = "",
        durationMillis = 60_000, kept = kept,
    )

    @Test fun selectAllCountsRowsNotNumbers() {
        // 20 picked, then a call saved while selecting: 21 shown, the new one
        // not picked. Not "all" - and tapping must pick it, not drop the rest.
        val shown = (1..21).map { "r$it" }
        val selected = (1..20).map { "r$it" }.toSet()
        assertEquals(Picked.SOME, picked(selected, shown))
        assertEquals(Picked.ALL, picked(shown.toSet(), shown))
        assertEquals(Picked.NONE, picked(emptySet(), shown))
        // Picked rows no longer shown are not "some" of what is shown.
        assertEquals(Picked.NONE, picked(setOf("gone"), shown))
    }

    @Test fun aRowThatLeftTheListLeavesTheSelection() {
        // Under "Today", a row from yesterday is not shown: not deletable.
        val today = rec(daysAgo = 0)
        val yesterday = rec(daysAgo = 1)
        val state = UiState(
            recordings = listOf(today, yesterday),
            filter = RecordingFilter.TODAY,
            selecting = true,
            selected = setOf(today.name, yesterday.name),
        )
        assertEquals(setOf(today.name), stillShown(state))
    }

    @Test fun aNotificationsRecordingIsBroughtOnScreen() {
        val narrowed = UiState(
            screen = Screen.SETTINGS, homeTab = HomeTab.CALLS,
            query = "x", searchOpen = true, filter = RecordingFilter.TODAY,
            favoritesOnly = true, keptOnly = true, selecting = true, selected = setOf("a"),
        )
        val shown = narrowed.revealed()
        assertEquals(Screen.HOME, shown.screen)
        assertEquals(HomeTab.RECORDINGS, shown.homeTab)
        assertEquals(RecordingFilter.ALL, shown.filter)
        assertEquals("", shown.query)
        assertFalse(shown.keptOnly)
        assertFalse(shown.favoritesOnly)
        assertFalse(shown.selecting)
    }
}
