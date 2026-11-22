package com.jemcik.jemrec.capture

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.jemcik.jemrec.recorder.Admit
import com.jemcik.jemrec.recorder.CableWatch
import com.jemcik.jemrec.recorder.RecorderLifecycle
import com.jemcik.jemrec.recorder.Trigger
import com.jemcik.jemrec.rethrowingCancellation
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The cable's watch, run as a job when Android says the USB state changed
 * (UsbStateReceiver): a USB mode chosen with the cable in kills the recorder
 * on some phones after the daemon's own cable watch has ended, and nothing
 * else hears of it.
 *
 * WHY A JOB, NOT THE CABLE'S SERVICE
 *
 * The daemon wakes CallMonitorService with `am`, from the shell, which may
 * start a foreground service. An app woken by a broadcast may not: USB_STATE
 * is not among Android's exemptions (developer.android.com, "Restrictions on
 * starting a foreground service from the background"). An expedited job runs
 * at once instead - a regular one once JemRec's expedited quota is spent -
 * and JobScheduler keeps the phone awake while it runs. So this shows no
 * "Checking the recorder" notification.
 *
 * WHAT IT DOES
 *
 * Asks setup and whether JemRec is standing down, as the USB debugging watch
 * does, then CableWatch.watch: looks at the recorder for three seconds
 * (CableWatch.STATE_WATCH_MS, where a cable's watch takes eight),
 * revives it if it went - with the cable's note only where adbd reads
 * stopped (CableWatch.notesStoppedAdbd) - three tries, then the keep-alive.
 * Three seconds, not one look: where a change kills, JemRec's
 * receiver ran 86 to 143 ms after the kill on the four emulators, and on the
 * Honor's charging only 61 ms after the configuration was set, as adbd went
 * (2026-11-22) - and the emulators set the old configuration again about a
 * second later, a second kill with no broadcast of its own.
 *
 * HOW IT IS QUEUED
 *
 * One name, decided one broadcast at a time on the keep-alive's own serial
 * executor, by the keep-alive's own rule (RecorderKeepAlive.queuePolicy): a
 * run waiting does the job, one running gets one run behind it, no more. A
 * real gadget sends three broadcasts to a change - configured, not, configured
 * again, within 2.6 s on the Honor - and each appended would have chained
 * three watches. The network it asks is the kicks' (runConstraints): the
 * revive reaches adbd over 127.0.0.1, which Android takes from an app idle in
 * the rare bucket or below unless its job asks for a network.
 */
class UsbStateWatch(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // Not set up; or JemRec switched all debugging off itself, by the
        // user's choice - and an off still pending sees here that the
        // recorder finished it.
        if (RecorderLifecycle.admit(applicationContext, Trigger.USB_STATE) !is Admit.Go) return Result.success()
        Log.i(TAG, "usbstate: the USB state changed, watching the recorder")
        // Success whatever it found, a failure too: the keep-alive owns the
        // retrying, and a run that ended failed would fail the one queued
        // behind it with it, as WorkManager fails a chain's dependents
        // (review round 1).
        runCatching { CableWatch.watch(applicationContext, "USB state changed", Trigger.USB_STATE) }
            .rethrowingCancellation()
            .onFailure { Log.w(TAG, "usbstate: the watch failed", it) }
        return Result.success()
    }

    companion object {
        private const val TAG = "JemRec"
        internal const val NAME = "jemrec.usbstate.watch"

        /**
         * How long a broadcast is held open for its queueing, all of it, from
         * onReceive: Android gives a foreground broadcast ten seconds,
         * counted on Android 12 and 13 from its dispatch, before JemRec's
         * process has even started, and the legacy queue there holds other
         * apps' receivers while JemRec's is open (BroadcastQueue,
         * android-13). Over it, an ANR - a background app is killed without
         * a word, and the run with it. Each wait capped alone added up past
         * ten seconds behind a busy executor (review round 1), so the
         * broadcast is let go at this deadline whatever the queueing is
         * doing - still waiting on the keep-alive's executor behind a kick's
         * getprop, or slow in its own - and the queueing goes on without it
         * (review round 2).
         */
        internal const val BUDGET_MS = 4_000L

        /**
         * A run, queued for one broadcast, on the keep-alive's serial
         * executor, within [budgetMs] from now. [done], once: when the run is
         * queued or given up, at the deadline if that comes first, or at
         * once if the queueing cannot even be handed over - a broadcast
         * never finished is an ANR at ten seconds (review round 2).
         */
        fun queue(context: Context, budgetMs: Long = BUDGET_MS, done: () -> Unit = {}) {
            val app = context.applicationContext
            val deadline = SystemClock.elapsedRealtime() + budgetMs
            val letGo = AtomicBoolean(false)
            val finish = { if (letGo.compareAndSet(false, true)) done() }
            Handler(Looper.getMainLooper()).postDelayed(finish, budgetMs)
            try {
                RecorderKeepAlive.onLooks {
                    try {
                        queueNow(app, deadline)
                    } finally {
                        finish()
                    }
                }
            } catch (e: RuntimeException) {
                Log.w(TAG, "usbstate: could not hand the queueing over", e)
                finish()
            }
        }

        /**
         * Runs appended since what is under the name was last read
         * ([policy]). On the keep-alive's executor only, one at a time; a
         * new process starts at none.
         */
        @androidx.annotation.VisibleForTesting
        internal var unreadAppends = 0

        /**
         * How a run is queued: by the keep-alive's rule where what is under
         * the name could be read ([under]), and appended where it could not -
         * one run more, never one lost: KEEP read from nothing dropped a run
         * meant to go behind a running one (review round 1). One such append
         * until the next read, not one per broadcast: each late broadcast
         * after it chained another three seconds of looks and expedited
         * quota for nothing - the run appended watches for them all
         * (review round 2).
         */
        internal fun policy(under: List<WorkInfo>?, asked: Constraints, unread: Int): ExistingWorkPolicy = when {
            under != null -> RecorderKeepAlive.queuePolicy(under, asked, ExistingWorkPolicy.APPEND_OR_REPLACE)
            unread == 0 -> ExistingWorkPolicy.APPEND_OR_REPLACE
            else -> ExistingWorkPolicy.KEEP
        }

        private fun queueNow(app: Context, deadline: Long) {
            fun left() = deadline - SystemClock.elapsedRealtime()
            val manager = WorkManager.getInstance(app)
            // The network asked, and whether JemRec is in front, are read
            // from Android here, off the receiver's main thread.
            val asked = RecorderKeepAlive.kickConstraints(app)
            val under = if (left() <= 0) null else {
                runCatching { manager.getWorkInfosForUniqueWork(NAME).get(left(), TimeUnit.MILLISECONDS) }.getOrNull()
            }
            val work = OneTimeWorkRequestBuilder<UsbStateWatch>()
                .setConstraints(asked)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            val policy = policy(under, asked, unreadAppends)
            val queued = runCatching { manager.enqueueUniqueWork(NAME, policy, work) }
                .onFailure { Log.w(TAG, "usbstate: could not queue the watch", it) }
                .getOrNull() ?: return
            unreadAppends = when {
                under != null -> 0
                policy == ExistingWorkPolicy.APPEND_OR_REPLACE -> unreadAppends + 1
                else -> unreadAppends
            }
            // Waited for while there is time: the broadcast held open, and
            // the process's standing with it, until WorkManager has written
            // it. (The next broadcast's look sees it either way: WorkManager
            // runs both on its own serial executor, in order - review round
            // 2.) Past the deadline it is written there all the same, the
            // broadcast already let go.
            if (left() > 0) {
                runCatching { queued.result.get(left(), TimeUnit.MILLISECONDS) }
                    .onFailure { Log.w(TAG, "usbstate: the watch's queueing not confirmed in time", it) }
            }
        }
    }
}
