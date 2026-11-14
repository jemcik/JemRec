package com.jemcik.jemrec.ui

import android.app.Application
import android.util.Log
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.DebugNotificationCleaner
import com.jemcik.jemrec.recorder.DebugSwitchesOff
import com.jemcik.jemrec.recorder.NetworkRevive
import com.jemcik.jemrec.recorder.OffMode
import com.jemcik.jemrec.recorder.RecorderHealth
import com.jemcik.jemrec.recorder.RecorderLifecycle
import com.jemcik.jemrec.recorder.RecorderSwitch
import com.jemcik.jemrec.recorder.StartWithoutWifi
import com.jemcik.jemrec.recorder.Trigger
import com.jemcik.jemrec.setup.PairingNotification
import com.jemcik.jemrec.setup.Setup
import com.jemcik.jemrec.setup.SetupStep
import com.jemcik.jemrec.system.AutoStart
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * Setup's steps and the recorder's state, from the screen: the setup check
 * at every return to the app, the record control, the tile watched while
 * the screen is up, the revives the screen starts and shows as Starting
 * (StartsUnderWay), Start without Wi-Fi's switch, pairing again, and Start
 * fresh. One part, as setup and the recorder share their starts and call
 * each other: turning JemRec on ends in a setup check, and a setup check
 * can start the recorder.
 */
internal class RecorderAndSetup(
    private val app: Application,
    private val scope: CoroutineScope,
    private val state: MutableStateFlow<UiState>,
    private val log: ActionLog,
    private val actions: Actions,
    private val reading: Reading,
    /** One look of setup's background connect (reconnect) for adbd, true
     *  once connected (MainViewModel's). */
    private val lookForAdb: suspend (Application) -> Boolean,
    /** How long after a flip of Start without Wi-Fi a close still due is
     *  tried again (MainViewModel's). */
    private val portCloseAgainMs: Long,
) {
    /** The switch can be flipped from the Quick Settings tile while this
     *  screen is up, without the screen ever pausing. See RecorderSwitch.observe. */
    private val stopWatchingSwitch = RecorderSwitch.observe(app) { on ->
        val was = state.value.enabled
        state.update { it.copy(enabled = on) }
        // On from elsewhere - the tile - with this screen up: the header's
        // last reading of the recorder is from before it went, and said
        // "Ready" six seconds before the recorder was back (the Honor,
        // 2026-10-26). A flip from this screen is handled by setEnabled.
        if (on && !was) awaitRecorderStartedElsewhere()
    }

    /** What went off with the switch, as it changes - from the tile too. */
    private val stopWatchingDebugSwitches = DebugSwitchesOff.observe(app) { gone ->
        state.update { it.copy(debuggingOff = gone) }
    }

    /** Hide the debugging notification as saved, by a screen closed since
     *  too: its grant outlives the screen that asked (Action.DEBUG_NOTIFICATION),
     *  and one opened meanwhile read it off. */
    private val stopWatchingHiding = DebugNotificationCleaner.watchHiding(app) { hide ->
        state.update { it.copy(debugNotificationHidden = hide) }
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
        scope.launch {
            try {
                withTimeoutOrNull(RECORDER_ELSEWHERE_MS) {
                    while (!CaptureDaemon.isRunning(app)) delay(1_000)
                }
                reading.now()
            } finally {
                awaitingRecorder.set(false)
                starts.end()
            }
        }
    }

    fun askReset() = state.update { it.copy(confirmingReset = true) }

    fun cancelReset() = state.update { it.copy(confirmingReset = false) }

    /**
     * Undo setup and go back to the wizard.
     *
     * Ends on the HOME screen deliberately. After a reset there is no longer a
     * paired phone to have settings about, and leaving the user looking at a
     * settings page for an app that now needs setting up would be a strange
     * place to put them.
     */
    fun confirmReset() {
        state.update { it.copy(confirmingReset = false) }
        actions.run(Action.START_FRESH) {
            Setup.reset(app)
                .onSuccess { state.update { s -> s.copy(resetDone = true) } }
                .onFailure { log.say("could not reset: ${it.message}") }
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
                if (!CaptureDaemon.askToAllowNetwork(app)) {
                    log.say("could not switch Wireless debugging - open it in Developer options and allow this network")
                }
                reading.now()
            } catch (e: CancellationException) {
                throw e // the screen gone: not a failure to report
            } catch (t: Throwable) {
                Log.w("JemRec", "allow this Wi-Fi failed", t)
                log.say("allow this Wi-Fi failed: ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                askingForWifi.set(false)
                starts.end()
            }
        }
    }

    private val askingForWifi = AtomicBoolean(false)

    /** "They are on already", from the Developer options card on a phone that
     *  hides them. See Setup.developerOptions. */
    fun confirmDeveloperOptions() = actions.run(Action.DEVELOPER_OPTIONS_ON) {
        Setup.confirmDeveloperOptions(app)
        recheckSetup()
    }

    /**
     * The background-start setup card's "Not now": mark the question asked and
     * move on. JemRec cannot read the vendor setting, so the only thing ever
     * recorded is that it was offered (see [openBackgroundSettings] for the
     * other button).
     */
    fun confirmBackgroundChoice() = actions.run(Action.BACKGROUND_NOT_NOW) {
        Setup.markBackgroundChoice(app)
        recheckSetup()
    }

    /**
     * The card's other button: open the vendor screen, and count that as the
     * answer - JemRec cannot read the setting, so going there is all it can
     * know. Setup moves on when the user comes back, because the step is
     * worked out again every time the app returns to the foreground.
     */
    fun openBackgroundSettings() {
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
        if (RecorderLifecycle.goes(app, Trigger.SETUP_CHECK)) {
            RecorderLifecycle.arm(app, Trigger.SETUP_CHECK)
        }
        // Keep the daemon's recording mode in step with the app's settings, even
        // when the daemon was already up (so revive did not run). Cheap, and it
        // is how the daemon learns automatic vs ask-first after a plain reopen.
        CaptureDaemon.pushMode(app)
        val step = Setup.currentStep(app)
        val background = if (step == SetupStep.NEEDS_BACKGROUND_CHOICE) {
            withContext(Dispatchers.IO) { AutoStart.screen(app) }
        } else {
            null
        }
        state.update {
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
            PairingNotification.show(app)
        } else {
            PairingNotification.dismiss(app)
        }

        if (step == SetupStep.FINISHING) finishSetupInternal()
        reading.now()

        // THE SCREEN MUST NOT KEEP A READING IT ALREADY KNOWS IS BEING FIXED.
        //
        // currentStep() now answers READY for a set-up phone whose daemon is
        // merely down, which is right - but reading.now() then reports the
        // recorder stopped, and the header says in red that the phone cannot
        // record and needs Wi-Fi while the keep-alive is quietly starting it a
        // couple of seconds later. Nothing asked again, so that stale red
        // sentence was what the user saw until they reopened the app: the
        // "sometimes it says the recorder is not running" half of the app
        // starting inconsistently.
        //
        // So bring it up here and say so while it happens.
        if (step == SetupStep.READY && state.value.enabled && !state.value.daemonRunning) {
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
        val s = state.value
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
    fun watchPairing() {
        if (pairingWatch != null) return
        pairingWatch = scope.launch {
            state.map(::pairingLostOnHome).distinctUntilChanged().collect { lost -> if (lost) pairAgain() }
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
        Setup.forgetPairing(app)
        RecorderHealth.pairingAgain(app)
        // Refused no longer, until a revive says so again: kept, setup back on
        // READY by itself - the pairing fine after all - was sent straight
        // back here.
        state.update { it.copy(screen = Screen.HOME, pairingLost = true, pairingRefused = false) }
        scope.launch {
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
        scope.launch {
            try {
                CaptureDaemon.revive(app, request = request)
                reading.now()
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
    fun showPairingNotification() = PairingNotification.show(app)

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
        PairingNotification.show(app)
        log.say("check your notifications - you can type the code there")
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
        state.update { it.copy(step = SetupStep.FINISHING, finishFailed = null) }
        Setup.finish(app)
            .onSuccess { log.say("recorder started") }
            .onFailure {
                log.say("could not finish setup: ${it.message}")
                // The step is FINISHING again, and its spinner says there is
                // nothing left to do: with a Try again it is not a dead end.
                state.update { s -> s.copy(finishFailed = it.message ?: "something went wrong") }
            }
        state.update { it.copy(step = Setup.currentStep(app)) }
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
        if (CaptureDaemon.revive(app, request = CaptureDaemon.Request.USER)) {
            log.say("recorder running on 127.0.0.1:${CaptureDaemon.PORT}")
        } else {
            log.say("could not start the recorder: ${notStarted(RecorderHealth.cause(app))}")
        }
        reading.refresh()
    }

    /**
     * The record control. Turning off asks first in the one case it costs
     * more than it says: all debugging off, and no Wi-Fi to come back on
     * over (OffMode.asksFirst). Everything else goes straight to [setEnabled].
     */
    fun toggleRecording(on: Boolean) {
        // The port's reading the screen shows ("Back on: anywhere"), so the
        // question and the screen agree - and no getprop on the main thread.
        if (!on && DebugSwitchesOff.asksBeforeOff(app) { state.value.portSet }) {
            state.update { it.copy(askingToTurnOff = true) }
            return
        }
        setEnabled(on)
    }

    /** The question's answer: [turnOff], or leave JemRec on. */
    fun answerTurnOff(turnOff: Boolean) {
        state.update { it.copy(askingToTurnOff = false) }
        if (turnOff) setEnabled(false)
    }

    /** When JemRec is off: applies from the next off, and On puts back what
     *  the last off really switched off whatever this says by then. */
    fun setOffMode(mode: OffMode) {
        DebugSwitchesOff.setMode(app, mode)
        state.update { it.copy(offMode = mode) }
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
        StartWithoutWifi.setEnabled(app, on)
        state.update { it.copy(startWithoutWifi = on) }
        scope.launch {
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
            CaptureDaemon.revive(app, onPortChange = { if (began.compareAndSet(false, true)) starts.begin() })
        } catch (e: CancellationException) {
            throw e // the screen gone: not a failure to report
        } catch (t: Throwable) {
            Log.w("JemRec", "the port change's revive failed", t)
        } finally {
            if (began.get()) starts.end()
        }
        reading.now()
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
        state.update { it.copy(enabled = on) }
        // Saved at once, outside the action below, which waits its turn.
        RecorderSwitch.set(app, on)
        // The recorder follows in the process's own scope, as for the Quick
        // Settings tile (RecorderSwitch.turn), not in this screen's: closing
        // JemRec from Recents ends this screen's scope with the process
        // alive - Back does not, since Android 12 - and an Off waiting there
        // for the screen to be free was lost with it, debugging never
        // switched off.
        // Turns queue there, each dropped if the switch has moved on - a
        // quick on and off ends off.
        val turned = RecorderSwitch.turnLater(app, on)
        // What the screen does after it waits its turn rather than being
        // dropped: the record control takes a tap while the app is busy -
        // each opening's check holds busy for seconds - and the tap was
        // dropped without a sign, the control looking as ever.
        actions.run(if (on) Action.TURN_ON else Action.TURN_OFF) {
            try {
                turned.join()
                if (RecorderSwitch.isOn(app) != on) return@run
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
     * Hide Android's debugging notification, or stop hiding it: the answer to
     * setup's question, and the Settings switch after it.
     *
     * During setup the answer is only recorded - finish() grants notification
     * access over the session it opens anyway. After setup a yes needs that
     * access, which only the shell can grant (Setup.grantNotificationAccess);
     * if it cannot be had, the switch stays off and says why rather than
     * claiming a yes it cannot act on.
     */
    fun setHideDebugNotification(hide: Boolean) = actions.run(Action.DEBUG_NOTIFICATION) {
        if (hide && Setup.isComplete(app) && !DebugNotificationCleaner.accessGranted(app)) {
            val problem = Setup.grantNotificationAccess(app)
            if (problem != null) {
                state.update { it.copy(debugNotificationProblem = problem) }
                return@run
            }
        }
        DebugNotificationCleaner.choose(app, hide)
        state.update { it.copy(debugNotificationHidden = hide, debugNotificationProblem = null) }
        // From the setup step, move on to the next one.
        if (state.value.step != SetupStep.READY) recheckSetup()
    }

    /** Guards against two bring-ups racing when the screen is reopened. */
    private val reviving = AtomicBoolean(false)

    /** Every start the screen shows as Starting (StartsUnderWay). */
    private val starts = StartsUnderWay(state)

    /** The screen is gone: the switches are no longer watched. */
    fun close() {
        stopWatchingSwitch()
        stopWatchingDebugSwitches()
        stopWatchingHiding()
    }
}
