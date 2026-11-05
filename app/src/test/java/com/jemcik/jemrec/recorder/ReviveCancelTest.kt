package com.jemcik.jemrec.recorder

import com.jemcik.jemrec.adb.AdbTransport.ConnectOutcome
import com.jemcik.jemrec.recorder.Revive.Request
import com.jemcik.jemrec.recorder.StartWithoutWifi.PortStep
import com.jemcik.jemrec.system.AdbdState.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A revive cancelled half way - a worker WorkManager stops, a screen left -
 * on a FakePhone: each of its changes whose second half comes later gets
 * that second half all the same, for the revive runs to its end
 * (Revive.reviveLocked).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReviveCancelTest {

    private val wifiOff = "adb_wifi_enabled=0"
    private val wifiOn = "adb_wifi_enabled=1"
    private val started = listOf("start", "push", "stand down", "after start")

    private fun CoroutineScope.revive(phone: FakePhone): Job =
        launch { Revive.reviveLocked(phone, null, Request.ROUTINE, Revive.Found()) }

    @Test fun aReviveCancelledBetweenTheOffAndTheOnGoesOnToTheEnd() = runTest {
        // adbd stopped with both switches on: off and on, after two seconds
        // for an adbd starting by itself - the off at 2 s, the on at 3 s.
        val phone = FakePhone().apply {
            adbd[0] = State.STOPPED
            connects += ConnectOutcome.CONNECTED
        }
        val revive = revive(phone)
        advanceTimeBy(2_500)
        assertEquals(listOf("probe", wifiOff), phone.did)

        revive.cancel()
        revive.join()

        // Wireless debugging on again, and the recorder started.
        assertEquals(
            listOf("probe", wifiOff, wifiOn, "forget cable", "note switched on", "autoConnect 5000") + started,
            phone.did,
        )
        assertTrue(phone.wireless)
    }

    @Test fun aReviveCancelledAfterAdbdRestartedOnThePortStillNotesItAsJemRecs() = runTest {
        // Start without Wi-Fi switched on: adbd restarted on 5555, then a
        // second before the port is read back and noted.
        val phone = FakePhone().apply {
            step = PortStep.OPEN
            connects += listOf(ConnectOutcome.CONNECTED, ConnectOutcome.CONNECTED)
        }
        val revive = revive(phone)
        advanceTimeBy(500)
        assertEquals(listOf("probe", "autoConnect 5000", "restart tcpip:5555", "close"), phone.did)

        revive.cancel()
        revive.join()

        // Noted, so switching it off - or Start fresh - closes it again.
        assertEquals(
            listOf("probe", "autoConnect 5000", "restart tcpip:5555", "close", "note opened true", "connect 5555") + started,
            phone.did,
        )
    }

    @Test fun aReviveCancelledWhileTheRecorderStartsStillTurnsWirelessDebuggingOffAgain() = runTest {
        // JemRec off with Developer options hidden: Wireless debugging switched
        // on to start the recorder, and off again once it is up.
        val phone = FakePhone().apply {
            wireless = false
            switchedOff = true
            startMs = 3_000
            connects += ConnectOutcome.CONNECTED
        }
        val revive = revive(phone)
        // Switched on, four seconds for adbd, connected: starting.
        advanceTimeBy(5_000)
        assertEquals(listOf("probe", wifiOn, "note switched on", "autoConnect 5000"), phone.did)

        revive.cancel()
        revive.join()

        // Started, and then what follows a start, its off again included.
        assertEquals(started, phone.did.takeLast(4))
    }
}
