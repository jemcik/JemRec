package com.jemcik.jemrec.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.capture.GlobalSettings
import com.jemcik.jemrec.ui.AutoStart
import com.jemcik.jemrec.ui.DeviceSkin
import com.jemcik.jemrec.ui.Setup
import com.jemcik.jemrec.ui.SetupStep
import com.jemcik.jemrec.ui.ShellGrant
import com.jemcik.jemrec.ui.UiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * Setup's cards, as drawn, against the PNGs in app/src/test/screenshots.
 *
 * The background-start card first, because the user found its first layout
 * unbalanced on the Honor (2026-10-25): a lone button over a full-width pair.
 * It is now one row of two equal buttons, as the debugging card before it -
 * so each picture here is also the check that nothing slides back.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class SetupScreenshots : ScreenTest() {

    private fun backgroundCard(fontScale: Float = 1f) {
        // An Honor's App launch, as the setup check finds it (AutoStart.screen)
        // and the card names it.
        val honor = AutoStart.screenFor(
            "com.hihonor.systemmanager",
            "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        )
        val state = Fixtures.ready().copy(
            step = SetupStep.NEEDS_BACKGROUND_CHOICE,
            backgroundLabel = honor.label,
            backgroundInstruction = honor.instruction,
        )
        show(fontScale) { AppScaffold(state, viewModel(state)) }
        compose.onNodeWithText("Let JemRec keep running").assertIsDisplayed()
        // At twice the text size the card runs past the screen's foot, so
        // the buttons are scrolled to - and the picture then shows them.
        compose.onNodeWithText("Not now").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("App launch").performScrollTo().assertIsDisplayed()
        // Opening the screen is the answer; there is no separate "Done".
        check(compose.onAllNodes(androidx.compose.ui.test.hasText("Done")).fetchSemanticsNodes().isEmpty()) {
            "the card still has a Done button"
        }
    }

    @Test fun backgroundStep() {
        backgroundCard()
        snap("setup-background")
    }

    @Config(qualifiers = "+night")
    @Test fun backgroundStepDark() {
        backgroundCard()
        snap("setup-background-dark")
    }

    @Test fun backgroundStepAtTwiceTheTextSize() {
        backgroundCard(fontScale = 2f)
        snap("setup-background-2x")
    }

    /** An OPPO, OnePlus or realme phone's pairing card: the switch setup's
     *  grant needs is named before the grant, not after (issues #61, #64). */
    @Test fun pairingStepOnAOnePlus() {
        val state = Fixtures.ready().copy(
            step = SetupStep.NEEDS_PAIRING,
            usbDebugging = GlobalSettings.Reading.OFF,
            wirelessDebuggingOn = false,
            systemOptimizationSwitch = true,
        )
        show { AppScaffold(state, viewModel(state)) }
        compose.onNodeWithText("Disable system optimization", substring = true).assertIsDisplayed()
        snap("setup-pairing-oneplus")
    }

    /** The grant refused, as #64's OnePlus refused it: its switch, and a way
     *  to Settings beside Try again. */
    private fun refusedCard(fontScale: Float = 1f) {
        val state = Fixtures.ready().copy(
            step = SetupStep.FINISHING,
            finishFailed = ShellGrant.advice(ShellGrant.Outcome.REFUSED, DeviceSkin.OPLUS, monitoring = true),
        )
        show(fontScale) { AppScaffold(state, viewModel(state)) }
        compose.onNodeWithText("Disable system optimization", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Try again").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Open settings").performScrollTo().assertIsDisplayed()
    }

    @Test fun grantRefusedOnAOnePlus() {
        refusedCard()
        snap("setup-refused-oneplus")
    }

    @Test fun grantRefusedAtTwiceTheTextSize() {
        refusedCard(fontScale = 2f)
        snap("setup-refused-oneplus-2x")
    }

    // Every other card setup has, as its state draws it: none was drawn
    // before the 1.0 restructuring, which splits SetupScreen's one function
    // into one per step - these show that it changes nothing.

    private fun step(state: UiState) = show { AppScaffold(state, viewModel(state)) }

    /** The first look, while the check that opens the app holds busy: the
     *  bar over the card as well as the card's own spinner. */
    @Test fun checkingStep() {
        step(Fixtures.ready().copy(step = SetupStep.CHECKING, busy = true))
        compose.onNodeWithText("Checking...").assertIsDisplayed()
        snap("setup-checking")
    }

    @Test fun permissionsStep() {
        step(Fixtures.ready().copy(step = SetupStep.NEEDS_PERMISSIONS))
        compose.onNodeWithText("Allow permissions").assertIsDisplayed()
        snap("setup-permissions")
    }

    @Test fun debuggingChoiceStep() {
        step(Fixtures.ready().copy(step = SetupStep.NEEDS_DEBUGGING_CHOICE))
        compose.onNodeWithText("Set up JemRec").assertIsDisplayed()
        compose.onNodeWithText("Hide it").performScrollTo().assertIsDisplayed()
        snap("setup-debugging-choice")
    }

    /** The same question for an install set up before it existed: not
     *  greeted as a setup. */
    @Test fun debuggingChoiceAfterSetup() {
        Setup.markComplete(RuntimeEnvironment.getApplication())
        step(Fixtures.ready().copy(step = SetupStep.NEEDS_DEBUGGING_CHOICE))
        compose.onNodeWithText("One question").assertIsDisplayed()
        snap("setup-debugging-choice-after-setup")
    }

    @Test fun developerOptionsStep() {
        step(Fixtures.ready().copy(step = SetupStep.NEEDS_DEVELOPER_OPTIONS))
        compose.onAllNodes(hasText("They are on already")).assertCountEquals(0)
        snap("setup-developer-options")
    }

    /** On a phone that hides whether they are on: the user may say so. */
    @Test fun developerOptionsStepWhereThePhoneHidesThem() {
        step(Fixtures.ready().copy(step = SetupStep.NEEDS_DEVELOPER_OPTIONS, developerOptionsHidden = true))
        compose.onNodeWithText("They are on already").performScrollTo().assertIsDisplayed()
        snap("setup-developer-options-hidden")
    }

    // Finish connecting, in its three branches: a switch off, both on with
    // USB debugging hidden from the app, both on.

    @Test fun connectionStepWithASwitchOff() {
        step(Fixtures.ready().copy(step = SetupStep.NEEDS_CONNECTION, wirelessDebuggingOn = false))
        compose.onNodeWithText("Open settings").performScrollTo().assertIsDisplayed()
        snap("setup-connection-switch-off")
    }

    @Test fun connectionStepWithUsbDebuggingHidden() {
        step(Fixtures.ready().copy(step = SetupStep.NEEDS_CONNECTION, usbDebugging = GlobalSettings.Reading.UNKNOWN))
        compose.onNodeWithText("Check that USB debugging is on", substring = true).assertIsDisplayed()
        snap("setup-connection-usb-unknown")
    }

    @Test fun connectionStepWithBothOn() {
        step(Fixtures.ready().copy(step = SetupStep.NEEDS_CONNECTION))
        compose.onNodeWithText("Both switches are on", substring = true).assertIsDisplayed()
        snap("setup-connection-both-on")
    }

    /** The last step as it runs - inside the check, so busy - with nothing
     *  failed: a spinner, and nothing to do. */
    @Test fun finishingStep() {
        step(Fixtures.ready().copy(step = SetupStep.FINISHING, busy = true))
        compose.onNodeWithText("Almost done").assertIsDisplayed()
        compose.onAllNodes(hasText("Try again")).assertCountEquals(0)
        snap("setup-finishing")
    }

    /** Pairing with JemRec's notifications off: nowhere for the code to go,
     *  said before the steps, with the way to turn them on. */
    @Test fun pairingStepWithNotificationsBlocked() {
        step(Fixtures.ready().copy(step = SetupStep.NEEDS_PAIRING, pairingNotificationsBlocked = true))
        compose.onNodeWithText("Turn on notifications").assertIsDisplayed()
        snap("setup-pairing-notifications-blocked")
    }
}
