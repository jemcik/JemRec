package com.jemcik.jemrec.capture

import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jemcik.jemrec.Prefs

/**
 * Hides the system's "Wireless debugging connected" notification - if, and
 * only if, the user said to.
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
 * WHY IT ASKS FIRST
 *
 * It used to hide the banner for everyone, without a word. That banner is the
 * phone's own reminder that debugging is on, and debugging being on means a
 * computer paired with this phone can open a shell on it. Hiding that is the
 * user's call, not the app's - a private security report said so
 * (GHSA-hf4v-7hwh-hx7w), and it was right. So setup now explains what stays on
 * and asks (SetupStep.NEEDS_DEBUGGING_CHOICE), Settings can change the answer,
 * and until there is a yes this does nothing at all: not asked is not yes.
 *
 * WHY SNOOZE, NOT CANCEL
 *
 * The banner is posted by package "android" with FLAG_ONGOING_EVENT, and since
 * Android 12 a listener's cancelNotification() is ignored for ongoing system
 * notifications - measured, it returns without error and the banner stays.
 * snoozeNotification() is not ignored: it takes the banner off the shade for
 * the duration given. Snoozed for a week; if it ever comes back - the snooze
 * expiring, or Wireless debugging being re-armed after a revive - onNotification
 * Posted fires and it is snoozed again. The keys snoozed are remembered, so a
 * change of mind can bring the banner back at once rather than in a week.
 *
 * HOW IT TURNS ON WITHOUT A SCARY TOGGLE
 *
 * Notification access is a Settings switch that, for a side-loaded app, is
 * greyed out under "restricted settings" exactly like accessibility was. It is
 * not granted through Settings. The app grants it over its own ADB shell with
 * `cmd notification allow_listener` - during setup when the answer was yes,
 * the same way it grants itself WRITE_SECURE_SETTINGS, or from Settings later.
 * Granting alone does not bind the service on this ROM, so apply() toggles the
 * component's enabled state to force the bind.
 *
 * WHAT IT WILL AND WILL NOT TOUCH
 *
 * Only notifications posted by the system package that are the debugging
 * banners - the wireless one and its USB twin. It reads what they are to
 * decide and touches nothing it did not recognise, so it cannot hide a
 * message, a call, or anything a person would miss.
 */
class DebugNotificationCleaner : NotificationListenerService() {

    override fun onListenerConnected() {
        connected = this
        // The banner is already up by the time we bind, so sweep what is there
        // rather than waiting for it to be posted again.
        runCatching { activeNotifications }.getOrNull()?.forEach { hideIfDebug(it) }
    }

    override fun onListenerDisconnected() {
        if (connected === this) connected = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { hideIfDebug(it) }
    }

    private fun hideIfDebug(sbn: StatusBarNotification) {
        // Checked on every post, not only at bind: a "no" can arrive while
        // this is still bound, and the component being disabled behind it is
        // not instant.
        if (!hiding(this)) return
        val extras = sbn.notification?.extras
        if (!isDebuggingBanner(
                sbn.packageName,
                sbn.id,
                sbn.notification?.channelId,
                extras?.getCharSequence("android.title")?.toString(),
                extras?.getCharSequence("android.text")?.toString(),
            )
        ) return

        runCatching { snoozeNotification(sbn.key, SNOOZE_MS) }
            .onSuccess { remember(this, sbn.key) }
            .onFailure { Log.w(TAG, "debug-notif: could not snooze the banner", it) }
    }

    /**
     * Bring back every banner this snoozed, by snoozing it again for a moment:
     * there is no public unsnooze, but a new snooze replaces the old one's
     * duration. Called on a change of mind, before the component goes off.
     */
    private fun restore() {
        val prefs = Prefs.of(this)
        prefs.getStringSet(KEY_SNOOZED, null).orEmpty().forEach { key ->
            runCatching { snoozeNotification(key, RESTORE_MS) }
                .onFailure { Log.w(TAG, "debug-notif: could not bring back $key", it) }
        }
        prefs.edit().remove(KEY_SNOOZED).apply()
    }

    companion object {
        private const val TAG = "JemRec"
        private const val SYSTEM_PACKAGE = "android"
        private const val DEBUG_WORD = "debugging"
        private const val SNOOZE_MS = 7L * 24 * 60 * 60 * 1000
        private const val RESTORE_MS = 3_000L
        private const val KEY_HIDE = "hide_debug_notification"
        private const val KEY_SNOOZED = "debug_notification_snoozed"

        /**
         * The debugging banners as Android numbers them: SystemMessage's
         * NOTE_ADB_ACTIVE (USB) and NOTE_ADB_WIFI_ACTIVE (wireless), posted on
         * the developer channels.
         */
        private val ADB_NOTIFICATION_IDS = setOf(26, 62)
        private val DEVELOPER_CHANNELS = setOf("DEVELOPER", "DEVELOPER_IMPORTANT")

        /** The bound listener, while there is one - restore() needs it. */
        @Volatile
        private var connected: DebugNotificationCleaner? = null

        /**
         * Whether a notification is one of the system's debugging banners.
         *
         * By what it IS first - its number and channel - because that holds in
         * every language. Matching only the English word "debugging" left the
         * banner up on a phone set to anything else, which a report rightly
         * called behaviour that changes by locale. The word stays as the
         * fallback for a ROM that numbers its banners differently.
         */
        internal fun isDebuggingBanner(
            packageName: String?,
            id: Int,
            channelId: String?,
            title: String?,
            text: String?,
        ): Boolean {
            if (packageName != SYSTEM_PACKAGE) return false
            if (id in ADB_NOTIFICATION_IDS && channelId in DEVELOPER_CHANNELS) return true
            return DEBUG_WORD in title.orEmpty().lowercase() ||
                DEBUG_WORD in text.orEmpty().lowercase()
        }

        /** Whether setup's question has been answered, either way. */
        fun asked(context: Context): Boolean = Prefs.of(context).contains(KEY_HIDE)

        /** Whether the answer was yes. */
        fun hiding(context: Context): Boolean = Prefs.of(context).getBoolean(KEY_HIDE, false)

        fun accessGranted(context: Context): Boolean =
            context.getSystemService(NotificationManager::class.java)
                .isNotificationListenerAccessGranted(component(context))

        /** The ADB shell command that grants notification access, or withdraws it. */
        fun accessCommand(context: Context, allow: Boolean): String =
            "cmd notification ${if (allow) "allow_listener" else "disallow_listener"} " +
                component(context).flattenToString()

        /** Record the answer and act on it at once. */
        fun choose(context: Context, hide: Boolean) {
            Prefs.of(context).edit().putBoolean(KEY_HIDE, hide).apply()
            apply(context)
        }

        /** Forget the answer, for Start fresh: setup asks again, and until
         *  then nothing is hidden. */
        fun forget(context: Context) {
            Prefs.of(context).edit().remove(KEY_HIDE).apply()
            apply(context)
        }

        /**
         * Make the listener match the answer. Safe to run on every open.
         *
         * On a yes, force it to bind. Access is granted over ADB, but this ROM
         * does not then bind the service on the grant alone - measured:
         * allow-listed, app running, and the banner still there. Toggling the
         * component's enabled state makes NotificationManagerService
         * re-evaluate and bind it; requestRebind on top. Both are no-ops until
         * access is actually granted, and every open is a fresh chance to
         * recover a listener the ROM killed.
         *
         * Otherwise bring back anything it hid and switch the component off,
         * so there is nothing bound to hide anything.
         */
        fun apply(context: Context) {
            runCatching {
                val cn = component(context)
                val pm = context.packageManager
                if (hiding(context)) {
                    pm.setComponentEnabledSetting(
                        cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                    pm.setComponentEnabledSetting(
                        cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                    requestRebind(cn)
                } else {
                    connected?.restore()
                    pm.setComponentEnabledSetting(
                        cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                }
            }.onFailure { Log.w(TAG, "debug-notif: could not apply the choice", it) }
        }

        private fun component(context: Context) =
            ComponentName(context, DebugNotificationCleaner::class.java)

        private fun remember(context: Context, key: String) {
            val prefs = Prefs.of(context)
            val keys = prefs.getStringSet(KEY_SNOOZED, null).orEmpty() + key
            prefs.edit().putStringSet(KEY_SNOOZED, keys).apply()
        }
    }
}
