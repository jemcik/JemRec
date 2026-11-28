package com.jemcik.jemrec.ui

import android.content.IntentSender
import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.calls.CallHistory
import com.jemcik.jemrec.calls.CallRules
import com.jemcik.jemrec.calls.VoipApps
import com.jemcik.jemrec.playback.PlayThrough
import com.jemcik.jemrec.recorder.OffMode
import com.jemcik.jemrec.recordings.FileNames
import com.jemcik.jemrec.recordings.RecordingQuality
import com.jemcik.jemrec.recordings.Recording
import com.jemcik.jemrec.setup.Setup
import com.jemcik.jemrec.setup.SetupStep
import com.jemcik.jemrec.system.GlobalSettings

/** Recordings added to the list per "show more" tap. */
internal const val PAGE = 20

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
    /** How setup's last connect went (AdbTransport.lastConnect), for the
     *  Finish connecting card to say which: nothing answers on the phone, or
     *  Wireless debugging turns JemRec away (#140). NONE until one says. */
    val connectOutcome: AdbTransport.ConnectOutcome = AdbTransport.ConnectOutcome.NONE,
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
    /** This phone's own screen for letting JemRec run in the background, by
     *  name, for Settings - null where there is none. Read with the rest, on
     *  IO: until 1.2 Settings looked it up on the main thread as it opened,
     *  a call into the package manager for each candidate screen. */
    val autoStartLabel: String? = null,
    /**
     * Whether JemRec's notifications are off, or the pairing channel muted, so
     * the pairing code would have nowhere to appear. Only read on the pairing
     * step, where it raises a warning and a fix button. Defaults false so it
     * never accuses before it has been looked at.
     */
    val pairingNotificationsBlocked: Boolean = false,
    /** Notifications JemRec needs that are off, to be said on home - or
     *  null (NotificationHealth.notice). */
    val notificationsOff: com.jemcik.jemrec.calls.NotificationHealth.Off? = null,
    /** Settings' row for this phone's background settings to be brought
     *  into view: the "stopped in the background" warning's tap. */
    val revealBackground: Boolean = false,
    /** What Android said is off of JemRec's notifications, as the last
     *  reading found it: what [notificationsOff] is decided from, with the
     *  mode and a dismissal, as the reading lands (NotificationHealth). */
    val notificationSwitches: com.jemcik.jemrec.calls.NotificationHealth.Switches? = null,
    /** JemRec's Battery setting is Restricted, to be said on home
     *  (BatteryHealth.notice). */
    val batteryNotice: Boolean = false,
    /** Whether Android said JemRec's battery use is Restricted, as the last
     *  reading found it - null before any: what [batteryNotice] is decided
     *  from, with the mode and a dismissal, as the reading lands. */
    val batteryRestricted: Boolean? = null,
    /** The Default USB configuration stops the recorder at every lock, to be
     *  said on home (UsbDefault.notice). */
    val usbDefaultNotice: Boolean = false,
    /** The Default USB configuration as the recorder last said it,
     *  UsbManager's bits - null before any: what [usbDefaultNotice] is
     *  decided from, with [secureLock] and a dismissal. */
    val usbDefaultFunctions: Long? = null,
    /** A tap tried to set it to none - asked, or refused as unreadable -
     *  and it reads a data mode still (UsbDefault.didNotStay). */
    val usbDefaultDidNotStay: Boolean = false,
    /** Whether the lock is a PIN, pattern or password, as the last reading
     *  found it - null before any. */
    val secureLock: Boolean? = null,
    /** The folder chosen for recordings can no longer be reached
     *  (FolderAccess): saves go elsewhere, and its recordings do not show. */
    val folderLost: Boolean = false,
    /** An OPPO, OnePlus or realme phone that may refuse setup's grant until
     *  its own switch is on (ShellGrant), so the pairing card names it. */
    val systemOptimizationSwitch: Boolean = false,
    val folder: String = "",
    val usingDefaultFolder: Boolean = true,
    /** True when every call is recorded without asking. */
    val automatic: Boolean = true,
    /** Ask first's button over calls, switched on (CallButton.enabled). */
    val callButton: Boolean = false,
    /** Android lets JemRec display over other apps, as the last reading found
     *  it: the button cannot show without. Null while not known: before the
     *  first reading, and while the recorder is asked to allow it. */
    val overlayAllowed: Boolean? = null,
    /** Whether the user chose to hide Android's debugging notification. */
    val debugNotificationHidden: Boolean = false,
    /** Whether a notification with Play, Share and Delete follows each call. */
    val afterCallNotice: Boolean = true,
    /** Settings' "Play recordings" (PlayThroughSetting). */
    val playThrough: PlayThrough = PlayThrough.LOUDSPEAKER_AND_EAR,

    /** Settings' "Both sides in both ears" (BothEarsSetting). */
    val bothEars: Boolean = true,
    /** The phone has an earpiece, so Settings offers "Play recordings"
     *  (EarWatch.hasEarpiece). */
    val earpieceAvailable: Boolean = false,
    /** ...and a proximity sensor, so it offers the earpiece at the ear
     *  (EarWatch.canRaise). */
    val raiseAvailable: Boolean = false,
    /**
     * Turning the hiding on from Settings could not grant notification
     * access, and why (Setup.grantNotificationAccess). Shown under the
     * switch until the next try.
     */
    val debugNotificationProblem: Setup.GrantProblem? = null,
    /** Android forgets no debugging pairing: its timeout is off, by Keep
     *  JemRec paired or by hand (KeepPaired.kept). */
    val pairingKept: Boolean = false,
    /** How many days Android keeps a pairing while the timeout is on: 7
     *  unless someone set it (KeepPaired.days). */
    val pairingDays: Int = 7,
    /** A Keep JemRec paired choice no recorder has carried out yet: true to
     *  keep, false to put back (KeepPaired.waiting). */
    val keepPairedWaiting: Boolean? = null,
    /** The recorder answered that it could not carry out the choice
     *  waiting, at its last try: said under the switch until a try works or
     *  a new choice is made (KeepPaired.refused). */
    val keepPairedRefused: Boolean = false,
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
    /** The number adbd's port is on, as the lines name it
     *  (StartWithoutWifi.portNumber): JemRec's own or anyone's; null for
     *  none. And JemRec's own number, once one was picked
     *  (StartWithoutWifi.ownPort): the one "would not open" names. */
    val portNumber: Int? = null,
    val ownPort: Int? = null,
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
    /** How many bits a second new recordings get. See RecordingQuality. */
    val quality: RecordingQuality = RecordingQuality.STANDARD,

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
    /** Turning caller names on could not grant notification access, and
     *  why, as debugNotificationProblem. */
    val voipNamesProblem: Setup.GrantProblem? = null,
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
    /** This app's version, as Settings' About shows it and a copied report
     *  gives it (appVersion); empty until read. */
    val appVersion: String = "",
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
    /**
     * The window opened, nothing read yet: from the view model's making
     * until its first reading lands (Reading). Meanwhile home says nothing of
     * the list being empty or matching nothing - it said "No recordings yet"
     * for a moment at every start, and under a selection brought back after
     * the process ended (SavedPlace) - its list's place is not laid out over
     * the empty list, which would have lost it, and the other screens wait,
     * blank, rather than show defaults they have not read (MainScreen,
     * CallsScreen). The view model Android makes starts with it; one a test
     * makes, not.
     */
    val listUnread: Boolean = false,
    /** The call log not read yet in this window: it is read only by a
     *  reading taken while the Calls tab is up (Reading), and until the
     *  first such landed the tab said "No calls in the call log yet."
     *  (review, 2026-11-18). Set as [listUnread] is. */
    val callsUnread: Boolean = false,
    /** A recording a notification's Play or Share asked for: the list
     *  brings its row into view, then lets this go (RecordingsList.onRevealed). */
    val reveal: String? = null,
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
    /** It sounds at the earpiece (AtTheEar), not the speaker. */
    val earpiece: Boolean = false,
    /** The player offers the earpiece: the phone has one, and no headphones
     *  are in (EarWatch). */
    val earpieceOffered: Boolean = false,
    /** The screen covered: a recording plays at the earpiece held to the
     *  ear (EarCover). */
    val earCovered: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    /** The loaded recording's loudness, for its waveform (Waveforms); null
     *  until worked out, or for one that cannot be - the plain scrubber then. */
    val waveform: List<Float>? = null,
    val log: String = "",
)
