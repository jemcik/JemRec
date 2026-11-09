package com.jemcik.jemrec.ui.screens

import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.google.common.util.concurrent.SettableFuture
import com.jemcik.jemrec.DeepLink
import com.jemcik.jemrec.recordings.Recording
import com.jemcik.jemrec.recordings.Recordings
import com.jemcik.jemrec.recordings.Waveforms
import com.jemcik.jemrec.setup.SetupStep
import com.jemcik.jemrec.ui.HomeTab
import com.jemcik.jemrec.ui.RecordingsNarrowing
import com.jemcik.jemrec.ui.Screen
import com.jemcik.jemrec.ui.UiState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.concurrent.atomic.AtomicInteger

/**
 * A notification's button, acted on by the view model (MainViewModel.open):
 * play or share a call, run the self-test, allow this Wi-Fi, pair again, or
 * open the phone's background setting. Pinned before the view model's work
 * moves into holders of its own (the 1.0 restructuring).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class DeepLinksTest : ScreenTest() {

    private val app get() = RuntimeEnvironment.getApplication()

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

    @Before fun inventedWaveforms() {
        Waveforms.override = { name -> Fixtures.waveform(name.hashCode().toLong()) }
    }

    @After fun noStandIns() {
        Recordings.override = null
        Waveforms.override = null
    }

    /** Away from the row, its list narrowed and a selection under way. */
    private val elsewhere = UiState(
        step = SetupStep.READY,
        screen = Screen.SETTINGS,
        homeTab = HomeTab.CALLS,
        recordingsNarrowing = RecordingsNarrowing(kept = true),
        selecting = true,
        selected = setOf(marcus.name),
    )

    /** Spent at once, before anything it does - not again at a rotation. */
    @Test fun aLinkIsSpentAsItIsTaken() {
        Recordings.override = { listOf(olivia) }
        val vm = viewModel()
        DeepLink.offer(app, DeepLink.intent(app, DeepLink.Kind.PLAY, olivia.name))
        assertEquals(DeepLink.Kind.PLAY, DeepLink.pending.value?.kind)
        vm.open(DeepLink.pending.value!!)
        assertNull(DeepLink.pending.value)
        settle(vm)
    }

    /** Play: the list read first, then its row brought into view - home,
     *  Recordings, nothing narrowing it, no selection - then it plays. */
    @Test fun playReadsTheListShowsTheRowAndPlaysIt() {
        val reads = AtomicInteger()
        Recordings.override = { reads.incrementAndGet(); listOf(olivia, marcus) }
        val vm = viewModel(elsewhere)
        vm.open(DeepLink.Link(DeepLink.Kind.PLAY, olivia.name))
        settle(vm)
        val s = vm.state.value
        assertEquals(1, reads.get())
        assertEquals(Screen.HOME, s.screen)
        assertEquals(HomeTab.RECORDINGS, s.homeTab)
        assertEquals(RecordingsNarrowing(), s.recordingsNarrowing)
        assertFalse(s.selecting)
        assertEquals(emptySet<String>(), s.selected)
        assertEquals(olivia.name, s.playingName)
        assertEquals(Fixtures.waveform(olivia.name.hashCode().toLong()), s.waveform)
    }

    /** One already playing is left playing: Play is not a pause. Reaching
     *  for the session is what a play does first. */
    @Test fun playOfTheOnePlayingLeavesItPlaying() {
        Recordings.override = { listOf(olivia, marcus) }
        val connects = AtomicInteger()
        val vm = viewModel(
            UiState(recordings = listOf(olivia, marcus), playingName = olivia.name, playing = true, durationMs = 60_000),
            connectSession = { connects.incrementAndGet(); SettableFuture.create() },
        )
        vm.open(DeepLink.Link(DeepLink.Kind.PLAY, olivia.name))
        settle(vm)
        assertEquals("not touched", 0, connects.get())
        assertTrue(vm.state.value.playing)

        vm.open(DeepLink.Link(DeepLink.Kind.PLAY, marcus.name))
        settle(vm)
        assertEquals(1, connects.get())
        assertEquals(marcus.name, vm.state.value.playingName)
    }

    /** Share: the same reading and showing, then Share's question. */
    @Test fun shareReadsTheListShowsTheRowAndAsksHow() {
        Recordings.override = { listOf(olivia, marcus) }
        val vm = viewModel(elsewhere)
        vm.open(DeepLink.Link(DeepLink.Kind.SHARE, olivia.name))
        settle(vm)
        val s = vm.state.value
        assertEquals(Screen.HOME, s.screen)
        assertEquals(HomeTab.RECORDINGS, s.homeTab)
        assertFalse(s.selecting)
        assertSame(olivia, s.shareChoice)
        assertNull(s.playingName)
    }

    /** The self-test: Diagnostics at once; the test once the screen is
     *  free - the opening's own check holds busy as the link arrives. */
    @Test fun theSelfTestOpensDiagnosticsAndWaitsForTheScreen() {
        val vm = viewModel(UiState(step = SetupStep.READY, screen = Screen.SETTINGS))
        val busy = HeldBusy(emptyList())
        busy.hold(vm)
        vm.open(DeepLink.Link(DeepLink.Kind.SELF_TEST, null))
        awaitMain("Diagnostics") { vm.state.value.screen == Screen.DIAGNOSTICS }
        assertEquals("not while busy", 0L, vm.state.value.selfTestShown)
        busy.release()
        settle(vm)
        assertTrue(vm.state.value.selfTestShown > 0L)
    }

    /** Allow this Wi-Fi: home at once, Android asked once the screen is
     *  free. Here Android cannot be asked (no secure settings), and says so. */
    @Test fun allowThisWifiGoesHomeAndWaitsForTheScreen() {
        val vm = viewModel(UiState(step = SetupStep.READY, screen = Screen.SETTINGS))
        val busy = HeldBusy(emptyList())
        busy.hold(vm)
        vm.open(DeepLink.Link(DeepLink.Kind.ALLOW_WIFI, null))
        awaitMain("home") { vm.state.value.screen == Screen.HOME }
        assertFalse("not asked while busy", vm.state.value.log.contains("could not switch Wireless debugging"))
        busy.release()
        settle(vm)
        assertTrue(vm.state.value.log, vm.state.value.log.contains("could not switch Wireless debugging"))
    }

    /** A plain tap - a notification without a button, the tile - opens the
     *  recordings list, wherever JemRec was left: until 0.30 each opened a
     *  fresh window, on that list, and since there is one window it came
     *  forward on Theme, with no header to say why. */
    @Test fun aPlainTapOpensTheRecordingsList() {
        Recordings.override = { emptyList() }
        val vm = viewModel(
            UiState(
                step = SetupStep.READY, screen = Screen.THEME, homeTab = HomeTab.CALLS,
                recordingsNarrowing = RecordingsNarrowing(kept = true), selecting = true, selected = setOf("a.ogg"),
            ),
        )
        vm.open(DeepLink.Link(DeepLink.Kind.HOME, null))
        awaitMain("the list") { vm.state.value.screen == Screen.HOME }
        val s = vm.state.value
        assertEquals(HomeTab.RECORDINGS, s.homeTab)
        // Nothing narrowing it, nothing selected: the media notification's
        // row is on it.
        assertEquals(RecordingsNarrowing(), s.recordingsNarrowing)
        assertFalse(s.selecting)
    }

    /** A link lets a question left open go: one that changed the screen left
     *  "Start fresh?" or "Delete all for good?" to come back unasked on the
     *  next visit - one reflex tap deleting for good. */
    @Test fun aLinkLetsAQuestionLeftOpenGo() {
        Recordings.override = { emptyList() }
        val vm = viewModel(UiState(step = SetupStep.READY, screen = Screen.SETTINGS, confirmingReset = true, confirmingEmptyTrash = true))
        vm.open(DeepLink.Link(DeepLink.Kind.HOME, null))
        awaitMain("the list") { vm.state.value.screen == Screen.HOME }
        assertFalse(vm.state.value.confirmingReset)
        assertFalse(vm.state.value.confirmingEmptyTrash)
    }

    /** Pair again: home, on the pairing step's way back. */
    @Test fun pairAgainGoesHomeAsLost() {
        Recordings.override = { emptyList() }
        val vm = viewModel(UiState(step = SetupStep.READY, screen = Screen.SETTINGS, pairingRefused = true))
        vm.open(DeepLink.Link(DeepLink.Kind.PAIR_AGAIN, null))
        awaitMain("home") { vm.state.value.screen == Screen.HOME }
        assertTrue(vm.state.value.pairingLost)
        assertFalse(vm.state.value.pairingRefused)
        settle(vm)
    }

    /** The background warning's button: the warning goes, and the phone's
     *  own screen opens - here, with no vendor screen, JemRec's app page. */
    @Test fun backgroundSettingsTakesTheWarningAwayAndOpensTheScreen() {
        val manager = app.getSystemService(NotificationManager::class.java)
        manager.notify(35, Notification.Builder(app, "x").setSmallIcon(android.R.drawable.ic_dialog_info).build())
        val vm = viewModel()
        vm.open(DeepLink.Link(DeepLink.Kind.BACKGROUND_SETTINGS, null))
        settle(vm)
        assertTrue(manager.activeNotifications.none { it.id == 35 })
        val opened: Intent = shadowOf(app).nextStartedActivity
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, opened.action)
        assertEquals(app.packageName, opened.data?.schemeSpecificPart)
    }
}
