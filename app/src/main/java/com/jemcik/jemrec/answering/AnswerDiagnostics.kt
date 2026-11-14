package com.jemcik.jemrec.answering

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
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
    private const val KEY_MIXER = "answer_mixer_"
    private const val KEY_TEST = "answer_test"
    private const val KEY_CLEARED = "answer_cleared_by_update"
    private const val VOLTE = "VoLTE"

    /** Why an update cleared this phone's test results: said where they are
     *  shown until a test is filed again. Stored by name. */
    enum class Cleared {
        /** 1.0: its VoLTE result (AnsweringMachine.settleUpdates). */
        VOLTE,

        /** 0.31: all of them, as the greeting's route came to follow the
         *  call (AnsweringMachine.settleRoutesByPath). */
        ROUTES,
    }

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

    /** The arming a recorder's result is for (the id the app sent with it),
     *  or null from a recorder before 0.31, which names none. Pure. */
    fun arming(result: Map<String, String>): Long? = result["arming"]?.toLongOrNull()

    /**
     * The call's type: the audio path differs between them. Wi-Fi Calling
     * where Telecom marked the call so ([wifi] 1, read by the recorder
     * mid-greeting, with the route); else from telephony's voice network type ([voice]),
     * which a Galaxy S23 gave as LTE for a test call Telecom had on Wi-Fi
     * from its start (2026-11-12). No mark ([wifi] 0, or -1: not read, or a recorder before
     * 0.31, which sends none) leaves the voice type to say, as before. Pure.
     */
    fun callType(voice: Int, wifi: Int): String = if (wifi == 1) "Wi-Fi Calling" else when (voice) {
        1, 2, 4, 7, 11, 16 -> "2G"
        3, 5, 6, 8, 9, 10, 12, 14, 15, 17 -> "3G"
        13 -> "VoLTE"
        18 -> "Wi-Fi Calling"
        20 -> "VoNR"
        else -> "an unknown call type"
    }

    fun rememberStageOne(context: Context, fields: Map<String, String>) {
        Prefs.of(context).edit {
            putString(KEY_STAGE_ONE, fields.entries.joinToString(" ") { "${it.key}=${it.value}" })
        }
    }

    /** The last no-call checks; null if never run. */
    fun stageOne(context: Context): StageOne? =
        Prefs.of(context).getString(KEY_STAGE_ONE, null)?.let { line ->
            stageOne(line.split(' ').filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') })
        }

    fun rawStageOne(context: Context): String? = Prefs.of(context).getString(KEY_STAGE_ONE, null)

    /** [mixer]: the test's greeting went to a call through the mixer, whose
     *  route is the call-screening one whatever this app's (the recorder's
     *  CallDecisions.greetingRoute); null keeps what its measurement noted,
     *  for the caller's answer to the same test. */
    fun record(context: Context, callType: String, verdict: Verdict, mixer: Boolean? = null) {
        Prefs.of(context).edit {
            putString(KEY_RESULT + callType, verdict.name)
            // A test filed: what an update cleared is no longer the news.
            remove(KEY_CLEARED)
            when (mixer) {
                true -> putBoolean(KEY_MIXER + callType, true)
                false -> remove(KEY_MIXER + callType)
                null -> Unit
            }
        }
    }

    /** The call types whose last test went to a call through the mixer. */
    fun mixerTypes(context: Context): Set<String> =
        Prefs.of(context).all.entries
            .filter { it.key.startsWith(KEY_MIXER) }
            .map { it.key.removePrefix(KEY_MIXER) }
            .toSet()

    /**
     * A test call not yet done with, kept past the screen that armed it: the
     * recorder answers the call whether a screen is watching or not, and only
     * a screen reads how it went. [Armed]: the call may still come, its
     * window open until [Armed.until]. [Asking]: it came, and the caller is
     * to be asked whether they heard the greeting.
     */
    sealed interface PendingTest {
        data class Armed(val incoming: Boolean, val until: Long) : PendingTest
        data class Asking(val callType: String) : PendingTest
    }

    fun pendingTest(context: Context): PendingTest? {
        AnsweringMachine.settleUpdates(context)
        val words = Prefs.of(context).getString(KEY_TEST, null)?.split(' ') ?: return null
        return when (words.firstOrNull()) {
            "armed" -> words.getOrNull(2)?.toLongOrNull()?.let { PendingTest.Armed(words[1] == "1", it) }
            "asking" -> words.drop(1).joinToString(" ").takeIf { it.isNotEmpty() }?.let { PendingTest.Asking(it) }
            else -> null
        }
    }

    /** A test call is armed, [incoming] or not, its window open until [until].
     *  Under the takes' lock: written between a take's look and its write,
     *  it would be lost. */
    @Synchronized
    fun noteTestArmed(context: Context, incoming: Boolean, until: Long) {
        Prefs.of(context).edit { putString(KEY_TEST, "armed ${if (incoming) 1 else 0} $until") }
    }

    /**
     * The test armed until [until] - the one a screen watched - taken by the
     * screen that read how it went, or found that no call came, and now
     * asking the caller [asking] about, or done with on null. True for the
     * first screen to take it only: a second screen open on the same test -
     * a second JemRec window, which until 0.30 the tile, the media
     * notification or a notification's tap could open over the first - read
     * the same result, and acted on it again: the route moved twice, "Did
     * they hear it?" asked twice. Nor is a newer test taken, armed since
     * elsewhere.
     * Written with apply: the lock makes the take once, and preferences are
     * read from memory.
     */
    @Synchronized
    fun takeArmedTest(context: Context, until: Long, asking: String?): Boolean {
        if ((pendingTest(context) as? PendingTest.Armed)?.until != until) return false
        Prefs.of(context).edit {
            if (asking == null) remove(KEY_TEST) else putString(KEY_TEST, "asking $asking")
        }
        return true
    }

    /** The question about [callType] answered, or put aside: true for the
     *  first screen to take it only. */
    @Synchronized
    fun takeQuestion(context: Context, callType: String): Boolean {
        if (pendingTest(context) != PendingTest.Asking(callType)) return false
        Prefs.of(context).edit { remove(KEY_TEST) }
        return true
    }

    /** Each call type tested, and how it came out. */
    fun results(context: Context): Map<String, Verdict> {
        AnsweringMachine.settleUpdates(context)
        return Prefs.of(context).all.entries
            .filter { it.key.startsWith(KEY_RESULT) }
            .mapNotNull { e -> (e.value as? String)?.let { v -> runCatching { e.key.removePrefix(KEY_RESULT) to Verdict.valueOf(v) }.getOrNull() } }
            .toMap()
    }

    /** Why an update cleared test results here, until a test is filed. */
    fun cleared(context: Context): Cleared? {
        AnsweringMachine.settleUpdates(context)
        return Prefs.of(context).getString(KEY_CLEARED, null)?.let { runCatching { Cleared.valueOf(it) }.getOrNull() }
    }

    /** [why] noted in [edit], an update's own write (AnsweringMachine). */
    internal fun noteCleared(edit: SharedPreferences.Editor, why: Cleared) {
        edit.putString(KEY_CLEARED, why.name)
    }

    /**
     * What 1.0's one step clears ([stored]: the preferences as they are): a
     * test result filed under VoLTE, its mixer mark, and a question about a
     * VoLTE test still to be asked. Until 0.31 a test over Wi-Fi Calling was
     * filed under VoLTE where the phone's voice network read LTE through it
     * (a Galaxy S23's did, 2026-11-12), and a result does not say which
     * version filed it. Pure.
     */
    fun volteKeys(stored: Map<String, *>): List<String> = listOfNotNull(
        (KEY_RESULT + VOLTE).takeIf { it in stored },
        (KEY_MIXER + VOLTE).takeIf { it in stored },
        KEY_TEST.takeIf { stored[KEY_TEST] == "asking $VOLTE" },
    )

    /** The keys of every test result and its mark, and of a test still to
     *  be read or asked about - its question answered after a clearing would
     *  file the route before it under the one after - for a clearing
     *  (AnsweringMachine.settleRoutesByPath). */
    internal fun resultKeys(context: Context): List<String> =
        Prefs.of(context).all.keys.filter { it.startsWith(KEY_RESULT) || it.startsWith(KEY_MIXER) || it == KEY_TEST }

    /**
     * Whether a phone's route and test results from before 0.31 are put
     * back (AnsweringMachine.settleRoutesByPath): it is on the call-screening
     * route ([route]) though the checks found in-call music ([stage]) - which
     * only a failed test does (routeAfterFailure; routeAfterChecks moves to it
     * only where there is none). Not where the checks could not read the
     * audio setup: a failed test moved those too, but so may checks run
     * again after a first run found no in-call music output, and put back on
     * in-call music, a phone without one would have every greeting fail and
     * the caller hung up on. Pure.
     */
    fun resetsForRoutesByPath(route: String, stage: StageOne?): Boolean =
        route == AnsweringMachine.ROUTE_SCREENING && stage?.inCallMusic == true

    /**
     * The route to switch to once the no-call checks found [stage], with
     * [route] the one in use; null to keep it. Only the call-screening route
     * there: that is the one to try. Not on a setup that could not be read:
     * that is no "no" to in-call music.
     */
    fun routeAfterChecks(stage: StageOne, route: String): String? =
        AnsweringMachine.ROUTE_SCREENING.takeIf {
            !stage.inCallMusic && !stage.audioUnread && stage.screening && route != AnsweringMachine.ROUTE_SCREENING
        }

    /**
     * The route to try next after [route] failed a test call of [type]; null
     * to keep it. The route just failed is not the only one: try the other
     * next time - unless this one passed on another call type ([results]),
     * which one route for every call would break; and back again if that
     * fails too. Back as well where the checks ([stage]) could not read the
     * audio setup: no "no" to in-call music, as for the switch
     * (StageOne.possible). On the Honor the screening route reached nobody
     * (docs/ARCHITECTURE.md). A test of a call through the mixer ([mixer]:
     * the types whose last test went there) had the call-screening route
     * whatever [route]: neither its failure nor its pass is this route's. A
     * Galaxy S23 passed over Wi-Fi Calling there, which kept the route
     * through a failure over VoLTE that only the other route mends
     * (2026-11-13).
     */
    fun routeAfterFailure(
        route: String,
        stage: StageOne?,
        results: Map<String, Verdict>,
        type: String,
        mixer: Set<String> = emptySet(),
    ): String? {
        if (type in mixer) return null
        val passedElsewhere = results.any { (other, v) ->
            other != type && other !in mixer && (v == Verdict.WORKS || v == Verdict.LIKELY)
        }
        return when {
            passedElsewhere -> null
            route == AnsweringMachine.ROUTE_MUSIC && stage?.screening == true -> AnsweringMachine.ROUTE_SCREENING
            route == AnsweringMachine.ROUTE_SCREENING && (stage?.inCallMusic == true || stage?.audioUnread == true) ->
                AnsweringMachine.ROUTE_MUSIC
            else -> null
        }
    }

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
    fun report(
        stageOneLine: String?,
        results: Map<String, Verdict>,
        route: String,
        appVersion: String,
        mixer: Set<String> = emptySet(),
    ): String =
        buildString {
            appendLine("JemRec $appVersion - answering machine report")
            appendLine("Checks: ${stageOneLine ?: "not run"}")
            appendLine("Route: $route")
            if (results.isEmpty()) {
                appendLine("Test calls: none yet")
            } else {
                results.entries.sortedBy { it.key }.forEach { (type, v) ->
                    appendLine("Test call on $type: $v" + if (type in mixer) " (through the mixer)" else "")
                }
            }
        }.trimEnd()
}
