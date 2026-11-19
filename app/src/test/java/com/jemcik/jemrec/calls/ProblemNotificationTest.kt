package com.jemcik.jemrec.calls

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

    /** "Call not recorded" goes once the file it was about is saved after
     *  all - a later collection - and not for another's (review round 15). */
    @Test fun aFailureToldGoesOnceItsFileIsSaved() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        AfterCall.notRecordedAbout(context, "f.dat", retried = true)
        AfterCall.postNotRecorded(context, "Is the storage full?")
        AfterCall.savedAfterAll(context, "g.dat")
        assertEquals(1, org.robolectric.Shadows.shadowOf(manager).allNotifications.size)
        AfterCall.savedAfterAll(context, "f.dat")
        assertEquals(0, org.robolectric.Shadows.shadowOf(manager).allNotifications.size)
    }

    /** The alert gone - swiped, tapped - its files are forgotten: a call
     *  with no audio, never to be saved, swiped away, held every later
     *  alert up after its own file was saved (review round 17). */
    @Test fun anAlertGoneForgetsItsFiles() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        AfterCall.notRecordedAbout(context, "e.dat", retried = true)
        AfterCall.postNotRecorded(context, "Is the storage full?")
        manager.cancel(31) // swiped: "Call not recorded"
        AfterCall.notRecordedAbout(context, "x.dat", retried = true)
        AfterCall.postNotRecorded(context, "Is the storage full?")
        AfterCall.savedAfterAll(context, "x.dat")
        assertEquals(0, org.robolectric.Shadows.shadowOf(manager).allNotifications.size)
    }

    /** One never to be saved told over it: the alert says what no save
     *  mends, and stays when the files before are saved (review round 17). */
    @Test fun aFailureNoSaveMendsKeepsTheAlertUp() {
        val manager = context.getSystemService(android.app.NotificationManager::class.java)
        AfterCall.notRecordedAbout(context, "f.dat", retried = true)
        AfterCall.postNotRecorded(context, "Is the storage full?")
        AfterCall.notRecordedAbout(context, "e.dat", retried = false)
        AfterCall.postNotRecorded(context, AfterCall.NO_AUDIO)
        AfterCall.savedAfterAll(context, "f.dat")
        assertEquals(1, org.robolectric.Shadows.shadowOf(manager).allNotifications.size)
        // And the files before it are no longer what holds it up: one
        // failure told over it goes once that one is saved.
        AfterCall.notRecordedAbout(context, "x.dat", retried = true)
        AfterCall.postNotRecorded(context, "Is the storage full?")
        AfterCall.savedAfterAll(context, "x.dat")
        assertEquals(0, org.robolectric.Shadows.shadowOf(manager).allNotifications.size)
    }

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

    /** The button does the fix, the tap opens JemRec: Android's guide,
     *  "Don't include text actions that duplicate the behavior of tapping on
     *  the notification body" - every button did until 1.2's reviews. */
    @Test fun itsOneButtonDoesWhatTheTapDoesNot() {
        val fix = PendingIntent.getActivity(context, 2, Intent("fix"), PendingIntent.FLAG_IMMUTABLE)
        val n = AfterCall.problem(context, "Title", "Text", open, button = "App launch" to fix).build()
        assertEquals(1, n.actions.size)
        assertEquals("App launch", n.actions[0].title.toString())
        assertEquals(fix, n.actions[0].actionIntent)
        assertEquals(open, n.contentIntent)
    }

    @Test fun aFoldedLineCanDifferFromTheWholeText() {
        // MissedCalls: the first call folded, every call expanded.
        val n = AfterCall.problem(context, "2 calls were not recorded", "Anna, 10:00\nBob, 11:00", open,
            collapsed = "Anna, 10:00").build()
        assertEquals("Anna, 10:00", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals("Anna, 10:00\nBob, 11:00", n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
    }
}
