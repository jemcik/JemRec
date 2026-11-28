package com.jemcik.jemrec.recorder

import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.recorder.StartWithoutWifi.PortStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When Start without Wi-Fi's port is opened or closed - every change
 *  restarts adbd, and the recorder with it, so only the ones meant. */
class StartWithoutWifiTest {

    private companion object {
        /** JemRec's own number, as picked on one phone. */
        const val OWN = 62_817
    }

    private fun step(wanted: Boolean, set: Boolean?, ours: Boolean = false, failed: Boolean = false) =
        StartWithoutWifi.portStep(wanted, set, ours, failed)

    @Test fun switchedOnItOpensThePortOnce() {
        assertEquals(PortStep.OPEN, step(wanted = true, set = false))
        // Open already: by JemRec, or by a computer's `adb tcpip`.
        assertEquals(PortStep.LEAVE, step(wanted = true, set = true, ours = true))
        assertEquals(PortStep.LEAVE, step(wanted = true, set = true))
    }

    @Test fun anAskThatFailedIsNotRepeatedThisBoot() {
        // Each ask restarts the recorder; an adbd that does not keep the
        // port would have it restarted at every revive.
        assertEquals(PortStep.LEAVE, step(wanted = true, set = false, failed = true))
        // So is a close that left the port set: an adbd that keeps it would
        // have the recorder restarted at every revive too...
        assertEquals(
            PortStep.LEAVE,
            StartWithoutWifi.portStep(wanted = false, set = true, ours = true, failed = false, closeFailed = true),
        )
        // ...but a failed close says nothing about an open: switched on again
        // with the port gone, it is asked for.
        assertEquals(
            PortStep.OPEN,
            StartWithoutWifi.portStep(wanted = true, set = false, ours = true, failed = false, closeFailed = true),
        )
        // Nor a failed open about a close.
        assertEquals(PortStep.CLOSE, step(wanted = false, set = true, ours = true, failed = true))
    }

    @Test fun switchedOffItClosesOnlyThePortItOpened() {
        assertEquals(PortStep.CLOSE, step(wanted = false, set = true, ours = true))
        // A computer's port is the computer's.
        assertEquals(PortStep.LEAVE, step(wanted = false, set = true, ours = false))
        assertEquals(PortStep.LEAVE, step(wanted = false, set = false))
        // Closed already, by a computer's `adb usb`: nothing to restart for.
        assertEquals(PortStep.LEAVE, step(wanted = false, set = false, ours = true))
    }

    @Test fun aPropertyThatCannotBeReadDoesNotHideThePort() {
        // getprop prints the same empty line for a property never set and for
        // one this app may not read: neither may stop the open...
        assertEquals(PortStep.OPEN, step(wanted = true, set = null))
        // ...nor have it asked again once JemRec opened it this boot, every
        // ask being a restart of the recorder...
        assertEquals(PortStep.LEAVE, step(wanted = true, set = null, ours = true))
        // ...nor keep the one it opened from being closed.
        assertEquals(PortStep.CLOSE, step(wanted = false, set = null, ours = true))
    }

    @Test fun thePortIsNotClosedOverItself() {
        // On a Wi-Fi Android has not allowed Wireless debugging on, the
        // revive's connection is the port: closing it would leave no way back.
        assertNull(CaptureDaemon.portChangeOver(PortStep.CLOSE, connectedPort = OWN, classic = listOf(OWN, StartWithoutWifi.LEGACY_PORT)))
        // Over Wireless debugging's own port it is closed...
        assertEquals(PortStep.CLOSE, CaptureDaemon.portChangeOver(PortStep.CLOSE, connectedPort = 37_211, classic = listOf(OWN, StartWithoutWifi.LEGACY_PORT)))
        // ...and opening adds a way, over whichever.
        assertEquals(PortStep.OPEN, CaptureDaemon.portChangeOver(PortStep.OPEN, connectedPort = OWN, classic = listOf(OWN, StartWithoutWifi.LEGACY_PORT)))
        assertNull(CaptureDaemon.portChangeOver(null, connectedPort = 37_211, classic = listOf(OWN)))
    }

    @Test fun getpropsAnswerIsThePortOrUnknown() {
        // As the Honor printed it, 2026-10-27: after `adb tcpip 5555`, after
        // `adb usb`, and a property never set (an empty line - which is also
        // what a property the app may not read prints).
        assertEquals(5555, StartWithoutWifi.parsePort("5555\n"))
        assertEquals(0, StartWithoutWifi.parsePort("0\n"))
        assertNull(StartWithoutWifi.parsePort("\n"))
        assertNull(StartWithoutWifi.parsePort("not a number"))
    }

    @Test fun whoseThePortIsIsItsNumber() {
        fun ours(configured: Int?, own: Int? = OWN, armed: Boolean = false, legacy: Boolean = false) =
            StartWithoutWifi.isOurs(configured, own, armed, legacy)
        // JemRec's number set: its own, whatever the notes say - the app
        // killed after the ask, before anything was noted (1.10's review).
        assertTrue(ours(OWN))
        // A computer's `adb usb` then `adb tcpip 5555`, after JemRec's own
        // port in the same boot: the computer's, however fast (1.10's review).
        assertFalse(ours(5555, armed = true))
        // Tasker's ADB Wifi on its own number: Tasker's.
        assertFalse(ours(6_000, armed = true, legacy = true))
        // Closed, by anyone: nobody's.
        assertFalse(ours(0, armed = true, legacy = true))
        // A 5555 that 1.10 opened this boot stays JemRec's until the restart.
        assertTrue(ours(5555, legacy = true))
        assertTrue(ours(5555, own = null, legacy = true))
        // The property unreadable: what this boot asked for.
        assertTrue(ours(null, armed = true))
        assertTrue(ours(null, own = null, legacy = true))
        assertFalse(ours(null))
    }

    @Test fun switchedOnAPortSomeoneElseSetIsUsedNotMoved() {
        // adbd listens on one TCP port at a time: opening JemRec's would take
        // Tasker's or a computer's away.
        assertEquals(PortStep.LEAVE, step(wanted = true, set = true, ours = false))
        // Nor closed when switched off.
        assertEquals(PortStep.LEAVE, step(wanted = false, set = true, ours = false))
    }

    @Test fun theNumberIsPickedAboveTheEphemeralRangeAmongNoServices() {
        val picked = (1..2_000).map { StartWithoutWifi.pickPort { false } }
        assertTrue(picked.all { it in StartWithoutWifi.OWN_PORTS })
        // Above the phones' ephemeral range (32768-60999, measured), inside
        // IANA's dynamic range (49152-65535, RFC 6335): no registered service,
        // Syncthing's 22000 and the recorder's 28472 far below.
        assertTrue(StartWithoutWifi.OWN_PORTS.first > 60_999 && StartWithoutWifi.OWN_PORTS.first >= 49_152)
        assertEquals(65_535, StartWithoutWifi.OWN_PORTS.last)
        // Random, not one number for every phone.
        assertTrue(picked.toSet().size > 1_000)
    }

    @Test fun theNumberIsNotOneSomethingAnswersOn() {
        fun pick(vararg tries: Int, answering: Set<Int>): Int {
            val next = ArrayDeque(tries.toList())
            return StartWithoutWifi.pickPort(random = { next.removeFirst() }) { it in answering }
        }
        assertEquals(63_000, pick(62_000, 63_000, answering = setOf(62_000)))
        // Everything tried answering: the last one tried - what answers now
        // may be gone by the time adbd is asked.
        assertEquals(62_000, StartWithoutWifi.pickPort(random = { 62_000 }) { true })
    }

    @Test fun onlyAPortAdbdsPropertiesNameIsDialled() {
        fun ports(
            configured: Int?,
            persist: Int? = null,
            asked: Boolean = false,
            legacy: Boolean = false,
            emulator: Boolean = false,
        ) = PortChange.classicPorts(configured, persist, OWN, asked, legacy, emulator)
        // A port set: that one alone - adbd listens there and nowhere else.
        assertEquals(listOf(OWN), ports(OWN))
        assertEquals(listOf(6_000), ports(6_000))
        assertEquals(listOf(5555), ports(5555))
        assertEquals(listOf(6_000), ports(6_000, emulator = true))
        // None set: nothing on a phone, where adbd then has no TCP port; 5555
        // on an emulator, adbd's default there with no USB (daemon/main.cpp).
        assertEquals(emptyList<Int>(), ports(0))
        assertEquals(listOf(5555), ports(0, emulator = true))
        // Unreadable (empty after every restart until the first open): the
        // one JemRec asked for this boot, and 1.10's 5555 this boot - nothing
        // on a guess.
        assertEquals(emptyList<Int>(), ports(null))
        assertEquals(listOf(OWN), ports(null, asked = true))
        assertEquals(listOf(5555), ports(null, legacy = true))
        assertEquals(listOf(OWN, 5555), ports(null, asked = true, legacy = true))
        assertEquals(listOf(5555), ports(null, emulator = true))
        // Empty, with persist.adb.tcp.port set (some ROMs, rooted phones):
        // adbd's own fallback, and so the one dialled - first.
        assertEquals(listOf(5555), ports(null, persist = 5555))
        assertEquals(listOf(7_000, OWN), ports(null, persist = 7_000, asked = true))
        // A port set, or "0": persist is not adbd's then.
        assertEquals(listOf(OWN), ports(OWN, persist = 7_000))
        assertEquals(emptyList<Int>(), ports(0, persist = 7_000))
        // No number picked yet, nothing asked: nothing.
        assertEquals(
            emptyList<Int>(),
            PortChange.classicPorts(null, null, null, asked = true, legacy = false, defaultPort = false),
        )
    }
}
