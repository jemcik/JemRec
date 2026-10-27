package com.jemcik.jemrec.capture

import android.content.Context
import android.os.Build

/**
 * Copy a report, on Diagnostics: this phone, the settings that decide how a
 * call is recorded, and the recorder's own account of the last call it
 * recorded (the daemon's CallReport) - which way it was recorded, how loud
 * each side came out, what was playing. For a report on GitHub about a call
 * that came out wrong on a phone the developer does not have: a Galaxy M34's
 * Wi-Fi call came out with one side (issue #37), and nothing on the phone
 * could say why. Nothing about calls or people goes in: the recorder's part
 * names no number and no one, and this part only the phone and its settings.
 */
object LastCallReport {

    fun text(context: Context, appVersion: String, recorder: String?): String = text(
        appVersion = appVersion,
        phone = "${Build.MANUFACTURER} ${Build.MODEL}",
        android = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), build ${Build.DISPLAY}",
        messengerApps = VoipApps.enabled(context).sorted(),
        tracking = CallTracking.enabled(context),
        answering = AnsweringMachine.enabled(context),
        recorder = recorder,
    )

    /** Pure: the report from what was read. [recorder]: the recorder's
     *  part, null when it could not be had. */
    internal fun text(
        appVersion: String,
        phone: String,
        android: String,
        messengerApps: List<String>,
        tracking: Boolean,
        answering: Boolean,
        recorder: String?,
    ): String = buildString {
        appendLine("JemRec $appVersion - call report")
        appendLine("Phone: $phone, $android")
        appendLine(
            "Settings: messenger calls " + (messengerApps.joinToString(", ").ifEmpty { "off" }) +
                "; exact call tracking " + (if (tracking) "on" else "off") +
                "; answering machine " + (if (answering) "on" else "off"),
        )
        append(
            recorder ?: "Recorder: could not be asked - it is not running, or it is older than this app.",
        )
    }.trimEnd()
}
