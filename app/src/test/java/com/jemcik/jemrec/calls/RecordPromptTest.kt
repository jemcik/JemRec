package com.jemcik.jemrec.calls

import android.app.Notification
import android.app.NotificationManager
import android.os.CombinedVibration
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.VibratorManager
import com.jemcik.jemrec.RecordPromptActivity
import com.jemcik.jemrec.capture.CallMonitorService
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
import org.robolectric.util.ReflectionHelpers
import java.lang.reflect.Proxy

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
        // It can be swiped away, and a tap does not take it away.
        assertEquals(0, n.flags and Notification.FLAG_ONGOING_EVENT)
        assertEquals(0, n.flags and Notification.FLAG_AUTO_CANCEL)
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
        // And a tap on the question does the same.
        assertEquals(n.fullScreenIntent, n.contentIntent)
        assertFalse("a banner of its own", quiet(n))
    }

    /** What keeps Android from showing a heads-up of it: a group's child, the
     *  group's summary to do the alerting (Notification
     *  .suppressAlertingDueToGrouping, which SystemUI asks; hidden). */
    private fun quiet(n: Notification): Boolean =
        n.group != null && n.flags and Notification.FLAG_GROUP_SUMMARY == 0 &&
            n.groupAlertBehavior == Notification.GROUP_ALERT_SUMMARY

    @Test fun withTheButtonOverTheCallThereIsNoBanner() {
        var under: String? = null
        RecordPrompt.show(context, incoming = true) { ticket -> under = ticket; true }
        assertEquals("one ticket, on both", ticketOf(prompt()!!), under)
        assertNull(prompt()!!.fullScreenIntent)
        assertTrue("in the shade alone", quiet(prompt()!!))
        assertEquals("on its own channel", "jemrec.ask.v2", prompt()!!.channelId)
        assertEquals(listOf("Record", "Not now"), prompt()!!.actions.map { it.title.toString() })
        assertEquals(
            "a tap on it opens its window all the same",
            RecordPromptActivity::class.java.name,
            shadowOf(prompt()!!.contentIntent).savedIntent.component?.className,
        )
        // A button that could not be shown leaves the question as it was.
        RecordPrompt.show(context, incoming = true) { false }
        assertNotNull(prompt()!!.fullScreenIntent)
        assertFalse("a banner of its own", quiet(prompt()!!))
    }

    @Test fun aPhoneAsleepIsAskedWithTheQuestionsWindowTheButtonOrNot() {
        // Behind the lock screen until the call's own screen is in front.
        shadowOf(context.getSystemService(android.os.PowerManager::class.java)).turnScreenOn(false)
        RecordPrompt.show(context, incoming = true) { true }
        assertNotNull(prompt()!!.fullScreenIntent)
        assertFalse("Android does not open the window of a quiet one", quiet(prompt()!!))
        shadowOf(context.getSystemService(android.os.PowerManager::class.java)).turnScreenOn(true)
        RecordPrompt.show(context, incoming = true) { true }
        assertNull(prompt()!!.fullScreenIntent)
        assertTrue(quiet(prompt()!!))
    }

    @Test fun whoWatchesATicketHearsOnceItIsNoLongerLive() {
        RecordPrompt.show(context, incoming = true)
        val ticket = RecordPrompt.ticket(context)
        var heard = 0
        val stop = RecordPrompt.watch(context, ticket) { heard++ }
        // Something else of JemRec's changed.
        com.jemcik.jemrec.Prefs.of(context).edit().putBoolean("hide_debug_notification", true).apply()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(0, heard)
        RecordPrompt.dismiss(context)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(1, heard)
        stop()
        RecordPrompt.show(context, incoming = true)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals("no longer told", 1, heard)
        // One already over is heard of at once.
        RecordPrompt.watch(context, ticket) { heard++ }.invoke()
        assertEquals(2, heard)
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

    /** What reaches Android's vibrator service: each vibration, and what for. */
    private val vibrations = mutableListOf<Pair<CombinedVibration, VibrationAttributes>>()

    init {
        val service = Class.forName("android.os.IVibratorManagerService")
        ReflectionHelpers.setField(
            context.getSystemService(VibratorManager::class.java),
            "mService",
            Proxy.newProxyInstance(service.classLoader, arrayOf(service)) { _, method, args ->
                if (method.name == "vibrate") {
                    vibrations += args.filterIsInstance<CombinedVibration>().single() to
                        args.filterIsInstance<VibrationAttributes>().single()
                }
                null
            },
        )
    }

    @Test fun inAPhoneCallItVibratesByItselfAsItsChannelDoes() {
        RecordPrompt.show(context, incoming = true)
        val (vibration, whatFor) = vibrations.single()
        assertEquals(
            CombinedVibration.createParallel(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1)),
            vibration,
        )
        assertEquals(
            "a notification's, so the phone's rules for those hold",
            VibrationAttributes.USAGE_NOTIFICATION,
            whatFor.usage,
        )
    }

    @Test fun aMessengersQuestionVibratesTheSame() {
        // Its call may go through Telecom, as Google Meet's do, and have
        // Android drop the channel's vibration as a phone call has.
        RecordPrompt.show(context, incoming = false, app = "com.google.android.apps.tachyon")
        assertEquals(1, vibrations.size)
    }

    @Test fun itDoesNotVibrateWhereItsNotificationWouldNot() {
        RecordPrompt.ensureChannel(context)
        val channel = manager.getNotificationChannel(RecordPrompt.CHANNEL_ID)
        channel.enableVibration(false)
        RecordPrompt.show(context, incoming = true)
        assertEquals("its vibration switched off", 0, vibrations.size)
        channel.enableVibration(true)
        channel.importance = NotificationManager.IMPORTANCE_LOW
        RecordPrompt.show(context, incoming = true)
        assertEquals("made silent", 0, vibrations.size)
        channel.importance = NotificationManager.IMPORTANCE_HIGH
        shadowOf(manager).setNotificationsEnabled(false)
        RecordPrompt.show(context, incoming = true)
        assertEquals("JemRec's notifications off", 0, vibrations.size)
        shadowOf(manager).setNotificationsEnabled(true)
        RecordPrompt.show(context, incoming = true)
        assertEquals(1, vibrations.size)
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

    @Test fun aKeptRecordHasATicketOfItsOwnAndAButtonOfItsOwn() {
        RecordPrompt.show(context, incoming = true)
        val asked = ticketOf(prompt()!!)!!
        assertEquals("the question's, while it is up", asked, RecordPrompt.ticket(context))
        assertTrue(RecordPrompt.claim(context, asked))
        assertNull(RecordPrompt.ticket(context))
        RecordPrompt.keepRecord(context)
        val kept = RecordPrompt.ticket(context)!!
        assertNotEquals(asked, kept)
        val button = RecordPrompt.keptRecord(context, kept)
        val pending = shadowOf(button.actionIntent)
        assertEquals("Record", button.title.toString())
        assertEquals(CallMonitorService.ACTION_RECORD_NOW, pending.savedIntent.action)
        assertEquals(kept, pending.savedIntent.getStringExtra("ticket"))
        // Not the question's PendingIntent: one updated the other's ticket.
        assertNotEquals(CallMonitorService.ACTION_RECORD_NOW.hashCode(), pending.requestCode)
    }

    @Test fun aLiveQuestionsTicketIsNotReplaced() {
        // A Record that did not take, its answer come after the next call's
        // question went up: that question's buttons go on answering.
        RecordPrompt.show(context, incoming = true)
        val ticket = ticketOf(prompt()!!)!!
        RecordPrompt.keepRecord(context)
        assertEquals(ticket, RecordPrompt.ticket(context))
        assertTrue(RecordPrompt.claim(context, ticket))
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
