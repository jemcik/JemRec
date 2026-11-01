package com.jemcik.jemrec.capture

import android.app.Notification
import android.app.NotificationManager
import com.jemcik.jemrec.RecordPromptActivity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * The start-of-call prompt in Ask first (RecordPrompt), and the ticket its
 * answer must carry: CallMonitorService is exported, so the ticket is all
 * that tells the prompt's own Record from any app's (GHSA-hf4v-7hwh-hx7w).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class RecordPromptTest {

    private val context = RuntimeEnvironment.getApplication()
    private val manager = context.getSystemService(NotificationManager::class.java)

    private fun prompt(): Notification? = shadowOf(manager).getNotification(3)

    /** The ticket as the prompt's buttons carry it. */
    private fun ticketOf(n: Notification, button: Int = 0): String? =
        shadowOf(n.actions[button].actionIntent).savedIntent.getStringExtra("ticket")

    @Test fun thePromptAsksOnItsOwnChannelWithRecordAndNotNow() {
        RecordPrompt.show(context, incoming = true)
        val n = prompt()!!
        assertEquals("jemrec.ask.v2", n.channelId)
        assertEquals("Record this call?", n.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals("Incoming call in progress.", n.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertEquals(Notification.CATEGORY_EVENT, n.category)
        // Swiping it away is a no.
        assertEquals(0, n.flags and Notification.FLAG_ONGOING_EVENT)
        assertTrue(n.flags and Notification.FLAG_AUTO_CANCEL != 0)
        assertEquals(listOf("Record", "Not now"), n.actions.map { it.title.toString() })
        n.actions.zip(listOf(CallMonitorService.ACTION_RECORD_NOW, CallMonitorService.ACTION_DECLINE)).forEach { (button, action) ->
            val pending = shadowOf(button.actionIntent)
            // A notification's action may start a foreground service from the background.
            assertTrue(pending.isForegroundServiceIntent)
            assertEquals(action, pending.savedIntent.action)
            assertEquals(CallMonitorService::class.java.name, pending.savedIntent.component?.className)
            assertEquals(action.hashCode(), pending.requestCode)
            assertTrue(pending.savedIntent.getBooleanExtra("incoming", false))
        }
        assertEquals("both buttons carry one ticket", ticketOf(n, 0), ticketOf(n, 1))
        // The full-screen intent opens the prompt's activity, with the ticket.
        val full = shadowOf(n.fullScreenIntent)
        assertTrue(full.isActivityIntent)
        assertEquals(RecordPromptActivity::class.java.name, full.savedIntent.component?.className)
        assertEquals(ticketOf(n), full.savedIntent.getStringExtra("ticket"))
    }

    @Test fun itsChannelVibratesAndMakesNoSound() {
        manager.createNotificationChannel(
            android.app.NotificationChannel("jemrec.ask", "old", NotificationManager.IMPORTANCE_LOW),
        )
        RecordPrompt.show(context, incoming = false)
        val channel = manager.getNotificationChannel("jemrec.ask.v2")!!
        assertEquals(NotificationManager.IMPORTANCE_HIGH, channel.importance)
        assertNull(channel.sound)
        assertTrue(channel.shouldVibrate())
        assertArrayEquals(longArrayOf(0, 120, 80, 120), channel.vibrationPattern)
        assertFalse(channel.canShowBadge())
        assertNull("the pre-v2 channel is gone", manager.getNotificationChannel("jemrec.ask"))
    }

    @Test fun thePromptNamesTheCall() {
        assertEquals("Incoming call in progress.", RecordPrompt.callText(true, null))
        assertEquals("Outgoing call in progress.", RecordPrompt.callText(false, null))
        // A messenger call by its app, whichever way the daemon said it went.
        assertEquals("Telegram call in progress.", RecordPrompt.callText(false, "org.telegram.messenger"))
        assertEquals("Telegram call in progress.", RecordPrompt.callText(true, "org.telegram.messenger"))
        assertEquals("Messenger call in progress.", RecordPrompt.callText(false, "com.example.unknown"))
    }

    @Test fun theTicketAnswersOnce() {
        RecordPrompt.show(context, incoming = true)
        val ticket = ticketOf(prompt()!!)!!
        assertEquals("16 random bytes, in hex", 32, ticket.length)
        assertTrue(RecordPrompt.claim(context, ticket))
        assertFalse("spent", RecordPrompt.claim(context, ticket))
    }

    @Test fun aWrongTicketDoesNotSpendTheRightOne() {
        RecordPrompt.show(context, incoming = true)
        val ticket = ticketOf(prompt()!!)!!
        assertFalse(RecordPrompt.claim(context, null))
        assertFalse(RecordPrompt.claim(context, ""))
        assertFalse(RecordPrompt.claim(context, "0".repeat(32)))
        assertTrue(RecordPrompt.claim(context, ticket))
    }

    @Test fun noPromptNoTicket() {
        assertFalse(RecordPrompt.claim(context, "0".repeat(32)))
        // Nor an empty one against an empty store.
        assertFalse(RecordPrompt.claim(context, ""))
    }

    @Test fun eachPromptHasANewTicket() {
        RecordPrompt.show(context, incoming = true)
        val first = ticketOf(prompt()!!)!!
        RecordPrompt.show(context, incoming = true)
        val second = ticketOf(prompt()!!)!!
        assertNotEquals(first, second)
        assertFalse("the last prompt's buttons answer nothing", RecordPrompt.claim(context, first))
        assertTrue(RecordPrompt.claim(context, second))
    }

    @Test fun dismissingThePromptTakesItsTicketWithIt() {
        RecordPrompt.show(context, incoming = true)
        val ticket = ticketOf(prompt()!!)!!
        assertNotNull(prompt())
        RecordPrompt.dismiss(context)
        assertNull(prompt())
        assertFalse(RecordPrompt.claim(context, ticket))
    }
}
