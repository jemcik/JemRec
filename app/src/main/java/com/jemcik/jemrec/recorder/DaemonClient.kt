package com.jemcik.jemrec.recorder

import android.content.Context
import android.util.Log
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.answering.AnsweringMachine
import com.jemcik.jemrec.answering.Greeting
import com.jemcik.jemrec.calls.CallTracking
import com.jemcik.jemrec.calls.CoverageHistory
import com.jemcik.jemrec.calls.VoipApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * The app's side of the recorder's protocol: the handshake every command is
 * proved with, each command and the reading of its reply, and the token they
 * share. Over loopback, port 28472 - no ADB, no Wi-Fi (CaptureDaemon says
 * why). Whether a recorder is up, and starting one, is DaemonLauncher's.
 */
object DaemonClient {

    private const val TAG = "JemRec"

    private const val KEY_TOKEN = "daemon_token"

    // Sized as the launcher's PING_ATTEMPTS: for a throttled CPU.
    internal const val PING_CONNECT_MS = 3_000
    internal const val PING_READ_MS = 3_000
    private const val PORT_PROBE_MS = 200

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
    internal const val PROTOCOL_VERSION = 2
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
    internal fun probe(context: Context): Answer? =
        try {
            open(context, COMMAND_PING, connectMs = PING_CONNECT_MS, readMs = PING_READ_MS).use { socket ->
                parsePong(socket.getInputStream().bufferedReader().readLine())
            }
        } catch (_: Exception) {
            null
        }

    /**
     * What is on the port, without a handshake: whatever answers a bare ping,
     * or null if nothing does. For telling the log WHY a daemon is being
     * replaced, not for trusting it.
     */
    internal fun version(): Answer? =
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

    /** Whether anything at all holds the port, answering or not. */
    internal fun portInUse(): Boolean =
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
    internal fun currentMode(context: Context): Int = when (CoverageHistory.current(context)) {
        CoverageHistory.State.OFF -> MODE_OFF
        CoverageHistory.State.AUTOMATIC -> MODE_AUTOMATIC
        CoverageHistory.State.ASK_FIRST -> MODE_ON_DEMAND
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
    internal fun quitReply(context: Context): QuitReply =
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
}
