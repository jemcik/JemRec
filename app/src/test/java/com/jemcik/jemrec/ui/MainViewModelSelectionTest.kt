package com.jemcik.jemrec.ui

import android.app.Application
import android.net.Uri
import com.jemcik.jemrec.capture.Recording
import com.jemcik.jemrec.capture.RecordingFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.Mockito

/**
 * The pure state transitions of the home screen: chips, search, selection,
 * and the share chooser. Every method under test is a `_state.update`, so
 * none of this touches viewModelScope, the daemon or the disk.
 *
 * The regressions these pin are the ones the ViewModel's own comments
 * describe: a chip tapped twice returning to All, favourites as its own
 * axis, a narrowing search dropping rows that left the list while a widening
 * one keeps them, and select-all respecting both the query and the page.
 */
class MainViewModelSelectionTest {

    private val uri: Uri = Mockito.mock(Uri::class.java)

    // Deep stubs make getSharedPreferences(..).edit().putBoolean(..).apply()
    // a no-op chain, which is all startSelecting() needs from the Application.
    private val app: Application = Mockito.mock(Application::class.java, Mockito.RETURNS_DEEP_STUBS)
    private lateinit var vm: MainViewModel

    private fun rec(name: String, caller: String? = null) = Recording(
        name = name, uri = uri, whenLabel = "Today 10:00", incoming = name.endsWith("_in.ogg"),
        durationLabel = "1:02", sizeLabel = "339 KB", caller = caller,
    )

    private val alice = rec("20260907_100000_in.ogg", caller = "Alice")
    private val bob = rec("20260907_110000_out.ogg", caller = "Bob")
    private val unknown = rec("20260907_120000_in.ogg")

    private val state get() = vm.state.value

    /** A fresh ViewModel over these recordings. Recordings otherwise only
     *  enter state through refreshNow(), which is device-bound. */
    private fun given(vararg recordings: Recording) {
        vm = MainViewModel(app, UiState(recordings = recordings.toList()))
    }

    @Before fun setUp() = given()

    @Test fun tappingTheActiveChipAgainClearsItToAll() {
        vm.setFilter(RecordingFilter.TODAY)
        assertEquals(RecordingFilter.TODAY, state.filter)
        vm.setFilter(RecordingFilter.TODAY)
        assertEquals(RecordingFilter.ALL, state.filter)
        vm.setFilter(RecordingFilter.THIS_WEEK)
        vm.setFilter(RecordingFilter.TODAY)
        assertEquals(RecordingFilter.TODAY, state.filter)
    }

    @Test fun favouritesIsItsOwnAxis() {
        vm.setFilter(RecordingFilter.THIS_WEEK)
        vm.toggleFavorites()
        assertTrue(state.favoritesOnly)
        assertEquals(RecordingFilter.THIS_WEEK, state.filter)
        vm.toggleFavorites()
        assertFalse(state.favoritesOnly)
    }

    @Test fun foldingSearchAwayDropsTheQuery() {
        vm.toggleSearch()
        vm.setQuery("bob")
        assertTrue(state.searchOpen); assertEquals("bob", state.query)
        vm.toggleSearch()
        assertFalse(state.searchOpen); assertEquals("", state.query)
    }

    @Test fun narrowingTheQueryDropsSelectionsThatLeaveTheListAndWideningKeepsThem() {
        given(alice, bob, unknown)
        vm.startSelecting(alice)
        vm.toggleSelected(bob)
        assertEquals(setOf(alice.name, bob.name), state.selected)
        vm.setQuery("bob")
        assertEquals(setOf(bob.name), state.selected)
        assertTrue(state.selecting)
        vm.setQuery("")
        assertEquals(setOf(bob.name), state.selected)
    }

    @Test fun selectAllVisibleRespectsTheQueryAndThePage() {
        val page = state.visibleCount
        val many = (1..page + 5).map { rec("20260901_%06d_in.ogg".format(it)) }
        given(*many.toTypedArray())
        vm.startSelecting()
        vm.selectAllVisible()
        assertEquals(page, state.selected.size)
        vm.showMore()
        assertEquals(2 * page, state.visibleCount)
        vm.selectAllVisible()
        assertEquals(page + 5, state.selected.size)

        given(alice, bob, unknown)
        vm.startSelecting()
        vm.setQuery("ali")
        vm.selectAllVisible()
        assertEquals(setOf(alice.name), state.selected)
    }

    @Test fun unpickingTheLastRowLeavesSelectionMode() {
        given(alice, bob)
        vm.startSelecting(alice)
        assertTrue(state.selecting); assertEquals(setOf(alice.name), state.selected)
        vm.toggleSelected(alice)
        assertFalse(state.selecting); assertTrue(state.selected.isEmpty())
    }

    @Test fun selectingStopsWhateverIsPlaying() {
        vm = MainViewModel(app, UiState(recordings = listOf(alice, bob), playingName = alice.name, playing = true, durationMs = 62_000))
        vm.startSelecting(bob)
        assertNull(state.playingName)
        assertFalse(state.playing)
        assertTrue(state.selecting)

        vm = MainViewModel(app, UiState(recordings = listOf(alice), playingName = alice.name, playing = true, durationMs = 62_000))
        vm.startSelecting()
        assertNull(state.playingName)
        assertFalse(state.playing)
    }

    @Test fun headerSelectStartsEmptyAndRetiresTheHint() {
        vm.startSelecting()
        assertTrue(state.selecting)
        assertTrue(state.selected.isEmpty())
        assertTrue(state.selectHintSeen)
    }

    @Test fun deselectAllStaysInSelectionModeButClearSelectionLeavesIt() {
        given(alice, bob)
        vm.startSelecting(alice)
        vm.deselectAll()
        assertTrue(state.selecting); assertTrue(state.selected.isEmpty())
        vm.toggleSelected(bob)
        vm.clearSelection()
        assertFalse(state.selecting); assertTrue(state.selected.isEmpty())
    }

    @Test fun shareChoiceOpensAndDismissesWithoutConverting() {
        vm.share(alice)
        assertSame(alice, state.shareChoice)
        assertFalse(state.converting)
        vm.dismissShareChoice()
        assertNull(state.shareChoice)
    }
}
