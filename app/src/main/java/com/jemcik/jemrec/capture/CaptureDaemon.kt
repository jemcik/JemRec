package com.jemcik.jemrec.capture

import android.content.Context
import android.media.AudioManager
import android.util.Log
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.adb.AdbIdentity
import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.ui.Setup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Lifecycle of the shell-side capture daemon.
 *
 * The daemon is a jar bundled in this APK. ADB is used to START it and for
 * nothing else afterwards, which is the whole shape of the design and was
 * forced by measurement rather than chosen: with Wi-Fi off adbd tears its
 * listener down completely, and the shell UID may not set
 * service.adb.tcp.port to pin a stable one. So there is no ADB at the moment a
 * call arrives, and anything that needed ADB then would simply not record.
 *
 * What there IS at that moment is a process, because a process spawned from an
 * adb shell outlives the adb connection. Start it once, talk to it over
 * loopback forever.
 */
object CaptureDaemon {

    private const val TAG = "JemRec"

    private const val ASSET = "jemrec-capture.jar"
    private const val DEVICE_PATH = "/data/local/tmp/jemrec-capture.jar"
    private const val LOG_PATH = "/data/local/tmp/jemrec-capture.out"
    private const val MAIN_CLASS = "com.jemcik.jemrec.shell.Main"
    /** adbd needs a moment to come up and advertise a port after re-arming. */
    internal const val ADBD_SETTLE_MS = 4_000L

    /** How long a revive looks for adbd before giving up on this attempt. */
    internal const val CONNECT_MS = 5_000L
    private const val KEY_TOKEN = "daemon_token"

    /**
     * Kill the daemon by its class name WITHOUT killing the shell that runs the
     * command. `pkill -f` matches against every process's full command line -
     * including the `sh -c "pkill -f com.jemcik..."` that adbd spawns to run
     * this, whose own command line contains the class name. Measured: the
     * shell died and nothing after the pkill ran. The bracket makes the
     * pattern a regex that matches "com.jemcik..." in the daemon's arguments
     * but not the literal "[c]om.jemcik..." in the shell's.
     */
    private const val PKILL = "pkill -f '[c]om.jemcik.jemrec.shell.Main'"

    /** A ping is lost to a busy phone often enough that one "no" means nothing. */
    // Sized for a throttled CPU, not a plugged-in one. A loopback round trip
    // is sub-millisecond when the phone is awake; the whole budget here exists
    // because it is not always awake, and because concluding "dead" wrongly
    // used to cost a working recorder.
    private const val PING_ATTEMPTS = 5
    private const val PING_RETRY_GAP_MS = 600L
    private const val PING_CONNECT_MS = 3_000
    private const val PING_READ_MS = 3_000
    private const val PORT_PROBE_MS = 200

    /** Up to three seconds for a pkill-ed daemon to let go of the port. */
    private const val PORT_FREE_ATTEMPTS = 20
    private const val PORT_FREE_GAP_MS = 150L

    const val PORT = 28472
    private const val LOOPBACK = "127.0.0.1"

    /**
     * THE PROTOCOL THIS APP SPEAKS, AND WHY THERE IS A VERSION.
     *
     * Port 28472 is a plain TCP port on loopback, and loopback is shared by
     * every process on the phone. The first protocol took a bare command byte,
     * so any app could fetch a finished recording, delete one, open a live
     * capture of the call in progress, or stop the daemon. Version 2 proves
     * both ends know this app's token before a command is accepted - see
     * open() for the exchange, and Main.java for the same story from the
     * daemon's side.
     *
     * A bare ping still needs no handshake and answers "PONG <version>" (a
     * version-1 daemon answers a bare "PONG"). That is how an out-of-date
     * daemon is told apart from a current one, so ensureRunning() can replace
     * it - it cannot be spoken to otherwise.
     */
    private const val PROTOCOL_VERSION = 2
    private const val LEGACY_PROTOCOL_VERSION = 1

    /** First byte of every connection: a bare ping, or the handshake. */
    private const val COMMAND_PING = 'P'.code.toByte()
    private const val COMMAND_HELLO = 'A'.code.toByte()

    /** The command byte that follows a successful handshake. See Main.session(). */
    private const val COMMAND_RECORD = 'R'.code.toByte()
    private const val COMMAND_QUIT = 'Q'.code.toByte()
    private const val COMMAND_FETCH = 'F'.code.toByte()
    private const val COMMAND_DELETE = 'D'.code.toByte()
    private const val COMMAND_SET_ENABLED = 'E'.code.toByte()
    private const val COMMAND_LIST = 'L'.code.toByte()
    private const val COMMAND_START = 'S'.code.toByte()
    private const val COMMAND_WATCH = 'W'.code.toByte()
    /** The recorder's account of the last call it recorded - see the
     *  daemon's CallReport. A daemon older than this hangs up on it. */
    private const val COMMAND_REPORT = 'C'.code.toByte()
    /** The messenger apps whose calls to record - see VoipApps and the
     *  daemon's Voip. A daemon older than this hangs up on it. */
    private const val COMMAND_SET_VOIP = 'V'.code.toByte()
    /** Exact call tracking on or off (CallTracking): the daemon allows or
     *  withdraws MANAGE_ONGOING_CALLS for this app's uid. */
    private const val COMMAND_SET_TRACKING = 'T'.code.toByte()
    /** Leave out the phone call ringing or being dialled - the rules said so
     *  before it was recorded (CallTracker). 1 skips, 0 takes it back. */
    private const val COMMAND_SKIP = 'K'.code.toByte()

    /** The answering machine: its settings, the go-ahead for a ringing call,
     *  Talk to them / Hang up, the greeting, and the diagnostics. See Main in
     *  the daemon for each. */
    private const val COMMAND_ANSWER_SETTINGS = 'N'.code.toByte()
    private const val COMMAND_GO_AHEAD = 'Y'.code.toByte()
    private const val COMMAND_HANDOVER = 'H'.code.toByte()
    private const val COMMAND_GREETING = 'G'.code.toByte()
    private const val COMMAND_DIAGNOSE = 'X'.code.toByte()

    /** Switch Wireless and USB debugging off and go - "Turn all debugging
     *  off" (DebugSwitchesOff). 1 goes, 0 calls off a go still waiting. */
    private const val COMMAND_DEBUGGING_OFF = 'O'.code.toByte()

    /**
     * How long a BUSY is given to come true. A daemon really recording a call
     * lets go within a couple of seconds of the call's audio being released -
     * see retire() - so three BUSYs across twelve seconds with no call in
     * progress at any point is not a call.
     */
    private const val WEDGE_RECHECKS = 2
    private const val WEDGE_RECHECK_MS = 6_000L

    private const val NONCE_BYTES = 16
    private const val MAC_BYTES = 32
    private const val MAC_ALGORITHM = "HmacSHA256"
    private val DAEMON_LABEL = "jemrec-daemon".toByteArray()
    private val CLIENT_LABEL = "jemrec-client".toByteArray()
    private val EMPTY = ByteArray(0)
    private val random = SecureRandom()

    /** Header reads: the handshake, and the short replies most commands send. */
    private const val REPLY_MS = 3_000

    /** A fetch is a file copy off local disk. Thirty seconds without a byte
     *  means the daemon is gone, not slow. */
    private const val FETCH_READ_MS = 30_000

    /** The self-test reads a few packets of live audio; a capture that opens
     *  and then produces nothing must end in an error, not a stuck thread. */
    private const val LIVE_READ_MS = 10_000

    /** Recording policy pushed to the daemon (it cannot read the app's prefs). */
    const val MODE_OFF = 0
    const val MODE_AUTOMATIC = 1
    const val MODE_ON_DEMAND = 2

    /**
     * revive() and ensureRunning() take this, so two callers arriving together
     * - the keep-alive job and a Wi-Fi return, a resume and the switch - cannot
     * both conclude the daemon is down and both spawn one. The second spawn
     * used to die on EADDRINUSE and, worse, truncate the first one's log.
     */
    private val lifecycle = Mutex()

    /**
     * Fires when a daemon has just been started, for whoever is showing the
     * recorder's state. Revival runs out of the screen's sight - the keep-alive
     * job, woken by the Wi-Fi watcher - and the header re-read the world only
     * on resume. Measured: after a reboot with no Wi-Fi the header said "needs
     * Wi-Fi", Wi-Fi came, the job revived the daemon, and the header went on
     * saying "needs Wi-Fi" until the app was left and reopened.
     */
    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val changed: SharedFlow<Unit> = _changed.asSharedFlow()

    /**
     * Is OUR daemon alive AND answering?
     *
     * Deliberately a ping and not merely a successful connect. Before the
     * daemon understood a command byte, asking this question started a
     * recording session - it span up an AudioRecord on the voice-call source
     * and tore it down again - so a watchdog polling every few minutes would
     * have been quietly churning audio sessions all day.
     *
     * A ping also answers a better question than an open port does: something
     * has to be running the accept loop and able to reply. And since the
     * handshake, it answers a better question still: whatever replied knows
     * this app's token, so it is this install's daemon - not one left behind
     * by a previous install, and not one from before the handshake existed.
     *
     * Asked over the socket rather than with `ps` or `ss`, because those need
     * ADB - and needing ADB to find out whether ADB is still needed is the
     * dependency this whole design exists to remove.
     */
    suspend fun isRunning(context: Context): Boolean = withContext(Dispatchers.IO) { probe(context) != null }

    /**
     * The same question, asked properly before acting on a "no".
     *
     * One ping is a fine health check and a terrible death certificate. The
     * loopback round trip is normally sub-millisecond, but it runs on a phone
     * that swaps, throttles and freezes background work, so a single short
     * connect can time out against a daemon that is perfectly alive.
     *
     * That false negative was expensive. ensureRunning() answers a "no" by
     * pkill-ing the daemon and starting another, so one unlucky ping killed a
     * working recorder - and if the port was not free 300ms later the
     * replacement died on EADDRINUSE and left NOTHING running. Measured, not
     * guessed: the daemon's own log recorded that bind failure interleaved with
     * the pings the previous instance was still happily answering.
     *
     * So anything destructive asks this instead, and only five failures in a
     * row count as gone.
     */
    suspend fun isRunningConfirmed(context: Context): Boolean =
        withContext(Dispatchers.IO) { probeConfirmed(context) != null }

    /**
     * What a daemon answers a ping with: the protocol version it speaks and,
     * when it knows it, its build - the first eight hex digits of the SHA-256
     * of the jar it runs from. The version says whether we can talk to it;
     * the build says whether it is the code this APK carries.
     */
    internal data class Answer(val version: Int, val build: String?)

    /** "PONG" (version 1), "PONG 2", "PONG 2 1a2b3c4d"; anything else is not an answer. */
    internal fun parsePong(line: String?): Answer? {
        if (line == null || !line.startsWith("PONG")) return null
        val rest = line.removePrefix("PONG")
        if (rest.isNotEmpty() && rest[0] != ' ') return null
        val parts = rest.trim().split(' ').filter { it.isNotEmpty() }
        return Answer(
            version = parts.getOrNull(0)?.toIntOrNull() ?: LEGACY_PROTOCOL_VERSION,
            build = parts.getOrNull(1),
        )
    }

    /** An authenticated ping: an answer only from a daemon that knows our
     *  token, which by construction speaks the current protocol. */
    private fun probe(context: Context): Answer? =
        try {
            open(context, COMMAND_PING, connectMs = PING_CONNECT_MS, readMs = PING_READ_MS).use { socket ->
                parsePong(socket.getInputStream().bufferedReader().readLine())
            }
        } catch (_: Exception) {
            null
        }

    /** probe(), asked PING_ATTEMPTS times before concluding nothing is there. */
    private suspend fun probeConfirmed(context: Context): Answer? {
        repeat(PING_ATTEMPTS) { attempt ->
            probe(context)?.let { return it }
            if (attempt < PING_ATTEMPTS - 1) delay(PING_RETRY_GAP_MS)
        }
        return null
    }

    /**
     * What is on the port, without a handshake: whatever answers a bare ping,
     * or null if nothing does. For telling the log WHY a daemon is being
     * replaced, not for trusting it.
     */
    private fun version(): Answer? =
        try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(LOOPBACK, PORT), PING_CONNECT_MS)
                socket.soTimeout = PING_READ_MS
                socket.getOutputStream().apply { write(byteArrayOf(COMMAND_PING)); flush() }
                parsePong(socket.getInputStream().bufferedReader().readLine())
            }
        } catch (_: Exception) {
            null
        }

    /** Our daemon's answer to an authenticated ping, or null if it is not answering. */
    internal suspend fun answer(context: Context): Answer? = withContext(Dispatchers.IO) { probe(context) }

    /** Whether the daemon that gave that answer runs the jar this APK carries. */
    internal fun isCurrent(context: Context, answer: Answer): Boolean = upToDate(context, answer)

    @Volatile
    private var bundled: String? = null

    /** The build of the jar this APK carries, in the daemon's own terms. */
    private fun bundledBuild(context: Context): String = bundled ?: run {
        val digest = MessageDigest.getInstance("SHA-256")
        context.assets.open(ASSET).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().take(4).joinToString("") { "%02x".format(it) }.also { bundled = it }
    }

    /** A daemon that cannot say its build is left alone rather than churned. */
    private fun upToDate(context: Context, answer: Answer): Boolean =
        answer.build == null || answer.build == bundledBuild(context)

    /** Whether anything at all holds the port, answering or not. */
    private fun portInUse(): Boolean =
        try {
            Socket().use {
                it.connect(InetSocketAddress(LOOPBACK, PORT), PORT_PROBE_MS)
                true
            }
        } catch (_: Exception) {
            false
        }

    /**
     * Open a connection, prove both ends know the token, and send one command.
     * The caller owns the socket and reads the reply.
     *
     *   app    -> 'A' + Nc                                  (16 random bytes)
     *   daemon -> Nd + HMAC(token, "jemrec-daemon" | Nc | Nd)
     *   app    -> HMAC(token, "jemrec-client" | Nc | Nd) + command [+ payload]
     *
     * THE DAEMON PROVES ITSELF FIRST. Any app can bind 28472 while the daemon
     * is down and answer connections in its place; a scheme that sent the
     * token as a password would hand it to whoever answered. Here nothing but
     * a nonce leaves this app until the far end has shown it knows the token,
     * and nothing sent afterwards can be replayed, because both nonces are
     * fresh per connection and the daemon chose one of them.
     *
     * A version-1 daemon reads 'A' as an unknown command and hangs up, which
     * arrives here as EOF and is reported as such, so the log says "out of
     * date" rather than "connection reset".
     */
    private fun open(
        context: Context,
        command: Byte,
        payload: ByteArray = EMPTY,
        connectMs: Int = 1_500,
        readMs: Int = REPLY_MS,
    ): Socket {
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(LOOPBACK, PORT), connectMs)
            socket.soTimeout = REPLY_MS
            val key = token(context).toByteArray()
            val ours = ByteArray(NONCE_BYTES).also(random::nextBytes)
            val out = socket.getOutputStream()
            out.write(byteArrayOf(COMMAND_HELLO))
            out.write(ours)
            out.flush()

            // Unbuffered on purpose: whatever the daemon sends after the
            // handshake belongs to the caller's reader, byte for byte.
            val input = DataInputStream(socket.getInputStream())
            val theirs = ByteArray(NONCE_BYTES)
            val proof = ByteArray(MAC_BYTES)
            try {
                input.readFully(theirs)
                input.readFully(proof)
            } catch (_: EOFException) {
                throw IOException("the recorder on port $PORT hung up during the handshake - out of date?")
            }
            if (!MessageDigest.isEqual(proof, mac(key, DAEMON_LABEL, ours, theirs))) {
                throw SecurityException("whatever answers on port $PORT does not know this app's token")
            }

            out.write(mac(key, CLIENT_LABEL, ours, theirs))
            out.write(byteArrayOf(command))
            out.write(payload)
            out.flush()
            socket.soTimeout = readMs
            return socket
        } catch (t: Throwable) {
            runCatching { socket.close() }
            throw t
        }
    }

    private fun mac(key: ByteArray, label: ByteArray, a: ByteArray, b: ByteArray): ByteArray =
        Mac.getInstance(MAC_ALGORITHM).run {
            init(SecretKeySpec(key, MAC_ALGORITHM))
            update(label)
            update(a)
            doFinal(b)
        }

    /**
     * Bring the daemon back if it is down and can be reached.
     *
     * This was the DaemonWatchdog's repair(), moved here unchanged when the
     * permanent monitor service went away. A dormant app has no service running
     * a poll loop, so reviving the daemon is now the job of a scheduled worker
     * and of boot - both of which call this, so the logic lives with the rest
     * of the daemon's lifecycle rather than in a service that no longer exists.
     *
     * Returns true if the daemon is running when this returns, false if it
     * could not be reached - almost always no Wi-Fi, since restarting it needs
     * an ADB session and an ADB session needs Wi-Fi.
     */
    suspend fun revive(context: Context): Boolean = withContext(Dispatchers.IO) {
        // Switched off with debugging, by choice: every revive stands down
        // here, or one of them would have debugging back on within seconds.
        // Not a failure either, so RecorderHealth is not told of one.
        if (DebugSwitchesOff.stayingOff(context)) {
            ReviveLog.note(context, "revive: debugging is off with JemRec; nothing to do until it is on")
            return@withContext false
        }
        // With JemRec on, any record is an On cut short - the switch saved,
        // the process gone before the switches came back - finished here.
        // Any, not only one that took the recorder: Developer options would
        // otherwise stay hidden, and Wireless debugging go off after this
        // revive, with JemRec on.
        if (RecorderSwitch.isOn(context) && DebugSwitchesOff.switchedOff(context).isNotEmpty()) {
            ReviveLog.note(context, "revive: JemRec is on with debugging still recorded off; putting it back")
            DebugSwitchesOff.switchBackOn(context)
        }
        AdbTransport.forgetLastConnect()
        ReviveLog.note(context, "revive: start")
        val alive = lifecycle.withLock { reviveLocked(context) }
        ReviveLog.note(context, "revive: ${if (alive) "recorder up" else "recorder down"} (connect ${AdbTransport.lastConnect})")
        // Every revive is a reading of whether the recorder is up, and the
        // one place the user hears about it when it is not. See RecorderHealth.
        RecorderHealth.onRevive(context, alive)
        if (alive) CableWatch.forget(context)
        alive
    }

    private suspend fun reviveLocked(context: Context): Boolean {
        // Keep the off-Wi-Fi shield up whenever this phone is set up - even when
        // the daemon is already running. Honor clears adb_enabled when adbd
        // restarts (which is what a Wi-Fi return does), and a manual respawn
        // never sets it, so the shield can be down under a live daemon. The
        // health-check return just below would then skip re-arming it, and the
        // keep-alive that calls this every so often would never restore it.
        // Cheap: a read, and a write only when it has drifted.
        if (isSetUp(context)) armShield(context)

        val running = probeConfirmed(context)
        ReviveLog.note(context, "revive: recorder ${running?.let { "answers, build ${it.build}" } ?: "does not answer"}")
        if (running != null && upToDate(context, running)) return true
        if (running != null) {
            // Alive, but running the jar an earlier APK staged. Carry on down
            // the same path a dead daemon takes; ensureRunning() does the
            // replacing once there is a session to do it over.
            Log.i(
                TAG,
                "daemon: running build ${running.build}, this app carries ${bundledBuild(context)}; " +
                    "replacing when a session can be had",
            )
        }

        // NOTHING TO REVIVE UNTIL SETUP HAS ACTUALLY FINISHED.
        //
        // This asked whether a keypair existed, which reads like "have we
        // paired" and is not. AdbTransport builds one with getOrCreate() the
        // first time it ATTEMPTS a connection, so the file is there on a phone
        // that has never paired with anything - and the guard failed open,
        // doing exactly what the comment it replaces was afraid of: re-arming
        // Wireless debugging behind the user's back, silently undoing half of
        // Start fresh. Caught by a wizard that kept skipping to the pairing
        // step on a wiped phone, because something had turned Wireless
        // debugging back on between one screen and the next.
        if (!setupComplete(context) || !AdbIdentity.exists(context)) {
            Log.i(TAG, "daemon: setup has not finished, nothing to revive")
            return false
        }

        // Nothing below can work off Wi-Fi - see NetworkRevive.onWifi. The
        // Wi-Fi watcher brings us back here the moment there is some.
        if (!NetworkRevive.onWifi(context)) {
            Log.i(TAG, "daemon: not on Wi-Fi, nothing to revive over yet")
            ReviveLog.note(context, "revive: not on Wi-Fi")
            return running != null
        }

        // A reboot resets this to 0, and without it adbd is not listening even
        // on Wi-Fi. Needs WRITE_SECURE_SETTINGS, granted once at setup.
        //
        // And after a USB cable stopped adbd it reads 1 with nothing listening
        // (see CableWatch): only switching it off and on has Android start
        // adbd again. Measured on the Honor: adbd stopped at 18:44:21 and was
        // back at 18:47:13, the moment it was switched off and on, and not
        // before. Not done for a revive that merely finds nothing advertised:
        // on a Wi-Fi Android has not allowed, that puts its question on screen
        // out of nowhere (see RecorderHealth), so it is left to the button.
        val step = wirelessDebuggingStep(
            cableStoppedAdbd = CableWatch.stoppedAdbd(context),
            on = GlobalSettings.isOn(context, GlobalSettings.ADB_WIFI_ENABLED),
        )
        ReviveLog.note(context, "revive: Wireless debugging $step")
        when (step) {
            WirelessStep.CYCLE -> {
                Log.i(TAG, "daemon: a USB cable stopped adbd; switching Wireless debugging off and on to start it")
                val cycled = cycleWirelessDebugging(context)
                ReviveLog.note(context, "revive: switched off and on: $cycled")
                if (cycled) {
                    // Once: that starts adbd, and a revive that fails after
                    // it fails for some other reason, which cycling again
                    // would only hide.
                    CableWatch.forget(context)
                    delay(ADBD_SETTLE_MS)
                }
            }
            WirelessStep.TURN_ON -> if (GlobalSettings.set(context, GlobalSettings.ADB_WIFI_ENABLED, true)) {
                Log.i(TAG, "daemon: re-armed Wireless debugging")
                delay(ADBD_SETTLE_MS)
            }
            WirelessStep.LEAVE -> Unit
        }

        if (!AdbTransport.isConnected) {
            ReviveLog.note(context, "revive: connecting")
            AdbTransport.autoConnect(context, timeoutMs = CONNECT_MS).onFailure {
                Log.w(TAG, "daemon: no ADB session - ${it.javaClass.simpleName}: ${it.message ?: "no detail"}")
                ReviveLog.note(context, "revive: no ADB session (${AdbTransport.lastConnect}): ${it.message}")
                // An older build that is up is still a recorder. Only a daemon
                // that is down is a failure here - reporting the other as one
                // would have the keep-alive retrying with backoff all day.
                return running != null
            }
        }

        ReviveLog.note(context, "revive: connected, starting the recorder")
        val started = ensureRunningLocked(context)
        val ok = started.isSuccess
        ReviveLog.note(context, "revive: start ${started.fold({ "ok" }, { "failed: ${it.message}" })}")
        if (ok) {
            // A spawned daemon is told the mode in its environment, so this is
            // for the other case - one that was already running - and it is
            // one round trip. Best-effort.
            pushMode(context)
            // Wireless debugging was turned on a moment ago to make this
            // possible. It is not needed again until the next revive.
            standDown(context)
            // And off again, if JemRec is off with Developer options hidden.
            DebugSwitchesOff.afterRecorderStarted(context)
        }
        return ok
    }

    /**
     * Put Android's "Allow wireless debugging on this network?" back on
     * screen, for a Wi-Fi it has not allowed (see RecorderHealth).
     *
     * SystemUI asks when Wireless debugging is switched ON on such a network,
     * not while it stays on, so it is switched off and on again. Measured on
     * the Android 17 QPR2 emulator: the question appeared within a second of
     * the second write, and after Always allow and Allow, the next revive
     * found adbd advertising and the recorder came back. Called from the
     * activity, so the question lands over JemRec, and the screen's
     * re-check when it closes is what revives the recorder.
     *
     * False if the setting could not be written, which leaves the user to do
     * it by hand in Settings.
     */
    suspend fun askToAllowNetwork(context: Context): Boolean {
        Log.i(TAG, "daemon: asking Android to allow Wireless debugging on this Wi-Fi")
        return cycleWirelessDebugging(context)
    }

    /** Wireless debugging off, then on: what puts the network question back
     *  on screen, and what starts an adbd a cable stopped. */
    private suspend fun cycleWirelessDebugging(context: Context): Boolean {
        if (!GlobalSettings.set(context, GlobalSettings.ADB_WIFI_ENABLED, false)) return false
        // Long enough for the off to land as its own change.
        delay(1_000)
        return GlobalSettings.set(context, GlobalSettings.ADB_WIFI_ENABLED, true)
    }

    internal enum class WirelessStep { CYCLE, TURN_ON, LEAVE }

    /** What a revive does to Wireless debugging before it connects. */
    internal fun wirelessDebuggingStep(cableStoppedAdbd: Boolean, on: Boolean): WirelessStep = when {
        cableStoppedAdbd -> WirelessStep.CYCLE
        !on -> WirelessStep.TURN_ON
        else -> WirelessStep.LEAVE
    }

    /** Push the current recorder switch + automatic/on-demand choice to the
     *  daemon as its recording mode, and the messenger apps with it.
     *  Best-effort. */
    suspend fun pushMode(context: Context) {
        setDaemonMode(context, currentMode(context))
        setVoipApps(context)
        setTracking(context)
        syncGreeting(context)
        setAnswering(context)
    }

    /** One command, one line back; null if the daemon could not be asked or
     *  hung up - an older one does, on a command it does not know. */
    private suspend fun ask(context: Context, command: Byte, payload: ByteArray, readMs: Int = 3_000): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                open(context, command, payload, readMs = readMs).use { socket ->
                    socket.getInputStream().bufferedReader().readLine()?.trim()
                }
            }.getOrNull()
        }

    /**
     * Have the recorder switch Wireless and USB debugging off and go, or call
     * off a go still waiting (see DebugSwitchesOff). The recorder does it
     * rather than the app: it is never frozen, it waits out a call or a fetch
     * in progress, and it gives the microphone back first. "OFF NOW", "OFF
     * AFTER CALL", "OFF CANCELLED", or "OFF TOO LATE" for a call-off that
     * came while it was writing the switches; null if no recorder answered,
     * or one from before this command.
     */
    suspend fun debuggingOff(context: Context, go: Boolean): String? =
        ask(context, COMMAND_DEBUGGING_OFF, byteArrayOf(if (go) 1 else 0), readMs = 2_000)
            ?.takeIf { it.startsWith("OFF") }

    /** The answering machine's settings, to the daemon. Best-effort, like the mode. */
    suspend fun setAnswering(context: Context): Boolean =
        ask(context, COMMAND_ANSWER_SETTINGS, nameFrame(AnsweringMachine.wire(context)))
            ?.startsWith("ANSWER ") == true

    /** Answer the ringing call [delayMs] after it began to ring, or take the
     *  go-ahead back (negative). [rangAt] is when this app saw the ring, on
     *  SystemClock.elapsedRealtime - the daemon's clock too: it ignores a
     *  go-ahead for a ring that has ended since. The daemon's state after,
     *  or null. */
    suspend fun goAhead(context: Context, delayMs: Int, rangAt: Long): String? =
        ask(
            context, COMMAND_GO_AHEAD,
            java.nio.ByteBuffer.allocate(12).putInt(delayMs).putLong(rangAt).array(),
            readMs = 1_500,
        )

    /** During a message: Talk to them, or Hang up. */
    suspend fun handOver(context: Context, talk: Boolean): Boolean =
        ask(context, COMMAND_HANDOVER, byteArrayOf((if (talk) 'T' else 'H').code.toByte()), readMs = 1_500)
            ?.startsWith("HAND ") == true

    /** How many bytes of greeting the daemon has; null if it cannot say. */
    suspend fun greetingBytes(context: Context): Long? =
        ask(context, COMMAND_GREETING, byteArrayOf('Q'.code.toByte()))
            ?.takeIf { it.startsWith("GREETING ") }?.substringAfter(' ')?.toLongOrNull()

    /** Start recording a greeting take from the microphone - the daemon's,
     *  not this app's, which holds no microphone permission. */
    suspend fun startTake(context: Context): Boolean =
        ask(context, COMMAND_GREETING, byteArrayOf('R'.code.toByte())) == "TAKE recording"

    /** Stop the take; its length in bytes, or null. */
    suspend fun stopTake(context: Context): Long? =
        ask(context, COMMAND_GREETING, byteArrayOf('S'.code.toByte()), readMs = 5_000)
            ?.takeIf { it.startsWith("TAKE ") }?.substringAfter(' ')?.toLongOrNull()

    /** The take just recorded, as raw PCM; null if there is none. */
    suspend fun fetchTake(context: Context): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            open(context, COMMAND_GREETING, byteArrayOf('T'.code.toByte()), readMs = 10_000).use { socket ->
                val input = DataInputStream(socket.getInputStream())
                val length = input.readInt()
                require(length in 0..4_000_000) { "implausible take of $length bytes" }
                ByteArray(length).also { input.readFully(it) }
            }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    /** Make [pcm] the daemon's greeting. */
    suspend fun uploadGreeting(context: Context, pcm: ByteArray): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val payload = java.nio.ByteBuffer.allocate(5 + pcm.size)
                .put('P'.code.toByte()).putInt(pcm.size).put(pcm).array()
            open(context, COMMAND_GREETING, payload, readMs = 10_000).use { socket ->
                socket.getInputStream().bufferedReader().readLine() == "GREETING ${pcm.size}"
            }
        }.getOrDefault(false)
    }

    /**
     * The app's greeting is the master copy: the daemon is given it whenever
     * it lacks it or has another, and loses its own when the app has none.
     * The daemon's copy survives a restart of the daemon, but not every way
     * a daemon can be replaced, and a greeting that silently went missing
     * would turn the answering machine off without a word.
     */
    suspend fun syncGreeting(context: Context) {
        val there = greetingBytes(context) ?: return
        val here = Greeting.file(context)
        when {
            Greeting.present(context) && there != here.length() ->
                uploadGreeting(context, here.readBytes())
            !Greeting.present(context) && there > 0 ->
                ask(context, COMMAND_GREETING, byteArrayOf('D'.code.toByte()))
        }
    }

    /** "KEY=value" fields after a fixed head ("DIAG 1", "RESULT 1"). */
    internal fun fields(line: String?, head: String): Map<String, String>? {
        if (line == null || !line.startsWith("$head ")) return null
        return line.removePrefix("$head ").split(' ').filter { '=' in it }
            .associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    /** The no-call checks; null if the daemon cannot run them. */
    suspend fun stageOne(context: Context): Map<String, String>? =
        fields(ask(context, COMMAND_DIAGNOSE, byteArrayOf('C'.code.toByte()), readMs = 15_000), "DIAG 1")

    /** Arm a test call: the next incoming call within three minutes, or -
     *  [number] given - one the daemon dials now. */
    suspend fun armTest(context: Context, incoming: Boolean, number: String = ""): String? =
        ask(
            context, COMMAND_DIAGNOSE,
            byteArrayOf('A'.code.toByte(), if (incoming) 1 else 0) + nameFrame(number),
            readMs = 5_000,
        )

    /** What the last test call measured; null if the daemon cannot say. */
    suspend fun testResult(context: Context): Map<String, String>? =
        fields(ask(context, COMMAND_DIAGNOSE, byteArrayOf('R'.code.toByte())), "RESULT 1")

    /** The carrier's no-reply forwarding: "on 25 *1234", "off 0", "error ...";
     *  slow - the network is asked. */
    suspend fun forwarding(context: Context): String? =
        ask(context, COMMAND_DIAGNOSE, byteArrayOf('F'.code.toByte()), readMs = 60_000)
            ?.takeIf { it.startsWith("FWD ") }?.removePrefix("FWD ")

    /** Lengthen it to [seconds], keeping the carrier's voicemail number. */
    suspend fun setForwarding(context: Context, seconds: Int): String? =
        ask(context, COMMAND_DIAGNOSE, byteArrayOf('S'.code.toByte(), seconds.toByte()), readMs = 90_000)
            ?.takeIf { it.startsWith("FWD ") }?.removePrefix("FWD ")

    /** The phone's SIMs, as Telecom has them: the default one's id, and
     *  each one's id and label. The app cannot list them itself without a
     *  phone-state permission it does not hold. */
    data class Sims(val defaultId: String?, val all: List<Pair<String, String>>)

    suspend fun sims(context: Context): Sims? =
        parseSims(ask(context, COMMAND_DIAGNOSE, byteArrayOf('P'.code.toByte())))

    /** "SIMS default=<id> <id>|<label> ...", labels with '_' for spaces. */
    internal fun parseSims(line: String?): Sims? {
        if (line == null || !line.startsWith("SIMS")) return null
        val words = line.split(' ').drop(1)
        val default = words.firstOrNull { it.startsWith("default=") }?.substringAfter('=')?.ifEmpty { null }
        val all = words.filter { '|' in it }.map { it.substringBefore('|') to it.substringAfter('|').replace('_', ' ') }
        return Sims(default, all)
    }

    /** Tell the daemon whether exact call tracking is on; it allows or
     *  withdraws the permission Telecom checks. Best-effort, like the mode. */
    suspend fun setTracking(context: Context): Boolean = withContext(Dispatchers.IO) {
        val on = CallTracking.enabled(context)
        runCatching {
            // Ten seconds: the daemon runs `cmd appops` for it, twice, and may
            // be running them for a heartbeat at the same moment (one at a
            // time, TRACKING_LOCK) - on a slow phone, past three.
            open(context, COMMAND_SET_TRACKING, byteArrayOf(if (on) 1 else 0), readMs = 10_000).use { socket ->
                socket.getInputStream().bufferedReader().readLine()?.startsWith("TRACK ") == true
            }
        }.getOrDefault(false)
    }

    /** Ask the daemon not to record the phone call starting now. Whether it
     *  understood: an older one hangs up, and the saver deletes it after. */
    suspend fun skipCall(context: Context, skip: Boolean): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            open(context, COMMAND_SKIP, byteArrayOf(if (skip) 1 else 0), readMs = 1_500).use { socket ->
                socket.getInputStream().bufferedReader().readLine()?.startsWith("SKIP ") == true
            }
        }.getOrDefault(false)
    }

    /**
     * Tell the daemon which messenger apps' calls to record. It arms its
     * audio policy with the first and disarms with the last; see Voip in the
     * daemon. Best-effort, like the mode: a daemon too old to know the command
     * hangs up, records no messenger calls, and is replaced at the next revive
     * because its build no longer matches this app's.
     */
    suspend fun setVoipApps(context: Context): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            open(context, COMMAND_SET_VOIP, nameFrame(VoipApps.wire(context)), readMs = 1_500).use { socket ->
                val reply = socket.getInputStream().bufferedReader().readLine()
                Log.i(TAG, "voip: daemon says $reply")
                reply?.startsWith("VOIP ") == true
            }
        }.getOrDefault(false)
    }

    /** The mode the daemon should be in right now, from the app's settings. */
    internal fun currentMode(context: Context): Int = when {
        !RecorderSwitch.isOn(context) -> MODE_OFF
        RecordingMode.of(context) == RecordingMode.AUTOMATIC -> MODE_AUTOMATIC
        else -> MODE_ON_DEMAND
    }

    /** Setup.finish() ran to the end at least once. Setup owns the flag. */
    private fun setupComplete(context: Context): Boolean = Setup.isComplete(context)

    /**
     * Whether a daemon should exist on this phone at all: setup finished, and an
     * ADB identity to revive it with. This is the SAME guard revive() applies.
     *
     * The daemon's lifecycle is tied to this, NOT to the recorder switch. The
     * switch is a soft gate over whether a call is captured (CallMonitorService
     * reads it); turning recording off leaves the daemon running, so turning it
     * back on needs no Wi-Fi. That is why the keep-alive job, boot and the
     * Wi-Fi-return revival all ask this rather than the switch - if they gated
     * on the switch, an off in the field would strand the daemon with no way
     * back until Wi-Fi returned.
     */
    fun isSetUp(context: Context): Boolean =
        setupComplete(context) && AdbIdentity.exists(context)

    /**
     * THE OFF-WI-FI SHIELD. This is what lets recording survive in the field,
     * away from any Wi-Fi.
     *
     * The daemon dies on Wi-Fi loss for one reason: adbd RESTARTS, and the
     * restart cgroup-kills adbd's children. adbd restarts only when it has no
     * enabled transport left - measured in the framework: AdbService.stopAdbd()
     * returns early while EITHER USB or Wi-Fi adb is enabled. So turning USB
     * debugging on - the setting, adb_enabled, no cable needed and it sticks
     * without one - keeps adbd's USB transport "enabled" in the framework's
     * eyes. Losing Wi-Fi then stops the Wireless server but no longer restarts
     * adbd, and the daemon lives on.
     *
     * Proven on device: no cable, adb_enabled=1, Wi-Fi off - a real call
     * recorded while adbd and the daemon kept their PIDs across the outage.
     * Idempotent; needs WRITE_SECURE_SETTINGS, granted once at setup.
     *
     * Where Android 17 hides the setting, it is written every time, since
     * there is no telling whether it drifted. That costs nothing: on the 37.2
     * emulator, writing the 1 that was already there left adbd's PID as it
     * was, and so the daemon too. Logged only when the setting was known to
     * be off, or every revive would claim to have switched it on.
     */
    private fun armShield(context: Context) {
        val now = GlobalSettings.read(context, GlobalSettings.ADB_ENABLED)
        if (now != GlobalSettings.Reading.ON &&
            GlobalSettings.set(context, GlobalSettings.ADB_ENABLED, true) &&
            now == GlobalSettings.Reading.OFF
        ) {
            Log.i(TAG, "daemon: enabled USB debugging as the off-Wi-Fi shield")
        }
    }

    /**
     * The secret shared with the daemon: it wakes the recording service with
     * it, and since protocol 2 every loopback command is proved with it.
     *
     * Generated once and kept in preferences. It has to survive across app
     * restarts because the daemon outlives them: a token minted on every launch
     * would be wrong the moment the app process was recreated while the daemon
     * was not, which is the normal case. It reaches the daemon only through
     * its environment at spawn (see ensureRunning) and never crosses a socket.
     */
    fun token(context: Context): String {
        val prefs = Prefs.of(context)
        prefs.getString(KEY_TOKEN, null)?.let { return it }
        val fresh = ByteArray(24).also { random.nextBytes(it) }
            .joinToString("") { "%02x".format(it) }
        prefs.edit { putString(KEY_TOKEN, fresh) }
        return fresh
    }

    /**
     * Put the borrowed door back once we are through it.
     *
     * ADB is a bootstrap here, not a transport: it exists to start this daemon,
     * and once the daemon is up it serves loopback and needs no adbd, no mDNS
     * and no Wi-Fi. Nothing enforced that. Wireless debugging stayed on
     * afterwards, adbd stayed listening on the network for as long as the phone
     * was on that Wi-Fi, and Android posted its own "Wireless debugging
     * connected" notification to say so - which the app cannot dismiss, because
     * it belongs to the system.
     *
     * Turning it off removes the notification by removing its cause, which is
     * the only honest way to remove someone else's notification. The larger
     * win is the exposure: a debugging port open on every network the phone
     * joins, indefinitely, to support an operation that finished seconds ago.
     *
     * The watchdog turns it back on when it next needs to repair the daemon, so
     * this costs nothing but a few seconds at the moment of repair.
     *
     * Deliberately does nothing when the daemon is NOT running: that is exactly
     * when the way back in still has to be there.
     */
    suspend fun standDown(context: Context) {
        if (!isRunning(context)) return

        // IT USED TO TURN WIRELESS DEBUGGING OFF HERE. THAT KILLED THE DAEMON.
        //
        // Writing adb_wifi_enabled=0 restarts adbd, and this ROM kills
        // everything adbd spawned when it does - so the tidying-up destroyed
        // the recorder it had just started, seconds after starting it. Then the
        // keep-alive noticed the recorder was gone, re-armed Wireless debugging
        // to fix it (Android posting its notification each time), started a new
        // daemon, tidied up again, and killed that one too. Several times a
        // minute, forever, and only ever off USB.
        //
        // Measured, not deduced. A daemon, a shell script and a bare `sleep`,
        // all setsid-detached and all reparented to init, died at the same
        // instant adb_wifi_enabled went to 0; the log of the watcher stops mid
        // second. The comment above the setsid call says adbd "tears down the
        // whole process GROUP" and that a new session escapes it. A new session
        // does not: the kill is by cgroup, and shell cannot leave its cgroup.
        // /sys/fs/cgroup/cgroup.procs is system:system, shell is uid 2000 and
        // not in that group, and every tasks file on the device is read-only to
        // us. There is nowhere to escape to.
        //
        // So the session is closed and the setting is left alone. The cost is
        // real and worth stating: Wireless debugging stays on, which means
        // adbd keeps listening on whatever network the phone joins, and
        // Android keeps its own notification up. That is the exposure this
        // function was written to remove - but it was removing it by breaking
        // the recorder, and a call recorder that does not record is not a
        // safer call recorder.
        AdbTransport.close()
        Log.i(TAG, "daemon: up and serving loopback, ADB session closed")
    }

    /**
     * Ask the daemon to exit, over loopback.
     *
     * There is a stop() below that does this with `pkill` over ADB, and it is
     * useless exactly when it matters. Starting fresh closes the ADB session as
     * part of what it does, so by then there is no session left to send a kill
     * over, and the daemon survived every reset as an orphan holding port
     * 28472 until the phone was rebooted.
     *
     * Loopback has no such dependency, which is the whole reason this daemon
     * speaks over a socket in the first place. Only OUR daemon will take the
     * order: one from a previous install does not know this token and cannot
     * be told anything, so it is left to ensureRunning() to retire over ADB.
     */
    suspend fun quit(context: Context): Boolean =
        withContext(Dispatchers.IO) { quitReply(context) == QuitReply.BYE }

    /** What a daemon answers a quit with. BUSY means it is recording a call -
     *  or believes it is; see retire(). */
    internal enum class QuitReply { BYE, BUSY, NONE }

    /** "BYE" or "BUSY", as Main.session() writes them; anything else, or
     *  nothing, is no answer. */
    internal fun parseQuitReply(line: String?): QuitReply = when {
        line == null -> QuitReply.NONE
        line.startsWith("BYE") -> QuitReply.BYE
        line.startsWith("BUSY") -> QuitReply.BUSY
        else -> QuitReply.NONE
    }

    /** One quit, and what came back. Blocking; callers are on the IO dispatcher. */
    private fun quitReply(context: Context): QuitReply =
        try {
            open(context, COMMAND_QUIT, connectMs = 600, readMs = 1_500).use { socket ->
                parseQuitReply(socket.getInputStream().bufferedReader().readLine()).also { reply ->
                    Log.i(
                        TAG,
                        "daemon: quit " + when (reply) {
                            QuitReply.BYE -> "acknowledged"
                            QuitReply.BUSY -> "refused - it is recording a call"
                            QuitReply.NONE -> "not acknowledged"
                        },
                    )
                }
            }
        } catch (t: Exception) {
            Log.w(TAG, "daemon: quit failed - ${t.message}")
            QuitReply.NONE
        }

    /**
     * Whether a call may be up, judged by the audio mode: the signal the
     * daemon itself trusts to end a recording (Main.CallWatch.audioInCall), and
     * one this app can read without READ_PHONE_STATE, which it does not hold.
     * Anything but MODE_NORMAL counts - ringing, a call, a VoIP call,
     * screening - because getting this wrong in that direction only ever
     * leaves a daemon alone.
     */
    internal fun inCall(mode: Int): Boolean = mode != AudioManager.MODE_NORMAL

    internal fun callInProgress(context: Context): Boolean {
        val audio = context.getSystemService(AudioManager::class.java) ?: return true
        return inCall(audio.mode)
    }

    /** How asking an out-of-date daemon to make way turned out. */
    private enum class Retirement { QUIT, RECORDING, REFUSED, WEDGED }

    /**
     * ASK AN OUT-OF-DATE DAEMON TO MAKE WAY - AND DO NOT BELIEVE "BUSY" FOREVER.
     *
     * A daemon refuses to quit while it records a call, and that is right: a
     * call is worth more than an update being prompt. But it judges "recording"
     * by its own state, and that state can be stuck. Every build before the
     * call watch was held in a field (see Main.callWatch) could lose its watch
     * to garbage collection - and one that lost it MID-CALL never hears that
     * call end, so its recording never stops and it answers BUSY to every quit
     * for the rest of its life. The update that fixes the bug could then never
     * replace the daemon that has it.
     *
     * A real BUSY cannot outlast its call by much: the daemon ends a recording
     * within about two seconds of telecom releasing the call's audio mode. So a
     * BUSY with no call in progress is asked again, twice, six seconds apart,
     * and only a daemon still saying BUSY with the audio mode normal at every
     * look is called wedged. Any sign of a call at any point and it is left
     * alone, as before.
     */
    private suspend fun retire(context: Context): Retirement {
        var reply = quitReply(context)
        var asked = 1
        while (reply == QuitReply.BUSY) {
            if (callInProgress(context)) return Retirement.RECORDING
            if (asked > WEDGE_RECHECKS) return Retirement.WEDGED
            delay(WEDGE_RECHECK_MS)
            if (callInProgress(context)) return Retirement.RECORDING
            reply = quitReply(context)
            asked++
        }
        return if (reply == QuitReply.BYE) Retirement.QUIT else Retirement.REFUSED
    }

    /**
     * Make sure the daemon is running, starting it over ADB if not.
     *
     * This is the one operation in the whole app that needs a working ADB
     * session, so it is also the one that needs Wi-Fi. Call it at setup and
     * after a reboot - not when a call starts, by which time it may be too late.
     */
    suspend fun ensureRunning(context: Context): Result<Unit> = withContext(Dispatchers.IO) {
        lifecycle.withLock { ensureRunningLocked(context) }
    }

    private suspend fun ensureRunningLocked(context: Context): Result<Unit> {
        var retired = false
        val running = probeConfirmed(context)
        ReviveLog.note(context, "start: old recorder ${if (running != null) "answers" else "does not answer"}")
        if (running != null) {
            if (upToDate(context, running)) {
                Log.i(TAG, "daemon: already running")
                return Result.success(Unit)
            }
            if (!AdbTransport.isConnected) {
                Log.i(
                    TAG,
                    "daemon: running build ${running.build}, this app carries ${bundledBuild(context)}; " +
                        "replaced when there is a session",
                )
                return Result.success(Unit)
            }
            // Asked, not killed: it says BUSY while recording a call, and a
            // call is worth more than the update being prompt. The next
            // revive asks again - unless the BUSY cannot be true; see retire().
            Log.i(TAG, "daemon: replacing build ${running.build} with ${bundledBuild(context)}")
            when (retire(context)) {
                Retirement.QUIT -> retired = true
                Retirement.RECORDING -> {
                    Log.i(TAG, "daemon: it is recording a call, keeping the old build for now")
                    return Result.success(Unit)
                }
                Retirement.REFUSED -> {
                    Log.i(TAG, "daemon: it would not quit, keeping the old build for now")
                    return Result.success(Unit)
                }
                Retirement.WEDGED -> {
                    Log.w(
                        TAG,
                        "daemon: build ${running.build} still says BUSY with no call in progress - " +
                            "it lost the end of a call and will never quit; retiring it over ADB",
                    )
                    exec(PKILL)
                    // Its call never ended for the app either: CALL_ENDED was
                    // never sent, so a "Recording call" notification from it
                    // may still be up. Its file is collected like any orphan,
                    // by the reconcile that follows a revive.
                    if (CallMonitorService.running) CallMonitorService.stop(context)
                    retired = true
                }
            }
        }
        return runCatching {
            check(AdbTransport.isConnected) {
                "ADB is not connected, so the daemon cannot be started. " +
                    "Connect once over Wi-Fi; after that recording needs no network."
            }

            // ONLY KILL SOMETHING THAT IS ACTUALLY IN THE WAY.
            //
            // This killed unconditionally, and the reasoning was sound for the
            // case it was written for: a stale daemon from a previous version
            // owns port 28472 while failing the health check, so a fresh one
            // would fail to bind and exit, and the repair would loop against a
            // port that is never free.
            //
            // What it missed is that getting here does not mean the daemon is
            // gone - it means the ping did not come back. Off USB those are not
            // the same thing at all. Measured on this phone: with the recorder
            // switched on and the phone on battery, the daemon died within
            // twenty seconds, over and over, and each restart re-armed Wireless
            // debugging and posted Android's notification. With the recorder
            // switched off - nothing else changed, same battery, same phone -
            // the same daemon ran for minutes untouched. The system was never
            // killing it. This line was.
            //
            // So the question is no longer "did it answer" but "is anything
            // holding the port". Nothing there: nothing to kill, and the start
            // below has a clear run. Something there that will not answer even
            // now: that is the stale-daemon case this was written for, and it
            // still gets killed.
            if (retired) {
                // Ours, and it said BYE a moment ago: the port-free wait below
                // is all it needs, and the re-ask would only catch it on its
                // way out and call that "answers after all".
            } else if (portInUse()) {
                // SOMETHING HOLDS THE PORT. ASK IT AGAIN BEFORE KILLING IT.
                //
                // Getting here means the confirmed ping failed - but a held port
                // is strong evidence of a live daemon, and the pings can fail
                // against a live one: its accept loop was single-threaded and
                // its listen backlog was one, so two callers at once (the
                // keep-alive and a resume, say) had the second connect refused,
                // which reads as "dead" here and "port free" a moment later.
                // Measured: the daemon's log held twelve "port still held"
                // lines from a duplicate this spawned, interleaved with pings
                // the original was answering. The duplicate died on EADDRINUSE
                // and, worse, its spawn truncated the original's log - the one
                // record of what had happened. So a held port gets one more
                // patient ask, and an answer means there is nothing to do.
                if (isRunningConfirmed(context)) {
                    Log.i(TAG, "daemon: port $PORT is held by our daemon, which answers after all")
                    return@runCatching Unit
                }
                // Not ours, then. Say which kind of not-ours before retiring
                // it: the log is the only place this will ever be readable.
                val speaks = version()
                Log.w(
                    TAG,
                    when {
                        speaks == null ->
                            "daemon: port $PORT held by something that will not answer, retiring it"
                        speaks.version != PROTOCOL_VERSION ->
                            "daemon: port $PORT held by an out-of-date recorder (protocol ${speaks.version}), replacing it"
                        else ->
                            "daemon: port $PORT held by a recorder that does not know this app's token " +
                                "(a previous install?), replacing it"
                    },
                )
                exec(PKILL)
            } else {
                Log.i(TAG, "daemon: port $PORT is free, nothing to retire")
            }

            // WAIT FOR THE PORT, RATHER THAN BETTING ON A SLEEP.
            //
            // This was a flat 300ms, which is a wager that the kernel has
            // finished tearing the old process down. Lose it and the new daemon
            // binds onto a port its predecessor still holds, takes EADDRINUSE,
            // quits its accept loop and exits - leaving no daemon where a
            // working one stood a second earlier.
            var freed = false
            repeat(PORT_FREE_ATTEMPTS) {
                if (!freed) {
                    if (!portInUse()) freed = true else Thread.sleep(PORT_FREE_GAP_MS)
                }
            }
            if (!freed) Log.w(TAG, "daemon: port $PORT still held after pkill, starting anyway")
            ReviveLog.note(context, "start: port free: $freed")

            val staged = stageAsset(context)
            ReviveLog.note(context, "start: jar staged")
            Log.i(TAG, "daemon: staged jar at $staged")

            // The shell UID cannot read this app's private files, but it CAN
            // read the app's external files directory, so that is the handoff
            // point. From there the jar is copied onto /data/local/tmp, which
            // is shell-owned and a normal filesystem - dex loading from the
            // sdcard mount is not something to rely on.
            exec("cp '$staged' $DEVICE_PATH && chmod 600 $DEVICE_PATH && echo staged")
                .also { check(it.contains("staged")) { "could not copy the jar: $it" } }
            ReviveLog.note(context, "start: jar copied")

            // setsid is doing the real work here, and `nohup ... &` alone is
            // not enough - measured, not guessed. The identical command run
            // from a USB `adb shell` started the daemon fine, while through
            // this one-shot `shell:` service it left a zero-byte log and no
            // process.
            //
            // The difference is who closes the stream and when. exec() reads to
            // EOF and closes; stdout here is redirected to a file, so EOF is
            // immediate, the stream closes at once and adbd tears down the
            // whole process GROUP - taking the freshly backgrounded app_process
            // with it. nohup only blocks SIGHUP, which is not what arrives.
            //
            // setsid puts the daemon in a new session with its own process
            // group, so there is nothing left for that teardown to kill.
            //
            // The token and the mode ride in the ENVIRONMENT, not the
            // arguments. Arguments are visible to every app through
            // /proc/<pid>/cmdline; the environment of a shell-uid process is
            // readable only by shell and root. The token is what lets the
            // daemon wake this app's service without that service being
            // startable by anything else on the phone, and what every loopback
            // command is proved with. The mode is there so the daemon knows
            // from its first millisecond whether this call should be recorded,
            // asked about, or left alone - a push over the socket a moment
            // later used to be the only word it got, and a call in that gap
            // was recorded whatever the user had chosen.
            //
            // KEEP THE PREVIOUS INSTANCE'S LOG. It is the only durable record of
            // what happened before this spawn - logcat's main buffer rolls in
            // about a minute on this phone - and a spawn that truncated it has
            // already destroyed the one piece of evidence that mattered, once.
            // One generation back is enough to read a death; two would just be
            // clutter in /data/local/tmp.
            exec(
                "mv -f $LOG_PATH $LOG_PATH.1 2>/dev/null; " +
                    "JEMREC_TOKEN=${token(context)} JEMREC_MODE=${currentMode(context)} " +
                    "JEMREC_VOIP=${VoipApps.wire(context)} " +
                    "JEMREC_TRACK=${if (CallTracking.enabled(context)) 1 else 0} " +
                    "JEMREC_ANSWER=${AnsweringMachine.wire(context)} " +
                    "CLASSPATH=$DEVICE_PATH setsid nohup " +
                    "app_process / $MAIN_CLASS $PORT < /dev/null > $LOG_PATH 2>&1 &"
            )
            ReviveLog.note(context, "start: launch command returned")

            // Starting is not the same as listening, and listening is not the
            // same as working: app_process has a runtime to bring up and
            // Workarounds to apply first.
            //
            // Waiting on a ping rather than on a bare connect. A plain connect
            // proves only that something owns the port - it would be satisfied
            // by a stale daemon from a previous version - and it left
            // "session: unknown command -1" in the daemon's log every time,
            // because a connection that sends no command byte looks like a
            // client that hung up.
            var started = false
            var tries = 0
            repeat(20) {
                if (!started) {
                    Thread.sleep(250)
                    tries++
                    if (probe(context) != null) started = true
                }
            }
            ReviveLog.note(context, "start: ${if (started) "listening after $tries pings" else "not listening after $tries pings"}")
            if (!started) {
                val log = exec("cat $LOG_PATH")
                error("daemon did not start listening on $PORT. Its log said:\n$log")
            }
            Log.i(TAG, "daemon: listening on 127.0.0.1:$PORT")
            _changed.tryEmit(Unit)
            Unit
        }
    }

    /**
     * Open a LIVE capture session. No longer used for calls - the daemon
     * records those to a file - but the self-test still records a short clip
     * this way to prove the HAL hands over voice-call audio.
     */
    suspend fun connect(context: Context, timeoutMs: Int = 3_000): Socket = withContext(Dispatchers.IO) {
        open(context, COMMAND_RECORD, connectMs = timeoutMs, readMs = LIVE_READ_MS)
    }

    /**
     * Fetch a finished recording the daemon wrote during a call.
     *
     * The daemon streams the whole file back over this socket - the same framed
     * Opus the app used to read live, but complete and at rest. Reading it is
     * offline: the OS can throttle this app to a crawl and the only cost is a
     * slower copy, never lost audio, because the audio is already on disk.
     */
    suspend fun fetch(context: Context, name: String, timeoutMs: Int = 3_000): Socket =
        withContext(Dispatchers.IO) {
            open(context, COMMAND_FETCH, nameFrame(name), connectMs = timeoutMs, readMs = FETCH_READ_MS)
        }

    /**
     * Every recording still sitting on the daemon, one name per line. The app
     * asks for this on startup to collect anything a crash or a reboot left
     * behind between hang-up and the fetch.
     */
    suspend fun listPending(context: Context): List<String> = listPendingOrNull(context) ?: emptyList()

    /** The same, but null when the daemon could not be asked - which the
     *  missed-call check must not mistake for "nothing pending". */
    suspend fun listPendingOrNull(context: Context): List<String>? = withContext(Dispatchers.IO) {
        runCatching {
            open(context, COMMAND_LIST).use { socket ->
                socket.getInputStream().bufferedReader().readLines()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
            }
        }.getOrNull()
    }

    /** Delete a recording from the daemon's scratch area once it is saved. */
    suspend fun deleteRecording(context: Context, name: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            open(context, COMMAND_DELETE, nameFrame(name), readMs = 1_500).use { socket ->
                val reply = ByteArray(3)
                val n = socket.getInputStream().read(reply)
                n >= 2 && reply[0] == 'O'.code.toByte()
            }
        }.getOrDefault(false)
    }

    /**
     * Push the recording policy to the daemon: OFF (recorder switch off),
     * AUTOMATIC (record every call from off-hook), or ON_DEMAND (record nothing
     * until the user answers the start-of-call prompt with yes). The shell UID
     * cannot read the app's prefs, so the daemon has to be told. Best-effort;
     * a daemon that has never been told records nothing.
     */
    suspend fun setDaemonMode(context: Context, mode: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            open(context, COMMAND_SET_ENABLED, byteArrayOf(mode.toByte()), readMs = 1_500).use { socket ->
                socket.getInputStream().read() >= 0
            }
        }.getOrDefault(false)
    }

    /**
     * On-demand yes: tell the daemon to start recording the call in progress,
     * from now. Returns the recording's name, or null if it could not start
     * (usually the call ended first). The daemon captures the rest of the call
     * to a file, which the app collects at CALL_ENDED like any other.
     */
    suspend fun startOnDemand(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            open(context, COMMAND_START).use { socket ->
                socket.getInputStream().bufferedReader().readLine()?.trim()?.ifBlank { null }
            }
        }.getOrNull()
    }

    /**
     * What the daemon says its call watch has heard - see Main.COMMAND_WATCH
     * for why the self-test needs it. Times are wall-clock millis on this
     * phone, 0 for never.
     */
    internal data class Watch(
        /** When the daemon started: calls before this are not its business. */
        val since: Long,
        val watching: Boolean,
        /** Whether the thread that delivers call events still runs. */
        val looperOk: Boolean,
        val mode: Int,
        /** Whether the daemon believes a call is up right now. */
        val inCall: Boolean,
        val events: Int,
        val lastEvent: Long,
        val offHooks: Int,
        val lastOffHook: Long,
        /** When the recording in progress started, or 0 if there is none. */
        val recordingSince: Long,
        /** How many messenger apps it was told to record, and whether its
         *  audio policy is registered; null from a daemon too old to say. */
        val voipApps: Int? = null,
        val voipArmed: Boolean = false,
        /** Whether it has exact call tracking allowed; null if it cannot say. */
        val tracking: Boolean? = null,
        /** Whether its answering machine is on and it holds a greeting; null
         *  from a recorder too old to say. */
        val answer: Boolean? = null,
        val greeting: Boolean? = null,
    )

    /** "WATCH 1 since=... watching=1 looper=ok ..."; null for anything else,
     *  including a format this app does not know. */
    internal fun parseWatch(line: String?): Watch? {
        val parts = line?.trim()?.split(' ')?.filter { it.isNotEmpty() } ?: return null
        if (parts.size < 2 || parts[0] != "WATCH" || parts[1] != "1") return null
        val fields = parts.drop(2).associate { it.substringBefore('=') to it.substringAfter('=', "") }
        fun long(key: String) = fields[key]?.toLongOrNull()
        fun int(key: String) = fields[key]?.toIntOrNull()
        return Watch(
            since = long("since") ?: return null,
            watching = fields["watching"] == "1",
            looperOk = fields["looper"] == "ok",
            mode = int("mode") ?: return null,
            inCall = fields["call"] == "1",
            events = int("events") ?: return null,
            lastEvent = long("lastevent") ?: return null,
            offHooks = int("offhooks") ?: return null,
            lastOffHook = long("lastoffhook") ?: return null,
            recordingSince = long("recording") ?: return null,
            voipApps = int("voip"),
            voipArmed = fields["voiparmed"] == "1",
            tracking = fields["tracking"]?.let { it == "1" },
            answer = fields["answer"]?.let { it == "1" },
            greeting = fields["greeting"]?.let { it == "1" },
        )
    }

    /** The daemon's report on its call watch; null if there is none to be
     *  had, most often from a daemon too old to give one - it hangs up on the
     *  command. */
    internal suspend fun watch(context: Context): Watch? = withContext(Dispatchers.IO) {
        runCatching {
            open(context, COMMAND_WATCH).use { socket ->
                parseWatch(socket.getInputStream().bufferedReader().readLine())
            }
        }.getOrNull()
    }

    /** The recorder's account of the last call it recorded, for Copy a
     *  report (LastCallReport); null if there is none to be had - no
     *  recorder, or one too old to know the command. */
    internal suspend fun callReport(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching {
            open(context, COMMAND_REPORT).use { socket ->
                socket.getInputStream().bufferedReader().readText().trim().ifBlank { null }
            }
        }.getOrNull()
    }

    /** Two big-endian length bytes then the UTF-8 name, as the daemon reads it. */
    private fun nameFrame(name: String): ByteArray {
        val bytes = name.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 512) { "recording name too long" }
        return byteArrayOf((bytes.size ushr 8).toByte(), bytes.size.toByte()) + bytes
    }

    suspend fun readLog(): String = exec("cat $LOG_PATH")

    suspend fun stop(): String = exec("$PKILL; echo stopped")

    /**
     * Copy the bundled jar somewhere the shell UID can read it.
     *
     * Rewritten every time rather than cached: it costs 24 KB and a few
     * milliseconds, and it means an app update cannot leave a stale daemon jar
     * behind to be launched against mismatched app code.
     */
    private fun stageAsset(context: Context): String {
        val dir = context.getExternalFilesDir(null)
            ?: error("no external files directory, so there is nowhere the shell UID can read from")
        val out = File(dir, ASSET)
        context.assets.open(ASSET).use { input ->
            out.outputStream().use { input.copyTo(it) }
        }
        return out.absolutePath
    }

    private suspend fun exec(command: String): String =
        AdbTransport.exec(command).getOrElse { "<failed: ${it.message}>" }
}
