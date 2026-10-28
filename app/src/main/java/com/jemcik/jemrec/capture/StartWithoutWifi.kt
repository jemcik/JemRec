package com.jemcik.jemrec.capture

import android.content.Context
import android.provider.Settings
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.adb.AdbTransport
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Settings, Debugging, Start without Wi-Fi (issue #37): the recorder started
 * with no Wi-Fi at all, over adbd's classic port, 5555.
 *
 * Starting the recorder takes an adb connection, and the only one this phone
 * offers an app on its own is Wireless debugging, which Android keeps to a
 * Wi-Fi network the phone has joined - "Not connected to any wireless network.
 * Not enabling adbwifi." (AdbDebuggingManager); the phone's own hotspot does
 * not count. So "Turn all debugging off" could only be turned back on over
 * Wi-Fi, and someone travelling often has none.
 *
 * adbd has another door, the one `adb tcpip` opens: its `tcpip:5555` service
 * sets service.adb.tcp.port and restarts adbd, and from then on every start
 * of adbd listens on 5555 too - on every interface, loopback included, Wi-Fi
 * or not - until a reboot clears the property. Only init, adbd and
 * system_server may set it (sepolicy), so JemRec asks adbd, over Wireless
 * debugging, once each boot. Measured on the Honor (MagicOS 11, Android 17),
 * 2026-10-27, with Wi-Fi off: Turn all debugging off and back on brought adbd
 * up on 5555 with the property kept, adbd took this app's paired key there
 * without asking, and the recorder started over it.
 *
 * What it costs, and why it is a switch of its own, off until turned on:
 * 5555 is open on every network the phone joins whenever adbd runs - that is,
 * whenever USB debugging is on: with JemRec on, and with it off unless Turn
 * all debugging off goes with it (Hide Developer options keeps USB debugging,
 * and so the port). adbd can be kept to loopback only through
 * service.adb.listen_addrs, which only system_server may set. A computer that
 * reaches the port - or any app on the phone, at 127.0.0.1 - still needs the
 * phone to accept its key, but it can put the question on screen, which
 * Wireless debugging, with its pairing, never did. And adbd announces the
 * port over mDNS on every network, as `_adb._tcp` named after the phone's
 * serial number (AOSP daemon/mdns.cpp; seen from a Mac on the Honor's
 * network, 2026-10-28, gone again once closed) - Wireless debugging announces
 * that name too, but only on a Wi-Fi it is allowed on. With all debugging
 * off, adbd is stopped and nothing listens.
 */
object StartWithoutWifi {

    private const val KEY_ON = "start_without_wifi"

    /** The boot (Settings.Global.BOOT_COUNT) in which JemRec opened the port.
     *  Only that port is closed again: one a computer opened with `adb tcpip`
     *  is the computer's. */
    private const val KEY_OPENED_BOOT = "port_opened_boot"

    /** The boot in which asking adbd for the port left nothing listening. Not
     *  asked again until the next: every ask restarts adbd, and the recorder
     *  with it. */
    private const val KEY_FAILED_BOOT = "port_failed_boot"

    /** The boot in which asking adbd to close the port left it set. Its own
     *  note, not [KEY_FAILED_BOOT]: a close that did not take says nothing
     *  about an open, which the switch turned on again may still ask for. */
    private const val KEY_CLOSE_FAILED_BOOT = "port_close_failed_boot"

    const val PORT = AdbTransport.LEGACY_TCP_PORT

    /** By its full path, not looked up on PATH (CodeQL, code-scanning alert
     *  67): Android's own, in every build. */
    private const val GETPROP = "/system/bin/getprop"
    private const val GETPROP_MS = 2_000L
    private const val EXIT_POLL_MS = 2L
    private const val PROBE_MS = 200

    fun enabled(context: Context): Boolean = Prefs.of(context).getBoolean(KEY_ON, false)

    fun setEnabled(context: Context, on: Boolean) {
        Prefs.of(context).edit { putBoolean(KEY_ON, on) }
    }

    /**
     * The port adbd listens on whenever it starts: service.adb.tcp.port, 0
     * for none. Read with getprop, since android.os.SystemProperties is not
     * for apps (see DeviceSkin) and this property is readable by them -
     * `get_prop(appdomain, adbd_config_prop)`, sepolicy's app.te. It is what
     * says whether On after Turn all debugging off can come back without
     * Wi-Fi, which asking the port cannot: adbd is stopped then.
     *
     * Null when it cannot be told: getprop prints the same empty line for a
     * property never set as for one this app may not read, so an empty
     * answer is not taken for "no port" - a build that denied apps the read
     * would otherwise never see the port it opened ([ready] falls back on
     * what this boot did). Run on every revive and every screen refresh, so
     * the process and its pipes are let go every time.
     *
     * Its end is looked for every 2 ms, not with waitFor(timeout), which
     * looks every 100: each read took 102 ms that way, on the Honor,
     * 2026-10-28, eight reads in a row, for a getprop done in a few.
     */
    fun configuredPort(): Int? {
        val process = runCatching {
            ProcessBuilder(GETPROP, "service.adb.tcp.port").redirectErrorStream(true).start()
        }.getOrNull() ?: return null
        return try {
            val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(GETPROP_MS)
            while (process.isAlive) {
                if (System.nanoTime() > deadline) return null
                Thread.sleep(EXIT_POLL_MS)
            }
            parsePort(process.inputStream.bufferedReader().use { it.readText() })
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        } catch (_: Exception) {
            null
        } finally {
            process.destroy()
        }
    }

    /** getprop prints the value, or an empty line for a property never set
     *  or not readable - null, as is anything that is not a number. */
    internal fun parsePort(text: String): Int? = text.trim().toIntOrNull()

    /** Whether the property says adbd will be on 5555 whenever it is up, until
     *  a reboot: opened by JemRec or by a computer. Null if it cannot be told. */
    fun portSet(): Boolean? = configuredPort()?.let { it == PORT }

    /**
     * Whether adbd will be on 5555 whenever it is up - what lets the recorder
     * start with no Wi-Fi (CaptureDaemon's revive). The property, where it
     * can be read; where it cannot, whether JemRec opened the port this boot.
     * The screens and the question before turning off say [readyForUser].
     */
    fun ready(context: Context): Boolean = portSet() ?: openedThisBoot(context)

    /**
     * [ready], as the screens and the question before turning off say it.
     * Where the property cannot be read and adbd runs - USB debugging on -
     * the port answering is what says it: a computer's `adb usb` closes it
     * unseen otherwise, and turning all debugging off away from Wi-Fi would
     * go ahead without its question, with nothing to come back on over. Not
     * for the revive, which waits for an adbd only just starting
     * (CaptureDaemon.connectOnPort). [answers] is for the tests: the JVM's
     * loopback is no phone's.
     */
    fun readyForUser(context: Context, answers: () -> Boolean = ::answers): Boolean =
        portSet() ?: (
            openedThisBoot(context) &&
                (!GlobalSettings.isOn(context, GlobalSettings.ADB_ENABLED) || answers())
            )

    /** Whether anything answers on 127.0.0.1:5555 now - adbd, once it is up. */
    fun answers(): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(AdbTransport.LOOPBACK, PORT), PROBE_MS) }
        true
    }.getOrDefault(false)

    private fun boot(context: Context): Int =
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)

    private fun inThisBoot(context: Context, key: String): Boolean {
        val boot = boot(context)
        return boot >= 0 && Prefs.of(context).getInt(key, -1) == boot
    }

    /** Asked for this boot, and the property never said 5555 - nor, where it
     *  cannot be read, the port answer: the switch says so. */
    fun failedThisBoot(context: Context): Boolean = inThisBoot(context, KEY_FAILED_BOOT)

    /** Asked to close this boot, and the property still said 5555. */
    fun closeFailedThisBoot(context: Context): Boolean = inThisBoot(context, KEY_CLOSE_FAILED_BOOT)

    /** JemRec opened the port this boot - so it is JemRec's to close. */
    internal fun openedThisBoot(context: Context): Boolean = inThisBoot(context, KEY_OPENED_BOOT)

    /** Start fresh: the switch off and the notes gone (Setup.reset, which
     *  closes the port first if JemRec opened it) - all but [stillOpen]'s:
     *  a port the reset could not close stays JemRec's to close, once it is
     *  set up again and on Wi-Fi. */
    fun forget(context: Context, stillOpen: Boolean) {
        Prefs.of(context).edit {
            remove(KEY_ON)
            if (!stillOpen) remove(KEY_OPENED_BOOT)
            remove(KEY_FAILED_BOOT)
            remove(KEY_CLOSE_FAILED_BOOT)
        }
    }

    internal fun noteOpened(context: Context, opened: Boolean) {
        val key = if (opened) KEY_OPENED_BOOT else KEY_FAILED_BOOT
        Prefs.of(context).edit { putInt(key, boot(context)) }
    }

    internal fun noteClosed(context: Context) {
        Prefs.of(context).edit { remove(KEY_OPENED_BOOT) }
    }

    /** Asked to close, and the property still says 5555: still JemRec's to
     *  say open, not asked again this boot (KEY_CLOSE_FAILED_BOOT). */
    internal fun noteCloseFailed(context: Context) {
        Prefs.of(context).edit { putInt(KEY_CLOSE_FAILED_BOOT, boot(context)) }
    }

    /** What a revive does about the port before it starts the recorder. */
    internal enum class PortStep { OPEN, CLOSE, LEAVE }

    /**
     * Open it when the switch is on and it is not - or cannot be read and was
     * not opened this boot - unless an open [failed] this boot; close it
     * when the switch is off and it is the one JemRec opened, unless the
     * property says it is closed already, or a close failed this boot
     * ([closeFailed]); otherwise leave it. Either change
     * restarts adbd, so the caller makes it only where the recorder can be
     * started again at once (CaptureDaemon.portChangeDue).
     */
    internal fun portStep(
        wanted: Boolean,
        set: Boolean?,
        openedByUs: Boolean,
        failed: Boolean,
        closeFailed: Boolean = false,
    ): PortStep = when {
        wanted && !failed && (set == false || (set == null && !openedByUs)) -> PortStep.OPEN
        !wanted && openedByUs && !closeFailed && set != false -> PortStep.CLOSE
        else -> PortStep.LEAVE
    }

    /** [portStep] here and now. With the switch off and nothing opened this
     *  boot there is nothing to read: no getprop, as on most revives. */
    internal fun step(context: Context): PortStep {
        val wanted = enabled(context)
        val ours = openedThisBoot(context)
        if (!wanted && !ours) return PortStep.LEAVE
        return portStep(wanted, portSet(), ours, failedThisBoot(context), closeFailedThisBoot(context))
    }
}
