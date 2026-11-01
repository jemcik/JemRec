package com.jemcik.jemrec.ui.screens

import android.net.Uri
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.jemcik.jemrec.capture.AnsweringMachine
import com.jemcik.jemrec.capture.CallHistory
import com.jemcik.jemrec.capture.Recording
import com.jemcik.jemrec.capture.Recordings
import com.jemcik.jemrec.capture.Waveforms
import com.jemcik.jemrec.ui.AnswerUi
import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.HomeTab
import com.jemcik.jemrec.ui.MainViewModel
import com.jemcik.jemrec.ui.Screen
import com.jemcik.jemrec.ui.UiState
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookChoice
import com.jemcik.jemrec.ui.theme.Looks
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * What a refresh reads, and what it does with it once read - pinned before
 * MainViewModel's reading moves into a holder of its own (the 1.0
 * restructuring). Through the real refresh: the list comes from
 * Recordings.override and the call log from CallHistory.override, the seams
 * the debug build's demo list fills; everything else is read as Robolectric's
 * phone has it.
 *
 * No recorder answers here, so each refresh pays for the five pings that
 * call it down: 2.42-2.49 s a refresh, measured on a Mac under Robolectric
 * (2026-11-01) - which is why these tests read as few times as they can.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class ReadingTest : ScreenTest() {

    private val app get() = RuntimeEnvironment.getApplication()

    // A playing recording missing its waveform gets one at a refresh, and
    // the fill-in works out every row's: invented here, as the screenshots'
    // are - the recordings below are not files to decode.
    @Before fun inventedWaveforms() {
        Waveforms.override = { name -> Fixtures.waveform(name.hashCode().toLong()) }
    }

    @After fun noStandIns() {
        Recordings.override = null
        CallHistory.override = null
        Waveforms.override = null
    }

    private fun recording(stamp: String, caller: String) = Recording(
        name = "${stamp}_in.ogg",
        uri = Uri.parse("jemrec-test://$stamp"),
        whenLabel = "",
        incoming = true,
        durationLabel = "1:00",
        sizeLabel = "1.0 MB",
        caller = caller,
    )

    private val olivia = recording("20261021_160200", "Olivia Bennett")
    private val marcus = recording("20261021_152400", "Marcus Chen")
    private val priya = recording("20261020_141600", "Priya Raman")

    private fun refreshed(vm: MainViewModel) {
        awaitJob(vm.refresh(), "a refresh")
        // What the refresh started after it was applied - the waveform of
        // the recording playing, the fill-in - is part of what it did.
        settle(vm)
    }

    /** The list on screen is the one the refresh read - and with no
     *  recorder answering, the reading says so. */
    @Test fun aRefreshFillsTheRecordings() {
        Recordings.override = { listOf(olivia, marcus) }
        val vm = viewModel(UiState(daemonRunning = true))
        refreshed(vm)
        assertEquals(listOf(olivia, marcus), vm.state.value.recordings)
        assertFalse("no recorder answers here", vm.state.value.daemonRunning)
        // The stand-in list has no trash beside it.
        assertEquals(emptyList<Recording>(), vm.state.value.trashed)
    }

    /**
     * The call log is read only while the Calls tab shows; a refresh on the
     * Recordings tab leaves the calls read last as they were, rather than
     * blanking them.
     */
    @Test fun onTheRecordingsTabARefreshKeepsTheLastCallsRead() {
        val before = Fixtures.calls().take(2)
        val after = Fixtures.calls().drop(2)
        val reads = AtomicInteger()
        Recordings.override = { listOf(olivia) }
        CallHistory.override = { reads.incrementAndGet(); after }
        val vm = viewModel(UiState(homeTab = HomeTab.RECORDINGS, calls = before))

        refreshed(vm)
        assertEquals("not read on the Recordings tab", 0, reads.get())
        assertEquals(before, vm.state.value.calls)

        // Arriving at Calls reads it.
        vm.showTab(HomeTab.CALLS)
        settle(vm)
        assertEquals(1, reads.get())
        assertEquals(after, vm.state.value.calls)

        // Back on Recordings, the next refresh keeps them.
        vm.showTab(HomeTab.RECORDINGS)
        refreshed(vm)
        assertEquals(1, reads.get())
        assertEquals(after, vm.state.value.calls)
    }

    /** A recording gone from the list leaves the selection with it - it
     *  would still be counted and deleted - and selection mode stays on. */
    @Test fun aRefreshPrunesTheSelectionToTheRowsStillShown() {
        Recordings.override = { listOf(olivia, priya) }
        val vm = viewModel(
            UiState(recordings = listOf(olivia, marcus, priya), selecting = true, selected = setOf(olivia.name, marcus.name)),
        )
        refreshed(vm)
        assertEquals(setOf(olivia.name), vm.state.value.selected)
        assertTrue(vm.state.value.selecting)
    }

    /** Deleted from its notification or auto-deleted, the recording playing
     *  stops at the next refresh: a deleted call does not play on. No media
     *  session is reached - ScreenTest's never connects. */
    @Test fun aRecordingThatVanishedStopsPlaying() {
        Recordings.override = { listOf(olivia) }
        val vm = viewModel(
            UiState(
                recordings = listOf(olivia, marcus),
                playingName = marcus.name,
                playing = true,
                positionMs = 60_000,
                durationMs = 120_000,
                waveform = Fixtures.waveform(),
            ),
        )
        refreshed(vm)
        val s = vm.state.value
        assertNull(s.playingName)
        assertFalse(s.playing)
        assertEquals(0, s.positionMs)
        assertEquals(0, s.durationMs)
        assertNull(s.waveform)
    }

    /** ...and one still there plays on, where it was, and gets the waveform
     *  it was missing - one that played on while the screen was away. */
    @Test fun aRecordingStillThereKeepsPlayingAndGetsItsWaveform() {
        Recordings.override = { listOf(olivia, marcus) }
        val vm = viewModel(
            UiState(recordings = listOf(olivia, marcus), playingName = marcus.name, playing = true, positionMs = 60_000, durationMs = 120_000),
        )
        refreshed(vm)
        val s = vm.state.value
        assertEquals(marcus.name, s.playingName)
        assertTrue(s.playing)
        assertEquals(60_000, s.positionMs)
        assertEquals(Waveforms.override!!(marcus.name), s.waveform)
    }

    /**
     * A refresh reloads the answering machine's state from the preferences -
     * except a seeded one, which a test gave: nothing is then read over it
     * (AnswerController.seeded).
     */
    @Test fun aRefreshReloadsTheAnsweringMachineUnlessItWasSeeded() {
        Recordings.override = { emptyList() }
        AnsweringMachine.setEnabled(app, true)
        AnsweringMachine.setLongest(app, 120)

        val read = viewModel(UiState())
        assertFalse("not read before the first refresh", read.answer.state.value.enabled)
        refreshed(read)
        assertTrue(read.answer.state.value.enabled)
        assertEquals(120, read.answer.state.value.longest)

        val seeded = AnswerUi(enabled = false, longest = 60, greetingSeconds = 6.0)
        val given = viewModel(UiState(), seeded)
        refreshed(given)
        assertEquals(seeded, given.answer.state.value)
    }

    /**
     * Refreshes overlap - a resume, a save announced, a keep toggled - and
     * one begun earlier that finishes later is dropped: it put back a list
     * from before the other, and a recording just saved vanished until the
     * next resume.
     */
    @Test fun anOlderRefreshFinishingLastIsDropped() {
        val firstIn = CountDownLatch(1)
        val letFirstGo = CountDownLatch(1)
        val reads = AtomicInteger()
        Recordings.override = {
            if (reads.incrementAndGet() == 1) {
                firstIn.countDown()
                letFirstGo.await(30, TimeUnit.SECONDS)
                listOf(olivia)
            } else {
                listOf(olivia, marcus)
            }
        }
        val vm = viewModel(UiState())
        val first = vm.refresh()
        awaitMain("the first refresh to be reading") { firstIn.count == 0L }
        val second = vm.refresh()
        awaitJob(second, "the second refresh")
        assertEquals(listOf(olivia, marcus), vm.state.value.recordings)

        letFirstGo.countDown()
        awaitJob(first, "the first refresh")
        assertEquals("the older reading is not applied", listOf(olivia, marcus), vm.state.value.recordings)
    }

    /** Multitrack's caller colours are given at the reading, only while it
     *  is the look: a look that has none keeps none. */
    @Test fun callerColoursAreReadOnlyInMultitrack() {
        Recordings.override = { listOf(olivia, marcus) }
        val vm = viewModel(UiState())
        refreshed(vm)
        assertEquals(emptyMap<String, Int>(), vm.state.value.callerColours)
        try {
            Looks.set(app, LookChoice(Look.MULTITRACK))
            refreshed(vm)
            assertEquals(2, vm.state.value.callerColours.size)
        } finally {
            Looks.set(app, LookChoice())
        }
    }

    /**
     * Exact call tracking switched off before the first refresh: the
     * answering machine is on in the preferences, but the screen's copy of
     * it is not read yet (AnswerUi's default, off), so Settings does not ask
     * - and the view model turns the machine off anyway, as it acts on the
     * preference too.
     */
    @Test fun trackingOffBeforeTheFirstRefreshTurnsTheMachineOffUnasked() {
        AnsweringMachine.setEnabled(app, true)
        val shown = Fixtures.ready().copy(screen = Screen.SETTINGS)
        val vm = viewModel(shown)
        show { AppScaffold(shown, vm) }
        compose.onNodeWithText("Exact call tracking").performScrollTo().performClick()
        compose.waitForIdle()
        // Pins today's behaviour, which looks like a bug: the dialog asks only
        // when AnswerUi.enabled (SettingsScreen's tracking row), the action
        // turns the machine off on AnswerUi.enabled || AnsweringMachine.enabled
        // (MainViewModel.setCallTracking), so before the first refresh it goes
        // off without the question (to be fixed in its own commit)
        compose.onNodeWithText("Turn off the answering machine too?").assertDoesNotExist()
        assertFalse(vm.state.value.callTracking)
        assertFalse(AnsweringMachine.enabled(app))
    }
}
