package com.jemcik.jemrec.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Brings the monitor back after a reboot.
 *
 * This is the one broadcast worth relying on here. PHONE_STATE is dropped by
 * Honor's iAware when the app is not running, which is exactly when it would
 * matter - but BOOT_COMPLETED is delivered to stopped-but-installed apps by
 * design, and is one of the few cases where Android 12+ still permits starting
 * a foreground service from the background.
 *
 * LOCKED_BOOT_COMPLETED is handled too, and deliberately does nothing but log.
 * It arrives before the user has unlocked the phone for the first time, while
 * /data is still encrypted - measured after a real reboot, where `ls /data/data`
 * failed until unlock. The app's ADB identity lives there, so there is nothing
 * useful to do until BOOT_COMPLETED.
 *
 * What it starts is the keep-alive job, not a service. A reboot kills the
 * daemon and resets Wireless debugging to 0, so the daemon must be revived -
 * but that needs ADB and therefore Wi-Fi, which may not be up the instant boot
 * completes. The job kicks once immediately and then retries with backoff until
 * Wi-Fi arrives, and schedules the periodic heartbeat. No foreground service,
 * no notification, until an actual call.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_LOCKED_BOOT_COMPLETED ->
                Log.i(TAG, "boot: device booted but still locked; waiting for unlock")

            Intent.ACTION_BOOT_COMPLETED -> {
                // Revive whenever set up, regardless of the recorder switch: the
                // daemon must be alive so recording can be turned on later
                // without Wi-Fi. When the switch is off the daemon runs but
                // captures nothing (CallMonitorService gates that).
                if (!CaptureDaemon.isSetUp(context)) {
                    Log.i(TAG, "boot: JemRec is not set up, nothing to revive")
                    return
                }
                Log.i(TAG, "boot: arming the keep-alive job to revive the recorder")
                RecorderKeepAlive.schedule(context)
                RecorderKeepAlive.kickNow(context)
                // The system-held Wi-Fi watch is cleared by a reboot, so
                // re-arm it here to keep instant recovery working from boot on.
                NetworkRevive.arm(context)
            }
        }
    }

    private companion object {
        const val TAG = "JemRec"
    }
}
