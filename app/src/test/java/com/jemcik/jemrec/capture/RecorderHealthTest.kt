package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.CallLedger.Recorded
import com.jemcik.jemrec.capture.RecorderHealth.Problem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the recorder-down alert speaks, and what it says the cause is. */
class RecorderHealthTest {

    private val since = 1_791_544_738_000L

    @Test fun offWifiTheAnswerIsWifi() {
        assertEquals(Problem.NEEDS_WIFI, RecorderHealth.diagnose(onWifi = false, refusedInARow = 5))
    }

    @Test fun anAdbdThatKeepsTurningUsAwaySuggestsTheLostPairing() {
        assertEquals(Problem.CANNOT_START, RecorderHealth.diagnose(onWifi = true, refusedInARow = 1))
        assertEquals(
            Problem.CANNOT_CONNECT,
            RecorderHealth.diagnose(onWifi = true, refusedInARow = RecorderHealth.REFUSALS_FOR_PAIRING),
        )
    }

    @Test fun onWifiWithDebuggingOnAndNothingAdvertisedTheWifiIsNotAllowed() {
        // Android 17 emulator: is_trusted_network=false, tls_port=0.
        assertEquals(
            Problem.NETWORK_NOT_ALLOWED,
            RecorderHealth.diagnose(onWifi = true, refusedInARow = 0, unadvertised = true),
        )
    }

    @Test fun theWifiIsNotAllowedOnlyWithAdbdRunning() {
        val notAllowed = { refused: Boolean, on: Boolean, adbd: AdbdState.State, here: Boolean ->
            RecorderHealth.notAllowed(refusedHere = refused, switchOn = on, adbd = adbd, switchedOnHere = here)
        }
        // The emulator's untrusted Wi-Fi: adbd running, nothing advertised.
        assertTrue(notAllowed(false, true, AdbdState.State.RUNNING, false))
        // The Honor after a restart, 2026-10-28: switch on, adbd not running,
        // a Wi-Fi allowed long before. Not the Wi-Fi - even after a switch.
        assertFalse(notAllowed(false, true, AdbdState.State.STOPPED, false))
        assertFalse(notAllowed(false, true, AdbdState.State.STOPPED, true))
        // Unreadable: the Wi-Fi once a switch here has not started anything.
        assertFalse(notAllowed(false, true, AdbdState.State.UNKNOWN, false))
        assertTrue(notAllowed(false, true, AdbdState.State.UNKNOWN, true))
        // Refused here (Android 12 keeps the switch off) says it whatever else.
        assertTrue(notAllowed(true, false, AdbdState.State.STOPPED, false))
        // Switched off, with no refusal: nothing to allow.
        assertFalse(notAllowed(false, false, AdbdState.State.RUNNING, true))
    }

    @Test fun offWifiNothingIsAdvertisedAnywaySoThatSaysNothing() {
        assertEquals(Problem.NEEDS_WIFI, RecorderHealth.diagnose(onWifi = false, refusedInARow = 0, unadvertised = true))
    }

    @Test fun anAdbdThatAdvertisesAndRefusesIsAboutThePairingNotTheWifi() {
        // A refusal needs an advertisement first, so the two do not meet in
        // one revive; across revives, the refusals win.
        assertEquals(
            Problem.CANNOT_CONNECT,
            RecorderHealth.diagnose(onWifi = true, refusedInARow = RecorderHealth.REFUSALS_FOR_PAIRING, unadvertised = true),
        )
    }

    @Test fun aRecorderBackWithinTheGraceIsNeverAnnounced() {
        // A reboot on Wi-Fi: down for seconds, then up.
        assertFalse(RecorderHealth.shouldAlert(since + RecorderHealth.GRACE_MS - 1, since, null, Problem.CANNOT_START))
        assertTrue(RecorderHealth.shouldAlert(since + RecorderHealth.GRACE_MS, since, null, Problem.CANNOT_START))
    }

    @Test fun oneAlertPerCauseNotOneEveryRetry() {
        val later = since + RecorderHealth.GRACE_MS * 10
        assertFalse(RecorderHealth.shouldAlert(later, since, Problem.NEEDS_WIFI.name, Problem.NEEDS_WIFI))
        assertTrue(RecorderHealth.shouldAlert(later, since, Problem.NEEDS_WIFI.name, Problem.CANNOT_CONNECT))
    }
}

/** The ledger's entries survive a round trip, and rubbish does not stop it. */
class CallLedgerTest {

    @Test fun recordingsAndReportsRoundTrip() {
        val entries = setOf(
            CallLedger.encodeRecording(Recorded(1_791_544_738_000L, true, 95)),
            CallLedger.encodeRecording(Recorded(1_791_544_999_000L, false, 0)),
            "c:1791545000000",
        )
        val ledger = CallLedger.parse(entries)
        assertEquals(
            setOf(Recorded(1_791_544_738_000L, true, 95), Recorded(1_791_544_999_000L, false, 0)),
            ledger.recordings.toSet(),
        )
        assertEquals(setOf(1_791_545_000_000L), ledger.reported)
    }

    @Test fun skippedCallsAreKeptApartFromRecordings() {
        val ledger = CallLedger.parse(setOf("s:1791544738000:in:95", CallLedger.encodeRecording(Recorded(5, false, 1))))
        assertEquals(listOf(Recorded(1_791_544_738_000L, true, 95)), ledger.skipped)
        assertEquals(listOf(Recorded(5, false, 1)), ledger.recordings)
    }

    @Test fun unreadableEntriesAreDropped() {
        val ledger = CallLedger.parse(setOf("r:nope:in:5", "x:1", "c:", "r:1:in"))
        assertTrue(ledger.recordings.isEmpty())
        assertTrue(ledger.reported.isEmpty())
    }

    @Test fun lengthsReadTheWayTheListShowsThem() {
        assertEquals("45 s", AfterCall.length(45.9))
        assertEquals("3:21", AfterCall.length(201.0))
    }
}
