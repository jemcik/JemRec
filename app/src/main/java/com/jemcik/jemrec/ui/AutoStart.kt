package com.jemcik.jemrec.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

/**
 * The vendor screen that decides whether this app may start after a reboot.
 *
 * WHY THIS EXISTS, MEASURED ON THE PHONE THIS WAS BUILT ON. A restart kills the
 * shell-side recorder, and rebuilding it needs a few seconds of ADB and so of
 * Wi-Fi. The app is built to do that unattended: at boot it arms a scheduled
 * job and asks the system to wake it when a network appears. Both of those are
 * armed BY the boot broadcast - so a phone that refuses to deliver it leaves
 * nothing armed, and nothing ever begins.
 *
 * That is not hypothetical. The same Honor, the same reboot, one setting apart:
 *
 *   auto-launch OFF  the system declined to start the app for the broadcast
 *                    ("HwMtmBroadcastResourceManager: BootProxy ... to start
 *                    process com.jemcik.jemrec : don't meet cpuload"), and
 *                    seven minutes after Wi-Fi returned nothing had come back.
 *   auto-launch ON   the app started at 21s of uptime, and the recorder was
 *                    listening ten seconds after Wi-Fi came on, untouched.
 *
 * The setting is real and it works. It is also four levels deep in a menu whose
 * name differs on every make, which is why the app offers to open it rather
 * than describing where to look.
 *
 * NONE OF THIS IS PUBLIC API. These component names are what the vendors ship,
 * found by looking, and they change between versions - so every one of them is
 * checked for existence before it is offered, launching is wrapped, and the
 * fallback is this app's own page in Settings, which every phone has. The
 * button simply does not appear on a phone where nothing here resolves, which
 * is the right answer for a Pixel: there is no such screen because there is no
 * such restriction.
 */
internal object AutoStart {

    private const val TAG = "JemRec"

    /**
     * Candidates, best first, grouped by make. The packages are declared in the
     * manifest's <queries> block; without that, Android 11 and later hide them
     * from resolveActivity and every one of these would look absent.
     */
    private val CANDIDATES = listOf(
        // HONOR FIRST, and it is its own package. Honor split from Huawei and
        // MagicOS renamed everything to com.hihonor.*, keeping the class names -
        // so a list with only the Huawei package finds nothing on an Honor,
        // which is exactly what happened on the phone this was written for: the
        // card did not appear on the one phone known to need it.
        "com.hihonor.systemmanager" to "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.hihonor.systemmanager" to "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity",
        // Huawei: "App launch" inside Phone Manager.
        "com.huawei.systemmanager" to "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        "com.huawei.systemmanager" to "com.huawei.systemmanager.optimize.process.ProtectActivity",
        // Xiaomi, Redmi, POCO: "Autostart" inside Security.
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        // OPPO, realme, and older OnePlus builds on ColorOS.
        "com.coloros.safecenter" to "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        "com.coloros.safecenter" to "com.coloros.safecenter.startupapp.StartupAppListActivity",
        "com.oppo.safe" to "com.oppo.safe.permission.startup.StartupAppListActivity",
        // vivo and iQOO.
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity",
        // Samsung: the battery page, where "Allowed to run in background" lives.
        "com.samsung.android.lool" to "com.samsung.android.sm.ui.battery.BatteryActivity",
        // Letv, Asus, Meizu - rarer, and cheap to carry.
        "com.letv.android.letvsafe" to "com.letv.android.letvsafe.AutobootManageActivity",
        "com.asus.mobilemanager" to "com.asus.mobilemanager.entry.FunctionActivity",
        "com.meizu.safe" to "com.meizu.safe.security.SHOW_APPSEC",
    )

    /** The first candidate this phone actually has, or null. */
    private fun candidate(context: Context): Intent? {
        val manager = context.packageManager
        for ((pkg, activity) in CANDIDATES) {
            val intent = Intent().setComponent(ComponentName(pkg, activity))
            val resolved = runCatching {
                manager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
            }.getOrNull()
            if (resolved != null) return intent
        }
        return null
    }

    /**
     * Whether there is a screen worth offering. False on a phone with no such
     * restriction, and the button is then not shown at all - an offer to fix
     * something that is not broken is worse than silence.
     */
    fun available(context: Context): Boolean = candidate(context) != null

    /**
     * Open it, or fall back to this app's own page in Settings.
     *
     * The fallback matters more than it looks: a resolvable component can still
     * refuse to start (not exported, or guarded by a permission), and that
     * throws at the moment of the tap rather than when it was offered.
     */
    fun open(context: Context) {
        candidate(context)?.let { intent ->
            val launched = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess
            if (launched) return
            Log.w(TAG, "autostart: ${intent.component?.className} would not open")
        }
        runCatching {
            context.startActivity(
                Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", context.packageName, null),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.w(TAG, "autostart: could not open any settings screen", it) }
    }

    /**
     * What to call it on this phone, because the screens have different names
     * and telling someone to look for "App launch" on a Xiaomi sends them
     * hunting. Falls back to the generic phrasing, which is what the phone's
     * own page in Settings will show them.
     */
    fun label(context: Context): String = when (candidate(context)?.component?.packageName) {
        "com.hihonor.systemmanager", "com.huawei.systemmanager" -> "App launch"
        "com.miui.securitycenter" -> "Autostart"
        "com.coloros.safecenter", "com.oppo.safe" -> "Startup manager"
        "com.vivo.permissionmanager", "com.iqoo.secure" -> "Background start-up"
        "com.samsung.android.lool" -> "Battery"
        else -> "the app's start-up settings"
    }
}
