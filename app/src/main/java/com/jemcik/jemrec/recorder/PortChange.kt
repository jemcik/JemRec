package com.jemcik.jemrec.recorder

import android.content.Context
import com.jemcik.jemrec.adb.AdbTransport
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
     * The ports adbd's classic door may be on, to connect over - only ones
     * adbd's own properties name, as a port dialled on a guess may be anyone's
     * (CallVault keeps the same rule, LoopbackPorts): the one the property
     * names, JemRec's or anyone's, alone - adbd then listens there and nowhere
     * else (AOSP daemon/main.cpp); where it reads empty, the one
     * persist.adb.tcp.port names, which adbd falls back on then (main.cpp;
     * set by some ROMs and rooted phones, which the old 5555 fallback reached
     * when it was 5555); where it cannot be read, the one JemRec asked for
     * this boot, and a 5555 that 1.10 opened this boot; and
     * 5555 with no port set only where adbd listens there by default, with no
     * USB: an emulator (StartWithoutWifi.adbdOnDefaultPort). On a phone with
     * no port set there is none. Every connect asks here: the revive's, and
     * setup's through autoConnect's default.
     */
    internal fun classicPorts(context: Context): List<Int> = classicPorts(
        configured = StartWithoutWifi.configuredPort(),
        persist = StartWithoutWifi.persistPort(),
        own = StartWithoutWifi.ownPort(context),
        asked = StartWithoutWifi.armedThisBoot(context),
        legacy = StartWithoutWifi.legacyOpenedThisBoot(context),
        defaultPort = StartWithoutWifi.adbdOnDefaultPort(),
    )

    internal fun classicPorts(
        configured: Int?,
        persist: Int?,
        own: Int?,
        asked: Boolean,
        legacy: Boolean,
        defaultPort: Boolean,
    ): List<Int> = when {
        configured != null && configured > 0 -> listOf(configured)
        configured == null -> listOfNotNull(
            persist?.takeIf { it > 0 },
            own.takeIf { asked },
            StartWithoutWifi.LEGACY_PORT.takeIf { legacy || defaultPort },
        ).distinct()
        else -> listOfNotNull(StartWithoutWifi.LEGACY_PORT.takeIf { defaultPort })
    }

    /**
     * Connect on Start without Wi-Fi's port, once adbd is up on it: On after
     * Turn all debugging off has only just switched USB debugging back on,
     * which is what starts adbd, and an adbd restarting answers a moment
     * before it can be connected to. [ports] most likely first: the first one
     * is waited for, then each tried in turn. Where none connects, the
     * failure says each port's own, as autoConnect's does
     * (AdbTransport.classicWords).
     */
    internal suspend fun connectOnPort(w: ReviveWorld, ports: List<Int> = w.classicPorts()): Result<Unit> {
        val first = ports.firstOrNull() ?: return Result.failure(IllegalStateException("no classic port to connect on"))
        withTimeoutOrNull(Revive.ADBD_SETTLE_MS) {
            while (!w.portAnswers(first)) delay(250)
        }
        var result = w.connect(first)
        repeat(PORT_RECONNECTS - 1) {
            if (result.isFailure) {
                delay(1_000)
                result = w.connect(first)
            }
        }
        val failed = mutableListOf<AdbTransport.ClassicTry>()
        result.exceptionOrNull()?.let { failed += AdbTransport.ClassicTry(first, wordsOf(it)) }
        for (port in ports.drop(1)) {
            if (result.isSuccess) break
            result = w.connect(port)
            result.exceptionOrNull()?.let { failed += AdbTransport.ClassicTry(port, wordsOf(it)) }
        }
        // One port tried: its failure as it came, the words it always had.
        if (result.isSuccess || failed.size < 2) return result
        return Result.failure(IllegalStateException(AdbTransport.classicWords(failed), result.exceptionOrNull()))
    }

    /** A connect's failure in words: AdbTransport.connect's own message, or
     *  what libadb's exception says under its wrapping (causeOf). */
    internal fun wordsOf(failure: Throwable): String =
        failure.message?.takeIf { failure is IllegalStateException } ?: AdbTransport.causeOf(failure)

    /**
     * Open or close Start without Wi-Fi's port. Opened is JemRec's own
     * number (StartWithoutWifi.ensureOwnPort), noted as asked for before the
     * ask; closed is whichever the property names, which the step found to
     * be JemRec's. adbd restarts with service.adb.tcp.port set or cleared,
     * the recorder going with it, and this connects again for the start that
     * follows: on the port once it listens, or over Wireless debugging, which
     * adbd advertises again a few seconds after it starts. An ask after which
     * the property never says JemRec's number - an adbd that does not keep it
     * - is noted for the rest of this boot (StartWithoutWifi), so the recorder
     * is not restarted for it at every revive.
     */
    internal suspend fun changePort(w: ReviveWorld, open: Boolean) {
        val port = if (open) w.ensureOwnPort() else w.classicPorts().firstOrNull() ?: w.ownPort() ?: StartWithoutWifi.LEGACY_PORT
        // Before the ask, so that an app killed before the read-back leaves
        // it noted - for a property that cannot be read; one that can says
        // whose it is by its number (StartWithoutWifi.isOurs).
        if (open) w.noteArmed()
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
                while (w.configuredPort().let { it != port && !(it == null && w.portAnswers(port)) }) {
                    delay(250)
                }
                true
            } == true
            if (!opened) w.noteOpenFailed()
            w.note("revive: port $port ${if (opened) "opened" else "not opened; not asked again this boot"}")
            if (opened && connectOnPort(w, listOf(port)).isSuccess) return
        } else {
            // A close that did not take - the ask lost on a connection that
            // had just died, or an adbd that keeps the port - leaves the port
            // JemRec's to show, not asked again this boot: each ask restarts
            // the recorder.
            val closed = closeTook(w, port)
            if (closed) w.noteClosed() else w.noteCloseFailed()
            w.note("revive: port $port ${if (closed) "closed" else "still set; not asked again this boot"}")
        }
        // Back over Wireless debugging, which adbd's restart can leave off:
        // on the Honor, 2026-10-28, it read 0 after one open of the port and
        // 1 after another. Switched on as a revive does - not where Android
        // refused it on this Wi-Fi, which would only ask. No note of whose it
        // is: on before the restart, by the revive's own switch-on (noted
        // then) or the user's, it stays whose it was (Revive.askWirelessOff).
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

    /** For Start fresh's close of the port: its session opened while
     *  JemRec's key still lets it in - before Android's pairing timeout is
     *  put back, which can delete the key at once (Setup.reset). Nothing to
     *  open where the port set is not JemRec's, or a session is open. */
    internal suspend fun openForReset(context: Context) {
        val w = ReviveWorld.Live(context)
        if (!StartWithoutWifi.isOurs(context) || w.connected) return
        w.connect(w.classicPorts().firstOrNull() ?: return)
    }

    /** Start fresh's close of the port (RecorderLifecycle.endForReset),
     *  under the recorder's lock: only JemRec's own. */
    internal suspend fun closeForReset(context: Context): Boolean {
        val w = ReviveWorld.Live(context)
        if (!StartWithoutWifi.isOurs(context)) return true
        val port = w.classicPorts().firstOrNull() ?: return false
        if (!w.connected && w.connect(port).isFailure) return false
        // Its answer is no guide: the restart can cut it off (restartAdbd).
        w.restartAdbd("usb:")
        w.close()
        delay(ADBD_RESTART_MS)
        val closed = closeTook(w, port)
        if (closed) w.noteClosed()
        return closed
    }

    /**
     * After `usb:`, whether [port] is closed: the property no longer naming a
     * port, waited for as long as adbd takes to come back - or, where the
     * property cannot be read, nothing answering on the port once it is
     * back. One reading for the revive's close and Start fresh's: they read
     * it two ways, an unreadable property closed for one and open for the
     * other.
     */
    private suspend fun closeTook(w: ReviveWorld, port: Int): Boolean = withTimeoutOrNull(Revive.ADBD_SETTLE_MS) {
        while (w.portSet().let { it == true || (it == null && w.portAnswers(port)) }) delay(250)
    } != null

    /**
     * Start without Wi-Fi's port change to make now, or null. Changing it
     * restarts adbd and with it the recorder, which then has to be started
     * again at once, so only: set up, on Wi-Fi, out of a call - and not
     * while When JemRec is off has debugging switched off, which a revive
     * would have to switch Wireless debugging on for, and leave on if it then
     * failed; the On puts debugging back, and the change is made after it.
     */
    internal fun portChangeDue(
        context: Context,
        onWifi: Boolean = NetworkRevive.onWifi(context),
    ): StartWithoutWifi.PortStep? = portChangeDue(ReviveWorld.Live(context), onWifi)

    internal fun portChangeDue(w: ReviveWorld, onWifi: Boolean): StartWithoutWifi.PortStep? {
        if (!onWifi || !w.setUp() || w.inCall()) return null
        if (w.switchedOff()) return null
        // Nor where Android refused Wireless debugging on this Wi-Fi and it is
        // still off: switching it on for the port would only ask again.
        if (w.stillRefusedHere(Revive.wirelessDebuggingOn(w))) return null
        return w.portStep().takeIf { it != StartWithoutWifi.PortStep.LEAVE }
    }

    /**
     * [change], made over a connection on [connectedPort] - unless it is a
     * close and that connection came over the classic door ([classic]).
     * autoConnect falls back on it where Wireless debugging cannot be had,
     * on a Wi-Fi Android has not allowed it on, and adbd restarted without
     * the port, the recorder going with it, would leave nothing to start it
     * again over until another Wi-Fi. The close waits for a revive over
     * Wireless debugging.
     */
    internal fun portChangeOver(
        change: StartWithoutWifi.PortStep?,
        connectedPort: Int,
        classic: List<Int>,
    ): StartWithoutWifi.PortStep? =
        change.takeUnless { it == StartWithoutWifi.PortStep.CLOSE && connectedPort in classic }
}
