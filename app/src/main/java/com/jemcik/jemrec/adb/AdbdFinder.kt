package com.jemcik.jemrec.adb

/**
 * Where adbd's Wireless debugging listens on this phone: the ports mDNS
 * gives, each checked by adbd's own answer ([AdbdCheck]), and the loopback
 * probe ([LoopbackProbe]) when none of them answers.
 *
 * Until 1.9 the first port mDNS gave was taken as it came (libadb's AdbMdns),
 * and a look that gave none - or only one adbd had left - failed every
 * connect while adbd listened: setup stuck on Finish connecting (#140), and a
 * recorder start failing where Android's cache still held the port of the
 * start before. Measured and traced in tmp/issue-140/PLAN.md.
 *
 * The decisions only, in plain Kotlin; [World] is the phone (AdbTransport's
 * LiveFinderWorld), a fake in AdbdFinderTest.
 */
internal class AdbdFinder(private val world: World) {

    interface World {
        /** A look for adbd's service, started now. */
        fun look(): Look
        fun check(port: Int): AdbdCheck.Answer

        /** Null where the probe may run, else why not. */
        fun noProbe(): String?
        fun probe(): LoopbackProbe.Result
        fun now(): Long

        /** The caller gone (its coroutine cancelled): stop at the next turn. */
        fun cancelled(): Boolean = false
    }

    /** One mDNS look: the ports it resolves, in order. */
    interface Look : AutoCloseable {
        /** The next port resolved, waiting up to [timeoutMs]; null if none came. */
        fun next(timeoutMs: Long): Int?
    }

    enum class By { MDNS, PROBE }

    data class Candidate(val port: Int, val answer: AdbdCheck.Answer)

    data class Found(
        /** adbd's port, null if not found. */
        val port: Int?,
        val by: By?,
        /** What mDNS gave, each as checked, in order. */
        val candidates: List<Candidate>,
        /** The last probe, where one ran. */
        val probe: LoopbackProbe.Result? = null,
        /** Why the last probe due was not made, where it was not. */
        val noProbe: String? = null,
        /** How many probes ran. */
        val probes: Int = if (probe != null) 1 else 0,
    ) {
        /** For a log line and the setup report: ports only, nothing personal. */
        fun summary(): String = buildString {
            append(
                if (candidates.isEmpty()) {
                    "mDNS gave no port"
                } else {
                    "mDNS gave " + candidates.joinToString { "${it.port} (${it.answer.name.lowercase()})" }
                },
            )
            if (probes > 1) append("; probed ").append(probes).append(" times, the last")
            probe?.let { append("; ").append(it.summary()) }
            noProbe?.let { append("; no probe: ").append(it) }
        }
    }

    /**
     * Looks for adbd until [lookMs] is up - the caller's time, which is also
     * how long adbd may take to appear: Allow this Wi-Fi gives the user 20 s
     * to answer Android's question before adbd starts its server at all
     * (Revive's ALLOW_WAIT_MS). mDNS first, every port it gives checked; the
     * probe at the end, or brought forward by a port that is not adbd's.
     *
     * Brought forward to [AFTER_A_DEAD_PORT_MS] after that port: a newer port
     * for the same service reaches a look only as an update, which a
     * discovery's listener never hears (NsdService SERVICE_UPDATED,
     * android15-release) - on the Honor the cache took the new port 2.5 s
     * into the look that had been given the old one, and that look found
     * nothing (P0). A probe that finds nothing is made again every
     * [AGAIN_MS] while there is time, the last at the deadline - with the
     * switch read again each time, as Android sets it back off while its
     * question is up (AdbDebuggingManager MSG_ADBDWIFI_ENABLE, 12 to 16) -
     * and mDNS goes on being heard meanwhile. Review round 1 found the cut-short look ending
     * Allow this Wi-Fi's wait 4.5 s in, the question still on screen.
     */
    fun find(lookMs: Long): Found {
        val candidates = mutableListOf<Candidate>()
        var last: LoopbackProbe.Result? = null
        var why: String? = null
        var probes = 0
        val deadline = world.now() + lookMs
        var probeAt = deadline
        world.look().use { look ->
            while (!world.cancelled()) {
                val until = minOf(probeAt, deadline)
                val left = until - world.now()
                if (left > 0) {
                    val port = look.next(left)
                    if (port != null) {
                        if (candidates.none { it.port == port }) {
                            val answer = world.check(port)
                            candidates += Candidate(port, answer)
                            if (answer == AdbdCheck.Answer.ADBD) {
                                return Found(port, By.MDNS, candidates.toList(), last, why, probes)
                            }
                            probeAt = minOf(probeAt, world.now() + AFTER_A_DEAD_PORT_MS)
                        }
                        continue
                    }
                    if (world.now() < until) continue
                }
                // The deadline came before the next probe was due: done.
                if (world.now() < probeAt) break
                why = world.noProbe()
                if (why == null) {
                    probes++
                    val result = world.probe()
                    last = result
                    result.port?.let { return Found(it, By.PROBE, candidates.toList(), result, null, probes) }
                }
                if (world.now() >= deadline) break
                // Never past the deadline: an Allow in the look's last seconds is
                // still seen (review round 2).
                probeAt = minOf(world.now() + AGAIN_MS, deadline)
            }
        }
        return Found(null, null, candidates.toList(), last, why, probes)
    }

    companion object {
        const val AFTER_A_DEAD_PORT_MS = 500L

        /** Between probes: the Honor's sweep takes 1.2 s, the AVDs' up to 3.5 s. */
        const val AGAIN_MS = 3_000L
    }
}
