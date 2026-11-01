package com.jemcik.jemrec.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import com.jemcik.jemrec.DeepLink
import com.jemcik.jemrec.MainActivity
import com.jemcik.jemrec.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * What AfterCall.show posts once a call is saved, branch by branch: the
 * everyday notice, the silent recording (a problem), and a message - which
 * channel, title, buttons, timeout and id each gets, and which of them the
 * after-call switch turns off. The decode (AudioCheck) is not run here: the
 * verdict is given, as saved() would have worked it out.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class AfterCallNotificationTest {

    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val start = 1_795_000_000_000L
    private val twelveHours = 12L * 60 * 60 * 1000

    private fun call(
        name: String = "2026-11-01 10-00-00 in.ogg",
        seconds: Double = 65.4,
        incoming: Boolean = true,
        caller: String? = "Anna",
        verdict: AudioCheck.Verdict = AudioCheck.Verdict.FINE,
        message: Boolean = false,
    ) = AfterCall.Call(
        name, Uri.parse("content://media/external/audio/media/7"), null, seconds, incoming, start, caller, verdict, message,
    )

    private fun posted(id: Int): Notification? = shadowOf(manager).getNotification(id)
    private fun Notification.title() = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
    private fun Notification.text() = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
    private fun Notification.buttons() = actions.map { it.title.toString() }
    private fun Notification.button(label: String): PendingIntent = actions.single { it.title.toString() == label }.actionIntent
    private fun messageId(name: String) = 3400 + (name.hashCode() and 0xffff)

    /** The guarded link DeepLink makes for that kind and name, extra for extra. */
    @Suppress("DEPRECATION")
    private fun assertSameLink(expected: Intent, actual: Intent) {
        assertEquals(expected.action, actual.action)
        val keys = expected.extras!!.keySet()
        assertEquals(keys, actual.extras!!.keySet())
        keys.forEach { assertEquals(it, expected.extras!!.get(it), actual.extras!!.get(it)) }
    }

    @Test fun anOrdinaryCallIsAnnouncedOnItsQuietChannel() {
        assertTrue(AfterCall.show(context, call()))
        val n = posted(33)!!
        assertEquals("jemrec.aftercall.v2", n.channelId)
        assertEquals("Anna", n.title())
        assertEquals("Recorded, 1:05", n.text())
        assertEquals(listOf("Play", "Share", "Delete"), n.buttons())
        assertEquals(twelveHours, n.timeoutAfter)
        assertEquals(start, n.`when`)
        assertEquals(android.R.drawable.ic_btn_speak_now, n.smallIcon.resId)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertTrue(n.flags and Notification.FLAG_ONLY_ALERT_ONCE != 0)
        // The caller's name is not for the lock screen.
        assertEquals(Notification.VISIBILITY_PRIVATE, n.visibility)
        assertEquals("Call recorded", n.publicVersion.title())
    }

    @Test fun eachButtonNamesItsOwnRecording() {
        val c = call()
        AfterCall.show(context, c)
        val n = posted(33)!!
        // The recording rides in the identifier: PendingIntents are told
        // apart without their extras, so one name in the extras alone made
        // every notification's Play the newest call's.
        fun opens(pending: PendingIntent, request: Int, kind: DeepLink.Kind) {
            val shadow = shadowOf(pending)
            assertTrue(shadow.isActivityIntent)
            assertEquals(request, shadow.requestCode)
            assertEquals(MainActivity::class.java.name, shadow.savedIntent.component?.className)
            assertEquals(c.name, shadow.savedIntent.identifier)
            assertSameLink(DeepLink.intent(context, kind, c.name), shadow.savedIntent)
        }
        opens(n.contentIntent, 3300, DeepLink.Kind.PLAY)
        opens(n.button("Play"), 3301, DeepLink.Kind.PLAY)
        opens(n.button("Share"), 3302, DeepLink.Kind.SHARE)
        val delete = shadowOf(n.button("Delete"))
        assertTrue(delete.isBroadcastIntent)
        assertEquals(AfterCallReceiver::class.java.name, delete.savedIntent.component?.className)
        assertEquals(AfterCall.ACTION_DELETE_ASK, delete.savedIntent.action)
        assertEquals(3300 + AfterCall.ACTION_DELETE_ASK.hashCode(), delete.requestCode)
        assertEquals(c.name, delete.savedIntent.identifier)
        assertEquals(c, AfterCall.Call.from(delete.savedIntent))
    }

    @Test fun withNoCallerItSaysWhichWayAndHowLong() {
        AfterCall.show(context, call(caller = null, incoming = true, seconds = 42.9))
        assertEquals("Incoming call", posted(33)!!.title())
        assertEquals("Recorded, 42 s", posted(33)!!.text())
        AfterCall.show(context, call(caller = null, incoming = false, seconds = 3600.0))
        assertEquals("Outgoing call", posted(33)!!.title())
        assertEquals("Recorded, 60:00", posted(33)!!.text())
    }

    @Test fun theNewestCallReplacesTheLast() {
        AfterCall.show(context, call(name = "a.ogg", caller = "Anna"))
        AfterCall.show(context, call(name = "b.ogg", caller = "Bob"))
        assertEquals("Bob", posted(33)!!.title())
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test fun theSwitchTurnsTheEverydayNoticeOff() {
        AfterCall.show(context, call())
        assertNotNull(posted(33))
        AfterCall.setEnabled(context, false)
        assertNull("switching it off takes the last one down", posted(33))
        assertFalse(AfterCall.show(context, call()))
        assertNull(posted(33))
        // Nor is a one-sided or undecided verdict a problem to tell anyway.
        assertFalse(AfterCall.show(context, call(verdict = AudioCheck.Verdict.ONE_SIDE)))
        assertFalse(AfterCall.show(context, call(verdict = AudioCheck.Verdict.UNKNOWN)))
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    @Test fun oneSidedOrUndecidedIsAnOrdinaryCall() {
        AfterCall.show(context, call(verdict = AudioCheck.Verdict.ONE_SIDE))
        assertEquals("jemrec.aftercall.v2", posted(33)!!.channelId)
        AfterCall.show(context, call(verdict = AudioCheck.Verdict.UNKNOWN))
        assertEquals("jemrec.aftercall.v2", posted(33)!!.channelId)
        assertEquals("Anna", posted(33)!!.title())
    }

    @Test fun aSilentRecordingIsAProblemToldWhateverTheSwitch() {
        AfterCall.setEnabled(context, false)
        assertTrue(AfterCall.show(context, call(verdict = AudioCheck.Verdict.SILENT)))
        val n = posted(33)!!
        assertEquals(AfterCall.PROBLEM_CHANNEL_ID, n.channelId)
        assertEquals("Last call recorded, but silent", n.title())
        val text = "Anna, 1:05. No sound came through. This happens on calls over Wi-Fi calling, " +
            "and on some phones always. Run the self-test to check this phone."
        assertEquals(text, n.text())
        assertEquals(text, n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString())
        assertEquals(listOf("Play", "Self-test", "Delete"), n.buttons())
        val selfTest = shadowOf(n.button("Self-test"))
        assertEquals(3303, selfTest.requestCode)
        assertSameLink(DeepLink.intent(context, DeepLink.Kind.SELF_TEST, null), selfTest.savedIntent)
        assertEquals(android.R.drawable.stat_notify_error, n.smallIcon.resId)
        assertEquals(twelveHours, n.timeoutAfter)
        assertEquals("A call recording needs a look", n.publicVersion.title())
    }

    @Test fun aMessageIsPostedWhateverTheSwitchAndKeptUntilDismissed() {
        AfterCall.setEnabled(context, false)
        val c = call(message = true, seconds = 42.0)
        assertTrue(AfterCall.show(context, c))
        assertNull("not as the everyday notice", posted(33))
        val n = posted(messageId(c.name))!!
        assertEquals("jemrec.messages.v1", n.channelId)
        assertEquals("Message from Anna", n.title())
        assertEquals("42 s message", n.text())
        assertEquals(listOf("Play", "Share", "Delete"), n.buttons())
        assertEquals("never times out", 0L, n.timeoutAfter)
        assertEquals("New message", n.publicVersion.title())
    }

    @Test fun aSilentMessageIsAMessageStill() {
        // The user's side is muted, and a caller who says nothing after the
        // beep left a silent message, not a recording gone wrong.
        val c = call(message = true, caller = null, verdict = AudioCheck.Verdict.SILENT)
        AfterCall.show(context, c)
        val n = posted(messageId(c.name))!!
        assertEquals("jemrec.messages.v1", n.channelId)
        assertEquals("New message", n.title())
        assertEquals("1:05 message", n.text())
        assertEquals(listOf("Play", "Share", "Delete"), n.buttons())
    }

    @Test fun twoMessagesAreTwoNotifications() {
        val first = call(name = "2026-11-01 10-00-00 in.ogg", message = true)
        val second = call(name = "2026-11-01 11-00-00 in.ogg", message = true, caller = "Bob")
        AfterCall.show(context, first)
        AfterCall.show(context, second)
        assertEquals("Message from Anna", posted(messageId(first.name))!!.title())
        assertEquals("Message from Bob", posted(messageId(second.name))!!.title())
        assertEquals(2, shadowOf(manager).allNotifications.size)
    }

    @Test fun deleteAsksFirstAndKeepPutsItBack() {
        val c = call()
        AfterCall.show(context, c)
        val receiver = AfterCallReceiver()
        receiver.onReceive(context, shadowOf(posted(33)!!.button("Delete")).savedIntent)
        val asking = posted(33)!!
        assertEquals("Anna", asking.title())
        assertEquals(
            "Delete the recording of this call? It goes to Recently deleted, where it can be restored for ${Trash.DAYS} days.",
            asking.text(),
        )
        assertEquals(listOf("Delete", "Keep"), asking.buttons())
        assertEquals(twelveHours, asking.timeoutAfter)
        val keep = shadowOf(asking.button("Keep"))
        assertEquals(AfterCall.ACTION_DELETE_CANCEL, keep.savedIntent.action)
        assertEquals(AfterCall.ACTION_DELETE_CONFIRM, shadowOf(asking.button("Delete")).savedIntent.action)
        receiver.onReceive(context, keep.savedIntent)
        assertEquals("Recorded, 1:05", posted(33)!!.text())
        assertEquals(listOf("Play", "Share", "Delete"), posted(33)!!.buttons())
    }

    @Test fun keepAfterTheSwitchWentOffTakesItAway() {
        val c = call()
        AfterCall.show(context, c)
        val receiver = AfterCallReceiver()
        receiver.onReceive(context, shadowOf(posted(33)!!.button("Delete")).savedIntent)
        val keep = shadowOf(posted(33)!!.button("Keep")).savedIntent
        // The switch off with the question still up. Written straight to its
        // key: setEnabled takes the question down first, so this is the one
        // way to reach Keep's other branch.
        Prefs.of(context).edit().putBoolean("after_call_notice", false).commit()
        receiver.onReceive(context, keep)
        assertNull(posted(33))
    }

    @Test fun aMessagesQuestionStaysAsLongAsTheMessage() {
        val c = call(message = true)
        AfterCall.show(context, c)
        AfterCallReceiver().onReceive(context, c.into(Intent(AfterCall.ACTION_DELETE_ASK)))
        val asking = posted(messageId(c.name))!!
        assertEquals("jemrec.messages.v1", asking.channelId)
        assertEquals(0L, asking.timeoutAfter)
        assertEquals(listOf("Delete", "Keep"), asking.buttons())
    }

    @Test fun theChannelsAreQuietLoudAndNews() {
        manager.createNotificationChannel(NotificationChannel("jemrec.aftercall.v1", "old", NotificationManager.IMPORTANCE_LOW))
        AfterCall.ensureChannels(context)
        val everyday = manager.getNotificationChannel("jemrec.aftercall.v2")!!
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, everyday.importance)
        assertNull(everyday.sound)
        assertFalse(everyday.shouldVibrate())
        assertFalse(everyday.canShowBadge())
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel("jemrec.messages.v1")!!.importance)
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT, manager.getNotificationChannel(AfterCall.PROBLEM_CHANNEL_ID)!!.importance)
        assertNull("v1 was LOW, and is gone", manager.getNotificationChannel("jemrec.aftercall.v1"))
    }
}
