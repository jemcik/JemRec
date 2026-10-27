package com.jemcik.jemrec.capture

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * What wakes the app when the phone switches USB debugging off (see
 * UsbDebuggingWatch). Measured on the Honor: the switch-off kills the recorder,
 * and this trigger is the only thing that tells the app.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class UsbDebuggingWatchTest {

    @Test fun itWatchesUsbDebuggingAndNothingElse() {
        val triggers = UsbDebuggingWatch.constraints().contentUriTriggers
        assertEquals(1, triggers.size)
        val trigger = triggers.single()
        assertEquals(Uri.parse("content://settings/global/adb_enabled"), trigger.uri)
        // Not Wireless debugging: Android writes that off at every Wi-Fi drop,
        // and with USB debugging on it stops nothing.
        assertFalse(trigger.isTriggeredForDescendants)
    }

    @Test fun itRunsAsSoonAsJobSchedulerAllows() {
        val c = UsbDebuggingWatch.constraints()
        // JobScheduler's floors (JobStatus.MIN_TRIGGER_UPDATE_DELAY and
        // MIN_TRIGGER_MAX_DELAY): anything lower is raised to them.
        assertEquals(500L, c.contentTriggerUpdateDelayMillis)
        assertEquals(1_000L, c.contentTriggerMaxDelayMillis)
        // Unset, they would be 10 s and 2 min - the time a call rings through.
        assertTrue(c.contentTriggerUpdateDelayMillis < 10_000L)
        assertTrue(c.contentTriggerMaxDelayMillis < 120_000L)
    }
}
