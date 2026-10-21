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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.jemcik.jemrec.DeepLink
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.RecordingSaver
import com.jemcik.jemrec.ui.theme.JemRecColors
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbars) },
        topBar = {
            // No bar during setup. There is nothing to configure yet and no
            // second screen to reach, so a gear there would be a dead end.
            if (ready) {
                AppHeader(state, vm)
            }
        },
    ) { padding ->
        // Settings keeps its place while a screen opened from it is up -
        // Recently deleted included, which is drawn by a different branch
        // below, so this lives above them all - and Back lands where the tap
        // was. Each of those screens starts at its own top.
        val settingsScroll = rememberScrollState()
        // The home screen is its own scrolling list (a LazyColumn with a pinned
        // header), so it must NOT be nested in a vertical scroll - two vertical
        // scrolls inside each other is both a crash and the wrong behaviour. It
        // takes the padding and fills the area itself. Setup and Settings are
        // short, static content, so they keep the plain page scroll.
        if (ready && !offHome) {
            if (state.homeTab == HomeTab.CALLS) {
                CallsScreen(state, vm, modifier = Modifier.padding(padding))
            } else {
                HomeScreen(state, vm, modifier = Modifier.padding(padding))
            }
        } else if (inTrash) {
            // A list too, with its own scroll, for the same reason.
            TrashScreen(state, vm, modifier = Modifier.padding(padding))
        } else {
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
                        Screen.WHOSE_CALLS -> WhoseCallsScreen(state, vm)
                        Screen.MESSENGER_CALLS -> MessengerCallsScreen(state, vm)
                        Screen.DIAGNOSTICS -> DiagnosticsScreen(state, vm)
                        else -> SettingsScreen(state, vm)
                    }
                }
            }
        }
    }
}

/**
 * The bar over every screen once setup is done: the state as the headline on
 * home (with the on/off switch, the gear and the tabs), the screen's name and
 * Back elsewhere, and the gradient rule under both.
 *
 * Its own composable so the screen tests can draw it without MainScreen, whose
 * resume effects reach the recorder and the device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppHeader(state: UiState, vm: MainViewModel) {
    val haptics = LocalHapticFeedback.current
    val offHome = state.screen != Screen.HOME
    // The bar and the line under it are one thing, so they live in
    // one Column. TopAppBar has no bottom border of its own.
    //
    // ON HOME, THE BAR AND THE TABS ARE ONE TINTED BLOCK. Material
    // 3 joins tabs to their app bar as a single unit, with a divider
    // along the bottom (m3.material.io, Tabs). On the plain surface
    // the tabs floated between the bar and the list - nothing said
    // they belonged to either. The tint is the app bar's scrolled
    // colour, kept on at rest: at rest is exactly when the tabs
    // floated. The gradient rule under them is the divider.
    val headerColor = if (offHome) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    Column {
        TopAppBar(
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = headerColor,
                scrolledContainerColor = headerColor,
            ),
            title = {
                // THE STATE IS THE HEADLINE.
                //
                // The app's name was, and a name is the one thing
                // on this screen the user already knows - it is
                // written under the icon they just tapped. What
                // they cannot know without being told is whether
                // their next call will be recorded, so that gets
                // the row.
                Column {
                    Text(
                        if (offHome) {
                            when (state.screen) {
                                Screen.WHOSE_CALLS -> "Whose calls to keep"
                                Screen.MESSENGER_CALLS -> "Messenger calls"
                                Screen.DIAGNOSTICS -> "Diagnostics"
                                Screen.TRASH -> "Recently deleted"
                                else -> "Settings"
                            }
                        } else {
                            when {
                                !state.enabled -> "Off"
                                state.starting -> "Starting..."
                                state.daemonRunning -> "Ready to record"
                                // The recorder is down. Wi-Fi is the
                                // one lever the user has: the app
                                // turns Wireless debugging back on by
                                // itself when it revives, so that
                                // switch is never theirs to touch
                                // here (it used to be named, and the
                                // phone turns it off on every Wi-Fi
                                // loss - so it was named constantly,
                                // and wrongly). The Wi-Fi goes in the
                                // line under this one: at title size
                                // "Cannot record, needs Wi-Fi" wrapped.
                                else -> "Cannot record"
                            }
                        },
                        // ONE LINE, WHATEVER THE FONT SIZE. The
                        // headline is the state and has to be read
                        // at a glance beside the switch; a wrap
                        // there looks like a broken layout. The
                        // wording fits at the default size, and the
                        // type steps down a little for anyone
                        // running large fonts rather than wrapping
                        // or losing letters to an ellipsis.
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(
                            minFontSize = 15.sp,
                            maxFontSize = 22.sp,
                        ),
                        // A traffic light: green ready, coral cannot,
                        // grey off/starting. Green is the play colour,
                        // so "go" reads the same in the header as it
                        // does on every row - and says "good to go"
                        // where the accent blue only said "accent".
                        color = when {
                            offHome -> MaterialTheme.colorScheme.onSurface
                            !state.enabled || state.starting ->
                                MaterialTheme.colorScheme.onSurfaceVariant
                            state.daemonRunning -> JemRecColors.play
                            else -> MaterialTheme.colorScheme.error
                        },
                    )
                    // Under the ready state, HOW it will record - so
                    // the user is not left to remember whether the
                    // next call records on its own or waits for a
                    // tap. Only when it is actually ready: under
                    // "Off", "Starting" or "Cannot record" it would
                    // either be moot or contradict the line above.
                    if (!offHome && state.enabled && state.daemonRunning) {
                        Text(
                            if (state.automatic) "Recording every call"
                            else "Asks before each call",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            autoSize = SUBTITLE_AUTO_SIZE,
                        )
                    }
                    // Under the down state, what to do about it. The
                    // app watches for Wi-Fi and revives the recorder
                    // the moment it returns - re-arming Wireless
                    // debugging itself - so this is one instruction
                    // and a wait, not a fault to act on.
                    if (!offHome && state.enabled && !state.starting &&
                        !state.daemonRunning
                    ) {
                        // Except on a Wi-Fi Android has not allowed
                        // Wireless debugging on (see RecorderHealth):
                        // the user is on Wi-Fi already, and the one
                        // thing that helps is Android's question,
                        // which the tap puts back on screen.
                        if (state.networkNotAllowed) {
                            Text(
                                "Tap to allow this Wi-Fi",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                autoSize = SUBTITLE_AUTO_SIZE,
                                modifier = Modifier.clickable(enabled = !state.busy) {
                                    haptics.tap()
                                    vm.allowThisWifi()
                                },
                            )
                        } else {
                            Text(
                                // Every fact in one short line: the cause,
                                // that any network will do, and that there
                                // is nothing else to do. The longer version
                                // wrapped under the title.
                                "Join any Wi-Fi - it resumes on its own",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                autoSize = SUBTITLE_AUTO_SIZE,
                            )
                        }
                    }
                }
            },
            navigationIcon = {
                if (offHome) {
                    IconButton(onClick = { haptics.tap(); vm.back() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                }
            },
            actions = {
                if (!offHome) {
                    // Red when on, matching the record capsule in
                    // the launcher icon - so the thing that means
                    // "recording" is one colour everywhere the user
                    // meets it.
                    Switch(
                        checked = state.enabled,
                        onCheckedChange = { haptics.toggle(it); vm.setEnabled(it) },
                        enabled = !state.busy,
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = JemRecColors.iconRecord,
                            checkedBorderColor = JemRecColors.iconRecord,
                        ),
                    )
                    IconButton(onClick = { haptics.tap(); vm.showSettings() }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                }
            },
        )
        // Recordings | Calls. Primary tabs, under the app bar, as
        // Material 3 puts them; not on the other screens, which
        // are reached from here and not part of it. Their own
        // divider is dropped - the header rule below already is
        // one, and the busy line rides on it.
        if (!offHome) {
            PrimaryTabRow(
                selectedTabIndex = state.homeTab.ordinal,
                containerColor = headerColor,
                divider = {},
            ) {
                Tab(
                    selected = state.homeTab == HomeTab.RECORDINGS,
                    onClick = { haptics.tap(); vm.showTab(HomeTab.RECORDINGS) },
                    text = { Text("Recordings") },
                )
                Tab(
                    selected = state.homeTab == HomeTab.CALLS,
                    onClick = { haptics.tap(); vm.showTab(HomeTab.CALLS) },
                    text = { Text("Calls") },
                )
            }
        }
        HeaderRule(active = state.busy)
    }
}

/**
 * The line under the control bar.
 *
 * A gradient in the launcher icon's own two colours, left to right - the blue
 * of the waveform running into the red of the record mark. Separating fixed
 * controls from scrolling content is what any divider would do; what this one
 * additionally does is carry the icon's colours into the app, so the thing on
 * the home screen and the thing you open are recognisably the same object.
 *
 * WHEN BUSY, THE LINE ITSELF MOVES.
 *
 * A separate progress bar used to appear below this rule while the app worked -
 * turning on, checking setup - which put two horizontal lines a few dp apart,
 * one of them meaningful and one of them chrome. Animating the rule instead
 * keeps it to one line: the gradient slides, so the divider that is already
 * there becomes the activity indicator rather than growing a second one beneath
 * it. The colours flow wave-into-record-into-wave (a mirrored, tiled gradient),
 * so the motion reads as the app's own rather than a generic bar.
 */
@Composable
private fun HeaderRule(active: Boolean) {
    val period = with(LocalDensity.current) { 220.dp.toPx() }

    val shift by rememberInfiniteTransition(label = "rule").animateFloat(
        initialValue = 0f,
        targetValue = period,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "rule-shift",
    )

    val brush = if (active) {
        // Three stops, mirror-tiled, translated by an animated offset: the
        // gradient flows along the line without a visible seam. Mirror rather
        // than Repeated so blue never butts straight up against blue.
        Brush.linearGradient(
            colors = listOf(
                JemRecColors.iconWave, JemRecColors.iconRecord, JemRecColors.iconWave,
            ),
            start = Offset(shift, 0f),
            end = Offset(shift + period, 0f),
            tileMode = TileMode.Mirror,
        )
    } else {
        Brush.horizontalGradient(
            listOf(JemRecColors.iconWave, JemRecColors.iconRecord),
        )
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(brush),
    )
}

/** The header's second line: bodySmall, stepping down a little before it would wrap. */
private val SUBTITLE_AUTO_SIZE = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = 12.sp)
