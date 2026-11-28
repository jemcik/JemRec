package com.jemcik.jemrec.capture

import android.content.Context
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.jemcik.jemrec.calls.MissedCalls
import com.jemcik.jemrec.recorder.Admit
import com.jemcik.jemrec.recorder.RecorderLifecycle
import com.jemcik.jemrec.recorder.ReviveLog
import com.jemcik.jemrec.recorder.Trigger
import com.jemcik.jemrec.recordings.RecordingSaver
import com.jemcik.jemrec.recordings.Retention
import com.jemcik.jemrec.rethrowingCancellation
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
        //
        // Off with debugging, by choice: no recorder to keep, and a retry
        // would only back off and come again. Only an off the recorder left
        // unfinished is worth a look - and the day's auto-delete, which needs
        // no recorder.
        // The Battery setting read at each run: lifted - from the
        // notification's own button, say, which does not clear it - what
        // was said of it goes (BatteryHealth.read). Android asked: a run may
        // come while it is still set, JemRec on the screen.
        runCatching { com.jemcik.jemrec.system.BatteryHealth.read(applicationContext) }
            .onFailure { Log.w(TAG, "keepalive: battery setting not read", it) }
        when (RecorderLifecycle.admit(applicationContext, Trigger.KEEP_ALIVE)) {
            Admit.Skip -> {
                Log.i(TAG, "keepalive: not set up, nothing to do")
                return Result.success()
            }
            Admit.StandDown -> {
                Log.i(TAG, "keepalive: debugging is off with JemRec, no recorder to keep")
                retention()
                return Result.success()
            }
            is Admit.Go -> Unit
        }
        val forWifi = inputData.getBoolean(FOR_WIFI, false)
        ReviveLog.note(
            applicationContext,
            "keepalive: ${if (forWifi) "Wi-Fi run" else "run"} (attempt ${runAttemptCount + 1})",
        )
        // A force-stop clears the system's trigger with every other job; this
        // run is the next chance to put it back. KEEP, so a watch already
        // waiting is left alone.
        RecorderLifecycle.arm(applicationContext, Trigger.KEEP_ALIVE)
        val alive = CaptureDaemon.revive(applicationContext)
        // The Default USB configuration asked of the recorder, up, and said
        // once if it stops the recorder at every lock: the run is where
        // JemRec learns of it while nobody opens it (UsbDefault).
        if (alive) {
            runCatching { com.jemcik.jemrec.system.UsbDefault.check(applicationContext) }.rethrowingCancellation()
                .onFailure { Log.w(TAG, "keepalive: the USB default not read", it) }
        }
        // Off Wi-Fi is exactly when the Wi-Fi watch must be armed, and the
        // one place it reliably can be: armed on Wi-Fi it fires at once and
        // the system drops it. See NetworkRevive.
        // The heartbeat's runs are told from the kicks' by its tag: it never
        // tries again (triesAgain).
        val next = RecorderLifecycle.followUp(applicationContext, Trigger.KEEP_ALIVE, alive, forWifi, periodic = PERIODIC in tags)
        if (next.collect) {
            // Collect anything the daemon still holds - normally nothing, but a
            // crash or a reboot between hang-up and the fetch can strand a
            // recording. This is the app's safety net for exactly that; it
            // no-ops when there is nothing pending.
            runCatching { RecordingSaver.reconcile(applicationContext) }.rethrowingCancellation()
                .onFailure { Log.w(TAG, "keepalive: reconcile failed", it) }
            // After the reconcile, so a call it has just saved is not one this
            // reports as missed.
            runCatching { MissedCalls.check(applicationContext) }.rethrowingCancellation()
                .onFailure { Log.w(TAG, "keepalive: missed-call check failed", it) }
        }
        retention()
        // retry(), not failure(): failure is terminal for a one-off, and a
        // daemon that could not be revived now - almost always no Wi-Fi - is
        // the thing worth trying again shortly with backoff. Except Wi-Fi's
        // run off Wi-Fi, and the heartbeat, whose backoff would put its next
        // run hours away - a day off Wi-Fi did, and a recorder that died once
        // home again waited that long (triesAgain, which says why).
        return if (next.retry) Result.retry() else Result.success()
    }

    /** Auto-delete and the trash's expiry, once a day at most, and stale
     *  share copies. Needs no recorder, so it runs either way - alive, not
     *  revivable, or off with debugging. */
    private fun retention() {
        runCatching { Retention.runIfDue(applicationContext) }
            .onFailure { Log.w(TAG, "keepalive: auto-delete failed", it) }
        runCatching { com.jemcik.jemrec.recordings.OldRecordingPages.runIfDue(applicationContext) }
            .onFailure { Log.w(TAG, "keepalive: mending old recordings' pages failed", it) }
        // Every run, not once a day: a stale copy of a call goes within a
        // heartbeat of going stale (ShareCopies).
        runCatching { com.jemcik.jemrec.recordings.ShareCopies.sweep(applicationContext) }
            .onFailure { Log.w(TAG, "keepalive: sweeping share copies failed", it) }
    }

    companion object {
        private const val TAG = "JemRec"
        private const val PERIODIC = "jemrec.keepalive.periodic"
        private const val IMMEDIATE = "jemrec.keepalive.now"

        /** A call's end's own kick ([kickAfterCall]): under a name of its own,
         *  so a run under [IMMEDIATE] begun before the call ended - its
         *  reconcile left for the call - never takes it for itself (KEEP)
         *  and ends with the call's files uncollected (review rounds 26, 27:
         *  a second reconcile at such a run's end could take the call's file
         *  before the hang-up's own save claimed it); and queued behind one
         *  of its own name still running, never dropped (round 28). */
        private const val AFTER_CALL = "jemrec.keepalive.aftercall"
        private const val WIFI = "jemrec.keepalive.wifi"
        private const val FOR_WIFI = "for_wifi"

        /** Whether a run that ended with the recorder down tries again with
         *  backoff: the rule is the lifecycle's (Triggers.kt), where a run's
         *  follow-up is decided. */
        internal fun triesAgain(alive: Boolean, forWifi: Boolean, onWifi: Boolean, periodic: Boolean = false): Boolean =
            com.jemcik.jemrec.recorder.triesAgain(alive, forWifi, onWifi, periodic)

        /** The steady heartbeat, and the watch on USB debugging that cuts it
         *  short when the phone switches that off (see UsbDebuggingWatch).
         *  Idempotent. The app arms these two through the trigger table
         *  (RecorderLifecycle.arm), which calls the same two in this order;
         *  this is the tests' way to arm them, as boot and the app did. */
        fun schedule(context: Context) {
            UsbDebuggingWatch.arm(context)
            heartbeat(context)
        }

        /** The steady heartbeat alone (RecorderLifecycle.arm arms the watch
         *  beside it). Idempotent. */
        fun heartbeat(context: Context) {
            val work = PeriodicWorkRequestBuilder<RecorderKeepAlive>(
                15, TimeUnit.MINUTES,
            ).addTag(PERIODIC).setConstraints(constraints(context)).build()
            // Once, from 1.2: the heartbeat there before it asked to retry,
            // and may be hours into its backoff - which UPDATE would keep, as
            // it keeps the run's attempt count (WorkManager's WorkerUpdater) -
            // so it is put anew, with the tag its runs are told apart by.
            val prefs = Prefs.of(context)
            val current = prefs.getInt(KEY_HEARTBEAT, 0) >= HEARTBEAT_VERSION
            // Built for Start without Wi-Fi as it is now: switched since -
            // in Settings, or by Start fresh, which turns it off - UPDATE,
            // which keeps the period going and sets the constraints anew
            // (WorkManager's WorkerUpdater). Kept, a heartbeat built with
            // the switch on asked for no network for good (review,
            // 2026-11-18).
            val port = com.jemcik.jemrec.recorder.StartWithoutWifi.enabled(context)
            val builtFor = prefs.getBoolean(KEY_HEARTBEAT_PORT, false)
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                // KEEP: do not reset the clock every time the app opens, or the
                // period never actually elapses on a phone opened often.
                when {
                    !current -> ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE
                    builtFor != port -> ExistingPeriodicWorkPolicy.UPDATE
                    else -> ExistingPeriodicWorkPolicy.KEEP
                },
                work,
            )
            prefs.edit {
                if (!current) putInt(KEY_HEARTBEAT, HEARTBEAT_VERSION)
                putBoolean(KEY_HEARTBEAT_PORT, port)
            }
        }

        /** Start without Wi-Fi switched: the heartbeat and the USB watch
         *  queued now ask for a network, or none ([constraints]). */
        fun startWithoutWifiChanged(context: Context) {
            heartbeat(context)
            UsbDebuggingWatch.arm(context, ExistingWorkPolicy.REPLACE)
        }

        /** The heartbeat from before 1.2 put anew, at the process's start,
         *  on a phone set up (the heartbeat is setup's): [heartbeat] runs at
         *  an open, a boot or setup's check, and until one came a phone not
         *  opened after the update kept the old heartbeat, retrying hours
         *  apart (review, 2026-11-18). Nothing once it is the new one. */
        fun renewOldHeartbeat(context: Context) {
            if (Prefs.of(context).getInt(KEY_HEARTBEAT, 0) >= HEARTBEAT_VERSION) return
            if (!com.jemcik.jemrec.setup.Setup.isComplete(context)) return
            heartbeat(context)
        }

        /** Which heartbeat is queued: 1, the one that does not retry; 2, the
         *  one that asks for a network; 3, for any network, not validated
         *  internet ([NETWORK]) - all 1.2. */
        private const val KEY_HEARTBEAT = "heartbeat_version"
        private const val HEARTBEAT_VERSION = 3

        /** Whether the heartbeat queued was built with Start without Wi-Fi
         *  on ([constraints]). */
        private const val KEY_HEARTBEAT_PORT = "heartbeat_port"

        /**
         * A network, asked of every keep-alive run. The revive reaches adbd
         * and the recorder over 127.0.0.1, and Android takes the network -
         * the loopback with it - from an app idle in the background on
         * battery once it is in the rare standby bucket or below
         * (developer.android.com, Power management restrictions: Rare and
         * Restricted, "Network: Disabled"), and gives it back to a job only
         * if it asks for one: an expedited job will "bypass Doze, app
         * standby, and battery saver network restrictions (if the job has a
         * setRequiredNetwork(NetworkRequest))" (JobInfo.Builder.setExpedited),
         * and a regular one with a network constraint is let through app
         * standby (AOSP's ConnectivityController). Without it, measured on
         * the Android 17 AVD, 2026-11-18, in the restricted bucket, JemRec
         * idle, the battery unplugged - and again with the emulator's charger
         * in, though whether Android took that for charging, which lifts
         * the standby firewall (AOSP's AppStandbyController), was not read:
         * the run started and every step of its revive timed out, 15 to 18 s
         * each (review). With it, the same on the Android 12 and 17 AVDs:
         * back 4 s after a kick. Any network does: Wireless
         * debugging needs Wi-Fi, and a run off Wi-Fi - on mobile data - is
         * what arms the Wi-Fi watch; with none at all the run waits for one,
         * where it would have found no Wi-Fi anyway - unless Start without
         * Wi-Fi is on, whose runs ask for none ([constraints]).
         *
         * A network request of its own, not NetworkType.CONNECTED: WorkManager
         * turns that into JobScheduler's NETWORK_TYPE_ANY
         * (SystemJobInfoConverter, 2.12.0), which asks for validated
         * internet - the Android 12 AVD's dump, 2026-11-18, read
         * "INTERNET&NOT_RESTRICTED&TRUSTED&VALIDATED". A Wi-Fi without
         * internet - a car's, another phone's hotspot with no data - is
         * never validated, and with no mobile data the keep-alive would
         * never have run there, where Wireless debugging works and
         * ARCHITECTURE promises it does ("Any joined Wi-Fi counts") - read
         * from the dump and the request, not produced. The
         * request below is NetworkRequest.Builder's own, VPNs allowed - not
         * restricted and trusted, no internet asked - and WorkManager hands
         * it to JobScheduler as it is (JobInfo.Builder.setRequiredNetwork).
         * Measured on the Android 17 AVD, 2026-11-18: its Wi-Fi not
         * validated (Android's checks pointed at nothing), mobile data off -
         * the jobs' network met, and a kick had an ended recorder back in
         * 4 s, over Wireless debugging on that Wi-Fi.
         */
        // Made at first use, not with the class: a NetworkRequest is
        // Android's, and the plain-JVM tests of this class's rules
        // (RecorderKeepAliveTest) have none.
        private val NETWORK by lazy { Constraints.Builder().setRequiredNetworkRequest(anyNetwork(), NetworkType.CONNECTED).build() }

        /**
         * The heartbeat's and the USB watch's: [NETWORK] where a revive
         * needs one anyway, none where it may not. Wireless debugging works
         * only on a joined Wi-Fi, so asking costs a revive over it nothing:
         * with no network it could not have succeeded. With Start without
         * Wi-Fi on, a revive goes over adbd's TCP port with no network at
         * all - no Wi-Fi and mobile data off, as a traveller has it, while
         * calls still come - and a run that waited for a network never came
         * there (reviews, 2026-11-18). The trade, said: such a run in the
         * rare or restricted standby bucket, JemRec idle on battery, finds no
         * loopback and its revive times out, as every run did before 1.2. A
         * one-time run is decided as it is queued ([runConstraints]).
         */
        internal fun constraints(context: Context): Constraints =
            if (com.jemcik.jemrec.recorder.StartWithoutWifi.enabled(context)) Constraints.NONE else NETWORK

        /**
         * A one-time run's: [NETWORK], but for a kick with Start without
         * Wi-Fi's port open and no network there as it is queued - that one
         * none, as only the port can be had. The Wi-Fi run always asks, as
         * it comes only once a Wi-Fi has joined, and so does a kick at a
         * restart, the port closed until a Wi-Fi opens it again: asking for
         * none there lost the network's exemption - in the buckets, under
         * Battery Saver and Doze, for an expedited job ("bypass Doze, app
         * standby, and battery saver network restrictions (if the job has a
         * setRequiredNetwork(NetworkRequest))") - for nothing (review,
         * 2026-11-18).
         */
        internal fun runConstraints(context: Context, forWifi: Boolean, inFront: Boolean = false): Constraints {
            // JemRec in front, with no network at all: its kick asks for none.
            // Loopback is never taken from an app in front ("visible or in
            // foreground state: Network: No restrictions", Power management
            // restrictions), and a kick that waited for a network held back
            // the reconcile, auto-delete and the missed-call check with it -
            // and, kept, every kick after it (review round 9).
            if (inFront && !forWifi && !networkNow(context)) return Constraints.NONE
            if (forWifi || !com.jemcik.jemrec.recorder.StartWithoutWifi.enabled(context)) return NETWORK
            if (!com.jemcik.jemrec.recorder.StartWithoutWifi.ready(context)) return NETWORK
            return if (networkNow(context)) NETWORK else Constraints.NONE
        }

        /** Whether a network of the kind [anyNetwork] asks for is up now: one
         *  not restricted - not the phone's own IMS one, say - and trusted. */
        private fun networkNow(context: Context): Boolean = runCatching {
            val cm = context.getSystemService(android.net.ConnectivityManager::class.java) ?: return true
            @Suppress("DEPRECATION") // allNetworks: its replacement is a callback, and this is asked in passing
            cm.allNetworks.any { network ->
                cm.getNetworkCapabilities(network)?.let {
                    it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED) &&
                        it.hasCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                } == true
            }
        }.getOrDefault(true)

        /** [NETWORK]'s request, for the USB watch too: any network not
         *  restricted, VPNs allowed - no internet asked, proved or not. */
        internal fun anyNetwork(): NetworkRequest =
            NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build()

        /** Right now, once - for boot and app-open, so the daemon does not wait
         *  out a 15-minute period to come back after a reboot. KEEP, not
         *  REPLACE: every open and resume kicks, and replacing cancelled a
         *  run mid-way - a reconcile between saving a call and deleting the
         *  daemon's copy, or a revive between stopping a daemon and starting
         *  the next. One already running does the same job - but a
         *  reconcile it left for a call up: a call's end kicks under a name
         *  of its own ([kickAfterCall]); one waiting out a retry is replaced
         *  ([runOnce]). */
        fun kickNow(context: Context, inFront: Boolean? = null) =
            runOnce(context, IMMEDIATE, forWifi = false, inFront = inFront)

        /** A call's end, in front still as the call's service: a run of its
         *  own name, after any run already going ([AFTER_CALL]). */
        fun kickAfterCall(context: Context) =
            runOnce(context, AFTER_CALL, forWifi = false, inFront = true)

        /**
         * Whether JemRec's process is in front now - an activity on the
         * screen, or a foreground service: network is not taken from a
         * process at PROCESS_STATE_BOUND_FOREGROUND_SERVICE or above - the
         * same limit in Doze, Battery Saver and the standby buckets' chain
         * (AOSP NetworkPolicyManager.isProcStateAllowedWhileIdleOrPowerSaveMode,
         * NetworkPolicyManagerService.isUidForegroundOnRestrictPowerUL).
         * Asked as each kick is made, not told by its trigger: only the
         * open's was, and the resume's check, made with JemRec on the screen,
         * asked for a network and replaced the open's (review round 10). Read
         * on the kicks' own executor, not the caller's thread: a binder call,
         * and the open's is the main thread's before its first frame (review
         * round 11). A test seam.
         */
        @androidx.annotation.VisibleForTesting
        internal var inFrontNow: (Context) -> Boolean = {
            runCatching {
                val info = android.app.ActivityManager.RunningAppProcessInfo()
                android.app.ActivityManager.getMyMemoryState(info)
                info.importance <= android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND_SERVICE
            }.getOrDefault(false)
        }

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

        /** One run under [name], KEEP - but a call's end's ([AFTER_CALL]),
         *  appended behind one running with none behind it: one already waiting or running is
         *  left to do the job - but not one waiting out a retry's backoff,
         *  which doubles from 30 s to 5 h and outlives a restart in
         *  WorkManager's database: kept, it took the boot's, an open's and
         *  the cable's kicks for itself, hours off (review, 2026-11-18). That
         *  one is replaced; one running never is. Between the look and the
         *  enqueue a retry may start, and is then cancelled: what a run must
         *  finish once begun - a revive, a save's publish - is NonCancellable
         *  (Revive.reviveLocked, RecordingSaver.publish).
         *  Expedited, with a network asked but where Start without Wi-Fi's
         *  port can do without one ([runConstraints]):
         *  in the restricted standby bucket - an app left unused for days, as
         *  JemRec is between calls by design - a regular job runs "once per
         *  day for up to 10 minutes", an expedited one within "up to 5
         *  minutes in a rolling 24h window" (Power management restrictions),
         *  and a revive takes seconds. Out of that quota it runs as a regular
         *  one; a retry too, as WorkManager never expedites a retry nor a
         *  job with a delay ("Retries cannot be expedited jobs",
         *  SystemJobInfoConverter). The user's Battery
         *  setting Restricted runs none while JemRec is in the background -
         *  an open's kick, JemRec in front, does run (BatteryHealth). */
        private fun runOnce(context: Context, name: String, forWifi: Boolean, inFront: Boolean? = false) {
            // A call's end's kick is never dropped: behind one already under
            // its name (APPEND_OR_REPLACE - "append the newly-specified work
            // as the child of all the leaves", ExistingWorkPolicy), as that
            // one's reconcile may have met the next call (review round 28).
            val notReplaced = if (name == AFTER_CALL) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP
            val app = context.applicationContext
            // Read where the run's ask is: on `looks`, below.
            fun front() = inFront ?: inFrontNow(app)
            // Off the caller's thread: what the run asks for is read from
            // Android - a getprop process for the port, the networks - and
            // an open made it on the main thread before its first frame
            // (review round 9).
            fun work(asked: Constraints = runConstraints(app, forWifi, front())) = OneTimeWorkRequestBuilder<RecorderKeepAlive>()
                .setInputData(workDataOf(FOR_WIFI to forWifi))
                .setConstraints(asked)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            val manager = WorkManager.getInstance(context)
            // One look at a time for a name: two kicks close together - an
            // open arms two - both found the retry and both replaced, the
            // second cancelling the first's fresh kick (review, 2026-11-18).
            // One that comes while another looks is kept behind it (KEEP; a
            // call's end's appended),
            // queued after the look on WorkManager's own serial executor.
            if (!deciding.add(name)) {
                looks.execute { runCatching { manager.enqueueUniqueWork(name, notReplaced, work()) } }
                return
            }
            val under = manager.getWorkInfosForUniqueWork(name)
            under.addListener({
                val asked = runConstraints(app, forWifi, front())
                val work = work(asked)
                val policy = queuePolicy(runCatching { under.get() }.getOrNull().orEmpty(), asked, notReplaced)
                val queued = runCatching {
                    manager.enqueueUniqueWork(name, policy, work)
                }.getOrNull()
                if (queued == null) {
                    deciding.remove(name)
                } else {
                    queued.result.addListener({ deciding.remove(name) }, { it.run() })
                }
            }, looks)
        }

        /**
         * How a one-time run asking for [asked] is queued under a name whose
         * work is [under] ([runOnce], UsbStateWatch). Replaced: one waiting
         * out a retry, and one never run whose network ask no longer fits -
         * asking for a network that went, it absorbed every kick after it
         * (review round 9). Put in [behind] - appended, for a call's end's
         * kick and a USB state's run - only behind one running with none
         * behind it yet: one waiting does the job itself, and a chain is no
         * queue of redundant runs (review round 29). Else kept.
         */
        internal fun queuePolicy(under: List<WorkInfo>, asked: Constraints, behind: ExistingWorkPolicy): ExistingWorkPolicy {
            val replace = under.any {
                it.state == WorkInfo.State.ENQUEUED &&
                    (it.runAttemptCount > 0 || asksNetwork(it.constraints) != asksNetwork(asked))
            }
            val states = under.map { it.state }
            val goesBehind = WorkInfo.State.RUNNING in states &&
                states.none { it == WorkInfo.State.BLOCKED || it == WorkInfo.State.ENQUEUED }
            return when {
                replace -> ExistingWorkPolicy.REPLACE
                goesBehind -> behind
                else -> ExistingWorkPolicy.KEEP
            }
        }

        /** A one-time run's network ask as a kick's is decided ([runConstraints]),
         *  JemRec in front or not read now - for a USB state's run (UsbStateWatch).
         *  Reads Android: not on the main thread. */
        internal fun kickConstraints(context: Context): Constraints =
            runConstraints(context, forWifi = false, inFront = inFrontNow(context))

        /** [block] on [looks], where kicks are decided one at a time - a USB
         *  state's run too (UsbStateWatch). */
        internal fun onLooks(block: () -> Unit) = looks.execute(block)

        /** Whether [c] asks for a network: from Android 9 WorkManager keeps
         *  a request of its own, its type then NOT_REQUIRED
         *  (Constraints.Builder.setRequiredNetworkRequest). */
        internal fun asksNetwork(c: Constraints): Boolean =
            c.requiredNetworkRequest != null || c.requiredNetworkType != NetworkType.NOT_REQUIRED

        /** Where a kick's request is made and queued: off the caller's
         *  thread, one at a time. The JVM tests' runs at once. */
        @androidx.annotation.VisibleForTesting
        internal var looks: java.util.concurrent.Executor = java.util.concurrent.Executors.newSingleThreadExecutor()

        /** The names [runOnce] is looking at now. */
        private val deciding: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }
}
