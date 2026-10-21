package com.jemcik.jemrec.ui.screens

import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.HomeTab
import com.jemcik.jemrec.ui.UiState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * The home screen as drawn - header, tabs, the two lists - against the PNGs
 * in app/src/test/screenshots: recording, off, unable to record, playing,
 * selecting, empty, and at twice the text size.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class HomeScreenshots : ScreenTest() {

    private fun home(state: UiState, fontScale: Float = 1f) {
        show(fontScale) { AppScaffold(state, viewModel(state)) }
    }

    @Test fun recordings() {
        home(Fixtures.ready())
        snap("home")
    }

    @Config(qualifiers = "+night")
    @Test fun recordingsDark() {
        home(Fixtures.ready())
        snap("home-dark")
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
        val olivia = Fixtures.recordings().first()
        home(Fixtures.ready().copy(playingName = olivia.name, playing = true, positionMs = 107_000, durationMs = 252_000))
        snap("home-playing")
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

    @Test fun callsLargeText() {
        home(Fixtures.ready().copy(homeTab = HomeTab.CALLS), fontScale = 2f)
        snap("calls-large-text")
    }

    @Config(qualifiers = "+night")
    @Test fun callsDark() {
        home(Fixtures.ready().copy(homeTab = HomeTab.CALLS))
        snap("calls-dark")
    }
}
