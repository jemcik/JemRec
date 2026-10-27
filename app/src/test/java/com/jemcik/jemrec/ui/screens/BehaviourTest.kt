package com.jemcik.jemrec.ui.screens

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import java.util.Locale
import com.jemcik.jemrec.capture.Recording
import android.net.Uri
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.longClick
import com.jemcik.jemrec.ui.dayOf
import com.jemcik.jemrec.ui.Day
import androidx.compose.ui.semantics.SemanticsNode
import com.jemcik.jemrec.ui.UiState
import com.jemcik.jemrec.ui.STEADY_SHOWN
import com.jemcik.jemrec.ui.STEADY_LINE
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.test.getBoundsInRoot
import com.jemcik.jemrec.ui.AnswerUi
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.launch
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Modifier
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.click
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.answerSummary
import com.jemcik.jemrec.ui.MainViewModel
import com.jemcik.jemrec.ui.ChoiceDialog
import com.jemcik.jemrec.ui.ConnectedChoices
import com.jemcik.jemrec.ui.HomeTab
import com.jemcik.jemrec.ui.Screen
import com.jemcik.jemrec.ui.SettingsRow
import com.jemcik.jemrec.ui.SwitchRow
import com.jemcik.jemrec.ui.theme.LightDark
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookChoice
import com.jemcik.jemrec.ui.theme.Looks
import com.jemcik.jemrec.ui.LOOK_PREVIEW
import com.jemcik.jemrec.ui.SETTINGS_BAR
import com.jemcik.jemrec.ui.CallsNarrowing
import com.jemcik.jemrec.capture.RecorderSwitch
import androidx.compose.ui.semantics.getOrNull
import com.jemcik.jemrec.ui.SettingsBar
import com.jemcik.jemrec.ui.headerMarks
import com.jemcik.jemrec.ui.markTag
import com.jemcik.jemrec.ui.RecorderStatus
import com.jemcik.jemrec.ui.markFaceTag
import com.jemcik.jemrec.ui.RecordingsNarrowing
import com.jemcik.jemrec.ui.headerAside
import com.jemcik.jemrec.ui.MEMO_MARK_IN_SETTINGS
import com.jemcik.jemrec.ui.markGlyphTag
import com.jemcik.jemrec.ui.HeaderMark
import com.jemcik.jemrec.ui.marks
import com.jemcik.jemrec.ui.recorderStatus
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.onNodeWithTag
import org.junit.Assert.assertNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * What the screens do, as opposed to how they look: where Back goes, what a
 * screen reader is told, and that a control that says it is off is off.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class BehaviourTest : ScreenTest() {

    private fun back() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test fun backFromEachScreenOfSettingsReturnsToSettingsThenHome() {
        val vm = viewModel(Fixtures.ready().copy(screen = Screen.SETTINGS))
        showApp(vm)
        // Each row, and the title its screen opens with. Recently deleted is
        // drawn by a different branch of the scaffold from the others.
        for ((row, title) in listOf(
            "Whose calls to keep" to Screen.WHOSE_CALLS,
            "Messenger calls" to Screen.MESSENGER_CALLS,
            "Recently deleted" to Screen.TRASH,
            "Diagnostics" to Screen.DIAGNOSTICS,
        )) {
            compose.onNodeWithText(row).performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(row, title, vm.state.value.screen)
            back()
            assertEquals("Back from $row", Screen.SETTINGS, vm.state.value.screen)
            // And the overview is what is drawn again.
            compose.onNodeWithText(row).assertExists()
        }
        back()
        assertEquals(Screen.HOME, vm.state.value.screen)
        compose.onNodeWithText("Ready to record").assertIsDisplayed()
    }

    @Test fun settingsKeepsItsPlaceAfterRecentlyDeleted() {
        // The scroll lived inside a branch Recently deleted replaces, and was
        // lost with it: Back came out at the top.
        val vm = viewModel(Fixtures.ready().copy(screen = Screen.SETTINGS))
        showApp(vm)
        compose.onNodeWithText("Start fresh").performScrollTo()
        // Brought back into view from the bottom: the Debugging group put
        // more than a screen between it and Start fresh.
        compose.onNodeWithText("Recently deleted").performScrollTo().performClick()
        compose.waitForIdle()
        back()
        // Where it was left: the row tapped still in view, not the top.
        compose.onNodeWithText("Recently deleted").assertIsDisplayed()
        compose.onNodeWithText("Record every call").assertIsNotDisplayed()
    }

    @Test fun aSelfTestResultThatLandsElsewhereIsNotSavedUp() {
        // It used to be marked seen only on the screens of Settings, so one
        // that came in while home was up waited, and jumped Diagnostics to
        // the bottom whenever it was next opened.
        val vm = viewModel(Fixtures.ready().copy(screen = Screen.HOME, selfTestShown = 1L))
        showApp(vm)
        assertEquals(0L, vm.state.value.selfTestShown)
    }

    @Test fun theTipGoesAtItsX() {
        val vm = viewModel(Fixtures.ready().copy(selectHintSeen = false))
        showApp(vm)
        compose.onNodeWithText("Tap a photo, or press and hold a call, to select recordings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss tip").performClick()
        compose.waitForIdle()
        assertTrue(vm.state.value.selectHintSeen)
        compose.onAllNodesWithText("Tap a photo, or press and hold a call, to select recordings").assertCountEquals(0)
    }

    /** Opening search leaves a scrolled list where it was. On the Honor
     *  (2026-10-23) both lists jumped to the top as the field opened. */
    @Test fun openingSearchLeavesTheRecordingsWhereTheyWere() {
        val many = (0 until 40).map { i ->
            Recording(
                name = "202610%02d_1200%02d_in.ogg".format(Locale.US, 20 - i / 10, i % 60),
                uri = Uri.parse("jemrec-test://$i"),
                whenLabel = "",
                incoming = true,
                durationLabel = "1:00",
                sizeLabel = "1.0 MB",
                caller = "Caller $i",
            )
        }
        showApp(viewModel(Fixtures.ready().copy(recordings = many)))
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(14)
        compose.waitForIdle()
        compose.onAllNodesWithText("Caller 0").assertCountEquals(0)
        compose.onNodeWithContentDescription("Search recordings").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Caller 0").assertCountEquals(0)
    }

    @Test fun openingSearchLeavesTheCallsWhereTheyWere() {
        val many = (0 until 40).map { i ->
            Fixtures.calls().first().copy(at = Fixtures.calls().first().at - i * 3_600_000L, label = "Caller $i", recording = null)
        }
        showApp(viewModel(Fixtures.ready().copy(homeTab = HomeTab.CALLS, calls = many)))
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(14)
        compose.waitForIdle()
        compose.onAllNodesWithText("Caller 0").assertCountEquals(0)
        compose.onNodeWithContentDescription("Search calls").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Caller 0").assertCountEquals(0)
    }

    /** On a phone on its side the header is beside the list: the rows begin
     *  right of the gear, and the list starts at the top, not under it. */
    @Config(qualifiers = "w800dp-h360dp-land-hdpi")
    @Test fun onAPhoneOnItsSideTheHeaderIsBesideTheList() {
        showApp(viewModel(Fixtures.ready()))
        val gear = compose.onNodeWithContentDescription("Settings").fetchSemanticsNode().boundsInRoot
        val row = compose.onNodeWithText("Olivia Bennett").fetchSemanticsNode().boundsInRoot
        assertTrue("the row begins right of the header: $row, gear $gear", row.left > gear.right)
        assertTrue("the row is higher than under a header on top: $row", row.top < gear.bottom + 200f)
    }

    /** On its side, the chips are in the panel with their names, the list
     *  has none of its own, and Search opens its field there. */
    @Config(qualifiers = "w800dp-h360dp-land-hdpi")
    @Test fun onAPhoneOnItsSideTheChipsAreNamedInThePanel() {
        val vm = viewModel(Fixtures.ready())
        showApp(vm)
        compose.onNodeWithText("Favourites").assertIsDisplayed()
        // One set of chips: the row's icon-only search is not drawn as well.
        compose.onAllNodesWithContentDescription("Search recordings").assertCountEquals(0)
        compose.onNodeWithText("Search").performClick()
        compose.waitForIdle()
        assertTrue(vm.state.value.recordingsNarrowing.searchOpen)
        compose.onNodeWithText("Search by name or number").assertIsDisplayed()
        compose.onNodeWithText("Kept").performClick()
        compose.waitForIdle()
        assertTrue(vm.state.value.recordingsNarrowing.kept)
    }

    /** A narrowed list says so - what narrows it, how many of how many - and
     *  Show all brings every recording back: a lit chip could be past the
     *  row's edge, and the list showed 2 of 52 with nothing saying why. */
    @Test fun aNarrowedListSaysSoAndShowAllBringsEveryRecordingBack() {
        val vm = viewModel(Fixtures.ready().copy(recordingsNarrowing = RecordingsNarrowing(kept = true)))
        showApp(vm)
        val all = Fixtures.recordings()
        compose.onNodeWithText("Kept · ${all.count { it.kept }} of ${all.size}").assertIsDisplayed()
        compose.onNodeWithText("Show all").performClick()
        compose.waitForIdle()
        assertEquals(RecordingsNarrowing(), vm.state.value.recordingsNarrowing)
        compose.onAllNodesWithText("Show all").assertCountEquals(0)
    }

    @Test fun aNarrowedCallsListSaysSoToo() {
        val vm = viewModel(Fixtures.ready().copy(homeTab = HomeTab.CALLS, callsNarrowing = CallsNarrowing(favorites = true)))
        showApp(vm)
        val calls = Fixtures.calls()
        compose.onNodeWithText("Favourites · ${calls.count { it.favorite }} of ${calls.size}").assertIsDisplayed()
        compose.onNodeWithText("Show all").performClick()
        compose.waitForIdle()
        assertEquals(CallsNarrowing(), vm.state.value.callsNarrowing)
    }

    /** A chip lit past the row's edge is brought into view as the screen
     *  opens: on the Honor, Messages was lit off the screen. */
    @Test fun aLitChipPastTheRowsEdgeIsBroughtIntoView() {
        val more = listOf("telegram", "viber", "signal").mapIndexed { i, app ->
            Fixtures.recordings()[1].copy(name = "2026101${i}_120000_in_$app.ogg")
        } + Fixtures.recordings()[0].copy(name = "20261009_100000_in.ogg", message = true)
        val state = Fixtures.ready().copy(
            recordings = Fixtures.recordings() + more,
            recordingsNarrowing = RecordingsNarrowing(messages = true),
        )
        showApp(viewModel(state))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Only messages").assertIsDisplayed()
    }

    @Test fun whenTheHeaderGoesBesideTheList() {
        assertTrue("a phone on its side", headerAside(widthDp = 800, heightDp = 360))
        assertFalse("a phone upright", headerAside(widthDp = 360, heightDp = 800))
        assertFalse("a tablet on its side: room for the header on top", headerAside(widthDp = 1280, heightDp = 800))
        assertFalse("a square window of compact height", headerAside(widthDp = 400, heightDp = 400))
    }

    /** Recently deleted's two buttons; Delete all for good asks first, and
     *  Cancel leaves everything as it was. */
    @Test fun recentlyDeletedRestoresAllOrDeletesAllForGoodOnceAsked() {
        val vm = viewModel(Fixtures.ready().copy(screen = Screen.TRASH, trashed = Fixtures.trashed()))
        showApp(vm)
        compose.onNodeWithText("Restore all").assertIsDisplayed()
        compose.onNodeWithText("Delete all for good").performClick()
        compose.onNodeWithText("Delete all 3 for good?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitForIdle()
        assertFalse(vm.state.value.confirmingEmptyTrash)
        compose.onAllNodesWithText("Delete all 3 for good?").assertCountEquals(0)
        assertEquals(3, vm.state.value.trashed.size)
    }

    @Test fun anEmptyRecentlyDeletedOffersNeither() {
        showApp(viewModel(Fixtures.ready().copy(screen = Screen.TRASH)))
        compose.onAllNodesWithText("Restore all").assertCountEquals(0)
        compose.onAllNodesWithText("Delete all for good").assertCountEquals(0)
    }

    @Test fun playbackGoesOnWhenItsRowLeavesTheScreen() {
        // Until 0.19 another tab, Settings or Recently deleted paused it: the
        // row's player was its only control. Now the system's media controls
        // are there too (PlaybackService), so it plays on, its place kept.
        val playing = Fixtures.playing(positionMs = 60_000, waveform = null).copy(homeTab = HomeTab.CALLS)
        viewModel(playing).let { vm ->
            vm.showTab(HomeTab.RECORDINGS)
            assertTrue("another tab", vm.state.value.playing)
            assertEquals("its place kept", 60_000, vm.state.value.positionMs)
            assertEquals(playing.playingName, vm.state.value.playingName)
        }
        viewModel(playing).let { vm ->
            vm.showSettings()
            assertTrue("Settings", vm.state.value.playing)
        }
        viewModel(playing).let { vm ->
            vm.showTrash()
            assertTrue("Recently deleted", vm.state.value.playing)
        }
        viewModel(playing).let { vm ->
            vm.screenHidden()
            assertTrue("the app out of sight", vm.state.value.playing)
        }
    }

    @Test fun noLimitOnAMessageIsAskedAboutFirst() {
        val state = Fixtures.ready().copy(screen = Screen.ANSWERING_MACHINE)
        val vm = viewModel(state, AnswerUi(enabled = true, greetingSeconds = 6.0, longest = 60))
        show { AppScaffold(state, vm) }
        compose.onNodeWithText("Longest message").performScrollTo().performClick()
        compose.onNodeWithText("No limit").performClick()
        // A warning, not the setting: Cancel leaves it as it was.
        compose.onNodeWithText("No limit?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(60, vm.answer.state.value.longest)
        compose.onNodeWithText("1 minute, then it hangs up").assertIsDisplayed()
        // Asked again, and said yes to.
        compose.onNodeWithText("Longest message").performClick()
        compose.onNodeWithText("No limit").performClick()
        compose.onNodeWithText("No limit").performClick()
        assertEquals(com.jemcik.jemrec.capture.AnsweringMachine.NO_LIMIT, vm.answer.state.value.longest)
        compose.onNodeWithText("No limit - it hangs up after 30 seconds of silence").assertIsDisplayed()
    }

    @Test fun answeringAgainAfterAnEmergencyCallIsAskedAboutFirst() {
        val state = Fixtures.ready().copy(screen = Screen.ANSWERING_MACHINE)
        val vm = viewModel(state, AnswerUi(enabled = true, greetingSeconds = 6.0, pausedUntil = Fixtures.NOW))
        show { AppScaffold(state, vm) }
        compose.onNodeWithText("Answer again now").performClick()
        // A warning first: keeping the pause changes nothing.
        compose.onNodeWithText("Answer calls again?").assertIsDisplayed()
        compose.onNodeWithText("Keep paused").performClick()
        assertEquals(Fixtures.NOW, vm.answer.state.value.pausedUntil)
        // Asked again, and said yes to: answering again, the row gone.
        compose.onNodeWithText("Answer again now").performClick()
        compose.onNodeWithText("Answer again").performClick()
        assertEquals(null, vm.answer.state.value.pausedUntil)
        compose.onNodeWithText("Answer again now").assertDoesNotExist()
    }

    @Test fun trackingOffWithTheMachineOnIsAskedAboutFirst() {
        // It turned the answering machine off without a word (the user,
        // 2026-10-24).
        val state = Fixtures.ready().copy(screen = Screen.SETTINGS)
        val vm = viewModel(state, AnswerUi(enabled = true, greetingSeconds = 6.0))
        show { AppScaffold(state, vm) }
        compose.onNodeWithText("Exact call tracking").performScrollTo().performClick()
        compose.onNodeWithText("Turn off the answering machine too?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(vm.state.value.callTracking)
        assertTrue(vm.answer.state.value.enabled)
        // Asked again, and said yes to: both off.
        compose.onNodeWithText("Exact call tracking").performClick()
        compose.onNodeWithText("Turn both off").performClick()
        assertFalse(vm.state.value.callTracking)
        assertFalse(vm.answer.state.value.enabled)
    }

    @Test fun trackingOffWithTheMachineOffIsNotAsked() {
        val state = Fixtures.ready().copy(screen = Screen.SETTINGS)
        val vm = viewModel(state, AnswerUi(enabled = false))
        show { AppScaffold(state, vm) }
        compose.onNodeWithText("Exact call tracking").performScrollTo().performClick()
        compose.onNodeWithText("Turn off the answering machine too?").assertDoesNotExist()
        assertFalse(vm.state.value.callTracking)
    }

    @Test fun theMachineOffForWantOfTrackingSaysWhy() {
        assertEquals("Off - needs exact call tracking", answerSummary(AnswerUi(callTracking = false)))
        assertEquals("Off", answerSummary(AnswerUi()))
    }

    @Test fun theTileRowSaysWhenTheTileIsInThePanel() {
        show { AppScaffold(Fixtures.ready().copy(screen = Screen.SETTINGS, tileAdded = true), viewModel()) }
        compose.onNodeWithText("In your Quick Settings panel").performScrollTo().assertIsDisplayed()
    }

    @Test fun connectedButtonsTellAScreenReaderWhichIsChosen() {
        show {
            var chosen by remember { mutableStateOf("All") }
            ConnectedChoices(listOf("All", "All but", "Only", "None"), chosen, { it }, { chosen = it })
        }
        compose.onNodeWithText("All").assertIsSelected()
        compose.onNodeWithText("Only").assertIsNotSelected().performClick()
        compose.onNodeWithText("Only").assertIsSelected()
        compose.onNodeWithText("All").assertIsNotSelected()
    }

    @Test fun aSwitchRowIsOneSwitch() = oneSwitch(Look.MEMO)

    @Test fun decksKeyIsOneSwitch() = oneSwitch(Look.DECK)

    @Test fun multitracksLitButtonIsOneSwitch() = oneSwitch(Look.MULTITRACK)

    /** The whole row is the control: one toggleable node, its text merged
     *  in, not a row with a switch beside it that a screen reader visits
     *  separately - Deck's key and Multitrack's button too, which are only
     *  drawn, and say ON or OFF to the eye alone. */
    private fun oneSwitch(look: Look) {
        show(look = LookChoice(look)) {
            var on by remember { mutableStateOf(true) }
            SwitchRow("Record every call", on, { on = it }, summary = "Every call, without asking")
        }
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onAllNodesWithText("ON").assertCountEquals(0)
        compose.onNodeWithText("Record every call").assertIsOn().performClick()
        compose.onNodeWithText("Record every call").assertIsOff()
        compose.onAllNodesWithText("OFF").assertCountEquals(0)
    }

    /**
     * And it is the header's mark as drawn, not a likeness: drawn apart,
     * Memo's sat in circles the header never had and Multitrack's stood its
     * LED over a smaller label (the user found it, 2026-10-23), while the
     * test below held only that they said the same. Held here by size: each
     * mark the header shows is the same size in Settings, in every look.
     */
    /** "Allow this Wi-Fi" takes a tap while the app is busy, as the record
     *  control does: it shows while the recorder is down, when each
     *  opening's check is busiest, and a tap then did nothing at all. */
    @Test fun memosWifiTapIsTakenWhileBusy() = wifiTapTakenWhileBusy(Look.MEMO)

    @Test fun decksWifiTapIsTakenWhileBusy() = wifiTapTakenWhileBusy(Look.DECK)

    @Test fun multitracksWifiTapIsTakenWhileBusy() = wifiTapTakenWhileBusy(Look.MULTITRACK)

    private fun wifiTapTakenWhileBusy(look: Look) {
        val asks = Fixtures.ready().copy(daemonRunning = false, networkNotAllowed = true, busy = true)
        show(look = LookChoice(look)) { AppScaffold(asks, viewModel(asks)) }
        compose.onNode(
            SemanticsMatcher("taps to allow this Wi-Fi") {
                it.config.getOrNull(SemanticsActions.OnClick)?.label == "Allow this Wi-Fi"
            },
        ).assertIsEnabled()
    }

    /** The pairing refused: every look's header offers to pair again, for the
     *  moment before home gives way to the pairing step, and the tap opens
     *  it. The view model's own state is not refused, so the tap is what
     *  pairs again here, not its watch (homeGivesWayToThePairingStep). */
    @Test fun memosHeaderPairsAgain() = headerPairsAgain(Look.MEMO)

    @Test fun decksHeaderPairsAgain() = headerPairsAgain(Look.DECK)

    @Test fun multitracksHeaderPairsAgain() = headerPairsAgain(Look.MULTITRACK)

    private fun headerPairsAgain(look: Look) {
        val app = RuntimeEnvironment.getApplication()
        com.jemcik.jemrec.ui.Setup.markPaired(app)
        val refused = Fixtures.ready().copy(daemonRunning = false, pairingRefused = true)
        val vm = viewModel(Fixtures.ready())
        show(look = LookChoice(look)) { AppScaffold(refused, vm) }
        compose.onNode(
            SemanticsMatcher("taps to pair again") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Pair again" },
        ).performClick()
        assertFalse(com.jemcik.jemrec.ui.Setup.hasPaired(app))
        assertTrue(vm.state.value.pairingLost)
    }

    @Test fun homeGivesWayToThePairingStep() {
        // Whatever changes the state to it, not one refresh: on the Honor
        // (2026-10-24) the refusal that settled it came from a revive whose
        // refresh still read Starting, and the header was left saying Tap to
        // pair again. Here Settings, left for home.
        val app = RuntimeEnvironment.getApplication()
        com.jemcik.jemrec.ui.Setup.markPaired(app)
        val vm = viewModel(Fixtures.ready().copy(screen = Screen.SETTINGS, daemonRunning = false, pairingRefused = true))
        vm.screenShown() // as MainScreen does as it starts
        assertTrue("left alone in Settings", com.jemcik.jemrec.ui.Setup.hasPaired(app))
        vm.showScreen(Screen.HOME)
        assertFalse(com.jemcik.jemrec.ui.Setup.hasPaired(app))
        assertTrue(vm.state.value.pairingLost)
        assertFalse(vm.state.value.pairingRefused)
    }

    @Test fun thePairingStepSaysWhyItIsBack() {
        val lost = Fixtures.ready().copy(step = com.jemcik.jemrec.ui.SetupStep.NEEDS_PAIRING, pairingLost = true)
        show { AppScaffold(lost, viewModel(lost)) }
        compose.onNodeWithText("Wireless debugging no longer lets JemRec in", substring = true).assertIsDisplayed()
    }

    /** Call waiting: two calls on the Calls tab share one recording, and
     *  playing it opens one player, under the topmost - each opened its own,
     *  two seek bars for one playback. */
    @Test fun twoCallsSharingARecordingOpenOnePlayer() {
        val olivia = Fixtures.calls().first()
        val waiting = olivia.copy(at = olivia.at + 60_000, label = "Marcus Chen", favorite = false)
        val state = Fixtures.playing().copy(homeTab = HomeTab.CALLS, calls = listOf(waiting, olivia))
        show { AppScaffold(state, viewModel(state)) }
        compose.onAllNodesWithContentDescription("Collapse player").assertCountEquals(1)
        compose.onAllNodesWithText("Marcus Chen").assertCountEquals(1)
    }

    @Test fun settingsDrawsMemosMarksAsItsHeaderDoes() = marksDrawnAlike(Look.MEMO)

    @Test fun settingsDrawsDecksMarksAsItsHeaderDoes() = marksDrawnAlike(Look.DECK)

    @Test fun settingsDrawsMultitracksMarksAsItsHeaderDoes() = marksDrawnAlike(Look.MULTITRACK)

    private fun marksDrawnAlike(look: Look) {
        val vm = viewModel(Fixtures.ready(), AnswerUi(enabled = true))
        showApp(vm, look = LookChoice(look))
        // Memo's marks are measured by their dot or icon, which Settings
        // draws larger; the others whole, which it draws at the header's size.
        val tagOf = { mark: HeaderMark -> if (look == Look.MEMO) markGlyphTag(mark) else markFaceTag(mark) }
        val inHeader = headerMarks(marks(Fixtures.ready(), answering = true), RecorderStatus.READY)
            .mapNotNull { mark ->
                // Twice in the header, each line laying out what it can show
                // unseen (SteadyLine): the same composable, one size.
                compose.onAllNodesWithTag(tagOf(mark), useUnmergedTree = true).fetchSemanticsNodes()
                    .firstOrNull()?.let { tagOf(mark) to it.size }
            }
        assertTrue("$look: the header shows marks", inHeader.size >= 3)
        compose.runOnUiThread { vm.showSettings() }
        compose.waitForIdle()
        // Memo's icons are the header's drawn larger, at the rows' icon size;
        // the others the header's own size.
        val scale = if (look == Look.MEMO) MEMO_MARK_IN_SETTINGS else 1f
        for ((tag, size) in inHeader) {
            val inSettings = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().fetchSemanticsNode().size
            assertEquals("$look $tag width", size.width * scale, inSettings.width.toFloat(), 1.5f)
            assertEquals("$look $tag height", size.height * scale, inSettings.height.toFloat(), 1.5f)
        }
    }

    /**
     * Settings in the look's own way, design C: each setting the header
     * marks carries the header's mark - from the same function - where its
     * icon would be, and it changes with the setting. Silent to a screen
     * reader: the row's name says it in words.
     */
    @Test fun settingsCarriesTheHeadersMarks() {
        val vm = viewModel(Fixtures.ready().copy(screen = Screen.SETTINGS), AnswerUi(enabled = true))
        showApp(vm, look = LookChoice(Look.DECK))
        // What the header would show, from the same facts: here AUTO, ANS and
        // DEL 90D lit, MSG dark - no messenger is installed on the test phone.
        val shown = headerMarks(marks(vm.state.value, answering = true), recorderStatus(vm.state.value))
        assertEquals(listOf("AUTO", "ANS", "DEL 90D"), shown.filter { it.lit }.map { it.label })
        for (mark in shown) compose.onNodeWithTag(markTag(mark), useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("ANS").assertCountEquals(0)
        compose.onNodeWithText("Record every call").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("mark-ASK-lit", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithTag("mark-AUTO-lit", useUnmergedTree = true).assertCountEquals(0)
    }

    @Test fun memosBarKeepsStill() = barKeepsStill(Look.MEMO)

    @Test fun decksBarKeepsStill() = barKeepsStill(Look.DECK)

    @Test fun multitracksBarKeepsStill() = barKeepsStill(Look.MULTITRACK)

    /** The bar over Settings' screens says the screen's name as a heading,
     *  in its own words - not Deck's and Multitrack's capitals - and keeps
     *  one height, and the name in one place, busy or not: the busy
     *  signal's room is kept. Back goes back. */
    private fun barKeepsStill(look: Look) {
        var busy by mutableStateOf(false)
        var backs = 0
        show(look = LookChoice(look)) { SettingsBar("Whose calls to keep", busy) { backs++ } }
        val bar = compose.onNodeWithTag(SETTINGS_BAR).getBoundsInRoot()
        val name = compose.onNodeWithText("Whose calls to keep")
            .assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
            .getBoundsInRoot()
        compose.onAllNodesWithText("WHOSE CALLS TO KEEP").assertCountEquals(0)
        compose.runOnUiThread { busy = true }
        compose.waitForIdle()
        assertEquals("$look", bar, compose.onNodeWithTag(SETTINGS_BAR).getBoundsInRoot())
        assertEquals("$look", name, compose.onNodeWithText("Whose calls to keep").getBoundsInRoot())
        compose.onNodeWithContentDescription("Back").performClick()
        assertEquals(1, backs)
    }

    @Test fun aDisabledRowIgnoresTaps() {
        var tapped = false
        show { SettingsRow("Start fresh", onClick = { tapped = true }, enabled = false) }
        compose.onNodeWithText("Start fresh").assertIsNotEnabled().performClick()
        assertFalse(tapped)
    }

    @Test fun aChoiceAppliesAndClosesTheDialog() {
        var applied: Int? = null
        show {
            var open by remember { mutableStateOf(true) }
            if (open) {
                ChoiceDialog(
                    title = "Auto-delete old recordings",
                    choices = listOf(0, 30, 90),
                    selected = 0,
                    label = { if (it == 0) "Never" else "$it days" },
                    onChoose = { applied = it },
                    onDismiss = { open = false },
                )
            }
        }
        compose.onNodeWithText("30 days").performClick()
        compose.waitForIdle()
        assertEquals(30, applied)
        compose.onAllNodesWithText("Auto-delete old recordings").assertCountEquals(0)
    }

    /** A recording loaded in the player, its waveform worked out. */
    private fun playingWithWaveform() = Fixtures.playing(playing = false)

    @Test fun aTapOnTheWaveformGoesThere() {
        val vm = viewModel(playingWithWaveform())
        showApp(vm)
        compose.onNodeWithContentDescription("Position in the recording").performTouchInput { click(center) }
        compose.waitForIdle()
        // The middle of a 4:12 call, to within a bar.
        assertEquals(126_000f, vm.state.value.positionMs.toFloat(), 3_000f)
    }

    @Test fun aDragOnTheWaveformSeeksOnceAtItsEnd() {
        val vm = viewModel(playingWithWaveform())
        showApp(vm)
        val waveform = compose.onNodeWithContentDescription("Position in the recording")
        waveform.performTouchInput {
            down(centerLeft + androidx.compose.ui.geometry.Offset(10f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(width / 4f, 0f))
            moveBy(androidx.compose.ui.geometry.Offset(width / 4f, 0f))
        }
        compose.waitForIdle()
        // Not sought yet: the player stays where it was while the finger looks.
        assertEquals(107_000, vm.state.value.positionMs)
        waveform.performTouchInput { up() }
        compose.waitForIdle()
        assertTrue(vm.state.value.positionMs in 120_000..135_000)
    }

    @Test fun aScreenReaderHearsTheWaveformAsAPositionItCanMove() {
        val vm = viewModel(playingWithWaveform())
        showApp(vm)
        val waveform = compose.onNodeWithContentDescription("Position in the recording")
        waveform.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "1:47 of 4:12"))
        waveform.assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        waveform.performSemanticsAction(SemanticsActions.SetProgress) { it(60_000f) }
        compose.waitForIdle()
        assertEquals(60_000, vm.state.value.positionMs)
    }

    // The record control, in each look: the old header's switch in another
    // shape, so a switch to a screen reader, on while recording is on, and a
    // tap turns recording off.

    private fun homeIn(look: Look): MainViewModel {
        val vm = viewModel(Fixtures.ready())
        showApp(vm, look = LookChoice(look))
        return vm
    }

    private fun tapTheRecordControl(look: Look) {
        val vm = homeIn(look)
        compose.onNodeWithContentDescription("Record calls").assertIsOn().performClick()
        // The switch is saved at once; what the recorder then does is
        // setEnabled's business, and here (no phone) setup is re-checked into
        // a step this test does not draw - so only the switch is read.
        assertFalse("$look", vm.state.value.enabled)
    }

    /** While the app is busy - each opening's check holds busy for seconds -
     *  the record control still takes a tap: it was dropped, with nothing
     *  to show it. The switch is saved at once; the recorder follows when
     *  the app is free. */
    @Test fun theRecordControlTakesATapWhileBusy() {
        val app = RuntimeEnvironment.getApplication()
        val vm = viewModel(Fixtures.ready().copy(busy = true))
        try {
            showApp(vm)
            compose.onNodeWithContentDescription("Record calls").assertIsOn().performClick()
            compose.waitForIdle()
            assertFalse(vm.state.value.enabled)
            assertFalse(RecorderSwitch.isOn(app))
            compose.onNodeWithContentDescription("Record calls").assertIsOff()
        } finally {
            RecorderSwitch.set(app, true)
        }
    }

    /** Memo's tabs are 36dp tall, under the 48dp Android asks of a touch
     *  target - but Compose takes a tap near a small control as its own
     *  (NodeCoordinator's minimum touch target, 48dp): a tap 5dp under the
     *  Calls tab opens it. */
    @Test fun aTapJustOffMemosTabStillTakesIt() {
        val vm = homeIn(Look.MEMO)
        val tab = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab) and hasText("Calls")
        compose.onNode(tab).performTouchInput { click(Offset(centerX, height + 5.dp.toPx())) }
        compose.waitForIdle()
        assertEquals(HomeTab.CALLS, vm.state.value.homeTab)
    }

    @Test fun memosButtonIsTheRecordSwitch() = tapTheRecordControl(Look.MEMO)

    @Test fun decksRecKeyIsTheRecordSwitch() = tapTheRecordControl(Look.DECK)

    @Test fun multitracksButtonIsTheRecordSwitch() = tapTheRecordControl(Look.MULTITRACK)

    /**
     * Held down, the record button lights up inside its circle and not in
     * the square around it - which Multitrack's did on the Honor (2026-10-23).
     * Robolectric does not draw the platform ripple, so the screen is given
     * a press that paints all it is allowed to: what stops it is the clip,
     * as it is what stops the ripple. The corner of the button's box is
     * outside the circle and must stay as it was.
     */
    private fun pressStaysRound(look: Look) {
        val vm = viewModel(Fixtures.ready())
        show(look = LookChoice(look)) {
            val state by vm.state.collectAsState()
            CompositionLocalProvider(LocalIndication provides PaintsWhenPressed) { AppScaffold(state, vm) }
        }
        val button = compose.onNodeWithContentDescription("Record calls")
        val before = button.captureToImage().toPixelMap()
        button.performTouchInput { down(center) }
        val held = button.captureToImage().toPixelMap()
        button.performTouchInput { cancel() }
        assertEquals("$look: no press drawn", PaintsWhenPressed.COLOUR, held[held.width / 2, held.height / 2])
        assertEquals("$look: the press reached the corner", before[1, 1], held[1, 1])
    }

    /**
     * The header is one height whatever the record switch and the Wi-Fi make
     * it say - switched on, switching on, off, unable to record - under each
     * set of settings. Multitrack's grew and shrank as recording was switched
     * on the Honor (2026-10-23) - the line under the state is LEDs, a sweep
     * or words, each its own height - and the list under it jumped. At three
     * text sizes, since a line that wraps at one of them is the same jump.
     * (A setting is changed on another screen, so the header may differ from
     * one set of settings to the next; it is never seen to change.)
     */
    private fun oneHeight(look: Look) {
        val ready = Fixtures.ready()
        val settings = listOf(
            "everything on" to ready,
            "asks" to ready.copy(automatic = false),
            "nothing else on" to ready.copy(voipEnabled = emptySet(), autoDeleteAfterDays = 0),
        )
        val switches = listOf<Pair<String, (UiState) -> UiState>>(
            "ready" to { it },
            "starting" to { it.copy(starting = true, daemonRunning = false) },
            "off" to { it.copy(enabled = false) },
            "cannot" to { it.copy(daemonRunning = false, onWifi = false) },
            "asks for this Wi-Fi" to { it.copy(daemonRunning = false, networkNotAllowed = true) },
            "asks to pair again" to { it.copy(daemonRunning = false, pairingRefused = true) },
            "asks to start again" to { it.copy(daemonRunning = false) },
        )
        val vm = viewModel(ready, AnswerUi(enabled = true))
        var shown by mutableStateOf(ready)
        var scale by mutableFloatStateOf(1f)
        show(look = LookChoice(look)) {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) { AppScaffold(shown, vm) }
        }
        val tabs = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        for (s in listOf(1f, 1.3f, 2f)) {
            scale = s
            for ((set, with) in settings) {
                val bottoms = switches.associate { (name, switch) ->
                    shown = switch(with)
                    compose.waitForIdle()
                    name to compose.onAllNodes(tabs)[0].getBoundsInRoot().bottom
                }
                assertEquals("$look, $set, at ${s}x: $bottoms", 1, bottoms.values.distinct().size)
            }
        }
        // What the lines are sized for is neither seen nor heard: switched
        // on, nothing says to switch on.
        shown = ready
        compose.waitForIdle()
        compose.onAllNodesWithText("record calls", substring = true, ignoreCase = true).assertCountEquals(0)
    }

    @Test fun memosHeaderIsOneHeight() = oneHeight(Look.MEMO)

    @Test fun decksHeaderIsOneHeight() = oneHeight(Look.DECK)

    @Test fun multitracksHeaderIsOneHeight() = oneHeight(Look.MULTITRACK)

    /**
     * At normal text size a steady line keeps no line it does not show: all
     * it can say fits one line. It may keep a little more than it shows -
     * Deck's marks are smaller than its notes - but a note that wraps keeps
     * a whole line more, and that is what is caught. On the Honor
     * (2026-10-23) Memo's second line kept two lines - for its longest note,
     * and for marks that were off - and the one line it showed sat in the
     * middle of them, with the record button centred on the blank. Memo
     * draws in the phone's own font, so it is held to this a tenth wider
     * too; Deck and Multitrack bring their fonts, and draw the same on any
     * phone.
     */
    private fun wasteNoRoom(look: Look, vararg scales: Float) {
        val ready = Fixtures.ready()
        val vm = viewModel(ready, AnswerUi(enabled = true))
        var shown by mutableStateOf(ready)
        var scale by mutableFloatStateOf(1f)
        show(look = LookChoice(look)) {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, scale)) { AppScaffold(shown, vm) }
        }
        for (s in scales) {
            scale = s
            // The Honor's: asking each call, the answering machine on, nothing else.
            for (state in listOf(ready, ready.copy(automatic = false, voipEnabled = emptySet(), autoDeleteAfterDays = 0))) {
                shown = state
                compose.waitForIdle()
                val lines = compose.onAllNodesWithTag(STEADY_LINE, useUnmergedTree = true).fetchSemanticsNodes()
                val inside = compose.onAllNodesWithTag(STEADY_SHOWN, useUnmergedTree = true).fetchSemanticsNodes()
                assertEquals(2, lines.size)
                for ((line, words) in lines.zip(inside)) {
                    assertTrue(
                        "$look at ${s}x: a line ${line.size.height}px tall shows ${words.size.height}px",
                        line.size.height - words.size.height < words.size.height / 2,
                    )
                }
            }
        }
    }

    @Test fun memosLinesWasteNoRoom() = wasteNoRoom(Look.MEMO, 1f, 1.1f)

    @Test fun decksLinesWasteNoRoom() = wasteNoRoom(Look.DECK, 1f)

    @Test fun multitracksLinesWasteNoRoom() = wasteNoRoom(Look.MULTITRACK, 1f)

    /**
     * Every look says how many recordings there are, in one place: on the
     * Recordings tab, in every state. Deck had it in its LCD, and only while
     * ready, and Multitrack not at all, until the user asked for the three
     * headers to tell the same things (2026-10-23).
     */
    private fun theTabCounts(look: Look) {
        val ready = Fixtures.ready()
        val vm = viewModel(ready)
        var shown by mutableStateOf(ready)
        show(look = LookChoice(look)) { AppScaffold(shown, vm) }
        val says = "${ready.recordings.size} recordings"
        val tabs = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        for (state in listOf(ready, ready.copy(enabled = false), ready.copy(daemonRunning = false))) {
            shown = state
            compose.waitForIdle()
            compose.onAllNodes(tabs)[0].assert(
                SemanticsMatcher("says \"$says\"") { says in it.config.getOrElse(SemanticsProperties.ContentDescription) { emptyList() } },
            )
        }
        // And only there.
        compose.onAllNodesWithText("TRK", substring = true).assertCountEquals(0)
    }

    @Test fun memosRecordingsTabCountsThem() = theTabCounts(Look.MEMO)

    @Test fun decksRecordingsTabCountsThem() = theTabCounts(Look.DECK)

    @Test fun multitracksRecordingsTabCountsThem() = theTabCounts(Look.MULTITRACK)

    /**
     * Every look's day heading ends with how many recordings the day has:
     * Deck's and Multitrack's did, Memo's said only "Today", until the user
     * asked for the three to say the same (2026-10-23).
     */
    private fun theDayHeadingCounts(look: Look) {
        homeIn(look)
        val today = Fixtures.recordings().count { r -> r.startedAtMillis?.let { dayOf(it, Fixtures.NOW) } == Day.TODAY }
        val texts = { node: SemanticsNode -> node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.map { it.text } }
        val heading = SemanticsMatcher("today's heading") { node ->
            SemanticsProperties.Heading in node.config && texts(node).any { it.equals("Today", ignoreCase = true) }
        }
        compose.onNode(heading).assert(SemanticsMatcher("says $today") { node -> texts(node).any { it.startsWith("$today") } })
        // Heard with what it counts, not a bare number.
        compose.onNode(heading).assert(SemanticsMatcher.expectValue(SemanticsProperties.ContentDescription, listOf("Today, $today recordings")))
    }

    @Test fun memosDayHeadingCounts() = theDayHeadingCounts(Look.MEMO)

    @Test fun decksDayHeadingCounts() = theDayHeadingCounts(Look.DECK)

    @Test fun multitracksDayHeadingCounts() = theDayHeadingCounts(Look.MULTITRACK)

    /**
     * Starting to select takes Keep and Share off the rows, and the rows stay
     * as tall as they were: the list does not lurch under the finger. Read
     * from where the next day's heading sits.
     */
    private fun rowsKeepTheirHeight(look: Look) {
        val vm = homeIn(look)
        val yesterday = SemanticsMatcher("yesterday's heading") { node ->
            SemanticsProperties.Heading in node.config &&
                node.config.getOrElse(SemanticsProperties.Text) { emptyList() }.any { it.text.equals("Yesterday", ignoreCase = true) }
        }
        val before = compose.onNode(yesterday).getBoundsInRoot().top
        vm.startSelecting(Fixtures.recordings().first())
        compose.waitForIdle()
        compose.onAllNodesWithContentDescription("Share").assertCountEquals(0)
        assertEquals("$look", before, compose.onNode(yesterday).getBoundsInRoot().top)
    }

    @Test fun memosRowsKeepTheirHeightSelecting() = rowsKeepTheirHeight(Look.MEMO)

    @Test fun decksRowsKeepTheirHeightSelecting() = rowsKeepTheirHeight(Look.DECK)

    @Test fun multitracksRowsKeepTheirHeightSelecting() = rowsKeepTheirHeight(Look.MULTITRACK)

    /**
     * Playing, the row keeps its photo where every row has one - the play
     * key used to take its place, out of line with the photos around it
     * (the user, 2026-10-23) - and the pause key is in the player under it.
     */
    private fun thePlayingRowKeepsItsPhoto(look: Look) {
        val playing = Fixtures.recordings().first()
        val vm = viewModel(
            Fixtures.playing(waveform = null),
        )
        showApp(vm, look = LookChoice(look))
        val centre = { initials: String -> compose.onNodeWithText(initials, useUnmergedTree = true).getBoundsInRoot().let { (it.left + it.right) / 2 } }
        // The playing row's photo (Olivia Bennett's) and the next row's
        // (Marcus Chen's), one above the other.
        assertEquals("$look", centre("OB").value, centre("MC").value, 0.5f)
        val photoBottom = compose.onNodeWithText("OB", useUnmergedTree = true).getBoundsInRoot().bottom
        assertTrue("$look: the pause key is under the photo", compose.onNodeWithContentDescription("Pause").getBoundsInRoot().top > photoBottom)
    }

    @Test fun memosPlayingRowKeepsItsPhoto() = thePlayingRowKeepsItsPhoto(Look.MEMO)

    @Test fun decksPlayingRowKeepsItsPhoto() = thePlayingRowKeepsItsPhoto(Look.DECK)

    @Test fun multitracksPlayingRowKeepsItsPhoto() = thePlayingRowKeepsItsPhoto(Look.MULTITRACK)

    /**
     * The playhead glides: between the player's readings - every 300 ms - it
     * moves on with the frames. Drawn only at the readings it stepped three
     * times a second (the Honor, 2026-10-23). A 20-second call, so one
     * reading's 300 ms is pixels of the scrubber. The test's clock is taken
     * by hand first: while it runs by itself the test environment cancels
     * infinite animations, which is what keeps the screen tests' pictures
     * still.
     */
    @Test fun thePlayheadMovesBetweenReadings() {
        val olivia = Fixtures.recordings().first()
        val playing = Fixtures.playing(positionMs = 7_000, durationMs = 20_000)
        val vm = viewModel(playing)
        var shown by mutableStateOf(playing)
        show { AppScaffold(shown, vm) }
        compose.mainClock.autoAdvance = false
        // Paused and played again, so the head's frame loop starts under
        // the hand-run clock; a reading that shows the player moving; then
        // the 300 ms until the next.
        // Each change sent on before the frames, or the clock run by hand
        // never sees it: the pause and the play would meet in one picture.
        val frames = { n: Int ->
            Snapshot.sendApplyNotifications()
            repeat(n) { compose.mainClock.advanceTimeByFrame() }
        }
        shown = playing.copy(playing = false)
        frames(2)
        shown = playing
        frames(2)
        shown = playing.copy(positionMs = 7_300)
        frames(2)
        val scrubber = compose.onNodeWithContentDescription("Position in the recording")
        val before = scrubber.captureToImage().toPixelMap()
        compose.mainClock.advanceTimeBy(300)
        val after = scrubber.captureToImage().toPixelMap()
        compose.mainClock.autoAdvance = true
        // The head, found in a band only it reaches - above the bars, which
        // keep 6dp off the scrubber's edges - as the middle of its dark run.
        val band = (before.height * 0.09f).toInt()
        val headAt = { map: PixelMap ->
            (0 until map.width).filter { map[it, band].red < 0.5f }.average()
        }
        // 300 ms of a 20-second call is about 7px of this scrubber.
        val moved = headAt(after) - headAt(before)
        assertTrue("the playhead moved ${"%.1f".format(moved)}px between two readings", moved >= 3)
    }

    /** A messenger's chip on the Recordings tab leaves that messenger's
     *  calls: the fixtures' one WhatsApp call, Marcus Chen's. */
    @Test fun aMessengersChipLeavesItsCalls() {
        homeIn(Look.MEMO)
        compose.onNodeWithContentDescription("Only WhatsApp calls").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Marcus Chen").assertExists()
        compose.onAllNodesWithText("Olivia Bennett").assertCountEquals(0)
    }

    /** Two source chips together show the calls that came either way: the
     *  phone's and WhatsApp's both. */
    @Test fun sourceChipsCombine() {
        homeIn(Look.MEMO)
        compose.onNodeWithContentDescription("Only WhatsApp calls").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Only phone calls").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Marcus Chen").assertExists()
        compose.onNodeWithText("Olivia Bennett").assertExists()
    }

    /** The Calls tab's own chip: the calls that connected and were not
     *  recorded - Hana Sato's (recording was off), not Olivia Bennett's
     *  (recorded) nor the missed one. */
    @Test fun theCallsTabsNotRecordedChipLeavesThoseCalls() {
        val vm = viewModel(Fixtures.ready().copy(homeTab = HomeTab.CALLS))
        showApp(vm)
        compose.onNodeWithContentDescription("Only calls not recorded").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Hana Sato").assertExists()
        compose.onAllNodesWithText("Olivia Bennett").assertCountEquals(0)
        compose.onAllNodesWithText("+44 20 7946 0321").assertCountEquals(0)
    }

    /** On the Calls tab a recorded call's row is its Recordings row, Keep and
     *  Share included - but there is no selection there, so a long press
     *  does not start one. */
    @Test fun aRecordedCallsRowHasKeepAndShareButStartsNoSelection() {
        val vm = viewModel(Fixtures.ready().copy(homeTab = HomeTab.CALLS))
        showApp(vm)
        compose.onNodeWithContentDescription("Kept - tap to stop keeping").assertExists()
        compose.onNodeWithText("Olivia Bennett").performTouchInput { longClick() }
        compose.waitForIdle()
        assertFalse(vm.state.value.selecting)
    }

    /** Two identical rows in the call log - a backup restored twice - are two
     *  rows: keyed by time and name alone, they crashed the tab, as LazyColumn
     *  takes no key twice. */
    @Test fun aCallTheLogRepeatsIsTwoRows() {
        val calls = Fixtures.calls().flatMap { if (it.label == "+44 20 7946 0321") listOf(it, it) else listOf(it) }
        show { AppScaffold(Fixtures.ready().copy(homeTab = HomeTab.CALLS, calls = calls), viewModel()) }
        compose.onAllNodesWithText("+44 20 7946 0321").assertCountEquals(2)
    }

    /** Share on the Calls tab asks how, as on Recordings: the sheet was the
     *  Recordings tab's alone, and a tap here showed nothing. */
    @Test fun shareAsksHowOnTheCallsTabToo() {
        val vm = viewModel(Fixtures.ready().copy(homeTab = HomeTab.CALLS))
        showApp(vm)
        compose.onAllNodesWithContentDescription("Share")[0].performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Share as").assertExists()
    }

    /** The plain slider - a recording with no waveform yet - seeks for
     *  TalkBack's adjust, and so for a tap: both end the drag in the same
     *  handler that starts it, and reading the drag as it was when last drawn,
     *  it never sought. */
    @Test fun thePlainSliderSeeksForTalkBack() {
        val olivia = Fixtures.recordings().first()
        val vm = viewModel(
            Fixtures.playing(positionMs = 0, playing = false, waveform = null),
        )
        showApp(vm)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .performSemanticsAction(SemanticsActions.SetProgress) { it(120_000f) }
        compose.waitForIdle()
        assertEquals(120_000, vm.state.value.positionMs)
    }

    /** ...and a drag the waveform cuts short - it arrives mid-drag and takes
     *  the slider's place - still seeks: it was lost, and the row's clock
     *  stayed frozen where the finger was. */
    @Test fun aDragTheWaveformCutsShortStillSeeks() {
        val olivia = Fixtures.recordings().first()
        val vm = viewModel(Fixtures.ready())
        var state by mutableStateOf(
            Fixtures.playing(positionMs = 0, playing = false, waveform = null),
        )
        show { AppScaffold(state, vm) }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress)).performTouchInput {
            down(Offset(width * 0.1f, centerY))
            repeat(10) { moveBy(Offset(width * 0.04f, 0f)) }
        }
        // The drag is the slider's to take first; the finger stays down.
        compose.waitForIdle()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsActions.SetProgress))
            .assert(SemanticsMatcher("dragged") { (it.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo)?.current ?: 0f) > 0f })
        compose.runOnUiThread { state = state.copy(waveform = Fixtures.waveform()) }
        compose.waitForIdle()
        assertTrue("${vm.state.value.positionMs}", vm.state.value.positionMs in 90_000..160_000)
    }

    /** The Calls tab says when its chips and search leave nothing, as the
     *  Recordings tab does: it showed a blank list under the chips. */
    @Test fun theCallsTabSaysWhenNothingMatches() {
        val nobody = CallsNarrowing(query = "Nobody", searchOpen = true)
        show { AppScaffold(Fixtures.ready().copy(homeTab = HomeTab.CALLS, callsNarrowing = nobody), viewModel()) }
        compose.onNodeWithText("No matches").assertExists()
        compose.onNodeWithText("No calls match your search.").assertExists()
    }

    /** A day's heading counts all of its recordings, not the first page the
     *  endless list has drawn: 30 under Earlier read 20. */
    @Test fun aDaysCountIsAllOfIt() {
        val earlier = (0 until 30).map { i ->
            Fixtures.recordings().first().copy(name = "20261012_10%02d00_in.ogg".format(i), caller = "Caller $i", kept = false)
        }
        show { AppScaffold(Fixtures.ready().copy(recordings = earlier), viewModel()) }
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading) and hasText("30")).assertExists()
    }

    /** Settings and the Theme screen say the look the app is drawn in,
     *  read from the theme: a copy of it in the screen's state said Memo and
     *  System until the first refresh - seconds, with the recorder down -
     *  over an app in Deck. */
    @Test fun theThemeScreenSaysTheLookTheAppIsIn() {
        showApp(viewModel(Fixtures.ready().copy(screen = Screen.THEME)), look = LookChoice(Look.DECK, LightDark.DARK))
        compose.onNodeWithText("Deck").assertIsSelected()
        compose.onNodeWithText("Dark").performScrollTo().assertIsSelected()
        back()
        compose.onNodeWithText("Deck · dark").assertExists()
    }

    @Test fun memosButtonPressesRound() = pressStaysRound(Look.MEMO)

    @Test fun decksRecKeyPressesRound() = pressStaysRound(Look.DECK)

    @Test fun multitracksButtonPressesRound() = pressStaysRound(Look.MULTITRACK)

    @Test fun decksLcdIsOneSentenceToAScreenReader() {
        // "AUTO ANS MSG DEL 90D" means nothing read out; the LCD is heard as
        // what it says.
        homeIn(Look.DECK)
        compose.onNodeWithContentDescription("Ready to record. Records every call.", substring = true).assertExists()
        compose.onAllNodesWithText("DEL 90D").assertCountEquals(0)
    }

    @Test fun choosingAThemeKeepsIt() {
        val app = RuntimeEnvironment.getApplication()
        val vm = viewModel(Fixtures.ready().copy(screen = Screen.THEME))
        try {
            // Drawn in the saved choice, as the app is: a choice repaints it.
            showApp(vm, look = null)
            compose.onNodeWithText("Deck").performClick()
            compose.onNodeWithText("Dark").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals(LookChoice(Look.DECK, LightDark.DARK), Looks.choice(app))
            compose.onNodeWithText("Deck").assertIsSelected()
            compose.onNodeWithText("Dark").assertIsSelected()
            // The line under the looks is the one picked's.
            compose.onNodeWithText("A pocket recorder, an LCD and a REC key").assertExists()
            compose.onAllNodesWithText("Calm and simple, one big record button").assertCountEquals(0)
            back()
            // Settings names it.
            compose.onNodeWithText("Deck · dark").assertExists()
        } finally {
            Looks.set(app, LookChoice())
        }
    }

    /** Light, Dark, System: Android's words, in its order - and Settings
     *  says the phone's own in full. */
    @Test fun lightOrDarkIsAsAndroidSaysIt() {
        val vm = viewModel(Fixtures.ready().copy(screen = Screen.THEME))
        showApp(vm)
        val lefts = listOf("Light", "Dark", "System").map {
            compose.onNodeWithText(it).performScrollTo().getBoundsInRoot().left
        }
        assertEquals(lefts.sorted(), lefts)
        compose.onNodeWithText("System").assertIsSelected()
        compose.onAllNodesWithText("Same as phone").assertCountEquals(0)
        back()
        compose.onNodeWithText("Memo · system default").assertExists()
    }

    /**
     * The Theme screen's preview is the home screen's own header and rows,
     * wired to the view model - so nothing in it may take a tap: its record
     * button would switch recording off, its gear open Settings, a row play
     * a call that never happened.
     */
    @Test fun theThemePreviewTakesNoTaps() {
        val vm = viewModel(Fixtures.ready().copy(screen = Screen.THEME))
        showApp(vm)
        compose.onNodeWithTag(LOOK_PREVIEW).performTouchInput {
            // Memo's record button, its gear, then the first row.
            click(Offset(width * 0.11f, height * 0.05f))
            click(Offset(width * 0.92f, height * 0.05f))
            click(Offset(width * 0.5f, height * 0.42f))
        }
        compose.waitForIdle()
        assertTrue(vm.state.value.enabled)
        assertEquals(Screen.THEME, vm.state.value.screen)
        assertNull(vm.state.value.playingName)
    }

    /** ...and a screen reader hears none of it: the choices under it name
     *  the look. Its invented calls are not read out as if they were the
     *  user's. */
    @Test fun theThemePreviewIsSilent() {
        val vm = viewModel(Fixtures.ready().copy(screen = Screen.THEME))
        showApp(vm)
        // The merged tree, as TalkBack reads it: the unmerged one keeps what
        // clearAndSetSemantics cleared, by design.
        compose.onNodeWithTag(LOOK_PREVIEW).assertExists()
        compose.onAllNodesWithText("Olivia Bennett").assertCountEquals(0)
        compose.onAllNodesWithText("Ready to record").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Settings").assertCountEquals(0)
    }

    @Test fun theHeaderStaysPutAndTheListScrolls() {
        // Memo's header used to fold away as the list scrolled, and once - on
        // the Honor, 2026-10-23 - the fold took every scroll for itself and
        // the list never moved. One height now: a swipe moves the list only.
        val many = Fixtures.recordings() + Fixtures.recordings().mapIndexed { i, r -> r.copy(name = "more-$i-" + r.name) }
        val vm = viewModel(Fixtures.ready().copy(recordings = many))
        showApp(vm)
        repeat(3) { compose.onRoot().performTouchInput { swipeUp(startY = bottom - 10f, endY = centerY - 100f) } }
        compose.waitForIdle()
        compose.onNodeWithText("Olivia Bennett").assertIsNotDisplayed()
        compose.onNodeWithText("Ready to record").assertIsDisplayed()
        compose.onAllNodesWithContentDescription("Record calls").assertCountEquals(1)
        repeat(8) { compose.onRoot().performTouchInput { swipeDown(startY = centerY, endY = bottom - 10f) } }
        compose.waitForIdle()
        compose.onNodeWithText("Olivia Bennett").assertIsDisplayed()
    }

    @Test fun theBusySignalIsForSwitchingOnOnly() {
        // The old busy line moved on every opening of the app - the check it
        // makes - for nothing the user did. The look's signal (here Memo's
        // ring) shows while recording is switching on, and only then.
        val vm = viewModel(Fixtures.ready())
        var state by mutableStateOf(Fixtures.ready().copy(busy = true))
        show { AppScaffold(state, vm) }
        val ring = hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate)
        // Busy with the check on opening: nothing moves.
        compose.onAllNodes(ring).assertCountEquals(0)
        // Switching on: the ring turns.
        compose.runOnUiThread { state = state.copy(starting = true) }
        compose.waitForIdle()
        compose.onAllNodes(ring).assertCountEquals(1)
        compose.onNodeWithText("Starting...").assertIsDisplayed()
    }
}

/** A press that fills everything it may draw on in one plain colour. */
private object PaintsWhenPressed : IndicationNodeFactory {
    val COLOUR = Color(0xFFFF00FF)

    override fun create(interactionSource: InteractionSource): DelegatableNode = Node(interactionSource)

    override fun equals(other: Any?) = other === this

    override fun hashCode() = 0

    private class Node(private val source: InteractionSource) : Modifier.Node(), DrawModifierNode {
        private var pressed = false

        override fun onAttach() {
            coroutineScope.launch {
                source.interactions.collect {
                    when (it) {
                        is PressInteraction.Press -> pressed = true
                        is PressInteraction.Release, is PressInteraction.Cancel -> pressed = false
                    }
                    invalidateDraw()
                }
            }
        }

        override fun ContentDrawScope.draw() {
            drawContent()
            if (pressed) drawRect(COLOUR)
        }
    }
}
