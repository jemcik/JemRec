package com.jemcik.jemrec.recorder

import android.app.Notification
import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The recorder-down alert as posted: silent in a call the way AndroidX's
 *  setSilent makes one - a group whose summary alone may alert, and there
 *  is none - and plain otherwise (review round 12: no test held either). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class RecorderHealthPostTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun posted(): Notification =
        shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications.single()

    @Test fun silentInACallIsAGroupThatOnlyItsSummaryMayAlert() {
        RecorderHealth.post(context, RecorderHealth.Problem.NEEDS_WIFI, silent = true)
        val n = posted()
        assertEquals("jemrec.health.silent", n.group)
        assertEquals(Notification.GROUP_ALERT_SUMMARY, n.groupAlertBehavior)
    }

    /** Said silently in a call: posted again with its sound once the call
     *  is over - not left to a revive that may wait for a network (review
     *  round 13) - and marked said. Swiped away in the call, it is said. */
    @Test fun theCallsEndPostsASilentAlertAgainAloud() {
        val prefs = com.jemcik.jemrec.Prefs.of(context)
        prefs.edit().putString("recorder_down_alerted", "NEEDS_WIFI|silent").commit()
        RecorderHealth.callOver(context)
        assertNull("posted plain", posted().group)
        assertEquals("NEEDS_WIFI", prefs.getString("recorder_down_alerted", null))
        prefs.edit().putString("recorder_down_alerted", "NEEDS_WIFI|silent").commit()
        RecorderHealth.dismissed(context)
        assertEquals("NEEDS_WIFI", prefs.getString("recorder_down_alerted", null))
        // Seen by opening JemRec - its tap among the ways (round 14).
        prefs.edit().putString("recorder_down_alerted", "NEEDS_WIFI|silent").commit()
        RecorderHealth.seen(context)
        assertEquals("NEEDS_WIFI", prefs.getString("recorder_down_alerted", null))
    }

    @Test fun otherwiseItIsPlain() {
        RecorderHealth.post(context, RecorderHealth.Problem.NEEDS_WIFI, silent = false)
        assertNull(posted().group)
    }
}
