package com.jemcik.jemrec.ui.screens

import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.ChoiceDialog
import com.jemcik.jemrec.ui.ConnectedChoices
import com.jemcik.jemrec.ui.HomeTab
import com.jemcik.jemrec.ui.Screen
import com.jemcik.jemrec.ui.SettingsRow
import com.jemcik.jemrec.ui.SwitchRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
        show {
            val state by vm.state.collectAsState()
            AppScaffold(state, vm)
        }
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
        show {
            val state by vm.state.collectAsState()
            AppScaffold(state, vm)
        }
        compose.onNodeWithText("Start fresh").performScrollTo()
        compose.onNodeWithText("Recently deleted").performClick()
        compose.waitForIdle()
        back()
        compose.onNodeWithText("Start fresh").assertIsDisplayed()
    }

    @Test fun aSelfTestResultThatLandsElsewhereIsNotSavedUp() {
        // It used to be marked seen only on the screens of Settings, so one
        // that came in while home was up waited, and jumped Diagnostics to
        // the bottom whenever it was next opened.
        val vm = viewModel(Fixtures.ready().copy(screen = Screen.HOME, selfTestShown = 1L))
        show {
            val state by vm.state.collectAsState()
            AppScaffold(state, vm)
        }
        assertEquals(0L, vm.state.value.selfTestShown)
    }

    @Test fun theTipGoesAtItsX() {
        val vm = viewModel(Fixtures.ready().copy(selectHintSeen = false))
        show {
            val state by vm.state.collectAsState()
            AppScaffold(state, vm)
        }
        compose.onNodeWithText("Tap a photo, or press and hold a call, to select recordings").assertIsDisplayed()
        compose.onNodeWithContentDescription("Dismiss tip").performClick()
        compose.waitForIdle()
        assertTrue(vm.state.value.selectHintSeen)
        compose.onAllNodesWithText("Tap a photo, or press and hold a call, to select recordings").assertCountEquals(0)
    }

    @Test fun playbackPausesWhenItsControlsLeaveTheScreen() {
        // The scrubber under the row is the only control; another tab or
        // Settings takes it off the screen, so the playing stops there too -
        // paused, with its place kept.
        val playing = Fixtures.ready().copy(
            homeTab = HomeTab.CALLS, playingName = Fixtures.recordings().first().name,
            playing = true, positionMs = 60_000, durationMs = 252_000,
        )
        viewModel(playing).let { vm ->
            vm.showTab(HomeTab.RECORDINGS)
            assertFalse("another tab", vm.state.value.playing)
            assertEquals("its place kept", 60_000, vm.state.value.positionMs)
            assertEquals(playing.playingName, vm.state.value.playingName)
        }
        viewModel(playing).let { vm ->
            vm.showSettings()
            assertFalse("Settings", vm.state.value.playing)
        }
        viewModel(playing).let { vm ->
            vm.showTrash()
            assertFalse("Recently deleted", vm.state.value.playing)
        }
    }

    @Test fun noLimitOnAMessageIsAskedAboutFirst() {
        val state = Fixtures.ready().copy(screen = Screen.ANSWERING_MACHINE)
        val vm = com.jemcik.jemrec.ui.MainViewModel(
            org.robolectric.RuntimeEnvironment.getApplication(), state,
            com.jemcik.jemrec.ui.AnswerUi(enabled = true, greetingSeconds = 6.0, longest = 60),
        )
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
        val vm = com.jemcik.jemrec.ui.MainViewModel(
            org.robolectric.RuntimeEnvironment.getApplication(), state,
            com.jemcik.jemrec.ui.AnswerUi(enabled = true, greetingSeconds = 6.0, pausedUntil = Fixtures.NOW),
        )
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

    @Test fun aSwitchRowIsOneSwitch() {
        // The whole row is the control: one toggleable node, its text merged
        // in, not a row with a switch beside it that a screen reader visits
        // separately.
        show {
            var on by remember { mutableStateOf(true) }
            SwitchRow("Record every call", on, { on = it }, RectangleShape, summary = "Every call, without asking")
        }
        compose.onAllNodes(isToggleable()).assertCountEquals(1)
        compose.onNodeWithText("Record every call").assertIsOn().performClick()
        compose.onNodeWithText("Record every call").assertIsOff()
    }

    @Test fun aDisabledRowIgnoresTaps() {
        var tapped = false
        show { SettingsRow("Start fresh", RectangleShape, onClick = { tapped = true }, enabled = false) }
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
}
