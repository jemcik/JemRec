package com.jemcik.jemrec.capture

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit

/**
 * Keeps the shell-side recorder alive, without the app staying alive to do it.
 *
 * WHAT THIS REPLACED
 *
 * The daemon used to be watched by a poll loop inside a PERMANENT foreground
 * service - which is the whole reason that service, and its permanent
 * notification, existed. Now the daemon watches call state itself and wakes the
 * app only for a call, so the app is dormant between calls and there is no
 * service running a loop.
 *
 * But the daemon can still die - a reboot kills it, and the system reclaims it
 * eventually (measured once at ~13 hours) - and a dead daemon records nothing
 * while nothing notices. So the one thing the watchdog genuinely had to do,
 * revive a dead daemon, becomes a scheduled job instead of a running loop.
 *
 * WHY WORKMANAGER AND NOT AN ALARM
 *
 * It survives reboot, it batches with the system's other maintenance work
 * rather than waking the phone alone, and it retries with backoff on its own.
 * The 15-minute floor on its period is fine: the daemon dying is rare, and a
 * call in the gap is the cost of not holding a permanent service - which is the
 * trade the user chose. Boot and app-open both kick an immediate one-off so the
 * common cases do not wait for the period.
 */
class RecorderKeepAlive(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Tied to setup, not the recorder switch: the daemon is kept alive
        // whenever this phone is set up, so turning recording off (and back on)
        // never needs the daemon torn down or a Wi-Fi trip to rebuild it. The
        // switch only gates capture, in CallMonitorService.
        if (!CaptureDaemon.isSetUp(applicationContext)) {
            Log.i(TAG, "keepalive: not set up, nothing to do")
            return Result.success()
        }
        // Off with debugging, by choice: no recorder to keep, and a retry
        // would only back off and come again. Only an off the recorder left
        // unfinished is worth a look - and the day's auto-delete, which needs
        // no recorder.
        if (DebugSwitchesOff.stayingOff(applicationContext)) {
            Log.i(TAG, "keepalive: debugging is off with JemRec, no recorder to keep")
            retention()
            return Result.success()
        }
        val forWifi = inputData.getBoolean(FOR_WIFI, false)
        ReviveLog.note(
            applicationContext,
            "keepalive: ${if (forWifi) "Wi-Fi run" else "run"} (attempt ${runAttemptCount + 1})",
        )
        // A force-stop clears the system's trigger with every other job; this
        // run is the next chance to put it back. KEEP, so a watch already
        // waiting is left alone.
        UsbDebuggingWatch.arm(applicationContext)
        val alive = CaptureDaemon.revive(applicationContext)
        // Off Wi-Fi is exactly when the Wi-Fi watch must be armed, and the
        // one place it reliably can be: armed on Wi-Fi it fires at once and
        // the system drops it. See NetworkRevive.
        val onWifi = NetworkRevive.onWifi(applicationContext)
        if (!onWifi) NetworkRevive.arm(applicationContext)
        if (alive) {
            // Collect anything the daemon still holds - normally nothing, but a
            // crash or a reboot between hang-up and the fetch can strand a
            // recording. This is the app's safety net for exactly that; it
            // no-ops when there is nothing pending.
            runCatching { RecordingSaver.reconcile(applicationContext) }
                .onFailure { Log.w(TAG, "keepalive: reconcile failed", it) }
            // After the reconcile, so a call it has just saved is not one this
            // reports as missed.
            runCatching { MissedCalls.check(applicationContext) }
                .onFailure { Log.w(TAG, "keepalive: missed-call check failed", it) }
        }
        retention()
        // retry(), not failure(): failure is terminal for a one-off, and a
        // daemon that could not be revived now - almost always no Wi-Fi - is
        // the thing worth trying again shortly with backoff. Except Wi-Fi's
        // run off Wi-Fi: the watch armed above starts the next (triesAgain).
        return if (triesAgain(alive, forWifi, onWifi)) Result.retry() else Result.success()
    }

    /** Auto-delete and the trash's expiry, once a day at most. Needs no
     *  recorder, so it runs either way - alive, not revivable, or off with
     *  debugging. */
    private fun retention() {
        runCatching { Retention.runIfDue(applicationContext) }
            .onFailure { Log.w(TAG, "keepalive: auto-delete failed", it) }
    }

    companion object {
        private const val TAG = "JemRec"
        private const val PERIODIC = "jemrec.keepalive.periodic"
        private const val IMMEDIATE = "jemrec.keepalive.now"
        private const val WIFI = "jemrec.keepalive.wifi"
        private const val FOR_WIFI = "for_wifi"

        /** Whether a run that ended with the recorder down tries again with
         *  backoff. A kick or the heartbeat does, Wi-Fi or not: the retry is
         *  what brings the recorder back if nothing else does. The Wi-Fi run
         *  does only while it is on Wi-Fi: off it, the watch is armed again
         *  (doWork) and will wake a new one, which a retry still waiting
         *  would be taken for, as the kick's was (kickForWifi). */
        internal fun triesAgain(alive: Boolean, forWifi: Boolean, onWifi: Boolean): Boolean =
            !alive && (!forWifi || onWifi)

        /** The steady heartbeat, and the watch on USB debugging that cuts it
         *  short when the phone switches that off (see UsbDebuggingWatch).
         *  Idempotent - safe to call on every app open. */
        fun schedule(context: Context) {
            UsbDebuggingWatch.arm(context)
            val work = PeriodicWorkRequestBuilder<RecorderKeepAlive>(
                15, TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                // KEEP: do not reset the clock every time the app opens, or the
                // period never actually elapses on a phone opened often.
                ExistingPeriodicWorkPolicy.KEEP,
                work,
            )
        }

        /** Right now, once - for boot and app-open, so the daemon does not wait
         *  out a 15-minute period to come back after a reboot. KEEP, not
         *  REPLACE: every open and resume kicks, and replacing cancelled a
         *  run mid-way - a reconcile between saving a call and deleting the
         *  daemon's copy, or a revive between stopping a daemon and starting
         *  the next. One already running does the same job. */
        fun kickNow(context: Context) = runOnce(context, IMMEDIATE, forWifi = false)

        /** Wi-Fi is back (NetworkReviveReceiver): a run of its own, not a
         *  kick. A kick that ran off Wi-Fi - an open away from home, a
         *  cable's or USB debugging's fallback - is still waiting out its
         *  retry, 30 s, then 60, 120 and on, and KEEP took the watch's kick
         *  for it. Measured on the Honor, 2026-10-31: Wi-Fi back at
         *  15:39:42, the watch's kick taken for the waiting one, the recorder
         *  up at 15:40:04 on that one's second try. The waits double, so
         *  from the sixth a waiting kick can be further off than the
         *  15-minute job (WorkManager's backoff, not measured). Its own
         *  name, so only an earlier Wi-Fi run is taken for it. A run
         *  alongside a kick or the heartbeat is safe: revive takes the
         *  recorder's lock, and reconcile claims each file. */
        fun kickForWifi(context: Context) = runOnce(context, WIFI, forWifi = true)

        /** One run under [name], KEEP: one already waiting or running is
         *  left to do the job. */
        private fun runOnce(context: Context, name: String, forWifi: Boolean) {
            val work = OneTimeWorkRequestBuilder<RecorderKeepAlive>()
                .setInputData(workDataOf(FOR_WIFI to forWifi))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, work)
        }

        /** Switched off: stop trying to keep anything alive. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE)
            WorkManager.getInstance(context).cancelUniqueWork(WIFI)
            UsbDebuggingWatch.cancel(context)
        }
    }
}
