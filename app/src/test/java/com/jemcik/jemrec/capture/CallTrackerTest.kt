package com.jemcik.jemrec.capture

import android.telecom.Call
import com.jemcik.jemrec.calls.CallTracking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Telecom's list as CallTracker keeps it: its last call removed ends the call
 * up for the next start's rule (CallMonitorService.telecomOutAtMs; review
 * round 29) - and only its last. Set at every removal, a held call's other
 * call hanging up "ended" the held one: the recorder lost and revived in
 * it, its joined start was taken for a new call, the call split in two and
 * its Record answer dropped (review round 30).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class CallTrackerTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var tracker: CallTracker
    private val made = mutableListOf<Call>()

    @Before fun setUp() {
        // Off: the rest of a call's add and removal - the rules, the answer -
        // is not this test's; the list and its end come before that check.
        CallTracking.setEnabled(context, false)
        CallMonitorService.telecomOutAtMs = 0L
        tracker = Robolectric.buildService(CallTracker::class.java).create().get()
    }

    @After fun tearDown() {
        made.forEach { LiveCalls.removed(it) }
        CallMonitorService.telecomOutAtMs = 0L
    }

    /** android.telecom.Call has no public constructor: Mockito's inline
     *  mock maker stands in, as for Uri. */
    private fun call(id: Long): Call {
        val details = mock(Call.Details::class.java)
        `when`(details.creationTimeMillis).thenReturn(id)
        `when`(details.callDirection).thenReturn(Call.Details.DIRECTION_INCOMING)
        return mock(Call::class.java).also {
            `when`(it.details).thenReturn(details)
            made += it
        }
    }

    @Test fun onlyTheLastCallRemovedEndsTheCallUp() {
        val held = call(1_000L)
        val other = call(2_000L)
        tracker.onCallAdded(held)
        tracker.onCallAdded(other)
        tracker.onCallRemoved(other)
        assertEquals("a call still in the list", 0L, CallMonitorService.telecomOutAtMs)
        tracker.onCallRemoved(held)
        assertTrue("the last one gone", CallMonitorService.telecomOutAtMs > 0L)
    }
}
