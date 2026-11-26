package com.jemcik.jemrec.adb

import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Finds adbd's Wireless debugging port with no mDNS at all: every port of the
 * range the kernel picks it from - adbd's TLS server binds port 0
 * (daemon/adb_wifi.cpp, TlsServer(0)) - asked [AdbdCheck] until one answers
 * as adbd.
 *
 * WHY. The port is learnt by mDNS, and mDNS can give none that answers while
 * adbd listens: Android's NsdService answers a look from its cache and keeps
 * the port adbd had before Wireless debugging went off and on - one look on
 * the Honor, all of five minutes of looks on the Android 16 AVD - and adbd's
 * own announcement can fail where mdnsd is not running yet (a Moto G05,
 * Android 15). Issue #140; tmp/issue-140/PLAN.md has the measurements.
 *
 * THE COST, measured 2026-11-26 as the app's uid (P0): the whole range in
 * 1.2 s on the Honor, 2.5-3.5 s on the AVDs, 32 at once; adbd found in every
 * run. Every listener in the range gets one CONNECT - a 31-byte hello - and
 * is let go. The app cannot read the range itself
 * (/proc/sys/net/ipv4/ip_local_port_range: EACCES on all three), so it is
 * Linux's default, which is what the Android 15 AVD's shell read.
 *
 * A firewall that drops this uid's loopback - Doze, power saving, a
 * restricted app (bpf_progs/netd.c: those chains apply to loopback,
 * android-15.0.0_r1) - would make every port time out instead of refusing;
 * one closed port asked first says so, and no sweep is made.
 */
internal class LoopbackProbe(
    private val check: (Int) -> AdbdCheck.Answer = { AdbdCheck.check(it) },
    private val ports: IntRange = EPHEMERAL,
    private val parallel: Int = PARALLEL,
    private val budgetMs: Long = BUDGET_MS,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
    /** The caller gone: the workers stop at their next port. */
    private val cancelled: () -> Boolean = { false },
) {

    data class Result(
        /** adbd's port, null if none answered. */
        val port: Int?,
        /** Ports asked. */
        val asked: Int,
        /** Ports that listened and were not adbd. */
        val others: Int,
        /** Ports whose connect timed out. */
        val timedOut: Int,
        val ms: Long,
        /** The closed port timed out: loopback dropped for this uid, no sweep. */
        val blocked: Boolean = false,
        /** Stopped at [BUDGET_MS] with ports left. */
        val outOfTime: Boolean = false,
        /** Stopped because its caller went. */
        val cancelled: Boolean = false,
    ) {
        /** For a log line and the setup report: ports only, nothing personal. */
        fun summary(): String = when {
            blocked -> "loopback dropped for JemRec (a closed port timed out), no probe"
            port != null -> "found by the probe on $port after $asked ports in $ms ms"
            cancelled -> "probe stopped by its caller after $asked ports"
            outOfTime -> "probe stopped at ${ms} ms after $asked ports, adbd not among them"
            else -> "probe: $asked ports in $ms ms, adbd not among them" +
                (if (others > 0) ", $others other listeners" else "") +
                (if (timedOut > 0) ", $timedOut timed out" else "")
        }
    }

    fun run(): Result {
        val start = now()
        if (check(CLOSED_PORT) == AdbdCheck.Answer.TIMED_OUT) {
            return Result(null, 1, 0, 1, now() - start, blocked = true)
        }
        val next = AtomicInteger(ports.first)
        val found = AtomicInteger(0)
        val asked = AtomicInteger(0)
        val others = AtomicInteger(0)
        val timedOut = AtomicInteger(0)
        val deadline = start + budgetMs
        val workers = (1..parallel).map {
            thread(isDaemon = true, name = "adbd-probe") {
                while (found.get() == 0 && now() < deadline && !cancelled()) {
                    val port = next.getAndIncrement()
                    if (port > ports.last) break
                    asked.incrementAndGet()
                    when (check(port)) {
                        AdbdCheck.Answer.ADBD -> found.compareAndSet(0, port)
                        AdbdCheck.Answer.OTHER -> others.incrementAndGet()
                        AdbdCheck.Answer.TIMED_OUT -> timedOut.incrementAndGet()
                        AdbdCheck.Answer.CLOSED -> Unit
                    }
                }
            }
        }
        // Each worker ends at the deadline or on its own, at most one check
        // past it: a connect cap and an answer cap.
        workers.forEach { it.join(budgetMs + AdbdCheck.CONNECT_MS + AdbdCheck.ANSWER_MS) }
        val port = found.get().takeIf { it > 0 }
        return Result(
            port = port,
            asked = asked.get(),
            others = others.get(),
            timedOut = timedOut.get(),
            ms = now() - start,
            outOfTime = port == null && next.get() <= ports.last && !cancelled(),
            cancelled = port == null && cancelled(),
        )
    }

    companion object {
        /** Linux's default ip_local_port_range. */
        val EPHEMERAL = 32768..60999

        /** P0's setting: 1.2 s for the range on the Honor. */
        const val PARALLEL = 32

        /** Three times the slowest sweep in P0 (3.5 s, the Android 15 AVD). */
        const val BUDGET_MS = 10_000L

        /** tcpmux: privileged, nothing on a phone listens on it, so it refuses -
         *  unless the uid's loopback is dropped, and then it times out. */
        const val CLOSED_PORT = 1
    }
}
