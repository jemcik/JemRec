package com.jemcik.jemrec.capture

import android.Manifest
import android.app.NotificationManager
import androidx.work.ListenableWorker.Result
import androidx.work.WorkInfo
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.capture.HeldWork.Companion.NOW
import com.jemcik.jemrec.capture.HeldWork.Companion.PERIODIC
import com.jemcik.jemrec.capture.HeldWork.Companion.USB_WATCH
import com.jemcik.jemrec.capture.HeldWork.Companion.WIFI
import com.jemcik.jemrec.recorder.DebugSwitchesOff
import com.jemcik.jemrec.recorder.RecorderHealth
import com.jemcik.jemrec.recorder.RecorderSwitch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * A revive, a start, the keep-alive and the USB watch with a recorder that
 * answers (FakeRecorder), as they are today, on a phone set up and off
 * Wi-Fi: what each does when the recorder is up and current, up and out of
 * date, or up and unable to say its build - and what it asks it. Nothing
 * here connects to adbd; ReviveTest has the recorder down.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class RecorderUpTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var work: HeldWork
    private var recorder: FakeRecorder? = null

    @Before fun aPhoneSetUpOffWifiWithJemRecOn() {
        TestPhone.switchesOn(context)
        TestPhone.offWifi(context)
        DebugSwitchesOff.forget(context)
        RecorderSwitch.set(context, true)
        TestPhone.setUp(context)
        TestPhone.clearReviveLog(context)
        work = HeldWork(context)
    }

    @After fun closeAll() {
        recorder?.close()
        work.close()
    }

    private fun up(build: String? = FakeRecorder.bundledBuild(context)) =
        FakeRecorder(context, build).also { recorder = it }

    private fun heard() = recorder!!.heard.map { it.key }

    @Test fun aCurrentRecorderIsLeftAsItIsAndTheNotesOfItsAbsenceGo() = runBlocking {
        val build = FakeRecorder.bundledBuild(context)
        up()
        // A shield drifted off, the recorder-down alert up, a cable's note
        // and a switch-on noted on this connection, from before.
        TestPhone.put(context, TestPhone.USB, 0)
        Prefs.of(context).edit()
            .putLong("recorder_down_since", System.currentTimeMillis() - 3 * 60_000L)
            .putBoolean("cable_stopped_adbd", true)
            .putString("wireless_debugging_switched_on", "noted")
            .apply()
        RecorderHealth.onRevive(context, alive = false, connect = AdbTransport.ConnectOutcome.NONE)
        val alerts = shadowOf(context.getSystemService(NotificationManager::class.java))
        assertNotNull(alerts.getNotification(34))
        TestPhone.clearReviveLog(context)

        assertTrue(CaptureDaemon.revive(context))

        // armShield first, with the recorder up too; one ping, and nothing
        // pushed to it, no session closed: it returned at the current check.
        assertEquals(1, TestPhone.get(context, TestPhone.USB))
        assertEquals(listOf("P"), heard())
        assertEquals(
            listOf("revive: start", "revive: recorder answers, build $build", "revive: recorder up (connect NONE)"),
            TestPhone.reviveLog(context),
        )
        // Up is told to RecorderHealth, which clears the alert; the cable's
        // and the switch-on's notes go.
        assertNull(RecorderHealth.cause(context))
        assertNull(alerts.getNotification(34))
        assertFalse(Prefs.of(context).contains("cable_stopped_adbd"))
        assertFalse(Prefs.of(context).contains("wireless_debugging_switched_on"))
    }

    @Test fun anOutOfDateRecorderOffWifiIsStillARecorderUp() = runBlocking {
        up(build = "00000000")

        assertTrue(CaptureDaemon.revive(context))

        // Down the path a dead recorder takes, as far as Wi-Fi allows: no
        // session to replace it over, and up is up.
        assertEquals(
            listOf(
                "revive: start", "revive: recorder answers, build 00000000", "revive: not on Wi-Fi",
                "revive: recorder up (connect NONE)",
            ),
            TestPhone.reviveLog(context),
        )
        assertEquals(listOf("P"), heard())
    }

    @Test fun aRecorderThatSaysNoBuildIsTakenForCurrent() = runBlocking {
        up(build = null)
        assertTrue(CaptureDaemon.revive(context))
        assertEquals(
            listOf("revive: start", "revive: recorder answers, build null", "revive: recorder up (connect NONE)"),
            TestPhone.reviveLog(context),
        )
        assertTrue(CaptureDaemon.isCurrent(context, CaptureDaemon.Answer(2, null)))
        assertTrue(CaptureDaemon.isCurrent(context, CaptureDaemon.Answer(2, FakeRecorder.bundledBuild(context))))
        assertFalse(CaptureDaemon.isCurrent(context, CaptureDaemon.Answer(2, "00000000")))
    }

    @Test fun aRecorderThatCannotReadItsJarIsTakenForOutOfDate() = runBlocking {
        // The recorder answers "unknown" when it cannot hash its own jar
        // (Main.jarBuild), and says the app takes that for current.
        up(build = "unknown")
        assertTrue(CaptureDaemon.revive(context))
        // Pins today's behaviour, which looks like a bug: the app takes only
        // no build at all for current, so a recorder that says "unknown" goes
        // down the out-of-date path at every revive, and is replaced at each
        // one that has a session (to be fixed in its own commit)
        assertTrue("revive: not on Wi-Fi" in TestPhone.reviveLog(context))
        assertFalse(CaptureDaemon.isCurrent(context, CaptureDaemon.Answer(2, "unknown")))
    }

    @Test fun aStartWithACurrentRecorderNeedsNoSession() = runBlocking {
        up()
        assertTrue(CaptureDaemon.ensureRunning(context).isSuccess)
        assertEquals(listOf("start: old recorder answers"), TestPhone.reviveLog(context))
        assertEquals(listOf("P"), heard())
    }

    @Test fun aStartWithAnOutOfDateRecorderAndNoSessionLeavesItForLater() = runBlocking {
        up(build = "00000000")
        // Not asked to quit: there is no session to start the next one over.
        assertTrue(CaptureDaemon.ensureRunning(context).isSuccess)
        assertEquals(listOf("start: old recorder answers"), TestPhone.reviveLog(context))
        assertEquals(listOf("P"), heard())
        assertTrue(recorder!!.isOpen)
    }

    @Test fun allowThisWifiWithoutThePermissionAsksNothing() = runBlocking {
        up()
        assertFalse(CaptureDaemon.askToAllowNetwork(context))
        assertEquals(emptyList<String>(), TestPhone.reviveLog(context))
        assertEquals(emptyList<String>(), heard())
    }

    @Test fun allowThisWifiWithThePermissionRevivesAndSaysYesWhateverItFound() = runBlocking {
        // Down and off Wi-Fi: the revive cannot bring it back, and the answer
        // is still yes - it was asked.
        shadowOf(context).grantPermissions(Manifest.permission.WRITE_SECURE_SETTINGS)
        assertTrue(CaptureDaemon.askToAllowNetwork(context))
        val log = TestPhone.reviveLog(context)
        assertEquals("revive: start", log.first())
        assertEquals("revive: recorder down (connect NONE)", log.last())
    }

    private fun keepAlive(): Result = runBlocking {
        TestListenableWorkerBuilder<RecorderKeepAlive>(context)
            .setInputData(workDataOf("for_wifi" to false))
            .setRunAttemptCount(0)
            .build()
            .doWork()
    }

    @Test fun aKeepAliveRunWithTheRecorderUpCollectsThenLooksForMissedCalls() {
        up()
        // The missed-call check reads the call log, and asks nothing without it.
        shadowOf(context).grantPermissions(Manifest.permission.READ_CALL_LOG)
        assertEquals(Result.success(), keepAlive())
        // The revive's ping, then the reconcile's list, then the missed-call
        // check's (MissedCalls.check asks what the recorder still holds).
        assertEquals(listOf("P", "L", "L"), heard())
        val log = TestPhone.reviveLog(context)
        assertEquals(listOf("keepalive: run (attempt 1)", "revive: start"), log.take(2))
        assertEquals("revive: recorder up (connect NONE)", log.last())
        // Off Wi-Fi the Wi-Fi watch is armed all the same, and the day's
        // auto-delete runs; no other run is started.
        assertEquals(1, TestPhone.wifiWatches(context).size)
        assertTrue(Prefs.of(context).getLong("auto_delete_last_run", 0L) > 0L)
        listOf(PERIODIC, NOW, WIFI).forEach { assertEquals(it, emptyList<WorkInfo>(), work.unique(it)) }
        assertEquals(1, work.unique(USB_WATCH).size)
    }

    @Test fun aUsbWatchRunWithTheRecorderUpNeedsNothingMore() {
        up()
        val result = runBlocking { TestListenableWorkerBuilder<UsbDebuggingWatch>(context).build().doWork() }
        assertEquals(Result.success(), result)
        assertEquals(listOf("P"), heard())
        assertEquals(
            listOf("usbwatch: USB debugging changed", "revive: start"),
            TestPhone.reviveLog(context).take(2),
        )
        // No fallback: no kick, no Wi-Fi watch.
        assertEquals(emptyList<WorkInfo>(), work.unique(NOW))
        assertTrue(TestPhone.wifiWatches(context).isEmpty())
    }
}
