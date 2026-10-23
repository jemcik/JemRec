package com.jemcik.jemrec.ui.screens

import com.jemcik.jemrec.capture.Waveforms
import com.jemcik.jemrec.ui.AnswerUi
import com.jemcik.jemrec.ui.CallsNarrowing
import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.HomeTab
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

    @Test fun cannotRecord() {
        home(Fixtures.ready().copy(daemonRunning = false))
        snap("home-cannot-record")
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

    @Config(qualifiers = "+night")
    @Test fun multitrackPlayingDark() {
        home(
            Fixtures.playing(),
            look = multitrack,
        )
        snap("multitrack-playing-dark")
    }

    @Test fun multitrackCannotRecord() {
        home(Fixtures.ready().copy(daemonRunning = false), look = multitrack)
        snap("multitrack-cannot-record")
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
