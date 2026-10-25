package com.jemcik.jemrec.ui

import android.app.Activity
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import com.jemcik.jemrec.ui.theme.LocalLook
import com.jemcik.jemrec.ui.theme.Look
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jemcik.jemrec.DeepLink
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.RecordingSaver
import kotlinx.coroutines.delay
import kotlin.system.exitProcess
import kotlinx.coroutines.flow.conflate

/**
 * Routes between setup and the running app.
 *
 * There is no "settings" concept here on purpose. Either the phone can record a
 * call or it cannot, and whichever is true is what the screen shows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()

    // Re-checked every time the screen comes forward, not once when it is
    // created. Everything shown here lives outside this process - the daemon,
    // the pairing, the recordings on disk - and the interesting moment is
    // exactly the one where the user returns from somewhere else: from Settings
    // after pairing, or from a call that just produced a recording.
    //
    // Doing this only on first composition is why a finished call showed
    // nothing until "Check" was pressed. Making the user ask is not a refresh
    // strategy.
    LifecycleResumeEffect(Unit) {
        vm.refreshSetup()
        onPauseOrDispose { }
    }

    // Out of sight, the scrubber is not driven and the fill-in stops; a
    // recording plays on, under the system's media controls. Back in sight,
    // the screen shows what played on meanwhile. See MainViewModel.screenHidden
    // and fillInWaveforms.
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
        vm.screenHidden()
        vm.stopFillingIn()
    }
    LifecycleEventEffect(Lifecycle.Event.ON_START) { vm.screenShown() }

    // A recording saved while this screen is up - by the per-call service
    // seconds after a hang-up, or by the keep-alive's reconcile right after an
    // open - would otherwise wait for the next resume to appear. Measured: the
    // file was in the folder and not in the list until the app was reopened.
    // Conflated behind a short pause, so a reconcile saving several files is
    // one refresh.
    LaunchedEffect(Unit) {
        RecordingSaver.saved.conflate().collect {
            delay(250)
            vm.refresh()
        }
    }

    // A notification's button - play this call, share it, run the self-test,
    // pair again. The activity hands the link over; this acts on it once.
    val link by DeepLink.pending.collectAsStateWithLifecycle()
    LaunchedEffect(link) { link?.let(vm::open) }

    // The recorder coming back is the other change that happens out of sight:
    // the keep-alive revives it when Wi-Fi returns, and the header kept saying
    // "needs Wi-Fi" until the next resume. Measured, after a reboot.
    LaunchedEffect(Unit) {
        CaptureDaemon.changed.collect { vm.refresh() }
    }

    // And Wi-Fi arriving while this screen is up starts the revive from here,
    // so the header goes to "Starting..." at once rather than waiting for the
    // keep-alive job to be scheduled, run, and report back. The ViewModel
    // decides whether there is anything to revive.
    val appContext = LocalContext.current.applicationContext
    DisposableEffect(Unit) {
        val cm = appContext.getSystemService(ConnectivityManager::class.java)
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = vm.onWifiAvailable()
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching { cm?.registerNetworkCallback(request, callback) }
        onDispose { runCatching { cm?.unregisterNetworkCallback(callback) } }
    }

    // MediaStore will occasionally not take our word for it that a recording
    // is ours to delete, and hands back a request for the user to approve.
    // Launching it needs an Activity, so it lives here, above both screens -
    // deleting one recording happens on the home screen and deleting all of
    // them happens in settings, and they should not each own a copy.
    val consentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result -> vm.onDeleteConsentResult(result.resultCode == Activity.RESULT_OK) }

    LaunchedEffect(state.deleteConsent) {
        state.deleteConsent?.let {
            consentLauncher.launch(IntentSenderRequest.Builder(it).build())
            vm.onDeleteConsentShown()
        }
    }

    // Reset revokes this app's own permissions, and the system only applies
    // that once the app is out of the way. Closing is part of the operation,
    // not a courtesy.
    val activity = LocalActivity.current
    LaunchedEffect(state.resetDone) {
        if (!state.resetDone) return@LaunchedEffect
        activity?.finishAndRemoveTask()
        // Handing back a permission only takes effect when the process dies,
        // and Android decides when that is - for a cached process it can be a
        // long while. Ending it here is what makes "start fresh" mean now
        // rather than eventually, and the dialog promised the app would close.
        delay(300)
        exitProcess(0)
    }

    AppScaffold(state, vm)
}

/**
 * The app as it is drawn for a given state: the header, Back, and whichever
 * screen is showing - with none of MainScreen's reaching out to the device
 * (resume checks, Wi-Fi, deep links), so the screen tests can draw exactly
 * this from a UiState of their own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppScaffold(state: UiState, vm: MainViewModel) {
    val ready = state.step == SetupStep.READY
    val offHome = ready && state.screen != Screen.HOME
    val inTrash = ready && state.screen == Screen.TRASH
    val inDiagnostics = ready && state.screen == Screen.DIAGNOSTICS
    val selecting = ready && !offHome && state.selecting

    // Back unwinds one layer at a time, innermost first: out of selection, then
    // out of a screen reached from Settings, then out of Settings, then out of
    // the app. Selection has to come first - Back is how Android users leave a
    // selection, and dropping them to the home screen instead would lose the
    // picking they had just done.
    BackHandler(enabled = selecting || offHome) {
        if (selecting) vm.clearSelection() else vm.back()
    }

    val snackbars = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.notices.collect { snackbars.showSnackbar(it) }
    }
    // The window's own size, not the screen's: in split screen the two differ.
    val window = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.let { DpSize(it.width.toDp(), it.height.toDp()) } }
    val aside = ready && !offHome && headerAside(window.width.value.toInt(), window.height.value.toInt())

    CompositionLocalProvider(LocalCallerColours provides state.callerColours) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbars) },
            topBar = {
                // No bar during setup. There is nothing to configure yet and no
                // second screen to reach, so a gear there would be a dead end.
                // Nor on top on a phone on its side: home's header is beside the
                // list then.
                if (ready && !aside) {
                    AppHeader(state, vm)
                }
            },
        ) { padding ->
            // Share's sheet, over whichever tab asked for it.
            if (ready) ShareSheets(state, vm)
            // Settings keeps its place while a screen opened from it is up -
            // Recently deleted included, which is drawn by a different branch
            // below, so this lives above them all - and Back lands where the tap
            // was. Each of those screens starts at its own top.
            val settingsScroll = rememberScrollState()
            // Above the screens below, so it runs whichever is showing: in the
            // branch with Settings, a result that came in while home or Recently
            // deleted was up waited, and jumped Diagnostics to the bottom when it
            // was next opened.
            val subScroll = key(state.screen) { rememberScrollState() }
            val scrollState = if (state.screen == Screen.SETTINGS || !ready) settingsScroll else subScroll
            // A self-test's result lands in the log at the very bottom of
            // Diagnostics - below the fold on a long log, so the user pressed
            // the button and saw nothing move. Run the scroll to the end, so
            // the answer is on screen instead of under a thumb.
            LaunchedEffect(state.selfTestShown) {
                // A self-test's result only: any other log line - a switch
                // flipped with the recorder down, a folder chosen - jumped the
                // page to the bottom, away from what the user was doing. And
                // only one that lands on Diagnostics: a result that arrives
                // after the user left is marked seen, not saved up to jump the
                // page the next time Diagnostics opens.
                if (state.selfTestShown > 0L && !inDiagnostics) vm.selfTestSeen()
                if (inDiagnostics && state.selfTestShown > 0L) {
                    // The log is added/grown in this same frame and only
                    // measured after it, so the scroll range does not yet include
                    // it - reading maxValue now gives the OLD bottom, and the
                    // scroll goes nowhere. Wait two frames for layout to settle,
                    // then run to the real end.
                    withFrameNanos {}
                    withFrameNanos {}
                    scrollState.animateScrollTo(scrollState.maxValue)
                    vm.selfTestSeen()
                }
            }
            // The home screen is its own scrolling list (a LazyColumn with a pinned
            // header), so it must NOT be nested in a vertical scroll - two vertical
            // scrolls inside each other is both a crash and the wrong behaviour. It
            // takes the padding and fills the area itself. Setup and Settings are
            // short, static content, so they keep the plain page scroll.
            if (ready && !offHome) {
                val list = @Composable { modifier: Modifier, narrowingAside: Boolean ->
                    if (state.homeTab == HomeTab.CALLS) {
                        CallsScreen(state, vm, modifier = modifier, narrowingAside = narrowingAside)
                    } else {
                        HomeScreen(state, vm, modifier = modifier, narrowingAside = narrowingAside)
                    }
                }
                if (aside) {
                    // A phone on its side: the header in a column of its own at
                    // the left, at its portrait width where there is room (it is
                    // laid out for 360 dp: at 320, Multitrack's LEDs wrapped onto
                    // a second line), and the list beside
                    // it at the full height. On top, the header and the chips
                    // took half of a landscape screen and left about one row in
                    // view (800 x 360 dp, every look, 2026-10-23). Android's
                    // window size classes: compact height is "99.78% of phones in
                    // landscape", where its example shows the top app bar only
                    // from medium height up - the width is what there is room in.
                    //
                    // The header is the side panel, its ground the full height
                    // (option B of the landscape sketches, the user's pick): the
                    // state at the top, the list's chips and search in the middle -
                    // named, as the row has no room to - and the tabs at the foot,
                    // under the left thumb. Under a header on top, the column was
                    // two-thirds empty on the Honor. The bottom is kept clear too:
                    // the tabs sit over the gesture bar.
                    val direction = LocalLayoutDirection.current
                    Row(Modifier.fillMaxSize()) {
                        CompositionLocalProvider(
                            LocalBarSides provides WindowInsetsSides.Top + WindowInsetsSides.Start + WindowInsetsSides.Bottom,
                        ) {
                            Box(
                                Modifier
                                    .width((window.width * 0.45f).coerceIn(300.dp, 360.dp))
                                    .fillMaxHeight(),
                            ) { AppHeader(state, vm, panel = { NarrowingPanel(state, vm) }) }
                        }
                        list(
                            Modifier
                                .weight(1f)
                                .padding(
                                    top = padding.calculateTopPadding(),
                                    bottom = padding.calculateBottomPadding(),
                                    end = padding.calculateEndPadding(direction),
                                ),
                            true,
                        )
                    }
                } else {
                    list(Modifier.padding(padding), false)
                }
            } else if (inTrash) {
                // A list too, with its own scroll, for the same reason.
                TrashScreen(state, vm, modifier = Modifier.padding(padding))
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        // verticalScroll BEFORE the 16dp, so the scroll viewport
                        // reaches up to the header rule and content clips exactly
                        // there as it scrolls under it.
                        .verticalScroll(scrollState)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    // Only during setup, where there is no header rule to animate.
                    if (state.busy && !ready) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    if (!ready) {
                        SetupScreen(state, vm)
                    } else {
                        when (state.screen) {
                            Screen.THEME -> ThemeScreen(vm)
                            Screen.WHOSE_CALLS -> WhoseCallsScreen(state, vm)
                            Screen.MESSENGER_CALLS -> MessengerCallsScreen(state, vm)
                            Screen.DIAGNOSTICS -> DiagnosticsScreen(state, vm)
                            Screen.ANSWERING_MACHINE -> AnsweringMachineScreen(state, vm)
                            else -> SettingsScreen(state, vm)
                        }
                    }
                }
            }
        }
    }
}

/**
 * Whether home's header goes beside the list rather than over it: a window
 * of compact height - under 480 dp, Android's line - that is wider than it
 * is tall, as a phone on its side is.
 */
internal fun headerAside(widthDp: Int, heightDp: Int): Boolean = heightDp < 480 && widthDp > heightDp

/**
 * The bar over every screen once setup is done: on home, the look's own
 * header (DeckHeader, MemoHeader, MultitrackHeader); elsewhere the look's
 * own bar, with the screen's name and Back (SettingsBar).
 *
 * WHAT THE OLD HOME HEADER DID, AND WHERE EACH NOW IS. It was one TopAppBar
 * for every look; each look's header now does all of it in its own way:
 * - the state as the headline (Off, Starting..., Ready to record, Cannot
 *   record), one line at any text size, green, coral or grey: each header's
 *   word, from recorderStatus, auto-sized;
 * - "Recording every call" / "Asks before each call" under it: the AUTO or
 *   ASK mark (Deck, Multitrack) and Memo's first mark, its dot and words;
 * - what brings the recorder back, when it is down: "Join any Wi-Fi",
 *   or a tap - to Android's question for this Wi-Fi, to pairing again, to
 *   starting it again - each look's words for it (Instruction), and the
 *   same tap, haptic and label (instructionTap);
 * - the on/off switch: each record control (recordToggle). It was disabled
 *   while busy, which showed; the controls looked the same and dropped the
 *   tap, so now a tap while busy applies at once and the recorder follows
 *   when the app is free (setEnabled);
 * - the gear, and Recordings | Calls: on each header;
 * - the gradient rule, moving while busy: each look's own busy signal in
 *   its status instead (Deck's LCD segments, Memo's ring, Multitrack's
 *   LEDs) - and only while recording is switching on, not on the check each
 *   opening of the app makes, which moved the line for nothing the user did.
 *   At rest each header ends on its own edge.
 *
 * Its own composable so the screen tests can draw it without MainScreen, whose
 * resume effects reach the recorder and the device.
 */
@Composable
internal fun AppHeader(state: UiState, vm: MainViewModel, panel: (@Composable () -> Unit)? = null) {
    val haptics = LocalHapticFeedback.current
    if (state.screen == Screen.HOME) {
        // The answering machine has its own state (AnswerController); its
        // mark is the one fact here that UiState does not carry.
        val answer by vm.answer.state.collectAsStateWithLifecycle()
        HomeHeader(state, vm, answer.enabled, panel)
        return
    }
    SettingsBar(
        title = when (state.screen) {
            Screen.WHOSE_CALLS -> "Whose calls to keep"
            Screen.MESSENGER_CALLS -> "Messenger calls"
            Screen.DIAGNOSTICS -> "Diagnostics"
            Screen.ANSWERING_MACHINE -> "Answering machine"
            Screen.TRASH -> "Recently deleted"
            Screen.THEME -> "Theme"
            else -> "Settings"
        },
        busy = state.busy,
        onBack = { haptics.tap(); vm.back() },
    )
}

/** The home screen's header, in the look's own way: on home, and in the Theme
 *  screen's preview. With a [panel], the side panel of a phone on its side. */
@Composable
internal fun HomeHeader(state: UiState, vm: MainViewModel, answering: Boolean, panel: (@Composable () -> Unit)? = null) {
    when (LocalLook.current) {
        Look.MEMO -> MemoHeader(state, vm, answering, panel)
        Look.DECK -> DeckHeader(state, vm, answering, panel)
        Look.MULTITRACK -> MultitrackHeader(state, vm, answering, panel)
    }
}


/**
 * Text that must stay on one line and shrinks to fit, from [max] (which grows
 * with the phone's font size, as text should) down to [min] in dp (which does
 * not: an sp floor doubles at twice the size, and a line that cannot shrink
 * past it overflows - over the header's switch, out of a connected button).
 */
@Composable
@ReadOnlyComposable
internal fun oneLineAutoSize(min: Dp, max: TextUnit): TextAutoSize =
    with(LocalDensity.current) { TextAutoSize.StepBased(minFontSize = min.toSp(), maxFontSize = max) }
