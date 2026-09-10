package com.jemcik.jemrec.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.adb.AdbTransport
import android.content.Intent
import android.content.IntentSender
import android.net.Uri
import com.jemcik.jemrec.capture.CallMonitorService
import com.jemcik.jemrec.capture.AudioAccess
import com.jemcik.jemrec.capture.CallLogLookup
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.NetworkRevive
import com.jemcik.jemrec.capture.RecorderKeepAlive
import com.jemcik.jemrec.capture.DeleteResult
import com.jemcik.jemrec.capture.Recording
import com.jemcik.jemrec.capture.Recordings
import com.jemcik.jemrec.capture.Transcoder
import androidx.core.content.FileProvider
import java.io.File
import com.jemcik.jemrec.capture.RecorderSwitch
import com.jemcik.jemrec.capture.RecordingFilter
import com.jemcik.jemrec.capture.filterRecordings
import com.jemcik.jemrec.capture.RecordingMode
import com.jemcik.jemrec.capture.RecordingPlayer
import com.jemcik.jemrec.capture.RecordingStore
import com.jemcik.jemrec.capture.SelfTest
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

/** How much diagnostics log to keep. Roughly a screen or two of history,
 *  which is as far back as anyone reads. */
private const val LOG_LIMIT = 8_000

/**
 * Which of the two screens is showing.
 *
 * Two, and no navigation library. The whole app is a status page and a place to
 * put the things that are not it, and pulling in a nav graph to express "or the
 * other one" would be more machinery than the problem has.
 */
enum class Screen { HOME, SETTINGS }

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
     * on when this is, so setup asks for it first - see Setup.usbDebuggingOn.
     * Defaults true so a not-yet-read state never accuses.
     */
    val usbDebuggingOn: Boolean = true,
    /**
     * Whether JemRec's notifications are off, or the pairing channel muted, so
     * the pairing code would have nowhere to appear. Only read on the pairing
     * step, where it raises a warning and a fix button. Defaults false so it
     * never accuses before it has been looked at.
     */
    val pairingNotificationsBlocked: Boolean = false,
    val folder: String = "",
    val usingDefaultFolder: Boolean = true,
    /** True when every call is recorded without asking. */
    val automatic: Boolean = true,
    /** Whether the call log has been offered to the app, and whether the
     *  one-time offer is still worth showing. */
    val canReadCallLog: Boolean = false,
    val canReadAudio: Boolean = false,
    val offerCallLog: Boolean = false,
    /** Whether JemRec is switched on at all. */
    val enabled: Boolean = true,
    /**
     * Switching on, and not yet finished.
     *
     * Its own flag rather than the general busy one, which is raised by
     * every background check including the one on every screen open - using
     * that would make the header flicker each time the app is opened.
     */
    val starting: Boolean = false,
    val recordings: List<Recording> = emptyList(),
    /** Live search text over the recordings (name or number). */
    val query: String = "",
    /** Whether the search field is unfolded. Folded away by default: it is used
     *  now and then, not every visit, so it does not sit taking height until the
     *  search chip asks for it. */
    val searchOpen: Boolean = false,
    /** Which time preset is active: All / Today / This week. */
    val filter: RecordingFilter = RecordingFilter.ALL,
    /** Favourites is its own axis, not one of the time presets: narrowing to
     *  calls with starred contacts ANDs with whatever time range is chosen,
     *  rather than replacing it. */
    val favoritesOnly: Boolean = false,
    /** Whether the "tap a photo to select" hint has been shown its course - it
     *  retires the first time the user actually enters selection. */
    val selectHintSeen: Boolean = true,
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
    val log: String = "",
)

class MainViewModel @JvmOverloads constructor(
    app: Application,
    /** Tests seed the list through this; the app never passes it. Without it
     *  the only way recordings entered state was refreshNow(), which is
     *  device-bound, and the tests reached into the flow by reflection. */
    initial: UiState = UiState(),
) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(initial)
    val state: StateFlow<UiState> = _state.asStateFlow()

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
    }

    fun showSettings() = _state.update { it.copy(screen = Screen.SETTINGS) }

    fun showHome() = _state.update { it.copy(screen = Screen.HOME) }

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
        launchAction("start fresh") {
            Setup.reset(getApplication())
                .onSuccess { _state.update { s -> s.copy(resetDone = true) } }
                .onFailure { say("could not reset: ${it.message}") }
        }
    }

    /** Work out which setup step we are on, and refresh everything else too. */
    fun refreshSetup() = launchAction("check setup") { recheckSetup() }

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
        _state.update { it.copy(step = step) }

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
    fun onWifiAvailable() {
        val s = _state.value
        if (s.step == SetupStep.READY && s.enabled && !s.daemonRunning) reviveInBackground()
    }

    private fun reviveInBackground() {
        if (!reviving.compareAndSet(false, true)) return
        _state.update { it.copy(starting = true) }
        viewModelScope.launch {
            try {
                CaptureDaemon.revive(getApplication())
                refreshNow()
            } catch (t: Throwable) {
                Log.w("JemRec", "reviving the recorder failed", t)
            } finally {
                _state.update { it.copy(starting = false) }
                reviving.set(false)
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
        _state.update { it.copy(step = SetupStep.FINISHING) }
        Setup.finish(getApplication())
            .onSuccess { say("recorder started") }
            .onFailure { say("could not finish setup: ${it.message}") }
        _state.update { it.copy(step = Setup.currentStep(getApplication())) }
    }

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
     * Turn JemRec on or off.
     *
     * A soft switch, see RecorderSwitch: off tells the daemon to capture
     * nothing and stops the per-call service; the daemon itself stays up, so
     * on is instant anywhere. It used to stop the daemon too, which made on
     * the expensive direction - a respawn needs an ADB session and therefore
     * Wi-Fi - and stranded an "off" in the field until the next network.
     */
    fun setEnabled(on: Boolean) {
        RecorderSwitch.set(getApplication(), on)
        // `starting` matters as much as `enabled` here. The switch flips at
        // once, but the recorder takes a couple of seconds to come up, and in
        // between the screen still held the reading from when it was off - so
        // it announced in red that the phone could not record and needed Wi-Fi,
        // while the recorder was starting normally. An error shown for a normal
        // operation in progress is how people learn to ignore errors.
        _state.update { it.copy(enabled = on, starting = on) }
        launchAction(if (on) "turn on" else "turn off") {
            if (on) {
                // revive() connects, starts the daemon and stands the ADB
                // session back down - the whole bring-up in one call, shared
                // with boot and the keep-alive job. Then arm that job so the
                // daemon is kept alive without a permanent service. When the
                // daemon is already up - the usual case, since "off" leaves it
                // running - revive() no-ops over loopback and needs no Wi-Fi, so
                // turning recording back on is instant anywhere.
                CaptureDaemon.revive(getApplication())
                RecorderKeepAlive.schedule(getApplication())
                NetworkRevive.arm(getApplication())
                // Tell the daemon how to record now (automatic vs ask-first).
                pushDaemonMode()
            } else {
                // SOFT OFF. Leave the daemon running and its keep-alive/Wi-Fi
                // revival armed - killing it here used to be a field trap (a
                // rebuild needs Wi-Fi). The daemon's life is tied to setup, not
                // this switch. Tell it to capture nothing.
                CaptureDaemon.setDaemonMode(getApplication(), CaptureDaemon.MODE_OFF)
                CallMonitorService.stop(getApplication())
            }
            // Ask what the world looks like rather than assuming. Called
            // directly, not via refreshSetup(), because that is a launchAction
            // and one will not start while this one is running.
            recheckSetup()
            _state.update { it.copy(starting = false) }
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

    fun setQuery(query: String) = _state.update { current ->
        // Searching stays live during selection now, so a query can narrow the
        // list out from under picked rows. Any selection that no longer matches
        // has left the visible list, and must leave the selection with it - a
        // later delete acts on names, and would otherwise take a row the user
        // can no longer see. Widening the query keeps everything, as it should.
        val next = if (current.selecting) {
            val stillShown = filterRecordings(
                current.recordings, query, current.filter, current.favoritesOnly,
            ).map { it.name }.toSet()
            current.selected intersect stillShown
        } else {
            current.selected
        }
        current.copy(query = query, selected = next)
    }

    /** Fold the search field in or out. Folding it away also drops the query:
     *  a filter still narrowing the list from behind a hidden field is a filter
     *  the user cannot see is on, and the chip that reopens search would not
     *  explain why half the recordings were missing. */
    fun toggleSearch() = _state.update {
        if (it.searchOpen) it.copy(searchOpen = false, query = "") else it.copy(searchOpen = true)
    }

    fun setFilter(filter: RecordingFilter) = _state.update {
        // Tapping the active chip again clears it back to All, the usual
        // single-select filter-chip behaviour. These are the TIME presets only;
        // favourites is separate and untouched here.
        it.copy(filter = if (it.filter == filter) RecordingFilter.ALL else filter)
    }

    /** Favourites is a toggle on its own axis, not one of the time presets:
     *  turning it on narrows to starred contacts on TOP of the time range,
     *  turning it off widens back. It never disturbs the time chips. */
    fun toggleFavorites() = _state.update { it.copy(favoritesOnly = !it.favoritesOnly) }


    fun useDefaultFolder() = launchAction("use default folder") {
        RecordingStore.clearTree(getApplication())
        say("recordings will go to the standard Recordings folder")
        refresh()
    }

    fun onFolderChosen(uri: Uri) = launchAction("choose folder") {
        RecordingStore.setTree(getApplication(), uri)
        say("recordings will be saved to the folder you chose")
        refresh()
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

        // ALL of this is file I/O and it must not run on the main thread.
        // viewModelScope dispatches on Main, and listing recordings queries
        // MediaStore and opens every file with MediaMetadataRetriever to read
        // its duration. On the UI thread that freezes the app - and because the
        // activity had been brought to the front over the dialer, it froze the
        // dialer's touches with it. An unresponsive phone right after a call is
        // about the worst thing this app could do.
        val snapshot = withContext(Dispatchers.IO) {
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
                usbDebuggingOn = Setup.usbDebuggingOn(app),
                pairingNotificationsBlocked = PairingNotification.blocked(app),
                folder = RecordingStore.describe(app),
                usingDefaultFolder = RecordingStore.treeUri(app) == null,
                automatic = RecordingMode.of(app) == RecordingMode.AUTOMATIC,
                enabled = RecorderSwitch.isOn(app),
                canReadCallLog = CallLogLookup.granted(app),
                canReadAudio = AudioAccess.granted(app),
                offerCallLog = CallLogLookup.anythingLeftToOffer(app) &&
                    !CallLogLookup.bannerDismissed(app),
                recordings = Recordings.list(app),
                selectHintSeen = Prefs.of(app).getBoolean("select_hint_seen", false),
            )
        }

        _state.update {
            it.copy(
                daemonRunning = snapshot.daemonRunning,
                wirelessDebuggingOn = snapshot.wirelessDebuggingOn,
                usbDebuggingOn = snapshot.usbDebuggingOn,
                pairingNotificationsBlocked = snapshot.pairingNotificationsBlocked,
                folder = snapshot.folder,
                usingDefaultFolder = snapshot.usingDefaultFolder,
                automatic = snapshot.automatic,
                enabled = snapshot.enabled,
                canReadCallLog = snapshot.canReadCallLog,
                canReadAudio = snapshot.canReadAudio,
                offerCallLog = snapshot.offerCallLog,
                recordings = snapshot.recordings,
                selectHintSeen = snapshot.selectHintSeen,
                // A name that is no longer in the list cannot be selected. It
                // would still be counted by the toolbar, and the count is what
                // the user reads before confirming a deletion.
                selected = it.selected intersect snapshot.recordings.map { r -> r.name }.toSet(),
                connected = AdbTransport.isConnected,
            )
        }
    }

    /** Everything refresh() reads off disk, gathered in one background pass. */
    private data class Snapshot(
        val daemonRunning: Boolean,
        val wirelessDebuggingOn: Boolean,
        val usbDebuggingOn: Boolean,
        val pairingNotificationsBlocked: Boolean,
        val folder: String,
        val usingDefaultFolder: Boolean,
        val automatic: Boolean,
        val enabled: Boolean,
        val canReadCallLog: Boolean,
        val canReadAudio: Boolean,
        val offerCallLog: Boolean,
        val recordings: List<Recording>,
        val selectHintSeen: Boolean,
    )

    /**
     * One action at a time. Every entry point funnels through here so that the
     * busy flag, the connected flag and error reporting cannot drift apart.
     */
    private fun launchAction(label: String, block: suspend () -> Unit) {
        if (_state.value.busy) return
        viewModelScope.launch {
            _state.update { it.copy(busy = true) }
            try {
                block()
            } catch (t: Throwable) {
                Log.w("JemRec", "$label failed", t)
                say("$label failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                _state.update { it.copy(busy = false, connected = AdbTransport.isConnected) }
            }
        }
    }

    /** Guards against two bring-ups racing when the screen is reopened. */
    private val reviving = AtomicBoolean(false)

    /** The in-flight .m4a conversion, so its spinner's Cancel can stop it. */
    private var conversionJob: Job? = null

    private val player by lazy { RecordingPlayer(getApplication()) }
    private var ticker: kotlinx.coroutines.Job? = null

    /**
     * Play or pause a recording, inside this app.
     *
     * It used to fire an ACTION_VIEW and let Android choose, which on this
     * phone meant YouTube Music's preview bar - no close button, and it looked
     * like the app had frozen. The privacy problem was worse: handing a
     * recording of a private call to whichever app claimed the intent.
     */
    fun play(recording: Recording) {
        player.toggle(recording.uri) { onPlaybackFinished() }
        startTicker(recording)
    }

    fun seekTo(ms: Int) {
        player.seekTo(ms)
        _state.update { it.copy(positionMs = ms) }
    }

    /**
     * The end of the recording rewinds the player; it does not close it. It
     * used to vanish the moment playback ended - a player that disappears
     * under your thumb as you go to seek back is a small ambush. The chevron
     * is the way out; this leaves the row ready to play again.
     */
    private fun onPlaybackFinished() {
        ticker?.cancel()
        _state.update { it.copy(playing = false, positionMs = 0) }
    }

    /** Drives the progress bar. Cheap, and only while something is playing. */
    private fun startTicker(recording: Recording) {
        ticker?.cancel()
        _state.update {
            it.copy(
                playingName = if (player.current != null) recording.name else null,
                playing = player.isPlaying,
                durationMs = player.durationMs,
                positionMs = player.positionMs,
            )
        }
        if (!player.isPlaying) return
        ticker = viewModelScope.launch {
            while (player.isPlaying) {
                _state.update {
                    it.copy(playing = true, positionMs = player.positionMs, durationMs = player.durationMs)
                }
                kotlinx.coroutines.delay(300)
            }
            _state.update { it.copy(playing = false) }
        }
    }

    /** The chevron on the scrubber: stop, and fold the row back. */
    fun stopPlaying() = stopPlayback()

    private fun stopPlayback() {
        ticker?.cancel()
        player.release()
        _state.update {
            it.copy(playing = false, playingName = null, positionMs = 0, durationMs = 0)
        }
    }

    override fun onCleared() {
        ticker?.cancel()
        player.release()
        super.onCleared()
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
                say("could not prepare that recording to share")
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
            .onFailure { say("could not share that recording") }
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

    /** The tap-a-photo hint has done its job once selection has been used. */
    private fun rememberSelectHintSeen() {
        Prefs.of(getApplication()).edit().putBoolean("select_hint_seen", true).apply()
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
        val visible = filterRecordings(
            current.recordings, current.query, current.filter, current.favoritesOnly,
        ).take(current.visibleCount)
        current.copy(selected = visible.map { it.name }.toSet())
    }

    fun clearSelection() = _state.update { it.copy(selecting = false, selected = emptySet()) }

    /** Unpick everything but stay in selection mode: the user is still
     *  choosing, they have just changed their mind about all of it. */
    fun deselectAll() = _state.update { it.copy(selected = emptySet()) }

    fun askDeleteSelected() = _state.update { it.copy(confirmingDeleteSelected = true) }

    fun cancelDeleteSelected() = _state.update { it.copy(confirmingDeleteSelected = false) }

    /**
     * Delete what is selected.
     *
     * Selection mode ends whether or not every deletion succeeded. Leaving the
     * user in a toolbar showing a count that no longer means anything is worse
     * than making them tap Select again.
     */
    fun confirmDeleteSelected() {
        _state.update { it.copy(confirmingDeleteSelected = false) }
        launchAction("delete recordings") {
            val current = _state.value
            val doomed = current.recordings.filter { it.name in current.selected }
            if (doomed.isEmpty()) return@launchAction
            if (current.playingName in current.selected) stopPlayback()

            val result = withContext(Dispatchers.IO) {
                Recordings.deleteAll(getApplication(), doomed)
            }
            say("deleted ${result.deleted}, could not delete ${result.failed}")
            result.needsConsent?.let { request ->
                _state.update { it.copy(deleteConsent = request) }
            }
            _state.update { it.copy(selecting = false, selected = emptySet()) }
            refresh()
        }
    }

    /** Named `say` rather than `append` on purpose: inside a buildString block
     *  an `append` member would be shadowed by StringBuilder.append. */
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
