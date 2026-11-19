package com.jemcik.jemrec.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jemcik.jemrec.recorder.RecorderLifecycle
import com.jemcik.jemrec.recorder.Trigger

/**
 * Brings the monitor back after a reboot.
 *
 * The broadcast most worth relying on here. PHONE_STATE is dropped by
 * Honor's iAware when the app is not running, which is exactly when it would
 * matter; BOOT_COMPLETED reaches an app that is not stopped, and is one of
 * the few cases where Android 12+ still permits starting a foreground
 * service from the background. Not every app: one the user force-stopped (or
 * never opened) is stopped, and broadcasts have left stopped apps out since
 * Android 3.1 (FLAG_INCLUDE_STOPPED_PACKAGES, which the boot intent does not
 * carry). What Android 15 added: an app entering the stopped state has all
 * its pending intents cancelled - NetworkRevive's Wi-Fi watch among them -
 * and "When the user's actions remove the app from the stopped state, the
 * ACTION_BOOT_COMPLETED broadcast is delivered to the app" (developer.
 * android.com, Android 15 behavior changes, "Changes to package stopped
 * state"), which re-arms the watch here. And for an app whose
 * Battery is Restricted, Android 13 and later hold it until the app is
 * started for another reason (developer.android.com, Background
 * optimization) - BatteryHealth says what that costs. What it does at a
 * restart is not measured: the shell's Restricted does not outlive one on
 * the AVDs (2026-11-18), and a recorder back 114 s after one, measured
 * earlier, was the setting already gone.
 *
 * It can be late, and only Android decides: on a Galaxy S20 locked again
 * straight after the first unlock, 2026-11-04, it came 4 min 46 s after the
 * unlock, the phone asleep for most of them.
 *
 * Not LOCKED_BOOT_COMPLETED: that comes before the first unlock, while /data
 * is still encrypted - measured after a real reboot, where `ls /data/data`
 * failed until unlock - and only to components marked directBootAware, which
 * this is not: the app's ADB identity lives in /data, so there is nothing
 * useful to do until BOOT_COMPLETED. (A filter for it, and a branch that only
 * logged, were here until 1.2; neither could ever run.)
 *
 * What it starts is the keep-alive job, not a service. A reboot kills the
 * daemon, and leaves adbd without Wireless debugging: switched off on some
 * phones, and on the Honor (MagicOS 11, 2026-10-28) left on with adbd not
 * started - the revive switches it on, or off and on (CaptureDaemon). So the
 * daemon must be revived - but that needs ADB and therefore Wi-Fi, which may
 * not be up the instant boot completes. The job kicks once immediately and
 * then retries with backoff until Wi-Fi arrives, and schedules the periodic
 * heartbeat. No foreground service, no notification, until an actual call.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED -> {
                // Revive whenever set up, regardless of the recorder switch: the
                // daemon must be alive so recording can be turned on later
                // without Wi-Fi. When the switch is off the daemon runs but
                // captures nothing (CallMonitorService gates that).
                if (!RecorderLifecycle.goes(context, Trigger.BOOT)) {
                    Log.i(TAG, "boot: JemRec is not set up, nothing to revive")
                    return
                }
                Log.i(TAG, "boot: arming the keep-alive job to revive the recorder")
                // The heartbeat, the USB watch and a kick; and the system-held
                // Wi-Fi watch, which a reboot clears, to keep instant recovery
                // working from boot on.
                RecorderLifecycle.arm(context, Trigger.BOOT)
            }
        }
    }

    private companion object {
        const val TAG = "JemRec"
    }
}
