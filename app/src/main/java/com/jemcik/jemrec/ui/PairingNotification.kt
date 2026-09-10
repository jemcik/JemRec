package com.jemcik.jemrec.ui

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/**
 * Typing the pairing code from the notification shade, without leaving Settings.
 *
 * WHY NOT AN OVERLAY, WHICH IS THE OBVIOUS ANSWER
 *
 * A floating window was built first and does not work, for a good reason.
 * Android hides non-system overlays over the Settings app - anti-tapjacking
 * protection, and exactly right, since an overlay that can sit on top of a
 * permission screen is how tapjacking works. Measured: the same overlay renders
 * perfectly over the launcher, and over Wireless debugging the window still
 * exists but with `frame=[Rect(0, 0 - 0, 0)] alpha=0.0`. The one screen it
 * would need to cover is the one screen it may not.
 *
 * The notification shade is a system surface, so it has no such problem, and a
 * direct-reply action turns it into a text field. Measured: opening the shade
 * over the pairing dialog leaves the dialog alive - Settings stays the resumed
 * activity and the pairing port keeps listening - which is the whole
 * requirement.
 *
 * It also needs no permission beyond POST_NOTIFICATIONS, which the app already
 * has and already explains, rather than "display over other apps", which is
 * alarming to grant and rightly so.
 *
 * The typed code goes straight to [PairingService], which then does the pairing
 * AND the waiting-for-switches that follows, under this same notification.
 */
object PairingNotification {

    private const val TAG = "JemRec"
    private const val CHANNEL_ID = "jemrec.pairing.v2"
    private const val LEGACY_CHANNEL_ID = "jemrec.pairing"

    /**
     * Shared with PairingService, which runs in the foreground UNDER this same
     * notification - one card in the shade for the whole of pairing, whether a
     * code is being typed into it or the service is waiting for a switch.
     */
    const val NOTIFICATION_ID = 2
    const val KEY_CODE = "pairing_code"

    /**
     * Build the card.
     *
     * [withReply] adds the "Enter code" box. The service drops it while it is
     * pairing or waiting for switches: the code has already been typed, and a
     * box still sitting there would only invite typing it again.
     */
    fun build(context: Context, text: String? = null, withReply: Boolean = true): Notification {
        val manager = context.getSystemService(NotificationManager::class.java)
        runCatching { manager.deleteNotificationChannel(LEGACY_CHANNEL_ID) }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Pairing",
                // HIGH so it is easy to find in a shade opened over Settings.
                // Silent, because it appears while the user is mid-task.
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                setSound(null, null)
                // Setup is a one-off. A badge left over from it would outlive
                // the thing it was about.
                setShowBadge(false)
            }
        )

        val builder = Notification.Builder(context, CHANNEL_ID)
            .setContentTitle("JemRec — pairing")
            .setContentText(text ?: "Open the pairing dialog, then type the code here.")
            .setStyle(
                Notification.BigTextStyle().bigText(
                    text ?: "In Settings, open Wireless debugging and tap \"Pair device " +
                        "with pairing code\". Then swipe this shade down over it and type " +
                        "the six digits here — you never have to leave the dialog."
                )
            )
            .setSmallIcon(android.R.drawable.ic_menu_send)
            // The words change as the service watches the switches. Each change
            // must not re-alert, or the shade would buzz on every update.
            .setOnlyAlertOnce(true)
            .setOngoing(true)

        if (withReply) {
            val remoteInput = RemoteInput.Builder(KEY_CODE)
                .setLabel("6-digit code")
                .build()

            // Delivered STRAIGHT to the foreground service, the way Shizuku does
            // it: the system starts the service as the result of the tap, so
            // there is no receiver hop and no question of whether a background
            // start is allowed. MUTABLE is required: the system has to write the
            // typed text into this intent before delivering it. An immutable one
            // silently arrives empty.
            val pending = PendingIntent.getForegroundService(
                context,
                0,
                Intent(context, PairingService::class.java),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

            builder.addAction(
                Notification.Action.Builder(
                    android.R.drawable.ic_menu_send, "Enter code", pending,
                ).addRemoteInput(remoteInput).build()
            )
        }

        return builder.build()
    }

    fun show(context: Context, text: String? = null, withReply: Boolean = true) {
        context.getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, build(context, text, withReply))
        Log.i(TAG, "pairing notification: shown")
    }

    fun update(context: Context, text: String, withReply: Boolean = true) =
        show(context, text, withReply)

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    /**
     * Whether the pairing code has nowhere to land.
     *
     * The whole shade mechanism rests on one thing being true: that a
     * notification from this app can appear. If notifications are turned off for
     * JemRec, or this pairing channel has been muted to IMPORTANCE_NONE, the
     * code field never shows and pairing is impossible - not merely un-peeked,
     * dead. Unlike whether it pops as a heads-up (which no API can tell us, and
     * which the "swipe down" instruction does not need), THIS is fully knowable,
     * and it is the one notification state that actually strands the user - so
     * setup checks it and offers a way to fix it before sending anyone hunting
     * for a prompt that cannot come.
     *
     * The channel may not exist yet the first time this is asked - it is created
     * in build() - and that is not "blocked": it will be created at
     * IMPORTANCE_HIGH. Only an app-level block or an explicit mute counts.
     */
    fun blocked(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (!manager.areNotificationsEnabled()) return true
        val channel = manager.getNotificationChannel(CHANNEL_ID) ?: return false
        return channel.importance == NotificationManager.IMPORTANCE_NONE
    }

    /**
     * Open the system screen where the user turns the pairing notification back
     * on - the pairing channel's own settings by preference, falling back to the
     * app's notification settings if the channel is not there to point at yet.
     */
    fun openNotificationSettings(context: Context) {
        val intents = listOf(
            Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL_ID),
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
        for (intent in intents) {
            val opened = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (opened) return
        }
    }
}
