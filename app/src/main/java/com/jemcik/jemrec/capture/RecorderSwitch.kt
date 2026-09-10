package com.jemcik.jemrec.capture

import android.content.Context
import com.jemcik.jemrec.Prefs

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
        Prefs.of(context)
            .edit().putBoolean(KEY, on).apply()
    }
}
