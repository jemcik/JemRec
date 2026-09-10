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
        val alive = CaptureDaemon.revive(applicationContext)
        if (alive) {
            // Collect anything the daemon still holds - normally nothing, but a
            // crash or a reboot between hang-up and the fetch can strand a
            // recording. This is the app's safety net for exactly that; it
            // no-ops when there is nothing pending.
            runCatching { RecordingSaver.reconcile(applicationContext) }
                .onFailure { Log.w(TAG, "keepalive: reconcile failed", it) }
        }
        // retry(), not failure(): failure is terminal for a one-off, and a
        // daemon that could not be revived now - almost always no Wi-Fi - is
        // exactly the thing worth trying again shortly with backoff.
        return if (alive) Result.success() else Result.retry()
    }

    companion object {
        private const val TAG = "JemRec"
        private const val PERIODIC = "jemrec.keepalive.periodic"
        private const val IMMEDIATE = "jemrec.keepalive.now"

        /** The steady heartbeat. Idempotent - safe to call on every app open. */
        fun schedule(context: Context) {
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
         *  out a 15-minute period to come back after a reboot. */
        fun kickNow(context: Context) {
            val work = OneTimeWorkRequestBuilder<RecorderKeepAlive>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                IMMEDIATE, ExistingWorkPolicy.REPLACE, work,
            )
        }

        /** Switched off: stop trying to keep anything alive. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(PERIODIC)
            WorkManager.getInstance(context).cancelUniqueWork(IMMEDIATE)
        }
    }
}
