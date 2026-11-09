package com.jemcik.jemrec.ui

import android.app.Application
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.jemcik.jemrec.DeepLink
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.answering.AnsweringMachine
import com.jemcik.jemrec.calls.AfterCall
import com.jemcik.jemrec.calls.CallLogLookup
import com.jemcik.jemrec.calls.CallRules
import com.jemcik.jemrec.calls.CallTracking
import com.jemcik.jemrec.calls.MissedCalls
import com.jemcik.jemrec.calls.VoipApps
import com.jemcik.jemrec.calls.VoipCallNotes
import com.jemcik.jemrec.capture.AudioAccess
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.DebugNotificationCleaner
import com.jemcik.jemrec.capture.LastCallReport
import com.jemcik.jemrec.capture.PlaybackService
import com.jemcik.jemrec.capture.RecordingMode
import com.jemcik.jemrec.recorder.BackgroundKill
import com.jemcik.jemrec.recorder.DebugSwitchesOff
import com.jemcik.jemrec.recorder.NetworkRevive
import com.jemcik.jemrec.recorder.OffMode
import com.jemcik.jemrec.recorder.RecorderHealth
import com.jemcik.jemrec.recorder.RecorderLifecycle
import com.jemcik.jemrec.recorder.RecorderSwitch
import com.jemcik.jemrec.recorder.SelfTest
import com.jemcik.jemrec.recorder.StartWithoutWifi
import com.jemcik.jemrec.recorder.Trigger
import com.jemcik.jemrec.recordings.FileNames
import com.jemcik.jemrec.recordings.Recording
import com.jemcik.jemrec.recordings.RecordingMarks
import com.jemcik.jemrec.recordings.RecordingStore
import com.jemcik.jemrec.recordings.RecordingTags
import com.jemcik.jemrec.recordings.Recordings
import com.jemcik.jemrec.recordings.Retention
import com.jemcik.jemrec.recordings.Trash
import com.jemcik.jemrec.setup.PairingNotification
import com.jemcik.jemrec.setup.Setup
import com.jemcik.jemrec.setup.SetupStep
import com.jemcik.jemrec.system.AutoStart
import com.jemcik.jemrec.ui.theme.LightDark
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.Looks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.cancellation.CancellationException
/** How long the header says "Starting..." for a recorder started from the
 *  tile before it takes a fresh reading anyway: the Honor's took 6 s, and
 *  one that needs Wi-Fi there is none of will not come at all. */
private const val RECORDER_ELSEWHERE_MS = 30_000L

/** Why Start recorder did not start it, by the cause its revive left; null
 *  is no cause kept - JemRec off, or setup unfinished. */
internal fun notStarted(cause: RecorderHealth.Problem?): String = when (cause) {
    RecorderHealth.Problem.NEEDS_WIFI -> "it needs Wi-Fi for a few seconds, or Start without Wi-Fi's port"
    RecorderHealth.Problem.NETWORK_NOT_ALLOWED -> "Android has not allowed Wireless debugging on this Wi-Fi"
    RecorderHealth.Problem.CANNOT_CONNECT -> "JemRec cannot connect to Wireless debugging - the pairing may be gone"
    RecorderHealth.Problem.CANNOT_START -> "it would not start on this network"
    null -> "JemRec is off, or setup has not finished"
}

class MainViewModel @JvmOverloads constructor(
    app: Application,
    /** Tests seed the list through this; the app never passes it. Without it
     *  the only way recordings entered state was refreshNow(), which is
     *  device-bound, and the tests reached into the flow by reflection. */
    initial: UiState = UiState(),
    /** The same, for the answering machine's screen. */
    initialAnswer: AnswerUi? = null,
    /** How it reaches PlaybackService's session: Media3's controller in the
     *  app. The screen tests give one that never connects - Robolectric
     *  binds no real service, and Media3 fails on its stand-in binding. */
    connectSession: (Application) -> ListenableFuture<MediaController> = { a ->
        MediaController.Builder(a, SessionToken(a, ComponentName(a, PlaybackService::class.java))).buildAsync()
    },
    /** One look of setup's background connect (reconnect) for adbd, true
     *  once connected. The tests count the looks. */
    private val lookForAdb: suspend (Application) -> Boolean = { a ->
        AdbTransport.autoConnect(a, timeoutMs = 6_000).isSuccess
    },
    /** How long after a flip of Start without Wi-Fi a close still due is
     *  tried again (setStartWithoutWifi). The tests' is short. */
    private val portCloseAgainMs: Long = 10_000,
) : AndroidViewModel(app) {

    // Whether messenger calls can work here is known at once (a cached
    // permission check): the Settings card must not show their switches'
    // text for a moment on a phone where they cannot work.
    private val _state = MutableStateFlow(initial.copy(voipSupported = VoipApps.supported(app)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** What the screen says about what it did: Diagnostics' log, snackbars. */
    private val log = ActionLog(_state)

    /** Each action as the busy policy's table says (Action). */
    private val actions = Actions(_state, viewModelScope, log)

    /** The recording loaded, and the player in PlaybackService that plays it. */
    private val playback = Playback(app, viewModelScope, _state, log, connectSession)

    /** Share's question, and what it hands to Android's share sheet. */
    private val sharing = Sharing(app, viewModelScope, _state, log)

    /** Waveforms worked out for the list's rows while the screen is up. */
    private val fill = WaveformFill(app, viewModelScope, _state)

    /** The answering machine's settings, greeting and diagnostics. */
    val answer = AnswerController(app, viewModelScope, log::say, initialAnswer, quiet = playback::pause)

    /** The screen's reading of everything it shows from outside itself. */
    private val reading = Reading(app, viewModelScope, _state, answer, playback, fill)

    /** Recently deleted's buttons, and Android's question before a move. */
    private val trash = TrashActions(app, _state, log, actions, reading)

    /** Failures of something the user just did, for a snackbar where they are. */
    val notices: SharedFlow<String> = log.notices

    /** A reading of everything, for callers that only want the screen to
     *  catch up (Reading.refresh). */
    fun refresh() = reading.refresh()

    private suspend fun refreshNow() = reading.now()

    /** The switch can be flipped from the Quick Settings tile while this
     *  screen is up, without the screen ever pausing. See RecorderSwitch.observe. */
    private val stopWatchingSwitch = RecorderSwitch.observe(app) { on ->
        val was = _state.value.enabled
        _state.update { it.copy(enabled = on) }
        // On from elsewhere - the tile - with this screen up: the header's
        // last reading of the recorder is from before it went, and said
        // "Ready" six seconds before the recorder was back (the Honor,
        // 2026-10-26). A flip from this screen is handled by setEnabled.
        if (on && !was) awaitRecorderStartedElsewhere()
    }

    /** What went off with the switch, as it changes - from the tile too. */
    private val stopWatchingDebugSwitches = DebugSwitchesOff.observe(app) { gone ->
        _state.update { it.copy(debuggingOff = gone) }
    }

    private val awaitingRecorder = AtomicBoolean(false)

    /**
     * "Starting..." until the recorder answers, then a fresh reading. Only
     * waits: whoever switched JemRec on is starting the recorder, and a
     * second revive here could run before the switches are back and be
     * turned away by them (DebugSwitchesOff.recorderGone). Thirty seconds at
     * most - off Wi-Fi it will not come, and the reading then says so.
     */
    private fun awaitRecorderStartedElsewhere() {
        if (!awaitingRecorder.compareAndSet(false, true)) return
        starts.begin()
        viewModelScope.launch {
            try {
                kotlinx.coroutines.withTimeoutOrNull(RECORDER_ELSEWHERE_MS) {
                    while (!CaptureDaemon.isRunning(getApplication())) kotlinx.coroutines.delay(1_000)
                }
                refreshNow()
            } finally {
                awaitingRecorder.set(false)
                starts.end()
            }
        }
    }

    /**
     * Prove the recording chain end to end, without ringing anyone.
     *
     * No file is written and no report is saved to disk: the old one wrote a
     * report to external storage, which made sense while there was an
     * acceptance check to sign off and is litter now.
     */
    fun selfTest() = actions.run(Action.SELF_TEST) {
        val result = SelfTest.run(getApplication())
        // Replace, don't stack: the log should be THIS self-test, not a pile of
        // every one before it. Cleared here, once the new result is ready,
        // rather than at the tap - so the previous result stays put while the
        // test runs and then swaps in, with no empty gap.
        _state.update { it.copy(log = "") }
        say(result.report.trimEnd())
        // For Diagnostics to scroll down to it - this, not every log line.
        _state.update { it.copy(selfTestShown = System.currentTimeMillis()) }
    }

    /** Copy a report, on Diagnostics: this phone, its settings and the
     *  recorder's account of the last call (LastCallReport). */
    fun copyCallReport() {
        val app = getApplication<Application>()
        actions.run(Action.COPY_REPORT) {
            val text = LastCallReport.text(app, appVersion(app), CaptureDaemon.callReport(app))
            reportToClipboard(app, "JemRec call report", text)
        }
    }

    /** Diagnostics scrolled to the self-test's result; not again on returning. */
    fun selfTestSeen() = _state.update { it.copy(selfTestShown = 0L) }

    fun showSettings() {
        _state.update { it.copy(screen = Screen.SETTINGS) }
    }

    fun showTrash() {
        _state.update { it.copy(screen = Screen.TRASH) }
    }

    fun showScreen(screen: Screen) {
        _state.update { it.copy(screen = screen) }
    }

    /** One step back: a screen reached from Settings returns to it. */
    fun back() = _state.update { it.copy(screen = it.screen.up) }

    /** Switch tabs. Leaving Recordings ends a selection, which only means
     *  something there; arriving at Calls reads the call log. */
    fun showTab(tab: HomeTab) {
        if (_state.value.homeTab == tab) return
        _state.update { it.copy(homeTab = tab, selecting = false, selected = emptySet()) }
        if (tab == HomeTab.CALLS) refresh()
    }

    fun askReset() = _state.update { it.copy(confirmingReset = true) }

    fun cancelReset() = _state.update { it.copy(confirmingReset = false) }

    /**
     * Undo setup and go back to the wizard.
     *
     * Ends on the HOME screen deliberately. After a reset there is no longer a
     * paired phone to have settings about, and leaving the user looking at a
     * settings page for an app that now needs setting up would be a strange
     * place to put them.
     */
    fun confirmReset() {
        _state.update { it.copy(confirmingReset = false) }
        actions.run(Action.START_FRESH) {
            Setup.reset(getApplication())
                .onSuccess { _state.update { s -> s.copy(resetDone = true) } }
                .onFailure { say("could not reset: ${it.message}") }
        }
    }

    /** Work out which setup step we are on, and refresh everything else too. */
    fun refreshSetup() = actions.run(Action.CHECK_SETUP) { recheckSetup() }

    /**
     * The header's Allow this Wi-Fi, and the alert's: Android asks again, its
     * question over this screen, and the recorder starts once adbd is
     * advertised (CaptureDaemon.askToAllowNetwork), with Starting in the
     * header meanwhile - as a revive, not holding the screen still
     * (reviveInBackground). It waits its turn while the screen is busy rather
     * than being dropped, as setEnabled does: asked for while the recorder is
     * down, when the opening's check holds busy for seconds, the tap went
     * without a sign. One at a time - a second tap while one runs is the same
     * question - and one while another revive runs waits for it, under the
     * revive's lock.
     */
    fun allowThisWifi() {
        if (!askingForWifi.compareAndSet(false, true)) return
        starts.begin()
        actions.run(Action.ALLOW_THIS_WIFI) {
            try {
                if (!CaptureDaemon.askToAllowNetwork(getApplication())) {
                    say("could not switch Wireless debugging - open it in Developer options and allow this network")
                }
                refreshNow()
            } catch (e: CancellationException) {
                throw e // the screen gone: not a failure to report
            } catch (t: Throwable) {
                Log.w("JemRec", "allow this Wi-Fi failed", t)
                say("allow this Wi-Fi failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                askingForWifi.set(false)
                starts.end()
            }
        }
    }

    private val askingForWifi = java.util.concurrent.atomic.AtomicBoolean(false)

    /** "They are on already", from the Developer options card on a phone that
     *  hides them. See Setup.developerOptions. */
    fun confirmDeveloperOptions() = actions.run(Action.DEVELOPER_OPTIONS_ON) {
        Setup.confirmDeveloperOptions(getApplication())
        recheckSetup()
    }

    /**
     * The background-start setup card's "Not now": mark the question asked and
     * move on. JemRec cannot read the vendor setting, so the only thing ever
     * recorded is that it was offered (see [openBackgroundSettings] for the
     * other button).
     */
    fun confirmBackgroundChoice() = actions.run(Action.BACKGROUND_NOT_NOW) {
        Setup.markBackgroundChoice(getApplication())
        recheckSetup()
    }

    /**
     * The card's other button: open the vendor screen, and count that as the
     * answer - JemRec cannot read the setting, so going there is all it can
     * know. Setup moves on when the user comes back, because the step is
     * worked out again every time the app returns to the foreground.
     */
    fun openBackgroundSettings() {
        val app = getApplication<Application>()
        Setup.markBackgroundChoice(app)
        AutoStart.open(app)
    }

    /**
     * Work out where setup stands and act on it.
     *
     * Extracted so that anything which changes the world can finish by asking
     * what the world now looks like. Turning JemRec on used to derive the step
     * by hand and got it wrong: it checked a second before the watchdog got the
     * recorder up, landed on FINISHING, and stayed there - a spinner reading
     * "Finishing setup" over a phone that was already recording perfectly well,
     * with no path out until the screen happened to be reopened.
     */
    private suspend fun recheckSetup() {
        // DELIBERATELY NOT BLANKING THE STEP.
        //
        // This set CHECKING first, so every return to the app replaced the card
        // the user was reading with "Checking..." for as long as the checks
        // took - seconds, on battery. The state starts as CHECKING anyway, so
        // the first look still shows it; a re-check just leaves the last answer
        // on screen until it has a new one.
        // Keep the daemon alive from a scheduled job rather than a permanent
        // service. Idempotent, so it is safe to (re-)arm on every check; it does
        // nothing while the app is switched off.
        // The Wi-Fi watch too, on every open: it is lost to a force-stop or an
        // app update, and opening the app is when the user most expects "back
        // on Wi-Fi means ready" to hold.
        if (RecorderLifecycle.goes(getApplication(), Trigger.SETUP_CHECK)) {
            RecorderLifecycle.arm(getApplication(), Trigger.SETUP_CHECK)
        }
        // Keep the daemon's recording mode in step with the app's settings, even
        // when the daemon was already up (so revive did not run). Cheap, and it
        // is how the daemon learns automatic vs ask-first after a plain reopen.
        pushDaemonMode()
        val step = Setup.currentStep(getApplication())
        val background = if (step == SetupStep.NEEDS_BACKGROUND_CHOICE) {
            withContext(Dispatchers.IO) { AutoStart.screen(getApplication()) }
        } else {
            null
        }
        _state.update {
            it.copy(
                step = step,
                pairingLost = it.pairingLost && step != SetupStep.READY,
                backgroundLabel = background?.label,
                backgroundInstruction = background?.instruction,
            )
        }

        // THE REPLY FIELD HAS TO BE THERE BEFORE THE USER NEEDS IT.
        //
        // Once the pairing dialog is open, coming back to JemRec to press a
        // button would close it - which is the entire difficulty of this step.
        // So the notification is posted the moment pairing becomes the thing to
        // do, not when a button is pressed. By the time the user is standing in
        // Settings looking at six digits, the place to type them already exists.
        if (step == SetupStep.NEEDS_PAIRING) {
            PairingNotification.show(getApplication())
        } else {
            PairingNotification.dismiss(getApplication())
        }

        if (step == SetupStep.FINISHING) finishSetupInternal()
        refreshNow()

        // THE SCREEN MUST NOT KEEP A READING IT ALREADY KNOWS IS BEING FIXED.
        //
        // currentStep() now answers READY for a set-up phone whose daemon is
        // merely down, which is right - but refreshNow() then reports the
        // recorder stopped, and the header says in red that the phone cannot
        // record and needs Wi-Fi while the keep-alive is quietly starting it a
        // couple of seconds later. Nothing asked again, so that stale red
        // sentence was what the user saw until they reopened the app: the
        // "sometimes it says the recorder is not running" half of the app
        // starting inconsistently.
        //
        // So bring it up here and say so while it happens.
        if (step == SetupStep.READY && _state.value.enabled && !_state.value.daemonRunning) {
            reviveInBackground()
        }
    }

    /**
     * Wi-Fi came while the screen is up. If the recorder is down, this is the
     * moment it can come back - and doing it from here puts "Starting..." in
     * the header at once, instead of leaving "needs Wi-Fi" there until the
     * keep-alive job has been scheduled, run and reported. Gated on READY and
     * on the last reading being "down": the callback also fires on
     * registration, before anything has been read.
     */
    fun onWifiAvailable() = reviveIfDown(CaptureDaemon.Request.ROUTINE)

    /** The header's Tap to start again: the recorder down on Wi-Fi, for a
     *  reason the last revive could not name - started again now, rather
     *  than at the keep-alive's next turn, and allowed to switch Wireless
     *  debugging off and on again for an adbd still stopped. */
    fun startAgain() = reviveIfDown(CaptureDaemon.Request.USER)

    private fun reviveIfDown(request: CaptureDaemon.Request) {
        val s = _state.value
        if (s.step == SetupStep.READY && s.enabled && !s.daemonRunning) reviveInBackground(request)
    }

    private var pairingWatch: Job? = null

    /**
     * The pairing refused, home in view: setup's pairing step, which says why
     * (pairingLostOnHome). Watched, not checked at a refresh: the refusal that
     * settles it comes from a revive, whose refresh ran while the header still
     * read Starting, and the header was left saying Tap to pair again (on the
     * Honor, 2026-10-24). From the screen's first showing, as only then does
     * the step matter. Wrong, and setup's next look connects and finishes by
     * itself (Setup.forgetPairing).
     */
    private fun watchPairing() {
        if (pairingWatch != null) return
        pairingWatch = viewModelScope.launch {
            _state.map(::pairingLostOnHome).distinctUntilChanged().collect { lost -> if (lost) pairAgain() }
        }
    }

    /**
     * Pair again: from the header, and from the recorder-down alert. Wireless
     * debugging is there and turns JemRec away, which is what a deleted
     * pairing looks like (RecorderHealth.CANNOT_CONNECT); setup opens on the
     * pairing step. If the pairing was fine after all, setup's next look
     * connects and finishes by itself (Setup.forgetPairing).
     */
    fun pairAgain() {
        Setup.forgetPairing(getApplication())
        RecorderHealth.pairingAgain(getApplication())
        // Refused no longer, until a revive says so again: kept, setup back on
        // READY by itself - the pairing fine after all - was sent straight
        // back here.
        _state.update { it.copy(screen = Screen.HOME, pairingLost = true, pairingRefused = false) }
        viewModelScope.launch {
            actions.free()
            refreshSetup()
        }
    }

    /**
     * Bring the recorder back without holding the screen still while it happens.
     *
     * Deliberately not an action (Actions): reviving re-arms Wireless
     * debugging, waits for adbd, opens an ADB session and starts the daemon,
     * which is seconds - and an action would hold `busy` for all of them,
     * greying out every control on a screen that is working perfectly well. `starting` is the
     * flag that already exists for exactly this, and the header reads it rather
     * than announcing a failure that is being repaired as it is read.
     */
    private fun reviveInBackground(request: CaptureDaemon.Request = CaptureDaemon.Request.ROUTINE) {
        if (!reviving.compareAndSet(false, true)) return
        starts.begin()
        viewModelScope.launch {
            try {
                CaptureDaemon.revive(getApplication(), request = request)
                refreshNow()
            } catch (e: CancellationException) {
                throw e // the screen gone: not a failure to report
            } catch (t: Throwable) {
                Log.w("JemRec", "reviving the recorder failed", t)
            } finally {
                reviving.set(false)
                starts.end()
            }
        }
    }

    /**
     * Post the pairing code field, so it is waiting before Settings opens.
     *
     * The Settings launch itself lives in the setup screen, off the foreground
     * Activity - a launch from the Application context is blocked before adb
     * exists, which is exactly when this screen runs. Posting again here, when
     * refreshSetup has already posted, is deliberate: a user who swiped the
     * notification away has no other route back to it and would arrive at the
     * dialog with nowhere to type.
     */
    fun showPairingNotification() = PairingNotification.show(getApplication())

    /**
     * Post a notification with a reply field, so the code can be typed from the
     * shade without leaving the Settings dialog.
     *
     * This is the way, not a convenience. Switching apps closes the pairing
     * dialog, and an overlay cannot help because Android hides overlays over
     * Settings. The shade is a system surface, so it does not disturb the
     * dialog at all - measured.
     */
    fun startPairingNotification() = actions.run(Action.PAIR_FROM_SHADE) {
        PairingNotification.show(getApplication())
        say("check your notifications - you can type the code there")
    }

    /**
     * Connect in the background while the user is turning Wireless debugging on
     * in Settings - the same trick pairing uses, and for the same reason.
     *
     * The connect must not wait for the user to come back to JemRec: returning
     * here backgrounds the Wireless debugging screen, and on Honor that is often
     * exactly what drops Wireless debugging. So this polls for a reachable phone
     * for a while, catching the window while the user is still on the Settings
     * screen with Wireless debugging up. We are already paired, so a connect that
     * lands lets recheckSetup finish setup outright.
     *
     * Waits its turn rather than being dropped: Open settings is tapped
     * whatever the screen is doing - a resume's check holds busy for seconds
     * - and goes to Settings at once, so a connect dropped there never ran
     * while the user was in Settings. One at a time: a tap while one waits
     * or runs adds nothing, each looking for up to 36 s.
     */
    fun reconnect() {
        if (!reconnecting.compareAndSet(false, true)) return
        actions.run(Action.CONNECT) {
            try {
                val app = getApplication<Application>()
                repeat(6) {
                    if (lookForAdb(app)) {
                        recheckSetup()
                        return@run
                    }
                }
                recheckSetup()
            } finally {
                reconnecting.set(false)
            }
        }
    }

    /** A connect of [reconnect]'s waiting or under way. */
    private val reconnecting = AtomicBoolean(false)

    private suspend fun finishSetupInternal() {
        _state.update { it.copy(step = SetupStep.FINISHING, finishFailed = null) }
        Setup.finish(getApplication())
            .onSuccess { say("recorder started") }
            .onFailure {
                say("could not finish setup: ${it.message}")
                // The step is FINISHING again, and its spinner says there is
                // nothing left to do: with a Try again it is not a dead end.
                _state.update { s -> s.copy(finishFailed = it.message ?: "something went wrong") }
            }
        _state.update { it.copy(step = Setup.currentStep(getApplication())) }
    }

    /** The last step of setup, again, after it failed. */
    fun retryFinish() = actions.run(Action.FINISH_SETUP) { finishSetupInternal() }

    /**
     * Start the recorder: Diagnostics' Start recorder.
     *
     * A revive the user asked for, as Tap to start again is
     * (CaptureDaemon.Request.USER): Wireless debugging switched on, or off
     * and on, where a revive would; the session opened; the recorder started
     * from the jar this APK carries; the session closed again. It asked for
     * the start alone, which never connects - and a revive closes its session
     * once the recorder is up - so it failed unless a session happened to be
     * open. Why not, when not: the cause the revive left (RecorderHealth).
     */
    fun bootstrapDaemon() = actions.run(Action.START_RECORDER) {
        val app = getApplication<Application>()
        if (CaptureDaemon.revive(app, request = CaptureDaemon.Request.USER)) {
            say("recorder running on 127.0.0.1:${CaptureDaemon.PORT}")
        } else {
            say("could not start the recorder: ${notStarted(RecorderHealth.cause(app))}")
        }
        refresh()
    }

    /** Never offered again once waved away. An optional feature that keeps
     *  asking is not optional. */
    fun dismissCallLogOffer() {
        CallLogLookup.dismissBanner(getApplication())
        _state.update { it.copy(offerCallLog = false) }
    }

    /**
     * The user has answered the audio-access request, either way.
     *
     * Granted, the recordings from before the reinstall appear on the next
     * refresh. Refused, the app carries on recording and seeing everything it
     * makes from now on - which is why nothing here treats "no" as a problem.
     */
    fun onAudioAccessResult() {
        _state.update { it.copy(canReadAudio = AudioAccess.granted(getApplication())) }
        refresh()
    }

    /**
     * The permission dialog has closed, whichever way and whichever of the two
     * were granted.
     *
     * The verdict is read back from the system rather than taken from the
     * callback: the user can grant one and refuse the other, and the system is
     * the only thing that knows which.
     */
    fun onCallLogResult() {
        val app = getApplication<Application>()
        // Anything refused counts as an answer. Asking again next time the app
        // opens would be nagging, which is what the offer exists to avoid.
        if (CallLogLookup.anythingLeftToOffer(app)) CallLogLookup.dismissBanner(app)
        _state.update {
            it.copy(canReadCallLog = CallLogLookup.granted(app), offerCallLog = false)
        }
        refresh()
    }

    /**
     * The record control. Turning off asks first in the one case it costs
     * more than it says: all debugging off, and no Wi-Fi to come back on
     * over (OffMode.asksFirst). Everything else goes straight to [setEnabled].
     */
    fun toggleRecording(on: Boolean) {
        // The port's reading the screen shows ("Back on: anywhere"), so the
        // question and the screen agree - and no getprop on the main thread.
        if (!on && DebugSwitchesOff.asksBeforeOff(getApplication()) { _state.value.portSet }) {
            _state.update { it.copy(askingToTurnOff = true) }
            return
        }
        setEnabled(on)
    }

    /** The question's answer: [turnOff], or leave JemRec on. */
    fun answerTurnOff(turnOff: Boolean) {
        _state.update { it.copy(askingToTurnOff = false) }
        if (turnOff) setEnabled(false)
    }

    /** When JemRec is off: applies from the next off, and On puts back what
     *  the last off really switched off whatever this says by then. */
    fun setOffMode(mode: OffMode) {
        DebugSwitchesOff.setMode(getApplication(), mode)
        _state.update { it.copy(offMode = mode) }
    }

    /**
     * Settings, Start without Wi-Fi. Saved at once; the port follows at the
     * first revive that can change it - this one, if it can
     * (PortChange.portChangeDue), or a later one. The change restarts
     * adbd, and the recorder with it, so it is revived in the background:
     * Starting in the header until it is back, and the rest of the screen
     * left usable - an action would hold `busy`, and every control with it,
     * for all of it.
     *
     * Its own revive, not reviveInBackground's, which stands aside for one
     * already under way - and that one, or a keep-alive run (kickNow keeps a
     * run already going), may have read the switch before this flip. Revives
     * queue on the recorder's lock, so this one comes after them and finds
     * the switch as it is now.
     *
     * Every flip has one, and what is due is asked there, under the lock.
     * Asked here, a flip that came while a revive changed the port found
     * nothing to change, the change not noted yet, and asked for none: on the
     * Honor, 2026-11-05, on and straight off, the off landing as adbd
     * restarted, left 5555 open under a switch that says off.
     *
     * A close still due after it is tried once more, a moment on. adbd's
     * restart takes Wireless debugging's advertisement with it, and a revive
     * that can connect only over the port does not close it (portChangeOver):
     * on the same phone and day, on and off half a second apart, nothing was
     * advertised 9 s after the restart, and on the first try the port stayed
     * open. It was advertised again 15 s after one. Once, as on a Wi-Fi
     * Android has not allowed it never comes; the keep-alive is left the rest.
     *
     * A refusal of this Wi-Fi a background revive noted is forgotten
     * (NetworkRevive.noteWirelessRefused) when the flip asks for a change of
     * the port: the one flipping the switch is there to answer Android's
     * question, which would otherwise not be asked, and the switch would do
     * nothing at all. Kept for a flip that changes nothing, or the next
     * background revive would ask again.
     */
    fun setStartWithoutWifi(on: Boolean) {
        val app = getApplication<Application>()
        StartWithoutWifi.setEnabled(app, on)
        _state.update { it.copy(startWithoutWifi = on) }
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                if (StartWithoutWifi.step(app) != StartWithoutWifi.PortStep.LEAVE) NetworkRevive.forgetWirelessRefused(app)
            }
            reviveForThePort()
            if (withContext(Dispatchers.IO) { StartWithoutWifi.step(app) } == StartWithoutWifi.PortStep.CLOSE) {
                delay(portCloseAgainMs)
                reviveForThePort()
            }
        }
    }

    /**
     * A revive for Start without Wi-Fi's switch: Starting once the change
     * begins - the restart of adbd and the recorder - not for one that ends
     * up changing nothing, as most find: one that connects over the port
     * itself leaves a close for a Wi-Fi with Wireless debugging
     * (portChangeOver).
     */
    private suspend fun reviveForThePort() {
        val began = AtomicBoolean(false)
        try {
            CaptureDaemon.revive(getApplication(), onPortChange = { if (began.compareAndSet(false, true)) starts.begin() })
        } catch (e: CancellationException) {
            throw e // the screen gone: not a failure to report
        } catch (t: Throwable) {
            Log.w("JemRec", "the port change's revive failed", t)
        } finally {
            if (began.get()) starts.end()
        }
        refreshNow()
    }

    /**
     * Turn JemRec on or off.
     *
     * A soft switch, see RecorderSwitch: off tells the daemon to capture
     * nothing and stops the per-call service; the daemon itself stays up, so
     * on is instant anywhere. It used to stop the daemon too, which made on
     * the expensive direction - a respawn needs an ADB session and therefore
     * Wi-Fi - and stranded an "off" in the field until the next network.
     */
    fun setEnabled(on: Boolean) {
        // `starting` matters as much as `enabled` here. The switch flips at
        // once, but the recorder takes a couple of seconds to come up, and in
        // between the screen still held the reading from when it was off - so
        // it announced in red that the phone could not record and needed Wi-Fi,
        // while the recorder was starting normally. An error shown for a normal
        // operation in progress is how people learn to ignore errors. And
        // shown before the switch is saved, so the switch's own listener
        // (stopWatchingSwitch) takes this flip for this screen's, not the
        // tile's.
        if (on) starts.begin()
        _state.update { it.copy(enabled = on) }
        // Saved at once, outside the action below, which waits its turn.
        RecorderSwitch.set(getApplication(), on)
        // The recorder follows in the process's own scope, as for the Quick
        // Settings tile (RecorderSwitch.turn), not in this screen's: closing
        // JemRec from Recents ends this screen's scope with the process
        // alive - Back does not, since Android 12 - and an Off waiting there
        // for the screen to be free was lost with it, debugging never
        // switched off.
        // Turns queue there, each dropped if the switch has moved on - a
        // quick on and off ends off.
        val turned = RecorderSwitch.turnLater(getApplication(), on)
        // What the screen does after it waits its turn rather than being
        // dropped: the record control takes a tap while the app is busy -
        // each opening's check holds busy for seconds - and the tap was
        // dropped without a sign, the control looking as ever.
        actions.run(if (on) Action.TURN_ON else Action.TURN_OFF) {
            try {
                turned.join()
                if (RecorderSwitch.isOn(getApplication()) != on) return@run
                // Ask what the world looks like rather than assuming. Called
                // directly, not via refreshSetup(), because that is a dropped
                // action and one will not start while this one is running.
                recheckSetup()
            } finally {
                // Whatever happened: "Starting..." outranks "Ready" in the
                // header, and left set it stays there with the recorder up -
                // unless another start is still under way (startingEnded).
                if (on) starts.end()
            }
        }
    }

    /**
     * Switch between recording every call and being asked each time.
     *
     * Written straight through rather than deferred, because the very next call
     * could arrive seconds from now and it must use the setting the user just
     * chose, not the one this screen last read.
     */
    fun setAutomatic(automatic: Boolean) {
        RecordingMode.set(
            getApplication(),
            if (automatic) RecordingMode.AUTOMATIC else RecordingMode.ON_DEMAND,
        )
        _state.update { it.copy(automatic = automatic) }
        // The daemon decides at off-hook whether to record or to ask first, so
        // it has to be told which the moment this changes.
        actions.run(Action.TELL_RECORDER) { pushDaemonMode() }
    }

    private suspend fun pushDaemonMode() = CaptureDaemon.pushMode(getApplication())

    /**
     * Hide Android's debugging notification, or stop hiding it: the answer to
     * setup's question, and the Settings switch after it.
     *
     * During setup the answer is only recorded - finish() grants notification
     * access over the session it opens anyway. After setup a yes needs that
     * access, which only an ADB session can grant; if there is none to be had,
     * the switch stays off and says why rather than claiming a yes it cannot
     * act on.
     */
    fun setHideDebugNotification(hide: Boolean) = actions.run(Action.DEBUG_NOTIFICATION) {
        val app = getApplication<Application>()
        if (hide && Setup.isComplete(app) && !DebugNotificationCleaner.accessGranted(app) &&
            !Setup.grantNotificationAccess(app)
        ) {
            _state.update { it.copy(debugNotificationProblem = true) }
            return@run
        }
        DebugNotificationCleaner.choose(app, hide)
        _state.update { it.copy(debugNotificationHidden = hide, debugNotificationProblem = false) }
        // From the setup step, move on to the next one.
        if (_state.value.step != SetupStep.READY) recheckSetup()
    }

    /** The Settings switch for the notification that follows each call.
     *  Problems are still reported with it off - see AfterCall. */
    fun setAfterCallNotice(on: Boolean) {
        AfterCall.setEnabled(getApplication(), on)
        _state.update { it.copy(afterCallNotice = on) }
    }

    /** The app repaints at once: both activities draw from Looks, and
     *  Settings reads the choice from the theme (LocalLookChoice). */
    fun setLook(look: Look) {
        val app = getApplication<Application>()
        Looks.set(app, Looks.choice(app).copy(look = look))
        // Multitrack's caller colours are given only while it is the look.
        if (look == Look.MULTITRACK) refresh()
    }

    fun setLightDark(lightDark: LightDark) {
        val app = getApplication<Application>()
        Looks.set(app, Looks.choice(app).copy(lightDark = lightDark))
    }

    /**
     * A notification's button brought the user here: play that call, share
     * it, run the self-test, or pair again.
     *
     * Each waits until nothing else is running, because opening the app from
     * a notification is also a resume, and the resume's own setup check holds
     * the busy flag - which would otherwise swallow the self-test or the
     * re-pair the user just asked for.
     */
    fun open(link: DeepLink.Link) {
        DeepLink.consumed()
        viewModelScope.launch {
            val app = getApplication<Application>()
            when (link.kind) {
                DeepLink.Kind.PLAY, DeepLink.Kind.SHARE -> {
                    refreshNow()
                    val recording = _state.value.recordings.firstOrNull { it.name == link.name }
                    if (recording == null) {
                        // Gone by another way than JemRec's: a file manager's.
                        link.name?.let { AfterCall.gone(app, it) }
                        tell("that recording is no longer there")
                        return@launch
                    }
                    // Its row has to be on screen for its player to be, so
                    // whatever was narrowing the list goes.
                    _state.update { it.revealed() }
                    if (link.kind == DeepLink.Kind.SHARE) {
                        share(recording)
                    } else if (_state.value.playingName != recording.name || !_state.value.playing) {
                        play(recording)
                    }
                }
                DeepLink.Kind.SELF_TEST -> {
                    MissedCalls.dismiss(app)
                    showScreen(Screen.DIAGNOSTICS)
                    actions.free()
                    selfTest()
                }
                DeepLink.Kind.ALLOW_WIFI -> {
                    _state.update { it.copy(screen = Screen.HOME) }
                    actions.free()
                    allowThisWifi()
                }
                DeepLink.Kind.PAIR_AGAIN -> pairAgain()
                // From the "stopped in the background" warning: open the vendor
                // auto-start screen. JemRec cannot change the setting itself,
                // so the button only takes the user there. BackgroundKill.cancel
                // clears the notification now that it has been acted on.
                DeepLink.Kind.BACKGROUND_SETTINGS -> {
                    BackgroundKill.cancel(app)
                    AutoStart.open(app)
                }
            }
        }
    }

    fun setQuery(query: String) = _state.update { current ->
        // Searching stays live during selection now, so a query can narrow the
        // list out from under picked rows. Any selection that no longer matches
        // has left the visible list, and must leave the selection with it - a
        // later delete acts on names, and would otherwise take a row the user
        // can no longer see. Widening the query keeps everything, as it should.
        val next = current.copy(recordingsNarrowing = current.recordingsNarrowing.copy(query = query))
        next.copy(selected = stillShown(next))
    }

    /** A chip of the Recordings tab tapped. Leaves a selection to the rows
     *  still shown, as a search does: the chips are hidden while selecting,
     *  but whichever way the list narrows, a delete must not take a row it
     *  has stopped showing - only the sources' chip made sure of that. */
    internal fun tapRecordingsChip(chip: ListChip) = _state.update { current ->
        val next = current.copy(recordingsNarrowing = current.recordingsNarrowing.tapped(chip))
        next.copy(selected = stillShown(next))
    }

    /** The narrowing line's Show all: every chip off, the search folded away
     *  with what was typed. Widening, so the selection stays as it is. */
    fun showAllRecordings() = _state.update { it.copy(recordingsNarrowing = RecordingsNarrowing()) }

    fun showAllCalls() = _state.update { it.copy(callsNarrowing = CallsNarrowing()) }

    /** A chip of the Calls tab tapped. */
    internal fun tapCallsChip(chip: ListChip) = _state.update { it.copy(callsNarrowing = it.callsNarrowing.tapped(chip)) }

    fun setCallsQuery(query: String) = _state.update { it.copy(callsNarrowing = it.callsNarrowing.copy(query = query)) }

    /** Mark one recording to keep, or take the mark off. Kept recordings are
     *  never auto-deleted. */
    fun toggleKept(recording: Recording) {
        RecordingMarks.Keep.set(getApplication(), listOf(recording.name), !recording.kept)
        refresh()
        keepInFiles(listOf(recording))
    }

    /** The mark into the files too, so a reinstall finds it (RecordingTags):
     *  as the preferences have it when the write runs, which is after the
     *  list has it - the preferences say it at once, the files take a write
     *  each. */
    private fun keepInFiles(recordings: List<Recording>) {
        viewModelScope.launch(Dispatchers.IO) { RecordingTags.writeKept(getApplication(), recordings) }
    }

    /** The selection toolbar's keep: marks every picked recording, or - when
     *  all of them are kept already - takes the mark off them all. */
    fun keepSelected() {
        val current = _state.value
        val picked = current.recordings.filter { it.name in current.selected }
        if (picked.isEmpty()) return
        val kept = !picked.all { it.kept }
        RecordingMarks.Keep.set(getApplication(), picked.map { it.name }, kept = kept)
        _state.update { it.copy(selecting = false, selected = emptySet()) }
        refresh()
        keepInFiles(picked.filter { it.kept != kept })
    }

    /** One of auto-delete's two rules changed: saved, and applied at once. */
    fun setAutoDelete(afterDays: Int? = null, shorterThan: Int? = null) = actions.run(Action.AUTO_DELETE) {
        val app = getApplication<Application>()
        afterDays?.let { Retention.setMaxAgeDays(app, it) }
        shorterThan?.let { Retention.setMinSeconds(app, it) }
        val result = withContext(Dispatchers.IO) { Retention.run(app) }
        if (result.deleted > 0) say("auto-delete moved ${result.deleted} recording(s) to Recently deleted")
        // Recordings Android wants confirmed are asked about now, while the
        // user is here; the daily pass cannot ask.
        result.needsConsent?.let { askAndroid(it, result.disputed) }
        refreshNow()
    }

    /** Whose calls to keep. Applied to calls from now on; see CallRules. */
    fun setRuleContacts(contacts: CallRules.Contacts) {
        CallRules.setContacts(getApplication(), contacts)
        _state.update { it.copy(ruleContacts = contacts) }
        // The chosen contacts are read only for the choices that list them.
        refresh()
    }

    fun setRuleOtherNumbers(on: Boolean) {
        CallRules.setOtherNumbers(getApplication(), on)
        _state.update { it.copy(ruleOtherNumbers = on) }
    }

    /**
     * The contact the picker returned, added to the rules' list - while
     * busy too (Action.RULE_CONTACT_ADDED): coming back from the picker
     * resumes the screen, and the resume's own setup check holds busy.
     */
    fun addRuleContact(contact: Uri) {
        actions.run(Action.RULE_CONTACT_ADDED) {
            val added = withContext(Dispatchers.IO) { CallRules.add(getApplication(), contact) }
            if (!added) tell("could not add that contact")
            refreshNow()
        }
    }

    fun removeRuleContact(key: String) {
        actions.run(Action.RULE_CONTACT_REMOVED) {
            withContext(Dispatchers.IO) { CallRules.remove(getApplication(), key) }
            refreshNow()
        }
    }

    /** Exact call tracking on or off; the daemon allows or withdraws it at once. */
    fun setCallTracking(on: Boolean) {
        val app = getApplication<Application>()
        CallTracking.setEnabled(app, on)
        _state.update { it.copy(callTracking = on) }
        // The answering machine answers on tracking's word alone: left on
        // without it, it would say it answers and never would. Settings asks
        // before this (SettingsDialog.TRACKING_OFF).
        if (!on && (answer.state.value.enabled || AnsweringMachine.enabled(app))) {
            answer.setEnabled(false)
            say("the answering machine is off too: it needs exact call tracking")
        }
        answer.reload()
        actions.run(Action.TELL_RECORDER) {
            if (!CaptureDaemon.setTracking(app)) {
                say("the recorder will pick up exact call tracking when it next restarts")
            }
        }
    }

    /** Record [pkg]'s calls, or stop; the daemon is told at once. */
    fun setVoipApp(pkg: String, on: Boolean) {
        val app = getApplication<Application>()
        VoipApps.setEnabled(app, pkg, on)
        _state.update { it.copy(voipEnabled = VoipApps.enabled(app)) }
        // The last one off stops the caller-name listener; the first one on
        // starts it again, if names were asked for.
        DebugNotificationCleaner.apply(app)
        actions.run(Action.TELL_RECORDER) {
            if (!CaptureDaemon.setVoipApps(app)) {
                say("the recorder will pick up the messenger apps when it next restarts")
            }
        }
    }

    /** Caller names for messenger calls, from their call notifications. Needs
     *  notification access, granted over ADB like the debugging one. */
    fun setVoipNames(on: Boolean) = actions.run(Action.MESSENGER_NAMES) {
        val app = getApplication<Application>()
        if (on && !DebugNotificationCleaner.accessGranted(app) && !Setup.grantNotificationAccess(app)) {
            _state.update { it.copy(voipNamesProblem = true) }
            return@run
        }
        VoipCallNotes.setEnabled(app, on)
        DebugNotificationCleaner.apply(app)
        _state.update { it.copy(voipNames = on, voipNamesProblem = false) }
    }

    /** How new recordings are named. Ones already saved keep their names. */
    fun setFileNames(format: FileNames.Format) {
        FileNames.setFormat(getApplication(), format)
        _state.update { it.copy(fileNames = format) }
    }

    fun useDefaultFolder() = actions.run(Action.DEFAULT_FOLDER) {
        RecordingStore.clearTree(getApplication())
        say("recordings will go to the standard Recordings folder")
        refresh()
    }

    /** While busy too, as addRuleContact: the picker's answer arrives as the
     *  screen resumes, while its check runs (Action.FOLDER_CHOSEN). */
    fun onFolderChosen(uri: Uri) {
        actions.run(Action.FOLDER_CHOSEN) {
            withContext(Dispatchers.IO) { RecordingStore.setTree(getApplication(), uri) }
            say("recordings will be saved to the folder you chose")
            refreshNow()
        }
    }

    /** The list's waveform fill-in stops with the screen (WaveformFill). */
    fun stopFillingIn() = fill.stop()

    /** Guards against two bring-ups racing when the screen is reopened. */
    private val reviving = AtomicBoolean(false)

    /** Every start the screen shows as Starting (StartsUnderWay). */
    private val starts = StartsUnderWay(_state)

    /** The screen came forward: the pairing watched from now on, and the
     *  player shows what the session plays (Playback.shown). */
    fun screenShown() {
        watchPairing()
        playback.shown()
    }

    /** Out of sight: the scrubber is no longer driven, and the recording
     *  plays on (Playback.hidden). */
    fun screenHidden() = playback.hidden()

    /**
     * Android's answer to "add the tile": added, already there, or declined.
     * The answer is what Android knows, so it settles what the Settings row
     * says - and "already there" is said back, so a tap is never met with
     * nothing, which is what an added tile's row did before.
     */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun onTileAddAnswer(result: Int) {
        val added = when (result) {
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED,
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> true
            StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> false
            else -> return
        }
        RecorderTile.setAdded(getApplication(), added)
        _state.update { it.copy(tileAdded = added) }
        if (result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED) {
            tell("it is in your Quick Settings panel already")
        }
    }

    /** Play or pause a recording (Playback). */
    fun play(recording: Recording) = playback.play(recording)

    fun seekTo(ms: Int) = playback.seekTo(ms)

    /** The chevron on the scrubber: stop, and fold the row back. */
    fun stopPlaying() = playback.stopPlayback()

    private fun stopPlayback() = playback.stopPlayback()

    /** Share's question first (Sharing). */
    fun share(recording: Recording) = sharing.share(recording)

    fun dismissShareChoice() = sharing.dismissShareChoice()

    fun shareOriginal(recording: Recording) = sharing.shareOriginal(recording)

    fun shareUniversal(recording: Recording) = sharing.shareUniversal(recording)

    fun cancelConversion() = sharing.cancelConversion()

    /** Back out of the trash, as it was (TrashActions). */
    fun restore(recording: Recording) = trash.restore(recording)

    fun restoreAll() = trash.restoreAll()

    fun askEmptyTrash() = trash.askEmptyTrash()

    fun cancelEmptyTrash() = trash.cancelEmptyTrash()

    fun confirmEmptyTrash() = trash.confirmEmptyTrash()

    fun onDeleteConsentShown() = trash.onDeleteConsentShown()

    fun onDeleteConsentResult(granted: Boolean) = trash.onDeleteConsentResult(granted)

    private fun askAndroid(request: IntentSender, leaving: List<String> = emptyList()) = trash.askAndroid(request, leaving)

    /** The screen is gone for good; a recording playing goes on playing,
     *  under the system's controls, until they stop it. */
    override fun onCleared() {
        stopWatchingSwitch()
        stopWatchingDebugSwitches()
        playback.close()
    }

    /**
     * Show the next batch of older recordings.
     */
    fun showMore() = _state.update { it.copy(visibleCount = it.visibleCount + PAGE) }

    /**
     * Enter selection mode from the header, with nothing chosen yet.
     *
     * Long-press is the documented way into selection on Android and it is
     * supported below, but it is an invisible affordance: nothing on screen
     * says it is there. This is the visible way in, for the majority who will
     * never think to try holding a row down.
     */
    fun startSelecting() {
        // Selecting stops playback. The scrubber is hidden while selecting,
        // so a recording left playing under it was playing invisibly, with no
        // control on screen that could stop it.
        stopPlayback()
        _state.update {
            rememberSelectHintSeen()
            it.copy(selecting = true, selected = emptySet(), selectHintSeen = true)
        }
    }

    /** Long-press, or a tap on a row's photo: enter selection with this one chosen. */
    fun startSelecting(recording: Recording) {
        stopPlayback()
        _state.update {
            rememberSelectHintSeen()
            it.copy(selecting = true, selected = setOf(recording.name), selectHintSeen = true)
        }
    }

    /** The tip's x: it is not shown again. */
    fun dismissSelectHint() {
        rememberSelectHintSeen()
        _state.update { it.copy(selectHintSeen = true) }
    }

    /** The tap-a-photo hint has done its job once selection has been used. */
    private fun rememberSelectHintSeen() {
        Prefs.of(getApplication()).edit { putBoolean("select_hint_seen", true) }
    }

    fun toggleSelected(recording: Recording) = _state.update { current ->
        val next = if (recording.name in current.selected) {
            current.selected - recording.name
        } else {
            current.selected + recording.name
        }
        // Unpicking the last one leaves selection mode, as Gmail does: an empty
        // selection toolbar is a mode hanging around with nothing to act on, and
        // the photo that dropped you in is the natural way back out.
        if (next.isEmpty()) current.copy(selecting = false, selected = emptySet())
        else current.copy(selected = next)
    }

    /** Everything currently listed, which is not necessarily everything there
     *  is - the list pages, and selecting rows the user cannot see would be a
     *  quiet way to delete more than they meant to. */
    fun selectAllVisible() = _state.update { current ->
        // "All visible" means all that pass the search and preset - the same
        // rows the list is showing - not every recording on disk.
        val visible = filterRecordings(current.recordings, current.recordingsNarrowing).take(current.visibleCount)
        current.copy(selected = visible.map { it.name }.toSet())
    }

    fun clearSelection() = _state.update { it.copy(selecting = false, selected = emptySet()) }

    /** Unpick everything but stay in selection mode: the user is still
     *  choosing, they have just changed their mind about all of it. */
    fun deselectAll() = _state.update { it.copy(selected = emptySet()) }

    fun askDeleteSelected() = _state.update { it.copy(confirmingDeleteSelected = true) }

    fun cancelDeleteSelected() = _state.update { it.copy(confirmingDeleteSelected = false) }

    /**
     * Delete what is selected - to Recently deleted, where it can be restored
     * for 30 days (see Trash).
     *
     * Selection mode ends whether or not every deletion succeeded. Leaving the
     * user in a toolbar showing a count that no longer means anything is worse
     * than making them tap Select again.
     */
    fun confirmDeleteSelected() {
        _state.update { it.copy(confirmingDeleteSelected = false) }
        actions.run(Action.DELETE_SELECTED) {
            val current = _state.value
            val doomed = current.recordings.filter { it.name in current.selected }
            if (doomed.isEmpty()) return@run
            if (current.playingName in current.selected) stopPlayback()

            val result = withContext(Dispatchers.IO) {
                Trash.trashAll(getApplication(), doomed)
            }
            // Whatever Android wants confirmed is not moved yet - its own
            // dialog comes next - so it is counted as asked, not as failed.
            val asked = doomed.size - result.deleted - result.failed
            val summary = listOfNotNull(
                "moved ${result.deleted} to Recently deleted".takeIf { result.deleted > 0 },
                "asking Android about $asked".takeIf { result.needsConsent != null && asked > 0 },
                "could not move ${result.failed}".takeIf { result.failed > 0 },
            ).joinToString(", ")
            if (result.failed > 0) tell(summary) else say(summary)
            result.needsConsent?.let { askAndroid(it, result.disputed) }
            _state.update { it.copy(selecting = false, selected = emptySet()) }
            refresh()
        }
    }

    private fun tell(text: String) = log.tell(text)

    private fun say(text: String) = log.say(text)
}
