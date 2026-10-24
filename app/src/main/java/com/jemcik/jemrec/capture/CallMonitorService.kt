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
import com.jemcik.jemrec.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Date

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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile
    private var recording: Job? = null

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
        // A cable watch says so, rather than "Call in progress" - and only
        // the daemon's, carrying the token, gets to change what this says.
        if (intent?.action == ACTION_CABLE && intent.getStringExtra(EXTRA_TOKEN) == CaptureDaemon.token(this)) {
            checkingRecorder = true
        }
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
            ACTION_CABLE -> {
                if (intent.getStringExtra(EXTRA_TOKEN) != CaptureDaemon.token(this)) {
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
                if (callInProgress || recording?.isActive == true) {
                    runCatching { startForegroundCompat() }
                } else {
                    // This job is still active until this block returns.
                    stopSelf()
                }
            }
        }
    }

    /** The user tapped Record on the start-of-call prompt (on-demand). */
    private fun recordNow() {
        RecordPrompt.dismiss(this)
        recording = scope.launch {
            val name = CaptureDaemon.startOnDemand(this@CallMonitorService)
            if (name.isNullOrBlank()) {
                Log.w(TAG, "monitor: on-demand start did not take (call over?)")
                finishIfIdle()
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
        recording?.cancel()
        recording = null
        scope.cancel()
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
            recording = scope.launch {
                CaptureDaemon.deleteRecording(this@CallMonitorService, recfile)
                doneWith(coroutineContext[Job])
            }
            return
        }

        // A recording exists - automatic, or an on-demand call the user said yes
        // to. Either way the decision is made; fetch and save it.
        recording = scope.launch {
            saveAndReport(recfile, incoming)
            Log.i(TAG, "monitor: recording handled")
            doneWith(coroutineContext[Job])
        }
    }

    /**
     * A save is done: stand down - unless another call has started since, or
     * its save is now the one running. A plain stopSelf here ended the service
     * under the next call: its notification went, and its save was cancelled
     * half way, when the user redialled within this save's few seconds.
     */
    private fun doneWith(job: Job?) {
        // Nor under a cable watch, which may be half way through starting
        // the recorder again; it stands the service down when it is done.
        if (recording === job && !callInProgress && cableWatch?.isActive != true) stopSelf()
    }

    /**
     * Fetch the finished recording from the daemon, mux it, and speak up only if
     * it could not be saved. The fetch-and-mux itself lives in RecordingSaver,
     * shared with the startup reconcile; this adds the one thing that is the
     * service's job - telling the user when a call went unsaved.
     */
    private suspend fun saveAndReport(recfile: String, incoming: Boolean) {
        when (val outcome = RecordingSaver.save(this, recfile, incoming)) {
            is RecordingSaver.Outcome.Saved -> {
                Log.i(TAG, "monitor: saved $recfile")
                // While this service still holds the foreground: the check
                // decodes the file, and a dormant app might not get the time.
                // The saved part's own call - with call waiting, the last of
                // them - not the recording's.
                runCatching { AfterCall.saved(this, outcome, outcome.incoming, outcome.startMillis) }
                    .onFailure { Log.w(TAG, "monitor: after-call notice failed", it) }
                // And while the daemon is plainly reachable, whether any
                // earlier call went by unrecorded.
                runCatching { MissedCalls.check(this) }
                    .onFailure { Log.w(TAG, "monitor: missed-call check failed", it) }
            }
            // The reconcile got there first, or has it now. Either way the
            // recording is being kept, and this is not a failure to report.
            RecordingSaver.Outcome.Gone ->
                Log.i(TAG, "monitor: $recfile was collected already")
            RecordingSaver.Outcome.Busy ->
                Log.i(TAG, "monitor: $recfile is being saved by the reconcile")
            // The user's rules on whose calls to keep left this one out.
            RecordingSaver.Outcome.Skipped ->
                Log.i(TAG, "monitor: $recfile was left out by the rules")
            // Only ringing: nobody answered.
            RecordingSaver.Outcome.NotAnswered ->
                Log.i(TAG, "monitor: $recfile was never answered")
            // The answering machine answered; the caller left before the beep.
            RecordingSaver.Outcome.GreetingHangUp ->
                Log.i(TAG, "monitor: $recfile - the caller hung up during the greeting")
            // Every answered call would end this way until it is put right.
            RecordingSaver.Outcome.GreetingFailed -> reportNotRecorded(GREETING_FAILED)
            RecordingSaver.Outcome.Empty -> {
                reportNotRecorded(
                    "No call audio came through. Some phones do not allow calls to be recorded.",
                )
                // Told now, so the missed-call check must not say it again.
                // Here, where the telling is: a recording collected later
                // is left to that check.
                RecordingSaver.startMillis(recfile)?.let { CallLedger.noteRecording(this, it, incoming, 0) }
            }
            is RecordingSaver.Outcome.Failed ->
                reportNotRecorded(outcome.reason)
        }
    }

    /** Nothing left to do this start, and no recording running: stand down
     *  rather than sit as a foreground service between calls. */
    private fun finishIfIdle() {
        if (recording?.isActive != true && !callInProgress && cableWatch?.isActive != true) stopSelf()
    }

    private fun startForegroundCompat() {
        val tap = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // This service only exists during a call now, so the notification only
        // says what it is doing about THIS call. "Recording call" while it
        // records; "Call in progress" in the gap before recording starts, or
        // while the mid-call prompt waits for an answer in on-demand mode.
        val ticket = messageTicket
        // Only the cable watch, no call: a check that ends in seconds when the
        // cable changed nothing, and so the one notification here that waits.
        val cableOnly = checkingRecorder && !callInProgress && !recordingNow && ticket == null
        val title = when {
            cableOnly -> "Checking the recorder"
            ticket != null && testing -> "Testing the answering machine"
            ticket != null -> "Taking a message"
            recordingNow -> "Recording call"
            notRecording -> "Not recording this call"
            else -> "Call in progress"
        }
        val text = when {
            cableOnly -> "A USB cable went in or came out"
            ticket != null && testing -> "Your greeting is playing to the caller"
            ticket != null -> "Your greeting is playing; your microphone is muted"
            recordingNow -> "Saving this call"
            notRecording -> "Tap to see why"
            else -> "JemRec is handling this call"
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tap)
            .setOngoing(true)
        if (ticket != null) {
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
            // DEFERRED for the cable watch alone: a cable that changed
            // nothing ends it within CableWatch.WATCH_MS, under those ten
            // seconds, so a phone put on a charger shows nothing at all. One
            // that took the recorder down takes longer, and then it shows.
            .setForegroundServiceBehavior(
                if (cableOnly) Notification.FOREGROUND_SERVICE_DEFERRED else Notification.FOREGROUND_SERVICE_IMMEDIATE,
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
        postNotRecorded(this, reason)
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

        // DEFAULT, not LOW: a call that was not recorded is worth one sound.
        manager.createNotificationChannel(
            NotificationChannel(
                PROBLEM_CHANNEL_ID,
                "Recording problems",
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        )
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
        private const val PROBLEM_CHANNEL_ID = AfterCall.PROBLEM_CHANNEL_ID
        private const val PROBLEM_NOTIFICATION_ID = 31

        /** Why an answered call kept nothing: the greeting would not play. */
        internal const val GREETING_FAILED = "The answering machine answered a call, but could not play your " +
            "greeting, so it hung up. Run the test call in Settings, Answering machine."

        /** "Call not recorded", with [reason] - from here at a hang-up, or
         *  from a collection that found out later (RecordingSaver.reconcile). */
        internal fun postNotRecorded(context: Context, reason: String) {
            runCatching {
                // A collection can get here before this service ever ran.
                AfterCall.ensureChannels(context)
                val tap = PendingIntent.getActivity(
                    context, 0, Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
                val notification = Notification.Builder(context, PROBLEM_CHANNEL_ID)
                    .setContentTitle("Call not recorded")
                    .setContentText(reason)
                    .setStyle(Notification.BigTextStyle().bigText(reason))
                    .setSmallIcon(android.R.drawable.stat_notify_error)
                    .setContentIntent(tap)
                    .setAutoCancel(true)
                    .build()
                context.getSystemService(NotificationManager::class.java)
                    .notify(PROBLEM_NOTIFICATION_ID, notification)
            }.onFailure { Log.w(TAG, "monitor: could not report the failure", it) }
        }
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
