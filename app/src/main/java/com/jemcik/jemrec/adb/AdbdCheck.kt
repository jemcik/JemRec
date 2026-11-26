package com.jemcik.jemrec.adb

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Whether adbd's Wireless debugging server listens on a port of this phone's
 * own loopback - asked the way adb opens a connection: a CONNECT, which the
 * TLS server answers with STLS before anything else (adb.cpp
 * handle_new_connection: a transport its TLS server made sends STLS first,
 * android15-release). Nothing else on a phone answers CONNECT with STLS, so
 * the answer is the proof. A port that merely accepts is not: on loopback a
 * connect can land on its own socket, TCP's self-connect - seen as "open"
 * ports that changed from run to run on the Honor and both AVDs
 * (2026-11-26, tmp/issue-140/PLAN.md, P0).
 *
 * Plain java.net and no Android, so it is tested on the JVM against real
 * sockets (AdbdCheckTest).
 */
internal object AdbdCheck {

    enum class Answer {
        /** adbd's TLS server: it answered STLS. */
        ADBD,

        /** Something listens and is not adbd's TLS server. */
        OTHER,

        /** Nothing listens: refused at once. */
        CLOSED,

        /** No answer to the connect: a firewall dropping it, or a peer too slow. */
        TIMED_OUT,
    }

    /** protocol.txt and adb.h: the command constants and the message's header. */
    const val A_CNXN = 0x4e584e43
    const val A_STLS = 0x534c5453
    const val HEADER = 24

    /** adb.h A_VERSION, what adb and libadb send. */
    const val A_VERSION = 0x01000001

    /** protocol.txt: a CONNECT to a device "must not be larger than" 4096,
     *  sent before the device's own CONNECT says what it takes. */
    const val MAX_DATA = 4096

    /** protocol.txt: "<systemtype>:<serialno>:<banner>". A host, with neither. */
    private val IDENTITY = "host::\u0000".toByteArray(Charsets.US_ASCII)

    /** Connect cap: a closed port on loopback refuses within a millisecond
     *  (0.2 ms on the Honor, 0.4-0.65 ms on the AVDs, P0). */
    const val CONNECT_MS = 200

    /** Answer cap: adbd answered STLS in 1-8 ms in P0. */
    const val ANSWER_MS = 300

    /** The CONNECT message: protocol.txt's header, little-endian, then its body. */
    fun connectMessage(): ByteArray {
        val check = IDENTITY.sumOf { it.toInt() and 0xff }
        return ByteBuffer.allocate(HEADER + IDENTITY.size).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(A_CNXN)
            .putInt(A_VERSION)
            .putInt(MAX_DATA)
            .putInt(IDENTITY.size)
            .putInt(check)
            .putInt(A_CNXN.inv())
            .put(IDENTITY)
            .array()
    }

    /** Whether a received header is STLS: its command and the magic that
     *  must go with it (command ^ 0xffffffff, protocol.txt). */
    fun isStls(header: ByteArray): Boolean {
        if (header.size < HEADER) return false
        val b = ByteBuffer.wrap(header, 0, HEADER).order(ByteOrder.LITTLE_ENDIAN)
        return b.getInt(0) == A_STLS && b.getInt(20) == A_STLS.inv()
    }

    /** Asks 127.0.0.1:[port]; never throws. */
    fun check(port: Int, connectMs: Int = CONNECT_MS, answerMs: Int = ANSWER_MS): Answer {
        Socket().use { socket ->
            try {
                socket.connect(InetSocketAddress(AdbTransport.LOOPBACK, port), connectMs)
            } catch (e: SocketTimeoutException) {
                return Answer.TIMED_OUT
            } catch (e: IOException) {
                return Answer.CLOSED
            }
            return try {
                socket.soTimeout = answerMs
                socket.getOutputStream().run {
                    write(connectMessage())
                    flush()
                }
                val header = ByteArray(HEADER)
                val input = socket.getInputStream()
                var read = 0
                while (read < HEADER) {
                    val n = input.read(header, read, HEADER - read)
                    if (n < 0) break
                    read += n
                }
                if (read == HEADER && isStls(header)) Answer.ADBD else Answer.OTHER
            } catch (e: IOException) {
                // Silent past the cap, or reset: it listens, and is not adbd.
                Answer.OTHER
            }
        }
    }
}
