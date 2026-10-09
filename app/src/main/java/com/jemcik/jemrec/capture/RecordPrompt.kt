package com.jemcik.jemrec.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.RecordPromptActivity
import java.security.SecureRandom

/**
 * Asks, mid-call, whether to record this one.
 *
 * WHY THIS IS A NOTIFICATION AND NOT A DIALOG
 *
 * A dialog is an activity, and an app cannot start an activity from the
 * background. Holding a foreground service does not buy that back - Android 10
 * removed the exemption and Android 12 tightened what is left. During a call
 * this app is firmly in the background with the dialer on top, so the obvious
 * design is simply not available.
 *
 * The two ways to get a real window there are both worse. SYSTEM_ALERT_WINDOW,
 * "display over other apps", would allow it - that is exactly the permission
 * tapjacking needs, it is alarming to grant, and this app already decided
 * against it once when the pairing overlay turned out to be blocked over
 * Settings. A full-screen intent is gated behind USE_FULL_SCREEN_INTENT, which
 * from Android 14 is granted automatically only to calling and alarm apps, and
 * even when granted it opens full screen only while the device is locked.
 *
 * A high-importance notification needs none of that. It arrives as a banner
 * over the dialer with the buttons on it, and if the banner is missed it is
 * still sitting in the shade, which is a surface this app already relies on and
 * has measured working over another app's screen.
 *
 * It vibrates and makes no sound. Mid-call a sound is the one thing it must not
 * do, and a silent banner is easy to miss while the phone is at an ear.
 */
object RecordPrompt {

    private const val TAG = "JemRec"
    private const val CHANNEL_ID = "jemrec.ask.v2"
    private const val LEGACY_CHANNEL_ID = "jemrec.ask"
    private const val NOTIFICATION_ID = 3
    private const val KEY_TICKET = "prompt_ticket"
    private val random = SecureRandom()

    fun show(context: Context, incoming: Boolean) {
        val ticket = mintTicket(context)
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Record this call?",
                // HIGH is what makes it a banner rather than a line in the
                // shade, which matters when the user has a phone at their ear
                // and a few seconds to decide.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                // A question about the call happening right now. Once it is
                // answered or the call ends there is nothing left to read, so
                // it has no business leaving a count on the launcher icon.
                setShowBadge(false)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 120, 80, 120)
                description = "Asked at the start of a call when automatic recording is off"
            }
        )

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("Record this call?")
            .setContentText(
                if (incoming) "Incoming call in progress." else "Outgoing call in progress."
            )
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setCategory(Notification.CATEGORY_EVENT)
            // The banner otherwise vanishes after about five seconds, which is
            // not long enough to notice mid-conversation. There is no API for
            // that timeout - it belongs to SystemUI - and a full-screen intent
            // is the one supported lever: SystemUI leaves a heads-up backed by
            // one on screen far longer. On a locked phone it opens the activity
            // properly, which is the case with no shade to pull down.
            .setFullScreenIntent(fullScreen(context, incoming, ticket), true)
            .addAction(action(context, "Record", CallMonitorService.ACTION_RECORD_NOW, incoming, ticket))
            .addAction(action(context, "Not now", CallMonitorService.ACTION_DECLINE, incoming, ticket))
            // Deliberately dismissable: swiping it away is a perfectly clear
            // way to say no, and an unswipeable prompt during a call would be
            // an irritation with no upside.
            .setOngoing(false)
            .setAutoCancel(true)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
        Log.i(TAG, "prompt: asking whether to record this call")
    }

    private fun fullScreen(context: Context, incoming: Boolean, ticket: String): PendingIntent =
        PendingIntent.getActivity(
            context,
            1,
            Intent(context, RecordPromptActivity::class.java)
                .putExtra(CallMonitorService.EXTRA_INCOMING, incoming)
                .putExtra(CallMonitorService.EXTRA_TICKET, ticket)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    /** Take the prompt down. Its ticket goes with it: a prompt that is not
     *  showing has no answer left to give. */
    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        synchronized(this) { Prefs.of(context).edit().remove(KEY_TICKET).apply() }
    }

    /**
     * Whether an answer came from the prompt that is up right now. Spends the
     * ticket, so one prompt gives one answer.
     *
     * WHY THE ANSWER NEEDS A TICKET AT ALL
     *
     * The buttons go to CallMonitorService, and that service is exported so
     * the daemon can wake it. Exported means any installed app can send it an
     * intent, and before this a bare RECORD_NOW from anywhere started
     * recording the call in progress - in the mode where the user had said
     * they would decide each call themselves, and after they had said Not now.
     * Reported privately as GHSA-hf4v-7hwh-hx7w.
     *
     * The ticket is minted fresh for each prompt and travels only in this
     * app's own intents: the prompt's PendingIntents, whose extras no other
     * app can read, and the answer RecordPromptActivity sends. So only this
     * prompt's own buttons carry it, and only until it is answered, dismissed
     * or the call ends. Kept in preferences rather than in the service, so the
     * answer still lands if the service was stopped or the process was killed
     * while the prompt sat waiting.
     */
    fun claim(context: Context, presented: String?): Boolean = synchronized(this) {
        val prefs = Prefs.of(context)
        val expected = prefs.getString(KEY_TICKET, null)
        if (presented.isNullOrEmpty() || presented != expected) return false
        prefs.edit().remove(KEY_TICKET).apply()
        true
    }

    private fun mintTicket(context: Context): String = synchronized(this) {
        val ticket = ByteArray(16).also { random.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        Prefs.of(context).edit().putString(KEY_TICKET, ticket).apply()
        ticket
    }

    /**
     * The buttons target the monitor service directly.
     *
     * getForegroundService, not getService: the tap arrives while the app is in
     * the background, and a notification action is one of the few things that
     * is allowed to start a foreground service from there. The service is
     * already running, so this is really just a delivery, but asking for the
     * weaker form would be relying on that staying true.
     */
    private fun action(
        context: Context,
        label: String,
        action: String,
        incoming: Boolean,
        ticket: String,
    ): Notification.Action {
        val intent = Intent(context, CallMonitorService::class.java)
            .setAction(action)
            .putExtra(CallMonitorService.EXTRA_INCOMING, incoming)
            .putExtra(CallMonitorService.EXTRA_TICKET, ticket)
        val pending = PendingIntent.getForegroundService(
            context,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Action.Builder(
            android.R.drawable.ic_btn_speak_now, label, pending,
        ).build()
    }
}
