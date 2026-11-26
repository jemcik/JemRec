package com.jemcik.jemrec.adb

import com.jemcik.jemrec.adb.AdbdCheck.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import kotlin.concurrent.thread

/**
 * The loopback probe's rules, with the ports' answers made up - and once over
 * real sockets, a stand-in adbd among closed ports.
 */
class LoopbackProbeTest {

    private fun probe(
        answers: (Int) -> Answer,
        ports: IntRange = 40_000..40_999,
        parallel: Int = 4,
        budgetMs: Long = 5_000,
    ) = LoopbackProbe(answers, ports, parallel, budgetMs)

    @Test fun findsAdbdAmongClosedPortsAndOthers() {
        val r = probe({ p -> if (p == 40_500) Answer.ADBD else if (p % 100 == 0) Answer.OTHER else Answer.CLOSED }).run()
        assertEquals(40_500, r.port)
        assertFalse(r.blocked)
        assertTrue(r.summary(), r.summary().contains("found by the probe on 40500"))
    }

    /** Found, the workers stop: the ports after it are not all asked. */
    @Test fun stopsOnceFound() {
        val asked = Collections.synchronizedSet(HashSet<Int>())
        val r = probe({ p -> asked += p; if (p == 40_010) Answer.ADBD else Answer.CLOSED }).run()
        assertEquals(40_010, r.port)
        assertTrue("asked ${asked.size} of 1000", asked.size < 100)
    }

    /** Loopback dropped for this uid (Doze, a restricted app): the closed
     *  port times out, and no sweep is made. */
    @Test fun aDroppedLoopbackIsNotSwept() {
        val asked = Collections.synchronizedList(ArrayList<Int>())
        val r = probe({ p -> asked += p; Answer.TIMED_OUT }).run()
        assertTrue(r.blocked)
        assertNull(r.port)
        assertEquals(listOf(LoopbackProbe.CLOSED_PORT), asked.toList())
        assertTrue(r.summary(), r.summary().contains("loopback dropped"))
    }

    @Test fun notThereSaysWhatItAsked() {
        val r = probe({ p -> if (p == 40_001) Answer.OTHER else if (p == 40_002) Answer.TIMED_OUT else Answer.CLOSED }).run()
        assertNull(r.port)
        assertEquals("every port of the range", 1000, r.asked)
        assertEquals(1, r.others)
        assertEquals(1, r.timedOut)
        assertFalse(r.outOfTime)
        assertTrue(r.summary(), r.summary().contains("adbd not among them"))
    }

    /** Ports slower than the budget allows: stopped, and said so. */
    @Test fun stopsAtItsBudget() {
        val start = System.nanoTime()
        val r = probe({ p -> if (p != LoopbackProbe.CLOSED_PORT) Thread.sleep(20); Answer.CLOSED }, budgetMs = 300).run()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(r.outOfTime)
        assertNull(r.port)
        assertTrue("ended at the budget, not after 1000 x 20 ms / 4 ($ms ms)", ms < 2_000)
    }

    /** Its caller gone: the workers stop at their next port. */
    @Test fun aCancelledCallerStopsTheWorkers() {
        val gone = java.util.concurrent.atomic.AtomicBoolean()
        val asked = java.util.concurrent.atomic.AtomicInteger()
        val r = LoopbackProbe(
            { p -> if (asked.incrementAndGet() > 50) gone.set(true); Answer.CLOSED },
            40_000..40_999, 4, 5_000, cancelled = { gone.get() },
        ).run()
        assertNull(r.port)
        assertTrue(r.cancelled)
        assertFalse(r.outOfTime)
        assertTrue("stopped near 50 of 1000 (${r.asked})", r.asked < 100)
        assertTrue(r.summary(), r.summary().startsWith("probe stopped by its caller"))
    }

    /** Over real sockets: a stand-in adbd answering STLS, in a range of
     *  ports nobody else listens on. */
    @Test fun findsAStandInAdbdOverRealSockets() {
        val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
        server.use {
            thread(isDaemon = true) {
                while (!server.isClosed) {
                    runCatching {
                        server.accept().use { s ->
                            s.getInputStream().read(ByteArray(64))
                            val stls = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(AdbdCheck.A_STLS).putInt(0x01000000).putInt(0).putInt(0).putInt(0)
                                .putInt(AdbdCheck.A_STLS.inv()).array()
                            s.getOutputStream().run { write(stls); flush() }
                        }
                    }
                }
            }
            val port = server.localPort
            val r = LoopbackProbe(ports = maxOf(1025, port - 20)..minOf(65_535, port + 20), parallel = 4).run()
            assertEquals(port, r.port)
        }
    }
}
