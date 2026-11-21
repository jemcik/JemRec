package com.jemcik.jemrec.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.jemcik.jemrec.recordings.Waveforms
import com.jemcik.jemrec.ui.AnswerUi
import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.CallsNarrowing
import com.jemcik.jemrec.ui.HomeTab
import com.jemcik.jemrec.ui.RecordingsNarrowing
import com.jemcik.jemrec.ui.UiState
import com.jemcik.jemrec.ui.theme.LightDark
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookChoice
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * The home screen as drawn - header, tabs, the two lists - against the PNGs
 * in app/src/test/screenshots: recording, off, unable to record, playing (the
 * waveform, and the slider before it),
 * selecting, empty, and at twice the text size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class HomeScreenshots : ScreenTest() {

    /** Home in [look], the answering machine on - so every header shows all
     *  four marks: the mode, answering, messengers (two of three apps on in
     *  the fixture) and auto-delete (90 days). */
    private fun home(state: UiState, fontScale: Float = 1f, look: LookChoice = LookChoice()) {
        val vm = viewModel(state, AnswerUi(enabled = true))
        show(fontScale, look) { AppScaffold(state, vm) }
    }

    // Each row's waveform, as the cache would hand it over: a call of its
    // own for each recording, the same every run.
    @Before fun rowWaveforms() {
        Waveforms.override = { name -> Fixtures.waveform(name.hashCode().toLong()) }
    }

    @After fun noRowWaveforms() {
        Waveforms.override = null
    }

    private val deck = LookChoice(Look.DECK)
    private val multitrack = LookChoice(Look.MULTITRACK)

    @Test fun recordings() {
        home(Fixtures.ready())
        snap("home")
    }

    @Config(qualifiers = "+night")
    @Test fun recordingsDark() {
        home(Fixtures.ready())
        snap("home-dark")
    }

    // A phone on its side, 800 x 360 dp: the header in a column at the left,
    // the list beside it at the full height (headerAside).
    @Config(qualifiers = "w800dp-h360dp-land-hdpi")
    @Test fun landscape() {
        home(Fixtures.ready())
        snap("home-landscape")
    }

    @Config(qualifiers = "w800dp-h360dp-land-hdpi")
    @Test fun deckLandscape() {
        home(Fixtures.ready(), look = deck)
        snap("deck-landscape")
    }

    @Config(qualifiers = "w800dp-h360dp-land-night-hdpi")
    @Test fun multitrackLandscapeDark() {
        home(Fixtures.ready(), look = multitrack)
        snap("multitrack-landscape-dark")
    }

    @Test fun withTheSelectionTip() {
        home(Fixtures.ready().copy(selectHintSeen = false))
        snap("home-tip")
    }

    @Test fun recordingsLargeText() {
        home(Fixtures.ready(), fontScale = 2f)
        snap("home-large-text")
    }

    @Test fun messengerUninstalledLargeText() {
        // The rare case at the hardest size: the app's name in words, which
        // gives way first - the time next, and the length never.
        messengersInstalled = false
        home(Fixtures.ready(), fontScale = 2f)
        snap("home-messenger-uninstalled-large-text")
    }

    @Test fun playing() {
        home(
            Fixtures.playing(),
        )
        snap("home-playing")
    }

    @Config(qualifiers = "+night")
    @Test fun playingDark() {
        home(
            Fixtures.playing(),
        )
        snap("home-playing-dark")
    }

    /** At the earpiece: the button an ear, in the look's colour for on. */
    @Test fun playingAtTheEarpiece() {
        home(Fixtures.playing(earpiece = true))
        snap("home-playing-earpiece")
    }

    /** A long call's time beside four buttons: it must still fit at 360 dp. */
    @Test fun playingALongCall() {
        home(Fixtures.playing(positionMs = 1_925_000, durationMs = 2_706_000))
        snap("home-playing-long")
    }

    @Test fun playingBeforeItsWaveform() {
        // The first play of a long call, while its loudness is worked out:
        // the plain scrubber, as before there was a waveform.
        home(Fixtures.playing(waveform = null))
        snap("home-playing-slider")
    }

    @Config(qualifiers = "+night")
    @Test fun selecting() {
        val picked = Fixtures.recordings().take(2).map { it.name }.toSet()
        home(Fixtures.ready().copy(selecting = true, selected = picked))
        snap("home-selecting-dark")
    }

    @Test fun empty() {
        // After a reinstall, the old recordings are there but unreadable: the
        // empty list says so, with the button that shows them.
        home(Fixtures.ready().copy(recordings = emptyList(), canReadAudio = false))
        snap("home-empty")
    }

    @Config(qualifiers = "+night")
    @Test fun switchedOff() {
        home(Fixtures.ready().copy(enabled = false))
        snap("home-off-dark")
    }

    // Off with all debugging (When JemRec is off, issue #37): the header says
    // turning on needs Wi-Fi, in each look's words, and the answering
    // machine's mark goes dark - set, but there is no recorder to answer.
    @Test fun switchedOffWithDebugging() {
        home(Fixtures.ready().copy(enabled = false, debuggingOff = true))
        snap("home-off-debugging-off")
    }

    @Config(qualifiers = "+night")
    @Test fun deckOffWithDebuggingDark() {
        home(Fixtures.ready().copy(enabled = false, debuggingOff = true), look = deck)
        snap("deck-off-debugging-off-dark")
    }

    @Test fun multitrackOffWithDebugging() {
        home(Fixtures.ready().copy(enabled = false, debuggingOff = true), look = multitrack)
        snap("multitrack-off-debugging-off")
    }

    // The one question turning off asks: all debugging off, and no Wi-Fi to
    // turn back on over.
    @Test fun turnOffAwayFromWifi() {
        home(Fixtures.ready().copy(askingToTurnOff = true, onWifi = false))
        snapWithDialog("dialog-turn-off-away-from-wifi")
    }

    @Test fun cannotRecord() {
        home(Fixtures.ready().copy(daemonRunning = false, onWifi = false))
        snap("home-cannot-record")
    }

    @Test fun cannotRecordOnWifi() {
        // On Wi-Fi, for no reason a revive has named yet: a tap starts it again.
        home(Fixtures.ready().copy(daemonRunning = false))
        snap("home-start-again")
    }

    @Test fun wifiNotAllowed() {
        home(Fixtures.ready().copy(daemonRunning = false, networkNotAllowed = true))
        snap("home-allow-this-wifi")
    }

    @Test fun calls() {
        home(Fixtures.ready().copy(homeTab = HomeTab.CALLS))
        snap("calls")
    }

    @Test fun deckCalls() {
        home(Fixtures.ready().copy(homeTab = HomeTab.CALLS), look = deck)
        snap("deck-calls")
    }

    @Test fun multitrackCalls() {
        home(Fixtures.ready().copy(homeTab = HomeTab.CALLS), look = multitrack)
        snap("multitrack-calls")
    }

    /** The Calls tab's own chip on: the calls that were not recorded. */
    @Test fun callsNotRecorded() {
        home(Fixtures.ready().copy(homeTab = HomeTab.CALLS, callsNarrowing = CallsNarrowing(notRecorded = true)))
        snap("calls-not-recorded")
    }

    @Test fun callsLargeText() {
        home(Fixtures.ready().copy(homeTab = HomeTab.CALLS), fontScale = 2f)
        snap("calls-large-text")
    }

    @Config(qualifiers = "+night")
    @Test fun callsDark() {
        home(Fixtures.ready().copy(homeTab = HomeTab.CALLS))
        snap("calls-dark")
    }

    // Deck: the LCD and the REC key, in each state the header has.

    @Test fun deck() {
        home(Fixtures.ready(), look = deck)
        snap("deck")
    }

    @Config(qualifiers = "+night")
    @Test fun deckDark() {
        home(Fixtures.ready(), look = deck)
        snap("deck-dark")
    }

    @Test fun deckLargeText() {
        home(Fixtures.ready(), fontScale = 2f, look = deck)
        snap("deck-large-text")
    }

    @Test fun deckOff() {
        home(Fixtures.ready().copy(enabled = false), look = deck)
        snap("deck-off")
    }

    @Config(qualifiers = "+night")
    @Test fun deckCannotRecordDark() {
        home(Fixtures.ready().copy(daemonRunning = false, networkNotAllowed = true), look = deck)
        snap("deck-allow-this-wifi-dark")
    }

    @Test fun deckPlaying() {
        home(
            Fixtures.playing(),
            look = deck,
        )
        snap("deck-playing")
    }

    @Test fun deckPlayingAtTheEarpiece() {
        home(Fixtures.playing(earpiece = true), look = deck)
        snap("deck-playing-earpiece")
    }

    /** The player in dark: the negative LCD, and its keys' greys. */
    @Config(qualifiers = "+night")
    @Test fun deckPlayingDark() {
        home(
            Fixtures.playing(),
            look = deck,
        )
        snap("deck-playing-dark")
    }

    @Config(qualifiers = "+night")
    @Test fun deckSelecting() {
        val names = Fixtures.recordings().take(2).map { it.name }.toSet()
        home(Fixtures.ready().copy(selecting = true, selected = names), look = deck)
        snap("deck-selecting-dark")
    }

    // Multitrack: the armed button and an LED per setting.

    @Test fun multitrack() {
        home(Fixtures.ready(), look = multitrack)
        snap("multitrack")
    }

    @Config(qualifiers = "+night")
    @Test fun multitrackDark() {
        home(Fixtures.ready(), look = multitrack)
        snap("multitrack-dark")
    }

    @Test fun multitrackLargeText() {
        home(Fixtures.ready(), fontScale = 2f, look = multitrack)
        snap("multitrack-large-text")
    }

    @Config(qualifiers = "+night")
    @Test fun multitrackOffDark() {
        home(Fixtures.ready().copy(enabled = false), look = multitrack)
        snap("multitrack-off-dark")
    }

    /** The player in light, whose key and buttons were near-black once. */
    @Test fun multitrackPlaying() {
        home(
            Fixtures.playing(),
            look = multitrack,
        )
        snap("multitrack-playing")
    }

    @Test fun multitrackPlayingAtTheEarpiece() {
        home(Fixtures.playing(earpiece = true), look = multitrack)
        snap("multitrack-playing-earpiece")
    }

    /** Multitrack's timecode is the longest of the three looks' times. */
    @Test fun multitrackPlayingALongCall() {
        home(Fixtures.playing(positionMs = 1_925_000, durationMs = 2_706_000), look = multitrack)
        snap("multitrack-playing-long")
    }

    @Config(qualifiers = "+night")
    @Test fun multitrackPlayingDark() {
        home(
            Fixtures.playing(),
            look = multitrack,
        )
        snap("multitrack-playing-dark")
    }

    @Test fun multitrackCannotRecord() {
        home(Fixtures.ready().copy(daemonRunning = false, onWifi = false), look = multitrack)
        snap("multitrack-cannot-record")
    }

    // What home draws that nothing drew before the 1.0 restructuring, which
    // splits HomeScreen and the narrowing headers: the call-log offer, the
    // Calls tab before its permission, the delete question, the share sheet
    // and its wait, and a narrowing that leaves nothing.

    /** The one-time offer over the list, before the call log is allowed. */
    @Test fun callLogOffer() {
        home(Fixtures.ready().copy(offerCallLog = true, canReadCallLog = false, canReadContacts = false))
        compose.onNodeWithText("Show who called").assertIsDisplayed()
        snap("home-call-log-offer")
    }

    /** Notifications refused after setup: home says what that costs, and
     *  the recordings stay (NotificationHealth). Put off with Not now while
     *  calls are recorded anyway. */
    @Test fun notificationsOff() {
        home(Fixtures.ready().copy(notificationsOff = com.jemcik.jemrec.calls.NotificationHealth.Off.ALL))
        compose.onNodeWithText("Notifications are off").assertIsDisplayed()
        compose.onNodeWithText("Not now").assertIsDisplayed()
        snap("home-notifications-off")
    }

    /** In Ask first, with its question off, no call is recorded: no Not now. */
    @Test fun askFirstCannotAsk() {
        home(
            Fixtures.ready().copy(
                automatic = false,
                notificationsOff = com.jemcik.jemrec.calls.NotificationHealth.Off.ASK_FIRST,
            ),
        )
        compose.onNodeWithText("Ask first cannot ask").assertIsDisplayed()
        compose.onNodeWithText("Not now").assertDoesNotExist()
        // The header says so too, with its way back.
        compose.onNodeWithText("Tap to turn on notifications").assertIsDisplayed()
        snap("home-ask-first-cannot-ask")
    }

    /** The same in the other two looks: their capitals on one line. */
    @Test fun askFirstCannotAskInDeck() {
        val state = Fixtures.ready().copy(automatic = false, notificationsOff = com.jemcik.jemrec.calls.NotificationHealth.Off.ASK_FIRST)
        home(state, look = deck)
        // The header is one sentence to TalkBack (headerSpeech), its words a picture.
        compose.onNodeWithContentDescription("Cannot record. Ask first asks in a notification", substring = true).assertIsDisplayed()
        snap("deck-ask-first-cannot-ask")
    }

    @Test fun askFirstCannotAskInMultitrack() {
        val state = Fixtures.ready().copy(automatic = false, notificationsOff = com.jemcik.jemrec.calls.NotificationHealth.Off.ASK_FIRST)
        home(state, look = multitrack)
        compose.onNodeWithContentDescription("Cannot record. Ask first asks in a notification", substring = true).assertIsDisplayed()
        snap("multitrack-ask-first-cannot-ask")
    }

    /** Selecting at 200% text: the bar's title on one line, cut with an
     *  ellipsis rather than wrapped out of the bar. */
    @Test fun selectingAtLargeText() {
        val recordings = Fixtures.recordings()
        home(Fixtures.ready().copy(recordings = recordings, selecting = true, selected = setOf(recordings.first().name)), fontScale = 2f)
        snap("home-selecting-large-text")
    }

    /** The folder chosen lost, with Honor's file names: where calls go now,
     *  and that the Phone app looks only in its own folder (FolderAccess). */
    @Test fun folderLost() {
        home(
            Fixtures.ready().copy(
                folder = "Sounds/CallRecord", usingDefaultFolder = false, folderLost = true,
                fileNames = com.jemcik.jemrec.recordings.FileNames.Format.HONOR,
            ),
        )
        compose.onNodeWithText("Your recordings folder cannot be reached").assertIsDisplayed()
        compose.onNodeWithText("Honor's Phone app plays only", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Use the standard folder").assertIsDisplayed()
        snap("home-folder-lost")
    }

    /** While the screen is busy, Use the standard folder is greyed - it is
     *  dropped then (the busy table), as the Settings dialog's Use default
     *  is - and Choose again is not: a folder picked is taken at once. */
    @Test fun folderLostWhileBusyGreysOnlyWhatWouldBeDropped() {
        home(Fixtures.ready().copy(folder = "Sounds/CallRecord", usingDefaultFolder = false, folderLost = true, busy = true))
        compose.onNodeWithText("Use the standard folder").assertIsNotEnabled()
        compose.onNodeWithText("Choose again").assertIsEnabled()
    }

    /** The same at 200% text: the two ways on, one under the other where
     *  they do not fit beside each other, each whole. */
    @Test fun folderLostAtLargeText() {
        home(
            Fixtures.ready().copy(folder = "Sounds/CallRecord", usingDefaultFolder = false, folderLost = true),
            fontScale = 2f,
        )
        compose.onNodeWithText("Use the standard folder").performScrollTo().assertIsDisplayed()
        snap("home-folder-lost-large-text")
    }

    /** The Battery setting Restricted: what it holds back, App info, and
     *  Not now in Automatic. */
    @Test fun batteryRestricted() {
        home(Fixtures.ready().copy(batteryNotice = true))
        compose.onNodeWithText("Battery use is Restricted").assertIsDisplayed()
        compose.onNodeWithText("Open App info").assertIsDisplayed()
        compose.onNodeWithText("Not now").assertIsDisplayed()
        snap("home-battery-restricted")
    }

    /** In Ask first a call may go unasked: no Not now. With exact call
     *  tracking off no call can be asked about, and the header says it
     *  cannot record, with the way to App info. */
    @Test fun batteryRestrictedInAskFirstCannotBePutOff() {
        home(Fixtures.ready().copy(automatic = false, batteryNotice = true, batteryRestricted = true, callTracking = false))
        compose.onNodeWithText("none is recorded", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Not now").assertDoesNotExist()
        compose.onNodeWithContentDescription("Cannot record.", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Battery Restricted · tap to allow").assertIsDisplayed()
        snap("home-ask-first-battery-restricted")
    }

    @Test fun notificationsOffInAskFirstCannotBePutOff() {
        home(Fixtures.ready().copy(automatic = false, notificationsOff = com.jemcik.jemrec.calls.NotificationHealth.Off.ALL))
        compose.onNodeWithText("no call is recorded", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Not now").assertDoesNotExist()
    }

    @Test fun callsWithoutPermission() {
        home(Fixtures.ready().copy(homeTab = HomeTab.CALLS, canReadCallLog = false, canReadContacts = false))
        compose.onNodeWithText("See every call here").assertIsDisplayed()
        snap("calls-no-permission")
    }

    @Test fun deleteSelectedDialog() {
        val picked = Fixtures.recordings().take(2).map { it.name }.toSet()
        home(Fixtures.ready().copy(selecting = true, selected = picked, confirmingDeleteSelected = true))
        compose.onNodeWithText("Delete 2 recordings?").assertIsDisplayed()
        snapWithDialog("dialog-delete-selected")
    }

    /** Share asks how first: the sheet over the list. */
    @Test fun shareSheet() {
        home(Fixtures.ready().copy(shareChoice = Fixtures.recordings().first()))
        compose.onNodeWithText("Share as").assertIsDisplayed()
        snapWithDialog("home-share-sheet")
    }

    /** The wait while the .m4a is made. */
    @Test fun convertingToShare() {
        home(Fixtures.ready().copy(converting = true))
        compose.onNodeWithText("Converting to .m4a…").assertIsDisplayed()
        snapWithDialog("dialog-converting")
    }

    /** A source chip still on after its calls have gone: the list says
     *  nothing matches, under the chip that would bring them back. */
    @Test fun recordingsNoMatches() {
        home(Fixtures.ready().copy(recordingsNarrowing = RecordingsNarrowing(sources = setOf("telegram"))))
        compose.onNodeWithText("No matches").assertIsDisplayed()
        compose.onNodeWithText("No recordings match your search or filter.").assertIsDisplayed()
        snap("home-no-matches")
    }

    // The user's own light or dark, over the phone's: Dark chosen on a
    // light phone draws what the dark phone does.

    @Test fun deckChosenDarkOnALightPhone() {
        home(Fixtures.ready(), look = LookChoice(Look.DECK, LightDark.DARK))
        snap("deck-dark")
    }

    @Config(qualifiers = "+night")
    @Test fun memoChosenLightOnADarkPhone() {
        home(Fixtures.ready(), look = LookChoice(Look.MEMO, LightDark.LIGHT))
        snap("home")
    }
}
