package com.jemcik.jemrec.capture

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Hides the system's "Wireless debugging connected" notification.
 *
 * WHY IT HAS TO EXIST
 *
 * On this phone a shell-uid process dies the instant adbd restarts - the kill
 * is by cgroup, and shell cannot leave its cgroup - and adbd restarts whenever
 * Wireless debugging is toggled off. So the recorder can only stay alive while
 * Wireless debugging stays ON, and Android shows its own permanent "Wireless
 * debugging connected" notification for exactly as long. The two are welded
 * together by the ROM; no setting unpicks them. What can be unpicked is the
 * notification.
 *
 * WHY SNOOZE, NOT CANCEL
 *
 * The banner is posted by package "android" with FLAG_ONGOING_EVENT, and since
 * Android 12 a listener's cancelNotification() is ignored for ongoing system
 * notifications - measured, it returns without error and the banner stays.
 * snoozeNotification() is not ignored: it takes the banner off the shade for
 * the duration given. Snoozed for a week; if it ever comes back - the snooze
 * expiring, or Wireless debugging being re-armed after a revive - onNotification
 * Posted fires and it is snoozed again.
 *
 * HOW IT TURNS ON WITHOUT A SCARY TOGGLE
 *
 * Notification access is a Settings switch that, for a side-loaded app, is
 * greyed out under "restricted settings" exactly like accessibility was. It is
 * not granted through Settings. The app grants it over its own ADB shell with
 * `cmd notification allow_listener`, during setup, the same way it grants itself
 * WRITE_SECURE_SETTINGS. Granting alone does not bind the service on this ROM,
 * so MainActivity toggles the component's enabled state to force the bind.
 *
 * WHAT IT WILL AND WILL NOT TOUCH
 *
 * Only notifications posted by the system package whose title or text mentions
 * debugging - the wireless banner and its USB twin. It reads their text to
 * decide and touches nothing it did not recognise, so it cannot hide a message,
 * a call, or anything a person would miss.
 */
class DebugNotificationCleaner : NotificationListenerService() {

    override fun onListenerConnected() {
        // The banner is already up by the time we bind, so sweep what is there
        // rather than waiting for it to be posted again.
        runCatching { activeNotifications }.getOrNull()?.forEach { hideIfDebug(it) }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { hideIfDebug(it) }
    }

    private fun hideIfDebug(sbn: StatusBarNotification) {
        if (sbn.packageName != SYSTEM_PACKAGE) return
        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence("android.title")?.toString()?.lowercase().orEmpty()
        val text = extras.getCharSequence("android.text")?.toString()?.lowercase().orEmpty()
        if (DEBUG_WORD !in title && DEBUG_WORD !in text) return

        runCatching { snoozeNotification(sbn.key, SNOOZE_MS) }
            .onFailure { Log.w(TAG, "debug-notif: could not snooze the banner", it) }
    }

    private companion object {
        const val TAG = "JemRec"
        const val SYSTEM_PACKAGE = "android"
        const val DEBUG_WORD = "debugging"
        const val SNOOZE_MS = 7L * 24 * 60 * 60 * 1000
    }
}
