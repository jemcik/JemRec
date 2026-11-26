package com.jemcik.jemrec.ui

import android.content.Context
import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.setup.Setup
import com.jemcik.jemrec.setup.SetupStep
import com.jemcik.jemrec.system.GlobalSettings
import com.jemcik.jemrec.system.ThisPhone

/**
 * Copy a report, on setup's cards that can fail: this phone, where setup
 * stands, the two switches, and how the last connects went
 * (AdbTransport.attempts). Until 1.9 setup had none - Settings, where the
 * other reports are, opens only once setup is done - and #140 came with
 * an empty report, from a phone that paired and never connected. Like the
 * call report, nothing personal: no address, no network name, no service
 * name (adbd's carries the serial number), only ports and Android's words.
 */
internal object SetupReport {

    fun text(context: Context, appVersion: String, state: UiState): String = text(
        appVersion = appVersion,
        phone = ThisPhone.phone,
        android = ThisPhone.android,
        step = state.step,
        paired = Setup.hasPaired(context),
        usb = state.usbDebugging,
        wireless = state.wirelessDebuggingOn,
        onWifi = state.onWifi,
        finishFailed = state.finishFailed,
        attempts = AdbTransport.attempts(),
        nowMs = System.currentTimeMillis(),
    )

    /** Pure: the report from what was read. */
    internal fun text(
        appVersion: String,
        phone: String,
        android: String,
        step: SetupStep,
        paired: Boolean,
        usb: GlobalSettings.Reading,
        wireless: Boolean,
        onWifi: Boolean,
        finishFailed: String?,
        attempts: List<AdbTransport.Attempt>,
        nowMs: Long,
    ): String = buildString {
        appendLine("JemRec $appVersion - setup report")
        appendLine(ThisPhone.line(phone, android))
        appendLine("Step: ${step.name}" + if (paired) ", paired" else ", not paired")
        appendLine(
            "Switches: USB debugging " + when (usb) {
                GlobalSettings.Reading.ON -> "on"
                GlobalSettings.Reading.OFF -> "off"
                GlobalSettings.Reading.UNKNOWN -> "hidden by Android"
            } + ", Wireless debugging " + (if (wireless) "on" else "off") +
                "; Wi-Fi " + (if (onWifi) "on" else "off"),
        )
        finishFailed?.let { appendLine("Last step failed: $it") }
        if (attempts.isEmpty()) {
            append("Connects: none yet")
        } else {
            appendLine("Connects, newest first:")
            attempts.forEach { a ->
                val ago = ((nowMs - a.atMs) / 1000).coerceAtLeast(0)
                appendLine("- $ago s ago: ${a.line()}")
            }
        }
    }.trimEnd()
}
