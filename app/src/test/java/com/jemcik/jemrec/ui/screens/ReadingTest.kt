package com.jemcik.jemrec.ui.screens

import android.net.Uri
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.jemcik.jemrec.answering.AnsweringMachine
import com.jemcik.jemrec.calls.CallHistory
import com.jemcik.jemrec.calls.CallRules
import com.jemcik.jemrec.capture.TestPhone
import com.jemcik.jemrec.recordings.FileNames
import com.jemcik.jemrec.recordings.Recording
import com.jemcik.jemrec.recordings.Recordings
import com.jemcik.jemrec.recordings.Waveforms
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
import java.io.File
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

    /** The version Settings shows is read as a copied report reads it
     *  (appVersion), so the two never disagree. */
    @Test fun aRefreshReadsTheVersionAReportGives() {
        val vm = viewModel(UiState())
        refreshed(vm)
        val version = com.jemcik.jemrec.ui.appVersion(RuntimeEnvironment.getApplication())
        assertEquals(version, vm.state.value.appVersion)
        assertTrue(version, version.isNotEmpty())
    }

    /** The switches the screen watches as they are saved come from what
     *  is saved as the reading lands, whatever the screen held: a reading
     *  under way when one was saved put back what it had read before. */
    @Test fun aReadingShowsTheWatchedSwitchesAsSaved() {
        com.jemcik.jemrec.calls.VoipCallNotes.setEnabled(RuntimeEnvironment.getApplication(), true)
        com.jemcik.jemrec.capture.DebugNotificationCleaner.choose(RuntimeEnvironment.getApplication(), true)
        com.jemcik.jemrec.recorder.RecorderSwitch.set(RuntimeEnvironment.getApplication(), false)
        // Off with all debugging: USB debugging among what went (DebugSwitchesOff).
        com.jemcik.jemrec.Prefs.of(RuntimeEnvironment.getApplication()).edit()
            .putStringSet("debug_switched_off", setOf("USB_DEBUGGING", "WIRELESS_DEBUGGING")).commit()
        val vm = viewModel(UiState(voipNames = false, debugNotificationHidden = false, enabled = true, debuggingOff = false))
        refreshed(vm)
        assertTrue(vm.state.value.voipNames)
        assertTrue(vm.state.value.debugNotificationHidden)
        assertFalse(vm.state.value.enabled)
        assertTrue(vm.state.value.debuggingOff)
        // And taken as saved, whatever the reading had read.
        val stale = vm.state.value.copy(voipNames = false, debugNotificationHidden = false, enabled = true, debuggingOff = false)
        val landed = com.jemcik.jemrec.ui.asSaved(RuntimeEnvironment.getApplication(), stale)
        assertTrue(landed.voipNames && landed.debugNotificationHidden && !landed.enabled && landed.debuggingOff)
    }

    /** Every choice Settings saves from its rows is taken as saved when a
     *  reading lands: one made while the reading waited on the recorder was
     *  put back (the 1.1 review). */
    @Test fun aReadingLandsWithTheChoicesAsSaved() {
        val app = RuntimeEnvironment.getApplication()
        com.jemcik.jemrec.playback.PlayThroughSetting.set(app, com.jemcik.jemrec.playback.PlayThrough.EARPIECE)
        com.jemcik.jemrec.calls.AfterCall.setEnabled(app, false)
        com.jemcik.jemrec.recordings.FileNames.setFormat(app, com.jemcik.jemrec.recordings.FileNames.Format.HONOR)
        com.jemcik.jemrec.calls.CallRules.setOtherNumbers(app, false)
        com.jemcik.jemrec.calls.CallTracking.setEnabled(app, false)
        com.jemcik.jemrec.recordings.Retention.setMaxAgeDays(app, 30)
        com.jemcik.jemrec.recordings.QualitySetting.set(app, com.jemcik.jemrec.recordings.RecordingQuality.SMALLEST)
        com.jemcik.jemrec.playback.BothEarsSetting.set(app, false)
        val stale = UiState()
        val landed = com.jemcik.jemrec.ui.asSaved(app, stale)
        assertEquals(com.jemcik.jemrec.playback.PlayThrough.EARPIECE, landed.playThrough)
        assertFalse(landed.afterCallNotice)
        assertEquals(com.jemcik.jemrec.recordings.FileNames.Format.HONOR, landed.fileNames)
        assertFalse(landed.ruleOtherNumbers)
        assertFalse(landed.callTracking)
        assertEquals(30, landed.autoDeleteAfterDays)
        assertEquals(com.jemcik.jemrec.recordings.RecordingQuality.SMALLEST, landed.quality)
        assertFalse(landed.bothEars)
    }

    /** The tile's answer, the tip seen and the call-log offer dismissed are
     *  saved from the screen without a reading of their own: taken as saved
     *  when one lands. */
    @Test fun aReadingLandsWithTheScreensOwnAnswersAsSaved() {
        val app = RuntimeEnvironment.getApplication()
        com.jemcik.jemrec.ui.RecorderTile.setAdded(app, true)
        com.jemcik.jemrec.ui.SelectHint.markSeen(app)
        com.jemcik.jemrec.calls.CallLogLookup.dismissBanner(app)
        val stale = UiState(tileAdded = false, selectHintSeen = false, offerCallLog = true)
        val landed = com.jemcik.jemrec.ui.asSaved(app, stale)
        assertTrue(landed.tileAdded)
        assertTrue(landed.selectHintSeen)
        assertFalse(landed.offerCallLog)
    }

    /** A phone with an earpiece, a proximity sensor and the lock that
     *  darkens the screen by it offers all of "Play recordings", read as
     *  saved. */
    @Test fun aRefreshReadsWhereRecordingsPlayAndWhatThePhoneCanDo() {
        val app = RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.media.AudioManager::class.java)).setOutputDevices(
            listOf(
                org.robolectric.shadows.AudioDeviceInfoBuilder.newBuilder()
                    .setType(android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE).build(),
            ),
        )
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.hardware.SensorManager::class.java))
            .addSensor(org.robolectric.shadows.ShadowSensor.newInstance(android.hardware.Sensor.TYPE_PROXIMITY))
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.os.PowerManager::class.java))
            .setIsWakeLockLevelSupported(android.os.PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, true)
        com.jemcik.jemrec.playback.PlayThroughSetting.set(app, com.jemcik.jemrec.playback.PlayThrough.EARPIECE)
        val vm = viewModel(UiState(earpieceAvailable = false, raiseAvailable = false))
        refreshed(vm)
        assertTrue(vm.state.value.earpieceAvailable)
        assertTrue(vm.state.value.raiseAvailable)
        assertEquals(com.jemcik.jemrec.playback.PlayThrough.EARPIECE, vm.state.value.playThrough)
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
     * answering machine is on in the preferences, though the screen's copy
     * of it is not read yet (AnswerUi's default, off). Settings asks all the
     * same - the question and the switching off go by one rule - and
     * nothing is off until the answer.
     */
    @Test fun trackingOffBeforeTheFirstRefreshAsksAboutTheMachine() {
        AnsweringMachine.setEnabled(app, true)
        val shown = Fixtures.ready().copy(screen = Screen.SETTINGS)
        val vm = viewModel(shown)
        show { AppScaffold(shown, vm) }
        compose.onNodeWithText("Exact call tracking").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Turn off the answering machine too?").assertExists()
        assertTrue(vm.state.value.callTracking)
        assertTrue(AnsweringMachine.enabled(app))

        compose.onNodeWithText("Turn both off").performClick()
        compose.waitForIdle()
        assertFalse(vm.state.value.callTracking)
        assertFalse(AnsweringMachine.enabled(app))
    }

    /** After the list arrives, the waveform fill-in goes over it: each
     *  recording is asked whether it has its levels. */
    @Test fun aRefreshStartsTheFillInOverTheList() {
        val asked = java.util.Collections.synchronizedSet(mutableSetOf<String>())
        Waveforms.override = { name -> asked += name; Fixtures.waveform(name.hashCode().toLong()) }
        Recordings.override = { listOf(olivia, marcus) }
        val vm = viewModel(UiState())
        refreshed(vm)
        assertEquals(setOf(olivia.name, marcus.name), asked.toSet())
    }

    /** Switched to Ask first with its question's channel off, home says so
     *  at once - no reading comes for a mode - and back in Automatic, it
     *  goes. */
    @Test fun switchingToAskFirstSaysAtOnceThatItsQuestionIsOff() {
        com.jemcik.jemrec.calls.RecordPrompt.ensureChannel(app)
        val manager = app.getSystemService(android.app.NotificationManager::class.java)
        manager.getNotificationChannel(com.jemcik.jemrec.calls.RecordPrompt.CHANNEL_ID).importance =
            android.app.NotificationManager.IMPORTANCE_NONE
        Recordings.override = { emptyList() }
        // What a reading found Android saying: Ask first's question off.
        val switches = com.jemcik.jemrec.calls.NotificationHealth.switches(app)
        val vm = viewModel(UiState(automatic = true, notificationSwitches = switches))
        vm.setAutomatic(false)
        assertEquals(com.jemcik.jemrec.calls.NotificationHealth.Off.ASK_FIRST, vm.state.value.notificationsOff)
        vm.setAutomatic(true)
        assertEquals(null, vm.state.value.notificationsOff)
        settle(vm)
    }

    /** A reading asks Android itself, off the main thread, what the notices
     *  are made of: the Battery setting and the notifications' switches.
     *  Taken out of the reading, every test of the notices passed - they
     *  were all given a state by hand (review, 2026-11-18). */
    @Test fun aReadingAsksAndroidForTheBatteryAndTheNotifications() {
        Recordings.override = { listOf(olivia) }
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.ActivityManager::class.java))
            .setBackgroundRestricted(true)
        org.robolectric.Shadows.shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
            .setNotificationsEnabled(false)
        val vm = viewModel(UiState())
        refreshed(vm)
        assertEquals(true, vm.state.value.batteryRestricted)
        assertEquals(true, vm.state.value.batteryNotice)
        assertEquals(com.jemcik.jemrec.calls.NotificationHealth.Off.ALL, vm.state.value.notificationsOff)
    }

    /** Not now on a notice shows the next one down at once, not at the
     *  next reading (review round 11). */
    @Test fun notNowShowsTheNextNoticeDownAtOnce() {
        Recordings.override = { emptyList() }
        com.jemcik.jemrec.capture.RecordingMode.set(app, com.jemcik.jemrec.capture.RecordingMode.ON_DEMAND)
        val switches = com.jemcik.jemrec.calls.NotificationHealth.Switches(all = false, askFirst = false, problems = true, askFirstQuiet = true)
        val quiet = com.jemcik.jemrec.calls.NotificationHealth.Off.ASK_FIRST_QUIET
        val vm = viewModel(UiState(automatic = false, notificationSwitches = switches, notificationsOff = quiet))
        vm.dismissNotificationsOff(quiet)
        assertEquals(com.jemcik.jemrec.calls.NotificationHealth.Off.PROBLEMS, vm.state.value.notificationsOff)
        settle(vm)
        com.jemcik.jemrec.capture.RecordingMode.set(app, com.jemcik.jemrec.capture.RecordingMode.AUTOMATIC)
    }

    /** The battery card put away in Automatic comes back at once in Ask
     *  first, where it cannot be put away. */
    @Test fun switchingToAskFirstBringsTheBatteryCardBack() {
        Recordings.override = { emptyList() }
        com.jemcik.jemrec.system.BatteryHealth.dismiss(app)
        val vm = viewModel(UiState(automatic = true, batteryRestricted = true, batteryNotice = false))
        vm.setAutomatic(false)
        assertEquals(true, vm.state.value.batteryNotice)
        vm.setAutomatic(true)
        assertEquals(false, vm.state.value.batteryNotice)
        settle(vm)
    }

    /** What the notice says is decided as a reading lands, from what the
     *  reading found Android saying and the mode then: a mode switched while
     *  a reading was out is not undone by its landing (review, 2026-11-18). */
    @Test fun theNoticeIsDecidedAsTheReadingLands() {
        val switches = com.jemcik.jemrec.calls.NotificationHealth.Switches(all = false, askFirst = true, problems = false)
        val read = UiState(notificationSwitches = switches)
        com.jemcik.jemrec.capture.RecordingMode.set(app, com.jemcik.jemrec.capture.RecordingMode.ON_DEMAND)
        assertEquals(com.jemcik.jemrec.calls.NotificationHealth.Off.ASK_FIRST, com.jemcik.jemrec.ui.asSaved(app, read).notificationsOff)
        com.jemcik.jemrec.capture.RecordingMode.set(app, com.jemcik.jemrec.capture.RecordingMode.AUTOMATIC)
        assertEquals(null, com.jemcik.jemrec.ui.asSaved(app, read).notificationsOff)
    }

    /** Granted while the log is being read: the list read before it says
     *  nothing of the calls - the permission is asked before the load, so
     *  the tab stays unread for the next reading (review, 2026-11-18). */
    @Test fun aGrantDuringTheLoadIsNotTakenForTheLog() {
        Recordings.override = { listOf(olivia) }
        val shadow = org.robolectric.Shadows.shadowOf(app)
        shadow.denyPermissions(android.Manifest.permission.READ_CALL_LOG)
        CallHistory.override = {
            shadow.grantPermissions(android.Manifest.permission.READ_CALL_LOG)
            emptyList()
        }
        val vm = viewModel(UiState(homeTab = HomeTab.CALLS, callsUnread = true))
        refreshed(vm)
        assertEquals(true, vm.state.value.callsUnread)
    }

    /** The Calls tab is unread until a reading reads the call log itself:
     *  without the permission its list is an empty one that says nothing of
     *  the calls, and taken as read, a grant from the tab said "No calls in
     *  the call log yet." for as long as the next reading took (review,
     *  2026-11-18). */
    @Test fun theCallsAreReadOnlyFromTheCallLogItself() {
        Recordings.override = { listOf(olivia) }
        CallHistory.override = { emptyList() }
        val shadow = org.robolectric.Shadows.shadowOf(app)
        shadow.denyPermissions(android.Manifest.permission.READ_CALL_LOG)
        val vm = viewModel(UiState(homeTab = HomeTab.CALLS, callsUnread = true))
        refreshed(vm)
        assertEquals("no call log, still unread", true, vm.state.value.callsUnread)
        shadow.grantPermissions(android.Manifest.permission.READ_CALL_LOG)
        refreshed(vm)
        assertEquals("read from the call log", false, vm.state.value.callsUnread)
    }

    /** The Battery setting Restricted, the same: from what the reading
     *  found and the mode as it lands, and asked of Android with the rest. */
    @Test fun theBatteryNoticeIsDecidedAsTheReadingLands() {
        val read = UiState(batteryRestricted = true)
        com.jemcik.jemrec.system.BatteryHealth.dismiss(app)
        com.jemcik.jemrec.capture.RecordingMode.set(app, com.jemcik.jemrec.capture.RecordingMode.AUTOMATIC)
        assertEquals(false, com.jemcik.jemrec.ui.asSaved(app, read).batteryNotice)
        com.jemcik.jemrec.capture.RecordingMode.set(app, com.jemcik.jemrec.capture.RecordingMode.ON_DEMAND)
        assertEquals(true, com.jemcik.jemrec.ui.asSaved(app, read).batteryNotice)
        assertEquals(false, com.jemcik.jemrec.ui.asSaved(app, UiState()).batteryNotice)
    }

    /**
     * What asks for a fresh reading of everything, and what does not. Each
     * of these reads the list once: the Calls tab coming up, Multitrack
     * chosen, the rule of whose calls, a keep mark, keeping the selected,
     * the call log's and the recordings' permission answers, Android's
     * answer about a delete, a rule's contact added or taken off, a folder
     * chosen. Another look, the other numbers' rule, the file names, the
     * note after each call and the recording mode read nothing.
     */
    @Test fun whatAsksForAFreshReadingAndWhatDoesNot() {
        val reads = AtomicInteger()
        Recordings.override = { reads.incrementAndGet(); listOf(olivia, marcus) }
        CallHistory.override = { emptyList() }
        val vm = viewModel(UiState(recordings = listOf(olivia, marcus), selecting = true, selected = setOf(marcus.name)))
        try {
            vm.setLook(Look.DECK)
            vm.setRuleOtherNumbers(false)
            vm.setFileNames(FileNames.Format.DATE_AND_NUMBER)
            vm.setAfterCallNotice(false)
            vm.setAutomatic(false)
            settle(vm)
            assertEquals("nothing read", 0, reads.get())

            // Before the tab switch, which ends the selection.
            vm.keepSelected()
            vm.showTab(HomeTab.CALLS)
            vm.setLook(Look.MULTITRACK)
            vm.setRuleContacts(CallRules.Contacts.ALL_BUT)
            vm.toggleKept(olivia)
            vm.onCallLogResult()
            vm.onAudioAccessResult()
            vm.onDeleteConsentResult(false)
            vm.addRuleContact(Uri.parse("content://com.android.contacts/contacts/404"))
            vm.removeRuleContact("nobody")
            val picked = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ACalls")
            com.jemcik.jemrec.recordings.PickerGrants.offer(picked)
            vm.onFolderChosen(picked)
            settle(vm)
            assertEquals(11, reads.get())
        } finally {
            Looks.set(app, LookChoice())
        }
    }

    /** Each action that changes what is listed, or what the recorder is,
     *  reads everything again once it is done. */
    @Test fun anActionThatChangesWhatIsListedReadsItAgain() {
        TestPhone.offWifi(app)
        val calls = File(app.filesDir, "calls").apply { mkdirs() }
        val inTrash = File(calls, ".trashed-1795000000-${olivia.name}").apply { writeBytes(ByteArray(64)) }
        val trashed = olivia.copy(uri = Uri.fromFile(inTrash), filePath = inTrash.path)
        val reads = AtomicInteger()
        Recordings.override = { reads.incrementAndGet(); emptyList() }
        val vm = viewModel(UiState(screen = Screen.SETTINGS, trashed = listOf(trashed)))
        for ((what, action) in listOf<Pair<String, () -> Unit>>(
            "restore all" to { vm.restoreAll() },
            "auto-delete" to { vm.setAutoDelete(afterDays = 30) },
            "the standard folder" to { vm.useDefaultFolder() },
            "start recorder" to { vm.bootstrapDaemon() },
            "allow this Wi-Fi" to { vm.allowThisWifi() },
            "start without Wi-Fi" to { vm.setStartWithoutWifi(true) },
        )) {
            val before = reads.get()
            action()
            settle(vm)
            assertEquals(what, before + 1, reads.get())
        }
    }

}
