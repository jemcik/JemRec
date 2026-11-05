package com.jemcik.jemrec.recorder

import com.jemcik.jemrec.adb.AdbTransport.ConnectOutcome
import com.jemcik.jemrec.recorder.Revive.Request
import com.jemcik.jemrec.system.AdbdState.State
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A revive cancelled half way - a worker WorkManager stops, a screen left -
 * between Wireless debugging's off and its on (Revive's off and on, on a
 * FakePhone): Wireless debugging must not be left off by it, nor until the
 * next revive.
 */
class ReviveCancelTest {

    @Test fun aReviveCancelledBetweenTheOffAndTheOnLeavesWirelessDebuggingOn() = runTest {
        // adbd stopped with both switches on: off and on, after two seconds
        // for an adbd starting by itself - the off at 2 s, the on at 3 s.
        val phone = FakePhone().apply {
            adbd[0] = State.STOPPED
            connects += ConnectOutcome.CONNECTED
        }
        val revive = launch { Revive.reviveLocked(phone, null, Request.ROUTINE, Revive.Found()) }
        advanceTimeBy(2_500)
        assertEquals(listOf("probe", "adb_wifi_enabled=0"), phone.did)

        revive.cancel()
        revive.join()

        // The on is written all the same, and what it means noted; the
        // revive stops at its next wait, before any connect.
        assertEquals(
            listOf("probe", "adb_wifi_enabled=0", "adb_wifi_enabled=1", "forget cable", "note switched on"),
            phone.did,
        )
        assertTrue(phone.wireless)
    }
}
