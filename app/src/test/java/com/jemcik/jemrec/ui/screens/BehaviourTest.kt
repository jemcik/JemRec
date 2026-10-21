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
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.ChoiceDialog
import com.jemcik.jemrec.ui.ConnectedChoices
import com.jemcik.jemrec.ui.Screen
import com.jemcik.jemrec.ui.SettingsRow
import com.jemcik.jemrec.ui.SwitchRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
