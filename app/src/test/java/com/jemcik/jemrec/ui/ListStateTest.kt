package com.jemcik.jemrec.ui

import android.net.Uri
import com.jemcik.jemrec.capture.Recording
import com.jemcik.jemrec.capture.RecordingStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        // Under Kept, a row no longer kept is not shown: not deletable.
        val kept = rec(daysAgo = 0, kept = true)
        val unkept = rec(daysAgo = 1)
        val state = UiState(
            recordings = listOf(kept, unkept),
            keptOnly = true,
            selecting = true,
            selected = setOf(kept.name, unkept.name),
        )
        assertEquals(setOf(kept.name), stillShown(state))
    }

    @Test fun aNotificationsRecordingIsBroughtOnScreen() {
        val narrowed = UiState(
            screen = Screen.SETTINGS, homeTab = HomeTab.CALLS,
            query = "x", searchOpen = true, sources = setOf("whatsapp"),
            favoritesOnly = true, keptOnly = true, selecting = true, selected = setOf("a"),
        )
        val shown = narrowed.revealed()
        assertEquals(Screen.HOME, shown.screen)
        assertEquals(HomeTab.RECORDINGS, shown.homeTab)
        assertTrue(shown.sources.isEmpty())
        assertEquals("", shown.query)
        assertFalse(shown.keptOnly)
        assertFalse(shown.favoritesOnly)
        assertFalse(shown.selecting)
    }
}
