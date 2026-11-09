package com.jemcik.jemrec.ui.screens

import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasScrollToIndexAction
import com.jemcik.jemrec.calls.CallLogLookup
import com.jemcik.jemrec.recordings.Recordings
import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.CallsNarrowing
import com.jemcik.jemrec.ui.HomeTab
import com.jemcik.jemrec.ui.RecordingsNarrowing
import com.jemcik.jemrec.ui.Screen
import com.jemcik.jemrec.ui.UiState
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * The lists' and Settings' parts that only a drawn screen shows: the four
 * Allow buttons for the call log, the narrowing line over an empty list,
 * where the search field's focus goes, and the endless list's next page.
 * Pinned before the composables are reworked (the 1.0 restructuring).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class ListScreensTest : ScreenTest() {

    private val app get() = RuntimeEnvironment.getApplication()

    @After fun noStandIns() {
        Recordings.override = null
    }

    /**
     * [state]'s Allow, tapped: Android is asked for the call log and the
     * contacts; then refused, and the refusal counts as the answer - the
     * offer is not made again.
     */
    private fun allowAsksForTheCallLog(state: UiState, scrollTo: Boolean = false) {
        Recordings.override = { emptyList() }
        val vm = viewModel(state)
        show { AppScaffold(state, vm) }
        val allow = compose.onNodeWithText("Allow")
        if (scrollTo) allow.performScrollTo()
        allow.performClick()
        compose.waitForIdle()
        val asked = shadowOf(compose.activity).lastRequestedPermission
        assertArrayEquals(CallLogLookup.PERMISSIONS, asked.requestedPermissions)
        compose.activity.onRequestPermissionsResult(
            asked.requestCode,
            asked.requestedPermissions,
            IntArray(asked.requestedPermissions.size) { PackageManager.PERMISSION_DENIED },
        )
        compose.waitForIdle()
        assertFalse(vm.state.value.offerCallLog)
        assertTrue(CallLogLookup.bannerDismissed(app))
        settle(vm)
    }

    @Test fun theRecordingsOfferAsksForTheCallLog() =
        allowAsksForTheCallLog(Fixtures.ready().copy(offerCallLog = true, canReadCallLog = false))

    @Test fun theCallsTabAsksForTheCallLog() =
        allowAsksForTheCallLog(Fixtures.ready().copy(homeTab = HomeTab.CALLS, canReadCallLog = false))

    @Test fun settingsAsksForTheCallLog() =
        allowAsksForTheCallLog(Fixtures.ready().copy(screen = Screen.SETTINGS, canReadCallLog = false))

    @Test fun whoseCallsAsksForTheCallLog() =
        allowAsksForTheCallLog(Fixtures.ready().copy(screen = Screen.WHOSE_CALLS, canReadCallLog = false), scrollTo = true)

    /** With no recordings at all, a chip still on is not said: "No
     *  recordings yet" says it all, as the Calls tab's empty log does. It
     *  said "Kept · 0 of 0", with Show all, until the author's word
     *  (2026-11-08). */
    @Test fun overNoRecordingsNoNarrowingIsSaid() {
        val recordings = Fixtures.ready().copy(recordings = emptyList(), recordingsNarrowing = RecordingsNarrowing(kept = true))
        show { AppScaffold(recordings, viewModel(recordings)) }
        compose.onNodeWithText("No recordings yet").assertExists()
        compose.onNodeWithText("Kept · 0 of 0").assertDoesNotExist()
        compose.onNodeWithText("Show all").assertDoesNotExist()
    }

    /** The Calls tab, its log empty, has no narrowing line either. */
    @Test fun overAnEmptyCallLogNoNarrowingIsSaid() {
        val calls = Fixtures.ready().copy(homeTab = HomeTab.CALLS, canReadCallLog = true, calls = emptyList(), callsNarrowing = CallsNarrowing(kept = true))
        show { AppScaffold(calls, viewModel(calls)) }
        compose.onNodeWithText("No calls in the call log yet.").assertExists()
        compose.onNodeWithText("Show all").assertDoesNotExist()
    }

    /** Search opened: the field takes the focus, keyboard and all. Then
     *  selection takes it away again. */
    @Test fun openingSearchFocusesTheFieldAndSelectingDropsIt() {
        val vm = viewModel(Fixtures.ready())
        showApp(vm)
        compose.onNodeWithContentDescription("Search recordings").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        vm.startSelecting()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    /** On a phone on its side the field sits in the panel, which takes the
     *  focus as it opens, not the list's own. */
    @Test
    @Config(qualifiers = "w800dp-h360dp-hdpi")
    fun inThePanelTheFieldIsFocusedAsItOpens() {
        val vm = viewModel(Fixtures.ready())
        showApp(vm)
        compose.onNodeWithText("Search").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    /**
     * Near the end of what is drawn, twenty more come - and only there. The
     * first frame, before the list is measured, read as near its end: every
     * time the tab was shown anew another page came, and select-all then
     * picked rows nobody had scrolled to.
     */
    @Test fun theListGrowsByAPageNearItsEndOnly() {
        val many = List(65) { i -> Fixtures.recordings()[0].copy(name = "20261021_${"%06d".format(100000 + i)}_in.ogg") }
        Recordings.override = { many }
        val vm = viewModel(Fixtures.ready().copy(recordings = many))
        showApp(vm)
        assertEquals("the first page only", 20, vm.state.value.visibleCount)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(21)
        compose.waitForIdle()
        assertEquals(40, vm.state.value.visibleCount)

        // Away to the Calls tab and back: the list starts at its top again,
        // and adds nothing.
        vm.showTab(HomeTab.CALLS)
        settle(vm)
        compose.waitForIdle()
        vm.showTab(HomeTab.RECORDINGS)
        settle(vm)
        compose.waitForIdle()
        assertEquals(40, vm.state.value.visibleCount)
    }
}
