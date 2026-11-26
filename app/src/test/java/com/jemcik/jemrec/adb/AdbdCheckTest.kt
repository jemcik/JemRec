package com.jemcik.jemrec.adb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread

/**
 * adbd's answer as AdbdCheck asks for it: the CONNECT by adb's own spec
 * (packages/modules/adb protocol.txt, adb.h, android15-release), and the
 * check against real sockets on this machine's loopback - a stand-in adbd
 * that answers STLS, servers that answer something else or nothing, and a
 * port nobody listens on.
 */
class AdbdCheckTest {

    private fun le(bytes: ByteArray, at: Int) = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getInt(at)

    private fun header(command: Int, arg0: Int = 0x01000000, magic: Int = command.inv()): ByteArray =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(command).putInt(arg0).putInt(0).putInt(0).putInt(0).putInt(magic).array()

    /** protocol.txt's message: command, arg0, arg1, data_length, data
     *  check, magic, little-endian, then the body. CONNECT(version, maxdata,
     *  "system-identity-string"). */
    @Test fun theConnectIsAdbsOwnMessage() {
        val m = AdbdCheck.connectMessage()
        assertEquals("A_CNXN (adb.h)", 0x4e584e43, le(m, 0))
        assertEquals("A_VERSION (adb.h)", 0x01000001, le(m, 4))
        assertTrue("maxdata must not be over 4096 before the device's own CONNECT (protocol.txt)", le(m, 8) <= 4096)
        val body = m.copyOfRange(24, m.size)
        assertEquals("data_length", body.size, le(m, 12))
        assertEquals("data check: the sum of the body's bytes", body.sumOf { it.toInt() and 0xff }, le(m, 16))
        assertEquals("magic: command ^ 0xffffffff", 0x4e584e43 xor -1, le(m, 20))
        assertArrayEquals("<systemtype>:<serialno>:<banner>, a host with neither", "host::\u0000".toByteArray(), body)
        assertTrue("the whole message under 4096 (protocol.txt)", m.size <= 4096)
    }

    @Test fun stlsIsItsCommandWithItsMagic() {
        assertTrue(AdbdCheck.isStls(header(0x534C5453)))
        assertFalse("a wrong magic is not a message", AdbdCheck.isStls(header(0x534C5453, magic = 0)))
        assertFalse("CONNECT is not STLS", AdbdCheck.isStls(header(0x4e584e43)))
        assertFalse("too short", AdbdCheck.isStls(header(0x534C5453).copyOf(23)))
    }

    /** A server on loopback answering [answer] (nothing, if null) to what it
     *  reads; what it read is handed to [heard]. */
    private fun server(answer: ByteArray?, heard: (ByteArray) -> Unit = {}): ServerSocket {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { s ->
                    val got = ByteArray(AdbdCheck.connectMessage().size)
                    var n = 0
                    while (n < got.size) {
                        val k = s.getInputStream().read(got, n, got.size - n)
                        if (k < 0) break
                        n += k
                    }
                    heard(got.copyOf(n))
                    if (answer != null) s.getOutputStream().run { write(answer); flush() } else Thread.sleep(2_000)
                }
            }
        }
        return server
    }

    @Test fun adbdsTlsServerAnswersStls() {
        var heard = ByteArray(0)
        server(header(0x534C5453)) { heard = it }.use { s ->
            assertEquals(AdbdCheck.Answer.ADBD, AdbdCheck.check(s.localPort))
        }
        assertArrayEquals("it was sent the CONNECT, whole", AdbdCheck.connectMessage(), heard)
    }

    /** adbd's classic port answers CONNECT with AUTH or CONNECT, not STLS:
     *  not the Wireless debugging server. */
    @Test fun anotherAnswerIsNotAdbd() {
        server(header(0x4e584e43)).use { s -> assertEquals(AdbdCheck.Answer.OTHER, AdbdCheck.check(s.localPort)) }
    }

    @Test fun aServerThatSaysNothingIsNotAdbd() {
        server(null).use { s ->
            val start = System.nanoTime()
            assertEquals(AdbdCheck.Answer.OTHER, AdbdCheck.check(s.localPort, answerMs = 200))
            assertTrue("let go at the answer cap", (System.nanoTime() - start) / 1_000_000 < 1_500)
        }
    }

    @Test fun aPortNobodyListensOnIsClosed() {
        val port = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        assertEquals(AdbdCheck.Answer.CLOSED, AdbdCheck.check(port))
    }
}
