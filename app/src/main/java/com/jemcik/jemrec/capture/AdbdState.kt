package com.jemcik.jemrec.capture

import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Whether adbd is running, from init's own note of each service it runs:
 * init.svc.adbd, "running" or "stopped" (or on its way between them).
 *
 * The switches cannot say it. After a restart of the Honor (MagicOS 11,
 * Android 17), 2026-10-28, with no cable, USB debugging and Wireless
 * debugging both read on and Android's AdbService believed both on, but
 * AdbDebuggingManager logged "adbd_auth domain socket unavailable" every
 * second - adbd was not running, and nothing was advertised. Switching
 * Wireless debugging off and on started it ("Enabling ADBd Wifi property",
 * adbd's socket connected half a second later).
 *
 * "" is a property never set - adbd not started this boot - or one this app
 * may not read. init.svc.zygote carries the same label
 * (init_service_status_prop, `getprop -Z`, on the Honor and the Android 12,
 * 13, 16 and 17 emulators, 2026-10-28, where this app read it "running"),
 * and zygote has run by the time any app does - every app is forked from
 * it - so a zygote read means adbd's "" is unset.
 */
object AdbdState {

    enum class State { RUNNING, STOPPED, UNKNOWN }

    fun read(): State = state(SystemProperty.read("init.svc.adbd")) { SystemProperty.read("init.svc.zygote") }

    /**
     * [first], or where [first] is stopped, what adbd is once it is not
     * within [waitMs]: a switch written a moment before has Android start
     * adbd by itself, a little later (CaptureDaemon's revive). Read again
     * every quarter second, by [read].
     */
    internal suspend fun settled(first: State, waitMs: Long, read: () -> State = ::read): State {
        if (first != State.STOPPED) return first
        return withTimeoutOrNull(waitMs) {
            var now = first
            while (now == State.STOPPED) {
                delay(250)
                now = read()
            }
            now
        } ?: first
    }

    /** Starting, restarting, stopping: on its way, and no reading of which
     *  side it lands on. */
    internal fun state(adbd: String?, zygote: () -> String?): State = when (adbd) {
        null -> State.UNKNOWN
        "running" -> State.RUNNING
        "stopped" -> State.STOPPED
        "" -> if (zygote().isNullOrEmpty()) State.UNKNOWN else State.STOPPED
        else -> State.UNKNOWN
    }
}
