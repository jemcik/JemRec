package com.jemcik.jemrec.capture

import android.content.Context
import android.provider.Settings
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration

/**
 * Wakes the app the moment USB debugging changes, so a recorder killed by it is
 * back in seconds instead of at the next keep-alive.
 *
 * WHY: THE PHONE'S OWN SETTINGS APP SWITCHES IT OFF
 *
 * Measured on the Honor (MagicOS 11, Android 17), 2026-10-26, no cable: showing
 * the Developer options screen - opening it, and again coming back to it from
 * Wireless debugging - had the phone's Settings app write adb_enabled=0 by
 * itself (logcat: "[SPMN] insert GLOBAL -- name = adb_enabled, package =
 * com.android.settings", 21 ms after the screen was created and 1.2 s after the
 * return; the user saw the switch off both times without touching it). adbd
 * stopped at once, with Wireless debugging on or not, and the recorder, which
 * lives in adbd's cgroup, went with it. The daemon's shield (keepShieldUp) cannot
 * answer that: it dies in the same instant. Nothing told the app. The recorder
 * was back 46 s later from a keep-alive run off the 15-minute schedule, as the
 * user's Wi-Fi came back on; with nothing to wake it, it would have waited for
 * the next scheduled run.
 *
 * HOW
 *
 * A content-URI trigger on the setting. The system watches it with nothing of
 * ours running and starts the app on a change - the same shape as NetworkRevive's
 * PendingIntent, for this event. A trigger fires once, so each run arms the next
 * before it does anything else. The run is the keep-alive's revive: it puts
 * USB debugging back, and starts the recorder again if it died. Our own writes
 * of the setting (the shield, revive's armShield) fire it too; it then finds
 * the recorder up, which is one loopback ping.
 *
 * WHAT THAT BUYS ON THE HONOR
 *
 * Measured the same day. With the cable in, the watch ran 0.5 s after a write
 * and the recorder answered about 6 s later. Off the cable, the phone's own job
 * condition (HN_USER_EXPERIENCE in `dumpsys jobscheduler`) holds every
 * background app's jobs while the phone is in use - 484 of them flipped at
 * once - and lifts it for the app in front, whatever App launch says. So with
 * the user still in Settings the watch waits until JemRec is opened; with the
 * phone locked right after, it ran 30 s after the switch-off and the recorder
 * was back at 37 s. The 15-minute job sits under the same hold, and after it
 * waits for its slot too.
 *
 * Off Wi-Fi this cannot bring the recorder back, because starting it needs
 * Wireless debugging and that needs Wi-Fi. The keep-alive arms the Wi-Fi watch
 * for that case, as it does whenever it runs off Wi-Fi.
 */
class UsbDebuggingWatch(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // The next change needs a watch of its own. APPEND_OR_REPLACE, because
        // this run still holds the name: KEEP would see it and arm nothing, and
        // REPLACE would cancel this run.
        arm(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        if (!CaptureDaemon.isSetUp(applicationContext)) return Result.success()
        // JemRec switched USB debugging off itself, by the user's choice -
        // and an off still pending sees here that the recorder finished it.
        if (DebugSwitchesOff.stayingOff(applicationContext)) return Result.success()
        Log.i(TAG, "usbwatch: USB debugging changed, checking the recorder")
        ReviveLog.note(applicationContext, "usbwatch: USB debugging changed")
        // The revive itself, not a kick of the keep-alive: a kick is KEEP, and
        // a keep-alive still waiting out the backoff of a revive that failed
        // earlier (off Wi-Fi, say) swallows it - measured on the Android 17
        // emulator, where USB debugging then stayed off past 90 s. revive()
        // takes the recorder's lifecycle lock, so it never runs alongside
        // another one, and it puts USB debugging back before anything else.
        if (!CaptureDaemon.revive(applicationContext)) {
            // Down and not startable now: the keep-alive owns the retrying,
            // and off Wi-Fi the Wi-Fi watch the moment there is some.
            if (!NetworkRevive.onWifi(applicationContext)) NetworkRevive.arm(applicationContext)
            RecorderKeepAlive.kickNow(applicationContext)
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "JemRec"
        private const val NAME = "jemrec.usbdebugging.watch"

        // The floors JobScheduler allows (JobStatus.MIN_TRIGGER_UPDATE_DELAY and
        // MIN_TRIGGER_MAX_DELAY). Left unset they are 10 s and 2 min, which is
        // time a call can ring through.
        internal val UPDATE_DELAY: Duration = Duration.ofMillis(500)
        internal val MAX_DELAY: Duration = Duration.ofSeconds(1)

        /** What wakes the watch: a change to USB debugging, and nothing else. */
        internal fun constraints(): Constraints = Constraints.Builder()
            .addContentUriTrigger(Settings.Global.getUriFor(GlobalSettings.ADB_ENABLED), false)
            .setTriggerContentUpdateDelay(UPDATE_DELAY)
            .setTriggerContentMaxDelay(MAX_DELAY)
            .build()

        /** Idempotent with the default KEEP: safe on every app open and every
         *  keep-alive run. */
        fun arm(context: Context, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
            val work = OneTimeWorkRequestBuilder<UsbDebuggingWatch>()
                .setConstraints(constraints())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, policy, work)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(NAME)
        }
    }
}
