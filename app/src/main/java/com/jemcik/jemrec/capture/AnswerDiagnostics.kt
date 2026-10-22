package com.jemcik.jemrec.capture

import android.content.Context
import com.jemcik.jemrec.Prefs

/**
 * Whether the answering machine works on this phone - found out, not assumed.
 *
 * Android's own answer is no use: isPstnCallAudioInterceptable said yes on an
 * Honor for a route the caller never heard. So three stages, each from what
 * was measured on that Honor (2026-10-22):
 *
 *  1. NO CALL. The recorder (the shell) holds the permissions, and the phone's
 *     audio setup declares an in-call music output - the one Qualcomm mixes
 *     into the caller's side. The emulator declares none (Android 17,
 *     2026-10-22), nor does the published audio setup of a Huawei Kirin 970
 *     (read, not tried on a phone); there only the call-screening route is
 *     left, which reached nobody on the Honor. With neither, a "no" is
 *     certain; a "yes" is "possible".
 *  2. A TEST CALL, measured by the recorder: the greeting on the in-call music
 *     output, and played at real-time pace (the route that failed was eaten
 *     three times too fast); and the check tone in the phone's own recording,
 *     which proves it went out - but whose absence proves nothing (on the
 *     Honor's 2G calls it was never there, though the caller heard it).
 *  3. "DID THEY HEAR IT?" after a test call someone made: the only certainty.
 *
 * Results are kept per call type: 2G, VoLTE and Wi-Fi Calling take different
 * audio paths, and a pass on one says nothing about another.
 */
object AnswerDiagnostics {

    private const val KEY_STAGE_ONE = "answer_stage_one"
    private const val KEY_RESULT = "answer_result_"

    enum class Verdict {
        /** Proven: the caller said so, or the phone's own recording carried the check tone. */
        WORKS,
        /** On the right output at the right pace; nobody said they heard it. */
        LIKELY,
        /** The greeting went somewhere the caller cannot hear. */
        FAILS,
        /** The test did not get far enough to tell. */
        INCONCLUSIVE,
    }

    /** What the no-call checks found. */
    data class StageOne(
        val permissions: Boolean,
        val inCallMusic: Boolean,
        val screening: Boolean,
        val describe: String,
        /** What the recorder said it lacks, comma-separated; empty when nothing. */
        val missing: String = "",
        /** The phone's audio setup could not be read: neither a yes nor a
         *  certain no, so the test call is left to tell. */
        val audioUnread: Boolean = false,
    ) {
        /** The answering machine can be tried at all. */
        val possible: Boolean get() = permissions && (inCallMusic || screening || audioUnread)

        /** Why not, in a sentence, or null. */
        val problem: String?
            get() = when {
                !permissions && "mute" in missing.split(',') ->
                    "This phone does not let the recorder mute your microphone, so a caller would " +
                        "hear the room."
                !permissions && "calls" in missing.split(',') ->
                    "The recorder cannot tell when another app is in a call here, so it would " +
                        "never answer."
                !permissions -> "The recorder lacks a permission it needs here."
                !inCallMusic && !screening && !audioUnread ->
                    "This phone has no way to play a greeting into a call: its audio setup has " +
                        "no in-call music output."
                else -> null
            }
    }

    /** The recorder's "DIAG 1" fields as a [StageOne]. */
    fun stageOne(fields: Map<String, String>): StageOne = StageOne(
        permissions = fields["perms"] == "ok",
        missing = fields["perms"]?.takeIf { it != "ok" }.orEmpty(),
        inCallMusic = fields["incallmusic"] == "1",
        screening = fields["screening"] == "1",
        audioUnread = fields["incallmusic"] == "-1",
        describe = listOfNotNull(
            fields["maker"], fields["model"], fields["soc"]?.let { "($it)" },
            fields["sdk"]?.let { "Android API $it" },
        ).joinToString(" "),
    )

    /**
     * A test call's verdict from what the recorder measured ("RESULT 1"
     * fields). The route check only means something on the in-call music
     * route; the pace is within a third of real time.
     */
    fun verdict(result: Map<String, String>): Verdict {
        // The greeting could not be played at all on this route.
        if (result["played"] == "0") return Verdict.FAILS
        // The microphone would not mute: the caller would hear the room.
        if (result["mute"] == "0") return Verdict.FAILS
        val audioMs = result["audioms"]?.toLongOrNull() ?: 0L
        val realMs = result["realms"]?.toLongOrNull() ?: 0L
        if (audioMs <= 0 || realMs <= 0) return Verdict.INCONCLUSIVE
        val pace = realMs.toDouble() / audioMs
        if (pace < 0.7 || pace > 1.3) return Verdict.FAILS
        if (result["route"] == "0") return Verdict.FAILS
        return if (result["tone"] == "1") Verdict.WORKS else Verdict.LIKELY
    }

    /** The call's type from telephony's voice network type: the audio path
     *  differs between them. */
    fun callType(voice: Int): String = when (voice) {
        1, 2, 4, 7, 11, 16 -> "2G"
        3, 5, 6, 8, 9, 10, 12, 14, 15, 17 -> "3G"
        13 -> "VoLTE"
        18 -> "Wi-Fi Calling"
        20 -> "VoNR"
        else -> "an unknown call type"
    }

    fun rememberStageOne(context: Context, fields: Map<String, String>) {
        Prefs.of(context).edit()
            .putString(KEY_STAGE_ONE, fields.entries.joinToString(" ") { "${it.key}=${it.value}" })
            .apply()
    }

    /** The last no-call checks; null if never run. */
    fun stageOne(context: Context): StageOne? =
        Prefs.of(context).getString(KEY_STAGE_ONE, null)?.let { line ->
            stageOne(line.split(' ').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') })
        }

    fun rawStageOne(context: Context): String? = Prefs.of(context).getString(KEY_STAGE_ONE, null)

    fun record(context: Context, callType: String, verdict: Verdict) {
        Prefs.of(context).edit().putString(KEY_RESULT + callType, verdict.name).apply()
    }

    /** Each call type tested, and how it came out. */
    fun results(context: Context): Map<String, Verdict> =
        Prefs.of(context).all.entries
            .filter { it.key.startsWith(KEY_RESULT) }
            .mapNotNull { e -> (e.value as? String)?.let { v -> runCatching { e.key.removePrefix(KEY_RESULT) to Verdict.valueOf(v) }.getOrNull() } }
            .toMap()

    /** The tested call types, in words: "Works on 2G", "Likely works on
     *  VoLTE, not tested on 2G". Null when nothing was tested. Pure. */
    fun summary(results: Map<String, Verdict>): String? {
        if (results.isEmpty()) return null
        return results.entries.sortedBy { it.key }.joinToString("; ") { (type, v) ->
            when (v) {
                Verdict.WORKS -> "Works on $type"
                Verdict.LIKELY -> "Very likely works on $type"
                Verdict.FAILS -> "Does not work on $type"
                Verdict.INCONCLUSIVE -> "Test on $type did not finish"
            }
        }
    }

    /** Everything a report needs, for the user to paste into an issue.
     *  Nothing about calls or people: the phone, and what was measured. */
    fun report(stageOneLine: String?, results: Map<String, Verdict>, route: String, appVersion: String): String =
        buildString {
            appendLine("JemRec $appVersion - answering machine report")
            appendLine("Checks: ${stageOneLine ?: "not run"}")
            appendLine("Route: $route")
            if (results.isEmpty()) {
                appendLine("Test calls: none yet")
            } else {
                results.entries.sortedBy { it.key }.forEach { (type, v) -> appendLine("Test call on $type: $v") }
            }
        }.trimEnd()
}
