package com.jemcik.jemrec.ui

import android.app.Application
import com.jemcik.jemrec.Prefs
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
import com.jemcik.jemrec.recorder.DebugSwitchesOff
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
 * Everything a reading takes in besides the lists, in the order it is read:
 * each setting once, its read beside the field it fills. A new one is one
 * line here.
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
    read({ RecordingMode.of(it) == RecordingMode.AUTOMATIC }) { copy(automatic = it) },
    read(DebugNotificationCleaner::hiding) { copy(debugNotificationHidden = it) },
    read(AfterCall::enabled) { copy(afterCallNotice = it) },
    read(RecorderSwitch::isOn) { copy(enabled = it) },
    read(DebugSwitchesOff::mode) { copy(offMode = it) },
    read(DebugSwitchesOff::recorderGone) { copy(debuggingOff = it) },
    read(StartWithoutWifi::enabled) { copy(startWithoutWifi = it) },
    read(StartWithoutWifi::readyForUser) { copy(portSet = it) },
    read(StartWithoutWifi::openedThisBoot) { copy(portOurs = it) },
    read(StartWithoutWifi::failedThisBoot) { copy(portFailed = it) },
    read(StartWithoutWifi::closeFailedThisBoot) { copy(portCloseFailed = it) },
    read(CallLogLookup::granted) { copy(canReadCallLog = it) },
    read(AudioAccess::granted) { copy(canReadAudio = it) },
    read({ CallLogLookup.anythingLeftToOffer(it) && !CallLogLookup.bannerDismissed(it) }) { copy(offerCallLog = it) },
    read(Retention::maxAgeDays) { copy(autoDeleteAfterDays = it) },
    read(Retention::minSeconds) { copy(autoDeleteShorterThan = it) },
    // Whose calls: the choice, the contacts chosen - a contacts query or two
    // each, so only for the choices that list them - and whether contacts
    // may be read at all, which telling who is who needs. Each read once.
    read({ app ->
        val contacts = CallRules.contacts(app)
        val canReadContacts = CallLogLookup.photosGranted(app)
        val listed = canReadContacts && (contacts == CallRules.Contacts.ALL_BUT || contacts == CallRules.Contacts.ONLY)
        Triple(contacts, canReadContacts, if (listed) CallRules.chosen(app) else emptyList())
    }) { (contacts, canReadContacts, chosen) ->
        copy(ruleContacts = contacts, canReadContacts = canReadContacts, ruleChosen = chosen)
    },
    read(CallRules::otherNumbers) { copy(ruleOtherNumbers = it) },
    read(CallTracking::enabled) { copy(callTracking = it) },
    read(VoipApps::installed) { copy(voipInstalled = it) },
    read(VoipApps::supported) { copy(voipSupported = it) },
    read(VoipApps::enabled) { copy(voipEnabled = it) },
    read(VoipCallNotes::enabled) { copy(voipNames = it) },
    read(FileNames::format) { copy(fileNames = it) },
    read(FileNames::honorDialer) { copy(honorDialer = it) },
    read(FileNames::inHonorFolder) { copy(inHonorFolder = it) },
    read({ Prefs.of(it).getBoolean("select_hint_seen", false) }) { copy(selectHintSeen = it) },
    read(RecorderTile::isAdded) { copy(tileAdded = it) },
)

/**
 * The screen's reading of everything it shows from outside itself: the
 * recordings and the trash, the call log while its tab is up, Multitrack's
 * caller colours, the recorder's state and every setting (SETTINGS) - read
 * together, off the main thread, and put into the state at once.
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
            settings.fold(now) { s, put -> put(s) }.copy(
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
