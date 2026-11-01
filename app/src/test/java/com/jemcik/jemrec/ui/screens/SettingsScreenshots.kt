package com.jemcik.jemrec.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.jemcik.jemrec.capture.CallRules.Contacts
import com.jemcik.jemrec.capture.FileNames
import com.jemcik.jemrec.capture.OffMode
import com.jemcik.jemrec.ui.AnswerUi
import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.Screen
import com.jemcik.jemrec.ui.UiState
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookChoice
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

    private fun settings(state: UiState, fontScale: Float = 1f, look: LookChoice = LookChoice()) {
        val shown = state.copy(screen = state.screen.takeIf { it != Screen.HOME } ?: Screen.SETTINGS)
        show(fontScale, look) { AppScaffold(shown, viewModel(shown)) }
    }

    // Settings > Theme: the home screen in the look picked, drawn by its own
    // parts, the looks and light or dark under it - each look once.

    @Test fun theme() {
        settings(Fixtures.ready().copy(screen = Screen.THEME))
        snap("theme")
    }

    @Config(qualifiers = "+night")
    @Test fun themeDeckDark() {
        // Deck chosen: the whole screen is Deck's, and so is the preview.
        val deck = LookChoice(Look.DECK)
        settings(Fixtures.ready().copy(screen = Screen.THEME), look = deck)
        snap("theme-deck-dark")
    }

    @Test fun themeMultitrack() {
        val multitrack = LookChoice(Look.MULTITRACK)
        settings(Fixtures.ready().copy(screen = Screen.THEME), look = multitrack)
        snap("theme-multitrack")
    }

    @Test fun themeLargeText() {
        settings(Fixtures.ready().copy(screen = Screen.THEME), fontScale = 2f)
        snap("theme-large-text")
    }

    // The rest of the app in the other looks: Material's own pieces in each
    // look's colours, as Settings will be drawn.

    @Config(qualifiers = "+h1800dp")
    @Test fun overviewDeck() {
        settings(Fixtures.ready(), look = LookChoice(Look.DECK))
        snap("settings-deck")
    }

    @Config(qualifiers = "+h1800dp-night")
    @Test fun overviewMultitrackDark() {
        settings(Fixtures.ready(), look = LookChoice(Look.MULTITRACK))
        snap("settings-multitrack-dark")
    }

    // Each look in light and dark: Deck dark (the user's own) and
    // Multitrack light, beside Memo's two above and the two over these.

    @Config(qualifiers = "+h1800dp-night")
    @Test fun overviewDeckDark() {
        settings(Fixtures.ready(), look = LookChoice(Look.DECK))
        snap("settings-deck-dark")
    }

    @Config(qualifiers = "+h1800dp")
    @Test fun overviewMultitrack() {
        settings(Fixtures.ready(), look = LookChoice(Look.MULTITRACK))
        snap("settings-multitrack")
    }

    // The whole overview, so the screen is taller than a phone's.
    @Config(qualifiers = "+h1800dp")
    @Test fun overview() {
        settings(Fixtures.ready())
        snap("settings")
    }

    @Config(qualifiers = "+h1800dp-night")
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

    // When JemRec is off (issue #37): three choices, each with the same two
    // lines so they read across - the user could not tell the second from the
    // third in an earlier draft. In each look, with JemRec off (a new choice
    // waits for the next off), and at twice the text size.

    @Test fun whenOff() {
        settings(Fixtures.ready().copy(screen = Screen.WHEN_OFF, offMode = OffMode.HIDE_DEVELOPER_OPTIONS))
        snap("when-off")
    }

    @Config(qualifiers = "+night")
    @Test fun whenOffDeckDark() {
        settings(
            Fixtures.ready().copy(screen = Screen.WHEN_OFF, offMode = OffMode.ALL_DEBUGGING_OFF),
            look = LookChoice(Look.DECK),
        )
        snap("when-off-deck-dark")
    }

    @Test fun whenOffMultitrack() {
        settings(Fixtures.ready().copy(screen = Screen.WHEN_OFF), look = LookChoice(Look.MULTITRACK))
        snap("when-off-multitrack")
    }

    @Test fun whenOffWhileOff() {
        settings(
            Fixtures.ready().copy(
                screen = Screen.WHEN_OFF,
                enabled = false,
                offMode = OffMode.ALL_DEBUGGING_OFF,
                debuggingOff = true,
            ),
        )
        snap("when-off-while-off")
    }

    // Start without Wi-Fi on, its port open: Turn all debugging off comes
    // back anywhere, and the footer no longer points to the switch.
    @Test fun whenOffWithStartWithoutWifi() {
        settings(
            Fixtures.ready().copy(
                screen = Screen.WHEN_OFF,
                offMode = OffMode.ALL_DEBUGGING_OFF,
                startWithoutWifi = true,
                portSet = true,
            ),
        )
        snap("when-off-start-without-wifi")
    }

    @Config(qualifiers = "+h1500dp")
    @Test fun whenOffLargeText() {
        settings(Fixtures.ready().copy(screen = Screen.WHEN_OFF, offMode = OffMode.ALL_DEBUGGING_OFF), fontScale = 2f)
        snap("when-off-large-text")
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

    // In Multitrack's mono, wider than the phone's font: "Start recorder" was
    // on two lines in its button on the Honor (2026-10-23). One line now.
    @Test fun diagnosticsInMultitrack() {
        settings(Fixtures.ready().copy(screen = Screen.DIAGNOSTICS, daemonRunning = false), look = LookChoice(Look.MULTITRACK))
        snap("diagnostics-multitrack")
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

    @Test fun fileNamesDialogWithTheNumber() {
        // The number format's words under its choices: the longest of the
        // formats', and at twice the text size below.
        settings(Fixtures.ready().copy(fileNames = FileNames.Format.DATE_AND_NUMBER))
        compose.onNodeWithText("File names").performScrollTo().performClick()
        compose.waitForIdle()
        snapWithDialog("dialog-file-names-number")
    }

    // The dialog is a window of its own, which the scale settings() gives the
    // screen does not reach: the configuration's reaches both.
    @Config(qualifiers = "+night", fontScale = 2f)
    @Test fun fileNamesDialogWithTheNumberLargeText() {
        settings(Fixtures.ready().copy(fileNames = FileNames.Format.DATE_AND_NUMBER), fontScale = 2f)
        compose.onNodeWithText("File names").performScrollTo().performClick()
        compose.waitForIdle()
        snapWithDialog("dialog-file-names-number-large-text-dark")
    }

    // Recently deleted, with its Restore all and Delete all for good, and the
    // question Delete all for good asks first.
    @Test fun recentlyDeleted() {
        settings(Fixtures.ready().copy(screen = Screen.TRASH, trashed = Fixtures.trashed()))
        snap("trash")
    }

    @Test fun recentlyDeletedInDeck() {
        settings(Fixtures.ready().copy(screen = Screen.TRASH, trashed = Fixtures.trashed()), look = LookChoice(Look.DECK))
        snap("trash-deck")
    }

    @Test fun recentlyDeletedInMultitrack() {
        settings(Fixtures.ready().copy(screen = Screen.TRASH, trashed = Fixtures.trashed()), look = LookChoice(Look.MULTITRACK))
        snap("trash-multitrack")
    }

    @Test fun recentlyDeletedAsksFirst() {
        settings(Fixtures.ready().copy(screen = Screen.TRASH, trashed = Fixtures.trashed(), confirmingEmptyTrash = true))
        snapWithDialog("trash-delete-all")
    }

    @Test fun trackingOffAsksFirst() {
        // With the answering machine on, exact call tracking's switch asks
        // before it turns both off.
        val shown = Fixtures.ready().copy(screen = Screen.SETTINGS)
        show { AppScaffold(shown, viewModel(shown, AnswerUi(enabled = true, greetingSeconds = 6.0))) }
        compose.onNodeWithText("Exact call tracking").performScrollTo().performClick()
        compose.onNodeWithText("Turn off the answering machine too?").assertIsDisplayed()
        snapWithDialog("dialog-tracking-off")
    }

    @Config(qualifiers = "+night")
    @Test fun autoDeleteDialog() {
        settings(Fixtures.ready())
        compose.onNodeWithText("Auto-delete old recordings").performScrollTo().performClick()
        compose.waitForIdle()
        snapWithDialog("dialog-auto-delete-dark")
    }
}
