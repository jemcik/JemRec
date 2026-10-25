package com.jemcik.jemrec.capture

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.service.quicksettings.TileService
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.ui.RecorderTile

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
     * Returns the way to stop, which also holds the listener: preferences keep
     * only a weak reference to it. A no-op where there are no preferences, as
     * in the JVM tests.
     */
    fun observe(context: Context, onChange: (Boolean) -> Unit): () -> Unit = runCatching {
        val prefs = Prefs.of(context)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { changed, key ->
            if (key == KEY) onChange(changed.getBoolean(KEY, true))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        val stop: () -> Unit = { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
        stop
    }.getOrElse { {} }

    /**
     * Switch recording on or off, and make the recorder agree. The one path
     * the app's switch and the Quick Settings tile share.
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
    suspend fun turn(context: Context, on: Boolean) {
        set(context, on)
        if (on) {
            CaptureDaemon.revive(context)
            RecorderKeepAlive.schedule(context)
            NetworkRevive.arm(context)
            CaptureDaemon.pushMode(context)
        } else {
            CaptureDaemon.setDaemonMode(context, CaptureDaemon.MODE_OFF)
            CallMonitorService.stop(context)
        }
    }
}
