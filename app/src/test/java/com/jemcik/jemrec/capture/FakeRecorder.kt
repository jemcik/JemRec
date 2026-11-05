package com.jemcik.jemrec.capture

import android.content.Context
import java.io.DataInputStream
import java.io.EOFException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * A recorder answering on 127.0.0.1:28472, for the lifecycle tests: it proves
 * itself with this install's token (CaptureDaemon.token) as the recorder's
 * Main.session does, reads each command and its payload, and answers with the
 * line the test gives it. What the app can tell of a recorder - a ping, a
 * reply, a hang-up, a port held or free - is all a test needs of one, and the
 * app's code reaches it exactly as it reaches the phone's.
 *
 * One at a time: the port is the recorder's, so a test that leaves one up
 * would answer for the next. [close] it.
 */
internal class FakeRecorder(
    private val context: Context,
    /** The build it says it runs: the jar this APK carries by default, so a
     *  recorder the app takes for current. */
    @Volatile var build: String? = bundledBuild(context),
) : AutoCloseable {

    /** A command as heard: its letter, and for the greeting's and the
     *  diagnostics' commands the letter of their first payload byte too
     *  ("GQ", "XA"); [payload] whole. */
    data class Heard(val key: String, val payload: ByteArray)

    /** Every command heard, in order; a bare ping is "bare P". */
    val heard: MutableList<Heard> = CopyOnWriteArrayList()

    /** The line each command is answered with, by [Heard.key]; null, or a key
     *  not here, hangs up without a word, as a recorder too old for the
     *  command does. A test may set any, or [answer] for more than a line. */
    val replies: MutableMap<String, String> = ConcurrentHashMap(
        mapOf(
            "E" to "1",
            "V" to "VOIP 0 off",
            "T" to "TRACK off",
            "GQ" to "GREETING 0",
            "D" to "OK",
            "K" to "SKIP current",
            "Q" to "BYE",
        ),
    )

    /** Called after a reply is written, with its key: what a recorder does
     *  next - exits after BYE (by default), writes the switches after OFF NOW. */
    @Volatile var after: (String) -> Unit = { key -> if (key == "Q" && replies["Q"] == "BYE") close() }

    private val server = ServerSocket().apply {
        reuseAddress = true
        bind(InetSocketAddress(LOOPBACK, PORT))
    }

    @Volatile private var open = true

    private val acceptor = thread(isDaemon = true, name = "fake-recorder") {
        while (open) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            thread(isDaemon = true) { client.use(::serve) }
        }
    }

    /** Gone: the port free, as a recorder that exited leaves it. */
    override fun close() {
        if (!open) return
        open = false
        runCatching { server.close() }
        if (Thread.currentThread() != acceptor) acceptor.join(2_000)
    }

    val isOpen: Boolean get() = open

    private fun serve(client: Socket) {
        client.soTimeout = 3_000
        val input = DataInputStream(client.getInputStream())
        val out = client.getOutputStream()
        val first = runCatching { input.readUnsignedByte() }.getOrNull() ?: return
        if (first == 'P'.code) {
            heard += Heard("bare P", ByteArray(0))
            out.write("${pong()}\n".toByteArray())
            out.flush()
            return
        }
        if (first != 'A'.code) return
        val key = CaptureDaemon.token(context).toByteArray()
        val theirs = ByteArray(NONCE).also(input::readFully)
        val ours = ByteArray(NONCE).also(random::nextBytes)
        out.write(ours)
        out.write(mac(key, "jemrec-daemon", theirs, ours))
        out.flush()
        val proof = ByteArray(MAC_BYTES)
        try {
            input.readFully(proof)
        } catch (_: EOFException) {
            return
        }
        if (!MessageDigest.isEqual(proof, mac(key, "jemrec-client", theirs, ours))) return
        val command = input.readUnsignedByte().toChar()
        val payload = payload(command, input)
        val name = if (command == 'G' || command == 'X') "$command${payload.firstOrNull()?.toInt()?.toChar() ?: ""}" else "$command"
        heard += Heard(name, payload)
        val line = if (command == 'P') pong() else replies[name]
        if (line != null) {
            out.write("$line\n".toByteArray())
            out.flush()
        }
        after(name)
    }

    private fun pong() = if (build == null) "PONG 2" else "PONG 2 $build"

    /** The payload each command carries, as the app writes it (CaptureDaemon). */
    private fun payload(command: Char, input: DataInputStream): ByteArray = when (command) {
        'O', 'E', 'T', 'K', 'H' -> ByteArray(1).also(input::readFully)
        'Y' -> ByteArray(12).also(input::readFully)
        'V', 'N', 'F', 'D' -> frame(input)
        'G' -> {
            val op = input.readUnsignedByte()
            if (op == 'P'.code) {
                val length = input.readInt()
                byteArrayOf(op.toByte()) + ByteArray(length).also(input::readFully)
            } else {
                byteArrayOf(op.toByte())
            }
        }
        'X' -> when (val op = input.readUnsignedByte()) {
            'A'.code -> byteArrayOf(op.toByte(), input.readByte()) + frame(input)
            'S'.code -> byteArrayOf(op.toByte(), input.readByte())
            else -> byteArrayOf(op.toByte())
        }
        else -> ByteArray(0)
    }

    private fun frame(input: DataInputStream): ByteArray {
        val length = input.readUnsignedShort()
        return ByteArray(length).also(input::readFully)
    }

    companion object {
        const val PORT = 28472
        private const val LOOPBACK = "127.0.0.1"
        private const val NONCE = 16
        private const val MAC_BYTES = 32
        private val random = SecureRandom()

        private fun mac(key: ByteArray, label: String, a: ByteArray, b: ByteArray): ByteArray =
            Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                update(label.toByteArray())
                update(a)
                doFinal(b)
            }

        /** The build of the jar this APK carries, as the recorder names it:
         *  the first eight hex digits of its SHA-256. */
        fun bundledBuild(context: Context): String {
            val digest = MessageDigest.getInstance("SHA-256")
            context.assets.open("jemrec-capture.jar").use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            return digest.digest().take(4).joinToString("") { "%02x".format(it) }
        }
    }
}
