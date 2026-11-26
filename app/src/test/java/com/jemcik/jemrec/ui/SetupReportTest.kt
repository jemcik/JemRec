package com.jemcik.jemrec.ui

import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.adb.AdbdCheck
import com.jemcik.jemrec.adb.AdbdFinder
import com.jemcik.jemrec.adb.LoopbackProbe
import com.jemcik.jemrec.setup.SetupStep
import com.jemcik.jemrec.system.GlobalSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Setup's Copy a report (#140, whose report came empty): what a phone stuck
 * on Finish connecting would hand over, and nothing personal in it.
 */
class SetupReportTest {

    private val stuck = AdbTransport.Attempt(
        atMs = 1_000_000,
        outcome = AdbTransport.ConnectOutcome.NOT_FOUND,
        found = AdbdFinder.Found(
            null, null,
            listOf(AdbdFinder.Candidate(36_467, AdbdCheck.Answer.CLOSED)),
            LoopbackProbe.Result(null, 28_232, 1, 0, 1_219),
        ),
        port = 0,
        failure = null,
        legacy = "ConnectException: failed to connect to /127.0.0.1 (port 5555): ECONNREFUSED",
    )

    private fun report(attempts: List<AdbTransport.Attempt>, finishFailed: String? = null) = SetupReport.text(
        appVersion = "1.9",
        phone = "motorola motorola edge 50 neo",
        android = "Android 15 (API 35), build V1UI35H.11-27-5",
        step = SetupStep.NEEDS_CONNECTION,
        paired = true,
        usb = GlobalSettings.Reading.ON,
        wireless = true,
        onWifi = true,
        finishFailed = finishFailed,
        attempts = attempts,
        nowMs = 1_012_000,
    )

    @Test fun aPhoneStuckOnFinishConnecting() {
        assertEquals(
            """
            JemRec 1.9 - setup report
            Phone: motorola motorola edge 50 neo, Android 15 (API 35), build V1UI35H.11-27-5
            Step: NEEDS_CONNECTION, paired
            Switches: USB debugging on, Wireless debugging on; Wi-Fi on
            Connects, newest first:
            - 12 s ago: NOT_FOUND; mDNS gave 36467 (closed); probe: 28232 ports in 1219 ms, adbd not among them, 1 other listeners; port 5555: ConnectException: failed to connect to /127.0.0.1 (port 5555): ECONNREFUSED
            """.trimIndent(),
            report(listOf(stuck)),
        )
    }

    /** The same phone line as the call report (ThisPhone.line). */
    @Test fun itsPhoneLineIsTheCallReportsToo() {
        val callReport = com.jemcik.jemrec.capture.LastCallReport.text(
            "1.9", "motorola motorola edge 50 neo", "Android 15 (API 35), build V1UI35H.11-27-5",
            emptyList(), tracking = false, answering = false, recorder = null,
        )
        assertEquals(callReport.lines()[1], report(emptyList()).lines()[1])
    }

    @Test fun beforeAnyConnectAndAfterAFailedLastStep() {
        val text = report(emptyList(), finishFailed = "Android would not let JemRec change debugging settings.")
        assertEquals("Last step failed: Android would not let JemRec change debugging settings.", text.lines()[4])
        assertEquals("Connects: none yet", text.lines().last())
    }

    /** No address, no network name: the loopback's, and ports, only. */
    @Test fun nothingPersonal() {
        val text = report(listOf(stuck))
        assertFalse(text, Regex("""\b(?!127\.0\.0\.1)\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""").containsMatchIn(text))
        assertFalse(text, text.contains("adb-"))
    }
}
