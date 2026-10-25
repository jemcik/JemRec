package com.jemcik.jemrec.capture

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * The one recipe the "Recording problems" notifications share (AfterCall.
 * problem): the recorder down, a call not recorded, calls missed, the phone
 * stopping JemRec. Pinned here because four notifications change with it.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class ProblemNotificationTest {

    private val context = RuntimeEnvironment.getApplication()
    private val open = PendingIntent.getActivity(context, 1, Intent(), PendingIntent.FLAG_IMMUTABLE)

    @Test fun aProblemHasItsShapeOnItsChannel() {
        val n = AfterCall.problem(context, "Call not recorded", "Why, at length.", open).build()
        assertEquals(AfterCall.PROBLEM_CHANNEL_ID, n.channelId)
        assertEquals("Call not recorded", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Why, at length.", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("Why, at length.", n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertEquals(open, n.contentIntent)
        assertTrue("cleared once tapped", n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(0, n.actions?.size ?: 0)
    }

    @Test fun itsOneButtonGoesWhereTheTapGoes() {
        val n = AfterCall.problem(context, "Title", "Text", open, button = "App launch").build()
        assertEquals(1, n.actions.size)
        assertEquals("App launch", n.actions[0].title.toString())
        assertEquals(open, n.actions[0].actionIntent)
    }

    @Test fun aFoldedLineCanDifferFromTheWholeText() {
        // MissedCalls: the first call folded, every call expanded.
        val n = AfterCall.problem(context, "2 calls were not recorded", "Anna, 10:00\nBob, 11:00", open,
            collapsed = "Anna, 10:00").build()
        assertEquals("Anna, 10:00", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("Anna, 10:00\nBob, 11:00", n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
    }
}
