package com.jemcik.jemrec.capture

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
 * It writes no file. The stream is read and dropped, so there is nothing to
 * clean up afterwards and no stray recording to explain.
 */
object SelfTest {

    private const val TAG = "JemRec"

    /** Enough to prove audio is flowing, short enough not to feel like a wait. */
    private const val PACKETS_WANTED = 5
    private const val TIMEOUT_MS = 6_000L

    data class Result(val passed: Boolean, val report: String)

    suspend fun run(context: Context): Result = withContext(Dispatchers.IO) {
        val lines = mutableListOf<String>()

        if (!CaptureDaemon.isRunning(context)) {
            return@withContext Result(
                passed = false,
                report = buildString {
                    appendLine("FAIL - the recorder is not answering")
                    appendLine()
                    appendLine("Nothing is listening on 127.0.0.1:${CaptureDaemon.PORT}.")
                    appendLine("Connect to any Wi-Fi - it restarts on its own.")
                },
            )
        }
        lines += "recorder:  answering on 127.0.0.1:${CaptureDaemon.PORT}"

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
            outcome == null -> Result(
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
                Result(
                    passed = false,
                    report = buildString {
                        appendLine("FAIL - ${outcome.exceptionOrNull()?.message}")
                        appendLine()
                        lines.forEach { appendLine(it) }
                    },
                )
            }

            else -> Result(
                passed = true,
                report = buildString {
                    appendLine("PASS - a call would be recorded")
                    appendLine()
                    lines.forEach { appendLine(it) }
                },
            )
        }
    }
}
