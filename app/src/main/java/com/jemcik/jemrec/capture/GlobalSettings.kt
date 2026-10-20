package com.jemcik.jemrec.capture

import android.content.Context
import android.os.Build
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
 *
 * TWO OF THEM CAN NO LONGER BE READ ON ANDROID 17. AOSP marked adb_enabled and
 * development_settings_enabled `@Readable(redactedValue = "0")`, and the docs
 * now say both "will always return 0 for all third-party apps". It is behind a
 * flag: on Pixels from the September 2026 update both read 0 whatever the
 * switches say, while the 37.2 emulator image, MagicOS 11 on the Honor, and
 * shell (so the daemon) still read the truth. A 0 from either, on SDK 37 or
 * later, is therefore not "off" but "cannot tell" - see [read]. A 1 is still
 * the truth, and adb_wifi_enabled was left alone, so [isOn] stays right for it.
 */
object GlobalSettings {

    private const val TAG = "JemRec"

    /** USB debugging. On this phone also the daemon's off-Wi-Fi shield: see
     *  CaptureDaemon.armShield. Hidden from apps on some Android 17 builds. */
    const val ADB_ENABLED = "adb_enabled"

    /** Wireless debugging: the bootstrap door, needed only to start the daemon. */
    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    /** Whether Developer options - and so the Wireless debugging screen - exist.
     *  Hidden from apps on some Android 17 builds. */
    const val DEVELOPMENT_SETTINGS_ENABLED = "development_settings_enabled"

    /** The settings Android 17 may report as 0 to this app whatever they are. */
    private val HIDEABLE = setOf(ADB_ENABLED, DEVELOPMENT_SETTINGS_ENABLED)

    /** The first SDK whose builds may hide them. */
    const val HIDDEN_FROM_SDK = 37

    /** What the app can learn about a switch. */
    enum class Reading { ON, OFF, UNKNOWN }

    /**
     * Debug builds only: report the hideable settings as 0, the way a Pixel
     * does, on a phone or emulator that does not. Nothing in the release
     * build sets it.
     */
    @Volatile
    internal var actAsHidden = false

    internal fun isOn(context: Context, name: String): Boolean = read(context, name) == Reading.ON

    /** ON, OFF, or UNKNOWN where this phone may be hiding the answer. */
    internal fun read(context: Context, name: String): Reading {
        val value = if (actAsHidden && name in HIDEABLE) 0
        else Settings.Global.getInt(context.contentResolver, name, 0)
        return classify(name, value, Build.VERSION.SDK_INT)
    }

    internal fun classify(name: String, value: Int, sdk: Int): Reading = when {
        value == 1 -> Reading.ON
        sdk >= HIDDEN_FROM_SDK && name in HIDEABLE -> Reading.UNKNOWN
        else -> Reading.OFF
    }

    /** True if the write went through. */
    internal fun set(context: Context, name: String, on: Boolean): Boolean = runCatching {
        Settings.Global.putInt(context.contentResolver, name, if (on) 1 else 0)
        true
    }.getOrElse {
        Log.w(TAG, "settings: cannot write $name - ${it.javaClass.simpleName}")
        false
    }
}
