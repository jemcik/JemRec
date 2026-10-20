package com.jemcik.jemrec.capture

import android.content.Context
import com.jemcik.jemrec.Prefs

/**
 * Messenger calls: which apps' calls are recorded. None until the user turns
 * one on, app by app.
 *
 * The recording itself is the daemon's (shellserver Voip.java): one audio
 * policy that copies any app's voice-call audio, so nothing here is specific to
 * an app except its name. The list below is only what Settings offers - the
 * apps people asked for - and a package not on it is never recorded.
 *
 * Each app has a short TAG for file names - 20261020_203501_viber.ogg - so a
 * recording says which app it came from in any file manager, and the list can
 * say so too (FileNames.parse).
 *
 * Tested on an Honor (MagicOS 11 / Android 17) with Viber, Telegram and
 * WhatsApp: both sides, and the other person heard the user throughout.
 * Signal uses the same path; an app can still mark its audio as not to be
 * captured, which Android honours - the recording then has the user's side
 * only.
 */
object VoipApps {

    private const val KEY = "voip_apps"

    data class App(val pkg: String, val label: String, val tag: String)

    val KNOWN = listOf(
        App("com.whatsapp", "WhatsApp", "whatsapp"),
        App("com.whatsapp.w4b", "WhatsApp Business", "whatsapp"),
        App("org.telegram.messenger", "Telegram", "telegram"),
        App("org.thunderdog.challegram", "Telegram X", "telegram"),
        App("org.thoughtcrime.securesms", "Signal", "signal"),
        App("com.viber.voip", "Viber", "viber"),
    )

    /** For a package not on the list, which should never be recording - but
     *  a file the daemon made is saved whatever it is. */
    private val OTHER = App("", "Messenger", "voip")

    /** The tags a file name can carry, for reading names back. */
    internal val TAGS: Set<String> = (KNOWN + OTHER).map { it.tag }.toSet()

    fun byPackage(pkg: String?): App? = KNOWN.firstOrNull { it.pkg == pkg }

    /** The app a daemon file came from, if it is a messenger call. */
    internal fun forRecfile(recfile: String): App? {
        val pkg = recfile.substringAfter("_voip-", "").removeSuffix(".dat").ifEmpty { return null }
        return byPackage(pkg) ?: OTHER.copy(pkg = pkg)
    }

    /** "Viber", for a name's tag. */
    fun labelForTag(tag: String?): String? = (KNOWN + OTHER).firstOrNull { it.tag == tag }?.label

    /** The known apps installed on this phone, in the list's order. */
    fun installed(context: Context): List<App> = KNOWN.filter { app ->
        runCatching { context.packageManager.getPackageInfo(app.pkg, 0); true }.getOrDefault(false)
    }

    /** The packages switched on. Only known ones, whatever is stored. */
    fun enabled(context: Context): Set<String> =
        (Prefs.of(context).getStringSet(KEY, emptySet()) ?: emptySet())
            .filter { pkg -> KNOWN.any { it.pkg == pkg } }
            .toSet()

    fun setEnabled(context: Context, pkg: String, on: Boolean) {
        if (KNOWN.none { it.pkg == pkg }) return
        val now = enabled(context).toMutableSet()
        if (on) now += pkg else now -= pkg
        Prefs.of(context).edit().putStringSet(KEY, now).apply()
    }

    /** For the daemon: "com.viber.voip,com.whatsapp", or "" for none.
     *  Package names only, so it is safe in a shell command line. */
    fun wire(context: Context): String = enabled(context).sorted().joinToString(",")
}
