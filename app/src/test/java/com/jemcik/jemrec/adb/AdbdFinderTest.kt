package com.jemcik.jemrec.adb

import com.jemcik.jemrec.adb.AdbdCheck.Answer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Finding adbd's Wireless debugging port (#140), on a made-up phone: what
 * mDNS gives and when, what each port answers, and the probe's result. The
 * clock moves only as the look waits.
 */
class AdbdFinderTest {

    /** A phone whose look gives [given] - (port, after how many ms) - and
     *  whose ports answer [answers]. */
    private class Phone(
        private val given: List<Pair<Int, Long>>,
        private val answers: Map<Int, Answer>,
        private val probeFinds: Int? = null,
        private val noProbe: String? = null,
        /** By the clock, where it changes: what the switch reads, and what a probe finds. */
        private val noProbeAt: (Long) -> String? = { noProbe },
        private val probeFindsAt: (Long) -> Int? = { probeFinds },
    ) : AdbdFinder.World {
        var now = 0L
        var probed = false
        var probes = 0
        var closed = false
        val checked = mutableListOf<Int>()

        override fun look() = object : AdbdFinder.Look {
            private var i = 0
            private val startedAt = now
            override fun next(timeoutMs: Long): Int? {
                val (port, at) = given.getOrNull(i) ?: run { now += timeoutMs; return null }
                val wait = startedAt + at - now
                if (wait > timeoutMs) {
                    now += timeoutMs
                    return null
                }
                now += maxOf(0, wait)
                i++
                return port
            }
            override fun close() { closed = true }
        }

        override fun check(port: Int): Answer {
            checked += port
            return answers[port] ?: Answer.CLOSED
        }

        override fun noProbe() = noProbeAt(now)
        override fun probe(): LoopbackProbe.Result {
            probed = true
            probes++
            return LoopbackProbe.Result(probeFindsAt(now), 28_232, 0, 0, 1_200)
        }

        override fun now() = now

        var cancelAt = Long.MAX_VALUE
        override fun cancelled() = now >= cancelAt
    }

    @Test fun adbdsPortFromMdnsNeedsNoProbe() {
        val phone = Phone(listOf(41_057 to 300L), mapOf(41_057 to Answer.ADBD))
        val found = AdbdFinder(phone).find(5_000)
        assertEquals(41_057, found.port)
        assertEquals(AdbdFinder.By.MDNS, found.by)
        assertFalse(phone.probed)
        assertTrue(phone.closed)
    }

    /** Android's cache gives the port adbd had before Wireless debugging went
     *  off and on, and nothing newer comes (the Honor and the AVDs, P0): the
     *  look is cut short and the probe finds adbd. */
    @Test fun anOldPortFromTheCacheGoesToTheProbe() {
        val phone = Phone(listOf(36_467 to 2L), emptyMap(), probeFinds = 33_469)
        val found = AdbdFinder(phone).find(5_000)
        assertEquals(33_469, found.port)
        assertEquals(AdbdFinder.By.PROBE, found.by)
        assertEquals(listOf(AdbdFinder.Candidate(36_467, Answer.CLOSED)), found.candidates)
        assertTrue("cut to ${AdbdFinder.AFTER_A_DEAD_PORT_MS} ms after the old port, not the whole look (${phone.now} ms)",
            phone.now <= 2 + AdbdFinder.AFTER_A_DEAD_PORT_MS)
        assertTrue(phone.closed)
    }

    /** Another service can still come in the short wait, and is taken. */
    @Test fun aLivePortAfterAnOldOneIsTaken() {
        val phone = Phone(listOf(36_467 to 2L, 33_469 to 300L), mapOf(33_469 to Answer.ADBD))
        val found = AdbdFinder(phone).find(5_000)
        assertEquals(33_469, found.port)
        assertEquals(AdbdFinder.By.MDNS, found.by)
        assertFalse(phone.probed)
    }

    /** Nothing announced (adbd's registration failed, the G05): the whole
     *  look, then the probe. */
    @Test fun nothingAnnouncedGoesToTheProbeAfterTheLook() {
        val phone = Phone(emptyList(), emptyMap(), probeFinds = 44_215)
        val found = AdbdFinder(phone).find(4_000)
        assertEquals(44_215, found.port)
        assertEquals(AdbdFinder.By.PROBE, found.by)
        assertEquals(4_000L, phone.now)
        assertTrue(found.summary(), found.summary().startsWith("mDNS gave no port"))
    }

    @Test fun aPortGivenTwiceIsCheckedOnce() {
        val phone = Phone(listOf(36_467 to 2L, 36_467 to 3L), emptyMap(), probeFinds = null)
        AdbdFinder(phone).find(5_000)
        assertEquals(listOf(36_467), phone.checked)
    }

    @Test fun noProbeWhereWirelessDebuggingIsOff() {
        val phone = Phone(emptyList(), emptyMap(), probeFinds = 44_215, noProbe = "Wireless debugging is off")
        val found = AdbdFinder(phone).find(1_000)
        assertNull(found.port)
        assertFalse(phone.probed)
        assertTrue(found.summary(), found.summary().endsWith("no probe: Wireless debugging is off"))
    }

    @Test fun notFoundAnywhereSaysWhatWasAsked() {
        val phone = Phone(listOf(36_467 to 2L), emptyMap(), probeFinds = null)
        val found = AdbdFinder(phone).find(5_000)
        assertNull(found.port)
        assertNull(found.by)
        assertTrue(phone.probed)
        assertEquals(
            "mDNS gave 36467 (closed); probed 3 times, the last; probe: 28232 ports in 1200 ms, adbd not among them",
            found.summary(),
        )
    }

    /** A probe that finds nothing is made again while the look has time:
     *  half a second after the old port, then every [AdbdFinder.AGAIN_MS],
     *  the last at the caller's deadline and none past it. */
    @Test fun theProbeIsMadeAgainWithinTheLook() {
        val phone = Phone(listOf(36_467 to 2L), emptyMap(), probeFinds = null)
        AdbdFinder(phone).find(5_000)
        assertEquals("at 502, 3502 and 5000 ms", 3, phone.probes)
        assertEquals(5_000L, phone.now)
    }

    /** An Allow in the wait's last seconds is still seen: the last probe is
     *  at the deadline, not lost past it (review round 2). */
    @Test fun anAllowAtTheEndOfTheWaitIsSeen() {
        val phone = Phone(
            listOf(36_467 to 2L), emptyMap(),
            noProbeAt = { t -> if (t < 19_000) "Wireless debugging is off" else null },
            probeFindsAt = { t -> if (t >= 19_000) 44_215 else null },
        )
        val found = AdbdFinder(phone).find(20_000)
        assertEquals(44_215, found.port)
        assertEquals(20_000L, phone.now)
    }

    /** Allow this Wi-Fi (review round 1): the look is the user's time to
     *  answer Android's question - 20 s. Android 12 reads the switch off
     *  while the question is up, and adbd's server starts only after Allow:
     *  an old port must not end the look, and adbd is found once allowed. */
    @Test fun allowThisWifiKeepsItsWholeWait() {
        val phone = Phone(
            listOf(36_467 to 2L), emptyMap(),
            noProbeAt = { t -> if (t < 8_000) "Wireless debugging is off" else null },
            probeFindsAt = { t -> if (t >= 8_000) 44_215 else null },
        )
        val found = AdbdFinder(phone).find(20_000)
        assertEquals(44_215, found.port)
        assertEquals(AdbdFinder.By.PROBE, found.by)
        assertTrue("found after Allow (${phone.now} ms), within the wait", phone.now in 8_000..20_000)
    }

    /** The caller gone - a screen closed mid-connect: no probe, no more look. */
    @Test fun aCancelledCallerStopsTheLook() {
        val phone = Phone(listOf(36_467 to 2L), emptyMap(), probeFinds = 33_469).apply { cancelAt = 1 }
        val found = AdbdFinder(phone).find(20_000)
        assertNull(found.port)
        assertFalse(phone.probed)
        assertTrue("stopped at once (${phone.now} ms)", phone.now < AdbdFinder.AFTER_A_DEAD_PORT_MS)
        assertTrue(phone.closed)
    }
}
