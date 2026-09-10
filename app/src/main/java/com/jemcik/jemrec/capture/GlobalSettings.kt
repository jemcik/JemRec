package com.jemcik.jemrec.capture

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * The Settings.Global switches this app lives by, read and written in one
 * place.
 *
 * Reading needs no permission. Writing needs WRITE_SECURE_SETTINGS, which setup
 * takes once with `pm grant` and which is not live in the process that asked
 * for it until that process restarts - so a write can fail on a phone that is
 * set up perfectly well. Every caller has a next step that does not depend on
 * the write, which is why a failure is logged and reported, never thrown.
 */
internal object GlobalSettings {

    private const val TAG = "JemRec"

    /** USB debugging. On this phone also the daemon's off-Wi-Fi shield: see
     *  CaptureDaemon.armShield. */
    const val ADB_ENABLED = "adb_enabled"

    /** Wireless debugging: the bootstrap door, needed only to start the daemon. */
    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    /** Whether Developer options - and so the Wireless debugging screen - exist. */
    const val DEVELOPMENT_SETTINGS_ENABLED = "development_settings_enabled"

    fun isOn(context: Context, name: String): Boolean =
        Settings.Global.getInt(context.contentResolver, name, 0) == 1

    /** True if the write went through. */
    fun set(context: Context, name: String, on: Boolean): Boolean = runCatching {
        Settings.Global.putInt(context.contentResolver, name, if (on) 1 else 0)
        true
    }.getOrElse {
        Log.w(TAG, "settings: cannot write $name - ${it.javaClass.simpleName}")
        false
    }
}
