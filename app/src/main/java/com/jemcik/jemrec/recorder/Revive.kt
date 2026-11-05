package com.jemcik.jemrec.recorder

import android.util.Log
import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.system.AdbdState
import com.jemcik.jemrec.system.GlobalSettings
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Bringing the recorder back, under the recorder's lock (RecorderLifecycle
 * takes it): the shield, the probe, the gates, Wireless debugging switched
 * on or off and on, the connect, Start without Wi-Fi's port, the start, and
 * the stand-down. What it does to Wireless debugging is decided by pure
 * functions (wirelessDebuggingStep, cycleAfterNotFound).
 */
object Revive {

    private const val TAG = "JemRec"

    /** adbd needs a moment to come up and advertise a port after re-arming. */
    internal const val ADBD_SETTLE_MS = 4_000L

    /** How long a revive looks for adbd before giving up on this attempt. */
    internal const val CONNECT_MS = 5_000L

    /** How long a revive gives an adbd Android is starting by itself - for a
     *  switch written just before, armShield's or On's - before taking it
     *  for stopped. On the Honor, 2026-10-28, adbd's socket answered 0.55 s
     *  and 1.0 s after the switch-on that started it. */
    private const val ADBD_START_MS = 2_000L

    /** How long Android's Wi-Fi question is given for an answer: a revive
     *  for the port alone waits this long for Allow before taking it for a
     *  refusal, and Allow this Wi-Fi's revive looks this long for adbd's
     *  advertisement. Long enough to read the question and tap, by
     *  judgement, not measured. */
    private const val ALLOW_WAIT_MS = 20_000L

    /** A revive's own connect outcome (see revive). */
    internal class Found {
        var connect = AdbTransport.ConnectOutcome.NONE
    }

    internal suspend fun reviveLocked(
        w: ReviveWorld,
        onPortChange: (() -> Unit)?,
        request: Request,
        found: Found,
    ): Boolean {
        // Keep the off-Wi-Fi shield up whenever this phone is set up - even when
        // the daemon is already running. Honor clears adb_enabled when adbd
        // restarts (which is what a Wi-Fi return does), and a manual respawn
        // never sets it, so the shield can be down under a live daemon. The
        // health-check return just below would then skip re-arming it, and the
        // keep-alive that calls this every so often would never restore it.
        // Cheap: a read, and a write only when it has drifted.
        if (w.setUp()) armShield(w)

        val running = w.recorder()
        w.note("revive: recorder ${running?.let { "answers, build ${it.build}" } ?: "does not answer"}")
        val onWifi = w.onWifi()
        // Start without Wi-Fi's port, to open or to close, where that can be
        // done now (portChangeDue).
        val portChange = PortChange.portChangeDue(w, onWifi)
        val current = running != null && w.current(running)
        if (current && portChange == null) return true
        if (running != null) {
            // Alive, but running the jar an earlier APK staged. Carry on down
            // the same path a dead daemon takes; ensureRunning() does the
            // replacing once there is a session to do it over.
            Log.i(
                TAG,
                "daemon: running build ${running.build}, this app carries ${w.bundledBuild()}; " +
                    "replacing when a session can be had",
            )
        }

        // NOTHING TO REVIVE UNTIL SETUP HAS ACTUALLY FINISHED.
        //
        // This asked whether a keypair existed, which reads like "have we
        // paired" and is not. AdbTransport builds one with getOrCreate() the
        // first time it ATTEMPTS a connection, so the file is there on a phone
        // that has never paired with anything - and the guard failed open,
        // doing exactly what the comment it replaces was afraid of: re-arming
        // Wireless debugging behind the user's back, silently undoing half of
        // Start fresh. Caught by a wizard that kept skipping to the pairing
        // step on a wiped phone, because something had turned Wireless
        // debugging back on between one screen and the next.
        if (!w.setUp()) {
            Log.i(TAG, "daemon: setup has not finished, nothing to revive")
            return false
        }

        // Nothing below can work off Wi-Fi - see NetworkRevive.onWifi - unless
        // adbd listens on its classic port (StartWithoutWifi), which it does
        // with no network at all. Otherwise the Wi-Fi watcher brings us back
        // here the moment there is some.
        val overPort = !onWifi && w.portReady()
        if (!onWifi && !overPort) {
            Log.i(TAG, "daemon: not on Wi-Fi, nothing to revive over yet")
            w.note("revive: not on Wi-Fi")
            return running != null
        }

        // Off, Wireless debugging is switched on (TURN_ON): without it adbd is
        // not listening even on Wi-Fi. Needs WRITE_SECURE_SETTINGS, granted
        // once at setup. Not where Android refused it on this Wi-Fi: on
        // Android 12 the switch stays off after Cancel, and each revive -
        // every return to JemRec with the recorder down - asked again
        // (NetworkRevive.noteWirelessRefused).
        //
        // On with adbd not running, only switching it off and on has Android
        // start adbd (CYCLE): after a USB cable stopped it (CableWatch), and
        // after a restart of the Honor (AdbdState). Measured on the Honor: adbd
        // stopped at 18:44:21 and was back at 18:47:13, the moment it was
        // switched off and on; and on 2026-10-28, rebooted at 22:53:35, every
        // revive read adbd stopped with both switches on, and found nothing
        // until a tap on the header switched it off and on at 22:56:28 - adbd
        // was up two seconds later. Not done where adbd runs and nothing is
        // advertised: that is a Wi-Fi Android has not allowed, and switching
        // would put its question on screen out of nowhere (RecorderHealth) -
        // the header's Allow this Wi-Fi asks.
        val wirelessOn = wirelessDebuggingOn(w)
        val cableStopped = w.cableStopped()
        val switchedOnHere = w.switchedOnHere()
        // Stopped, with the switch on, may be adbd a switch written a moment
        // ago is starting: armShield's above, or debugging put back on by On
        // (DebugSwitchesOff.switchBackOn) - a stopped that ends by itself.
        // Waited out only where stopped would have it switched off and on.
        val waitForStart = wirelessOn && onWifi && !cableStopped && request != Request.ALLOW_WIFI &&
            mayCycleAgain(switchedOnHere, request)
        val adbd = w.adbd().let { if (waitForStart) AdbdState.settled(it, ADBD_START_MS, w::adbd) else it }
        w.note("revive: adbd $adbd")
        val step = wirelessDebuggingStep(
            cableStoppedAdbd = cableStopped,
            on = wirelessOn,
            onWifi = onWifi,
            refused = w.stillRefusedHere(wirelessOn),
            adbdStopped = adbd == AdbdState.State.STOPPED,
            switchedOnHere = switchedOnHere,
            request = request,
        )
        w.note("revive: Wireless debugging $step")
        when (step) {
            WirelessStep.CYCLE -> {
                // Asked again, by the one who will answer.
                if (request == Request.ALLOW_WIFI) w.forgetRefused()
                switchOffAndOn(w, onWifi, answerAwaited = request == Request.ALLOW_WIFI)
            }
            WirelessStep.TURN_ON -> if (w.write(GlobalSettings.ADB_WIFI_ENABLED, true)) {
                Log.i(TAG, "daemon: re-armed Wireless debugging")
                w.noteSwitchedOn()
                delay(ADBD_SETTLE_MS)
                // Off again: Android refused it on this Wi-Fi and is asking
                // (NetworkRevive.noteWirelessRefused). With the recorder up,
                // only the port is at stake: a moment for Allow - the one who
                // has just flipped Start without Wi-Fi is there to answer -
                // then no more asking on this Wi-Fi (portChangeDue).
                if (!wirelessDebuggingOn(w) &&
                    !(current && withTimeoutOrNull(ALLOW_WAIT_MS) { while (!wirelessDebuggingOn(w)) delay(500) } != null)
                ) {
                    w.noteRefused()
                    w.note("revive: Android refused Wireless debugging on this Wi-Fi")
                    if (current) return true
                }
            }
            WirelessStep.LEAVE -> Unit
        }

        if (!w.connected) {
            w.note(if (overPort) "revive: connecting on port ${StartWithoutWifi.PORT}, no Wi-Fi" else "revive: connecting")
            // Asked to allow this Wi-Fi: Android's question is on screen, and
            // the connect waits for the answer.
            val lookMs = if (request == Request.ALLOW_WIFI) ALLOW_WAIT_MS else CONNECT_MS
            var connected = if (overPort) PortChange.connectOnPort(w) else w.autoConnect(lookMs)
            found.connect = w.lastConnect
            if (connected.isFailure && found.connect == AdbTransport.ConnectOutcome.NOT_FOUND &&
                cycleAfterNotFound(adbd, step, wirelessOn, onWifi, switchedOnHere, request)
            ) {
                w.note("revive: nothing advertised, adbd unreadable; switching off and on once on this Wi-Fi")
                if (switchOffAndOn(w, onWifi, answerAwaited = false)) {
                    connected = w.autoConnect(CONNECT_MS)
                    found.connect = w.lastConnect
                }
            }
            connected.onFailure {
                Log.w(TAG, "daemon: no ADB session - ${it.javaClass.simpleName}: ${it.message ?: "no detail"}")
                w.note("revive: no ADB session (${found.connect}): ${it.message}")
                // Cancelled: Android 12 leaves the switch off (noteWirelessRefused).
                if (request == Request.ALLOW_WIFI && onWifi && !wirelessDebuggingOn(w)) {
                    w.noteRefused()
                    w.note("revive: Android refused Wireless debugging on this Wi-Fi")
                }
                // An older build that is up is still a recorder. Only a daemon
                // that is down is a failure here - reporting the other as one
                // would have the keep-alive retrying with backoff all day.
                return running != null
            }
        }
        val change = PortChange.portChangeOver(portChange, w.connectedPort)
        if (change != null) {
            onPortChange?.invoke()
            PortChange.changePort(w, open = change == StartWithoutWifi.PortStep.OPEN)
            // It connects again after adbd's restart: that connect is this
            // revive's reading now, as it was when read at the end.
            found.connect = w.lastConnect
        } else if (portChange != null) {
            w.note("revive: port ${StartWithoutWifi.PORT} left open: connected over it, no Wireless debugging here")
        }

        w.note("revive: connected, starting the recorder")
        val started = w.start()
        val ok = started.isSuccess
        w.note("revive: start ${started.fold({ "ok" }, { "failed: ${it.message}" })}")
        if (ok) {
            // A spawned daemon is told the mode in its environment, so this is
            // for the other case - one that was already running - and it is
            // one round trip. Best-effort.
            w.pushMode()
            // Wireless debugging was turned on a moment ago to make this
            // possible. It is not needed again until the next revive.
            w.standDown()
            // And off again, if JemRec is off with Developer options hidden.
            w.afterRecorderStarted()
        }
        return ok
    }

    /**
     * A revive's off and on (cycleWirelessDebugging), then a moment for adbd
     * to start and advertise; noted for this Wi-Fi, which a routine revive
     * switches once (wirelessDebuggingStep). Read back as TURN_ON's switch-on
     * is: off again is a refusal (NetworkRevive.noteWirelessRefused) - except
     * where the user was asked and may still be answering, which the
     * connect's long look waits out. True if both writes landed.
     */
    private suspend fun switchOffAndOn(w: ReviveWorld, onWifi: Boolean, answerAwaited: Boolean): Boolean {
        Log.i(TAG, "daemon: switching Wireless debugging off and on")
        val cycled = cycleWirelessDebugging(w)
        w.note("revive: switched off and on: $cycled")
        if (!cycled) return false
        // Once: that starts adbd, and a revive that fails after it fails for
        // some other reason, which cycling again would only hide.
        w.forgetCable()
        if (onWifi) w.noteSwitchedOn()
        delay(ADBD_SETTLE_MS)
        if (onWifi && !answerAwaited && !wirelessDebuggingOn(w)) {
            w.noteRefused()
            w.note("revive: Android refused Wireless debugging on this Wi-Fi")
        }
        return true
    }

    /** Wireless debugging off, then on: what puts the network question back
     *  on screen, and what starts an adbd a cable stopped. Not to be cut
     *  between the two: a revive cancelled there - a worker WorkManager
     *  stops, a screen left - left Wireless debugging off until the next
     *  revive. */
    private suspend fun cycleWirelessDebugging(w: ReviveWorld): Boolean = withContext(NonCancellable) {
        if (!w.write(GlobalSettings.ADB_WIFI_ENABLED, false)) return@withContext false
        // Long enough for the off to land as its own change.
        delay(1_000)
        w.write(GlobalSettings.ADB_WIFI_ENABLED, true)
    }

    internal fun wirelessDebuggingOn(w: ReviveWorld) = w.read(GlobalSettings.ADB_WIFI_ENABLED) == GlobalSettings.Reading.ON

    internal enum class WirelessStep { CYCLE, TURN_ON, LEAVE }

    /** Who asked for a revive, which decides how far it goes to start adbd. */
    enum class Request {
        /** The keep-alive, a watch, the screen coming back: Wireless debugging
         *  switched off and on once per Wi-Fi connection at most, so that no
         *  reading gone wrong can put Android's question up again and again. */
        ROUTINE,

        /** The user asked - the header's Tap to start again, or turning
         *  JemRec on (RecorderSwitch.turn): they are there, and the once per
         *  connection does not hold. */
        USER,

        /** The header's Allow this Wi-Fi (askToAllowNetwork): switched off and
         *  on whatever else is found, and the connect waits for the answer. */
        ALLOW_WIFI,
    }

    /**
     * What a revive does to Wireless debugging before it connects. Off Wi-Fi,
     * on Start without Wi-Fi's port, only the off and on that starts an adbd
     * a cable stopped: Wireless debugging itself cannot come on there. On
     * Wi-Fi: switched on where it is off, but not where Android has [refused]
     * it on this Wi-Fi - that is left to the "Allow this Wi-Fi" button, and to
     * Start without Wi-Fi's switch, whose user is there to answer; off and on
     * where it is on and [adbdStopped], once per Wi-Fi connection unless the
     * user asked (Request); and off and on whatever else for Allow this Wi-Fi.
     */
    internal fun wirelessDebuggingStep(
        cableStoppedAdbd: Boolean,
        on: Boolean,
        onWifi: Boolean,
        refused: Boolean = false,
        adbdStopped: Boolean = false,
        switchedOnHere: Boolean = false,
        request: Request = Request.ROUTINE,
    ): WirelessStep = when {
        cableStoppedAdbd -> WirelessStep.CYCLE
        request == Request.ALLOW_WIFI && onWifi -> WirelessStep.CYCLE
        !on && onWifi && !refused -> WirelessStep.TURN_ON
        on && onWifi && adbdStopped && mayCycleAgain(switchedOnHere, request) -> WirelessStep.CYCLE
        else -> WirelessStep.LEAVE
    }

    /**
     * Where adbd's state cannot be read (AdbdState.UNKNOWN), nothing
     * advertised with the switch on is adbd not running or a Wi-Fi Android
     * has not allowed, which a revive cannot tell apart: one off and on per
     * Wi-Fi connection starts the first, and asks once about the second.
     * Only after a revive that left the switch as it was.
     */
    internal fun cycleAfterNotFound(
        adbd: AdbdState.State,
        step: WirelessStep,
        on: Boolean,
        onWifi: Boolean,
        switchedOnHere: Boolean,
        request: Request,
    ): Boolean = adbd == AdbdState.State.UNKNOWN && step == WirelessStep.LEAVE && on && onWifi &&
        mayCycleAgain(switchedOnHere, request)

    private fun mayCycleAgain(switchedOnHere: Boolean, request: Request) =
        !switchedOnHere || request != Request.ROUTINE

    /**
     * THE OFF-WI-FI SHIELD. This is what lets recording survive in the field,
     * away from any Wi-Fi.
     *
     * The daemon dies on Wi-Fi loss for one reason: adbd RESTARTS, and the
     * restart cgroup-kills adbd's children. adbd restarts only when it has no
     * enabled transport left - measured in the framework: AdbService.stopAdbd()
     * returns early while EITHER USB or Wi-Fi adb is enabled. So turning USB
     * debugging on - the setting, adb_enabled, no cable needed and it sticks
     * without one - keeps adbd's USB transport "enabled" in the framework's
     * eyes. Losing Wi-Fi then stops the Wireless server but no longer restarts
     * adbd, and the daemon lives on.
     *
     * Proven on device: no cable, adb_enabled=1, Wi-Fi off - a real call
     * recorded while adbd and the daemon kept their PIDs across the outage.
     * Idempotent; needs WRITE_SECURE_SETTINGS, granted once at setup.
     *
     * Where Android 17 hides the setting, it is written every time, since
     * there is no telling whether it drifted. That costs nothing: on the 37.2
     * emulator, writing the 1 that was already there left adbd's PID as it
     * was, and so the daemon too. Logged only when the setting was known to
     * be off, or every revive would claim to have switched it on.
     */
    private fun armShield(w: ReviveWorld) {
        val now = w.read(GlobalSettings.ADB_ENABLED)
        if (now != GlobalSettings.Reading.ON &&
            w.write(GlobalSettings.ADB_ENABLED, true) &&
            now == GlobalSettings.Reading.OFF
        ) {
            Log.i(TAG, "daemon: enabled USB debugging as the off-Wi-Fi shield")
        }
    }
}
