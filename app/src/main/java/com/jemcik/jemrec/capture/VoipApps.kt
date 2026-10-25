package com.jemcik.jemrec.capture

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.edit
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

    /**
     * Whether messenger calls can be recorded on this phone at all: whether
     * the shell - the daemon's user - holds CAPTURE_VOICE_COMMUNICATION_OUTPUT.
     * The daemon copies call audio through an audio policy, and Android's audio
     * service refuses one that captures voice communication without it
     * (AudioService); nor does its audio engine copy call audio into a policy
     * registered without it (AudioPolicyMix). Stock Android gives the shell
     * that permission from Android 14 on (AOSP frameworks/base,
     * packages/Shell/AndroidManifest.xml: not in 12, 12L or 13). Asked of
     * Android rather than read off the version, so a build that grants it
     * earlier gets messenger calls - and one that does not gets no switches
     * that would record nothing, which 0.13 offered on Android 12 and 13.
     * Measured on Android 12's emulator, 2026-10-20: the policy refused, for
     * want of this and MODIFY_AUDIO_ROUTING. Without it nothing is offered,
     * and nothing is switched on whatever is stored.
     *
     * A permission the shell has does not change while this process runs, so
     * an answer is kept. A failure to ask is not an answer: it counts as yes,
     * as before 0.14, and is asked again next time - a "no" would disarm a
     * daemon that records messenger calls perfectly well.
     */
    fun supported(context: Context): Boolean {
        supportedHere?.let { return it }
        val granted = runCatching {
            context.packageManager.checkPermission(CAPTURE_CALL_AUDIO, SHELL) == PackageManager.PERMISSION_GRANTED
        }.getOrNull() ?: return true
        supportedHere = granted
        return granted
    }

    @Volatile private var supportedHere: Boolean? = null
    private const val CAPTURE_CALL_AUDIO = "android.permission.CAPTURE_VOICE_COMMUNICATION_OUTPUT"
    private const val SHELL = "com.android.shell"

    /** The known apps installed on this phone, in the list's order. */
    fun installed(context: Context): List<App> = KNOWN.filter { app ->
        runCatching { context.packageManager.getPackageInfo(app.pkg, 0); true }.getOrDefault(false)
    }

    /** The packages switched on. Only known ones, whatever is stored, and
     *  none where messenger calls cannot work ([supported]). */
    fun enabled(context: Context): Set<String> {
        if (!supported(context)) return emptySet()
        return (Prefs.of(context).getStringSet(KEY, emptySet()) ?: emptySet())
            .filter { pkg -> KNOWN.any { it.pkg == pkg } }
            .toSet()
    }

    fun setEnabled(context: Context, pkg: String, on: Boolean) {
        if (!supported(context) || KNOWN.none { it.pkg == pkg }) return
        val now = enabled(context).toMutableSet()
        if (on) now += pkg else now -= pkg
        Prefs.of(context).edit { putStringSet(KEY, now) }
    }

    /** For the daemon: "com.viber.voip,com.whatsapp", or "" for none.
     *  Package names only, so it is safe in a shell command line. */
    fun wire(context: Context): String = enabled(context).sorted().joinToString(",")
}
