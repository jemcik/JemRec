package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.StartWithoutWifi.PortStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** When Start without Wi-Fi's port is opened or closed - every change
 *  restarts adbd, and the recorder with it, so only the ones meant. */
class StartWithoutWifiTest {

    private fun step(wanted: Boolean, set: Boolean?, openedByUs: Boolean = false, failed: Boolean = false) =
        StartWithoutWifi.portStep(wanted, set, openedByUs, failed)

    @Test fun switchedOnItOpensThePortOnce() {
        assertEquals(PortStep.OPEN, step(wanted = true, set = false))
        // Open already: by JemRec, or by a computer's `adb tcpip`.
        assertEquals(PortStep.LEAVE, step(wanted = true, set = true, openedByUs = true))
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
            StartWithoutWifi.portStep(wanted = false, set = true, openedByUs = true, failed = false, closeFailed = true),
        )
        // ...but a failed close says nothing about an open: switched on again
        // with the port gone, it is asked for.
        assertEquals(
            PortStep.OPEN,
            StartWithoutWifi.portStep(wanted = true, set = false, openedByUs = true, failed = false, closeFailed = true),
        )
        // Nor a failed open about a close.
        assertEquals(PortStep.CLOSE, step(wanted = false, set = true, openedByUs = true, failed = true))
    }

    @Test fun switchedOffItClosesOnlyThePortItOpened() {
        assertEquals(PortStep.CLOSE, step(wanted = false, set = true, openedByUs = true))
        // A computer's port is the computer's.
        assertEquals(PortStep.LEAVE, step(wanted = false, set = true, openedByUs = false))
        assertEquals(PortStep.LEAVE, step(wanted = false, set = false))
        // Closed already, by a computer's `adb usb`: nothing to restart for.
        assertEquals(PortStep.LEAVE, step(wanted = false, set = false, openedByUs = true))
    }

    @Test fun aPropertyThatCannotBeReadDoesNotHideThePort() {
        // getprop prints the same empty line for a property never set and for
        // one this app may not read: neither may stop the open...
        assertEquals(PortStep.OPEN, step(wanted = true, set = null))
        // ...nor have it asked again once JemRec opened it this boot, every
        // ask being a restart of the recorder...
        assertEquals(PortStep.LEAVE, step(wanted = true, set = null, openedByUs = true))
        // ...nor keep the one it opened from being closed.
        assertEquals(PortStep.CLOSE, step(wanted = false, set = null, openedByUs = true))
    }

    @Test fun thePortIsNotClosedOverItself() {
        // On a Wi-Fi Android has not allowed Wireless debugging on, the
        // revive's connection is the port: closing it would leave no way back.
        assertNull(CaptureDaemon.portChangeOver(PortStep.CLOSE, connectedPort = StartWithoutWifi.PORT))
        // Over Wireless debugging's own port it is closed...
        assertEquals(PortStep.CLOSE, CaptureDaemon.portChangeOver(PortStep.CLOSE, connectedPort = 37_211))
        // ...and opening adds a way, over whichever.
        assertEquals(PortStep.OPEN, CaptureDaemon.portChangeOver(PortStep.OPEN, connectedPort = StartWithoutWifi.PORT))
        assertNull(CaptureDaemon.portChangeOver(null, connectedPort = 37_211))
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
}
