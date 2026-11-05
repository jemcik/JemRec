package com.jemcik.jemrec.recorder

import android.content.Context
import android.util.Log
import com.jemcik.jemrec.adb.AdbIdentity
import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.capture.RecorderKeepAlive
import com.jemcik.jemrec.capture.UsbDebuggingWatch
import com.jemcik.jemrec.setup.Setup
import com.jemcik.jemrec.system.GlobalSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The recorder's lock, and what takes it: a revive, a start, Start fresh's
 * close of the port, and every write of the debugging switches outside a
 * revive - Off, On's putting back, a stand-down, Start fresh's. Whether a
 * trigger goes on (admit), what it arms (arm) and what follows its revive
 * (followUp), by the tables in Triggers.kt. The one writer of the switches
 * (write). Whether this phone is set up for a recorder at all.
 */
object RecorderLifecycle {

    private const val TAG = "JemRec"

    /**
     * revive() and ensureRunning() take this, so two callers arriving together
     * - the keep-alive job and a Wi-Fi return, a resume and the switch - cannot
     * both conclude the daemon is down and both spawn one. The second spawn
     * used to die on EADDRINUSE and, worse, truncate the first one's log.
     *
     * And every write of the debugging switches outside a revive takes it
     * too - Off and what it switches off, On's putting back, an off finished
     * where the recorder left it, Start fresh's - so a revive and an Off never
     * interleave: one under way finishes before the Off writes, and one
     * queued behind it asks, once it has the lock, whether JemRec is staying
     * off. Asked before the lock and not again, a revive queued when Off
     * came had USB debugging back on (its armShield) under a JemRec off with
     * all debugging.
     */
    private val lifecycle = Mutex()

    /**
     * Bring the daemon back if it is down and can be reached.
     *
     * This was the DaemonWatchdog's repair(), moved here unchanged when the
     * permanent monitor service went away. A dormant app has no service running
     * a poll loop, so reviving the daemon is now the job of a scheduled worker
     * and of boot - both of which call this, so the logic lives with the rest
     * of the daemon's lifecycle rather than in a service that no longer exists.
     *
     * Returns true if the daemon is running when this returns, false if it
     * could not be reached - almost always no Wi-Fi, since restarting it needs
     * an ADB session and an ADB session needs Wi-Fi, or Start without Wi-Fi's
     * port.
     *
     * [onPortChange] is told when a change of Start without Wi-Fi's port
     * begins - the restart of adbd, and of the recorder with it: the
     * switch's own revive says Starting from then, and not for one that ends
     * up changing nothing (MainViewModel.setStartWithoutWifi).
     *
     * [request] says who asked, which decides how far it goes to start adbd
     * (wirelessDebuggingStep).
     */
    suspend fun revive(
        context: Context,
        onPortChange: (() -> Unit)? = null,
        request: Revive.Request = Revive.Request.ROUTINE,
    ): Boolean = withContext(Dispatchers.IO) {
        // What this revive's own connect found, kept as it connects, under
        // the lock: AdbTransport.lastConnect is any connect's. Read after the
        // lock, it was the next revive's, which reset it on its way in - on
        // the Android 13 and 16 AVDs, 2026-10-29, a keep-alive queued behind
        // Allow this Wi-Fi's revive had it log "recorder up (connect NONE)".
        val found = Revive.Found()
        val alive = lifecycle.withLock {
            // Switched off with debugging, by choice: every revive stands down
            // here, or one of them would have debugging back on within
            // seconds. Asked under the lock (see it). Not a failure either:
            // RecorderHealth hears that JemRec stands down (standDown).
            val admitted = admitLocked(context, Trigger.REVIVE)
            if (admitted !is Admit.Go) {
                ReviveLog.note(context, "revive: debugging is off with JemRec; nothing to do until it is on")
                return@withLock null
            }
            // With JemRec on, any record is an On cut short - the switch saved,
            // the process gone before the switches came back - finished here.
            // Any, not only one that took the recorder: Developer options would
            // otherwise stay hidden, and Wireless debugging go off after this
            // revive, with JemRec on.
            if (admitted.putBack) {
                ReviveLog.note(context, "revive: JemRec is on with debugging still recorded off; putting it back")
                DebugSwitchesOff.switchBackOn(context)
            }
            AdbTransport.forgetLastConnect()
            ReviveLog.note(context, "revive: start")
            Revive.reviveLocked(ReviveWorld.Live(context), onPortChange, request, found)
        } ?: return@withContext false
        ReviveLog.note(context, "revive: ${if (alive) "recorder up" else "recorder down"} (connect ${found.connect})")
        // Every revive is a reading of whether the recorder is up, and the
        // one place the user hears about it when it is not. See RecorderHealth.
        RecorderHealth.onRevive(context, alive, found.connect)
        if (alive) {
            CableWatch.forget(context)
            NetworkRevive.forgetSwitchedOn(context)
        }
        alive
    }

    /**
     * The header's Allow this Wi-Fi, and the alert's: put Android's "Allow
     * wireless debugging on this network?" back on screen, for a Wi-Fi it
     * has not allowed (see RecorderHealth), and start the recorder once
     * adbd is advertised.
     *
     * SystemUI asks when Wireless debugging is switched ON on such a network,
     * not while it stays on, so the revive switches it off and on whatever
     * else it finds (Request.ALLOW_WIFI). Measured on the Android 17 QPR2
     * emulator: the question appeared within a second of the second write,
     * and after Always allow and Allow, adbd advertised and the recorder came
     * back. It revives itself rather than leaving that to the screen's
     * re-check when the question closes: where no question comes - a Wi-Fi
     * allowed already, adbd merely not running - nothing closes. On the
     * Honor, 2026-10-28, the tap started adbd two seconds after it and the
     * recorder waited a minute, for the keep-alive. Under the revive's lock,
     * so it waits for a start under way instead of cutting it off: the third
     * of three taps that evening switched Wireless debugging off two seconds
     * into a keep-alive's start, which then failed.
     *
     * False without WRITE_SECURE_SETTINGS, which leaves the user to do it by
     * hand in Settings: nothing can be switched then. Asked of the permission,
     * not tried with a write, which would race the switch's other writers -
     * a keep-alive's off and on among them. The permission and the writes go
     * live together (Setup.hasSecureSettings: not until the process restarts
     * after setup granted it).
     */
    suspend fun askToAllowNetwork(context: Context): Boolean {
        Log.i(TAG, "daemon: asking Android to allow Wireless debugging on this Wi-Fi")
        if (!Setup.hasSecureSettings(context)) return false
        revive(context, request = Revive.Request.ALLOW_WIFI)
        return true
    }

    /**
     * Make sure the daemon is running, starting it over ADB if not.
     *
     * This is the one operation in the whole app that needs a working ADB
     * session, so it is also the one that needs Wi-Fi. Call it at setup and
     * after a reboot - not when a call starts, by which time it may be too late.
     */
    suspend fun ensureRunning(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        lifecycle.withLock { DaemonLauncher.ensureRunningLocked(context) }
    }

    /**
     * Start fresh: Start without Wi-Fi's port closed, if JemRec opened it,
     * over the port itself - so with or without Wi-Fi. adbd restarts, which
     * is no loss: the recorder has been told to quit. Whether it is closed
     * now - or was never JemRec's - read back from the property: with adbd
     * stopped (Turn all debugging off) nothing answers to close it, and then
     * the note that JemRec opened it has to outlive the reset, or nothing
     * would close it before a reboot (Setup.reset).
     */

    suspend fun closePortForReset(context: Context): Boolean = withContext(Dispatchers.IO) {
        // On IO: Start fresh is run from the screen, and getprop is a process.
        // Under the recorder's lock: adbd's restart would cut off a revive
        // the keep-alive had under way, halfway through starting the recorder.
        lifecycle.withLock { PortChange.closeForReset(context) }
    }

    /** Arm what keeps the recorder alive, as [trigger] does (armedBy). */
    internal fun arm(context: Context, trigger: Trigger) = arm(context, armedBy(trigger))

    /** What [Trigger]s are admitted by, read now (Gates). */
    internal fun gates(context: Context): Gates {
        val off = DebugSwitchesOff.switchedOff(context)
        return Gates(
            setUp = isSetUp(context),
            switchOn = RecorderSwitch.isOn(context),
            recorded = off.isNotEmpty(),
            recorderGone = off.takesTheRecorder(),
        )
    }

    /** Whether [trigger] goes on, by what it finds now (admit). Standing
     *  down ([standDown]) under the lock, as every write of the switches
     *  outside a revive is - and asked again once the lock is had: an On
     *  that came while this waited for it has the switch on, and an off
     *  finished then would take the recorder from under it. */
    internal suspend fun admit(context: Context, trigger: Trigger): Admit {
        val admitted = admit(trigger, gates(context))
        return if (admitted == Admit.StandDown) lifecycle.withLock { admitLocked(context, trigger) } else admitted
    }

    /** [admit], with the lock already held (a revive's). */
    private suspend fun admitLocked(context: Context, trigger: Trigger): Admit =
        admit(trigger, gates(context)).also { if (it == Admit.StandDown) standDown(context) }

    /** What standing down does, whoever stands down: an off the recorder left
     *  undone finished, as each always has (DebugSwitchesOff.finishOffLeftUndone),
     *  and a recorder down no longer said to be (RecorderHealth.standingDown). */
    private suspend fun standDown(context: Context) {
        DebugSwitchesOff.finishOffLeftUndone(context)
        RecorderHealth.standingDown(context)
    }

    /** Off: what When JemRec is off switches off with it
     *  (DebugSwitchesOff.switchOff), under the lock - if JemRec is still
     *  off once the lock is had. A revive can hold it for tens of seconds,
     *  and an On tapped meanwhile waits behind this Off (RecorderSwitch.turn):
     *  switched off then, all debugging would take the recorder, and the On
     *  would need Wi-Fi, or Start without Wi-Fi's port, to start it again. */
    internal suspend fun switchOff(context: Context) = lifecycle.withLock {
        if (admit(Trigger.TURN_OFF, gates(context)) is Admit.Go) DebugSwitchesOff.switchOff(context)
    }

    /** On: what the last Off switched off, put back
     *  (DebugSwitchesOff.switchBackOn), under the lock - if JemRec is still
     *  on once the lock is had, for the same reason as [switchOff]. */
    internal suspend fun switchBackOn(context: Context) = lifecycle.withLock {
        if (admit(Trigger.TURN_ON, gates(context)) is Admit.Go) DebugSwitchesOff.switchBackOn(context)
    }

    /** Start fresh: When JemRec is off forgotten, and Developer options back
     *  if it hid them (DebugSwitchesOff.forget), under the lock. */
    internal suspend fun forgetDebugging(context: Context) = lifecycle.withLock { DebugSwitchesOff.forget(context) }

    /** [write], for a writer outside a revive: under the lock. */
    internal suspend fun writeOutsideRevive(context: Context, setting: String, on: Boolean): Boolean =
        lifecycle.withLock { write(context, setting, on) }

    /** Whether [trigger] goes on: for the triggers that never stand down,
     *  outside a coroutine - boot, Wi-Fi coming back, the app's own. */
    internal fun goes(context: Context, trigger: Trigger): Boolean = admit(trigger, gates(context)) is Admit.Go

    /** What [trigger] does after its revive (followUp): what it arms is
     *  armed here, the rest is the caller's. */
    internal fun followUp(context: Context, trigger: Trigger, alive: Boolean, forWifi: Boolean = false): FollowUp =
        followUp(trigger, alive, NetworkRevive.onWifi(context), forWifi).also { arm(context, it.arm) }

    private fun arm(context: Context, keepers: List<Keeper>) = keepers.forEach {
        when (it) {
            Keeper.USB_WATCH -> UsbDebuggingWatch.arm(context)
            Keeper.HEARTBEAT -> RecorderKeepAlive.heartbeat(context)
            Keeper.KICK -> RecorderKeepAlive.kickNow(context)
            Keeper.WIFI_RUN -> RecorderKeepAlive.kickForWifi(context)
            Keeper.WIFI_WATCH -> NetworkRevive.arm(context)
        }
    }

    /**
     * Every write the app makes of the debugging switches - USB debugging,
     * Wireless debugging, Developer options: a revive's (ReviveWorld), When
     * JemRec is off's (DebugSwitchesOff), Start fresh's (Setup.reset). One
     * place, so that what writes them, in what order and under which lock,
     * is read here (SwitchWritersTest holds it). The recorder writes them
     * too, as the shell: its shield, and Turn all debugging off (Main.java).
     */
    internal fun write(context: Context, setting: String, on: Boolean): Boolean = GlobalSettings.set(context, setting, on)

    /** Setup's off-Wi-Fi shield, written over the session setup opened, as
     *  the shell: the app's own WRITE_SECURE_SETTINGS is not live in this
     *  process until it restarts after setup granted it (Setup.finish). */
    internal suspend fun shieldOverAdb(): String =
        AdbTransport.exec("settings put global adb_enabled 1 && echo shielded").getOrElse { "failed: ${it.message}" }

    /** Setup.finish() ran to the end at least once. Setup owns the flag. */
    internal fun setupComplete(context: Context): Boolean = Setup.isComplete(context)

    /**
     * Whether a daemon should exist on this phone at all: setup finished, and an
     * ADB identity to revive it with. This is the SAME guard revive() applies.
     *
     * The daemon's lifecycle is tied to this, NOT to the recorder switch. The
     * switch is a soft gate over whether a call is captured (CallMonitorService
     * reads it); turning recording off leaves the daemon running, so turning it
     * back on needs no Wi-Fi. That is why the keep-alive job, boot and the
     * Wi-Fi-return revival all ask this rather than the switch - if they gated
     * on the switch, an off in the field would strand the daemon with no way
     * back until Wi-Fi returned.
     */
    fun isSetUp(context: Context): Boolean =
        setupComplete(context) && AdbIdentity.exists(context)
}
