package com.jemcik.jemrec.ui

import androidx.annotation.RequiresApi
import android.os.Build
import android.app.StatusBarManager
import android.app.Application
import android.util.Log
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.adb.AdbTransport
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import android.os.SystemClock
import android.content.ComponentName
import android.media.AudioManager
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.jemcik.jemrec.DeepLink
import com.jemcik.jemrec.capture.AfterCall
import com.jemcik.jemrec.capture.BackgroundKill
import com.jemcik.jemrec.capture.MissedCalls
import kotlinx.coroutines.flow.first
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import com.jemcik.jemrec.capture.AnsweringMachine
import com.jemcik.jemrec.capture.AudioAccess
import com.jemcik.jemrec.capture.CallHistory
import com.jemcik.jemrec.capture.CallRules
import com.jemcik.jemrec.capture.CallTracking
import com.jemcik.jemrec.capture.FileNames
import com.jemcik.jemrec.capture.VoipApps
import com.jemcik.jemrec.capture.VoipCallNotes
import com.jemcik.jemrec.capture.CallLogLookup
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.LastCallReport
import com.jemcik.jemrec.capture.DebugNotificationCleaner
import com.jemcik.jemrec.capture.DebugSwitchesOff
import com.jemcik.jemrec.capture.GlobalSettings
import com.jemcik.jemrec.capture.NetworkRevive
import com.jemcik.jemrec.capture.OffMode
import com.jemcik.jemrec.capture.RecorderHealth
import com.jemcik.jemrec.capture.RecorderKeepAlive
import com.jemcik.jemrec.capture.DeleteResult
import com.jemcik.jemrec.capture.BulkDeleteResult
import com.jemcik.jemrec.capture.Recording
import com.jemcik.jemrec.capture.RecordingTags
import com.jemcik.jemrec.capture.Recordings
import com.jemcik.jemrec.capture.Transcoder
import androidx.core.content.FileProvider
import java.io.File
import com.jemcik.jemrec.capture.RecorderSwitch
import com.jemcik.jemrec.capture.RecordingMode
import com.jemcik.jemrec.capture.PlaybackRules
import com.jemcik.jemrec.capture.PlaybackService
import com.jemcik.jemrec.capture.RecordingStore
import com.jemcik.jemrec.capture.StartWithoutWifi
import com.jemcik.jemrec.capture.Trash
import com.jemcik.jemrec.capture.Waveforms
import com.jemcik.jemrec.capture.Retention
import com.jemcik.jemrec.capture.KeepMarks
import com.jemcik.jemrec.capture.SelfTest
import com.jemcik.jemrec.ui.theme.LightDark
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.Looks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.Date
import java.util.Locale

/** Recordings added to the list per "show more" tap. */
private const val PAGE = 20

/** How long a recording the waveform fill-in could not decode waits before
 *  it is tried again: long enough that a file that will not decode does not
 *  log its warning at every refresh, short enough that one that failed for
 *  the moment gets its waveform the same day. */
private const val RETRY_UNDECODABLE_MS = 30 * 60_000L

/** How long the header says "Starting..." for a recorder started from the
 *  tile before it takes a fresh reading anyway: the Honor's took 6 s, and
 *  one that needs Wi-Fi there is none of will not come at all. */
private const val RECORDER_ELSEWHERE_MS = 30_000L

/** How much diagnostics log to keep. Roughly a screen or two of history,
 *  which is as far back as anyone reads. */
private const val LOG_LIMIT = 8_000

/**
 * Which screen is showing.
 *
 * No navigation library. The whole app is a status page, a place to put the
 * things that are not it, and a few screens reached from there; a nav graph to
 * express "or one of the others" would be more machinery than the problem has.
 * [up] is the whole of the back stack.
 */
enum class Screen {
    HOME, SETTINGS,
    // Reached from Settings, and back to it.
    WHOSE_CALLS, MESSENGER_CALLS, DIAGNOSTICS, TRASH, ANSWERING_MACHINE, THEME, WHEN_OFF;

    /** Where Back goes from here. */
    val up: Screen get() = when (this) {
        HOME, SETTINGS -> HOME
        WHOSE_CALLS, MESSENGER_CALLS, DIAGNOSTICS, TRASH, ANSWERING_MACHINE, THEME, WHEN_OFF -> SETTINGS
    }
}

/**
 * The two tabs of the home screen: the recordings, and the calls.
 *
 * Tabs, not a bottom bar: Material 3 says not to use a navigation bar for
 * fewer than three destinations, and to put primary tabs under the app bar
 * (m3.material.io, Navigation bar and Tabs guidelines).
 */
enum class HomeTab { RECORDINGS, CALLS }

data class UiState(
    val step: SetupStep = SetupStep.CHECKING,
    val screen: Screen = Screen.HOME,
    /** Waiting for the user to confirm starting over. */
    val confirmingReset: Boolean = false,
    /**
     * The reset has run and the app should close.
     *
     * Revoking permissions only takes effect once the app is no longer in
     * front of the user, so staying on screen would leave it looking as if
     * nothing happened, with the permissions still granted.
     */
    val resetDone: Boolean = false,
    /** A self-test's result just arrived (its time), for Diagnostics to
     *  scroll down to; 0 once it has. */
    val selfTestShown: Long = 0L,
    /** Setup's last step failed, and why: shown with a Try again. */
    val finishFailed: String? = null,
    val busy: Boolean = false,
    val connected: Boolean = false,
    val daemonRunning: Boolean = false,
    /**
     * Whether Wireless debugging is switched on - the channel the app needs to
     * relaunch the recorder. Defaults true so a not-yet-read state never blames
     * it. When the recorder is down, this is what tells "waiting for Wi-Fi"
     * apart from "the way back in has been turned off", so the header can point
     * at the right lever instead of always saying Wi-Fi.
     */
    val wirelessDebuggingOn: Boolean = true,
    /**
     * Whether USB debugging is on. On this phone Wireless debugging only stays
     * on when this is, so setup asks for it first - see Setup.usbDebugging.
     * UNKNOWN where Android 17 hides it; the cards then mention it instead of
     * waiting for it. Defaults ON so a not-yet-read state never accuses.
     */
    val usbDebugging: GlobalSettings.Reading = GlobalSettings.Reading.ON,
    /**
     * This phone hides whether Developer options are on (see GlobalSettings),
     * so their card offers "They are on already" rather than waiting for a
     * setting it will never see change.
     */
    val developerOptionsHidden: Boolean = false,
    /**
     * The recorder is down because Android has not allowed Wireless debugging
     * on this Wi-Fi (see RecorderHealth), so the header offers to ask again
     * instead of telling someone already on Wi-Fi to join one.
     */
    val networkNotAllowed: Boolean = false,
    /**
     * The recorder is down because Wireless debugging turns JemRec away, twice
     * in a row - a deleted pairing (RecorderHealth.CANNOT_CONNECT): home gives
     * way to setup's pairing step. Through 0.19 the header said "Join any Wi-Fi"
     * for it, with the phone on Wi-Fi (the user, on the Honor, 2026-10-24).
     */
    val pairingRefused: Boolean = false,
    /** Whether the phone is on Wi-Fi, the one network the recorder can come
     *  back on (NetworkRevive.onWifi). */
    val onWifi: Boolean = true,
    /** Setup is on its pairing step because Wireless debugging turned JemRec
     *  away (pairAgain): the step says so, as nobody chose to set up again. */
    val pairingLost: Boolean = false,
    /** On the background step, the screen its button opens: the name
     *  (AutoStart.Screen.label) and what to do there. Found with the step, on
     *  IO - each candidate screen is a call into the package manager - and
     *  null on every other step. */
    val backgroundLabel: String? = null,
    val backgroundInstruction: String? = null,
    /**
     * Whether JemRec's notifications are off, or the pairing channel muted, so
     * the pairing code would have nowhere to appear. Only read on the pairing
     * step, where it raises a warning and a fix button. Defaults false so it
     * never accuses before it has been looked at.
     */
    val pairingNotificationsBlocked: Boolean = false,
    /** An OPPO, OnePlus or realme phone that may refuse setup's grant until
     *  its own switch is on (ShellGrant), so the pairing card names it. */
    val systemOptimizationSwitch: Boolean = false,
    val folder: String = "",
    val usingDefaultFolder: Boolean = true,
    /** True when every call is recorded without asking. */
    val automatic: Boolean = true,
    /** Whether the user chose to hide Android's debugging notification. */
    val debugNotificationHidden: Boolean = false,
    /** Whether a notification with Play, Share and Delete follows each call. */
    val afterCallNotice: Boolean = true,
    /**
     * Turning the hiding on from Settings could not grant notification access,
     * which takes an ADB session and so Wi-Fi. Shown under the switch until the
     * next try.
     */
    val debugNotificationProblem: Boolean = false,
    /** Whether the call log has been offered to the app, and whether the
     *  one-time offer is still worth showing. */
    val canReadCallLog: Boolean = false,
    val canReadAudio: Boolean = false,
    val offerCallLog: Boolean = false,
    /** Whether JemRec is switched on at all. */
    val enabled: Boolean = true,
    /** What turning JemRec off switches off besides: Settings, When JemRec
     *  is off (DebugSwitchesOff). */
    val offMode: OffMode = OffMode.KEEP_DEBUGGING,
    /** Off with all debugging, so with the recorder gone: On needs Wi-Fi,
     *  unless [portSet]. */
    val debuggingOff: Boolean = false,
    /** Settings, Start without Wi-Fi (StartWithoutWifi): the switch; whether
     *  adbd is on its port until the next restart, so that the recorder can
     *  start with no Wi-Fi; whether JemRec opened it, so closes it once the
     *  switch is off; and whether asking adbd for it failed this boot. */
    val startWithoutWifi: Boolean = false,
    val portSet: Boolean = false,
    val portOurs: Boolean = false,
    val portFailed: Boolean = false,
    /** Switched off, and asking adbd to close JemRec's port left it set
     *  (StartWithoutWifi.closeFailedThisBoot). */
    val portCloseFailed: Boolean = false,
    /** The question before turning off with all debugging, away from Wi-Fi,
     *  is on screen (TurnOffAwayFromWifiDialog). */
    val askingToTurnOff: Boolean = false,
    /**
     * Switching on, and not yet finished.
     *
     * Its own flag rather than the general busy one, which is raised by
     * every background check including the one on every screen open - using
     * that would make the header flicker each time the app is opened.
     */
    val starting: Boolean = false,
    val recordings: List<Recording> = emptyList(),
    /** The Recordings tab's chips and search. */
    val recordingsNarrowing: RecordingsNarrowing = RecordingsNarrowing(),
    /** The Calls tab's own chips and search. */
    val callsNarrowing: CallsNarrowing = CallsNarrowing(),
    /** What is in Recently deleted, soonest to go first. See Trash. */
    val trashed: List<Recording> = emptyList(),
    /** Which tab of the home screen is showing. */
    val homeTab: HomeTab = HomeTab.RECORDINGS,
    /** The call log, matched to recordings. Read only while the Calls tab is
     *  showing - it is a query and a contacts lookup per number. */
    val calls: List<CallHistory.Call> = emptyList(),
    /** Multitrack's caller colours, by name key (CallerColours). */
    val callerColours: Map<String, Int> = emptyMap(),
    /** Auto-delete's two rules, 0 for off. See Retention. */
    val autoDeleteAfterDays: Int = 0,
    val autoDeleteShorterThan: Int = 0,
    /** Whose calls to keep - see CallRules - and the contacts chosen for it. */
    val ruleContacts: CallRules.Contacts = CallRules.Contacts.ALL,
    val ruleOtherNumbers: Boolean = true,
    val ruleChosen: List<CallRules.Chosen> = emptyList(),
    /** Whether the contacts may be read: the rules need them to tell who is who. */
    val canReadContacts: Boolean = false,
    /** How new recordings are named. See FileNames. */
    val fileNames: FileNames.Format = FileNames.Format.DATE,
    /** Exact call tracking - see CallTracking. On unless turned off. */
    val callTracking: Boolean = true,
    /** Messenger calls: the supported apps installed here, the ones switched
     *  on, and whether caller names are read from their notifications. */
    val voipInstalled: List<VoipApps.App> = emptyList(),
    /** Whether messenger calls can be recorded here at all (VoipApps.supported). */
    val voipSupported: Boolean = true,
    val voipEnabled: Set<String> = emptySet(),
    val voipNames: Boolean = false,
    /** Turning caller names on could not grant notification access, which
     *  takes an ADB session and so Wi-Fi. */
    val voipNamesProblem: Boolean = false,
    /** This phone has Honor's Phone app, so its format is offered. */
    val honorDialer: Boolean = false,
    /** Recordings are going to the folder Honor's Phone app plays them from. */
    val inHonorFolder: Boolean = false,
    /** Whether the "tap a photo to select" hint has been shown its course - it
     *  retires the first time the user actually enters selection. */
    val selectHintSeen: Boolean = true,
    /** The Quick Settings tile is in the panel, as far as Android has said
     *  (see RecorderTile.isAdded). */
    val tileAdded: Boolean = false,
    /**
     * How many of them the list is currently showing.
     *
     * The list used to be capped at twenty with nothing said about the rest, so
     * a user with more than twenty calls simply could not see the older ones
     * and was never told they existed. A cap is still wanted - this screen is
     * one long scrolling Column, not a lazy list, so rendering hundreds of rows
     * would cost real time - but it has to announce itself.
     */
    val visibleCount: Int = PAGE,
    /** MediaStore wants the user's own say-so, through a system dialog. */
    val deleteConsent: IntentSender? = null,
    /**
     * Selection mode, and what is picked.
     *
     * Two fields rather than one, because "selecting with nothing picked yet"
     * is a real state: it is what tapping Select gives you. Deriving the mode
     * from a non-empty set would make the toolbar vanish the moment the user
     * deselected their last row, which is exactly when they are still choosing.
     */
    val selecting: Boolean = false,
    val selected: Set<String> = emptySet(),
    val confirmingDeleteSelected: Boolean = false,
    /** Recently deleted's Delete all for good, asked about before it is done. */
    val confirmingEmptyTrash: Boolean = false,
    /** The recording whose share format is being chosen, if the sheet is open. */
    val shareChoice: Recording? = null,
    /** True while a recording is being transcoded to .m4a for sharing - the
     *  spinner the user waits on before the share sheet appears. */
    val converting: Boolean = false,
    /** Which recording is loaded in the player, and where it is up to. */
    val playingName: String? = null,
    val playing: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    /** The loaded recording's loudness, for its waveform (Waveforms); null
     *  until worked out, or for one that cannot be - the plain scrubber then. */
    val waveform: List<Float>? = null,
    val log: String = "",
)

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
    private val connectSession: (Application) -> ListenableFuture<MediaController> = { a ->
        MediaController.Builder(a, SessionToken(a, ComponentName(a, PlaybackService::class.java))).buildAsync()
    },
) : AndroidViewModel(app) {

    // Whether messenger calls can work here is known at once (a cached
    // permission check): the Settings card must not show their switches'
    // text for a moment on a phone where they cannot work.
    private val _state = MutableStateFlow(initial.copy(voipSupported = VoipApps.supported(app)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** The answering machine's settings, greeting and diagnostics. */
    val answer = AnswerController(app, viewModelScope, ::say, initialAnswer, quiet = { controller?.pause() })

    /** Failures of something the user just did, for a snackbar where they are:
     *  the log in Diagnostics is no place to learn that a share or a delete
     *  did not work. */
    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val notices: SharedFlow<String> = _notices.asSharedFlow()

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
    fun selfTest() = launchAction("self test") {
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
        viewModelScope.launch {
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
        launchActionWhenFree("start fresh") {
            Setup.reset(getApplication())
                .onSuccess { _state.update { s -> s.copy(resetDone = true) } }
                .onFailure { say("could not reset: ${it.message}") }
        }
    }

    /** Work out which setup step we are on, and refresh everything else too. */
    fun refreshSetup() = launchAction("check setup") { recheckSetup() }

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
        viewModelScope.launch {
            try {
                _state.first { !it.busy }
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
    fun confirmDeveloperOptions() = launchAction("check setup") {
        Setup.confirmDeveloperOptions(getApplication())
        recheckSetup()
    }

    /**
     * The background-start setup card's "Not now": mark the question asked and
     * move on. JemRec cannot read the vendor setting, so the only thing ever
     * recorded is that it was offered (see [openBackgroundSettings] for the
     * other button).
     */
    fun confirmBackgroundChoice() = launchAction("check setup") {
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
        if (RecorderSwitch.isOn(getApplication())) {
            RecorderKeepAlive.schedule(getApplication())
            RecorderKeepAlive.kickNow(getApplication())
            // Re-arm the Wi-Fi watch on every open: it is lost to a force-stop
            // or an app update, and opening the app is when the user most
            // expects "back on Wi-Fi means ready" to hold.
            NetworkRevive.arm(getApplication())
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
        // THE REPLY FIELD HAS TO BE THERE BEFORE THE USER NEEDS IT.
        //
        // Once the pairing dialog is open, coming back to JemRec to press a
        // button would close it - which is the entire difficulty of this step.
        // So the notification is posted the moment pairing becomes the thing to
        // do, not when a button is pressed.
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
     * Bring the recorder back without holding the screen still while it happens.
     *
     * Deliberately off launchAction: reviving re-arms Wireless debugging, waits
     * for adbd, opens an ADB session and starts the daemon, which is seconds -
     * and launchAction would hold `busy` for all of them, greying out every
     * control on a screen that is working perfectly well. `starting` is the
     * flag that already exists for exactly this, and the header reads it rather
     * than announcing a failure that is being repaired as it is read.
     */
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
            _state.first { !it.busy }
            refreshSetup()
        }
    }

    private fun reviveInBackground(request: CaptureDaemon.Request = CaptureDaemon.Request.ROUTINE) {
        if (!reviving.compareAndSet(false, true)) return
        starts.begin()
        viewModelScope.launch {
            try {
                CaptureDaemon.revive(getApplication(), request = request)
                refreshNow()
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
    fun startPairingNotification() = launchAction("pair from the shade") {
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
     */
    fun reconnect() = launchAction("connect") {
        val app = getApplication<Application>()
        repeat(6) {
            if (AdbTransport.autoConnect(app, timeoutMs = 6_000).isSuccess) {
                recheckSetup()
                return@launchAction
            }
        }
        recheckSetup()
    }

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
    fun retryFinish() = launchAction("finish setup") { finishSetupInternal() }

    /**
     * Start the shell-side daemon from the jar bundled in this APK.
     *
     * The one operation that genuinely needs a working ADB session, and so the
     * one that needs Wi-Fi. Everything afterwards runs over loopback.
     */
    fun bootstrapDaemon() = launchAction("start recorder") {
        CaptureDaemon.ensureRunning(getApplication())
            .onSuccess { say("recorder running on 127.0.0.1:${CaptureDaemon.PORT}") }
            .onFailure { say("could not start the recorder: ${it.message}") }
        refresh()
    }

    /** Never offered again once waved away. An optional feature that keeps
     *  asking is not optional. */
    fun dismissCallLogOffer() {
        CallLogLookup.dismissBanner(getApplication())
        _state.update { it.copy(offerCallLog = false) }
    }

    /**
     * The permission dialog has closed, whichever way and whichever of the two
     * were granted.
     *
     * The verdict is read back from the system rather than taken from the
     * callback: the user can grant one and refuse the other, and the system is
     * the only thing that knows which.
     */
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
     * first revive that can change it - this one, if it can now
     * (CaptureDaemon.portChangeDue), or a later one. The change restarts
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
            val changes = withContext(Dispatchers.IO) {
                val step = StartWithoutWifi.step(app)
                if (step != StartWithoutWifi.PortStep.LEAVE) NetworkRevive.forgetWirelessRefused(app)
                CaptureDaemon.portChangeDue(app, step = step) != null
            }
            if (changes) {
                // Starting once the change begins - the restart of adbd and
                // the recorder - not for a revive that ends up changing
                // nothing: one that connects over the port itself leaves a
                // close for a Wi-Fi with Wireless debugging (portChangeOver).
                val began = AtomicBoolean(false)
                try {
                    CaptureDaemon.revive(app, onPortChange = { if (began.compareAndSet(false, true)) starts.begin() })
                } catch (t: Throwable) {
                    Log.w("JemRec", "the port change's revive failed", t)
                } finally {
                    if (began.get()) starts.end()
                }
            }
            refreshNow()
        }
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
        // Waits its turn rather than being dropped: the record control takes
        // a tap while the app is busy - each opening's check holds busy for
        // seconds - and the tap was dropped without a sign, the control
        // looking as ever. The control flips at once; the recorder follows
        // when the app is free, as the switch then is - a quick on and off
        // ends off.
        launchActionWhenFree(if (on) "turn on" else "turn off") {
            try {
                if (RecorderSwitch.isOn(getApplication()) != on) return@launchActionWhenFree
                // Shared with the Quick Settings tile. See RecorderSwitch.turn.
                RecorderSwitch.turn(getApplication(), on)
                // Ask what the world looks like rather than assuming. Called
                // directly, not via refreshSetup(), because that is a launchAction
                // and one will not start while this one is running.
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
        viewModelScope.launch { pushDaemonMode() }
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
    fun setHideDebugNotification(hide: Boolean) = launchActionWhenFree("debugging notification") {
        val app = getApplication<Application>()
        if (hide && Setup.isComplete(app) && !DebugNotificationCleaner.accessGranted(app) &&
            !Setup.grantNotificationAccess(app)
        ) {
            _state.update { it.copy(debugNotificationProblem = true) }
            return@launchActionWhenFree
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
                    _state.first { !it.busy }
                    selfTest()
                }
                DeepLink.Kind.ALLOW_WIFI -> {
                    _state.update { it.copy(screen = Screen.HOME) }
                    _state.first { !it.busy }
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
        KeepMarks.set(getApplication(), listOf(recording.name), !recording.kept)
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
        KeepMarks.set(getApplication(), picked.map { it.name }, kept = kept)
        _state.update { it.copy(selecting = false, selected = emptySet()) }
        refresh()
        keepInFiles(picked.filter { it.kept != kept })
    }

    /** Back out of the trash, as it was. */
    fun restore(recording: Recording) = launchAction("restore") {
        when (val result = withContext(Dispatchers.IO) { Trash.restore(getApplication(), recording) }) {
            is DeleteResult.Deleted -> say("restored ${recording.name}")
            is DeleteResult.NeedsConsent -> _state.update { it.copy(deleteConsent = result.request) }
            is DeleteResult.Failed -> say("could not restore ${recording.name}: ${result.error.message}")
        }
        refreshNow()
    }

    /** Recently deleted's Restore all: every recording in it back in the list. */
    fun restoreAll() = launchAction("restore all") {
        val all = _state.value.trashed
        if (all.isEmpty()) return@launchAction
        val result = withContext(Dispatchers.IO) { Trash.restoreAll(getApplication(), all) }
        report(all.size, result, "restored")
        refreshNow()
    }

    fun askEmptyTrash() = _state.update { it.copy(confirmingEmptyTrash = true) }

    fun cancelEmptyTrash() = _state.update { it.copy(confirmingEmptyTrash = false) }

    /** Delete all for good, once asked: what is in Recently deleted is gone,
     *  and cannot be restored. When free, as the dialog's yes is not a tap
     *  to drop. */
    fun confirmEmptyTrash() {
        _state.update { it.copy(confirmingEmptyTrash = false) }
        // What the question counted, read as it is answered: read after the
        // wait for busy, a recording trashed meanwhile went for good unasked.
        val all = _state.value.trashed
        if (all.isEmpty()) return
        launchActionWhenFree("delete for good") {
            val result = withContext(Dispatchers.IO) { Trash.deleteForGood(getApplication(), all) }
            report(all.size, result, "deleted for good")
            refreshNow()
        }
    }

    /** How a bulk restore or delete went, and Android's question next for
     *  what it wants confirmed - counted as asked, not as failed. */
    private fun report(of: Int, result: BulkDeleteResult, did: String) {
        val asked = of - result.deleted - result.failed
        val summary = listOfNotNull(
            "$did ${result.deleted}".takeIf { result.deleted > 0 },
            "asking Android about $asked".takeIf { result.needsConsent != null && asked > 0 },
            "could not do ${result.failed}".takeIf { result.failed > 0 },
        ).joinToString(", ")
        if (result.failed > 0) tell(summary) else if (summary.isNotEmpty()) say(summary)
        result.needsConsent?.let { request -> _state.update { it.copy(deleteConsent = request) } }
    }

    /** One of auto-delete's two rules changed: saved, and applied at once. */
    fun setAutoDelete(afterDays: Int? = null, shorterThan: Int? = null) = launchAction("auto-delete") {
        val app = getApplication<Application>()
        afterDays?.let { Retention.setMaxAgeDays(app, it) }
        shorterThan?.let { Retention.setMinSeconds(app, it) }
        val result = withContext(Dispatchers.IO) { Retention.run(app) }
        if (result.deleted > 0) say("auto-delete moved ${result.deleted} recording(s) to Recently deleted")
        // Recordings Android wants confirmed are asked about now, while the
        // user is here; the daily pass cannot ask.
        result.needsConsent?.let { request -> _state.update { it.copy(deleteConsent = request) } }
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
     * The contact the picker returned, added to the rules' list.
     *
     * NOT through launchAction. Coming back from the picker resumes the
     * screen, and the resume's own setup check holds the busy flag - which
     * launchAction answers by dropping the call. Measured on an emulator:
     * the contact was picked, and nothing was added.
     */
    fun addRuleContact(contact: Uri) {
        viewModelScope.launch {
            val added = withContext(Dispatchers.IO) { CallRules.add(getApplication(), contact) }
            if (!added) tell("could not add that contact")
            refreshNow()
        }
    }

    fun removeRuleContact(key: String) {
        viewModelScope.launch {
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
        viewModelScope.launch {
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
        viewModelScope.launch {
            if (!CaptureDaemon.setVoipApps(app)) {
                say("the recorder will pick up the messenger apps when it next restarts")
            }
        }
    }

    /** Caller names for messenger calls, from their call notifications. Needs
     *  notification access, granted over ADB like the debugging one. */
    fun setVoipNames(on: Boolean) = launchAction("messenger caller names") {
        val app = getApplication<Application>()
        if (on && !DebugNotificationCleaner.accessGranted(app) && !Setup.grantNotificationAccess(app)) {
            _state.update { it.copy(voipNamesProblem = true) }
            return@launchAction
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

    fun useDefaultFolder() = launchAction("use default folder") {
        RecordingStore.clearTree(getApplication())
        say("recordings will go to the standard Recordings folder")
        refresh()
    }

    /** Not through launchAction, for the same reason as addRuleContact: the
     *  picker's answer arrives as the screen resumes, while its check runs. */
    fun onFolderChosen(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { RecordingStore.setTree(getApplication(), uri) }
            say("recordings will be saved to the folder you chose")
            refreshNow()
        }
    }

    /**
     * Fire and forget, for callers that only want the screen to catch up.
     *
     * Anything that needs the READING before it carries on must await
     * refreshNow instead. This one returns the instant it is called, which is
     * how a caller could finish, drop its busy flag and let the screen render a
     * verdict from before the work it had just done.
     */
    fun refresh() = viewModelScope.launch { refreshNow() }

    private suspend fun refreshNow() {
        val app = getApplication<Application>()
        // Refreshes overlap - a resume, a save announced, a keep toggled - and
        // each reads the list, then waits on the recorder for seconds. One
        // started earlier and finishing later must not put back a list from
        // before the other: a recording just saved vanished until the next
        // resume.
        val generation = ++refreshesStarted

        // ALL of this is file I/O and it must not run on the main thread.
        // viewModelScope dispatches on Main, and listing recordings queries
        // MediaStore and opens every file with MediaMetadataRetriever to read
        // its duration. On the UI thread that freezes the app - and because the
        // activity had been brought to the front over the dialer, it froze the
        // dialer's touches with it. An unresponsive phone right after a call is
        // about the worst thing this app could do.
        val snapshot = withContext(Dispatchers.IO) {
            val recordings = Recordings.list(app)
            val trashed = if (Recordings.override != null) emptyList() else Trash.list(app)
            // Only while the tab is up: it is the call log and a contacts
            // lookup per number, and nobody is looking at it otherwise. Null
            // when not read, so this refresh leaves alone a list a later one
            // has read already.
            val calls = if (_state.value.homeTab == HomeTab.CALLS) CallHistory.load(app, recordings, trashed) else null
            // Before the lists reach the screen, so no row shows a colour and
            // then changes it; and given only in Multitrack, the one look that
            // has them - a look never chosen keeps nothing. Once given, they
            // stay through a change of look, so a caller's colour is theirs.
            val colours = if (Looks.choice(app).look == Look.MULTITRACK) {
                CallerColours.update(
                    app,
                    recordings.map { it.caller to (it.startedAtMillis ?: 0L) } + (calls ?: _state.value.calls).map { it.label to it.at },
                )
            } else {
                emptyMap()
            }
            Snapshot(
                // CONFIRMED, because this reading decides whether to revive.
                //
                // A single ping is a health check; here it was a death
                // certificate. One missed reply - and on battery, with the CPU
                // throttled and the app just resumed, they are missed - meant
                // "the recorder is down", which fired a revive, which re-armed
                // Wireless debugging, which made Android post its notification.
                // Every trip to the home screen and back. Plugged into USB the
                // phone stays awake, the ping never misses, and none of it
                // happens; that is what made it look like a mystery rather than
                // a timeout.
                daemonRunning = CaptureDaemon.isRunningConfirmed(app),
                // Read alongside the daemon check: if the recorder is down, the
                // header needs to know whether the ADB channel is even open
                // before it tells the user to wait for Wi-Fi.
                wirelessDebuggingOn = Setup.wirelessDebuggingOn(app),
                usbDebugging = Setup.usbDebugging(app),
                developerOptionsHidden = Setup.developerOptionsHidden(app),
                networkNotAllowed = RecorderHealth.cause(app) == RecorderHealth.Problem.NETWORK_NOT_ALLOWED,
                pairingRefused = RecorderHealth.cause(app) == RecorderHealth.Problem.CANNOT_CONNECT,
                onWifi = NetworkRevive.onWifi(app),
                pairingNotificationsBlocked = PairingNotification.blocked(app),
                // Only until setup is done: a getprop, on the phones that may
                // have the switch.
                systemOptimizationSwitch = !Setup.isComplete(app) && ShellGrant.switchMayBeOff(),
                folder = RecordingStore.describe(app),
                usingDefaultFolder = RecordingStore.treeUri(app) == null,
                automatic = RecordingMode.of(app) == RecordingMode.AUTOMATIC,
                debugNotificationHidden = DebugNotificationCleaner.hiding(app),
                afterCallNotice = AfterCall.enabled(app),
                enabled = RecorderSwitch.isOn(app),
                offMode = DebugSwitchesOff.mode(app),
                debuggingOff = DebugSwitchesOff.recorderGone(app),
                startWithoutWifi = StartWithoutWifi.enabled(app),
                portSet = StartWithoutWifi.readyForUser(app),
                portOurs = StartWithoutWifi.openedThisBoot(app),
                portFailed = StartWithoutWifi.failedThisBoot(app),
                portCloseFailed = StartWithoutWifi.closeFailedThisBoot(app),
                canReadCallLog = CallLogLookup.granted(app),
                canReadAudio = AudioAccess.granted(app),
                offerCallLog = CallLogLookup.anythingLeftToOffer(app) &&
                    !CallLogLookup.bannerDismissed(app),
                recordings = recordings,
                trashed = trashed,
                calls = calls,
                callerColours = colours,
                autoDeleteAfterDays = Retention.maxAgeDays(app),
                autoDeleteShorterThan = Retention.minSeconds(app),
                ruleContacts = CallRules.contacts(app),
                ruleOtherNumbers = CallRules.otherNumbers(app),
                // A contacts query or two each, so only when Settings shows them.
                ruleChosen = if (
                    CallLogLookup.photosGranted(app) &&
                    CallRules.contacts(app).let { it == CallRules.Contacts.ALL_BUT || it == CallRules.Contacts.ONLY }
                ) {
                    CallRules.chosen(app)
                } else {
                    emptyList()
                },
                canReadContacts = CallLogLookup.photosGranted(app),
                callTracking = CallTracking.enabled(app),
                voipInstalled = VoipApps.installed(app),
                voipSupported = VoipApps.supported(app),
                voipEnabled = VoipApps.enabled(app),
                voipNames = VoipCallNotes.enabled(app),
                fileNames = FileNames.format(app),
                honorDialer = FileNames.honorDialer(app),
                inHonorFolder = FileNames.inHonorFolder(app),
                selectHintSeen = Prefs.of(app).getBoolean("select_hint_seen", false),
                tileAdded = RecorderTile.isAdded(app),
            )
        }

        if (generation < refreshApplied) return
        refreshApplied = generation
        _state.update {
            it.copy(
                daemonRunning = snapshot.daemonRunning,
                wirelessDebuggingOn = snapshot.wirelessDebuggingOn,
                usbDebugging = snapshot.usbDebugging,
                developerOptionsHidden = snapshot.developerOptionsHidden,
                networkNotAllowed = snapshot.networkNotAllowed,
                pairingRefused = snapshot.pairingRefused,
                onWifi = snapshot.onWifi,
                pairingNotificationsBlocked = snapshot.pairingNotificationsBlocked,
                systemOptimizationSwitch = snapshot.systemOptimizationSwitch,
                folder = snapshot.folder,
                usingDefaultFolder = snapshot.usingDefaultFolder,
                automatic = snapshot.automatic,
                debugNotificationHidden = snapshot.debugNotificationHidden,
                afterCallNotice = snapshot.afterCallNotice,
                enabled = snapshot.enabled,
                offMode = snapshot.offMode,
                debuggingOff = snapshot.debuggingOff,
                startWithoutWifi = snapshot.startWithoutWifi,
                portSet = snapshot.portSet,
                portOurs = snapshot.portOurs,
                portFailed = snapshot.portFailed,
                portCloseFailed = snapshot.portCloseFailed,
                canReadCallLog = snapshot.canReadCallLog,
                canReadAudio = snapshot.canReadAudio,
                offerCallLog = snapshot.offerCallLog,
                recordings = snapshot.recordings,
                trashed = snapshot.trashed,
                calls = snapshot.calls ?: it.calls,
                callerColours = snapshot.callerColours,
                autoDeleteAfterDays = snapshot.autoDeleteAfterDays,
                autoDeleteShorterThan = snapshot.autoDeleteShorterThan,
                ruleContacts = snapshot.ruleContacts,
                ruleOtherNumbers = snapshot.ruleOtherNumbers,
                ruleChosen = snapshot.ruleChosen,
                canReadContacts = snapshot.canReadContacts,
                callTracking = snapshot.callTracking,
                voipInstalled = snapshot.voipInstalled,
                voipSupported = snapshot.voipSupported,
                voipEnabled = snapshot.voipEnabled,
                voipNames = snapshot.voipNames,
                fileNames = snapshot.fileNames,
                honorDialer = snapshot.honorDialer,
                inHonorFolder = snapshot.inHonorFolder,
                selectHintSeen = snapshot.selectHintSeen,
                tileAdded = snapshot.tileAdded,
                connected = AdbTransport.lastKnownConnected,
            ).let { next ->
                // A name that is no longer shown cannot stay selected. It would
                // still be counted by the toolbar - the count the user reads
                // before confirming a deletion - and deleted with the rest.
                next.copy(selected = stillShown(next))
            }
        }
        answer.reload()
        // Its row gone - deleted from its notification, auto-deleted - the
        // recording stops too: a deleted call does not play on. The list
        // holds every recording (only drawing it is paged), so one playing
        // on from before the screen opened is in it.
        _state.value.playingName?.let { name ->
            if (snapshot.recordings.none { it.name == name }) stopPlayback()
        }
        // A recording that played on while the screen was away gets its
        // waveform once the list it is in has arrived: the session can name
        // it before that (measured on the Android 17 emulator, 2026-10-23 -
        // the reopened row showed the plain scrubber).
        _state.value.let { now ->
            if (now.playingName != null && now.waveform == null && waveformJob?.isActive != true) {
                now.recordings.firstOrNull { it.name == now.playingName }?.let(::loadWaveform)
            }
        }
        fillInWaveforms()
    }

    /**
     * Rows of the list draw each recording's waveform, from its cache (see
     * rememberRowLevels). A call saved since the waveform player has one
     * (AfterCall); one recorded before has none until it is played - so they
     * are worked out here, newest first, one at a time, while the app is on
     * screen. Measured on the Honor (2026-10-23): 0.14 s a minute-long call,
     * 2.5 s a half-hour one - once each, then read from the cache.
     *
     * Only on screen: it stops with the screen (stopFillingIn) and the next
     * refresh - each opening makes one - goes on from there. It ran on when
     * the app was left, with up to four decoders a call, during the next call
     * too. Whether a recording has its levels is a file's size, not a read of
     * them; one that cannot be decoded is not tried again this run of the
     * app; and the levels of recordings no longer kept go (Waveforms.prune).
     * Asked again while it runs, it goes over the list once more at the end,
     * for the recordings the list gained meanwhile.
     */
    private fun fillInWaveforms() {
        if (fillingIn?.isActive == true) {
            fillAgain = true
            return
        }
        val app = getApplication<Application>()
        fillingIn = viewModelScope.launch(Dispatchers.IO) {
            do {
                fillAgain = false
                val now = _state.value
                // Not over a list that is not the phone's: the debug build's
                // invented calls, or none for want of the audio permission -
                // every waveform would go, to be worked out again.
                if (Recordings.override == null && now.canReadAudio) {
                    Waveforms.prune(app, (now.recordings + now.trashed).map { it.name })
                }
                for (recording in now.recordings) {
                    if (!isActive) break
                    val name = recording.name
                    val failedAt = undecodable[name]
                    if (failedAt != null && SystemClock.elapsedRealtime() - failedAt < RETRY_UNDECODABLE_MS) continue
                    if (Waveforms.isBeingWritten(name) || Waveforms.has(app, name)) continue
                    val levels = Waveforms.levels(app, name, recording.uri, keepGoing = { isActive })
                    if (levels == null && isActive && !Waveforms.isBeingWritten(name)) {
                        undecodable[name] = SystemClock.elapsedRealtime()
                    } else {
                        undecodable.remove(name)
                    }
                }
            } while (fillAgain && isActive)
        }
    }

    /** The list's fill-in stops with the screen. See fillInWaveforms. */
    fun stopFillingIn() {
        fillingIn?.cancel()
    }

    private var fillingIn: Job? = null

    @Volatile
    private var fillAgain = false

    /** Recordings the fill-in could not decode, and when: tried on every
     *  refresh, each logged its warning again and again; never again for
     *  the life of the process, one that failed only for the moment - its
     *  codec not to be had while AfterCall decoded another - kept no
     *  waveform for days. Tried again after RETRY_UNDECODABLE_MS. */
    private val undecodable = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Everything refresh() reads off disk, gathered in one background pass. */
    private data class Snapshot(
        val daemonRunning: Boolean,
        val wirelessDebuggingOn: Boolean,
        val usbDebugging: GlobalSettings.Reading,
        val developerOptionsHidden: Boolean,
        val networkNotAllowed: Boolean,
        val pairingRefused: Boolean,
        val onWifi: Boolean,
        val pairingNotificationsBlocked: Boolean,
        val systemOptimizationSwitch: Boolean,
        val folder: String,
        val usingDefaultFolder: Boolean,
        val automatic: Boolean,
        val debugNotificationHidden: Boolean,
        val afterCallNotice: Boolean,
        val enabled: Boolean,
        val offMode: OffMode,
        val debuggingOff: Boolean,
        val startWithoutWifi: Boolean,
        val portSet: Boolean,
        val portOurs: Boolean,
        val portFailed: Boolean,
        val portCloseFailed: Boolean,
        val canReadCallLog: Boolean,
        val canReadAudio: Boolean,
        val offerCallLog: Boolean,
        val recordings: List<Recording>,
        val trashed: List<Recording>,
        val calls: List<CallHistory.Call>?,
        val callerColours: Map<String, Int>,
        val autoDeleteAfterDays: Int,
        val autoDeleteShorterThan: Int,
        val ruleContacts: CallRules.Contacts,
        val ruleOtherNumbers: Boolean,
        val ruleChosen: List<CallRules.Chosen>,
        val canReadContacts: Boolean,
        val callTracking: Boolean,
        val voipInstalled: List<VoipApps.App>,
        val voipSupported: Boolean,
        val voipEnabled: Set<String>,
        val voipNames: Boolean,
        val fileNames: FileNames.Format,
        val honorDialer: Boolean,
        val inHonorFolder: Boolean,
        val selectHintSeen: Boolean,
        val tileAdded: Boolean,
    )

    /**
     * One action at a time. Every entry point funnels through here so that the
     * busy flag, the connected flag and error reporting cannot drift apart.
     */
    private fun launchAction(label: String, block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch { runAction(label, block) }
    }

    /**
     * As launchAction, but waits its turn instead of being dropped - for an
     * action the user has already confirmed. A resume's setup check holds the
     * busy flag for seconds (longer with the recorder down), and a delete
     * confirmed meanwhile closed its dialog and did nothing, without a word.
     */
    private fun launchActionWhenFree(label: String, block: suspend () -> Unit) {
        viewModelScope.launch {
            _state.first { !it.busy }
            runAction(label, block)
        }
    }

    private suspend fun runAction(label: String, block: suspend () -> Unit) {
        _state.update { it.copy(busy = true) }
        try {
            block()
        } catch (t: Throwable) {
            Log.w("JemRec", "$label failed", t)
            say("$label failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            _state.update { it.copy(busy = false, connected = AdbTransport.lastKnownConnected) }
        }
    }

    /** Guards against two bring-ups racing when the screen is reopened. */
    private val reviving = AtomicBoolean(false)

    /** Every start the screen shows as Starting (StartsUnderWay). */
    private val starts = StartsUnderWay(_state)

    /** Refreshes begun, and the latest whose list is on screen. Main thread. */
    private var refreshesStarted = 0L
    private var refreshApplied = 0L

    /** The in-flight .m4a conversion, so its spinner's Cancel can stop it. */
    private var conversionJob: Job? = null

    /**
     * The media session's player, in PlaybackService, reached as the
     * system's controls reach it. Null until something is played, or until
     * the screen comes forward while the service is up. Main thread.
     */
    private var controller: MediaController? = null
    private var connecting: ListenableFuture<MediaController>? = null
    private val whenConnected = mutableListOf<(MediaController) -> Unit>()
    private var screenUp = true
    private var ticker: kotlinx.coroutines.Job? = null
    private var waveformJob: Job? = null

    /** What the session does, whoever asked for it: JemRec's own taps, or
     *  the system's controls - a pause from the lock screen, a call holding
     *  it back, the headphones coming out. */
    private val sessionEvents = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) = showPlayback()

        override fun onPlayerError(error: PlaybackException) {
            tell("could not play that recording")
            stopPlayback()
        }
    }

    /**
     * Play or pause a recording - inside this app, in PlaybackService, so it
     * goes on with the screen off under the system's media controls.
     *
     * It used to fire an ACTION_VIEW and let Android choose, which on this
     * phone meant YouTube Music's preview bar - no close button, and it looked
     * like the app had frozen. The privacy problem was worse: handing a
     * recording of a private call to whichever app claimed the intent.
     */
    fun play(recording: Recording) {
        // Android refuses a player the audio during a call; say why, rather
        // than a tap that does nothing. A pause is never refused: a voice
        // app can hold the call's audio mode with a recording still playing.
        val pausing = _state.value.playingName == recording.name && _state.value.playing
        if (!pausing && inCall()) {
            tell("a recording does not play during a call")
            return
        }
        if (_state.value.playingName != recording.name) {
            _state.update { it.copy(playingName = recording.name, playing = false, positionMs = 0, durationMs = 0) }
            loadWaveform(recording)
        }
        withController { c ->
            when {
                c.currentMediaItem?.mediaId != recording.name -> {
                    c.setMediaItem(mediaItem(recording))
                    c.prepare()
                    c.play()
                }
                c.isPlaying -> c.pause()
                else -> {
                    // Closed, its item still set: stopped by the system's
                    // controls, or failed with the screen away. Play alone
                    // opens nothing.
                    if (c.playbackState == Player.STATE_IDLE) c.prepare()
                    c.play()
                }
            }
        }
    }

    /** A recording as the session plays it: named for the system's controls
     *  (PlaybackRules.titles), its uri sent as the request's, which reaches
     *  the session where a MediaItem's own does not. */
    private fun mediaItem(recording: Recording): MediaItem {
        val titles = PlaybackRules.titles(recording.caller, recording.app, recording.message, recording.whenLabel)
        return MediaItem.Builder()
            .setMediaId(recording.name)
            .setUri(recording.uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(recording.uri).build())
            .setMediaMetadata(MediaMetadata.Builder().setTitle(titles.title).setArtist(titles.subtitle).build())
            .build()
    }

    private fun inCall(): Boolean =
        getApplication<Application>().getSystemService(AudioManager::class.java)
            ?.let { it.mode != AudioManager.MODE_NORMAL } == true

    /** [action] on the session's player, once connected - at once if it is. */
    private fun withController(action: (MediaController) -> Unit) {
        controller?.let { action(it); return }
        whenConnected += action
        if (connecting != null) return
        val app = getApplication<Application>()
        val future = connectSession(app)
        connecting = future
        future.addListener({
            connecting = null
            val waiting = whenConnected.toList()
            whenConnected.clear()
            val c = runCatching { future.get() }.getOrNull()
            if (c == null) {
                Log.e("JemRec", "playback: no session")
                if (waiting.isNotEmpty()) {
                    tell("could not play that recording")
                    stopPlayback()
                }
                return@addListener
            }
            controller = c
            c.addListener(sessionEvents)
            waiting.forEach { it(c) }
            showPlayback()
        }, ContextCompat.getMainExecutor(app))
    }

    /**
     * The screen made to match the session: which recording, playing or
     * not, where. A recording that started while the screen was away - the
     * app reopened while it plays - gets its waveform here.
     */
    private fun showPlayback() {
        val c = controller ?: return
        val name = c.currentMediaItem?.mediaId
        val arrived = name != null && name != _state.value.playingName
        _state.update {
            if (name == null) {
                it.copy(playingName = null, playing = false, positionMs = 0, durationMs = 0, waveform = null)
            } else {
                it.copy(
                    playingName = name,
                    playing = c.isPlaying,
                    positionMs = c.currentPosition.toInt(),
                    durationMs = c.duration.takeIf { d -> d != C.TIME_UNSET }?.toInt() ?: 0,
                )
            }
        }
        if (arrived) _state.value.recordings.firstOrNull { it.name == name }?.let(::loadWaveform)
        if (c.isPlaying && screenUp) tick() else ticker?.cancel()
    }

    /** The waveform of the recording just loaded: the plain scrubber until
     *  it is there, which for a long call can be a few seconds the first time. */
    private fun loadWaveform(recording: Recording) {
        waveformJob?.cancel()
        _state.update { it.copy(waveform = null) }
        waveformJob = viewModelScope.launch {
            val levels = withContext(Dispatchers.IO) {
                Waveforms.levels(getApplication(), recording.name, recording.uri, keepGoing = { isActive })
            } ?: return@launch
            _state.update { if (it.playingName == recording.name) it.copy(waveform = levels) else it }
        }
    }

    fun seekTo(ms: Int) {
        controller?.seekTo(ms.toLong())
        _state.update { it.copy(positionMs = ms) }
    }

    /**
     * Drives the progress bar: the session's position every 300 ms, while
     * it plays and the screen is up. The end of the recording rewinds it to
     * the start, paused (SessionPlayer); it does not close the row. It used to
     * vanish the moment playback ended - a player that disappears under your
     * thumb as you go to seek back is a small ambush. The chevron is the way
     * out.
     */
    private fun tick() {
        if (ticker?.isActive == true) return
        ticker = viewModelScope.launch {
            while (true) {
                val c = controller ?: break
                if (!c.isPlaying) break
                _state.update {
                    it.copy(
                        playing = true,
                        positionMs = c.currentPosition.toInt(),
                        durationMs = c.duration.takeIf { d -> d != C.TIME_UNSET }?.toInt() ?: it.durationMs,
                    )
                }
                kotlinx.coroutines.delay(300)
            }
        }
    }

    /** The chevron on the scrubber: stop, and fold the row back. */
    fun stopPlaying() = stopPlayback()

    /**
     * The screen came forward: show what the session plays - it may have
     * played on, or been paused from the lock screen, while it was away.
     */
    fun screenShown() {
        screenUp = true
        watchPairing()
        if (controller != null) showPlayback() else if (PlaybackService.running) withController { }
    }

    /**
     * Out of sight, the scrubber is no longer driven; the recording plays
     * on, under the system's media controls.
     *
     * Until 0.19 it paused here, and on another tab or Settings too: the
     * scrubber under the row was the only control there was, so a recording
     * that went on playing without it went on with nothing able to stop it -
     * from a pocket, a private call out loud. PlaybackService gave it the
     * system's controls, so it no longer pauses for any of them.
     */
    fun screenHidden() {
        screenUp = false
        ticker?.cancel()
    }

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

    private fun stopPlayback() {
        ticker?.cancel()
        waveformJob?.cancel()
        whenConnected.clear()
        controller?.run {
            stop()
            clearMediaItems()
        }
        _state.update {
            it.copy(playing = false, playingName = null, positionMs = 0, durationMs = 0, waveform = null)
        }
    }

    /** The screen is gone for good; a recording playing goes on playing,
     *  under the system's controls, until they stop it. */
    override fun onCleared() {
        stopWatchingSwitch()
        stopWatchingDebugSwitches()
        ticker?.cancel()
        controller?.run {
            removeListener(sessionEvents)
            release()
        }
        connecting?.let(MediaController::releaseFuture)
    }

    /**
     * Tapping share picks a format first, converting nothing yet.
     *
     * A recording is Opus in an Ogg on disk - fine for Android and desktop, but
     * Apple's stock apps will not open it. Only the .m4a route needs the
     * transcode, so the choice comes BEFORE any work: sharing the original is
     * instant, and the (slower, blocking) convert happens only when it is what
     * the user picked.
     */
    fun share(recording: Recording) = _state.update { it.copy(shareChoice = recording) }

    fun dismissShareChoice() = _state.update { it.copy(shareChoice = null) }

    /** Share the Opus original as-is: no conversion, so it is instant. */
    fun shareOriginal(recording: Recording) {
        _state.update { it.copy(shareChoice = null) }
        openShareSheet(recording.uri, "audio/ogg")
    }

    /** Convert to AAC in an .m4a first - so it plays on any device - showing a
     *  spinner while it works, then hand the result to the share sheet. */
    fun shareUniversal(recording: Recording) {
        _state.update { it.copy(shareChoice = null, converting = true) }
        conversionJob = viewModelScope.launch {
            val app = getApplication<Application>()
            val m4a = withContext(Dispatchers.Default) {
                val dir = File(app.getExternalFilesDir(null), "shares").apply { mkdirs() }
                sweepStaleShares(dir)
                val out = File(dir, recording.name.substringBeforeLast('.') + ".m4a")
                // isActive goes false the instant the job is cancelled, which is
                // how Cancel reaches the transcode loop.
                if (Transcoder.toM4a(app, recording.uri, out) { !isActive }) out else null
            }
            _state.update { it.copy(converting = false) }
            if (m4a == null) {
                tell("could not prepare that recording to share")
                return@launch
            }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.recordings", m4a)
            openShareSheet(uri, "audio/mp4")
        }
    }

    /**
     * Abort a running .m4a conversion - the spinner's Cancel, or a back/scrim
     * tap on it.
     *
     * Cancelling the job flips isActive false, so the transcode breaks out and
     * sweeps its half-written file; the coroutine then unwinds without reaching
     * the share sheet or the failure message. The spinner is taken down here
     * because that unwinding will not run the line that normally does it.
     */
    fun cancelConversion() {
        conversionJob?.cancel()
        conversionJob = null
        _state.update { it.copy(converting = false) }
    }

    private fun openShareSheet(uri: Uri, mime: String) {
        val app = getApplication<Application>()
        val intent = Intent(Intent.ACTION_SEND)
            .setType(mime)
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val chooser = Intent.createChooser(intent, "Share recording")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { app.startActivity(chooser) }
            .onFailure { tell("could not share that recording") }
    }

    /** Delete share copies older than a few hours. A share in flight is minutes
     *  old at most, so the cutoff never sweeps one still being read. */
    private fun sweepStaleShares(dir: File) {
        val cutoff = System.currentTimeMillis() - 6 * 60 * 60 * 1000L
        dir.listFiles()?.forEach { if (it.lastModified() < cutoff) it.delete() }
    }

    /**
     * Show the next batch of older recordings.
     */
    fun showMore() = _state.update { it.copy(visibleCount = it.visibleCount + PAGE) }

    /** The system delete dialog has closed, one way or the other. */
    /** Android's dialog is up: the request is spent. Left set, a new screen -
     *  rotated, re-themed behind the dialog - launched it a second time. */
    fun onDeleteConsentShown() = _state.update { it.copy(deleteConsent = null) }

    fun onDeleteConsentResult(granted: Boolean) {
        _state.update { it.copy(deleteConsent = null) }
        if (!granted) say("deletion cancelled")
        refresh()
    }

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
        launchActionWhenFree("delete recordings") {
            val current = _state.value
            val doomed = current.recordings.filter { it.name in current.selected }
            if (doomed.isEmpty()) return@launchActionWhenFree
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
            result.needsConsent?.let { request ->
                _state.update { it.copy(deleteConsent = request) }
            }
            _state.update { it.copy(selecting = false, selected = emptySet()) }
            refresh()
        }
    }

    /** Named `say` rather than `append` on purpose: inside a buildString block
     *  an `append` member would be shadowed by StringBuilder.append. */
    /** As say, and in a snackbar too: for a failure of something the user
     *  did just now. */
    private fun tell(text: String) {
        say(text)
        _notices.tryEmit(text.replaceFirstChar { it.uppercase() })
    }

    private fun say(text: String) {
        val stamp = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        // NEWEST FIRST. Appending put the answer at the bottom of a log that
        // grows, so pressing a button and reading the result meant scrolling
        // past everything that had happened before it - and pressing "Start
        // recorder" looked like it did nothing at all, because the one line it
        // wrote was below the fold. A log nobody scrolls to is not feedback.
        _state.update { current ->
            // Trimmed rather than cleared by hand. There was a Clear button and
            // it is gone: with the newest entry at the top there is nothing to
            // clear PAST, so the button only ever deleted history someone might
            // have wanted. A cap does the one useful thing it did - stop the
            // log growing without bound - without asking anyone to press it.
            current.copy(log = ("[$stamp] $text\n\n" + current.log).take(LOG_LIMIT))
        }
    }
}
