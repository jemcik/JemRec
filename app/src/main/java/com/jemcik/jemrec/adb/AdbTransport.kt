package com.jemcik.jemrec.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.jemcik.jemrec.recorder.NetworkRevive
import com.jemcik.jemrec.recorder.PortChange
import com.jemcik.jemrec.system.GlobalSettings
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbConnection
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException

/**
 * Concrete [AbsAdbConnectionManager] bound to this app's persisted identity.
 *
 * The only interesting line is setApi(). It tells the library which wire
 * protocol the daemon on the other end speaks. We are always talking to the
 * adbd on the same phone the app is running on, so the running SDK_INT is by
 * definition correct - there is no remote device whose version could differ.
 */
private class JemAdbConnectionManager(context: Context) : AbsAdbConnectionManager() {

    private val identity = AdbIdentity.getOrCreate(context)

    init {
        setApi(Build.VERSION.SDK_INT)
        // The library's default is to wait forever - in connect, holding its
        // lock, which every other call (isConnected too) then waits on. A peer
        // that accepts and never answers stalled every revive after it.
        setTimeout(10, TimeUnit.SECONDS)
    }

    override fun getPrivateKey(): PrivateKey = identity.privateKey

    override fun getCertificate(): Certificate = identity.certificate

    /** Shown on the phone's "paired devices" list in Wireless debugging. */
    override fun getDeviceName(): String = "JemRec"
}

/**
 * The embedded ADB client: pair with this phone's own Wireless debugging, hold
 * a TLS connection to its adbd over loopback, and run commands at shell
 * privilege.
 *
 * WHY LOOPBACK IS THE POINT, NOT A DETAIL
 *
 * adbd's TLS listener binds to INADDR_ANY, not to the Wi-Fi address. Measured
 * on the target device with Wireless debugging on: `ss -ltn` shows `*:42321`.
 * That wildcard bind is what lets a process on the phone itself reach it at
 * 127.0.0.1, and it is why this design does not need a companion app, a PC, or
 * a network. libadb agrees - its own AndroidUtils.getHostIpAddress() returns
 * the loopback address, so connecting to ourselves is the library's intended
 * use, not a trick played on it.
 *
 * The unsolved half is DISCOVERY, not connection. The port above is ephemeral
 * and is advertised over mDNS, which needs a live network interface. With Wi-Fi
 * off there is nothing to browse, so the port has to be either remembered or
 * pinned. Pinning is what Start without Wi-Fi does: adbd's classic door, on a
 * number of JemRec's own since 1.11 (5555 before), which listens with or
 * without a network (StartWithoutWifi).
 */
object AdbTransport {

    private const val TAG = "JemRec"

    /** adbd is on this phone. There is no other host this could ever be. */
    const val LOOPBACK = "127.0.0.1"

    /**
     * adbd's classic ADB-over-TCP port, the one a computer's `adb tcpip`
     * opens and what Start without Wi-Fi opened before 1.11, when it took a
     * number of its own (StartWithoutWifi). Only reachable if something has
     * set `service.adb.tcp.port` - or on an emulator, which listens on it
     * with none set; tried as a last resort because when it IS set it is
     * stable across Wi-Fi state, which no mDNS-discovered port is.
     */
    const val LEGACY_TCP_PORT = 5555

    /** `exec:` rather than `shell:` so adbd allocates no PTY. See ShellSession. */
    private const val SHELL_SERVICE = "exec:sh"

    @Volatile
    private var manager: AbsAdbConnectionManager? = null

    /** The one reused shell, and the lock that stops two commands interleaving
     *  on it. A shared stream with a sentinel protocol is only correct while
     *  exactly one caller is writing to it. Volatile: disconnect and close
     *  drop it outside the lock that exec reads it under. */
    @Volatile
    private var shell: ShellSession? = null
    private val mutex = Mutex()

    private fun manager(context: Context): AbsAdbConnectionManager =
        manager ?: synchronized(this) {
            manager ?: JemAdbConnectionManager(context.applicationContext).also { manager = it }
        }

    /**
     * The last answer isConnected gave, without asking again - for the main
     * thread, which must not wait on the library's lock: a connect holds it
     * for as long as it takes.
     */
    @Volatile
    var lastKnownConnected: Boolean = false
        private set

    /**
     * The port the live connection was made on, 0 for none. On adbd's
     * classic door - Start without Wi-Fi's port, or anyone's; autoConnect's
     * last resort, where Wireless debugging cannot be had - closing that
     * port would take the only way back to the recorder with it
     * (CaptureDaemon.portChangeOver). The port the connect that made the
     * session asked for ([made]), read from the session shown: before 1.11
     * every connect that found one up set the port it had asked for, which
     * two connects at once could make another session's (review round 5).
     */
    val connectedPort: Int
        get() = made?.takeIf { it.first === live() }?.second ?: 0

    /** The session libadb made last, and the port the connect that made it
     *  asked for. */
    @Volatile
    private var made: Pair<AdbConnection, Int>? = null

    /**
     * libadb's connect, once, with the session it made noted against [port]
     * ([made]). It gives true only to the call that made one and false at
     * once to a call that finds one up - but false too where its 10 s ran
     * out and the session it stored finishes after (AbsAdbConnectionManager
     * .connect stores the new AdbConnection first; AdbConnection
     * .waitForConnection; javap, 3.1.1). So a session that is not the one
     * there before is this call's, unless the call that made it says so -
     * which a true always does, last (review round 6 of 1.11).
     */
    private fun libadbConnect(mgr: AbsAdbConnectionManager, host: String, port: Int): Boolean {
        val before = mgr.adbConnection
        var ok = false
        try {
            ok = mgr.connect(host, port)
            return ok
        } finally {
            mgr.adbConnection?.let { conn ->
                if (ok || (conn !== before && made?.first !== conn)) made = conn to port
            }
        }
    }

    /**
     * The session that showed itself as this phone's shell (ShellProof), or
     * null: the only one anything is run over. libadb makes a new
     * AdbConnection for every connect it makes (AbsAdbConnectionManager
     * .connect, libadb 3.1.1, read with javap), so which object it is says
     * whether it was shown. One whose proof is under way counts as not
     * connected for every caller, and nothing is run over it: before, a
     * command queued behind the proof ran on the session it was proving
     * (review round 4 of 1.11).
     */
    @Volatile
    private var shown: AdbConnection? = null

    /** [shown], while it is up. */
    private fun live(): AdbConnection? = shown?.takeIf { it.isConnected && it.isConnectionEstablished }

    /** A session up that has shown itself as the phone's shell. */
    val isConnected: Boolean
        get() = try {
            (live() != null).also { lastKnownConnected = it }
        } catch (t: Throwable) {
            lastKnownConnected = false
            false
        }

    /**
     * Run the SPAKE2 pairing exchange. On success adbd permanently stores our
     * public key, and every later connect() needs no code.
     *
     * The code and the pairing port are both short-lived: they exist only while
     * the "Pair device with pairing code" dialog is open, and the port is not
     * the same as the connect port.
     */
    suspend fun pair(context: Context, host: String, port: Int, code: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                Log.i(TAG, "pair: $host:$port")
                val ok = manager(context).pair(host, port, code)
                check(ok) { "adbd rejected the pairing code" }
                Log.i(TAG, "pair: accepted")
                Unit // Log.i returns an Int; without this the block is Result<Int>
            }
        }

    /**
     * Connect to an explicitly known port.
     *
     * libadb's connect() returns false for TWO different things - the attempt
     * failed, and "it has already been made" - which its own Javadoc says
     * outright. Treating that boolean as success/failure reports a perfectly
     * good connection as broken, and the symptom is not obvious: connect says
     * it failed, everything afterwards works or does not depending on whether
     * anything checks. isConnected() is what actually settles it.
     */
    suspend fun connect(context: Context, host: String, port: Int): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val mgr = manager(context)
                val app = context.applicationContext
                // One libadb holds already: shown, or shown now (prove), on
                // whichever port it was made.
                if (mgr.isConnected && prove(app, mgr, 0) == null) {
                    Log.i(TAG, "connect: already connected")
                    return@runCatching
                }
                Log.i(TAG, "connect: $host:$port")
                val ok = libadbConnect(mgr, host, port)
                check(ok || mgr.isConnected) { "connect refused by adbd at $host:$port" }
                prove(app, mgr, port)?.let { why -> error("the session on $port $why") }
                Log.i(TAG, "connect: established")
            }
        }

    /** A cancel is passed on, not kept as a failed command or look:
     *  runCatching catches it too (kotlinx.coroutines #1814), and the
     *  caller's scope is gone (review round 5 of 1.11). connect() needs
     *  none: nothing follows its runCatching, and withContext throws the
     *  cancel on its way out. */
    internal fun passOnCancel(result: Result<*>) {
        result.exceptionOrNull()?.let { if (it is CancellationException) throw it }
    }

    /** Said of a session that did not show itself as this phone's shell. */
    internal const val NOT_SHELL = "did not show itself as the phone's shell"

    /** Said of a session that went down between its connect and its proof:
     *  adbd restarted, or the cable or a USB mode change took it - or, one
     *  found up, another caller's failed proof closed it. */
    internal const val GONE = "was gone before its proof"

    /**
     * A proof's own wait: the shell opened (its 400 ms settle) and one `cat`,
     * 0.4 s a connect on the Android 16 AVD (2026-11-27). Ten seconds, as a
     * phone busy at boot is slower, and the first command on a session had
     * exec's 15 s before 1.11; each proof's time is on its log line.
     */
    internal const val PROOF_MS = 10_000L

    /** Debug builds only: act as a session that is not the phone's shell,
     *  running this in place of the proof's `cat` - one that answers wrong,
     *  or one that does not answer in time (diag standin). Nothing in the
     *  release build sets it. */
    @Volatile
    internal var standIn: String? = null

    /** How long the last proof took, ms; -1 before any. For the debug
     *  build's connect op: the Honor's logcat is not readable. */
    @Volatile
    internal var lastProofMs: Long = -1
        private set

    /**
     * The session libadb has just made, or the one it holds, shows it is
     * this phone's shell before anything goes over it (ShellProof) - or it is
     * let go and not used. Every new session: the revive's, setup's, the
     * pairing's, Start fresh's. One shell round trip, under exec's lock: no
     * command waits behind it to run on a session not yet shown ([shown]),
     * and one that fails is closed before the lock goes - that session
     * alone, not another caller's. Null when it is shown; else why not
     * ([NOT_SHELL], or [GONE] where there was none up to prove). A caller
     * cancelled meanwhile gets its cancel, and the session is left as it
     * is, for the next caller to prove (review round 5).
     */
    private suspend fun prove(app: Context, mgr: AbsAdbConnectionManager, port: Int): String? {
        // Shown already: nothing to wait on the lock for, which a command
        // can hold for its 15 s ([shown] is set under it, once proved).
        mgr.adbConnection?.let { if (it === live()) return null }
        return mutex.withLock { proveLocked(app, mgr, port) }
    }

    private suspend fun proveLocked(app: Context, mgr: AbsAdbConnectionManager, port: Int): String? {
        val where = if (port > 0) "the session on $port" else "the session"
        val conn = mgr.adbConnection?.takeIf { it.isConnected && it.isConnectionEstablished }
        if (conn == null) {
            // A session found up a moment before is gone too where another
            // caller's proof failed and closed it; this caller then connects
            // afresh, nothing to tell. Its own connect's is told.
            if (port > 0) Log.w(TAG, "connect: $where $GONE")
            return GONE
        }
        if (conn === shown) return null
        val began = SystemClock.elapsedRealtime()
        val ok = ShellProof.proved(app.getExternalFilesDir(null), run = { command ->
            runOnShell(conn, standIn ?: command, PROOF_MS).getOrNull()
        })
        val ms = SystemClock.elapsedRealtime() - began
        lastProofMs = ms
        return if (ok) {
            shown = conn
            Log.i(TAG, "connect: $where is the phone's shell ($ms ms)")
            null
        } else {
            Log.w(TAG, "connect: $where $NOT_SHELL ($ms ms); let go")
            if (shell?.connection === conn) forgetShell()
            runCatching { conn.close() }
            NOT_SHELL
        }
    }

    /** The classic ports a connect may dial: [given], or the rule
     *  (PortChange.classicPorts) - only ports adbd's own properties name. */
    internal fun classicPortsFor(context: Context, given: List<Int>?): List<Int> =
        given ?: PortChange.classicPorts(context)

    /**
     * Find adbd without being told where it is, in the order that degrades
     * best: Wireless debugging's port (AdbdFinder: mDNS, each port checked by
     * adbd's own answer, then the loopback probe), then adbd's classic port
     * (correct whenever someone has pinned it, including with Wi-Fi off) -
     * [classicPorts], in turn - only ones adbd's own properties name
     * ([classicPortsFor], every caller's: none on a phone with no port set),
     * read off the main thread - and only then give up. Each try is kept ([attempts]) for the setup report.
     */
    suspend fun autoConnect(
        context: Context,
        timeoutMs: Long = 5_000,
        classicPorts: List<Int>? = null,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            lastConnect = ConnectOutcome.NONE
            val app = context.applicationContext
            var found: AdbdFinder.Found? = null
            var failure: String? = null
            // Each classic port that did not take the connect, and why.
            val classicFailed = mutableListOf<ClassicTry>()
            // Here, on IO: a default argument is worked out on the caller's
            // thread, and the screen's looks call from the main one, where a
            // getprop may not run (review round 3 of 1.11).
            val dial = classicPortsFor(app, classicPorts)
            var tries = 0
            var usedPort = 0
            // This call's own outcome, kept apart from lastConnect, which a
            // connect running at the same time sets too: the attempt's line
            // said NONE for one that had failed (the Android 13 AVD, 2026-11-26).
            var outcome = ConnectOutcome.NONE
            val result = runCatching {
                val mgr = manager(app)
                if (mgr.isConnected && prove(app, mgr, 0) == null) {
                    Log.i(TAG, "autoConnect: already connected")
                    outcome = ConnectOutcome.CONNECTED
                    return@runCatching
                }

                val job = coroutineContext.job
                val where = AdbdFinder(LiveFinderWorld(app, job)).find(timeoutMs).also { found = it }
                // The finder notices a cancel and gives no port, as for none
                // found: thrown here, the look ends - no classic port dialled
                // for a caller that is gone (review round 6 of 1.11).
                job.ensureActive()
                val port = where.port
                if (port != null) {
                    // See connect() above: false is ambiguous, isConnected is
                    // not. adbd answered on this port as adbd, so a failure
                    // that stays is adbd turning this key away at the TLS
                    // handshake - which is what a forgotten pairing looks
                    // like - not a port it left (before 1.9 an old port mDNS
                    // gave was counted as a refusal too).
                    val tried = connectTrying(mgr, port, job)
                    tries = tried.tries
                    tried.thrown?.let { failure = causeOf(it) }
                    val accepted = tried.accepted
                    job.ensureActive()
                    val unproved = if (accepted) prove(app, mgr, port) else null
                    if (accepted && unproved == null) {
                        outcome = ConnectOutcome.CONNECTED
                        usedPort = port
                        return@runCatching
                    }
                    if (accepted) {
                        // Taken, but not shown to be the phone's shell: let go,
                        // and no refusal - nothing turned this key away, so no
                        // lost pairing is to be read into it.
                        failure = unproved
                        outcome = ConnectOutcome.NOT_FOUND
                    } else {
                        if (failure == null) failure = "no answer in ${CONNECT_TIMEOUT_S} s"
                        outcome = ConnectOutcome.REFUSED
                    }
                    usedPort = port
                } else {
                    outcome = ConnectOutcome.NOT_FOUND
                }

                // Each in turn, and each one's failure kept: its exception's
                // words, the proof's, or "not accepted" where the connect
                // said no. Before, only the last port's was said (review
                // round 1 of 1.11).
                var classic: Int? = null
                var thrown: Throwable? = null
                for (port in dial) {
                    // Tried again the same way: a classic port dialled the
                    // moment a failed proof closed the session before it is
                    // turned away by the same name clash (the Android 12
                    // AVD, 2026-11-28: "Connection failed" 3 ms after).
                    val tried = connectTrying(mgr, port, job)
                    tried.thrown?.let { thrown = it }
                    val unproved = if (tried.accepted) prove(app, mgr, port) else null
                    if (tried.accepted && unproved == null) {
                        classic = port
                        break
                    }
                    classicFailed += ClassicTry(port, unproved ?: tried.thrown?.let(::causeOf) ?: NOT_ACCEPTED)
                }
                if (classic == null) {
                    // What Wireless debugging's port did first, as the revive
                    // notes it: on a phone with no port set, all there is to
                    // say (review round 4 of 1.11) - and then every classic
                    // port's own failure.
                    val wireless = when {
                        usedPort == 0 -> "no adbd found by mDNS"
                        failure == NOT_SHELL || failure == GONE -> "the session on $usedPort $failure"
                        else -> "adbd on $usedPort did not take the connect ($failure)"
                    }
                    if (dial.isEmpty()) error("$wireless, and no classic port set")
                    val words = "$wireless, and ${classicWords(classicFailed)}"
                    thrown?.let { throw IllegalStateException(words, it) }
                    error(words)
                }
                classicFailed.clear()
                outcome = ConnectOutcome.CONNECTED
                usedPort = classic
            }
            // Before anything is kept of it: a look a closed screen cancelled
            // says nothing about adbd - thrown or only noticed.
            passOnCancel(result)
            coroutineContext.ensureActive()
            lastConnect = outcome
            if (outcome != ConnectOutcome.CONNECTED || found != null) {
                val attempt = Attempt(
                    atMs = System.currentTimeMillis(),
                    outcome = outcome,
                    found = found,
                    port = usedPort,
                    failure = failure,
                    classic = classicFailed.toList(),
                    tries = tries,
                )
                remember(attempt)
                // A failure at W, as the runs' logs are read for (review round 1).
                if (outcome == ConnectOutcome.CONNECTED) {
                    Log.i(TAG, "autoConnect: ${attempt.line()}")
                } else {
                    Log.w(TAG, "autoConnect: ${attempt.line()}")
                }
            }
            result
        }

    /** A connect on [port] as [connectTrying] made it. */
    private class Tried(val accepted: Boolean, val tries: Int, val thrown: Throwable?)

    /**
     * libadb's connect on [port], a quick failure tried again: adbd names a
     * connection host-<fd> and turns a new one away while a connection of
     * that name, just closed, is still listed (validate_transport_list,
     * EALREADY) - AdbdCheck's own, closed a moment before, whose fd the new
     * one reuses, or a session a failed proof has just closed. adbd's trace
     * on the Android 13 AVD under load, 2026-11-26: "socket transport
     * host-90 is already in transport_list and fails to register", 8 and 4
     * failed connects in 20. Wireless debugging's port and each classic
     * one, the same way ([againAfter]). [Tried.thrown] is the last failure
     * thrown, if any.
     */
    private suspend fun connectTrying(mgr: AbsAdbConnectionManager, port: Int, job: Job): Tried {
        var tries = 0
        var thrown: Throwable? = null
        while (true) {
            tries++
            val began = SystemClock.elapsedRealtime()
            val attempt = runCatching { libadbConnect(mgr, LOOPBACK, port) }
            attempt.exceptionOrNull()?.let { thrown = it }
            val accepted = attempt.getOrDefault(false) || mgr.isConnected
            val again = againAfter(attempt.exceptionOrNull(), SystemClock.elapsedRealtime() - began)
            if (accepted || !again || tries >= CONNECT_TRIES || !job.isActive) return Tried(accepted, tries, thrown)
            delay(AGAIN_MS)
        }
    }

    /**
     * Whether a connect that failed in [tookMs] is tried again: a quick one
     * that got as far as adbd - the name clash above, adbd closing the
     * connection it had taken - but not a socket refused, where nothing
     * listens on the port, which a retry half a second later does not
     * change: on the emulators with 5555 shut, each look tried it three
     * times, a second longer (2026-11-28). A slow failure - no answer - is
     * not tried again either.
     */
    internal fun againAfter(failure: Throwable?, tookMs: Long): Boolean =
        tookMs < QUICK_FAILURE_MS && generateSequence(failure) { it.cause?.takeIf { cause -> cause !== it } }
            .none { it is java.net.ConnectException }

    /**
     * One [autoConnect] as it went, for a log line and the setup report.
     * Ports and Android's own error words only: nothing personal (no
     * address, no network name, no service name - adbd's names it after the
     * phone's serial number).
     */
    internal data class Attempt(
        val atMs: Long,
        val outcome: ConnectOutcome,
        val found: AdbdFinder.Found?,
        /** The port connected on, or adbd's port that refused; 0 for none. */
        val port: Int,
        /** Why adbd's port did not take the connect. */
        val failure: String?,
        /** Why adbd's classic ports did not either, where they were tried:
         *  each of them, in turn. */
        val classic: List<ClassicTry> = emptyList(),
        /** Connects made on adbd's port: more than one where a quick failure
         *  was tried again. */
        val tries: Int = 1,
    ) {
        fun line(): String = buildString {
            append(outcome.name)
            if (port > 0) append(" on ").append(port)
            if (tries > 1) append(" (").append(tries).append(" tries)")
            found?.let { append("; ").append(it.summary()) }
            failure?.let { append("; adbd: ").append(it) }
            if (classic.isNotEmpty()) append("; ").append(classicWords(classic))
        }
    }

    /** A classic port tried, and why it did not take the connect. */
    internal data class ClassicTry(val port: Int, val why: String)

    /** Said of a classic port whose connect gave no session and no error. */
    internal const val NOT_ACCEPTED = "not accepted"

    /**
     * [tried] in one line, every port in turn - the revive's note, the
     * setup report and PortChange.connectOnPort's failure say the same: a
     * phone whose port property cannot be read is dialled on JemRec's own
     * number and then on 5555, and the note named only 5555's failure
     * (review round 1 of 1.11).
     */
    internal fun classicWords(tried: List<ClassicTry>): String = tried.joinToString("; ") { (port, why) ->
        if (why == NOT_SHELL || why == GONE) "the session on $port $why" else "port $port: $why"
    }

    private val recent = ArrayDeque<Attempt>()

    private fun remember(attempt: Attempt) = synchronized(recent) {
        recent.addFirst(attempt)
        while (recent.size > KEPT_ATTEMPTS) recent.removeLast()
    }

    /** The last [KEPT_ATTEMPTS] of [autoConnect], newest first. */
    internal fun attempts(): List<Attempt> = synchronized(recent) { recent.toList() }

    private const val KEPT_ATTEMPTS = 5

    /** JemAdbConnectionManager's connect timeout. */
    private const val CONNECT_TIMEOUT_S = 10

    /** A connect turned away by adbd's name clash fails within milliseconds;
     *  one that takes longer is something else, and is not tried again. */
    private const val QUICK_FAILURE_MS = 2_000L
    private const val CONNECT_TRIES = 3
    private const val AGAIN_MS = 500L

    /**
     * What a failed connect says, with libadb's wrapping taken off: a socket
     * it could not open comes back as `new IOException().initCause(it)`, no
     * message of its own (AdbConnection.java, libadb 3.1.1), which left the
     * log saying "refused us - null" whatever happened (#140).
     */
    internal fun causeOf(t: Throwable): String {
        var root = t
        while (root.message == null && root.cause != null && root.cause !== root) root = root.cause!!
        return "${root.javaClass.simpleName}: ${root.message ?: "no detail"}"
    }

    /** The phone, for [AdbdFinder]; [job] is the caller's, whose end stops
     *  the look and the probe (a screen closed mid-connect). */
    private class LiveFinderWorld(private val context: Context, private val job: Job) : AdbdFinder.World {
        override fun look(): AdbdFinder.Look = MdnsLook(context)
        override fun check(port: Int) = AdbdCheck.check(port)

        /** No server to find: Wireless debugging read off (unread, it may
         *  be on), or no Wi-Fi - Android runs it only on a Wi-Fi network and
         *  switches it off when the Wi-Fi goes (AdbDebuggingManager,
         *  getCurrentWifiApInfo and its network receiver, android15-release). */
        override fun noProbe(): String? = when {
            GlobalSettings.read(context, GlobalSettings.ADB_WIFI_ENABLED) == GlobalSettings.Reading.OFF ->
                "Wireless debugging is off"
            !NetworkRevive.onWifi(context) -> "no Wi-Fi"
            else -> null
        }

        override fun probe() = LoopbackProbe(cancelled = ::cancelled).run()
        override fun now() = SystemClock.elapsedRealtime()
        override fun cancelled() = !job.isActive
    }

    /**
     * The kept shell closed and dropped - by runOnShell, under exec's lock,
     * when it is a stream of another connection than the one a command runs
     * on. A new connection is made only when the last one has died - adbd
     * restarted, stopped with USB debugging - and the kept shell is a stream
     * of the dead one: the first command after the reconnect wrote to it and
     * failed "Stream closed". Measured on the Honor, 2026-10-27: the
     * recorder's start after Turn all debugging off and on failed at copying
     * the jar, and the next try, with the shell then dropped, started it.
     * Until 1.11 a connect dropped it before connecting, outside the lock,
     * where it could close another caller's shell mid-command.
     */
    private fun forgetShell() {
        runCatching { shell?.close() }
        shell = null
    }

    /**
     * Have adbd restart itself listening on its classic port too
     * (`tcpip:<port>`), or no longer (`usb:`) - what `adb tcpip` and `adb usb`
     * send. adbd sets service.adb.tcp.port, answers one line and restarts, so
     * this connection - and every process adbd started, the recorder too -
     * goes with it; the caller closes this side and connects again. The line
     * adbd answered, as "restarting in TCP mode port: 5555" - or a failure
     * that means nothing about the restart: on the Honor, 2026-10-28, `usb:`
     * restarted adbd before its line arrived ("Stream closed."), and the
     * property was cleared all the same.
     */
    suspend fun restartAdbd(service: String, timeoutMs: Long = 5_000): Result<String> =
        withContext(Dispatchers.IO) {
            if (live() == null) return@withContext notConnected()
            mutex.withLock {
                // Under the lock: the session shown, still up (prove).
                val conn = live() ?: return@withLock notConnected()
                forgetShell()
                // A stream that never answers is let go by closing the whole
                // connection, as exec's is (watched); it is about to die anyway.
                watched(timeoutMs, onTimeout = { runCatching { conn.close() } }) {
                    runCatching {
                        Log.i(TAG, "restartAdbd: $service")
                        conn.open(service).openInputStream().bufferedReader().readLine().orEmpty().trim()
                    }
                }
            }
        }

    private fun notConnected(): Result<String> = Result.failure(IllegalStateException("ADB is not connected"))

    /**
     * How the last autoConnect went, for the recorder-down alert (see
     * RecorderHealth): an adbd that advertises itself and then turns us away
     * is telling us something different from one that is not there at all.
     * NONE until an attempt this process has made says otherwise.
     */
    enum class ConnectOutcome { NONE, CONNECTED, NOT_FOUND, REFUSED }

    @Volatile
    var lastConnect: ConnectOutcome = ConnectOutcome.NONE
        private set

    /** A revive starts a fresh reading, so one that never got as far as
     *  connecting does not inherit the last one's. */
    fun forgetLastConnect() {
        lastConnect = ConnectOutcome.NONE
    }

    /**
     * Browse mDNS for one of adbd's advertised services and return its port.
     *
     * [AdbMdns.SERVICE_TYPE_TLS_PAIRING] is advertised only while the pairing
     * dialog is on screen; [AdbMdns.SERVICE_TYPE_TLS_CONNECT] whenever Wireless
     * debugging is on AND a network interface exists to advertise on.
     */
    suspend fun discoverPort(
        context: Context,
        serviceType: String,
        timeoutMs: Long = 5_000,
    ): Int? = withContext(Dispatchers.IO) {
        val port = AtomicInteger(-1)
        val found = CountDownLatch(1)

        val mdns = AdbMdns(context.applicationContext, serviceType) { _: InetAddress?, p: Int ->
            if (p > 0 && port.compareAndSet(-1, p)) found.countDown()
        }
        mdns.start()
        try {
            if (!found.await(timeoutMs, TimeUnit.MILLISECONDS)) return@withContext null
        } finally {
            mdns.stop()
        }
        port.get().takeIf { it > 0 }
    }

    /**
     * ANDROID 12 AND 13: AN mDNS REQUEST OF THE APP'S OWN, HELD OPEN ACROSS A
     * SWITCH-ON (Revive). There adbd announces itself through the native
     * mdnsd, and NsdService stops mdnsd 10 s after the last app's request
     * ends (CLEANUP_DELAY_MS, removeRequestMap -> maybeScheduleStop ->
     * DAEMON_CLEANUP -> maybeStop; AOSP android12-release and
     * android13-release, read 2026-11-21): adbd's announcement goes with it,
     * and is not made again until the next switch-on. A stop still pending
     * from a look that ended a moment before could land just after the
     * switch-on's announcement - one NOT_FOUND after a switch-on on the
     * Android 12 AVD, 2026-11-21, read as that race, not proven. A request
     * active cancels a pending stop and keeps any new one from being
     * scheduled (storeRequestMap -> cancelStop), so one started before the
     * switch-on and closed after the connect keeps mdnsd up for all of it.
     *
     * A discovery that resolves nothing ([MdnsHold]): NsdService takes one
     * resolve at a time from an app and turns a second away
     * (FAILURE_ALREADY_ACTIVE, android12-release), and the connect's own look
     * (MdnsLook) resolves what it finds - a hold that resolved too could take
     * the connect's resolve from it (review round 1). Android 14 moved
     * NsdService to an mDNS stack of its own, and the 16 and 17 AVDs kept
     * finding adbd with nobody browsing: null there, as on the versions
     * before 12, which JemRec does not run on.
     *
     * Since 1.9 a lost announcement no longer fails the start - the loopback
     * probe finds adbd without one (AdbdFinder) - and the hold keeps the
     * quicker answer. Its type is the service's full one, as NsdManager takes
     * it; until 1.9 it was libadb's bare name, adb-tls-connect.
     */
    fun holdMdns(context: Context): AutoCloseable? {
        if (!holdsMdns(Build.VERSION.SDK_INT)) return null
        val nsd = context.applicationContext.getSystemService(NsdManager::class.java) ?: return null
        val hold = MdnsHold(nsd)
        return runCatching {
            nsd.discoverServices(MdnsLook.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, hold)
            hold
        }.onFailure { Log.w(TAG, "mdns: could not hold a request open - ${it.message}") }.getOrNull()
    }

    /**
     * An mDNS discovery whose only use is to be active: it resolves nothing
     * it finds. Closed at once after it was opened, it is stopped all the
     * same: NsdManager keeps the listener from discoverServices on and sends
     * its requests in order (android12-release), so a stop is not lost
     * before the discovery has started - unlike AdbMdns.stop, which stops
     * only after its started callback (libadb 3.1.1). One that failed to
     * start has nothing to stop, and the stop's refusal is let go.
     */
    private class MdnsHold(private val nsd: NsdManager) : NsdManager.DiscoveryListener, AutoCloseable {
        override fun close() {
            runCatching { nsd.stopServiceDiscovery(this) }
        }

        override fun onDiscoveryStarted(serviceType: String) = Unit
        override fun onDiscoveryStopped(serviceType: String) = Unit
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "mdns: the held request did not start ($errorCode)")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
        override fun onServiceFound(serviceInfo: NsdServiceInfo) = Unit
        override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
    }

    /** Whether Android [sdk] needs the look held (holdMdns): 12, 12L and 13. */
    internal fun holdsMdns(sdk: Int): Boolean = sdk in Build.VERSION_CODES.S..Build.VERSION_CODES.TIRAMISU

    /**
     * Run one command and return everything it wrote.
     *
     * Deliberately uses the one-shot `shell:<cmd>` form rather than opening an
     * interactive `shell:` and writing to it. One-shot means adbd closes the
     * stream when the command finishes, so end-of-output is EOF and not a
     * guess. The interactive form never signals completion, which is fine for a
     * terminal and useless for "run this and tell me what it said".
     *
     * The destination string is built directly instead of going through
     * LocalServices.getDestination(SHELL, ...), which double-quotes any
     * argument containing a space - turning `echo hi` into the single command
     * `"echo hi"`.
     *
     * Note this is the LEGACY shell protocol: stdout and stderr are merged and
     * there is no exit status. Adequate for identification and diagnostics.
     */
    suspend fun exec(command: String, timeoutMs: Long = 15_000): Result<String> =
        withContext(Dispatchers.IO) {
            // A session shown and up, not merely "a manager object exists".
            // Opening a stream on a manager whose connection has died does not
            // throw - it hands back a stream that yields nothing, so every
            // command silently "succeeds" with no output.
            if (live() == null) return@withContext notConnected()
            mutex.withLock {
                // Again under the lock: a proof that failed while this waited
                // has closed its session (prove).
                val conn = live() ?: return@withLock notConnected()
                runOnShell(conn, command, timeoutMs)
            }
        }

    /** One command on [conn]'s kept shell, exec's lock held: exec's, and the
     *  proof's own before [conn] is shown (prove). */
    private suspend fun runOnShell(conn: AdbConnection, command: String, timeoutMs: Long): Result<String> =
        runOnShellCatching(conn, command, timeoutMs).also(::passOnCancel)

    private suspend fun runOnShellCatching(conn: AdbConnection, command: String, timeoutMs: Long): Result<String> =
        runCatching {
            // A shell of an earlier connection is that connection's, gone.
            if (shell?.connection !== conn) forgetShell()
            // Watching from before the stream is opened: opening one
            // can hang too, and then only closing the whole
            // connection lets it go.
            // Its caller cancelled or not (watched).
            var session: ShellSession? = shell
            watched(timeoutMs, onTimeout = {
                // The command's first word only: the daemon's launch
                // line carries its token.
                Log.w(TAG, "exec: '${command.substringBefore(' ')} ...' timed out after ${timeoutMs}ms")
                session?.let { runCatching { it.close() } } ?: runCatching { conn.close() }
                shell = null
            }) {
                // A stream that cannot be opened on a connection that
                // says it is up is adbd refusing them (ShellSession):
                // the connection is closed, so the next autoConnect
                // makes a fresh one. Before 0.31 the watchdog did it,
                // fifteen seconds later and as a "timed out".
                val open = session ?: runCatching { ShellSession(conn, conn.open(SHELL_SERVICE)) }
                    .getOrElse { t ->
                        runCatching { conn.close() }
                        shell = null
                        throw t
                    }
                    .also { shell = it }
                session = open
                try {
                    open.run(command)
                } catch (t: Throwable) {
                    // A desynchronised session stays desynchronised, and a
                    // half-read one would mis-attribute the next command's
                    // output. Drop it; the next call opens a fresh one.
                    runCatching { open.close() }
                    shell = null
                    throw t
                }
            }
        }

    /**
     * ONE shell stream, reused for every command, because opening one per
     * command destroys the connection after exactly eight.
     *
     * WHY EIGHT
     *
     * libadb never acknowledges the peer's stream teardown. AdbStream.close()
     * begins "if (mIsClosed) return", and mIsClosed is exactly what the
     * connection thread sets when adbd's CLSE arrives - so for any stream the
     * remote closed first, which is every one-shot command, close() returns
     * early and the reciprocal CLSE is never sent. adbd is left holding
     * half-open sockets, and refuses new ones once it has collected enough.
     * Measured: commands 1-8 fine, 9 onwards "Stream closed." for good.
     * Retrying cannot help, because nothing about it is transient.
     *
     * WHY exec: AND NOT shell:
     *
     * A persistent shell needs clean I/O, and `shell:` with no command makes
     * adbd allocate a PTY. A PTY echoes back everything written to it and
     * prints a prompt, so output arrives as "HNBKQ:/ $ id" interleaved with
     * real results, and the sentinel line never matches because it turns up
     * wearing a prompt. Turning that off from inside with `stty -echo` only
     * desynchronised things further. The `exec:` service never allocates a PTY,
     * which makes `exec:sh` a persistent shell with clean pipes.
     *
     * A persistent shell never signals end-of-output, so a sentinel marks it:
     * write the command, then echo a marker, and read until the marker comes
     * back. It is random per session so no command's own output can collide.
     */
    private class ShellSession(val connection: AdbConnection, private val stream: AdbStream) {
        private val marker = "__jemrec_" + java.util.UUID.randomUUID().toString().take(12) + "__"
        private val out = stream.openOutputStream()
        private val reader = stream.openInputStream().bufferedReader()

        init {
            // Turn the terminal echo off ONCE, outside the command protocol.
            //
            // adbd hands back a PTY even for `exec:sh`, and a PTY echoes back
            // whatever is written to it. Dropping those echoed lines afterwards
            // works only while an echoed line IS a line: a long command wraps
            // across several, and then the filter misses it and the fragments
            // land in the output. A `pm grant ...` line is long enough to do it.
            //
            // An earlier attempt ran `stty -echo` as a normal command and
            // desynchronised the session, because its own echo arrives under
            // the old setting while the reader is already expecting the new
            // one. Writing it raw and draining whatever comes back for a moment
            // sidesteps that entirely - nothing is being matched, so nothing
            // can fail to match.
            runCatching {
                out.write("stty -echo 2>/dev/null; PS1=''\n".toByteArray(Charsets.UTF_8))
                out.flush()
                val deadline = System.currentTimeMillis() + SETTLE_MS
                while (System.currentTimeMillis() < deadline) {
                    if (reader.ready()) reader.read() else Thread.sleep(20)
                }
            }
        }

        fun run(command: String): String {
            val begin = "$marker-BEGIN"
            val end = "$marker-END"

            // ONE line, not three, and that is the whole trick.
            //
            // adbd gives back a PTY even for `exec:sh`, and a PTY echoes each
            // input LINE back immediately before the shell runs it. With the
            // markers on separate lines the echo interleaves with the output:
            //
            //   echo hello          <- echo of line 2
            //   hello               <- what line 2 printed
            //   echo <marker>-END   <- echo of line 3
            //
            // and no amount of line filtering is safe, because a long command
            // wraps across several lines and the fragments look like results.
            // `stty -echo` does not stick here either.
            //
            // Sent as a single line, ALL the echo happens before ANY output, so
            // discarding everything up to the first exact BEGIN removes it
            // whatever shape it arrived in. The echoed text always contains the
            // word "echo", so it can never be equal to the marker the shell
            // prints when it runs the line.
            //
            // Commands must therefore be single-line; use ';' rather than
            // newlines, which is what every caller here already does.
            val oneLine = command.replace('\n', ';')

            // The command goes in a SUBSHELL, and that is not decoration.
            //
            // Joining with semicolons alone produces `cmd & ; echo END` for any
            // command that ends in `&`, and `& ;` is a syntax error in sh. The
            // shell dies, the end marker never arrives, and the caller sees a
            // timeout rather than an error - which is exactly how the daemon
            // launch broke: it is the one command here that backgrounds itself.
            //
            // `( cmd & )` is valid, and so is `( cmd )`, so wrapping handles
            // both shapes without the caller having to think about it.
            out.write("echo $begin; ( $oneLine ) ; echo $end\n".toByteArray(Charsets.UTF_8))
            out.flush()

            while (true) {
                val line = reader.readLine() ?: throw java.io.IOException("shell stream closed")
                if (line.trimEnd() == begin) break
            }

            val sb = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: throw java.io.IOException("shell stream closed")
                if (line.trimEnd() == end) break
                sb.appendLine(line)
            }
            return sb.toString().trimEnd()
        }

        fun close() {
            runCatching { out.close() }
            runCatching { if (!stream.isClosed) stream.close() }
        }

        private companion object {
            /** Long enough for the shell to apply stty and stop echoing. */
            const val SETTLE_MS = 400L
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        runCatching { shell?.close() }
        shell = null
        shown = null
        runCatching { manager?.disconnect() }
        made = null
        Unit
    }

    /** Drops the connection AND the in-memory manager, but never the identity
     *  files - those are the pairing and must outlive any session. */
    suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { shell?.close() }
        shell = null
        runCatching { manager?.close() }
        manager = null
        shown = null
        made = null
        lastKnownConnected = false
        Unit
    }
}
