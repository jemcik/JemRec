package com.jemcik.jemrec.ui

import android.app.Application
import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.calls.AfterCall
import com.jemcik.jemrec.calls.CallHistory
import com.jemcik.jemrec.calls.CallLogLookup
import com.jemcik.jemrec.calls.CallRules
import com.jemcik.jemrec.calls.CallTracking
import com.jemcik.jemrec.calls.VoipApps
import com.jemcik.jemrec.calls.VoipCallNotes
import com.jemcik.jemrec.capture.AudioAccess
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.DebugNotificationCleaner
import com.jemcik.jemrec.capture.RecordingMode
import com.jemcik.jemrec.playback.PlayThroughSetting
import com.jemcik.jemrec.recorder.DebugSwitchesOff
import com.jemcik.jemrec.recorder.KeepPaired
import com.jemcik.jemrec.recorder.NetworkRevive
import com.jemcik.jemrec.recorder.RecorderHealth
import com.jemcik.jemrec.recorder.RecorderSwitch
import com.jemcik.jemrec.recorder.StartWithoutWifi
import com.jemcik.jemrec.recordings.FileNames
import com.jemcik.jemrec.recordings.Recording
import com.jemcik.jemrec.recordings.RecordingLibrary
import com.jemcik.jemrec.recordings.RecordingStore
import com.jemcik.jemrec.recordings.Retention
import com.jemcik.jemrec.setup.PairingNotification
import com.jemcik.jemrec.setup.Setup
import com.jemcik.jemrec.system.ShellGrant
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.Looks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A setting the screen shows: read off the main thread, then put into the
 *  state as it is when the reading arrives. */
private typealias Read = suspend (Application) -> (UiState) -> UiState

/** [get]'s value, put by [put]. */
private fun <T> read(get: suspend (Application) -> T, put: UiState.(T) -> UiState): Read =
    { app -> val value = get(app); { state -> state.put(value) } }

/**
 * The settings saved while a reading may be under way, read as it lands,
 * not with the rest: one saved between the reading's read and its landing
 * was shown and then put back by the reading until the next.
 *
 * - The ones the screen watches as they are saved (RecorderSwitch's,
 *   DebugSwitchesOff's, VoipCallNotes' and DebugNotificationCleaner's
 *   watch): the tile, a notification-access grant that outlived its screen.
 * - Everything the screen saves and shows at once, without a reading of its
 *   own: every choice Settings saves from its rows, the tile's answer, the
 *   selection tip seen, the call-log offer dismissed, the two permissions'
 *   answers. A reading waits seconds on the recorder; one made then -
 *   "Play recordings", file names, the rules, the tile - came back as it was
 *   before (the 1.1 review, 2026-11-15).
 *
 * Preferences, read from memory, and permission checks, which Android
 * answers from its own cache: quick enough for the main thread, where the
 * landing is. Nothing here may read a file or query a provider.
 */
internal fun asSaved(app: Application, state: UiState): UiState = state.copy(
    enabled = RecorderSwitch.isOn(app),
    debuggingOff = DebugSwitchesOff.recorderGone(app),
    voipNames = VoipCallNotes.enabled(app),
    debugNotificationHidden = DebugNotificationCleaner.hiding(app),
    keepPairedWaiting = KeepPaired.waiting(app),
    keepPairedRefused = KeepPaired.refused(app),
    automatic = RecordingMode.of(app) == RecordingMode.AUTOMATIC,
    afterCallNotice = AfterCall.enabled(app),
    playThrough = PlayThroughSetting.of(app),
    offMode = DebugSwitchesOff.mode(app),
    startWithoutWifi = StartWithoutWifi.enabled(app),
    autoDeleteAfterDays = Retention.maxAgeDays(app),
    autoDeleteShorterThan = Retention.minSeconds(app),
    ruleContacts = CallRules.contacts(app),
    ruleOtherNumbers = CallRules.otherNumbers(app),
    callTracking = CallTracking.enabled(app),
    voipEnabled = VoipApps.enabled(app),
    fileNames = FileNames.format(app),
    tileAdded = RecorderTile.isAdded(app),
    selectHintSeen = SelectHint.seen(app),
    canReadCallLog = CallLogLookup.granted(app),
    canReadAudio = AudioAccess.granted(app),
    canReadContacts = CallLogLookup.photosGranted(app),
    // What is left to offer is read with the rest; a dismissal, as it lands.
    offerCallLog = state.offerCallLog && !CallLogLookup.bannerDismissed(app),
)

/**
 * Everything a reading takes in besides the lists and the settings read as
 * it lands ([asSaved]), in the order it is read: each once, its read beside
 * the field it fills. A new one is one line here - or in [asSaved], for a
 * setting saved from the screen.
 */
private val SETTINGS: List<Read> = listOf(
    // CONFIRMED, because this reading decides whether to revive.
    //
    // A single ping is a health check; here it was a death certificate. One
    // missed reply - and on battery, with the CPU throttled and the app just
    // resumed, they are missed - meant "the recorder is down", which fired a
    // revive, which re-armed Wireless debugging, which made Android post its
    // notification. Every trip to the home screen and back. Plugged into USB
    // the phone stays awake, the ping never misses, and none of it happens;
    // that is what made it look like a mystery rather than a timeout.
    read(CaptureDaemon::isRunningConfirmed) { copy(daemonRunning = it) },
    // Read alongside the daemon check: if the recorder is down, the header
    // needs to know whether the ADB channel is even open before it tells the
    // user to wait for Wi-Fi.
    read(Setup::wirelessDebuggingOn) { copy(wirelessDebuggingOn = it) },
    read(Setup::usbDebugging) { copy(usbDebugging = it) },
    read(Setup::developerOptionsHidden) { copy(developerOptionsHidden = it) },
    // Why the recorder is down, once for both of the header's readings of it.
    read(RecorderHealth::cause) {
        copy(
            networkNotAllowed = it == RecorderHealth.Problem.NETWORK_NOT_ALLOWED,
            pairingRefused = it == RecorderHealth.Problem.CANNOT_CONNECT,
        )
    },
    read(NetworkRevive::onWifi) { copy(onWifi = it) },
    read(PairingNotification::blocked) { copy(pairingNotificationsBlocked = it) },
    // Only until setup is done: a getprop, on the phones that may have the
    // switch.
    read({ !Setup.isComplete(it) && ShellGrant.switchMayBeOff() }) { copy(systemOptimizationSwitch = it) },
    read(RecordingStore::describe) { copy(folder = it) },
    read({ RecordingStore.treeUri(it) == null }) { copy(usingDefaultFolder = it) },
    // An earpiece, and a proximity sensor to know when it is at the ear:
    // what "Play recordings" offers.
    read({ EarWatch.hasEarpiece(it) to EarWatch.canDarkenScreen(it) }) { (earpiece, darkens) ->
        copy(earpieceAvailable = earpiece, raiseAvailable = earpiece && darkens)
    },
    // Android's pairing timeout, for Keep JemRec paired: a settings query.
    read(KeepPaired::timeoutMs) { copy(pairingKept = it == 0L, pairingDays = KeepPaired.days(it)) },
    read(StartWithoutWifi::readyForUser) { copy(portSet = it) },
    read(StartWithoutWifi::openedThisBoot) { copy(portOurs = it) },
    read(StartWithoutWifi::failedThisBoot) { copy(portFailed = it) },
    read(StartWithoutWifi::closeFailedThisBoot) { copy(portCloseFailed = it) },
    read({ CallLogLookup.anythingLeftToOffer(it) && !CallLogLookup.bannerDismissed(it) }) { copy(offerCallLog = it) },
    // Whose calls: the choice, the contacts chosen - a contacts query or two
    // each, so only for the choices that list them - and whether contacts
    // may be read at all, which telling who is who needs. Each read once.
    read({ app ->
        val contacts = CallRules.contacts(app)
        val canReadContacts = CallLogLookup.photosGranted(app)
        val listed = canReadContacts && (contacts == CallRules.Contacts.ALL_BUT || contacts == CallRules.Contacts.ONLY)
        Triple(contacts, canReadContacts, if (listed) CallRules.chosen(app) else emptyList())
    }) { (_, _, chosen) ->
        // The choice and the permission are read as the reading lands
        // (asSaved); the contacts chosen, a query, here.
        copy(ruleChosen = chosen)
    },
    read(VoipApps::installed) { copy(voipInstalled = it) },
    read(VoipApps::supported) { copy(voipSupported = it) },
    read(FileNames::honorDialer) { copy(honorDialer = it) },
    read(FileNames::inHonorFolder) { copy(inHonorFolder = it) },
    read(::appVersion) { copy(appVersion = it) },
)

/**
 * The screen's reading of everything it shows from outside itself: the
 * recordings and the trash, the call log while its tab is up, Multitrack's
 * caller colours, the recorder's state and every setting (SETTINGS) - read
 * together, off the main thread, and put into the state at once, with the
 * switches the screen watches as they are saved then ([asSaved]).
 *
 * After each reading is put in, in this order: the selection keeps only the
 * rows still shown, the answering machine reads its own settings again,
 * playback hears of the list (Playback.listRead), and the waveform fill-in
 * goes over it.
 */
internal class Reading(
    private val app: Application,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<UiState>,
    private val answer: AnswerController,
    private val playback: Playback,
    private val fill: WaveformFill,
) {
    /** Readings begun, and the latest whose list is on screen. Main thread. */
    private var started = 0L
    private var applied = 0L

    /**
     * Fire and forget, for callers that only want the screen to catch up.
     *
     * Anything that needs the READING before it carries on must await [now]
     * instead. This one returns the instant it is called, which is how a
     * caller could finish, drop its busy flag and let the screen render a
     * verdict from before the work it had just done.
     */
    fun refresh(): Job = scope.launch { now() }

    /** A reading, put into the state before it returns. */
    suspend fun now() {
        // Readings overlap - a resume, a save announced, a keep toggled - and
        // each reads the list, then waits on the recorder for seconds. One
        // started earlier and finishing later must not put back a list from
        // before the other: a recording just saved vanished until the next
        // resume.
        val generation = ++started

        // ALL of this is file I/O and it must not run on the main thread.
        // The screen's scope dispatches on Main, and listing recordings
        // queries MediaStore and opens every file with MediaMetadataRetriever
        // to read its duration. On the UI thread that freezes the app - and
        // because the activity had been brought to the front over the dialer,
        // it froze the dialer's touches with it. An unresponsive phone right
        // after a call is about the worst thing this app could do.
        val reading = withContext(Dispatchers.IO) {
            val (recordings, trashed) = RecordingLibrary.read(app)
            // Only while the tab is up: it is the call log and a contacts
            // lookup per number, and nobody is looking at it otherwise. Null
            // when not read, so this reading leaves alone a list a later one
            // has read already.
            val calls = if (state.value.homeTab == HomeTab.CALLS) CallHistory.load(app, recordings, trashed) else null
            // Before the lists reach the screen, so no row shows a colour and
            // then changes it; and given only in Multitrack, the one look that
            // has them - a look never chosen keeps nothing. Once given, they
            // stay through a change of look, so a caller's colour is theirs.
            val colours = if (Looks.choice(app).look == Look.MULTITRACK) {
                CallerColours.update(
                    app,
                    recordings.map { it.caller to (it.startedAtMillis ?: 0L) } + (calls ?: state.value.calls).map { it.label to it.at },
                )
            } else {
                emptyMap()
            }
            val settings = SETTINGS.map { it(app) }
            Lists(recordings, trashed, calls, colours) to settings
        }
        val (lists, settings) = reading

        if (generation < applied) return
        applied = generation
        state.update { now ->
            asSaved(app, settings.fold(now) { s, put -> put(s) }).copy(
                recordings = lists.recordings,
                trashed = lists.trashed,
                calls = lists.calls ?: now.calls,
                callerColours = lists.colours,
                connected = AdbTransport.lastKnownConnected,
            ).let { next ->
                // A name that is no longer shown cannot stay selected. It would
                // still be counted by the toolbar - the count the user reads
                // before confirming a deletion - and deleted with the rest.
                next.copy(selected = stillShown(next))
            }
        }
        answer.reload()
        playback.listRead(lists.recordings)
        fill.start()
    }

    /** The lists a reading takes in. */
    private class Lists(
        val recordings: List<Recording>,
        val trashed: List<Recording>,
        val calls: List<CallHistory.Call>?,
        val colours: Map<String, Int>,
    )
}
