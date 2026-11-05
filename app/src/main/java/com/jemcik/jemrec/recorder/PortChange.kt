package com.jemcik.jemrec.recorder

import android.content.Context
import com.jemcik.jemrec.system.GlobalSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Start without Wi-Fi's port (StartWithoutWifi), opened or closed by a
 * revive: when a change is due, adbd told to change it and read back, and
 * the connect after its restart. Start fresh's close is here too.
 */
object PortChange {

    /** adbd's own restart, when it is told to change its port: gone, then
     *  started again by init. */
    private const val ADBD_RESTART_MS = 1_000L

    /** Connects tried after adbd restarts, a second or more apart. */
    private const val PORT_RECONNECTS = 3

    /**
     * Connect on Start without Wi-Fi's port, once adbd is up on it: On after
     * Turn all debugging off has only just switched USB debugging back on,
     * which is what starts adbd, and an adbd restarting answers a moment
     * before it can be connected to.
     */
    internal suspend fun connectOnPort(w: ReviveWorld): Result<Unit> {
        withTimeoutOrNull(Revive.ADBD_SETTLE_MS) {
            while (!w.portAnswers()) delay(250)
        }
        var result = w.connect(StartWithoutWifi.PORT)
        repeat(PORT_RECONNECTS - 1) {
            if (result.isFailure) {
                delay(1_000)
                result = w.connect(StartWithoutWifi.PORT)
            }
        }
        return result
    }

    /**
     * Open or close Start without Wi-Fi's port. adbd restarts with
     * service.adb.tcp.port set or cleared, the recorder going with it, and
     * this connects again for the start that follows: on the port once it
     * listens, or over Wireless debugging, which adbd advertises again a few
     * seconds after it starts. An ask after which the property never says
     * 5555 - an adbd that does not keep it - is noted for the rest of this
     * boot (StartWithoutWifi), so the recorder is not restarted for it at
     * every revive.
     */
    internal suspend fun changePort(w: ReviveWorld, open: Boolean) {
        val port = StartWithoutWifi.PORT
        // Whatever adbd answers: the restart can cut its line off (restartAdbd),
        // and what was asked for is read back from the property below.
        val reply = w.restartAdbd(if (open) "tcpip:$port" else "usb:")
        w.note(
            "revive: ${if (open) "opening" else "closing"} port $port, adbd said " +
                reply.getOrElse { "nothing, the restart cut it off (${it.message})" },
        )
        w.close()
        // adbd exits and init starts it again: gone a moment, then back.
        delay(ADBD_RESTART_MS)
        if (open) {
            // Opened is the property saying so; adbd answering on the port is
            // what connectOnPort waits for, and a slow one is not a failure.
            // Where the property cannot be read, the port answering is all
            // there is to go on.
            val opened = withTimeoutOrNull(Revive.ADBD_SETTLE_MS) {
                while (w.portSet().let { it != true && !(it == null && w.portAnswers()) }) {
                    delay(250)
                }
                true
            } == true
            w.noteOpened(opened)
            w.note("revive: port $port ${if (opened) "opened" else "not opened; not asked again this boot"}")
            if (opened && connectOnPort(w).isSuccess) return
        } else {
            // A close that did not take - the ask lost on a connection that
            // had just died, or an adbd that keeps the port - leaves the port
            // JemRec's to show, not asked again this boot: each ask restarts
            // the recorder.
            val closed = closeTook(w)
            if (closed) w.noteClosed() else w.noteCloseFailed()
            w.note("revive: port $port ${if (closed) "closed" else "still set; not asked again this boot"}")
        }
        // Back over Wireless debugging, which adbd's restart can leave off:
        // on the Honor, 2026-10-28, it read 0 after one open of the port and
        // 1 after another. Switched on as a revive does - not where Android
        // refused it on this Wi-Fi, which would only ask.
        if (!Revive.wirelessDebuggingOn(w) && !w.refusedHere() &&
            w.write(GlobalSettings.ADB_WIFI_ENABLED, true)
        ) {
            w.note("revive: Wireless debugging off after adbd's restart; switched on")
            delay(Revive.ADBD_SETTLE_MS)
        }
        repeat(PORT_RECONNECTS) {
            if (w.autoConnect(Revive.CONNECT_MS).isSuccess) return
        }
    }

    /** Start fresh's close of the port (RecorderLifecycle.endForReset),
     *  under the recorder's lock. */
    internal suspend fun closeForReset(context: Context): Boolean {
        val w = ReviveWorld.Live(context)
        if (!StartWithoutWifi.openedThisBoot(context) || w.portSet() == false) return true
        if (!w.connected && w.connect(StartWithoutWifi.PORT).isFailure) return false
        // Its answer is no guide: the restart can cut it off (restartAdbd).
        w.restartAdbd("usb:")
        w.close()
        delay(ADBD_RESTART_MS)
        val closed = closeTook(w)
        if (closed) w.noteClosed()
        return closed
    }

    /**
     * After `usb:`, whether the port is closed: the property no longer saying
     * 5555, waited for as long as adbd takes to come back - or, where the
     * property cannot be read, nothing answering on the port once it is
     * back. One reading for the revive's close and Start fresh's: they read
     * it two ways, an unreadable property closed for one and open for the
     * other.
     */
    private suspend fun closeTook(w: ReviveWorld): Boolean = withTimeoutOrNull(Revive.ADBD_SETTLE_MS) {
        while (w.portSet().let { it == true || (it == null && w.portAnswers()) }) delay(250)
    } != null

    /**
     * Start without Wi-Fi's port change to make now, or null. Changing it
     * restarts adbd and with it the recorder, which then has to be started
     * again at once, so only: set up, on Wi-Fi, out of a call - and not
     * while When JemRec is off has debugging switched off, which a revive
     * would have to switch Wireless debugging on for, and leave on if it then
     * failed; the On puts debugging back, and the change is made after it.
     * Shared with the switch's own revive (MainViewModel), which has read
     * the [step] already.
     */
    internal fun portChangeDue(
        context: Context,
        onWifi: Boolean = NetworkRevive.onWifi(context),
        step: StartWithoutWifi.PortStep? = null,
    ): StartWithoutWifi.PortStep? = portChangeDue(ReviveWorld.Live(context), onWifi, step)

    internal fun portChangeDue(w: ReviveWorld, onWifi: Boolean, step: StartWithoutWifi.PortStep? = null): StartWithoutWifi.PortStep? {
        if (!onWifi || !w.setUp() || w.inCall()) return null
        if (w.switchedOff()) return null
        // Nor where Android refused Wireless debugging on this Wi-Fi and it is
        // still off: switching it on for the port would only ask again.
        if (w.stillRefusedHere(Revive.wirelessDebuggingOn(w))) return null
        return (step ?: w.portStep()).takeIf { it != StartWithoutWifi.PortStep.LEAVE }
    }

    /**
     * [change], made over a connection on [connectedPort] - unless it is a
     * close of the port that connection came over. autoConnect falls back on
     * 5555 where Wireless debugging cannot be had, on a Wi-Fi Android has not
     * allowed it on, and adbd restarted without the port, the recorder going
     * with it, would leave nothing to start it again over until another
     * Wi-Fi. The close waits for a revive over Wireless debugging.
     */
    internal fun portChangeOver(change: StartWithoutWifi.PortStep?, connectedPort: Int): StartWithoutWifi.PortStep? =
        change.takeUnless { it == StartWithoutWifi.PortStep.CLOSE && connectedPort == StartWithoutWifi.PORT }
}
