package com.jemcik.jemrec.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.AutoStart
import com.jemcik.jemrec.ui.SetupStep
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
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
}
