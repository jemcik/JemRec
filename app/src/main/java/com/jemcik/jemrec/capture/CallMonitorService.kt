package com.jemcik.jemrec.capture

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.jemcik.jemrec.MainActivity
import com.jemcik.jemrec.calls.AfterCall
import com.jemcik.jemrec.calls.CallLedger
import com.jemcik.jemrec.calls.RecordPrompt
import com.jemcik.jemrec.recordings.AfterSave
import com.jemcik.jemrec.recordings.RecordingSaver
import com.jemcik.jemrec.recordings.react
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's presence during a call: a foreground service that exists for the
 * length of the call, shows what is happening to it, and collects the recording
 * when it ends. The shell-side daemon does the watching and the recording; this
 * is woken by it (see Main.startCallWatcher) and is never on the audio path.
 *
 * WHY THE APP CANNOT SIMPLY BE WOKEN BY A BROADCAST
 *
 * The obvious design - a manifest receiver on PHONE_STATE, woken by the system
 * when a call starts - does not work on MagicOS, and the reason is visible in
 * the system's own broadcast records. This device enqueues four variants of
 * PHONE_STATE, and for an app whose process is not running, every one that
 * could reach us is skipped:
 *
 *   [READ_PRIVILEGED_PHONE_STATE]            Permission Denial   (system only)
 *   [READ_PHONE_STATE, READ_CALL_LOG]        Permission Denial   (we hold neither
 *                                                                 nor want the second)
 *   [READ_PHONE_STATE]                       "iaware pevent send broadcast"
 *
 * That last one is Honor's iAware framework dropping broadcasts to apps that
 * are not already running. Measured twice: with the process ALIVE a fourth
 * variant was delivered with reason "remote app" and the receiver ran; with the
 * process killed - by `am kill`, so the package was NOT in the stopped state -
 * that variant does not appear at all and nothing is delivered.
 *
 * The first answer was a PERMANENT foreground service, awake for the broadcast
 * to land on, with the permanent notification that implies. The daemon made
 * that unnecessary: it runs as shell, is never frozen, can register for call
 * state itself, and wakes this service with `am start-foreground-service` -
 * which is not a broadcast, so nothing filters it, and is one of the starts
 * Android 12 still permits from the background.
 */
class CallMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /**
     * Every save, and every Record tap's start, each counted until it
     * completes. One field held only the newest, and a newer save - a
     * second call's that ended at once, or the same end come twice and
     * found Busy - made the service forget the older one still running:
     * the newer one's end stood the service down, and onDestroy cancelled
     * the older save half way.
     */
    private val saves: MutableSet<Job> = ConcurrentHashMap.newKeySet()

    /** Whether the service may stand down: one rule, finishIfIdle's. */
    private val standDown = StandDown<Job> { it.isCompleted }

    /**
     * Whether a call is actually happening.
     *
     * The prompt's Record button starts a recording, and a recording started
     * outside a call has NOTHING TO END IT: the only stop is the call going
     * idle, so it runs until the process dies. One got made that way and grew
     * silence for two minutes before a force-stop happened to close it.
     */
    @Volatile
    private var callInProgress = false

    /** Written from the save coroutine as well as the main thread. */
    @Volatile
    private var recordingNow = false
    @Volatile
    private var notRecording = false

    /** The ticket the "Taking a message" notification's buttons must carry,
     *  while the answering machine takes one (a test call, if [testing]);
     *  null otherwise. One field, read once: two that moved together could
     *  be seen half-moved from the other thread that writes them. */
    @Volatile
    private var messageTicket: String? = null
    @Volatile
    private var testing = false

    /** Watching the recorder after a USB cable went in or came out (see
     *  CableWatch); [checkingRecorder] while it runs, for the notification. */
    @Volatile
    private var cableWatch: Job? = null
    @Volatile
    private var checkingRecorder = false

    /** Started only to post the background-kill warning (see BackgroundKill):
     *  there is no call, so the service's own notification must not claim one,
     *  and it ends in a moment, so it waits like the cable watch's. */
    @Volatile
    private var warningOnly = false

    /** That warning's job: the service is not idle until it is done. */
    @Volatile
    private var warningJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
    }

    /**
     * TRANSIENT ON PURPOSE. This service exists only for the length of a call.
     *
     * It used to run permanently, watching call state itself and polling the
     * daemon's health, which is why it held a permanent notification. Now the
     * shell-side daemon watches the phone and wakes this service with
     * `am start-foreground-service` when a call begins - so the app is dormant
     * between calls, there is no permanent notification, and this service
     * starts, records, and stops itself again per call. Daemon health moved to
     * a scheduled job (RecorderKeepAlive), because nothing here is running to
     * poll it any more.
     *
     * START_NOT_STICKY throughout: if the system kills this mid-call there is
     * nothing to resurrect, and it must NOT come back on its own between calls -
     * the daemon is what brings it back, once, for the next call.
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Read once: every guarded action below and the two notification
        // states here hang on it.
        val fromDaemon = intent?.getStringExtra(EXTRA_TOKEN) == CaptureDaemon.token(this)
        // A cable watch says so, rather than "Call in progress" - and only
        // the daemon's, carrying the token, gets to change what this says.
        if (intent?.action == ACTION_CABLE && fromDaemon) checkingRecorder = true
        // The same for the daemon's background-kill wake, which without this
        // fell through to "Call in progress" with no call at all.
        if (intent?.action == ACTION_BACKGROUND_KILLED && fromDaemon) warningOnly = true
        // Owed within seconds whatever the action, because every start of this
        // service is a startForegroundService.
        startForegroundCompat()

        when (intent?.action) {
            // EVERY ACTION BELOW IS GUARDED. This service is exported, so any
            // installed app can send it any of them; the guard is the only
            // thing that tells the daemon or the user apart from the rest. An
            // unguarded RECORD_NOW was reported (GHSA-hf4v-7hwh-hx7w): any app
            // could start recording a call in ask-first mode, and follow it
            // with STOP to take this notification down while it ran.
            //
            // What no guard here can refuse is stopService(): any app may call
            // it on an exported service. That costs this notification for the
            // rest of a call the user chose to record, and nothing more - the
            // daemon owns the recording, CALL_ENDED brings this service back to
            // save it, and the prompt's ticket lives in preferences, not here.

            // THE DAEMON SAW A CALL. The route that lets the app be dormant: the
            // shell process wakes this service directly, which is not a broadcast
            // so iAware cannot drop it. Guarded by a token, because an exported
            // service that records phone calls must not be startable by any app.
            // STOP comes from the app itself and carries the same token.
            //
            // The daemon has already RECORDED the call to a file by the time
            // CALL_ENDED arrives; this service only reflects it and, on end,
            // fetches the finished file to save. It is no longer on the audio
            // path, so a freeze here cannot cost a single packet.
            ACTION_CALL_STARTED, ACTION_CALL_ENDED, ACTION_STOP, ACTION_MESSAGE_STARTED, ACTION_MESSAGE_TAKEN,
            ACTION_CABLE, ACTION_BACKGROUND_KILLED -> {
                if (!fromDaemon) {
                    Log.w(TAG, "monitor: ${intent.action} with a bad token, ignoring")
                    finishIfIdle()
                    return START_NOT_STICKY
                }
                when (intent.action) {
                    ACTION_CALL_STARTED -> onCallStarted(
                        intent.getStringExtra(EXTRA_RECFILE),
                        intent.getBooleanExtra(EXTRA_INCOMING, false),
                        intent.getStringExtra(EXTRA_APP),
                    )
                    ACTION_CALL_ENDED -> {
                        if (intent.getBooleanExtra(EXTRA_TEST, false)) {
                            noteTestCall(intent.getLongExtra(EXTRA_STARTED, 0L), intent.getBooleanExtra(EXTRA_INCOMING, false))
                        }
                        onCallEnded(
                            intent.getStringExtra(EXTRA_RECFILE),
                            intent.getBooleanExtra(EXTRA_INCOMING, false),
                        )
                    }
                    ACTION_MESSAGE_STARTED -> onMessageStarted(intent.getBooleanExtra(EXTRA_TEST, false))
                    ACTION_MESSAGE_TAKEN -> onMessageTaken()
                    ACTION_CABLE -> watchCable(intent.getStringExtra(EXTRA_CABLE))
                    // The daemon saw this phone kill JemRec in the background.
                    // Warn once (BackgroundKill dedups against the app's own
                    // on-open check), then let this service go - there is no
                    // call, nothing to hold the notification for.
                    // On the service's IO scope: it asks the package manager
                    // and reads the exit records, binder calls all, and the
                    // main thread may be needed by a call's wake meanwhile.
                    ACTION_BACKGROUND_KILLED -> warningJob = scope.launch {
                        try {
                            BackgroundKill.warnFromDaemon(this@CallMonitorService)
                        } finally {
                            warningOnly = false
                            finishIfIdle(ending = coroutineContext[Job])
                        }
                    }
                    else -> stopSelf()
                }
            }

            // The "Taking a message" notification's buttons. Guarded by the
            // ticket that notification was posted with: this service is
            // exported, and "unmute the microphone mid-call" and "hang up"
            // are not for any app on the phone to press.
            ACTION_TALK, ACTION_HANG_UP -> {
                val ticket = messageTicket
                if (ticket == null || intent.getStringExtra(EXTRA_TICKET) != ticket) {
                    Log.w(TAG, "monitor: ${intent.action} without this message's ticket, ignoring")
                    finishIfIdle()
                    return START_NOT_STICKY
                }
                val talk = intent.action == ACTION_TALK
                scope.launch {
                    val ok = CaptureDaemon.handOver(this@CallMonitorService, talk)
                    Log.i(TAG, "monitor: ${if (talk) "talk to them" else "hang up"} -> $ok")
                    if (talk && ok) messageTaken()
                }
            }

            // The user's answer to the start-of-call prompt (on-demand). Guarded
            // by the prompt's own ticket rather than the token - see
            // RecordPrompt.claim - so only the buttons of the prompt that is up
            // right now can answer it.
            ACTION_RECORD_NOW, ACTION_DECLINE -> {
                if (!RecordPrompt.claim(this, intent.getStringExtra(EXTRA_TICKET))) {
                    Log.w(TAG, "monitor: ${intent.action} without this prompt's ticket, ignoring")
                    finishIfIdle()
                    return START_NOT_STICKY
                }
                if (intent.action == ACTION_RECORD_NOW) recordNow() else decline()
            }

            else -> {
                // A bare start with nothing to do - do not linger as a
                // foreground service for no reason. finishIfIdle, not stopSelf:
                // any app can send this, and mid-call it must not be a STOP
                // that skips STOP's guard.
                Log.i(TAG, "monitor: started with no action")
                finishIfIdle()
            }
        }
        return START_NOT_STICKY
    }

    /** The daemon saw a USB cable go in or come out, seconds before adbd -
     *  and the daemon with it - may go. One watch at a time. */
    private fun watchCable(what: String?) {
        if (cableWatch?.isActive == true) return
        // Awake for the watch and the restart, capped. A cable usually comes
        // out with the screen off, and a foreground service keeps its process,
        // not the CPU, which may sleep between the watch's pings.
        //
        // On an Honor left to "Manage automatically" none of it holds: its
        // power manager freezes the app and takes this lock away regardless.
        // Measured there, 2026-10-25 00:10, screen off - unfrozen for the
        // daemon's wake at 00:10:09.72, "freeze pid=... reason=PG" with the
        // lock released half a second later, a 3 s thaw when the notification
        // appeared, then frozen until the phone was plugged in at 00:14:46.
        // With JemRec on "Manage manually" (what Survive a restart asks for)
        // nothing was frozen and the recorder was back in 13 s (23:47).
        val awake = runCatching {
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "jemrec:cable")
                .apply {
                    setReferenceCounted(false)
                    acquire(CableWatch.AWAKE_MS)
                }
        }.onFailure { Log.w(TAG, "monitor: no wake lock for the cable watch", it) }.getOrNull()
        cableWatch = scope.launch {
            ReviveLog.note(this@CallMonitorService, "cable: wake lock ${if (awake?.isHeld == true) "held" else "NOT held"}")
            try {
                CableWatch.watch(this@CallMonitorService, what)
            } finally {
                runCatching { if (awake?.isHeld == true) awake.release() }
                checkingRecorder = false
                // A call that started meanwhile keeps the service, and its
                // notification goes back to saying what it says for a call.
                // finishIfIdle first, whatever else is up: it is where this
                // job is marked as ending for the others' checks.
                if (!finishIfIdle(ending = coroutineContext[Job]) &&
                    (callInProgress || saves.any { !it.isCompleted })
                ) {
                    runCatching { startForegroundCompat() }
                }
            }
        }
    }

    /** The user tapped Record on the start-of-call prompt (on-demand). */
    private fun recordNow() {
        RecordPrompt.dismiss(this)
        launchRecording {
            val name = startOnDemand(this@CallMonitorService)
            if (name.isNullOrBlank()) {
                Log.w(TAG, "monitor: on-demand start did not take (call over?)")
                // Not counting itself: it is one of the saves, and still
                // running, so a plain finishIfIdle never stood the service
                // down with no call up - "Call in progress" stayed.
                finishIfIdle(ending = coroutineContext[Job])
            } else {
                Log.i(TAG, "monitor: on-demand recording started -> $name")
                recordingNow = true
                startForegroundCompat()
            }
        }
    }

    /** The user tapped Not now: nothing was ever recorded, so nothing to do. */
    private fun decline() {
        Log.i(TAG, "monitor: user declined to record this call")
        RecordPrompt.dismiss(this)
        finishIfIdle()
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        saves.clear()
        super.onDestroy()
    }

    private fun onCallStarted(recfile: String?, incoming: Boolean, app: String?) {
        callInProgress = true
        // A message's own wake is MESSAGE_STARTED. This one, for a call the
        // answering machine took, is Ask first's after a takeover: the
        // message is over, and the prompt asks about the rest.
        messageTicket = null
        testing = false
        if (recfile.isNullOrBlank()) {
            // ON-DEMAND. The daemon is recording NOTHING yet. Ask, right at the
            // start of the call, and only a Record tap (ACTION_RECORD_NOW) tells
            // the daemon to begin. Declining means the call is never captured at
            // all - the whole point of asking first.
            recordingNow = false
            Log.i(TAG, "monitor: off-hook (${if (incoming) "incoming" else "outgoing"}), asking whether to record")
            RecordPrompt.show(this, incoming, app)
        } else {
            // AUTOMATIC. The daemon is already recording this call to disk; this
            // just shows it. It never touches the audio.
            recordingNow = true
            Log.i(TAG, "monitor: off-hook (${if (incoming) "incoming" else "outgoing"}), the daemon is recording")
        }
        startForegroundCompat()
    }

    /** The answering machine answered, and the greeting is playing. */
    private fun onMessageStarted(test: Boolean) {
        Log.i(TAG, "monitor: the answering machine is taking a message${if (test) " (test)" else ""}")
        callInProgress = true
        testing = test
        messageTicket = java.util.UUID.randomUUID().toString()
        startForegroundCompat()
    }

    /** A test call of the answering machine is over, its recording deleted
     *  by the recorder: in the call log it is an answered call, which the
     *  missed-call check and the Calls tab must not take for a lost one. */
    private fun noteTestCall(started: Long, incoming: Boolean) {
        if (started <= 0) return
        val seconds = ((System.currentTimeMillis() - started) / 1000).toInt().coerceAtLeast(0)
        CallLedger.noteTestCall(this, started, incoming, seconds)
        Log.i(TAG, "monitor: a test call of the answering machine, ${seconds}s, noted")
    }

    /** The user took the call over - unmuting on the call screen, Talk to
     *  them, or answering a second call: a call like any other now. Never
     *  late: the recorder sends no start once the call's end is on its way,
     *  and its wakes arrive in order. So it may well be the first this
     *  service hears of the call, if the message's own wake did not land. */
    private fun onMessageTaken() {
        Log.i(TAG, "monitor: the call was taken over")
        callInProgress = true
        messageTaken()
    }

    /** The message is the user's call now: recorded on, in Record every
     *  call. In Ask first the recorder stopped at the takeover, and its
     *  prompt (CALL_STARTED) asks about the rest. */
    private fun messageTaken() {
        messageTicket = null
        recordingNow = RecordingMode.of(this) == RecordingMode.AUTOMATIC
        startForegroundCompat()
    }

    private fun onCallEnded(recfile: String?, incoming: Boolean) {
        Log.i(TAG, "monitor: call ended, recfile=$recfile")
        callInProgress = false
        recordingNow = false
        messageTicket = null
        // If a start-of-call prompt is still up, the chance to answer it is gone.
        RecordPrompt.dismiss(this)

        if (recfile.isNullOrBlank()) {
            // Nothing was recorded: switched off, or on-demand never said yes to.
            finishIfIdle()
            return
        }

        // Switched off after the daemon had already started (automatic): drop it.
        if (!RecorderSwitch.isOn(this)) {
            Log.i(TAG, "monitor: recorder is off, discarding $recfile")
            launchRecording {
                RecordingSaver.deleteFromRecorder(this@CallMonitorService, recfile)
                finishIfIdle(ending = coroutineContext[Job])
            }
            return
        }

        // A recording exists - automatic, or an on-demand call the user said yes
        // to. Either way the decision is made; fetch and save it.
        launchRecording {
            saveAndReport(recfile, incoming)
            Log.i(TAG, "monitor: recording handled")
            // Stand down - unless another call has started since, or another
            // save still runs. A plain stopSelf here ended the service under
            // the next call: its notification went, and its save was
            // cancelled half way, when the user redialled within this save's
            // few seconds. Nor under a cable watch, which may be half way
            // through starting the recorder again, nor under the
            // background-kill warning.
            finishIfIdle(ending = coroutineContext[Job])
        }
    }

    /**
     * [block] as one of the [saves], put there before it runs, so that the
     * job is counted when it looks at the others as it ends. Launched first
     * and stored after, a save that ended at once - nothing answering, or
     * that file being saved already, as when the recorder's end of a call
     * comes twice - looked before this thread had stored it and left the
     * service up after the call (CI, 2026-11-01; made certain by a 300 ms
     * pause between the launch and the store). Stored but not yet started, a
     * job is not active: what asks whether one is still running asks whether
     * it has completed.
     */
    private fun launchRecording(block: suspend CoroutineScope.() -> Unit) {
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        saves.removeAll { it.isCompleted }
        saves += job
        job.start()
    }

    /**
     * Fetch the finished recording from the daemon, mux it, and speak up only if
     * it could not be saved. The fetch-and-mux itself lives in RecordingSaver,
     * shared with the startup reconcile, and what follows each outcome is one
     * table for both (AfterSave); this adds the one thing that is the
     * service's job - its own notification saying when a call went unsaved.
     */
    private suspend fun saveAndReport(recfile: String, incoming: Boolean) {
        val outcome = RecordingSaver.save(this, recfile, incoming)
        AfterSave.react(this, outcome, recfile, incoming, AfterSave.Collector.HANG_UP, tell = ::reportNotRecorded)
    }

    /**
     * Stops the service unless something still needs it, rather than sit as a
     * foreground service between calls, and says whether it did. [ending] is
     * the job calling this as it finishes - still running until it returns -
     * so it does not count. The background-kill warning counts like the rest:
     * on an Honor left to "Manage automatically" JemRec was frozen two seconds
     * after its service ended (measured 2026-10-25), so a warning stopped
     * under would wait for the next thaw.
     *
     * The rule itself, and why an ending job is marked before it looks at
     * the others, is StandDown's. Only the mark and the look share its lock:
     * stopSelf is a call into the system, and the main thread takes this
     * lock too - for a call that ends with nothing recorded, Not now, and
     * every start it turns away.
     */
    private fun finishIfIdle(ending: Job? = null): Boolean {
        val idle = standDown.idle(ending, { callInProgress }) { listOf(cableWatch, warningJob) + saves }
        if (idle) stopSelf()
        return idle
    }

    private fun startForegroundCompat() {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // Each field read once: the save's coroutine writes some of them too.
        val notice = CallNotice.of(
            CallNotice.State(
                callUp = callInProgress,
                recording = recordingNow,
                notRecording = notRecording,
                messageTicket = messageTicket,
                testing = testing,
                checkingRecorder = checkingRecorder,
                warningOnly = warningOnly,
            ),
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tap)
            .setOngoing(true)
        notice.buttons?.let { ticket ->
            builder.addAction(messageAction("Talk to them", ACTION_TALK, ticket))
                .addAction(messageAction("Hang up", ACTION_HANG_UP, ticket))
        }
        val notification: Notification = builder
            // IMMEDIATE, or Android 12+ sits on a foreground service's
            // notification for ten seconds before showing it - a deferral meant
            // to spare short background jobs a flash of UI. Measured on a real
            // call: the service started at 14:53:53.364 and the system enqueued
            // this notification at 14:54:03.376, so a 27-second call showed
            // "Recording call" for its last 17 seconds only, and it was gone
            // 0.2s after hangup - the user never saw it. A recorder is exactly
            // what the platform's own guidance names as a case for showing it at
            // once: the person should know the moment their call is being
            // recorded, not ten seconds into it.
            //
            // DEFERRED for the cable watch alone, and the background-kill
            // warning's wake: a cable that changed nothing ends it within
            // CableWatch.WATCH_MS, under those ten seconds, so a phone put on
            // a charger shows nothing at all. One that took the recorder down
            // takes longer, and then it shows.
            .setForegroundServiceBehavior(
                if (notice.deferred) Notification.FOREGROUND_SERVICE_DEFERRED
                else Notification.FOREGROUND_SERVICE_IMMEDIATE,
            )
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // SPECIAL_USE, not MICROPHONE. The microphone type demands
            // RECORD_AUDIO, and this process never opens an audio device - the
            // shell-UID daemon does. Declaring a microphone type would mean
            // holding a permission the app has no use for, to describe work it
            // does not do.
            startForeground(
                NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun messageAction(label: String, action: String, ticket: String): Notification.Action =
        Notification.Action.Builder(
            null as android.graphics.drawable.Icon?,
            label,
            PendingIntent.getService(
                this,
                action.hashCode(),
                Intent(this, CallMonitorService::class.java).setAction(action).putExtra(EXTRA_TICKET, ticket),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        ).build()

    /**
     * Tell the user a call went unrecorded, once, with the reason.
     *
     * Its own channel because it is the opposite of the ongoing one: that is
     * permanently visible and must never make a sound, this is rare and must
     * not be missed. Not ongoing, and dismissible - it is news, not status.
     */
    private fun reportNotRecorded(reason: String) {
        // The ongoing notification must stop claiming otherwise.
        recordingNow = false
        notRecording = true
        runCatching { startForegroundCompat() }
        AfterCall.postNotRecorded(this, reason)
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        // Clearing out the pre-v2 channels, including the two this class does
        // not own. They are created lazily - pairing only during setup, the
        // prompt only when automatic recording is off - so left alone they
        // would sit in the user's notification settings indefinitely, and then
        // appear TWICE the first time their replacement is created. This
        // service is the one component guaranteed to run.
        LEGACY_CHANNEL_IDS.forEach { runCatching { manager.deleteNotificationChannel(it) } }
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Call recording",
            // LOW: permanently visible, so it must never make a sound.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            // No badge. It is a status notification about a call happening
            // right now, not something with an unread count to chase onto the
            // launcher icon.
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)

        // "Recording problems" - DEFAULT, not LOW: a call that was not
        // recorded is worth one sound - defined once, in AfterCall, which
        // every problem notification goes through.
        AfterCall.ensureChannels(this)
    }

    companion object {
        private const val TAG = "JemRec"
    /**
     * NOTE ON THE ".v2" IDS
     *
     * A channel's settings are immutable once created, and deleting one only to
     * re-create it with the same id restores everything it had - documented
     * behaviour, and it means showBadge cannot be changed in place for anyone
     * who already has the app. A new id is the only way. The old channel is
     * deleted so it does not sit in Settings as a dead entry.
     */
        private const val CHANNEL_ID = "jemrec.monitor.v2"
        private val LEGACY_CHANNEL_IDS =
            listOf("jemrec.monitor", "jemrec.ask", "jemrec.pairing")
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.jemcik.jemrec.STOP_MONITOR"

        /** The mid-call prompt's buttons, when automatic recording is off. */
        const val ACTION_RECORD_NOW = "com.jemcik.jemrec.RECORD_NOW"
        const val ACTION_DECLINE = "com.jemcik.jemrec.DECLINE_RECORDING"
        /** The prompt's one-time ticket, which its answer must carry. */
        const val EXTRA_TICKET = "ticket"
        const val EXTRA_INCOMING = "incoming"
        /** A messenger call's package, from the daemon's wake; absent for a phone call. */
        const val EXTRA_APP = "app"
        const val EXTRA_RECFILE = "recfile"

        /** Sent by the shell-side daemon when it sees a call begin or end. */
        const val ACTION_CALL_STARTED = "com.jemcik.jemrec.CALL_STARTED"
        const val ACTION_CALL_ENDED = "com.jemcik.jemrec.CALL_ENDED"
        /** Sent by the daemon when a USB cable goes in or comes out; [EXTRA_CABLE]
         *  says which. See CableWatch. */
        const val ACTION_CABLE = "com.jemcik.jemrec.CABLE"
        const val ACTION_BACKGROUND_KILLED = "com.jemcik.jemrec.BACKGROUND_KILLED"
        const val EXTRA_CABLE = "cable"
        /** Sent by the daemon when the answering machine has answered and its
         *  greeting is playing; [EXTRA_TEST] for a diagnostics test call. */
        const val ACTION_MESSAGE_STARTED = "com.jemcik.jemrec.MESSAGE_STARTED"
        /** Sent by the daemon when the user took over a message - unmuting on
         *  the call screen, Talk to them, a second call answered - in Record
         *  every call. */
        const val ACTION_MESSAGE_TAKEN = "com.jemcik.jemrec.MESSAGE_TAKEN"
        const val EXTRA_TEST = "test"
        /** With a test call's end: when its recording began, wall clock. */
        const val EXTRA_STARTED = "started"
        /** The "Taking a message" notification's buttons. */
        const val ACTION_TALK = "com.jemcik.jemrec.TALK"
        const val ACTION_HANG_UP = "com.jemcik.jemrec.HANG_UP"
        const val EXTRA_TOKEN = "token"

        /**
         * Whether this service exists in this process right now. For the one
         * caller that must not start it by asking it to stop: retiring a
         * wedged daemon (CaptureDaemon.retire) clears a notification that
         * daemon left up, and a STOP to a service that is not running would
         * first bring it up, notification and all.
         */
        @Volatile
        var running = false
            private set

        /** The thread the service's work runs on, and the recorder's answer
         *  to a Record tap: today's own, and the tests' in the tests
         *  (CallMonitorServiceTest). Read as a service is made. */
        @VisibleForTesting
        internal var dispatcher: CoroutineDispatcher = Dispatchers.IO

        @VisibleForTesting
        internal var startOnDemand: suspend (Context) -> String? = CaptureDaemon::startOnDemand

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, CallMonitorService::class.java)
                        .setAction(ACTION_STOP)
                        .putExtra(EXTRA_TOKEN, CaptureDaemon.token(context))
                )
            }
        }
    }
}
