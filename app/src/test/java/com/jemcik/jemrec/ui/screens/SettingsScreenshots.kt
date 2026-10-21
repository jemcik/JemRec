package com.jemcik.jemrec.ui.screens

import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.jemcik.jemrec.capture.CallRules.Contacts
import com.jemcik.jemrec.capture.FileNames
import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.Screen
import com.jemcik.jemrec.ui.UiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * Settings and the screens reached from it, as drawn, against the PNGs in
 * app/src/test/screenshots. Each state is one a person can be in: everything
 * allowed, a permission missing, a rule that keeps nothing, a phone too old
 * for messenger calls, text at twice the size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class SettingsScreenshots : ScreenTest() {

    private fun settings(state: UiState, fontScale: Float = 1f) {
        val shown = state.copy(screen = state.screen.takeIf { it != Screen.HOME } ?: Screen.SETTINGS)
        show(fontScale) { AppScaffold(shown, viewModel(shown)) }
    }

    // The whole overview, so the screen is taller than a phone's.
    @Config(qualifiers = "+h1500dp")
    @Test fun overview() {
        settings(Fixtures.ready())
        snap("settings")
    }

    @Config(qualifiers = "+h1500dp-night")
    @Test fun overviewDark() {
        settings(Fixtures.ready())
        snap("settings-dark")
    }

    @Test fun overviewLargeText() {
        settings(Fixtures.ready(), fontScale = 2f)
        snap("settings-large-text")
    }

    @Test fun overviewAsksForTheCallLog() {
        settings(Fixtures.ready().copy(canReadCallLog = false, canReadContacts = false))
        snap("settings-ask-call-log")
    }

    @Config(qualifiers = "+night")
    @Test fun whoseCallsAllButNobody() {
        // "Every call is kept", and the hint to add someone - not "except
        // calls with 0 contacts".
        settings(Fixtures.ready().copy(screen = Screen.WHOSE_CALLS, ruleContacts = Contacts.ALL_BUT))
        snap("whose-calls-all-but-nobody-dark")
    }

    @Test fun whoseCallsAllButTwo() {
        settings(
            Fixtures.ready().copy(
                screen = Screen.WHOSE_CALLS,
                ruleContacts = Contacts.ALL_BUT,
                ruleChosen = Fixtures.chosen("Dr. Lena Vogel", "Sofia Marchetti"),
            ),
        )
        snap("whose-calls-all-but-two")
    }

    @Test fun whoseCallsKeepingNothing() {
        settings(Fixtures.ready().copy(screen = Screen.WHOSE_CALLS, ruleContacts = Contacts.NONE, ruleOtherNumbers = false))
        snap("whose-calls-nothing-kept")
    }

    @Test fun whoseCallsWithoutPermission() {
        settings(Fixtures.ready().copy(screen = Screen.WHOSE_CALLS, canReadContacts = false))
        snap("whose-calls-no-permission")
    }

    @Config(qualifiers = "+night")
    @Test fun whoseCallsLargeText() {
        // Four buttons on one line at twice the text size: the labels shrink
        // to fit rather than wrap.
        settings(
            Fixtures.ready().copy(
                screen = Screen.WHOSE_CALLS,
                ruleContacts = Contacts.ALL_BUT,
                ruleChosen = Fixtures.chosen("Dr. Lena Vogel"),
            ),
            fontScale = 2f,
        )
        snap("whose-calls-large-text-dark")
    }

    @Config(qualifiers = "+night")
    @Test fun messengerCalls() {
        settings(Fixtures.ready().copy(screen = Screen.MESSENGER_CALLS))
        snap("messenger-calls-dark")
    }

    @Test fun messengerCallsBeforeAndroid14() {
        settings(Fixtures.ready().copy(screen = Screen.MESSENGER_CALLS, voipSupported = false))
        snap("messenger-calls-before-14")
    }

    @Test fun messengerCallsNoneInstalled() {
        settings(Fixtures.ready().copy(screen = Screen.MESSENGER_CALLS, voipInstalled = emptyList()))
        snap("messenger-calls-none-installed")
    }

    @Config(qualifiers = "+night")
    @Test fun diagnostics() {
        settings(
            Fixtures.ready().copy(
                screen = Screen.DIAGNOSTICS,
                log = "[17:46:08] PASS - a call would be recorded\n\n" +
                    "recorder:  answering on 127.0.0.1:28472\n" +
                    "codec:     opus\n" +
                    "mode:      every call\n" +
                    "tracking:  on",
            ),
        )
        snap("diagnostics-dark")
    }

    @Test fun diagnosticsRecorderDown() {
        settings(Fixtures.ready().copy(screen = Screen.DIAGNOSTICS, daemonRunning = false))
        snap("diagnostics-recorder-down")
    }

    @Test fun fileNamesDialog() {
        // Honor's format chosen but the folder not yet: the dialog says so,
        // with the button that fixes it.
        settings(
            Fixtures.ready().copy(honorDialer = true, fileNames = FileNames.Format.HONOR, inHonorFolder = false),
        )
        compose.onNodeWithText("File names").performScrollTo().performClick()
        compose.waitForIdle()
        snapWithDialog("dialog-file-names")
    }

    @Config(qualifiers = "+night")
    @Test fun autoDeleteDialog() {
        settings(Fixtures.ready())
        compose.onNodeWithText("Auto-delete old recordings").performScrollTo().performClick()
        compose.waitForIdle()
        snapWithDialog("dialog-auto-delete-dark")
    }
}
