package com.jemcik.jemrec.recorder

import android.content.Context
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.system.GlobalSettings
import com.jemcik.jemrec.system.SystemProperty
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * Settings, Debugging, Start without Wi-Fi (issue #37): the recorder started
 * with no Wi-Fi at all, over a TCP port of adbd's.
 *
 * Starting the recorder takes an adb connection, and the only one this phone
 * offers an app on its own is Wireless debugging, which Android keeps to a
 * Wi-Fi network the phone has joined - "Not connected to any wireless network.
 * Not enabling adbwifi." (AdbDebuggingManager); the phone's own hotspot does
 * not count. So "Turn all debugging off" could only be turned back on over
 * Wi-Fi, and someone travelling often has none.
 *
 * adbd has another door, the one `adb tcpip` opens: its `tcpip:N` service
 * sets service.adb.tcp.port and restarts adbd, and from then on every start
 * of adbd listens on N too - on every interface, loopback included, Wi-Fi or
 * not - until a reboot clears the property (AOSP daemon/restart_service.cpp,
 * daemon/main.cpp). Only init, adbd and system_server may set it (sepolicy),
 * so JemRec asks adbd, over Wireless debugging, once each boot. Measured on
 * the Honor (MagicOS 11, Android 17), 2026-10-27, with Wi-Fi off: Turn all
 * debugging off and back on brought adbd up on the port with the property
 * kept, adbd took this app's paired key there without asking, and the
 * recorder started over it.
 *
 * Which number: one of JemRec's own ([ownPort]), picked once for this phone,
 * since 1.11 - 5555 before. 5555 is what a computer's `adb tcpip` and
 * Tasker's ADB Wifi open too, so the number said nothing of whose port it
 * was, and JemRec kept a note per boot instead, which was wrong when the app
 * died before writing it or when a computer closed the port and opened it
 * again (1.10's review). Now the number says it ([isOurs]): JemRec closes only
 * its own, and a port anyone else opened - 5555 or Tasker's own number - is
 * used as it is, never moved or closed. CallVault and Stellar pick a number of
 * their own the same way. adbd listens on one TCP port at a time, whoever
 * asked last. Measured on the Honor, the S20 (One UI 5.1) and the OnePlus
 * (LineageOS 23.2), 2026-11-27: `tcpip:23456` set the property, adbd
 * listened on 23456 alone, and took a key there.
 *
 * What it costs, and why it is a switch of its own, off until turned on:
 * the port is open on every network the phone joins whenever adbd runs - that
 * is, whenever USB debugging is on: with JemRec on, and with it off unless
 * Turn all debugging off goes with it (Hide Developer options keeps USB
 * debugging, and so the port). adbd can be kept to loopback only through
 * service.adb.listen_addrs, which only system_server may set. A computer that
 * reaches the port - or any app on the phone, at 127.0.0.1 - still needs the
 * phone to accept its key, but it can put the question on screen, which
 * Wireless debugging, with its pairing, never did; a number of its own is not
 * the one probes for open debugging ports try. adbd announces the port over
 * mDNS on every network, as `_adb._tcp` named after the phone's serial
 * number (AOSP daemon/mdns.cpp; seen from a Mac on the Honor's network,
 * 2026-10-28, gone again once closed) - Wireless debugging announces that
 * name too, but only on a Wi-Fi it is allowed on. With all debugging off,
 * adbd is stopped and nothing listens.
 */
object StartWithoutWifi {

    private const val KEY_ON = "start_without_wifi"

    /** JemRec's own port number ([ownPort]): picked once, kept until the app's
     *  data is cleared - Start fresh keeps it, being no secret. */
    private const val KEY_OWN_PORT = "own_port"

    /** The boot (Settings.Global.BOOT_COUNT) in which JemRec asked adbd for
     *  its own port, noted before the ask. Whose port is open is said by its
     *  number ([isOurs]); this note is for a property that cannot be read. */
    private const val KEY_ARMED_BOOT = "own_port_armed_boot"

    /** 1.10 and before: the boot in which JemRec opened 5555. Read, never
     *  written since 1.11: a 5555 JemRec opened before the update is still
     *  its own to close, until the restart that clears it. */
    private const val KEY_OPENED_BOOT = "port_opened_boot"

    /** The boot in which asking adbd for the port left nothing listening. Not
     *  asked again until the next: every ask restarts adbd, and the recorder
     *  with it. */
    private const val KEY_FAILED_BOOT = "port_failed_boot"

    /** The boot in which asking adbd to close the port left it set. Its own
     *  note, not [KEY_FAILED_BOOT]: a close that did not take says nothing
     *  about an open, which the switch turned on again may still ask for. */
    private const val KEY_CLOSE_FAILED_BOOT = "port_close_failed_boot"

    /** adbd's classic port: what a computer's `adb tcpip` opens, what JemRec
     *  opened before 1.11, and what an emulator's adbd listens on when no
     *  port is set (daemon/main.cpp: no USB, the default port). */
    const val LEGACY_PORT = AdbTransport.LEGACY_TCP_PORT

    /**
     * Where JemRec's number is picked from: IANA's dynamic range, which no
     * service is assigned in (RFC 6335 section 6) - a number below it can be
     * a registered port a phone already runs, Syncthing's 22000 for one, and
     * then JemRec's port could never open (review round 3 of 1.11) - and in
     * it above the phones' ephemeral range, 32768-60999 on the Honor and the
     * Android 16 AVD (ip_local_port_range, 2026-11-27), so that no outgoing
     * connection holds it, and above the ports the Honor reserves (up to
     * 50061). The recorder's 28472 is far below. CallVault picks in
     * 47000-59999, inside that ephemeral range; Stellar in 1000-9999.
     */
    internal val OWN_PORTS = 61_000..65_535

    /** Numbers tried before one nothing answers on is given up looking for. */
    private const val PICK_TRIES = 20

    private const val PROBE_MS = 200

    fun enabled(context: Context): Boolean = Prefs.of(context).getBoolean(KEY_ON, false)

    fun setEnabled(context: Context, on: Boolean) {
        Prefs.of(context).edit { putBoolean(KEY_ON, on) }
    }

    /** JemRec's own port, if one was picked: null until the first arm. */
    fun ownPort(context: Context): Int? = Prefs.of(context).getInt(KEY_OWN_PORT, 0).takeIf { it in OWN_PORTS }

    /** [ownPort], picked now if there is none - on the revive's way to arming
     *  it (PortChange), never on a screen's read. On disk at once: the number
     *  is what says the port is JemRec's once it is open. */
    internal fun ensureOwnPort(context: Context, answersOn: (Int) -> Boolean = ::answers): Int =
        ownPort(context) ?: pickPort(answersOn = answersOn).also { port ->
            Prefs.of(context).edit(commit = true) { putInt(KEY_OWN_PORT, port) }
        }

    /** A number in [OWN_PORTS] that nothing answers on now - or, if every
     *  try was taken, the last one tried: something answering now may be gone
     *  by the arm. */
    internal fun pickPort(
        random: (IntRange) -> Int = ::secureRandomIn,
        answersOn: (Int) -> Boolean,
    ): Int {
        var last = OWN_PORTS.first
        repeat(PICK_TRIES) {
            val port = random(OWN_PORTS)
            if (!answersOn(port)) return port
            last = port
        }
        return last
    }

    private fun secureRandomIn(range: IntRange): Int = range.first + SecureRandom().nextInt(range.last - range.first + 1)

    /**
     * The port adbd listens on whenever it starts: service.adb.tcp.port, 0
     * for none. Read with getprop (SystemProperty), and this property is
     * readable by apps - `get_prop(appdomain, adbd_config_prop)`, sepolicy's
     * app.te. It is what says whether On after Turn all debugging off can
     * come back without Wi-Fi, which asking the port cannot: adbd is stopped
     * then.
     *
     * Null when it cannot be told: an empty answer is not taken for "no
     * port" - a build that denied apps the read would otherwise never see
     * the port it opened ([ready] falls back on what this boot did).
     */
    fun configuredPort(): Int? = readProperty("service.adb.tcp.port")?.let(::parsePort)

    /** getprop, for the port's properties: a seam the tests set - the JVM
     *  has no getprop - which also forgets [adbdOnDefaultPort]'s answer. */
    internal var readProperty: (String) -> String? = SystemProperty::read
        set(value) {
            field = value
            onDefaultPort = null
        }

    /** persist.adb.tcp.port: the port adbd listens on when
     *  service.adb.tcp.port is empty (AOSP daemon/main.cpp) - set by some ROMs
     *  and rooted phones, never by JemRec; null for none or unreadable. */
    fun persistPort(): Int? = readProperty("persist.adb.tcp.port")?.let(::parsePort)

    /** getprop prints the value, or an empty line for a property never set
     *  or not readable - null, as is anything that is not a number. */
    internal fun parsePort(text: String): Int? = text.trim().toIntOrNull()

    /** Whether adbd will listen on a TCP port whenever it is up, until a
     *  reboot - JemRec's or anyone's; "0" is closed, by `usb:`. Null if it
     *  cannot be told. */
    fun portSet(): Boolean? = configuredPort()?.let { it > 0 }

    /**
     * Whether the port adbd is set to - [configured], the property, null
     * when it cannot be read - is JemRec's: its [own] number, whatever this
     * boot's notes say, so an arm whose note was lost with the app is still
     * JemRec's, and a computer's `adb usb` then `adb tcpip 5555` is not (both
     * found in 1.10's review). A 5555 is JemRec's only where 1.10 or before
     * opened it this boot ([legacyThisBoot]). Unreadable, what this boot
     * asked for is all there is to go on.
     */
    internal fun isOurs(configured: Int?, own: Int?, armedThisBoot: Boolean, legacyThisBoot: Boolean): Boolean =
        when {
            configured == null -> armedThisBoot || legacyThisBoot
            configured <= 0 -> false
            configured == own -> true
            else -> configured == LEGACY_PORT && legacyThisBoot
        }

    /** [isOurs] here and now: the screens' "JemRec's port". */
    fun isOurs(context: Context): Boolean =
        isOurs(configuredPort(), ownPort(context), armedThisBoot(context), legacyOpenedThisBoot(context))

    /** The number adbd's port is on, for the screens to name: the
     *  property's - JemRec's own or anyone's - or, where it cannot be read,
     *  the one this boot asked for (JemRec's own, or 1.10's 5555); null for
     *  none. */
    fun portNumber(context: Context): Int? = when (val configured = configuredPort()) {
        null -> when {
            armedThisBoot(context) -> ownPort(context)
            legacyOpenedThisBoot(context) -> LEGACY_PORT
            else -> null
        }
        else -> configured.takeIf { it > 0 }
    }

    /**
     * Whether adbd will be on a port whenever it is up - JemRec's or anyone's,
     * what lets the recorder start with no Wi-Fi (CaptureDaemon's revive). The
     * property, where it can be read; where it cannot, whether JemRec asked
     * for its port this boot (or 1.10 opened 5555). The screens and the
     * question before turning off say [readyForUser].
     */
    fun ready(context: Context): Boolean = portSet() ?: (armedThisBoot(context) || legacyOpenedThisBoot(context))

    /**
     * [ready], as the screens and the question before turning off say it.
     * Where the property cannot be read and adbd runs - USB debugging on -
     * the port answering is what says it: a computer's `adb usb` closes it
     * unseen otherwise, and turning all debugging off away from Wi-Fi would
     * go ahead without its question, with nothing to come back on over. Not
     * for the revive, which waits for an adbd only just starting
     * (PortChange.connectOnPort). [answersOn] is for the tests: the JVM's
     * loopback is no phone's.
     */
    fun readyForUser(context: Context, answersOn: (Int) -> Boolean = ::answers): Boolean =
        portSet() ?: run {
            val adbdOff = !GlobalSettings.isOn(context, GlobalSettings.ADB_ENABLED)
            val own = ownPort(context)
            (armedThisBoot(context) && own != null && (adbdOff || answersOn(own))) ||
                (legacyOpenedThisBoot(context) && (adbdOff || answersOn(LEGACY_PORT)))
        }

    /** Whether anything answers on 127.0.0.1:[port] now - adbd, once it is up. */
    fun answers(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(AdbTransport.LOOPBACK, port), PROBE_MS) }
        true
    }.getOrDefault(false)

    private fun boot(context: Context): Int = GlobalSettings.bootCount(context)

    private fun inThisBoot(context: Context, key: String): Boolean {
        val boot = boot(context)
        return boot >= 0 && Prefs.of(context).getInt(key, -1) == boot
    }

    /** Asked for this boot, and the property never said JemRec's port - nor,
     *  where it cannot be read, the port answer: the switch says so. */
    fun failedThisBoot(context: Context): Boolean = inThisBoot(context, KEY_FAILED_BOOT)

    /** Asked to close this boot, and the property still said JemRec's port. */
    fun closeFailedThisBoot(context: Context): Boolean = inThisBoot(context, KEY_CLOSE_FAILED_BOOT)

    /** JemRec asked adbd for its own port this boot. */
    internal fun armedThisBoot(context: Context): Boolean = inThisBoot(context, KEY_ARMED_BOOT)

    /** 1.10 or before opened 5555 this boot ([KEY_OPENED_BOOT]). */
    internal fun legacyOpenedThisBoot(context: Context): Boolean = inThisBoot(context, KEY_OPENED_BOOT)

    /** Whether adbd listens on 5555 with no port set: only where it has no
     *  USB to listen on (AOSP daemon/main.cpp) - an emulator, as its own
     *  properties say. Kept once read, as it does not change - but not a
     *  "no" with a getprop that did not answer, which is no "not an
     *  emulator": on a loaded AVD that would take 5555 away until the process
     *  ended (review round 3 of 1.11). Either property says so, and the
     *  Android 12+ AVD images leave ro.kernel.qemu empty and set ro.boot.qemu
     *  (round 4): a "no" is kept only when both answered. */
    internal fun adbdOnDefaultPort(): Boolean = onDefaultPort ?: run {
        val kernel = readProperty("ro.kernel.qemu")
        val boot = readProperty("ro.boot.qemu")
        val answer = kernel == "1" || boot == "1"
        if (answer || (kernel != null && boot != null)) onDefaultPort = answer
        answer
    }

    @Volatile
    private var onDefaultPort: Boolean? = null

    /** Start fresh: the switch off and the notes gone (Setup.reset, which
     *  closes the port first if it is JemRec's) - all but [stillOpen]'s: a
     *  port the reset could not close stays JemRec's to close, once it is set
     *  up again and on Wi-Fi. The number is kept. */
    fun forget(context: Context, stillOpen: Boolean) {
        Prefs.of(context).edit {
            remove(KEY_ON)
            if (!stillOpen) {
                remove(KEY_ARMED_BOOT)
                remove(KEY_OPENED_BOOT)
            }
            remove(KEY_FAILED_BOOT)
            remove(KEY_CLOSE_FAILED_BOOT)
        }
    }

    /** About to ask adbd for JemRec's port: noted before the ask, and on disk
     *  at once - the app killed before the read-back still leaves the one
     *  note a property that cannot be read has to go on. */
    internal fun noteArmed(context: Context) {
        Prefs.of(context).edit(commit = true) {
            putInt(KEY_ARMED_BOOT, boot(context))
            // A new port of JemRec's: a close that did not take earlier this
            // boot said nothing of it, and its note would keep this one from
            // being closed (review round 2 of 1.11).
            remove(KEY_CLOSE_FAILED_BOOT)
        }
    }

    /** Asked, and the property never said JemRec's port: not asked again
     *  this boot, and the ask's note gone - as 1.10's failed open noted no
     *  port of its own. The property is empty after every restart until the
     *  first open, which reads as "cannot be read", and the note left behind
     *  counted as an open port there: revives off Wi-Fi tried to start over
     *  it, a switch-off restarted adbd to close it, and Start fresh said it
     *  could not close it (review round 1 of 1.11). Read back later after
     *  all, JemRec's number says it is JemRec's. */
    internal fun noteOpenFailed(context: Context) {
        Prefs.of(context).edit {
            putInt(KEY_FAILED_BOOT, boot(context))
            remove(KEY_ARMED_BOOT)
        }
    }

    /** Closed: no port of JemRec's this boot, 1.10's 5555 included. */
    internal fun noteClosed(context: Context) {
        Prefs.of(context).edit {
            remove(KEY_ARMED_BOOT)
            remove(KEY_OPENED_BOOT)
        }
    }

    /** Asked to close, and the property still says JemRec's port: still
     *  JemRec's to say open, not asked again this boot (KEY_CLOSE_FAILED_BOOT). */
    internal fun noteCloseFailed(context: Context) {
        Prefs.of(context).edit { putInt(KEY_CLOSE_FAILED_BOOT, boot(context)) }
    }

    /** What a revive does about the port before it starts the recorder. */
    internal enum class PortStep { OPEN, CLOSE, LEAVE }

    /**
     * Open JemRec's port when the switch is on and no port is set - or none
     * can be read and JemRec has not asked this boot - unless an open
     * [failed] this boot; a port anyone else set is used as it is, not moved
     * (adbd listens on one at a time). Close it when the switch is off and
     * the port set is JemRec's ([ours]), unless the property says it is closed
     * already, or a close failed this boot ([closeFailed]); otherwise leave
     * it. Either change restarts adbd, so the caller makes it only where the
     * recorder can be started again at once (CaptureDaemon.portChangeDue).
     */
    internal fun portStep(
        wanted: Boolean,
        set: Boolean?,
        ours: Boolean,
        failed: Boolean,
        closeFailed: Boolean = false,
    ): PortStep = when {
        wanted && !failed && (set == false || (set == null && !ours)) -> PortStep.OPEN
        !wanted && ours && !closeFailed && set != false -> PortStep.CLOSE
        else -> PortStep.LEAVE
    }

    /** [portStep] here and now. With the switch off and no port of JemRec's
     *  possible - no number picked, and no 5555 of 1.10's this boot - there is
     *  nothing to read here (the revive's connect reads the property for its
     *  own sake: PortChange.classicPorts). A read only: it is also asked
     *  outside the revive's lock (PortChange.portChangeDue). */
    internal fun step(context: Context): PortStep {
        val wanted = enabled(context)
        val own = ownPort(context)
        val legacy = legacyOpenedThisBoot(context)
        val armed = armedThisBoot(context)
        if (!wanted && own == null && !legacy && !armed) return PortStep.LEAVE
        val configured = configuredPort()
        return portStep(
            wanted = wanted,
            set = configured?.let { it > 0 },
            ours = isOurs(configured, own, armed, legacy),
            failed = failedThisBoot(context),
            closeFailed = closeFailedThisBoot(context),
        )
    }
}
