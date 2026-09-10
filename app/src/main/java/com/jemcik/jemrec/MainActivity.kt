package com.jemcik.jemrec

import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jemcik.jemrec.capture.DebugNotificationCleaner
import com.jemcik.jemrec.capture.RecorderKeepAlive
import com.jemcik.jemrec.capture.RecorderSwitch
import com.jemcik.jemrec.ui.MainScreen
import com.jemcik.jemrec.ui.theme.JemRecTheme

/**
 * Single activity. It owns the theme decision and nothing else - the whole of
 * the interesting parts are the transport and the daemon, and an activity that grows logic is an activity
 * that has started keeping state the transport should own.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Opening the app no longer starts a permanent service. It arms the
        // keep-alive job, which revives the daemon if it is down and keeps it
        // that way. Idempotent, and a no-op while switched off.
        if (RecorderSwitch.isOn(this)) {
            RecorderKeepAlive.schedule(this)
            RecorderKeepAlive.kickNow(this)
        }

        bindNotificationCleaner()

        setContent {
            JemRecTheme {
                MainScreen()
            }
        }
    }

    /**
     * Force the notification-cleaner listener to bind.
     *
     * Access is granted over ADB during setup, but this ROM does not then bind
     * the service on the grant alone - measured: allow-listed, app running, and
     * the banner still there. Toggling the component's enabled state makes
     * NotificationManagerService re-evaluate and bind it; requestRebind on top.
     * Both are no-ops until access is actually granted, so this is safe to run
     * on every open, and every open is a fresh chance to recover a listener the
     * ROM killed.
     */
    private fun bindNotificationCleaner() {
        runCatching {
            val cn = ComponentName(this, DebugNotificationCleaner::class.java)
            packageManager.setComponentEnabledSetting(
                cn, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            packageManager.setComponentEnabledSetting(
                cn, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            NotificationListenerService.requestRebind(cn)
        }
    }
}
