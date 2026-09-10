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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
        // Owed within seconds whatever the action, because every start of this
        // service is a startForegroundService.
        startForegroundCompat()

        when (intent?.action) {
            // THE DAEMON SAW A CALL. The route that lets the app be dormant: the
            // shell process wakes this service directly, which is not a broadcast
            // so iAware cannot drop it. Guarded by a token, because an exported
            // service that records phone calls must not be startable by any app.
            //
            // The daemon has already RECORDED the call to a file by the time
            // CALL_ENDED arrives; this service only reflects it and, on end,
            // fetches the finished file to save. It is no longer on the audio
            // path, so a freeze here cannot cost a single packet.
            ACTION_CALL_STARTED, ACTION_CALL_ENDED -> {
                if (intent.getStringExtra(EXTRA_TOKEN) != CaptureDaemon.token(this)) {
                    Log.w(TAG, "monitor: ${intent.action} with a bad token, ignoring")
                    finishIfIdle()
                    return START_NOT_STICKY
                }
                if (intent.action == ACTION_CALL_STARTED) {
                    onCallStarted(
                        intent.getStringExtra(EXTRA_RECFILE),
                        intent.getBooleanExtra(EXTRA_INCOMING, false),
                    )
                } else {
                    onCallEnded(
                        intent.getStringExtra(EXTRA_RECFILE),
                        intent.getBooleanExtra(EXTRA_INCOMING, false),
                    )
                }
            }

            // The user tapped Record on the start-of-call prompt (on-demand).
            ACTION_RECORD_NOW -> {
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

            // The user tapped Not now: nothing was ever recorded, so nothing to do.
            ACTION_DECLINE -> {
                Log.i(TAG, "monitor: user declined to record this call")
                RecordPrompt.dismiss(this)
                finishIfIdle()
            }

            ACTION_STOP -> stopSelf()

            else -> {
                // A bare start with nothing to do - do not linger as a
                // foreground service for no reason.
                Log.i(TAG, "monitor: started with no action, stopping")
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        recording?.cancel()
        recording = null
        scope.cancel()
        super.onDestroy()
    }

    private fun onCallStarted(recfile: String?, incoming: Boolean) {
        callInProgress = true
        if (recfile.isNullOrBlank()) {
            // ON-DEMAND. The daemon is recording NOTHING yet. Ask, right at the
            // start of the call, and only a Record tap (ACTION_RECORD_NOW) tells
            // the daemon to begin. Declining means the call is never captured at
            // all - the whole point of asking first.
            recordingNow = false
            Log.i(TAG, "monitor: off-hook (${if (incoming) "incoming" else "outgoing"}), asking whether to record")
            RecordPrompt.show(this, incoming)
        } else {
            // AUTOMATIC. The daemon is already recording this call to disk; this
            // just shows it. It never touches the audio.
            recordingNow = true
            Log.i(TAG, "monitor: off-hook (${if (incoming) "incoming" else "outgoing"}), the daemon is recording")
        }
        startForegroundCompat()
    }

    private fun onCallEnded(recfile: String?, incoming: Boolean) {
        Log.i(TAG, "monitor: call ended, recfile=$recfile")
        callInProgress = false
        recordingNow = false
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
                stopSelf()
            }
            return
        }

        // A recording exists - automatic, or an on-demand call the user said yes
        // to. Either way the decision is made; fetch and save it.
        recording = scope.launch {
            saveAndReport(recfile, incoming)
            Log.i(TAG, "monitor: recording handled, stopping service")
            stopSelf()
        }
    }

    /**
     * Fetch the finished recording from the daemon, mux it, and speak up only if
     * it could not be saved. The fetch-and-mux itself lives in RecordingSaver,
     * shared with the startup reconcile; this adds the one thing that is the
     * service's job - telling the user when a call went unsaved.
     */
    private suspend fun saveAndReport(recfile: String, incoming: Boolean) {
        when (val outcome = RecordingSaver.save(this, recfile, incoming)) {
            is RecordingSaver.Outcome.Saved ->
                Log.i(TAG, "monitor: saved ${outcome.name}")
            // The reconcile got there first, or has it now. Either way the
            // recording is being kept, and this is not a failure to report.
            RecordingSaver.Outcome.Gone ->
                Log.i(TAG, "monitor: $recfile was collected already")
            RecordingSaver.Outcome.Busy ->
                Log.i(TAG, "monitor: $recfile is being saved by the reconcile")
            RecordingSaver.Outcome.Empty ->
                reportNotRecorded(
                    "No call audio came through. Some phones do not allow calls to be recorded.",
                )
            is RecordingSaver.Outcome.Failed ->
                reportNotRecorded(outcome.reason)
        }
    }

    /** Nothing left to do this start, and no recording running: stand down
     *  rather than sit as a foreground service between calls. */
    private fun finishIfIdle() {
        if (recording?.isActive != true && !callInProgress) stopSelf()
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
        val title = when {
            recordingNow -> "Recording call"
            notRecording -> "Not recording this call"
            else -> "Call in progress"
        }
        val text = when {
            recordingNow -> "Saving this call"
            notRecording -> "Tap to see why"
            else -> "JemRec is handling this call"
        }
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tap)
            .setOngoing(true)
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
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
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

        runCatching {
            val tap = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = Notification.Builder(this, PROBLEM_CHANNEL_ID)
                .setContentTitle("Call not recorded")
                .setContentText(reason)
                .setStyle(Notification.BigTextStyle().bigText(reason))
                .setSmallIcon(android.R.drawable.stat_notify_error)
                .setContentIntent(tap)
                .setAutoCancel(true)
                .build()
            getSystemService(NotificationManager::class.java)
                .notify(PROBLEM_NOTIFICATION_ID, notification)
        }.onFailure { Log.w(TAG, "monitor: could not report the failure", it) }
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
        private const val PROBLEM_CHANNEL_ID = "jemrec.problem.v1"
        private const val PROBLEM_NOTIFICATION_ID = 31
        const val ACTION_STOP = "com.jemcik.jemrec.STOP_MONITOR"

        /** The mid-call prompt's buttons, when automatic recording is off. */
        const val ACTION_RECORD_NOW = "com.jemcik.jemrec.RECORD_NOW"
        const val ACTION_DECLINE = "com.jemcik.jemrec.DECLINE_RECORDING"
        const val EXTRA_INCOMING = "incoming"
        const val EXTRA_RECFILE = "recfile"

        /** Sent by the shell-side daemon when it sees a call begin or end. */
        const val ACTION_CALL_STARTED = "com.jemcik.jemrec.CALL_STARTED"
        const val ACTION_CALL_ENDED = "com.jemcik.jemrec.CALL_ENDED"
        const val EXTRA_TOKEN = "token"

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, CallMonitorService::class.java).setAction(ACTION_STOP)
                )
            }
        }
    }
}
