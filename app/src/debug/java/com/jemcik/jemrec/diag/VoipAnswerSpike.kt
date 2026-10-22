package com.jemcik.jemrec.diag

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.jemcik.jemrec.capture.DebugNotificationCleaner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Can JemRec answer a ringing MESSENGER call for the user? Feasibility test,
 * debug build only - see the answering machine's spike notes.
 *
 * Messengers do not put their calls through Telecom on the phone this was
 * built on (no WhatsApp, Telegram or Viber phone account in `dumpsys
 * telecom`), so the recorder's acceptRingingCall cannot reach them. What every
 * one of them does post is a call notification, and JemRec's notification
 * listener - bound when the user lets JemRec hide the debugging notification
 * or name messenger callers - can read it. So: find the ringing call's
 * notification, wait, and fire its answer - a CallStyle notification's
 * answer intent (Android 12+), else the action that says answer.
 *
 * Armed by `--es op voipanswer [--ei after 5] [--ei minutes 5]`; writes what it
 * saw and did to files/voip-answer.log (read with run-as).
 */
internal object VoipAnswerSpike {

    private val MESSENGERS = setOf("com.whatsapp", "org.telegram.messenger", "com.viber.voip", "org.thoughtcrime.securesms")
    private val ANSWER_WORDS = Regex("answer|accept|відпов|прийня|ответ|принят", RegexOption.IGNORE_CASE)

    @Volatile
    private var running: Thread? = null

    fun arm(context: Context, afterSeconds: Int, minutes: Int) {
        running?.interrupt()
        val log = File(context.filesDir, "voip-answer.log")
        val thread = Thread({ watch(context, log, afterSeconds, minutes) }, "voip-answer-spike")
        thread.isDaemon = true
        running = thread
        thread.start()
    }

    private fun watch(context: Context, log: File, afterSeconds: Int, minutes: Int) {
        write(log, "armed: answer after $afterSeconds s, for $minutes min")
        val until = System.currentTimeMillis() + minutes * 60_000L
        var seenKey: String? = null
        var seenAt = 0L
        var noListener = false
        try {
            while (System.currentTimeMillis() < until) {
                val listener = listener()
                if (listener == null) {
                    if (!noListener) write(log, "no notification listener bound - is notification access on?")
                    noListener = true
                    Thread.sleep(1_000)
                    continue
                }
                val call = runCatching { listener.activeNotifications }.getOrNull()
                    ?.firstOrNull { it.packageName in MESSENGERS && isCall(it.notification) }
                val now = System.currentTimeMillis()
                if (call == null) {
                    if (seenKey != null) write(log, "the call notification went before it was answered")
                    seenKey = null
                } else {
                    if (call.key != seenKey) {
                        seenKey = call.key
                        seenAt = now
                        write(log, "ringing: " + describe(call))
                    }
                    if (now - seenAt >= afterSeconds * 1000L) {
                        write(log, answer(context, call))
                        Thread.sleep(3_000)
                        val still = runCatching { listener.activeNotifications }.getOrNull()
                            ?.firstOrNull { it.key == call.key }
                        write(log, "3 s later: " + (still?.let { describe(it) } ?: "its notification is gone"))
                        return
                    }
                }
                Thread.sleep(250)
            }
            write(log, "disarmed: no messenger call came")
        } catch (e: InterruptedException) {
            write(log, "disarmed: re-armed")
        } catch (t: Throwable) {
            write(log, "failed: $t")
        }
    }

    /** JemRec's own listener, while Android has it bound. Private in the app;
     *  reached by reflection here, which is fine for a spike. */
    private fun listener(): NotificationListenerService? = runCatching {
        DebugNotificationCleaner::class.java.getDeclaredField("connected")
            .apply { isAccessible = true }
            .get(null) as NotificationListenerService?
    }.getOrNull()

    private fun isCall(n: Notification): Boolean =
        n.category == Notification.CATEGORY_CALL ||
            n.extras?.getString(Notification.EXTRA_TEMPLATE)?.contains("CallStyle") == true ||
            n.extras?.containsKey(Notification.EXTRA_ANSWER_INTENT) == true

    private fun describe(sbn: StatusBarNotification): String {
        val n = sbn.notification
        val extras = n.extras
        return "pkg=${sbn.packageName} category=${n.category} template=${extras?.getString(Notification.EXTRA_TEMPLATE)} " +
            "channel=${n.channelId} ongoing=${sbn.isOngoing} fullScreen=${n.fullScreenIntent != null} " +
            "answerIntent=${extras?.containsKey(Notification.EXTRA_ANSWER_INTENT) == true} " +
            "actions=${n.actions?.joinToString("|") { it.title.toString() } ?: "none"}"
    }

    private fun answer(context: Context, sbn: StatusBarNotification): String {
        val n = sbn.notification
        @Suppress("DEPRECATION")
        val styled = n.extras?.getParcelable<PendingIntent>(Notification.EXTRA_ANSWER_INTENT)
        val action = n.actions?.firstOrNull { ANSWER_WORDS.containsMatchIn(it.title.toString()) }
        val (how, intent) = when {
            styled != null -> "CallStyle answer intent" to styled
            action != null -> "action '${action.title}'" to action.actionIntent
            else -> return "no answer intent and no action that says answer: " + describe(sbn)
        }
        return try {
            // Allowed to start the messenger's call screen from the background,
            // where Android 14+ would otherwise refuse it for this sender.
            val options = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else {
                null
            }
            intent.send(context, 0, null, null, null, null, options)
            "sent the $how"
        } catch (t: Throwable) {
            "could not send the $how: $t"
        }
    }

    private fun write(log: File, line: String) {
        val stamped = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date()) + " " + line
        runCatching { log.appendText(stamped + "\n") }
    }
}
