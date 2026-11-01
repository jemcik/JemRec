package com.jemcik.jemrec.capture

import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.jemcik.jemrec.ui.Setup
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * The phone the lifecycle tests stand on, under Robolectric: the three
 * debugging switches in its real settings provider, setup's flag and the
 * ADB identity's two files, its Wi-Fi, and the revive log the debug build
 * keeps (R.bool.keeps_revive_log). No recorder answers on 28472 here, so
 * every probe fails: a confirmed one takes about 2.4 s (five pings, 600 ms
 * apart), and a revive that gets past its gate about 2.5 s - measured on
 * this machine, 2026-11-01.
 */
internal object TestPhone {

    const val DEV = GlobalSettings.DEVELOPMENT_SETTINGS_ENABLED
    const val WIFI = GlobalSettings.ADB_WIFI_ENABLED
    const val USB = GlobalSettings.ADB_ENABLED

    private val identity = listOf("adb_private_key.der", "adb_certificate.der")

    fun put(context: Context, name: String, value: Int) = Settings.Global.putInt(context.contentResolver, name, value)
    fun get(context: Context, name: String) = Settings.Global.getInt(context.contentResolver, name, -1)

    /** All three switches on, as setup leaves them. */
    fun switchesOn(context: Context) = listOf(DEV, WIFI, USB).forEach { put(context, it, 1) }

    /** Setup finished: the flag and an identity on disk (CaptureDaemon.isSetUp). */
    fun setUp(context: Context) {
        Setup.markComplete(context)
        identity.forEach { File(context.filesDir, it).writeText("set up") }
    }

    /** No Wi-Fi joined: a revive stops at "not on Wi-Fi", before any connect. */
    fun offWifi(context: Context) = shadowOf(context.getSystemService(ConnectivityManager::class.java)).clearAllNetworks()

    private fun log(context: Context) = File(context.filesDir, "revive.log")

    fun clearReviveLog(context: Context) {
        log(context).delete()
    }

    /** The revive log's lines without their times. */
    fun reviveLog(context: Context): List<String> =
        log(context).takeIf { it.exists() }?.readLines().orEmpty().map { it.substringAfter(' ') }

    /** The Wi-Fi watch's PendingIntents the system holds (NetworkRevive.arm). */
    fun wifiWatches(context: Context) =
        shadowOf(context.getSystemService(ConnectivityManager::class.java)).networkCallbackPendingIntents

    /**
     * Which Settings.Global names change, in the order they change. Only a
     * change is seen: a write of the value already there reaches no observer
     * here (measured: two writes of 1 to adb_wifi_enabled, one notification).
     */
    class Writes(private val context: Context) {
        private val names = mutableListOf<String>()
        private val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                uri?.lastPathSegment?.let { names += it }
            }
        }

        init {
            context.contentResolver.registerContentObserver(Settings.Global.CONTENT_URI, true, observer)
        }

        fun seen(): List<String> {
            shadowOf(Looper.getMainLooper()).idle()
            return names.toList()
        }

        fun close() = context.contentResolver.unregisterContentObserver(observer)
    }
}
