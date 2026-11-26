package com.jemcik.jemrec.ui

import android.app.Application
import android.content.ComponentName
import android.net.Uri
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.jemcik.jemrec.DeepLink
import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.answering.Greeting
import com.jemcik.jemrec.calls.CallRules
import com.jemcik.jemrec.calls.MissedCalls
import com.jemcik.jemrec.calls.VoipApps
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.LastCallReport
import com.jemcik.jemrec.capture.PlaybackService
import com.jemcik.jemrec.playback.Output
import com.jemcik.jemrec.playback.PlayThrough
import com.jemcik.jemrec.recorder.BackgroundKill
import com.jemcik.jemrec.recorder.OffMode
import com.jemcik.jemrec.recorder.SelfTest
import com.jemcik.jemrec.recordings.FileNames
import com.jemcik.jemrec.recordings.Recording
import com.jemcik.jemrec.recordings.RecordingQuality
import com.jemcik.jemrec.recordings.Trash
import com.jemcik.jemrec.system.AutoStart
import com.jemcik.jemrec.ui.theme.LightDark
import com.jemcik.jemrec.ui.theme.Look
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The app's one view model, for every screen: the state they draw (UiState),
 * where they are (Screen, HomeTab), a notification's way in (open), and
 * Diagnostics' self-test and report. The rest is done by its parts, each on
 * the same state, and reached here by one-line delegates the screens and the
 * tests call: the log (ActionLog), the busy policy (Actions), the reading
 * (Reading), the player (Playback), Share (Sharing), the waveforms
 * (WaveformFill), the lists (RecordingsList), Recently deleted
 * (TrashActions), Settings (SettingsActions), setup and the recorder
 * (RecorderAndSetup), and the answering machine (AnswerController).
 */
class MainViewModel @JvmOverloads constructor(
    app: Application,
    /** Tests seed the list through this; the app never passes it. Without it
     *  the only way recordings entered state was a reading (Reading.now),
     *  which is device-bound, and the tests reached into the flow by
     *  reflection. */
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
    lookForAdb: suspend (Application) -> Boolean = { a ->
        AdbTransport.autoConnect(a, timeoutMs = 6_000).isSuccess
    },
    /** How long after a flip of Start without Wi-Fi a close still due is
     *  tried again (setStartWithoutWifi). The tests' is short. */
    portCloseAgainMs: Long = 10_000,
    /** Where the user was, kept through the end of the process in the
     *  background (SavedPlace): Android's own place for a view model's
     *  saved state, given by its factory (below); none in the tests that
     *  make one themselves. */
    saved: SavedStateHandle? = null,
) : AndroidViewModel(app), RowEvents {

    /** The one Android's factory makes, with the window's saved state - the
     *  constructor lifecycle's own R8 rules keep. */
    constructor(app: Application, saved: SavedStateHandle) : this(app, UiState(listUnread = true, callsUnread = true), saved = saved)

    // Whether messenger calls can work here is known at once (a cached
    // permission check): the Settings card must not show their switches'
    // text for a moment on a phone where they cannot work.
    private val _state = MutableStateFlow(initial.copy(voipSupported = VoipApps.supported(app)))
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        // The settings as saved, at once: preferences, quick enough here.
        // The window's first reading takes seconds, and until it landed the
        // header drew the defaults - JemRec on, Every call - whatever was
        // chosen (review, 2026-11-18). For any view model made with nothing
        // read yet - Android's, and a test's that starts unread - the saved
        // settings put in; one a test makes read keeps what it is given.
        if (_state.value.listUnread) _state.update { asSaved(app, it) }
        // Made new after Android ended the process in the background: back
        // where the user was. A view model that lived through - the phone
        // turned - is not made again, and has it already. Saved whenever
        // the window's state is ("Save UI states": state the view model
        // holds, saved with SavedStateHandle).
        saved?.get<android.os.Bundle>(SavedPlace.NAME)?.let { place -> _state.update { SavedPlace.restore(it, place) } }
        saved?.setSavedStateProvider(SavedPlace.NAME) { android.os.Bundle().also { SavedPlace.save(_state.value, it) } }
    }

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
    // What answers a tap there is a snackbar as well (AnswerController.tell):
    // until 0.30 "could not arm", "no test call came" and the rest were in
    // Diagnostics' log alone.
    val answer = AnswerController(
        app, viewModelScope, log::say, initialAnswer, quiet = playback::pause,
        tell = log::tell, shown = { _state.value.screen == Screen.ANSWERING_MACHINE },
    )

    /** Which screen is shown, watched for the answering machine's to learn
     *  it was left. A scope of its own, ended with the view model: a watch
     *  never ends, and the view model's own work is waited for. */
    private val watching = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var leaving: Job? = null

    /**
     * The answering machine's screen opened: its own opening, and a watch for
     * it being left - Back, a link - which stops its preview and a take still
     * recording (AnswerController.left): a take went on unseen, and saved
     * whatever was playing as the greeting. Started here rather than with the
     * view model, which tests make with no main thread.
     */
    fun answeringMachineOpened() {
        answer.opened()
        if (leaving?.isActive == true) return
        leaving = watching.launch {
            var shown = _state.value.screen
            _state.collect { now ->
                if (now.screen != shown) {
                    if (shown == Screen.ANSWERING_MACHINE) answer.left()
                    shown = now.screen
                }
            }
        }
    }

    /** The screen's reading of everything it shows from outside itself. */
    private val reading = Reading(app, viewModelScope, _state, answer, playback, fill)

    /** Recently deleted's buttons, and Android's question before a move. */
    private val trash = TrashActions(app, _state, log, actions, reading)

    /** The lists: chips and search, paging, keep marks, selection. */
    private val list = RecordingsList(app, viewModelScope, _state, log, actions, reading, playback, trash)

    /** What Settings changes, and the permission answers. */
    private val settings = SettingsActions(app, _state, log, actions, reading, answer, trash)

    /** Setup's steps and the recorder's state, from the screen. */
    private val recorder = RecorderAndSetup(app, viewModelScope, _state, log, actions, reading, lookForAdb, portCloseAgainMs)

    /** The answer to something the user just did, for a snackbar where they are. */
    val notices: SharedFlow<String> = log.notices

    /** Android's folder picker, asked for by a notification's Choose again
     *  (DeepLink.Kind.CHOOSE_FOLDER), where to start it: the picker is the
     *  screen's to launch (MainScreen). Kept until the screen takes it - one
     *  asked for as the activity is made comes before the screen listens. */
    private val folderPicks = kotlinx.coroutines.channels.Channel<android.net.Uri?>(kotlinx.coroutines.channels.Channel.CONFLATED)
    val folderPickRequests: kotlinx.coroutines.flow.Flow<android.net.Uri?> = folderPicks.receiveAsFlow()

    /** A reading of everything, for callers that only want the screen to
     *  catch up (Reading.refresh). */
    fun refresh() = reading.refresh()

    private suspend fun refreshNow() = reading.now()

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

    /** Copy a report, on setup's cards that can fail (SetupReport). Read
     *  from memory and the screen's state: nothing to wait for. */
    fun copySetupReport() {
        val app = getApplication<Application>()
        reportToClipboard(app, "JemRec setup report", SetupReport.text(app, appVersion(app), _state.value))
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

    /**
     * A notification brought the user here. Its button: share a call, run
     * the self-test, pair again, allow this Wi-Fi, the phone maker's
     * background settings, choose the folder again. Play that call: the
     * after-call notification's tap, and its button. A problem's tap: JemRec
     * where the problem is shown - home, the Calls tab narrowed to calls not
     * recorded, Settings with its background row in view; home is the plain
     * notifications' tap too.
     *
     * Each waits until nothing else is running, because opening the app from
     * a notification is also a resume, and the resume's own setup check holds
     * the busy flag - which would otherwise swallow the self-test or the
     * re-pair the user just asked for.
     */
    fun open(link: DeepLink.Link) {
        DeepLink.consumed()
        // A question left open is let go: a link that changed the screen
        // left "Start fresh?" or "Delete all for good?" to come back unasked
        // on the next visit, its count taken then.
        _state.update {
            it.copy(
                confirmingReset = false, confirmingEmptyTrash = false, confirmingDeleteSelected = false,
                askingToTurnOff = false, shareChoice = null,
            )
        }
        viewModelScope.launch {
            val app = getApplication<Application>()
            when (link.kind) {
                DeepLink.Kind.PLAY, DeepLink.Kind.SHARE -> {
                    refreshNow()
                    // Or under the name this version gave it as the list was
                    // read, its notification still on the old one
                    // (HonorRenames).
                    val recording = _state.value.recordings.firstOrNull { it.name == link.name }
                        ?: link.name?.let(FileNames::honorRenamed)?.let { renamed -> _state.value.recordings.firstOrNull { it.name == renamed } }
                    if (recording == null) {
                        // Gone by another way than JemRec's: a file manager's.
                        link.name?.let { Trash.left(app, it) }
                        tell("that recording is no longer there")
                        return@launch
                    }
                    // Its row has to be on screen for its player to be, so
                    // whatever was narrowing the list goes, and the list
                    // brings the row into view.
                    _state.update { it.revealing(recording.name) }
                    if (link.kind == DeepLink.Kind.SHARE) {
                        share(recording)
                    } else if (_state.value.playingName != recording.name || !_state.value.playing) {
                        // A greeting's take this link left - stopped, not
                        // kept - holds the microphone until the recorder
                        // answers its stop: the play waits for that. It was
                        // refused, "while your greeting is recorded", with
                        // no greeting being recorded any more.
                        if (Greeting.takeListening) {
                            withTimeoutOrNull(TAKE_STOP_WAIT_MS) { Greeting.holder.first { it == null } }
                        }
                        play(recording)
                    }
                }
                DeepLink.Kind.SELF_TEST -> {
                    // Only the missed calls' own: the silent recording's
                    // Self-test took the list of calls not recorded down,
                    // unread (review round 9).
                    if (link.name == MissedCalls.LINK) MissedCalls.dismiss(app)
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
                // A plain tap: the recordings list and its header, nothing
                // narrowing it and nothing selected - the media notification's
                // row among them - where until 0.30 each of these opened a
                // window of its own on that list.
                DeepLink.Kind.HOME -> _state.update { it.revealed() }
                // From the "stopped in the background" warning: open the vendor
                // auto-start screen. JemRec cannot change the setting itself,
                // so the button only takes the user there. BackgroundKill.cancel
                // clears the notification now that it has been acted on.
                DeepLink.Kind.BACKGROUND_SETTINGS -> {
                    BackgroundKill.cancel(app)
                    AutoStart.open(app)
                }
                // The Battery notice's Open App info: Android's page for
                // JemRec, opened from here as the background warning's
                // screen is - the notification's button names JemRec's own
                // activity, never an implicit intent another app could
                // answer (CodeQL java/android/implicit-pendingintents, PR
                // #105). The notice stays until the setting is lifted.
                DeepLink.Kind.BATTERY_SETTINGS -> com.jemcik.jemrec.system.BatteryHealth.openSettings(app)
                // The calls not recorded, from their notification's tap: the
                // Calls tab narrowed to them - each says it was not, and why
                // where it knows. Its own narrowing, the Recordings tab's left
                // as it was: a Kept or a search there hid the very calls the
                // notification named (review round 9).
                DeepLink.Kind.CALLS -> {
                    MissedCalls.dismiss(app)
                    _state.update {
                        it.copy(screen = Screen.HOME, selecting = false, selected = emptySet(), callsNarrowing = CallsNarrowing(notRecorded = true))
                    }
                    showTab(HomeTab.CALLS)
                }
                // The background-stop warning's tap: Settings, whose
                // Troubleshooting opens the phone maker's screen and says why.
                DeepLink.Kind.SETTINGS -> {
                    showScreen(Screen.SETTINGS)
                    _state.update { it.copy(revealBackground = true) }
                }
                // "Recording saved elsewhere"'s Choose again: home, its card
                // under the picker, and the picker at once.
                DeepLink.Kind.CHOOSE_FOLDER -> {
                    _state.update { it.revealed() }
                    folderPicks.trySend(folderPickerStart())
                }
                // The USB default notification's Set to No data transfer:
                // home, where its card is, and the card's own tap.
                DeepLink.Kind.USB_DEFAULT_NONE -> {
                    _state.update { it.revealed() }
                    recorder.setUsbDefaultNone()
                }
            }
        }
    }

    /** Settings' background row is in view: asked once. */
    fun backgroundRevealed() = _state.update { it.copy(revealBackground = false) }

    /** The list's waveform fill-in stops with the screen (WaveformFill). */
    fun stopFillingIn() = fill.stop()

    /** The screen came forward: the pairing watched from now on, and the
     *  player shows what the session plays (Playback.shown). */
    fun screenShown() {
        recorder.watchPairing()
        playback.shown()
    }

    /** Out of sight: the scrubber is no longer driven, and the recording
     *  plays on (Playback.hidden). */
    fun screenHidden() = playback.hidden()

    /** In front, or not: a recording moves to the earpiece at the ear only
     *  while the screen is (EarWatch). */
    fun screenResumed(resumed: Boolean) = playback.resumed(resumed)

    /** The player's earpiece button. */
    override fun chooseOutput(output: Output) = playback.choose(output)

    /** Settings' "Play recordings": saved, and what plays follows it. */
    fun setBothEars(on: Boolean) = settings.setBothEars(on)

    fun setPlayThrough(playThrough: PlayThrough) {
        settings.setPlayThrough(playThrough)
        playback.playThrough(playThrough)
    }

    /** Play or pause a recording (Playback). */
    override fun play(recording: Recording) = playback.play(recording)

    override fun seekTo(ms: Int) = playback.seekTo(ms)

    override fun positionNow(): Int? = playback.positionNow()

    /** The chevron on the scrubber: stop, and fold the row back. */
    override fun stopPlaying() = playback.stopPlayback()

    /** Share's question first (Sharing). */
    override fun share(recording: Recording) = sharing.share(recording)

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

    /** The lists' chips, search and selection (RecordingsList). */
    fun setQuery(query: String) = list.setQuery(query)

    internal fun tapRecordingsChip(chip: ListChip) = list.tapRecordingsChip(chip)

    fun showAllRecordings() = list.showAllRecordings()

    fun showAllCalls() = list.showAllCalls()

    internal fun tapCallsChip(chip: ListChip) = list.tapCallsChip(chip)

    fun setCallsQuery(query: String) = list.setCallsQuery(query)

    override fun toggleKept(recording: Recording) = list.toggleKept(recording)

    fun keepSelected() = list.keepSelected()

    fun showMore() = list.showMore()

    fun onRevealed(name: String) = list.onRevealed(name)

    fun startSelecting() = list.startSelecting()

    override fun startSelecting(recording: Recording) = list.startSelecting(recording)

    fun dismissSelectHint() = list.dismissSelectHint()

    override fun toggleSelected(recording: Recording) = list.toggleSelected(recording)

    fun selectAllVisible() = list.selectAllVisible()

    fun clearSelection() = list.clearSelection()

    fun deselectAll() = list.deselectAll()

    fun askDeleteSelected() = list.askDeleteSelected()

    fun cancelDeleteSelected() = list.cancelDeleteSelected()

    fun confirmDeleteSelected() = list.confirmDeleteSelected()

    /** What Settings changes, and the permission answers (SettingsActions). */
    fun dismissCallLogOffer() = settings.dismissCallLogOffer()
    fun dismissNotificationsOff(off: com.jemcik.jemrec.calls.NotificationHealth.Off) = settings.dismissNotificationsOff(off)
    fun dismissBatteryNotice() = settings.dismissBatteryNotice()
    fun dismissUsbDefaultNotice() = settings.dismissUsbDefaultNotice()
    fun setUsbDefaultNone() = recorder.setUsbDefaultNone()
    fun openBatterySettings() = com.jemcik.jemrec.system.BatteryHealth.openSettings(getApplication())
    fun openNotificationSettings(off: com.jemcik.jemrec.calls.NotificationHealth.Off) =
        com.jemcik.jemrec.calls.NotificationHealth.openSettings(getApplication(), off)

    fun onAudioAccessResult() = settings.onAudioAccessResult()

    fun onCallLogResult() = settings.onCallLogResult()

    fun setAutomatic(automatic: Boolean) = settings.setAutomatic(automatic)

    fun setCallButton(on: Boolean) = settings.setCallButton(on)

    fun setAfterCallNotice(on: Boolean) = settings.setAfterCallNotice(on)

    fun setLook(look: Look) = settings.setLook(look)

    fun setLightDark(lightDark: LightDark) = settings.setLightDark(lightDark)

    fun setAutoDelete(afterDays: Int? = null, shorterThan: Int? = null) = settings.setAutoDelete(afterDays, shorterThan)

    fun setRuleContacts(contacts: CallRules.Contacts) = settings.setRuleContacts(contacts)

    fun setRuleOtherNumbers(on: Boolean) = settings.setRuleOtherNumbers(on)

    fun addRuleContact(contact: Uri) = settings.addRuleContact(contact)

    fun removeRuleContact(key: String) = settings.removeRuleContact(key)

    fun trackingOffTakesTheMachine() = settings.trackingOffTakesTheMachine()

    fun setCallTracking(on: Boolean) = settings.setCallTracking(on)

    fun setVoipApp(pkg: String, on: Boolean) = settings.setVoipApp(pkg, on)

    fun setVoipNames(on: Boolean) = settings.setVoipNames(on)
    fun openNewestVersion() = settings.openNewestVersion()

    fun setFileNames(format: FileNames.Format) = settings.setFileNames(format)
    fun setQuality(quality: RecordingQuality) = settings.setQuality(quality)

    fun useDefaultFolder() = settings.useDefaultFolder()

    /** Where the folder picker opens: the folder chosen, or, choosing the
     *  standard folder for Honor's names, Honor's own (FileNames.honorFolderUri). */
    fun folderPickerStart(): android.net.Uri? =
        com.jemcik.jemrec.recordings.RecordingStore.pickerStart(getApplication())
            ?: FileNames.honorFolderUri.takeIf { state.value.fileNames == FileNames.Format.HONOR }

    fun onFolderChosen(uri: Uri) = settings.onFolderChosen(uri)

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    fun onTileAddAnswer(result: Int) = settings.onTileAddAnswer(result)

    /** Setup's steps and the recorder, from the screen (RecorderAndSetup). */
    fun askReset() = recorder.askReset()

    fun cancelReset() = recorder.cancelReset()

    fun confirmReset() = recorder.confirmReset()

    fun refreshSetup() = recorder.refreshSetup()

    /** JemRec opened: the recorder-down alert seen (RecorderHealth.seen). */
    fun alertSeen() = com.jemcik.jemrec.recorder.RecorderHealth.seen(getApplication())

    fun allowThisWifi() = recorder.allowThisWifi()

    fun confirmDeveloperOptions() = recorder.confirmDeveloperOptions()

    fun confirmBackgroundChoice() = recorder.confirmBackgroundChoice()

    fun openBackgroundSettings() = recorder.openBackgroundSettings()

    fun onWifiAvailable() = recorder.onWifiAvailable()

    fun startAgain() = recorder.startAgain()

    fun pairAgain() = recorder.pairAgain()

    fun showPairingNotification() = recorder.showPairingNotification()

    fun startPairingNotification() = recorder.startPairingNotification()

    fun reconnect() = recorder.reconnect()

    fun retryFinish() = recorder.retryFinish()

    fun bootstrapDaemon() = recorder.bootstrapDaemon()

    fun toggleRecording(on: Boolean) = recorder.toggleRecording(on)

    fun answerTurnOff(turnOff: Boolean) = recorder.answerTurnOff(turnOff)

    fun setOffMode(mode: OffMode) = recorder.setOffMode(mode)

    fun setStartWithoutWifi(on: Boolean) = recorder.setStartWithoutWifi(on)

    fun setEnabled(on: Boolean) = recorder.setEnabled(on)

    fun setHideDebugNotification(hide: Boolean) = recorder.setHideDebugNotification(hide)
    fun setKeepPaired(keep: Boolean) = recorder.setKeepPaired(keep)

    /** The screen is gone for good; a recording playing goes on playing,
     *  under the system's controls, until they stop it. */
    override fun onCleared() {
        watching.cancel()
        recorder.close()
        settings.close()
        playback.close()
        answer.close()
    }

    private fun tell(text: String) = log.tell(text)

    private fun say(text: String) = log.say(text)
}

/** How long a Play link waits for a greeting's take it left to be stopped:
 *  the recorder answers a stop at once when it is up. */
private const val TAKE_STOP_WAIT_MS = 5_000L
