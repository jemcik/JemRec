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
import android.media.AudioManager
import androidx.annotation.VisibleForTesting
import com.jemcik.jemrec.DeepLink
import com.jemcik.jemrec.calls.AfterCall
import com.jemcik.jemrec.calls.AskButton
import com.jemcik.jemrec.calls.CallButton
import com.jemcik.jemrec.calls.CallButtonWindow
import com.jemcik.jemrec.calls.CallLedger
import com.jemcik.jemrec.calls.RecordPrompt
import com.jemcik.jemrec.recorder.BackgroundKill
import com.jemcik.jemrec.recorder.CableWatch
import com.jemcik.jemrec.recorder.DaemonLauncher
import com.jemcik.jemrec.recorder.RecorderHealth
import com.jemcik.jemrec.recorder.RecorderSwitch
import com.jemcik.jemrec.recorder.ReviveLog
import com.jemcik.jemrec.recordings.AfterSave
import com.jemcik.jemrec.recordings.CallParts
import com.jemcik.jemrec.recordings.DaemonFile
import com.jemcik.jemrec.recordings.LostMessages
import com.jemcik.jemrec.recordings.RecordingSaver
import com.jemcik.jemrec.recordings.reactToCall
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * The app's presence during a call: a foreground service that exists for the
 * length of the call, shows what is happening to it, and collects the recording
 * when it ends. The shell-side daemon does the watching and the recording; this
 * is woken by it (see CallWatch.startCallWatcher) and is never on the audio path.
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

    /** The last start this service took in: what a stop is made for. */
    @Volatile
    private var lastStart = 0

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

    /** Calls ended so far: the recorder's answer to Record or Talk to them
     *  that comes after its call's end is not taken for a call still up
     *  (ifTheCallIsUp). Under standDown's lock. */
    private var callEnds = 0

    /** What the notification says of the call: whether it is recorded, and
     *  whether a save failed. These two, [callInProgress] and
     *  [messageTicket] are written on the main thread and on the service's
     *  jobs - each write under standDown's lock, so a look at whether to
     *  stand down, or a failure's check for a call up, never sees a start's
     *  or an answer's change half made. */
    @Volatile
    private var recordingNow = false
    @Volatile
    private var notRecording = false

    /** The recording stopped mid-call - the recorder's own word
     *  (RECORDING_STOPPED), or the watch finding it gone (onRecorderGone) -
     *  until it goes on or the call ends. */
    @Volatile
    private var stopped: CallNotice.Stopped? = null

    /** Ask first's answer for the call up - Record (true) or Not now - kept
     *  to its end: a recorder started again mid-call joins a phone call, and
     *  asked again it would re-ask a question answered (review round 10). A
     *  messenger call's revived start is asked about again (round 18). */
    @Volatile
    private var askAnswer: Boolean? = null

    /** The answering machine's call, as Telecom's id, while it takes a
     *  message: ended if the recorder is lost mid-message (MessageHangUp). */
    @Volatile
    private var messageCall: Long? = null

    /** When the machine's greeting began: what a message's file lost with
     *  its recorder is known by (LostMessages). */
    @Volatile
    private var messageStartedAt = 0L

    /** Call starts heard: a prompt posted by one after the watch stood the
     *  call down is not taken down with it. */
    @Volatile
    private var callStarts = 0

    /** The messenger of the call up, from its last start; null for a phone
     *  call. */
    @Volatile
    private var callApp: String? = null

    /** [callStarts] as the recorder was lost: the start after it is the
     *  revived recorder's own. */
    @Volatile
    private var startsAtLoss = 0

    /** The recorder was lost during the call up: what it recorded before is
     *  a file of its own, collected at the call's end - not by the revive's
     *  kick mid-call, whose reconcile cannot name it yet (review round 10). */
    @Volatile
    private var lostRecorder = false

    /** A call before this one lost its recorder, its files left to a
     *  reconcile: the keep-alive is kicked at this call's end to collect
     *  them, as at that call's own. */
    @Volatile
    private var kickOwed = false

    /** The call up's recordings, as its starts and Record's answer named
     *  them: a recorder lost mid-call left one, the revived recorder made
     *  another, and its end names only its own. The rest are saved at the
     *  end as it is - with the hang-up's wait for the call log - not left to
     *  a keep-alive run that reads the log once and may be dropped (review
     *  round 11). */
    private val callFiles: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    /** The recorder, watched while a call it announced is up (RecorderWatch):
     *  brought back if it goes mid-call, and the call ended if it never says
     *  so. Ended by the recorder's own end of the call. */
    @Volatile
    private var recorderWatch: Job? = null

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
        runCatching { listenToMode(this, modeChanged) }
    }

    /**
     * The audio mode back to normal: the call it was is over, whatever this
     * service has heard - Ask first's answer for it forgotten. With the
     * recorder down a call ended and redialled within the watch's looks
     * read as the same call, and a recorder joining the new one was taken
     * as told to record it, or never to (review round 11). The ring's mode
     * too: Telecom sets it only with no call left active (AOSP
     * CallAudioModeStateMachine, RingingFocusState - a call waiting over
     * one up keeps the call's mode), so the call that was answered is over
     * and the one ringing is its own - the call up hung up while a waiting
     * one rang never passes normal (review round 12; read from AOSP, not
     * produced) - but for a call on hold, which a ring away from the ear
     * takes to the ring's mode too, still up (OtherFocusState; LiveCalls.held,
     * with exact call tracking on; review rounds 25, 26; read, not produced).
     * Only a ring that sounds, or, with a Bluetooth headset connected, one Do
     * Not Disturb lets through, sets it - or another app's call ringing
     * through Telecom, a self-managed one (Meet's): Telecom plays no ring for
     * it but takes the ring's mode, unless the work profile is quiet
     * (Ringer.startRinging, shouldAcquireAudioFocus): a silent one is caught
     * at the start instead (onCallStarted, round 13).
     * The mode is the one AudioManager reports to any app
     * (addOnModeChangedListener, API 31).
     */
    @VisibleForTesting
    internal val modeChanged = AudioManager.OnModeChangedListener { mode ->
        // Said: what the call's end and the next start are judged by - the
        // emulators' quick redial never passed normal, inferred: the end-of-
        // call tone keeps the call's mode (review round 28).
        Log.i(TAG, "monitor: audio mode $mode")
        // Not at a ring over a call on hold: that call goes on, its Record
        // with it (review round 26) - but not its Not now: the recorder itself
        // asks at a waiting call's off-hook with nothing recording
        // (CallDecisions.byMode, ASK), and a "Not now" carried left the
        // waiting call answered over it never asked (round 27).
        if (mode == AudioManager.MODE_NORMAL ||
            mode == AudioManager.MODE_RINGTONE && (!LiveCalls.held() || askAnswer == false)
        ) {
            askAnswer = null
        }
        // The recorder lost and the call over: over now, not when the watch
        // can look again - it is in a revive, tries of seconds each, which
        // can land in the next call, and that call's start was taken for
        // this one's, the two calls' files saved as one (review round 19;
        // read, not produced). Out of the call: normal, or for a phone call
        // a ring's mode - a waiting call ringing once the call up hung up,
        // which never passes normal (AOSP CallAudioModeStateMachine: a
        // ringing call's RingingFocusState before UnfocusedState; round 20).
        // Not a messenger's ring: Telecom knows nothing of that call, and a
        // phone call ringing over it sets the same mode.
        // A waiting call that rings silently sets no mode at all: only a
        // ring that sounds, or, with a Bluetooth headset connected, one Do
        // Not Disturb lets through, does - or another app's call ringing
        // through Telecom, a self-managed one (Meet's): Telecom plays no ring
        // for it but takes the ring's mode, unless the work profile is quiet
        // (AOSP Ringer.startRinging;
        // RingingFocusState otherwise leaves the call's mode): its answer
        // is not seen, and its call is taken with
        // the call before, as the recorder's own call waiting is
        // (Calls.onCallStart, RECORDING_ALREADY; review round 21).
        // Not a ring's over a call on hold, which is still up (LiveCalls.held,
        // with exact call tracking on; off, a ring is taken as the end - a
        // held call split in two, not a waiting call recorded on another's
        // Record; review round 25).
        val outOfTheCall = mode == AudioManager.MODE_NORMAL ||
            mode == AudioManager.MODE_RINGTONE && callApp == null && !LiveCalls.held()
        // A messenger's mode back in a call: its wait is over - a ring after
        // it is no time out for a messenger call, and the wait's end read a
        // ring's mode as out (review round 22).
        if (mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION) messengerOut?.cancel()
        // A phone call's mode left: the call up is over, lost recorder or not
        // - the next start is another call's (onCallStarted, review round 24:
        // a loss found only after the hang-up took the redial for this call).
        if (outOfTheCall && callApp == null) {
            phoneOutAt = callStarts
            phoneOutAtMs = System.currentTimeMillis()
        }
        // Only with the recorder lost and no start heard since the loss - read
        // under the lock the loss is written under (round 24). A messenger's
        // wait starts again at each time out: out, back and out again is no
        // 1.5 s out (round 21).
        val gate = standDown.holding { Triple(lostRecorder && callStarts == startsAtLoss, callStarts, backSinceLoss) }
        // Lost, and the revived recorder has said the call: its own end ends
        // it - said, as callMaybeOver says it (review round 25: the release
        // check waited for one of the two lines, and neither came).
        if (outOfTheCall && lostRecorder && !gate.first) {
            Log.i(TAG, "monitor: out of a call, the recorder back in it; its own end ends it")
        }
        if (outOfTheCall && gate.first) {
            messengerOut?.cancel()
            val job = callMaybeOver(gate.second, messenger = callApp != null, backAlready = gate.third)
            if (callApp != null) messengerOut = job
        }
    }

    /** [callStarts] when a phone call's mode was last left (normal, or a
     *  ring's with no call active or on hold - LiveCalls.held): a start
     *  after it, with no start between, is another call's. */
    @Volatile
    private var phoneOutAt = -1

    /** When, by the clock the recorder names its files by. */
    @Volatile
    private var phoneOutAtMs = 0L

    /** When a call was last taken up - a start, or one with none counted:
     *  Telecom's list emptied after it ([telecomOutAtMs]) ends that call. */
    @Volatile
    private var lastStartAtMs = 0L

    /** When the last joined start's file began (System.currentTimeMillis,
     *  as the recorder names its files), or the start was heard where it
     *  named none: one begun since a message's call began says the recorder
     *  that answered it is gone (onMessageStarted; review rounds 32, 33). */
    @Volatile
    private var lastJoinedBegunAt = 0L

    /** The watch's revive brought the recorder back since the loss: it is
     *  in this call, its start perhaps on its way - unless lost again before
     *  the watch's next look, or its revive begun before a phone call rang
     *  over a messenger call that then ends under it (the watch starts none
     *  under such a ring, round 26), or its call ended before it found it:
     *  the end is then the watch's two looks (review rounds 23, 24; each a
     *  second fault within seconds). */
    @Volatile
    private var backSinceLoss = false

    /** A messenger call's wait out of its mode ([callMaybeOver]), ended by
     *  the mode back in a call or the next time out. */
    @Volatile
    private var messengerOut: Job? = null

    /** [modeChanged]'s call out of its mode with the recorder lost: over,
     *  unless the recorder was back already as the mode left - in this call,
     *  its start on its way (a wake, up to ~34 s - Wakes), its own end to come: ended
     *  here, the call's parts were split between two collectors (round 20).
     *  Back as the mode left: [backAlready], the watch's revive (it knows,
     *  no ping wrong about it), or one ping at once for one back some other
     *  way. Not later: one back since is in no call of this one's - the
     *  ask of a ping and five more (round 21) took 2.4 s, and a revive
     *  landing in it, the call's end came from the watch ten seconds on,
     *  the next call's start taken for this one's (round 22). A phone call at once:
     *  Telecom sets normal or a ring's mode only with no call active (AOSP
     *  CallAudioModeStateMachine, UnfocusedState and RingingFocusState), so
     *  a waiting call answered at once - End and answer - is the next call
     *  and not this one, whatever the mode says by then (round 21). A
     *  [messenger] call only once out for [OUT_CONFIRM_MS] without a break:
     *  an app switching its route flips the mode, and the recorder's own
     *  watch waits as long (Voip.END_CONFIRM_MS; rounds 20 and 21). */
    private fun callMaybeOver(starts: Int, messenger: Boolean, backAlready: Boolean) = scope.launch {
        if (backAlready || recorderUpNow(this@CallMonitorService)) {
            Log.i(TAG, "monitor: out of a call, the recorder back in it; its own end ends it")
            return@launch
        }
        if (messenger) delay(OUT_CONFIRM_MS)
        if (messenger && CaptureDaemon.callUp(this@CallMonitorService)) return@launch
        if (onCallOverUnsaid(endedAt = starts)) Log.i(TAG, "monitor: out of a call, the recorder still lost; the call is over")
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
        // Owed within seconds to a startForegroundService: the recorder's wakes
        // and the prompt's. A plain start - the app's stop(), or another app's
        // - owes nothing, and Android may refuse it: the app 20 s off the
        // screen, an Off from the background took the process with it on the
        // Android 12, 13, 16 and 17 AVDs, 2026-11-05. (2 s off the screen it
        // was allowed, and so was a tap on the tile 20 s off, on 12 and 17.)
        runCatching { startForegroundCompat() }
            .onFailure { Log.i(TAG, "monitor: not in the foreground for ${intent?.action}: ${it.javaClass.simpleName}") }

        // The changes this start makes, made under the lock every look at
        // whether to stand down takes (finishIfIdle): a look sees them
        // whole, or not at all, and the start's id with them.
        standDown.holding {
            lastStart = startId
            dispatch(intent, fromDaemon)
        }
        return START_NOT_STICKY
    }

    /** What a start asks for, [fromDaemon] or not (see onStartCommand). */
    private fun dispatch(intent: Intent?, fromDaemon: Boolean) {
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
            // Of a call Ask first asks about it costs the button over it and,
            // after Not now, the Record on that notification: no recording, and
            // no call recorded unasked.

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
            ACTION_CABLE, ACTION_BACKGROUND_KILLED, ACTION_RECORDING_STOPPED, ACTION_RECORDING_BACK -> {
                if (!fromDaemon) {
                    Log.w(TAG, "monitor: ${intent.action} with a bad token, ignoring")
                    finishIfIdle()
                    return
                }
                when (intent.action) {
                    ACTION_CALL_STARTED -> onCallStarted(
                        intent.getStringExtra(EXTRA_RECFILE),
                        intent.getBooleanExtra(EXTRA_INCOMING, false),
                        intent.getStringExtra(EXTRA_APP),
                        intent.getBooleanExtra(EXTRA_JOINED, false),
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
                    ACTION_RECORDING_STOPPED -> onRecordingStopped(intent.getBooleanExtra(EXTRA_FOR_GOOD, false))
                    ACTION_RECORDING_BACK -> onRecordingBack()
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
                    return
                }
                val talk = intent.action == ACTION_TALK
                val ends = callEnds
                scope.launch {
                    val ok = handOver(this@CallMonitorService, talk)
                    Log.i(TAG, "monitor: ${if (talk) "talk to them" else "hang up"} -> $ok")
                    if (talk && ok && ifTheCallIsUp(ends, ::messageTaken)) startForegroundCompat()
                }
            }

            // The user's answer to the start-of-call prompt (on-demand). Guarded
            // by the prompt's own ticket rather than the token - see
            // RecordPrompt.claim - so only the buttons of the prompt that is up
            // right now can answer it.
            ACTION_RECORD_NOW, ACTION_DECLINE -> {
                answered(intent.getStringExtra(EXTRA_TICKET), record = intent.action == ACTION_RECORD_NOW, intent.action)
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
    }

    /** Record, or Not now, by [from] - under [ticket], which must be the one
     *  live (RecordPrompt.claim). */
    private fun answered(ticket: String?, record: Boolean, from: String?) {
        if (!RecordPrompt.claim(this, ticket)) {
            Log.w(TAG, "monitor: $from without this prompt's ticket, ignoring")
            finishIfIdle()
            return
        }
        if (record) recordNow() else decline()
    }

    /** Ask first's button over the call (CallButton). Its taps come to this
     *  service as it is, in the process, and are answers as any other: the
     *  ticket, under the lock a start's changes are made under. */
    private val button: AskButton by lazy {
        buttonFor(this) { ticket, record -> standDown.holding { answered(ticket, record, "the button") } }
    }

    /** Nothing left to ask of the call: the question and the button down,
     *  with the ticket. */
    private fun questionOver() {
        RecordPrompt.dismiss(this)
        button.on(CallButton.Event.OVER)
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

    /**
     * The user tapped Record on the start-of-call prompt (on-demand). Once
     * the recorder has started, a call is up - this service may never have
     * seen its start: the prompt's ticket outlives the process - and only
     * its end, as the recorder sends it after every call it recorded, or
     * STOP, stands the service down, as for any call. Before, nothing counted
     * it, and the next look at whether to stand down - a stray start, a
     * cable watch's end - did, with "Recording call" going as it recorded.
     * Unless that end came while the recorder was asked: the call is over,
     * and its end has seen to the rest.
     */
    private fun recordNow() {
        RecordPrompt.dismiss(this)
        button.on(CallButton.Event.RECORD)
        askAnswer = true
        // Its Record gone with its ticket, which this answer spent.
        if (callInProgress) startForegroundCompat()
        val ends = callEnds
        launchRecording {
            val name = startOnDemand(this@CallMonitorService)
            button.on(if (name.isNullOrBlank()) CallButton.Event.FAILED else CallButton.Event.TAKEN)
            if (name.isNullOrBlank()) {
                Log.w(TAG, "monitor: on-demand start did not take (call over?)")
                // A call up all the same - the recorder down, which is why it
                // did not take: the call is taken and watched, the recorder
                // brought back, and its joined start records the rest on
                // the answer kept (review round 11).
                val callUp = CaptureDaemon.callUp(this@CallMonitorService)
                var held = false
                ifTheCallIsUp(ends) {
                    if (callUp && !callInProgress) {
                        phoneOutAt = -1
                        lastStartAtMs = System.currentTimeMillis()
                    }
                    if (callUp) callInProgress = true
                    // Not recorded after all: Record stays in reach of a call
                    // this service holds, as after Not now - by its own word,
                    // as a waiting call ringing has the mode out of a call.
                    held = callInProgress
                    if (held) RecordPrompt.keepRecord(this@CallMonitorService)
                }
                if (callUp && held) watchTheRecorder()
                if (held) startForegroundCompat()
            } else {
                Log.i(TAG, "monitor: on-demand recording started -> $name")
                val up = ifTheCallIsUp(ends) {
                    // The call's own, only while it is up: added after its
                    // end, the next call's end saved it again (review round 12).
                    if (name !in callFiles) callFiles += name
                    heldNow = callFiles.toSet()
                    // Two files: the call's parts, as at a start (review
                    // round 15).
                    if (callFiles.size > 1) CallParts.note(this@CallMonitorService, callFiles.toList())
                    if (!callInProgress) {
                        phoneOutAt = -1
                        lastStartAtMs = System.currentTimeMillis()
                    }
                    callInProgress = true
                    recordingNow = true
                    // As at any call's start: a failure told of is the call
                    // before's.
                    notRecording = false
                }
                if (up) {
                    // Perhaps the first this service hears of the call - the
                    // prompt's ticket outlives the process: watched, as any
                    // call it holds (review round 10).
                    watchTheRecorder()
                    startForegroundCompat()
                }
            }
            // Not counting itself: it is one of the saves, and still running,
            // so a plain finishIfIdle never stood the service down with no
            // call up - "Call in progress" stayed.
            finishIfIdle(ending = coroutineContext[Job])
        }
    }

    /**
     * [change], under the lock the stand-down rule takes, if no call has
     * ended since [ends] - the count when the recorder was asked - and
     * whether it was made. The recorder's answer to Record or Talk to them
     * can come after the call's end, and is then no answer for a call up.
     */
    private fun ifTheCallIsUp(ends: Int, change: () -> Unit): Boolean = standDown.holding {
        (callEnds == ends).also { up -> if (up) change() }
    }

    /** The user tapped Not now: nothing was ever recorded, so nothing to do
     *  but keep Record within reach - for a call this service holds: one it
     *  does not has no notification of its own to put it on. */
    private fun decline() {
        Log.i(TAG, "monitor: user declined to record this call")
        askAnswer = false
        questionOver()
        if (callInProgress) {
            RecordPrompt.keepRecord(this)
            startForegroundCompat()
        }
        finishIfIdle()
    }

    override fun onDestroy() {
        runCatching { getSystemService(AudioManager::class.java).removeOnModeChangedListener(modeChanged) }
        button.close()
        running = false
        heldNow = emptySet()
        messageSince = 0L
        scope.cancel()
        saves.clear()
        super.onDestroy()
    }

    private fun onCallStarted(recfile: String?, incoming: Boolean, app: String?, joined: Boolean = false) {
        callStarts++
        // A recorder started again mid-call joins the call this service has:
        // the same call, its Ask first answer kept. Any other start is a call
        // of its own - and so, for the answer, is a joined start that came
        // through a ring (the recorder sends it incoming): a waiting call,
        // which may be the call that rang itself, the call up having hung up
        // first while it rang silently - no mode change says that (AOSP
        // RingingFocusState sets MODE_RINGTONE only for a ring that sounds,
        // or, with a Bluetooth headset connected, one Do Not Disturb lets
        // through - or a self-managed call's, which Telecom does not ring,
        // unless the work profile is quiet -
        // Ringer.startRinging), and the
        // answer for the call up recorded it
        // unasked, or never asked (review round 13; read, not produced).
        // A joined start continues a phone call only: one over a messenger
        // call whose recorder was lost is a phone call rung in during it, a
        // call of its own - taken for the same call, the messenger call's
        // Record recorded it unasked (review round 19; read, not produced).
        // And a phone call's whose mode was left since its last start - the
        // call over, its end never heard (the recorder lost unnoticed): a
        // start of a file begun since is the next call's (review round 24) -
        // not one begun before, a recorder revived in this call whose start
        // was on its way (round 20); with no file (Ask first), asked about.
        val fileStart = recfile?.takeIf { it.isNotBlank() }?.let { DaemonFile.parse(it).start }
        fun begunAfter(at: Long) = fileStart == null || fileStart >= at
        // Or Telecom's own list emptied since the last start (exact call
        // tracking on): a redial within the end-of-call tone, the mode never
        // normal between (round 29; the emulators' quick redial, inferred).
        val telecomOut = telecomOutAtMs
        val afterThePhoneCall = callInProgress && callApp == null && (
            phoneOutAt == callStarts - 1 && begunAfter(phoneOutAtMs) ||
                telecomOut > lastStartAtMs && begunAfter(telecomOut)
            )
        lastStartAtMs = System.currentTimeMillis()
        val joinedHere = joined && callApp == null && !afterThePhoneCall
        val sameCall = joinedHere && callInProgress && !incoming
        // A messenger call's start never comes joined - the recorder names
        // no messenger file so (Calls.onCallStart). The revived recorder's
        // own: the first start since the loss, of the same app, in the call
        // the recorder was lost in (review round 17: its first part was
        // dropped from the group). Only that one: any start after it is a
        // call of its own (round 18: a redial was grouped, and a messenger
        // call taken as a phone call hung up carried the phone call's
        // Record). Its files are kept; its answer is not - asked again: a
        // messenger's own call waiting, one call swapped for the next with
        // no mode change, cannot be told from it, and an answer carried
        // records a call nobody was asked about.
        val revived = callInProgress && lostRecorder && app != null && app == callApp && callStarts == startsAtLoss + 1
        if (!callInProgress) callFiles.clear()
        // A start of a call of its own while one is up - a new file, or none,
        // not joined - is a new call whose predecessor's end never came (its
        // wake lost): that call's files are its own, left to a reconcile, not
        // this call's parts (review round 16: two calls saved as one).
        if (callInProgress && !joinedHere && !revived && (recfile.isNullOrBlank() || recfile !in callFiles)) {
            // With the recorder lost in it, what that call left is a part of
            // it, one alone too (review round 20) - and the loss is that
            // call's: the next one's whole file was marked a part (round 21).
            // Its files are collected after this call's end (kickOwed). Lost
            // unseen too: a joined start is a recorder started with a call
            // up, and one started since that call's newest file began says
            // the recorder writing it is gone (cutSinceBegun; review round 30:
            // on the 17 AVD the Wi-Fi revive's recorder joined the redial with
            // no look of the watch having found the loss, and the cut file was
            // saved as a whole call).
            if (lostRecorder || joined || cutSinceBegun(callFiles)) {
                CallParts.note(this, callFiles.toList(), alone = true)
                lostRecorder = false
                kickOwed = true
            }
            callFiles.clear()
        }
        callApp = app
        if (!recfile.isNullOrBlank() && recfile !in callFiles) callFiles += recfile
        heldNow = callFiles.toSet()
        // Two files: the call's parts, noted now for any collector - a
        // reconcile between the call's end and its recorder's (review
        // round 15).
        if (callFiles.size > 1) CallParts.note(this, callFiles.toList())
        if (!sameCall) askAnswer = null
        // What a recorder lost had recorded belongs to the call up, and is
        // collected at its end: only an end forgets it - not call waiting's
        // second off-hook, which no recorder calls joined (review round 11).
        if (!callInProgress) lostRecorder = false
        callInProgress = true
        stopped = null
        watchTheRecorder()
        // A failure told of is the call before's.
        notRecording = false
        // A message's own wake is MESSAGE_STARTED. This one, for a call the
        // answering machine took, is Ask first's after a takeover: the
        // message is over, and the prompt asks about the rest - from the
        // recorder that took the message. A joined one, or any from a
        // recorder started since the message began, is another recorder's:
        // the one that took it is gone, as a loss the watch finds (onRecorderGone)
        // - its file a message it can no longer name, the greeting and the
        // mute gone with it, the caller talking into a call nothing ends
        // (review round 31).
        val endingTheMessage = messageTicket != null && (joined || cutSince(messageStartedAt)) &&
            messageLostUnseen(recfile, joined)
        // By its file's own start where it has one, not by when its wake came
        // (review round 33: a joined wake for the call before, late, was taken
        // for one in the message's call).
        if (joined) lastJoinedBegunAt = fileStart ?: System.currentTimeMillis()
        messageTicket = null
        messageCall = null
        testing = false
        if (recfile.isNullOrBlank() && sameCall && askAnswer != null) {
            // Ask first, answered already for this call: Record goes on with
            // the recorder now up, Not now stays not now - not asked again.
            recordingNow = false
            if (askAnswer == true) {
                Log.i(TAG, "monitor: the recorder joined the call it was told to record; recording the rest")
                startForegroundCompat()
                recordNow()
                return
            }
            Log.i(TAG, "monitor: the recorder joined a call the user said not to record; not asking again")
        } else if (recfile.isNullOrBlank() && endingTheMessage) {
            // The message's call, being hung up: nothing to ask about (review
            // round 32: a heads-up asked about a call already ending).
            recordingNow = false
            Log.i(TAG, "monitor: the recorder joined a message's call being hung up; not asking")
        } else if (recfile.isNullOrBlank()) {
            // ON-DEMAND. The daemon is recording NOTHING yet. Ask, right at the
            // start of the call, and only a Record tap (ACTION_RECORD_NOW) tells
            // the daemon to begin. Declining means the call is never captured at
            // all - the whole point of asking first.
            recordingNow = false
            Log.i(TAG, "monitor: off-hook (${if (incoming) "incoming" else "outgoing"}), asking whether to record")
            RecordPrompt.show(this, incoming, app, button::show)
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
        // The one call up as the machine starts its greeting; none known
        // with exact call tracking off (LiveCalls).
        val call = MessageHangUp.messageCall(LiveCalls.calls())
        // A message is a call of its own: an Ask first answer is the call
        // before's (review round 33: a Record kept recorded the message's
        // call as a joined start went into it).
        askAnswer = null
        // A joined start's file begun since this message's call began, its
        // wake ahead of this one's (each a wake: up to three tries of 10 s,
        // about 34 s at most - Wakes): the recorder that answered is gone,
        // replaced, and nobody takes the message - lost as one replaced
        // unseen mid-message is (messageLostUnseen), the prompt the joined
        // start put up taken down with its call; the joined start's call
        // stays the call up (review rounds 32, 33). With exact call tracking
        // on only: the message's call is Telecom's.
        if (callInProgress && call != null && lastJoinedBegunAt > call) {
            messageStartedAt = System.currentTimeMillis()
            messageCall = call
            testing = test
            if (messageLostUnseen(callFiles.lastOrNull(), joined = true)) questionOver()
            messageCall = null
            testing = false
            startForegroundCompat()
            return
        }
        callInProgress = true
        watchTheRecorder()
        notRecording = false
        // A stop said of a call whose end never came is not this one's.
        stopped = null
        testing = test
        messageTicket = java.util.UUID.randomUUID().toString()
        messageCall = call
        messageStartedAt = System.currentTimeMillis()
        // A message is a call of its own - the machine answers none over
        // another: files still held are a call before's, its end never
        // heard, not this one's parts (review round 17). A phone call's. One
        // a lost recorder left is a part of that call (round 21), lost unseen
        // too (cutSinceBegun; round 30) - none held, nothing cut: a kick owed
        // for nothing ended every message (round 31).
        if (lostRecorder || cutSinceBegun(callFiles)) {
            CallParts.note(this, callFiles.toList(), alone = true)
            lostRecorder = false
            kickOwed = true
        }
        callFiles.clear()
        heldNow = emptySet()
        callApp = null
        // A call up with no start counted: the call before's mode left is
        // not this one's (phoneOutAt), nor Telecom's list emptied before it.
        phoneOutAt = -1
        lastStartAtMs = System.currentTimeMillis()
        messageSince = System.currentTimeMillis()
        startForegroundCompat()
    }

    /** A recorder started since [at] (DaemonLauncher.launchedAtMs): one is
     *  started only when none answers, once an out-of-date one has quit -
     *  which it does only with no call, recording or fetch under way - or
     *  once a wedged one was killed - so the
     *  one up at [at] is gone, though the call's watch may never have seen
     *  it go: another path - the Wi-Fi revive, the heartbeat, the app opened
     *  - can start one between its looks (review round 30). Gone
     *  perhaps only after the call's end, its end's wake not yet in: what it
     *  left is then a part though whole, which errs safe - a part only
     *  escapes the short-call rule (round 31). By the wall clock, as the
     *  recorder names its files: a clock set back in between can hide a
     *  cut, or make one. */
    private fun cutSince(at: Long) = DaemonLauncher.launchedAtMs > at

    /** [files] cut: a recorder started since the newest of them began - by
     *  its start as the recorder named it, not by when its wake came - three
     *  tries of up to 10 s, 2 s apart, about 34 s at most (Wakes) - when the
     *  recorder that began it may already have gone and been replaced
     *  (review rounds 31, 33). None: nothing cut. */
    private fun cutSinceBegun(files: Collection<String>): Boolean =
        files.mapNotNull { DaemonFile.parse(it).start }.maxOrNull()?.let { cutSince(it) } ?: false

    /** A test call of the answering machine is over, its recording deleted
     *  by the recorder: in the call log it is an answered call, which the
     *  missed-call check and the Calls tab must not take for a lost one. */
    private fun noteTestCall(started: Long, incoming: Boolean) {
        if (started <= 0) return
        val seconds = ((System.currentTimeMillis() - started) / 1000).toInt().coerceAtLeast(0)
        CallLedger.noteTestCall(this, started, incoming, seconds)
        Log.i(TAG, "monitor: a test call of the answering machine, ${seconds}s, noted")
    }

    /** The recorder's capture stopped mid-call ([forGood]: not even tried
     *  again): said now, in this call's own notification - silent, as a
     *  sound mid-call is the one thing it must not make - where until 1.2's
     *  reviews it was said only after the call (StoppedEarly, which still
     *  says how much). The recorder drops one once its call is over, so
     *  one that finds no call here is this process started again during
     *  it - taken as the call it is for, as a takeover's is, and watched
     *  (RecorderWatch), which ends it if the call is in fact over; it was
     *  dropped, and the rest of the call had no notification (review round
     *  9). */
    private fun onRecordingStopped(forGood: Boolean) {
        callGoesOn()
        Log.w(TAG, "monitor: the recording stopped mid-call${if (forGood) "" else ", being tried again"}")
        stopped = if (forGood) CallNotice.Stopped.FOR_GOOD else CallNotice.Stopped.TRYING
        startForegroundCompat()
    }

    /** The recorder watched from now until the call's end (RecorderWatch);
     *  one watch for the call, whatever starts it - a call, a message. */
    private fun watchTheRecorder() = standDown.holding {
        if (recorderWatch?.isActive == true) return@holding
        // A messenger call's watch takes a ring's mode for the call: a phone
        // call ringing over it (RecorderWatch, review round 21).
        val hooks = WatchHooks(
            stopped = ::onRecorderGone,
            over = { up -> onCallOverUnsaid(recorderDown = !up) },
            starts = { callStarts },
            // And a phone call's on hold, a call ringing over it: the held
            // call is still up (LiveCalls.held; review round 26 - the watch
            // ended it two looks into the ring).
            ringIsTheCall = { callApp != null || LiveCalls.held() },
            // Back in this call: the mode leaving it then is no end of it
            // the service makes (callMaybeOver, review round 22).
            back = { backSinceLoss = true },
        )
        val watch = watchFor(this, hooks) ?: return@holding
        // Awake while it watches: with the recorder gone nothing else holds
        // the CPU - the audio server's capture held it - and on a call with
        // the screen off by the power key (speaker, headset) a look could
        // wait for the call's end (review round 11; inferred from AOSP's
        // PowerManagerService, not produced). Capped: a call of hours ends
        // the cap, not the watch. The cable watch does the same.
        val awake = runCatching {
            getSystemService(android.os.PowerManager::class.java)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "JemRec:recorderWatch")
                .apply { setReferenceCounted(false) }
        }.getOrNull()
        recorderWatch = scope.launch {
            try {
                awake?.acquire(WATCH_AWAKE_MS)
                watch.run()
            } finally {
                runCatching { if (awake?.isHeld == true) awake.release() }
            }
        }
    }

    /** The recorder found gone mid-call, being brought back: said, as the
     *  recorder's own stop is (onRecordingStopped). */
    private fun onRecorderGone() {
        // Under the lock: a call's end between the look and the writes left
        // "Trying again" and a lost recorder over a call that was over
        // (review round 11).
        // The message taken, read under the lock with its ticket: a start
        // landing after it clears its call and test mark (review round 32).
        var lostMessage: LostMessages.Lost? = null
        val goes = standDown.holding {
            if (!callInProgress) return@holding false
            stopped = CallNotice.Stopped.TRYING
            startsAtLoss = callStarts
            backSinceLoss = false
            // Every read that decides on these is under this lock; the mode
            // listener's one unlocked read of lostRecorder only picks its log
            // line (review round 32 - round 24's order no longer matters).
            lostRecorder = true
            if (messageTicket != null) {
                messageTicket = null
                recordingNow = true
                lostMessage = LostMessages.Lost(messageStartedAt, messageCall, testing)
                messageCall = null
            }
            true
        }
        if (!goes) return
        lostMessage?.let {
            // Its file is a message, which the recorder now cannot name.
            LostMessages.note(this, it)
            hangUpTheMessage(MessageHangUp.decide(it.callAt, LiveCalls.calls(), microphoneMuted()))
        }
        startForegroundCompat()
    }

    /** The call over, and no recorder said so (RecorderWatch): what its end
     *  would have done here, but the save - the recorder's copy is collected
     *  by the keep-alive's run, kicked now - and the service stands down. */
    private fun onCallOverUnsaid(endedAt: Int? = null, recorderDown: Boolean = false): Boolean {
        val watch = recorderWatch
        val starts = endedAt ?: callStarts
        // Looked at again under the lock: a call begun since the watch's
        // last look has its mode in a call, and is not over - its own start
        // left its state, and is watched anew (review round 10).
        val over = standDown.holding {
            // [endedAt]: the mode said the end as it happened, the call's
            // starts then - a call in its mode now is the next one
            // (callMaybeOver); one started since, or the recorder no longer
            // lost, is not ended here, looked at under the lock (review
            // round 22).
            if (endedAt != null) {
                if (!lostRecorder || callStarts != endedAt) return@holding false
            } else if (CaptureDaemon.callUp(this) || callApp == null && LiveCalls.held()) {
                // A phone call on hold is up whatever the mode, a ring over it
                // included (LiveCalls.held; review round 26).
                return@holding false
            }
            // Lost, or found down as the watch ended the call, or one started
            // since the newest file began, by another path: gone - perhaps
            // only after the call's end, which errs safe (cutSince) - so what
            // it left is a part, no whole call
            // (review round 29: killed in the call's last seconds, its loss
            // never seen, the file was judged a whole call; round 30: brought
            // back by another path between the watch's looks).
            val lost = lostRecorder || recorderDown || cutSinceBegun(callFiles)
            // A message under way, the recorder found down only now: its file
            // is a message the recorder can no longer name - noted as a loss
            // found mid-message notes it (onRecorderGone); unnoted, it was
            // saved as an ordinary call, a test call's kept (review round 30).
            // Noted here, under the lock, before the file's hold goes
            // (messageSince, below): a reconcile between took it for an
            // ordinary call (review round 31).
            if (messageTicket != null && (lost || cutSince(messageStartedAt))) {
                LostMessages.note(this, LostMessages.Lost(messageStartedAt, messageCall, testing))
            }
            callInProgress = false
            recordingNow = false
            stopped = null
            messageTicket = null
            messageCall = null
            askAnswer = null
            lostRecorder = false
            messageSince = 0L
            // Kicked below: owed no more here (review round 22). Its run's
            // reconcile, a call up by then, leaves them all - owed then by
            // RecordingSaver.reconcileOwed, which outlives this service
            // (rounds 24, 25).
            kickOwed = false
            callApp = null
            // Collected by the keep-alive kicked below, which brings the
            // recorder back first: no recorder answers a save here now - as
            // this call's parts, together (CallParts; review round 14). One
            // alone too, with the recorder lost: what it holds is only a part
            // of the call, no short call (review round 20).
            CallParts.note(this, callFiles.toList(), alone = lost)
            callFiles.clear()
            heldNow = emptySet()
            callEnds++
            recorderWatch = null
            true
        }
        if (!over) {
            // The mode's end not made: the call up keeps its watch.
            if (endedAt != null) return false
            recorderWatch = null
            watchTheRecorder()
            return false
        }
        // Ended by the mode, the watch still looking: done with it.
        if (endedAt != null) watch?.cancel()
        // Not a prompt a start posted since (review round 11).
        if (callStarts == starts) questionOver()
        // An alert said silently in the call is heard now (RecorderHealth).
        RecorderHealth.callOver(this)
        // In front still, as the call's service: no network waited for by
        // the run that collects and posts what a revive finds (review round
        // 13; one asked only while one is up - runConstraints).
        runCatching { RecorderKeepAlive.kickAfterCall(this) }
            .onFailure { Log.w(TAG, "monitor: could not kick the keep-alive after the call", it) }
        // Kept - a save still running, a start since - its notification
        // says what is left, not "Trying again" for a call that is over.
        if (!finishIfIdle(ending = watch)) runCatching { startForegroundCompat() }
        return true
    }

    /** A wake for a call this service has not heard start - this process
     *  started again during it: the call, recording, as its start said. */
    private fun callGoesOn() {
        if (callInProgress) return
        phoneOutAt = -1
        lastStartAtMs = System.currentTimeMillis()
        callInProgress = true
        recordingNow = true
        notRecording = false
        watchTheRecorder()
    }

    /** A capture reads again after [onRecordingStopped]. */
    private fun onRecordingBack() {
        callGoesOn()
        if (stopped == null) {
            startForegroundCompat()
            return
        }
        Log.i(TAG, "monitor: the recording goes on")
        stopped = null
        startForegroundCompat()
    }

    /** The user took the call over - unmuting on the call screen, Talk to
     *  them, or answering a second call: a call like any other now. Never
     *  late: the recorder sends no start once the call's end is on its way,
     *  and its wakes arrive in order. So it may well be the first this
     *  service hears of the call, if the message's own wake did not land. */
    private fun onMessageTaken() {
        Log.i(TAG, "monitor: the call was taken over")
        // Perhaps the first wake of the call (MESSAGE_STARTED dropped): no
        // start counted, the call before's mode left not this one's (review
        // round 25).
        if (!callInProgress) {
            phoneOutAt = -1
            lastStartAtMs = System.currentTimeMillis()
        }
        callInProgress = true
        watchTheRecorder()
        notRecording = false
        messageTaken()
        startForegroundCompat()
    }

    /** The recorder that took the message is gone, unseen - another's start
     *  says so: the message noted lost (LostMessages) as a loss the watch
     *  finds notes it, its call hung up, a kick owed (review round 31). Its
     *  window ends before [ownFile], what the recorder after it began, so
     *  that file is not taken for the message's (round 33). In the message's
     *  own call - [joined] into it, with Telecom's list that call still up
     *  and muted, without it the microphone still muted: nobody has it - a
     *  test call's [ownFile] is the test caller's too: noted as the test's
     *  other file, dropped by whichever save meets it, with no second test
     *  call counted (rounds 32, 33; kept, a test call's recording was the
     *  user's, round 14's harm). A real caller's last words there are a part
     *  by its name (joined, SavePlan.isPart). True if the call is being hung
     *  up. Read under the lock: the message's fields are its writes'. */
    private fun messageLostUnseen(ownFile: String?, joined: Boolean): Boolean {
        val ownStart = ownFile?.takeIf { it.isNotBlank() }?.let { DaemonFile.parse(it).start }
        val greetingAt = ownStart?.let { minOf(messageStartedAt, it - 1) } ?: messageStartedAt
        LostMessages.note(this, LostMessages.Lost(greetingAt, messageCall, testing))
        kickOwed = true
        val muted = microphoneMuted()
        val decision = MessageHangUp.decide(messageCall, LiveCalls.calls(), muted)
        val inItsCall = joined && (decision is MessageHangUp.Decision.End || messageCall == null && muted)
        if (testing && inItsCall && ownStart != null) {
            LostMessages.note(this, LostMessages.Lost(ownStart, ownStart, test = true, counted = false))
        }
        return hangUpTheMessage(decision)
    }

    private fun microphoneMuted() =
        runCatching { getSystemService(AudioManager::class.java).isMicrophoneMute }.getOrDefault(false)

    /**
     * The recorder lost while the machine took a message: its call hung up,
     * as the machine would have hung up on a recording that stopped - that
     * call and no other, while still active and muted (MessageHangUp). Left,
     * the caller heard silence until they hung up (since 0.17). Made where it
     * is decided, a start's path under the lock included: Call.disconnect
     * goes to Telecom through IInCallAdapter, a oneway interface (AOSP main,
     * read 2026-11-19), and waits on nothing - round 32 launched it on the
     * service's scope, which a stop of the service cancels first (round 33).
     * True if the call is being hung up.
     */
    private fun hangUpTheMessage(
        decision: MessageHangUp.Decision = MessageHangUp.decide(messageCall, LiveCalls.calls(), microphoneMuted()),
    ): Boolean = when (decision) {
        is MessageHangUp.Decision.End -> {
            // Whole, as the release check waits for it (LogLinesTest).
            Log.w(TAG, "monitor: the recorder was lost while taking a message; hanging up its call")
            if (!runCatching { LiveCalls.end(decision.id) }.getOrDefault(false)) {
                Log.w(TAG, "monitor: the message's call could not be hung up")
            }
            true
        }
        is MessageHangUp.Decision.Keep -> {
            Log.i(TAG, "monitor: the recorder was lost while taking a message; its call left up: ${decision.why}")
            false
        }
    }

    /** The message is the user's call now: recorded on, in Record every
     *  call. In Ask first the recorder stopped at the takeover, and its
     *  prompt (CALL_STARTED) asks about the rest. */
    private fun messageTaken() {
        messageCall = null
        messageTicket = null
        recordingNow = RecordingMode.of(this) == RecordingMode.AUTOMATIC
    }

    private fun onCallEnded(recfile: String?, incoming: Boolean) {
        Log.i(TAG, "monitor: call ended, recfile=$recfile")
        callEnds++
        recorderWatch?.cancel()
        callInProgress = false
        recordingNow = false
        stopped = null
        messageTicket = null
        messageCall = null
        askAnswer = null
        // If a start-of-call prompt is still up, the chance to answer it is gone.
        questionOver()
        // What a recorder lost mid-call had recorded is a file of its own:
        // saved now the call is over, as this one is (the reconcile leaves
        // it all alone while a call is up). And the keep-alive kicked for
        // any this service never heard of.
        // Not the end's own file under the name it began with: the recorder
        // renames it at the end (_early, a message's), and the old name was
        // saved again, to find it gone (review round 12).
        val endStart = recfile?.let { DaemonFile.parse(it).start }
        val others = synchronized(callFiles) {
            callFiles.filter { it != recfile && (endStart == null || DaemonFile.parse(it).start != endStart) }
                .also { callFiles.clear() }
        }
        // An alert said silently in the call is heard now (RecorderHealth).
        RecorderHealth.callOver(this)
        // Kicked once the call's own files are saved: its run's reconcile
        // took one of them first, saved as a call of its own (review round
        // 14). It is for files this service never heard of.
        val kickAfter = lostRecorder || kickOwed || RecordingSaver.reconcileOwed
        val lostHere = lostRecorder
        lostRecorder = false
        kickOwed = false
        callApp = null

        // Switched off after the daemon had already started (automatic): drop it.
        val switchedOff = !recfile.isNullOrBlank() && !RecorderSwitch.isOn(this)
        if (switchedOff) {
            Log.i(TAG, "monitor: recorder is off, discarding $recfile")
            launchRecording {
                RecordingSaver.deleteFromRecorder(this@CallMonitorService, recfile)
                finishIfIdle(ending = coroutineContext[Job])
            }
        }
        val own = recfile?.takeIf { it.isNotBlank() && !switchedOff }
        // The part a lost recorder left, with no file of this end's own -
        // Ask first asked again after the revive and not answered, its start
        // failed, or switched off since: a part, one alone too, no short
        // call (review rounds 21, 22). Lost unseen too: a recorder started
        // since the newest of them began (cutSinceBegun; review round 30). Not
        // by the end's naming none alone: the end of a call whose start never
        // came - left out by the rules, or its recording would not start -
        // names none either, the files then the call before's, whole, its own
        // end's wake lost (round 31).
        if (own == null && (lostHere || cutSinceBegun(others))) CallParts.note(this, others, alone = true)
        if (own == null && others.isEmpty()) {
            heldNow = emptySet()
            messageSince = 0L
            if (kickAfter) kickAfterTheCall()
            // Nothing was recorded: switched off, or on-demand never said yes to.
            if (!switchedOff) finishIfIdle()
            return
        }

        // A recording exists - automatic, or an on-demand call the user said yes
        // to - and any part a recorder lost mid-call left. Either way the
        // decision is made; fetch and save them.
        // Held until the save has them claimed: cleared before, a reconcile
        // could take one in between (review round 17).
        val saving = (others + listOfNotNull(own)).toSet()
        heldNow = saving
        // A message's file named now, and held by name: let go of by its
        // start only after (the reconcile reads the two the other way;
        // review round 30 - cleared first, a reconcile between took it).
        messageSince = 0L
        launchRecording {
            saveCall(own, incoming, others)
            // Under the lock a start writes it under: a redial's start in
            // between kept its hold (review round 29).
            standDown.holding { if (heldNow == saving) heldNow = emptySet() }
            if (kickAfter) kickAfterTheCall()
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

    /** The keep-alive kicked after a call whose recorder was lost, or after
     *  a reconcile left everything for this call (RecordingSaver.
     *  reconcileOwed; round 25): in front still, as the call's service - no
     *  network waited for by the run, which posts what a revive finds (review
     *  round 13). */
    private fun kickAfterTheCall() {
        Log.i(TAG, "monitor: kicking the keep-alive for what a reconcile left")
        runCatching { RecorderKeepAlive.kickAfterCall(this) }
            .onFailure { Log.w(TAG, "monitor: could not kick the keep-alive after the call", it) }
    }

    /**
     * A call's recordings saved together, and the call told of once: [own],
     * the file its end names (null for none, or switched off), and [others],
     * what a recorder lost mid-call left - each saved as the hang-up saves,
     * the call log waited for. The one told (AfterSave.toldOf: the most
     * audio saved; else one kept by another collector; nothing kept, [own]
     * unless it came to less than another's failure) is told as any call's
     * end is (HANG_UP: its notice, the missed-call check, or what went
     * wrong), last, so its notice is the call's; the rest as parts
     * (AfterSave.Collector.PART).
     * Told one by one, an empty end - a recorder revived seconds before the
     * hang-up - said "Call not recorded" for a call with minutes saved, the
     * part before did the same (review round 12), and the notice was the
     * part that finished last (round 13). More than one, each is a part, not
     * a short call (Retention).
     */
    private suspend fun saveCall(own: String?, incoming: Boolean, others: List<String>) {
        val files = others.map { it to DaemonFile.parse(it).incoming } + listOfNotNull(own?.let { it to incoming })
        // Noted first: a process killed mid-save leaves the rest to a
        // reconcile, which saves them as this call's parts (CallParts).
        CallParts.note(this, files.map { it.first })
        val ways = files.toMap()
        val outcomes = RecordingSaver.saveAll(this, files).map { (file, outcome) -> Triple(file, ways.getValue(file), outcome) }
        AfterSave.reactToCall(this, outcomes, own, AfterSave.Collector.HANG_UP, ::reportNotRecorded) {
            AfterCall.postNotRecorded(this, it, AfterCall.PART_NOT_SAVED)
        }
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
     * the others, is StandDown's. The stop is for the last start the service
     * took in when it looked: Android stops a service only for its latest
     * start, so a stop decided on a job's thread before a new wake - a call
     * starting as a save ends - is refused once that wake has come, where a
     * plain stopSelf took the service down under the new call. Every start's
     * changes share the look's lock (onStartCommand), so a look sees a start
     * whole, its id with it, or not at all. A job's stop is made outside the
     * lock, as a call into the system; a start that stands the service down
     * makes it still holding the lock its own changes were made under, on the
     * main thread, where nothing waits for a job that wants the lock.
     */
    private fun finishIfIdle(ending: Job? = null): Boolean {
        val start = standDown.holding {
            lastStart.takeIf { standDown.idle(ending, { callInProgress }) { listOf(cableWatch, warningJob) + saves } }
        } ?: return false
        return stopSelfResult(start)
    }

    private fun startForegroundCompat() {
        val tap = PendingIntent.getActivity(
            this, 0, DeepLink.home(this),
            PendingIntent.FLAG_IMMUTABLE,
        )
        // Each field read once: the save's coroutine writes some of them too.
        val notice = CallNotice.of(
            CallNotice.State(
                callUp = callInProgress,
                recording = recordingNow,
                notRecording = notRecording,
                stopped = stopped,
                messageTicket = messageTicket,
                testing = testing,
                checkingRecorder = checkingRecorder,
                warningOnly = warningOnly,
                recordTicket = RecordPrompt.ticket(this),
            ),
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tap)
            .setOngoing(true)
            // "An indication of running background service"
            // (Notification.CATEGORY_SERVICE): what this is, for the shade.
            .setCategory(Notification.CATEGORY_SERVICE)
        notice.buttonTicket?.let { ticket ->
            builder.addAction(messageAction("Talk to them", ACTION_TALK, ticket))
                .addAction(messageAction("Hang up", ACTION_HANG_UP, ticket))
        }
        notice.recordTicket?.let { builder.addAction(RecordPrompt.keptRecord(this, it)) }
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
        // The ongoing notification must stop claiming otherwise - for the
        // call that ended. A call up now, the next one, says what is
        // happening to it: a failure set on it said "Not recording this
        // call" over a call being recorded, and went on saying it for the
        // calls after.
        val ended = standDown.holding {
            (!callInProgress).also { ended ->
                if (ended) {
                    recordingNow = false
                    notRecording = true
                }
            }
        }
        if (ended) runCatching { startForegroundCompat() }
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

        /** Sent by the daemon when the call's recording stopped mid-call
         *  ([EXTRA_FOR_GOOD]: not even tried again), and when it goes on. */
        const val ACTION_RECORDING_STOPPED = "com.jemcik.jemrec.RECORDING_STOPPED"
        const val ACTION_RECORDING_BACK = "com.jemcik.jemrec.RECORDING_BACK"
        const val EXTRA_FOR_GOOD = "for_good"

        /** On CALL_STARTED: a recorder started again mid-call joined the
         *  call (Calls.joinedExtra). */
        const val EXTRA_JOINED = "joined"

        /** The watch's wake lock's cap. */
        private const val WATCH_AWAKE_MS = 3 * 60 * 60 * 1000L
        const val EXTRA_TEST = "test"
        /** With a test call's end: when its recording began, wall clock. */
        const val EXTRA_STARTED = "started"
        /** The "Taking a message" notification's buttons. */
        const val ACTION_TALK = "com.jemcik.jemrec.TALK"
        const val ACTION_HANG_UP = "com.jemcik.jemrec.HANG_UP"
        const val EXTRA_TOKEN = "token"

        /**
         * Whether this service exists in this process right now. A stop is
         * sent only to one that does ([stop]): a STOP to a service that is
         * not running would first bring it up, notification and all - and
         * retiring a wedged daemon (DaemonLauncher.retire) clears a
         * notification that daemon left up by it.
         */
        @Volatile
        var running = false
            private set

        /** The files of the call up, as its service holds them - from the
         *  call's start until its end's save has claimed them: a reconcile
         *  leaves every one to the call's end (RecordingSaver.reconcile;
         *  review rounds 16 and 28 - a joined part still being written, and
         *  the call's own file in the seconds before its end's wake). */
        @Volatile
        internal var heldNow: Set<String> = emptySet()

        /** A message under way since then (its file not named till its end:
         *  MESSAGE_STARTED carries none): a reconcile leaves a file begun
         *  about then to its end (review round 29). 0 for none. */
        @Volatile
        internal var messageSince = 0L

        /** When Telecom's last call went (CallTracker.onCallRemoved, exact
         *  call tracking on): the phone call up is over then. */
        @Volatile
        internal var telecomOutAtMs = 0L

        /** The thread the service's work runs on, and the recorder's answer
         *  to a Record tap: today's own, and the tests' in the tests
         *  (CallMonitorServiceTest). Read as a service is made. */
        @VisibleForTesting
        internal var dispatcher: CoroutineDispatcher = Dispatchers.IO

        @VisibleForTesting
        internal var startOnDemand: suspend (Context) -> String? = CaptureDaemon::startOnDemand

        /** How the service hears the audio mode: AudioManager's own
         *  listener, on the main thread; the tests' own - Robolectric's
         *  AudioManager tells no listener, and a registration taken out
         *  failed no test (review round 12). */
        @VisibleForTesting
        internal var listenToMode: (Context, AudioManager.OnModeChangedListener) -> Unit = { context, listener ->
            context.getSystemService(AudioManager::class.java).addOnModeChangedListener(context.mainExecutor, listener)
        }

        /** Whether the recorder answers as a call leaves its mode with the
         *  recorder lost (callMaybeOver): one ping, at once - for one back
         *  some way other than the watch's revive, which says so itself
         *  (backSinceLoss). Not the five more the watch asks: their 2.4 s
         *  let a recorder back after the call took it for one in it (review
         *  round 22). The tests' own. */
        @VisibleForTesting
        internal var recorderUpNow: suspend (Context) -> Boolean = { CaptureDaemon.isRunning(it) }

        /** One ping, then the five a destructive step asks before it
         *  believes the recorder gone (DaemonLauncher.isRunningConfirmed):
         *  the watch's ask. */
        private suspend fun recorderAnswers(context: Context): Boolean =
            CaptureDaemon.isRunning(context) || CaptureDaemon.isRunningConfirmed(context)

        /** How long a call stays out of its mode before it is over. */
        private const val OUT_CONFIRM_MS = 1_500L

        /** Ask first's button over the call: its window, or the tests' own. */
        @VisibleForTesting
        internal var buttonFor: (Context, (ticket: String, record: Boolean) -> Unit) -> AskButton = ::CallButtonWindow

        /** The watch on the recorder for a call (RecorderWatch): asked of
         *  Android and the recorder here; the tests' own, or none. */
        @VisibleForTesting
        internal var watchFor: (Context, WatchHooks) -> RecorderWatch? = { context, hooks ->
            RecorderWatch(
                callUp = { CaptureDaemon.callUp(context) },
                anyCall = { CaptureDaemon.callInProgress(context) },
                watching = { com.jemcik.jemrec.recorder.RecorderSwitch.isOn(context) },
                recorderUp = { recorderAnswers(context) },
                // Back, the keep-alive's run looks at what is pending - the
                // part before it leaves while the call is up, and the call's
                // end saves it (callFiles).
                revive = { CaptureDaemon.revive(context).also { if (it) runCatching { RecorderKeepAlive.kickNow(context, inFront = true) } } },
                stopped = hooks.stopped,
                over = hooks.over,
                log = { Log.i(TAG, it) },
                starts = hooks.starts,
                ringIsTheCall = hooks.ringIsTheCall,
                back = hooks.back,
            )
        }

        /** What a call's watch tells its service, and asks of it
         *  (RecorderWatch's own words for each). */
        internal class WatchHooks(
            val stopped: () -> Unit,
            val over: (recorderUp: Boolean) -> Unit,
            val starts: () -> Int,
            val ringIsTheCall: () -> Boolean,
            val back: () -> Unit,
        )

        /** And to Talk to them (true) or Hang up. */
        @VisibleForTesting
        internal var handOver: suspend (Context, Boolean) -> Boolean = CaptureDaemon::handOver

        /**
         * Stopped, if it runs: a start with STOP, which waits its turn behind
         * any start already sent. Not stopService: the recorder wakes the
         * service with startForegroundService, and a service stopped before
         * that start reached startForeground makes Android end the app -
         * "Context.startForegroundService() did not then call
         * Service.startForeground()" (ActiveServices.bringDownServiceLocked,
         * fgRequired; review, 2026-11-18) - an Off tapped as a call ended,
         * mid-save. A start to a service already started is allowed from the
         * background, except under Battery Restricted; refused there, the
         * stop is stopService after all. Until 1.2 the start went whether
         * the service ran or not, and made one not running - its notice
         * posted - only to stop it. Ask first's question goes with it, and
         * its ticket: JemRec switched off mid-call hears no end of that call
         * to take them down (the recorder's endWakes), and a ticket is live
         * only until the call is over - whether the service runs or not, as
         * the question outlives its process.
         */
        fun stop(context: Context) {
            RecordPrompt.dismiss(context)
            if (!running) return
            val stop = Intent(context, CallMonitorService::class.java)
                .setAction(ACTION_STOP)
                .putExtra(EXTRA_TOKEN, CaptureDaemon.token(context))
            runCatching { context.startService(stop) }
                .onFailure { refused ->
                    Log.w(TAG, "service: the stop's start was refused; stopping it directly", refused)
                    runCatching { context.stopService(Intent(context, CallMonitorService::class.java)) }
                }
        }
    }
}
