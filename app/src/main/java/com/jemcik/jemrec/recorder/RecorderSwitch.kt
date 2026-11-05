package com.jemcik.jemrec.recorder

import android.content.ComponentName
import android.content.Context
import android.service.quicksettings.TileService
import android.util.Log
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.calls.MissedCalls
import com.jemcik.jemrec.capture.CallMonitorService
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.ui.RecorderTile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Whether JemRec records at all.
 *
 * A SOFT SWITCH. Off tells the daemon to capture nothing (its mode goes to
 * OFF) and stops the per-call service; it does NOT stop the daemon. It used
 * to, on the grounds that a resident recorder the user had switched off was
 * a lie - but starting the daemon again needs an ADB session, which needs
 * Wi-Fi, so an "off" in the field stranded the phone unable to record until
 * it next saw a network. The daemon's life is tied to setup instead (see
 * CaptureDaemon.isSetUp), and on is instant anywhere.
 *
 * Unless the user chose otherwise. Settings' When JemRec is off can also take
 * Developer options and Wireless debugging off with it, the recorder kept, or
 * all debugging, the recorder with it - for apps that will not open with
 * debugging on (DebugSwitchesOff). That last one is the old field trap again,
 * so it is the user's to choose, says so, and asks first when there is no
 * Wi-Fi to come back on.
 *
 * The switch is read in two places that must agree: here, for the daemon's
 * mode and the wizard's "switched off is not unconfigured" rule, and in
 * CallMonitorService at the end of a call, which discards a recording the
 * daemon had already started when the switch went off mid-call.
 */
object RecorderSwitch {

    private const val KEY = "recorder_enabled"

    /** On by default. Someone who installed a call recorder wants it on. */
    fun isOn(context: Context): Boolean =
        Prefs.of(context).getBoolean(KEY, true)

    fun set(context: Context, on: Boolean) {
        val wasOn = isOn(context)
        Prefs.of(context)
            .edit { putBoolean(KEY, on) }
        MissedCalls.coverageChanged(context)
        // A stop while the switch was off cost no call: from here on only
        // newer ones count, however the switch was flipped (app or tile).
        if (on && !wasOn) BackgroundKill.recordingSwitchedOn(context)
        // The Quick Settings tile shows this switch; ask it to look again, so
        // a change made in the app is not contradicted the next time the
        // panel is pulled down.
        runCatching { TileService.requestListeningState(context, ComponentName(context, RecorderTile::class.java)) }
    }

    /**
     * Calls [onChange] whenever the switch flips, from anywhere. The app
     * needs this for the Quick Settings tile: pulling the panel down over the
     * app does not pause it, so a flip from the tile never reached the screen
     * - measured on the Honor, where the header said "Ready to record" over a
     * switch that was off, until the app was next reopened.
     *
     * Returns the way to stop (Prefs.watch).
     */
    fun observe(context: Context, onChange: (Boolean) -> Unit): () -> Unit =
        Prefs.watch(context, KEY) { onChange(isOn(context)) }

    /** Outlives whoever asked: a tile is unbound, and a question's activity
     *  finishes, as soon as the panel or the dialog closes - and a switch
     *  half-turned then would leave the recorder disagreeing with it. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** One turn at a time, whoever asked - the app, the tile, its question.
     *  Two at once interleave at the recorder: an off's "record nothing", or
     *  its debugging off, arriving after the on that followed it. */
    private val turning = Mutex()

    /**
     * Save the switch and [turn], for a caller that will be gone before it is
     * done: the Quick Settings tile and its question (TurnOffQuestionActivity).
     * Saved here, at once, so a second tap reads it and turns the other way.
     */
    fun turnInBackground(context: Context, on: Boolean) {
        val app = context.applicationContext
        set(app, on)
        turnLater(app, on)
    }

    /**
     * [turn], in the process's own scope, for a switch the caller has just
     * saved: the tile's (turnInBackground) and the app's record control,
     * whose screen Back takes away - an Off lost with it never switched
     * debugging off. Its job, for a caller still there to wait for it.
     */
    fun turnLater(context: Context, on: Boolean): Job {
        val app = context.applicationContext
        return background.launch {
            runCatching { turn(app, on) }
                .onFailure { Log.w("JemRec", "could not turn recording ${if (on) "on" else "off"}", it) }
        }
    }

    /**
     * Make the recorder agree with the switch, which the caller has just
     * saved as [on]. The one path the app's switch and the Quick Settings tile
     * share. It waits its turn, and is dropped if the switch has moved on by
     * then - a quick off and on ends on, whichever of the two turned it. And
     * looked at again after each wait inside it - the put-back's for the
     * recorder's lock, the mode's for a send under way: an On whose switch
     * is off again starts no revive, which may switch Wireless debugging off
     * and on for a JemRec that is off; an Off whose switch is on again stops
     * no call service.
     *
     * On is instant anywhere: off leaves the daemon running (see above), so
     * revive() finds it up and only checks it over loopback, with no Wi-Fi
     * needed. When the daemon is genuinely down, revive() brings it up - the
     * same bring-up as boot and the keep-alive job - and the job and the Wi-Fi
     * watch are armed so it stays up. Then the daemon is told how to record.
     *
     * Off is SOFT: the daemon is told to capture nothing and the per-call
     * service stops, but the daemon, its keep-alive and its Wi-Fi revival stay
     * as they are - killing it here used to be a field trap, since a rebuild
     * needs Wi-Fi.
     */
    suspend fun turn(context: Context, on: Boolean) = turning.withLock {
        val admitted = RecorderLifecycle.admit(context, if (on) Trigger.TURN_ON else Trigger.TURN_OFF)
        if (admitted !is Admit.Go) return@withLock
        if (on) {
            // What the last off switched off, back first: the revive below
            // stands down while it is still off (DebugSwitchesOff).
            if (admitted.putBack) RecorderLifecycle.switchBackOn(context)
            if (!isOn(context)) return@withLock
            // The user's own On: not held to a routine revive's limits.
            CaptureDaemon.revive(context, request = CaptureDaemon.Request.USER)
            RecorderLifecycle.arm(context, Trigger.TURN_ON)
            CaptureDaemon.pushMode(context)
        } else {
            // The mode as the switch says it when sent: Off, unless it is
            // on again by then.
            CaptureDaemon.sendMode(context)
            if (isOn(context)) return@withLock
            CallMonitorService.stop(context)
            // Then whatever else the user chose to have off with it: Settings,
            // When JemRec is off. The default switches nothing.
            RecorderLifecycle.switchOff(context)
        }
    }
}
