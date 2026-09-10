package com.jemcik.jemrec.adb

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.security.PrivateKey
import java.security.cert.Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
 * pinned. That is what makes the Wi-Fi-off state a real test rather than a
 * formality, and it is tracked separately.
 */
object AdbTransport {

    private const val TAG = "JemRec"

    /** adbd is on this phone. There is no other host this could ever be. */
    const val LOOPBACK = "127.0.0.1"

    /**
     * The legacy fixed ADB-over-TCP port. Only reachable if something has set
     * `service.adb.tcp.port`; tried as a last resort because when it IS set it
     * is stable across Wi-Fi state, which no mDNS-discovered port is.
     */
    const val LEGACY_TCP_PORT = 5555

    /** `exec:` rather than `shell:` so adbd allocates no PTY. See ShellSession. */
    private const val SHELL_SERVICE = "exec:sh"

    @Volatile
    private var manager: AbsAdbConnectionManager? = null

    /** The one reused shell, and the lock that stops two commands interleaving
     *  on it. A shared stream with a sentinel protocol is only correct while
     *  exactly one caller is writing to it. */
    private var shell: ShellSession? = null
    private val mutex = Mutex()

    private fun manager(context: Context): AbsAdbConnectionManager =
        manager ?: synchronized(this) {
            manager ?: JemAdbConnectionManager(context.applicationContext).also { manager = it }
        }

    val isConnected: Boolean
        get() = try {
            manager?.isConnected == true
        } catch (t: Throwable) {
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
                if (mgr.isConnected) {
                    Log.i(TAG, "connect: already connected")
                    return@runCatching
                }
                Log.i(TAG, "connect: $host:$port")
                val ok = mgr.connect(host, port)
                check(ok || mgr.isConnected) { "connect refused by adbd at $host:$port" }
                Log.i(TAG, "connect: established")
            }
        }

    /**
     * Find adbd without being told where it is, in the order that degrades
     * best: mDNS first (correct whenever there is a network), then the fixed
     * legacy port (correct whenever someone has pinned it, including with Wi-Fi
     * off), and only then give up.
     */
    suspend fun autoConnect(context: Context, timeoutMs: Long = 5_000): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val mgr = manager(context)
                if (mgr.isConnected) {
                    Log.i(TAG, "autoConnect: already connected")
                    return@runCatching
                }

                val discovered = discoverPort(context, AdbMdns.SERVICE_TYPE_TLS_CONNECT, timeoutMs)
                if (discovered != null) {
                    Log.i(TAG, "autoConnect: mDNS found connect port $discovered")
                    // See connect() above: false is ambiguous, isConnected is not.
                    if (mgr.connect(LOOPBACK, discovered) || mgr.isConnected) return@runCatching
                    Log.w(TAG, "autoConnect: mDNS port $discovered did not accept us")
                } else {
                    Log.i(TAG, "autoConnect: no mDNS advertisement (expected with Wi-Fi off)")
                }

                Log.i(TAG, "autoConnect: trying fixed port $LEGACY_TCP_PORT")
                val ok = mgr.connect(LOOPBACK, LEGACY_TCP_PORT)
                check(ok || mgr.isConnected) {
                    "no adbd found by mDNS, and nothing listening on $LEGACY_TCP_PORT"
                }
            }
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
            // isConnected, not merely "a manager object exists". Opening a
            // stream on a manager whose connection has died does not throw - it
            // hands back a stream that yields nothing, so every command
            // silently "succeeds" with no output.
            val mgr = manager
            if (mgr == null || !mgr.isConnected) {
                return@withContext Result.failure(IllegalStateException("ADB is not connected"))
            }

            mutex.withLock {
                runCatching {
                    val session = shell ?: ShellSession(mgr.openStream(SHELL_SERVICE))
                        .also { shell = it }
                    val watchdog = launch {
                        kotlinx.coroutines.delay(timeoutMs)
                        Log.w(TAG, "exec: '$command' timed out after ${timeoutMs}ms")
                        runCatching { session.close() }
                        shell = null
                    }
                    try {
                        session.run(command)
                    } catch (t: Throwable) {
                        // A desynchronised session stays desynchronised, and a
                        // half-read one would mis-attribute the next command's
                        // output. Drop it; the next call opens a fresh one.
                        runCatching { session.close() }
                        shell = null
                        throw t
                    } finally {
                        watchdog.cancel()
                    }
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
    private class ShellSession(private val stream: AdbStream) {
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
        runCatching { manager?.disconnect() }
        Unit
    }

    /** Drops the connection AND the in-memory manager, but never the identity
     *  files - those are the pairing and must outlive any session. */
    suspend fun close() = withContext(Dispatchers.IO) {
        runCatching { shell?.close() }
        shell = null
        runCatching { manager?.close() }
        manager = null
        Unit
    }
}
