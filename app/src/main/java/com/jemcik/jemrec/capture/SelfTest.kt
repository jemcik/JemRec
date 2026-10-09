package com.jemcik.jemrec.capture

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Does this phone record calls, right now?
 *
 * WHAT IT REPLACED, AND WHY THAT HAD TO GO
 *
 * The old self-test was the transport's acceptance check: it ran `id` over an
 * ADB shell session and passed only on uid=2000(shell). That was the right
 * question while the transport was the thing being proven, and it stopped being
 * the right question the moment ADB became a bootstrap that closes itself. The
 * app now shuts the ADB session as soon as the recorder is up, so the healthy
 * steady state has no session at all - and the check reported FAIL, "not
 * connected - pair and connect first", directly beneath a panel saying
 * "Recorder running. No ADB session. Normal."
 *
 * A diagnostic that reads FAIL when everything is fine is worse than none. It
 * teaches you to ignore it, and then it is there for the day it matters.
 *
 * WHAT IT CHECKS NOW
 *
 * The chain a call actually travels: the recorder answers, it can open a
 * capture, and audio comes out of it. Over loopback, so it needs no ADB, no
 * Wi-Fi and no permission - the same conditions a real call is recorded under.
 *
 * AND THE LINK THAT CHAIN LEFT OUT: NOTICING THE CALL
 *
 * A diagnostic that reads PASS when calls are being lost is worse still, and
 * this one did. A call is recorded only if the daemon's call watch hears it
 * start, and nothing above asks whether it does. A user's phone showed the gap:
 * "PASS - a call would be recorded" while every call after the first went
 * unrecorded, because the daemon's watch had been garbage-collected (see
 * Main.callWatch). So the daemon is now asked what its watch has heard, and
 * that is held against the phone's own call log - a call the log has and the
 * watch never heard is a FAIL, whatever the capture says. The log needs
 * READ_CALL_LOG, which is optional; without it everything else still runs and
 * the report says what it could not check.
 *
 * It writes no file. The stream is read and dropped, so there is nothing to
 * clean up afterwards and no stray recording to explain.
 */
object SelfTest {

    private const val TAG = "JemRec"

    /** Enough to prove audio is flowing, short enough not to feel like a wait. */
    private const val PACKETS_WANTED = 5
    private const val TIMEOUT_MS = 6_000L

    /** A recording with no call up may be one whose call ended a moment ago:
     *  the daemon takes about two seconds to confirm an end. Asked again after
     *  this long before it counts as stuck. */
    private const val STUCK_RECHECK_MS = 4_000L

    /**
     * Jitter only. The log dates a call when telecom creates it, and the watch
     * can only hear of it after that - it rings or dials once it exists - so a
     * heard call always has an event at or after its log date. This was ten
     * seconds, and that was a blind spot, not a margin: a call placed nine
     * seconds after the last one ended passed as heard on a deaf watch
     * (measured on the emulator, the two calls back to back).
     */
    internal const val HEARD_SLACK_MS = 2_000L

    private const val RESTART = "Restart the phone, then open JemRec on Wi-Fi."

    data class Result(val passed: Boolean, val report: String)

    suspend fun run(context: Context): Result = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()

        val answer = CaptureDaemon.answer(context)
            ?: return@withContext Result(
                passed = false,
                report = buildString {
                    appendLine("FAIL - the recorder is not answering")
                    appendLine()
                    appendLine("Nothing is listening on 127.0.0.1:${CaptureDaemon.PORT}.")
                    appendLine("Connect to any Wi-Fi - it restarts on its own.")
                },
            )
        lines += "recorder:  answering on 127.0.0.1:${CaptureDaemon.PORT}"
        val current = CaptureDaemon.isCurrent(context, answer)
        if (!current) lines += "build:     ${answer.build}, older than this app"

        val outcome = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching {
                AudioStream(CaptureDaemon.connect(context)).use { stream ->
                    // The first packet carries the OpusHead. Without it there
                    // is no container to write, so a capture that cannot
                    // produce one cannot record a call either.
                    val config = stream.readPacket()
                    require(config != null && config.isConfig) {
                        "no config packet; the capture did not start"
                    }
                    lines += "codec:     ${stream.codecId}"
                    lines += "config:    ${config.data.size} bytes"

                    var packets = 0
                    var bytes = 0
                    while (packets < PACKETS_WANTED) {
                        val packet = stream.readPacket() ?: break
                        if (packet.isConfig) continue
                        packets++
                        bytes += packet.data.size
                    }
                    require(packets > 0) { "the capture opened but produced no audio" }
                    lines += "audio:     $packets packets, $bytes bytes"
                }
            }
        }

        when {
            // Null means the timeout won: the capture opened but audio did
            // not come, or did not come fast enough. During a real call that is
            // expected - the recording has the voice-call source, and a
            // diagnostic must not be allowed to disturb it.
            outcome == null -> return@withContext Result(
                passed = false,
                report = buildString {
                    appendLine("FAIL - the recorder did not answer in time")
                    appendLine()
                    lines.forEach { appendLine(it) }
                    appendLine()
                    appendLine("If a call is in progress this is expected: the")
                    appendLine("recording has the audio source, and the call keeps it.")
                },
            )

            outcome.isFailure -> {
                Log.w(TAG, "self-test failed", outcome.exceptionOrNull())
                return@withContext Result(
                    passed = false,
                    report = buildString {
                        appendLine("FAIL - ${outcome.exceptionOrNull()?.message}")
                        appendLine()
                        lines.forEach { appendLine(it) }
                    },
                )
            }
        }

        // The capture works. Whether anything will ever start it is the
        // daemon's call watch, asked about next.
        val problem = watchProblem(context, current, lines)
        Result(
            passed = problem == null,
            report = buildString {
                appendLine(
                    if (problem != null) "FAIL - $problem"
                    else "PASS - ${verdict(CaptureDaemon.currentMode(context))}",
                )
                appendLine()
                lines.forEach { appendLine(it) }
            },
        )
    }

    /**
     * What is wrong with the daemon's call watch, or null if nothing is.
     * Adds what it learns to the report on the way.
     */
    private suspend fun watchProblem(
        context: Context,
        current: Boolean,
        lines: MutableList<String>,
    ): String? {
        val watch = CaptureDaemon.watch(context)
        if (watch == null) {
            if (current) return "the recorder would not say whether it hears calls"
            // Every build too old to give this report is one whose watch could
            // be garbage-collected: it may be deaf right now, and nothing short
            // of a call can tell. A revive replaces it, so one is asked for now
            // rather than at the next keep-alive tick, up to fifteen minutes on.
            RecorderKeepAlive.kickNow(context)
            return "this recorder is an older build that can stop noticing calls. " +
                "JemRec replaces it as soon as the phone is on Wi-Fi."
        }
        lines += "watching:  since ${at(watch.since)}"
        if (!watch.watching) return "the recorder is not watching for calls. $RESTART"
        if (!watch.looperOk) return "the recorder has stopped handling calls. $RESTART"
        lines += "heard:     " + calls(watch.offHooks) +
            if (watch.lastOffHook > 0) ", last at ${at(watch.lastOffHook)}" else ""

        // A daemon that missed a push records by the wrong rule - or, since it
        // fails closed, not at all - and still passes everything above. One
        // round trip puts it right.
        val wanted = CaptureDaemon.currentMode(context)
        when {
            watch.mode == wanted -> lines += "mode:      ${modeName(wanted)}"
            CaptureDaemon.setDaemonMode(context, wanted) ->
                lines += "mode:      ${modeName(wanted)} (was ${modeName(watch.mode)}, corrected)"
            else -> return "the recorder is set to \"${modeName(watch.mode)}\", " +
                "not \"${modeName(wanted)}\""
        }

        // A daemon that believes a call is up when none is has lost the end of
        // one - unless it ended a moment ago and the daemon is still confirming
        // it, which takes about two seconds. So it is asked twice.
        if ((watch.inCall || watch.recordingSince > 0) && !CaptureDaemon.callInProgress(context)) {
            delay(STUCK_RECHECK_MS)
            val again = CaptureDaemon.watch(context)
            if (again != null && (again.inCall || again.recordingSince > 0) &&
                !CaptureDaemon.callInProgress(context)
            ) {
                return if (again.recordingSince > 0) {
                    "a recording has run since ${at(again.recordingSince)} with no call in progress. $RESTART"
                } else {
                    "the recorder thinks a call is still going on, and none is. $RESTART"
                }
            }
        }

        when {
            !CallLogLookup.granted(context) ->
                lines += "missed:    not checked (no call log access)"
            CaptureDaemon.callInProgress(context) ->
                lines += "missed:    not checked during a call"
            else -> {
                val starts = runCatching { CallLogLookup.callStartsSince(context, watch.since) }
                    .onFailure { Log.w(TAG, "self-test: could not read the call log", it) }
                    .getOrNull()
                if (starts == null) {
                    lines += "missed:    not checked (call log unreadable)"
                } else {
                    unheardCall(starts, watch.lastEvent)?.let {
                        return "the recorder did not notice the call at ${at(it)}. " +
                            "It is running, but not hearing calls. $RESTART"
                    }
                    lines += "missed:    none in the call log"
                }
            }
        }
        return null
    }

    /**
     * The latest call the log has that the watch did not hear, or null.
     *
     * Every call the log is asked for (see CallLogLookup.callStartsSince) went
     * off-hook here, which reaches the watch as an event at or after the moment
     * the log dates the call. So a watch whose last event is older than the
     * latest call's start, by more than a little slack, never heard it. Only the
     * latest needs asking about: the watch hears in order, so an event after it
     * covers every call before it.
     */
    internal fun unheardCall(callStarts: List<Long>, lastEvent: Long): Long? {
        val latest = callStarts.maxOrNull() ?: return null
        return latest.takeIf { lastEvent < it - HEARD_SLACK_MS }
    }

    private fun verdict(mode: Int): String = when (mode) {
        CaptureDaemon.MODE_AUTOMATIC -> "a call would be recorded"
        CaptureDaemon.MODE_ON_DEMAND -> "you would be asked to record a call"
        else -> "the recorder works; recording is off"
    }

    /** In the app's own words for the modes - see MainScreen. */
    private fun modeName(mode: Int): String = when (mode) {
        CaptureDaemon.MODE_AUTOMATIC -> "every call"
        CaptureDaemon.MODE_ON_DEMAND -> "asks first"
        else -> "off"
    }

    private fun calls(n: Int): String = if (n == 1) "1 call" else "$n calls"

    /** "14:05" today, "3 Oct 14:05" before that. */
    private fun at(millis: Long): String {
        val then = Calendar.getInstance().apply { timeInMillis = millis }
        val now = Calendar.getInstance()
        val today = then.get(Calendar.YEAR) == now.get(Calendar.YEAR) &&
            then.get(Calendar.DAY_OF_YEAR) == now.get(Calendar.DAY_OF_YEAR)
        return SimpleDateFormat(if (today) "HH:mm" else "d MMM HH:mm", Locale.US).format(Date(millis))
    }
}
