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

    /**
     * A revive's steps, all of them once begun, under the recorder's lock.
     * Its changes come in pairs whose second half comes later: Wireless
     * debugging off, then on; adbd restarted on the port, then the port
     * noted as JemRec's; an old recorder retired, then the new one started;
     * Wireless debugging on to start it, then off again once it is up
     * (askWirelessOff). A revive cancelled in between - a
     * worker WorkManager stops, a screen closed from Recents - left the
     * first half alone: Wireless debugging off, a port open that JemRec would
     * never close, no recorder, Wireless debugging on for good. The first
     * half left alone is shown on a stand-in phone (ReviveCancelTest); what
     * each then left is read from the code. Measured the other way on the
     * Honor, 2026-11-04: Start without Wi-Fi switched off and JemRec closed
     * from Recents, the screen gone 6 s into the revive, after adbd's restart
     * had taken the recorder, and the revive ran on: recorder back 3.7 s
     * later.
     */
    internal suspend fun reviveLocked(
        w: ReviveWorld,
        onPortChange: (() -> Unit)?,
        request: Request,
        found: Found,
    ): Boolean = withContext(NonCancellable) { steps(w, onPortChange, request, found) }

    private suspend fun steps(
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
        if (w.setUp()) {
            armShield(w)
            // Installs from before 1.5: a Wireless debugging found on is
            // JemRec's, once (NetworkRevive.takeOverOnce).
            w.takeOverOnce()
        }

        val running = w.recorder()
        w.note("revive: recorder ${running?.let { "answers, build ${it.build}" } ?: "does not answer"}")
        val onWifi = w.onWifi()
        // Start without Wi-Fi's port, to open or to close, where that can be
        // done now (portChangeDue).
        val portChange = PortChange.portChangeDue(w, onWifi)
        val current = running != null && w.current(running)
        if (current && portChange == null) {
            // Up, and Wireless debugging JemRec switched on may still be on:
            // the app stopped between a start and its off, or a call then.
            // This look switches it off (askWirelessOff) - and where the off
            // took the recorder with it, starts it again.
            if (w.setUp() && !askWirelessOff(w)) return steps(w, onPortChange, request, found)
            return true
        }
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
        // Android 12 and 13: the app's own mDNS look, held from before the
        // switch-on until the connect and any port change are done, so that
        // no stop of mdnsd lands between adbd's announcement and the look
        // that finds it (AdbTransport.holdMdns). Null on other versions.
        val held = if (onWifi) w.holdMdns() else null
        try {
            when (step) {
                WirelessStep.CYCLE -> {
                    // Asked again, by the one who will answer.
                    if (request == Request.ALLOW_WIFI) w.forgetRefused()
                    switchOffAndOn(w, onWifi, answerAwaited = request == Request.ALLOW_WIFI, wasOn = wirelessOn)
                }
                WirelessStep.TURN_ON -> if (w.write(GlobalSettings.ADB_WIFI_ENABLED, true)) {
                    Log.i(TAG, "daemon: re-armed Wireless debugging")
                    w.noteSwitchedOn()
                    // Off before, on now: JemRec's, to switch off again once the
                    // recorder is up (askWirelessOff).
                    w.noteJemRecs()
                    delay(ADBD_SETTLE_MS)
                    // Off again: Android refused it on this Wi-Fi and is asking
                    // (NetworkRevive.noteWirelessRefused). With the recorder up,
                    // only the port is at stake: a moment for Allow - the one who
                    // has just flipped Start without Wi-Fi is there to answer -
                    // then no more asking on this Wi-Fi (portChangeDue).
                    if (!wirelessDebuggingOn(w) &&
                        !(current && withTimeoutOrNull(ALLOW_WAIT_MS) { while (!wirelessDebuggingOn(w)) delay(500) } != null)
                    ) {
                        noteRefusal(w)
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
                    cycleAfterNotFound(adbd, step, wirelessOn, onWifi, switchedOnHere, request, w.mdnsStops())
                ) {
                    w.note(
                        "revive: nothing found, " +
                            "${if (adbd == AdbdState.State.UNKNOWN) "adbd unreadable" else "adbd running"}; " +
                            "switching off and on once on this Wi-Fi",
                    )
                    if (switchOffAndOn(w, onWifi, answerAwaited = false, wasOn = wirelessOn)) {
                        connected = w.autoConnect(CONNECT_MS)
                        found.connect = w.lastConnect
                    }
                }
                connected.onFailure {
                    Log.w(TAG, "daemon: no ADB session - ${it.javaClass.simpleName}: ${it.message ?: "no detail"}")
                    w.note("revive: no ADB session (${found.connect}): ${it.message}")
                    // Cancelled: Android 12 leaves the switch off (noteWirelessRefused).
                    if (request == Request.ALLOW_WIFI && onWifi && !wirelessDebuggingOn(w)) noteRefusal(w)
                    // An older build that is up is still a recorder. Only a daemon
                    // that is down is a failure here - reporting the other as one
                    // would have the keep-alive retrying with backoff all day.
                    return running != null
                }
            }
            val change = PortChange.portChangeOver(portChange, w.connectedPort)
            when {
                // Asked again: a connect can be half a minute after portChangeDue
                // asked - adbd's four seconds, Allow's twenty, the look for adbd -
                // and adbd's restart would take the recorder from a call that came
                // meanwhile. The change is left for the next revive.
                change != null && w.inCall() ->
                    w.note("revive: port ${StartWithoutWifi.PORT} left as it is: a call came")
                // Nor a change the switch no longer asks for. Flipped back before
                // the port moved, nothing is due, and the flip's own revive, queued
                // behind this one, would only undo it, the recorder restarted
                // twice for nothing.
                change != null && w.portStep() != change ->
                    w.note("revive: port ${StartWithoutWifi.PORT} left as it is: the switch moved")
                change != null -> {
                    onPortChange?.invoke()
                    PortChange.changePort(w, open = change == StartWithoutWifi.PortStep.OPEN)
                    // It connects again after adbd's restart: that connect is this
                    // revive's reading now, as it was when read at the end.
                    found.connect = w.lastConnect
                }
                portChange != null ->
                    w.note("revive: port ${StartWithoutWifi.PORT} left open: connected over it, no Wireless debugging here")
            }
        } finally {
            held?.close()
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
            // The session that started it, closed: it is not needed again
            // until the next start.
            w.standDown()
            // Wireless debugging off again, if JemRec switched it on: it was
            // needed only to start the recorder (askWirelessOff). Where the
            // off took the recorder with it, round once more: switched on,
            // started, and no off asked again in this revive.
            if (!askWirelessOff(w)) return steps(w, onPortChange, request, found)
            // And one that is not JemRec's, where JemRec is off with
            // Developer options hidden (DebugSwitchesOff.afterRecorderStarted).
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
    private suspend fun switchOffAndOn(w: ReviveWorld, onWifi: Boolean, answerAwaited: Boolean, wasOn: Boolean): Boolean {
        Log.i(TAG, "daemon: switching Wireless debugging off and on")
        val cycled = cycleWirelessDebugging(w)
        w.note("revive: switched off and on: $cycled")
        if (!cycled) return false
        // Once: that starts adbd, and a revive that fails after it fails for
        // some other reason, which cycling again would only hide.
        w.forgetCable()
        if (onWifi) {
            w.noteSwitchedOn()
            // Off before the revive - a cable's stop with the switch off -
            // and on now: JemRec's. On before, it stays whose it was: the
            // user's stays on, JemRec's goes off (askWirelessOff).
            if (!wasOn) w.noteJemRecs()
        }
        delay(ADBD_SETTLE_MS)
        if (onWifi && !answerAwaited && !wirelessDebuggingOn(w)) noteRefusal(w)
        return true
    }

    /** Android has refused Wireless debugging on this Wi-Fi: noted for the
     *  revives to come (NetworkRevive.noteWirelessRefused), and in the log. */
    private fun noteRefusal(w: ReviveWorld) {
        w.noteRefused()
        w.note("revive: Android refused Wireless debugging on this Wi-Fi")
    }

    /** Wireless debugging off, then on: what puts the network question back
     *  on screen, and what starts an adbd a cable stopped. Never cut between
     *  the two: a revive runs to its end (reviveLocked). */
    private suspend fun cycleWirelessDebugging(w: ReviveWorld): Boolean {
        if (!w.write(GlobalSettings.ADB_WIFI_ENABLED, false)) return false
        // Long enough for the off to land as its own change.
        delay(1_000)
        return w.write(GlobalSettings.ADB_WIFI_ENABLED, true)
    }

    internal fun wirelessDebuggingOn(w: ReviveWorld) = w.read(GlobalSettings.ADB_WIFI_ENABLED) == GlobalSettings.Reading.ON

    /**
     * WIRELESS DEBUGGING OFF AGAIN, ONCE THE RECORDER IS UP.
     *
     * Wireless debugging is needed only to start the recorder: once it is
     * up, it serves loopback, and USB debugging - the setting, no cable -
     * keeps adbd, and so the recorder, alive (armShield). On, it leaves any
     * computer ever paired with this phone able to open a shell on it with
     * no question asked, the phone announcing itself on the Wi-Fi with its
     * serial number, and adbd's TLS port open to the network (plan 1.5).
     * Written off with USB debugging on, the recorder kept its process: on
     * the Honor (MagicOS 11, Android 17), 2026-10-26, with no cable, through
     * Wi-Fi off and on, six minutes locked and a call recorded, and
     * 2026-11-21, a computer's cable in, adbd and the recorder with the same
     * process IDs 8 s after; LineageOS does it
     * by itself a second after a start (the OnePlus, 2026-11-12); and two
     * Shizuku forks ship it, thedjchi's after every start and Stellar's
     * after a start it has confirmed.
     *
     * The recorder writes it, not the app (DaemonClient.wirelessOff): it
     * reads both switches as the shell, true where Android hides them from
     * apps, and writes it off only while USB debugging reads on - off with
     * USB debugging off would stop adbd, and the recorder with it. Asked
     * only for a Wireless debugging JemRec switched on (NetworkRevive.
     * noteJemRecs): one the user switched on for a computer stays on. And
     * only where [wirelessOffHeld] holds nothing back: called where the
     * recorder is up and current - a revive's early return and its start,
     * setup's end. Each answer is a line in revive.log; off, or off already,
     * the note goes. Not off, it is asked again at the next look that finds
     * the recorder up.
     *
     * THE GUARD (the user's choice, 2026-11-21, reversing the plan's pick
     * 7). A phone where the off stops adbd though USB debugging is on has
     * not been seen - not on the Honor, the S23, the AVDs, in AOSP's
     * AdbService 12 to 17 (stopAdbd only once neither transport is enabled),
     * nor by the titles of the issues of thedjchi's Shizuku fork, which
     * ships the off - but
     * on one, every start would end with the recorder gone, and nothing
     * would say so. So after an answer of off, or none - a recorder taken
     * with adbd may go before it answers - 2 s, and the recorder asked the
     * confirmed way. Gone, it is the off's doing only if nothing else explains
     * it (lostAfterOff): no off seen to happen; adbd running, as a USB change -
     * a lock, an unlock, the Default USB configuration set - restarts it and
     * the recorder in its cgroup goes (measured 2026-11-23, the Android 12 and
     * 16 AVDs: adbd down 0.05 s); or a USB change that stopped it, noted by
     * the cable's watch (the Honor's lock, 2026-11-24: adbd read STOPPED).
     * What is left counts, stopped or unreadable: twice in a row it stays on
     * from then on this phone, until Start fresh (NetworkRevive.noteOffLoss);
     * an off the recorder answered and survived clears the count. A loss that
     * does not count is asked about again at the next look. Either way false,
     * and the caller goes round once more, which switches it on and starts the
     * recorder, with no off asked again in this revive (offLost): a phone that
     * loses it every time is not left looping in one. True otherwise.
     */
    internal suspend fun askWirelessOff(w: ReviveWorld): Boolean {
        val held = wirelessOffHeld(
            jemRecs = w.jemRecs(),
            keepsAdbd = w.keepsAdbd(),
            lost = w.offLost,
            inCall = w.inCall(),
            canSwitchOn = w.canSwitchOn(),
        )
        if (held != null) {
            if (held != OffHeld.NOT_JEMRECS) w.note("revive: Wireless debugging left on: ${held.why}")
            return true
        }
        // A session still open - the stand-down's one ping missed a recorder
        // that is up - would be cut by the off; kept, every look after would
        // find it open. Closed: the recorder is up, and needs it no more
        // (review round 1).
        if (w.connected) {
            w.note("revive: an ADB session was left open; closed before the off")
            w.close()
        }
        val answer = w.wirelessOff()
        w.note("revive: Wireless debugging off - the recorder says ${answer ?: "nothing"}")
        if (answer == DaemonClient.WirelessOff.SWITCHED_OFF || answer == null) {
            delay(OFF_GUARD_MS)
            if (w.recorderStillUp()) {
                // Survived an off it answered; no answer, and the recorder up,
                // is no off seen - it is asked again at the next look.
                if (answer == DaemonClient.WirelessOff.SWITCHED_OFF) w.forgetOffLosses()
            } else {
                w.offLost = true
                lostAfterOff(w, answer)
                return false
            }
        }
        if (answer == DaemonClient.WirelessOff.SWITCHED_OFF || answer == DaemonClient.WirelessOff.ALREADY_OFF) {
            w.forgetJemRecs()
        }
        return true
    }

    /** The recorder gone [OFF_GUARD_MS] after an off: the off's doing only if
     *  adbd is stopped, or cannot be read, and nothing else explains it
     *  (askWirelessOff's guard): no off seen to happen - the recorder gone
     *  before it could answer, Wireless debugging still on; or a USB change
     *  that stopped adbd, noted by the cable's watch (CableWatch) - the
     *  Honor's lock stops it, 2026-11-24. */
    private suspend fun lostAfterOff(w: ReviveWorld, answer: DaemonClient.WirelessOff?) {
        if (answer == null && wirelessDebuggingOn(w)) {
            w.note("revive: the recorder went before Wireless debugging's off, which it never answered: not its doing; asked off at the next look")
            return
        }
        val adbd = adbdOnceSettled(w)
        when {
            adbd == AdbdState.State.RUNNING -> w.note(
                "revive: the recorder went within 2 s of Wireless debugging's off, but adbd runs: not its doing; asked off at the next look",
            )
            w.cableStopped() -> w.note(
                "revive: the recorder went within 2 s of Wireless debugging's off, a USB change having stopped adbd: not its doing; asked off at the next look",
            )
            w.noteOffLoss() -> w.note("revive: the recorder went with Wireless debugging; it stays on on this phone from now")
            else -> w.note("revive: the recorder went with Wireless debugging's off, adbd $adbd; a second in a row keeps it on here")
        }
    }

    /** adbd's state, given [OFF_ADBD_SETTLE_MS] to run: a stop and a start are
     *  passed through - "restarting" and "stopping" read as UNKNOWN, as does a
     *  property that cannot be read - and where it lands is what counts. Read
     *  again every quarter second. */
    private suspend fun adbdOnceSettled(w: ReviveWorld): AdbdState.State {
        var now = w.adbd()
        withTimeoutOrNull(OFF_ADBD_SETTLE_MS) {
            while (now != AdbdState.State.RUNNING) {
                delay(250)
                now = w.adbd()
            }
        }
        return now
    }

    /** How long the guard waits for adbd to run again before it counts the
     *  recorder's loss as the off's. A restart after a USB change took 0.05 s
     *  on the Android 12 and 16 AVDs (2026-11-23); the Honor's lock left it
     *  stopped (2026-11-24: read STOPPED 4 s after one), until a revive
     *  switched Wireless debugging off and on. */
    internal const val OFF_ADBD_SETTLE_MS = 2_000L

    /** How long the guard waits after an off before it asks for the
     *  recorder (askWirelessOff). */
    internal const val OFF_GUARD_MS = 2_000L

    /** Why Wireless debugging is not switched off now (wirelessOffHeld). */
    internal enum class OffHeld(val why: String) {
        NOT_JEMRECS("not JemRec's"),
        KEEPS_ADBD("on this phone it keeps adbd up"),
        LOST_THIS_REVIVE("the recorder went after an off in this revive; asked at the next look"),
        IN_CALL("a call is up; asked at the next look"),
        CANNOT_SWITCH_ON("this process could not switch it on again; asked at a later look"),
    }

    /**
     * Whether the recorder is asked for Wireless debugging off now
     * ([askWirelessOff]): null to ask, or why not. Only JemRec's. Never on a
     * phone where two offs in a row took the recorder, nor again in a revive
     * whose off did (the guard). Not mid-call, as the
     * port is not changed mid-call (portChangeDue); the call's end has the
     * keep-alive look again (kickAfterCall). And only where this process can
     * switch it on again for the next start: setup's own process, whose
     * WRITE_SECURE_SETTINGS is live only from the next one, or one whose
     * permission was taken back, would leave the next start nothing to
     * connect to, where 1.4 found it on (review round 1; the user's choice,
     * 2026-11-21).
     */
    internal fun wirelessOffHeld(
        jemRecs: Boolean,
        keepsAdbd: Boolean,
        lost: Boolean,
        inCall: Boolean,
        canSwitchOn: Boolean,
    ): OffHeld? =
        when {
            !jemRecs -> OffHeld.NOT_JEMRECS
            keepsAdbd -> OffHeld.KEEPS_ADBD
            lost -> OffHeld.LOST_THIS_REVIVE
            inCall -> OffHeld.IN_CALL
            !canSwitchOn -> OffHeld.CANNOT_SWITCH_ON
            else -> null
        }

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
     * And on Android 12 and 13 ([mdnsStops]) with adbd running: NsdService
     * stops mdnsd 10 s after the last app's request, and adbd's announcement
     * goes with it until the next switch-on (AdbTransport.holdMdns) - on the
     * Android 12 AVD, 2026-11-21, nothing was found 15 s after setup, and
     * all of it after a switch off and on. A Wireless debugging found on -
     * the user's, or JemRec's whose off was missed - is announced afresh by
     * one off and on (review round 1). Only after a revive that left the
     * switch as it was.
     *
     * Since 1.9, where the loopback probe ran, nothing found means it found
     * no server either (AdbdFinder): a lost announcement alone no longer gets
     * here - the probe finds adbd without one - but a switch read on with no
     * server answering does, and an off and on starts it again. Where it did
     * not run (loopback dropped for the app, the switch read off) nothing
     * changed.
     */
    internal fun cycleAfterNotFound(
        adbd: AdbdState.State,
        step: WirelessStep,
        on: Boolean,
        onWifi: Boolean,
        switchedOnHere: Boolean,
        request: Request,
        mdnsStops: Boolean = false,
    ): Boolean = (adbd == AdbdState.State.UNKNOWN || mdnsStops) && step == WirelessStep.LEAVE && on && onWifi &&
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
