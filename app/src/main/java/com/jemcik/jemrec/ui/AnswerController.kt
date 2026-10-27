package com.jemcik.jemrec.ui

import android.app.Application
import android.media.AudioTrack
import android.util.Log
import com.jemcik.jemrec.capture.AnswerConditions
import com.jemcik.jemrec.capture.AnswerDiagnostics
import com.jemcik.jemrec.capture.AnsweringMachine
import com.jemcik.jemrec.capture.CallTracking
import com.jemcik.jemrec.capture.EmergencyPause
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.Greeting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the answering machine's screen shows. */
data class AnswerUi(
    val enabled: Boolean = false,
    val delay: Int = AnsweringMachine.DEFAULT_DELAY,
    val dndShort: Boolean = true,
    val longest: Int = AnsweringMachine.DEFAULT_LONGEST,
    val sim: String = AnsweringMachine.SIM_DEFAULT,
    /** The phone's SIMs, as the recorder lists them; shown with two or more. */
    val sims: List<Pair<String, String>> = emptyList(),
    val defaultSim: String? = null,
    /** Seconds of greeting, or null with none recorded. */
    val greetingSeconds: Double? = null,
    val recordingTake: Boolean = false,
    val takeStartedAt: Long = 0L,
    val playing: Boolean = false,
    val greetingProblem: String? = null,
    val callTracking: Boolean = true,
    /** The no-call checks: null until run once. */
    val stageOne: AnswerDiagnostics.StageOne? = null,
    val checking: Boolean = false,
    val results: Map<String, AnswerDiagnostics.Verdict> = emptyMap(),
    /** A test call: "armed", "running" or null; and until when it is armed. */
    val test: String? = null,
    val testIncoming: Boolean = true,
    val testUntil: Long = 0L,
    /** The test just finished needs the user's word: did they hear it? */
    val askHeard: String? = null,
    /** The carrier's no-reply forwarding: "off 0", "on 25 *1234", null unknown. */
    val carrier: String? = null,
    /** Standing down after an emergency call until then (EmergencyPause), or null. */
    val pausedUntil: Long? = null,
    val checkingCarrier: Boolean = false,
) {
    /** Why the switch cannot be turned on, or null when it can. */
    val blocked: String?
        get() = when {
            stageOne == null -> "Run the checks below first"
            !stageOne.possible -> stageOne.problem
            !callTracking -> "Needs exact call tracking, in Settings"
            greetingSeconds == null -> "Record a greeting first"
            else -> null
        }

    /** Standing down after an emergency call, and switched on: until when. */
    val paused: Long?
        get() = pausedUntil?.takeIf { enabled }

    /** The carrier's no-reply timer in seconds, when forwarding is on. */
    val carrierSeconds: Int?
        get() = carrier?.split(' ')?.takeIf { it.firstOrNull() == "on" }?.getOrNull(1)?.toIntOrNull()
}

/**
 * The answering machine's settings, greeting and diagnostics, for the
 * screens. Owned by MainViewModel; its own state flow, so the eighty fields of
 * the app's UiState do not grow by twenty.
 */
class AnswerController(
    private val app: Application,
    private val scope: CoroutineScope,
    private val say: (String) -> Unit,
    initial: AnswerUi? = null,
    /** A recording playing paused, before a take or the greeting's preview.
     *  Since 0.19 one plays on past the home screen, and a take records the
     *  microphone: over the loudspeaker, the call would go into the greeting
     *  every caller hears. */
    private val quiet: () -> Unit = {},
) {
    private val _state = MutableStateFlow(initial ?: AnswerUi())
    val state: StateFlow<AnswerUi> = _state.asStateFlow()

    private var take: Job? = null
    private var preview: AudioTrack? = null
    private var testWatch: Job? = null
    /** Tests seed the state; nothing is then read from disk over it. */
    private val seeded = initial != null

    /** Read everything back from the preferences. Cheap: no recorder calls. */
    fun reload() {
        if (seeded) return
        _state.update {
            it.copy(
                enabled = AnsweringMachine.enabled(app),
                delay = AnsweringMachine.delay(app),
                dndShort = AnsweringMachine.dndShort(app),
                longest = AnsweringMachine.longest(app),
                sim = AnsweringMachine.sim(app),
                defaultSim = AnswerConditions.defaultSim(app),
                greetingSeconds = if (Greeting.present(app)) Greeting.seconds(app) else null,
                callTracking = CallTracking.enabled(app),
                pausedUntil = EmergencyPause.pausedUntil(app),
                stageOne = AnswerDiagnostics.stageOne(app),
                results = AnswerDiagnostics.results(app),
            )
        }
    }

    /** The screen opened: the checks, if never run, and the SIMs. */
    fun opened() {
        if (seeded) return
        reload()
        if (_state.value.stageOne == null) runChecks()
        scope.launch {
            CaptureDaemon.sims(app)?.let { sims ->
                AnswerConditions.remember(app, sims)
                _state.update { it.copy(sims = sims.all, defaultSim = sims.defaultId) }
            }
        }
    }

    private fun push() {
        scope.launch {
            if (!CaptureDaemon.setAnswering(app)) {
                say("the recorder will pick up the answering machine when it next restarts")
            }
        }
    }

    /** Answer again before the day after an emergency call is out - the
     *  user's choice, made after the screen's warning. */
    fun resumeAfterEmergency() {
        EmergencyPause.resume(app)
        _state.update { it.copy(pausedUntil = null) }
    }

    fun setEnabled(on: Boolean) {
        if (on && _state.value.blocked != null) return
        AnsweringMachine.setEnabled(app, on)
        _state.update { it.copy(enabled = on) }
        push()
    }

    fun setDelay(seconds: Int) {
        AnsweringMachine.setDelay(app, seconds)
        _state.update { it.copy(delay = seconds) }
    }

    fun setDndShort(short: Boolean) {
        AnsweringMachine.setDndShort(app, short)
        _state.update { it.copy(dndShort = short) }
    }

    fun setLongest(seconds: Int) {
        AnsweringMachine.setLongest(app, seconds)
        _state.update { it.copy(longest = seconds) }
        push()
    }

    fun setSim(sim: String) {
        AnsweringMachine.setSim(app, sim)
        _state.update { it.copy(sim = sim) }
    }

    /** Start recording a greeting - on the recorder, which may use the
     *  microphone; this app holds no permission to. */
    fun startTake() {
        if (take?.isActive == true) return
        stopPreview()
        quiet()
        take = scope.launch {
            if (!CaptureDaemon.startTake(app)) {
                _state.update { it.copy(greetingProblem = "The recorder could not start recording. Is it running?") }
                return@launch
            }
            _state.update { it.copy(recordingTake = true, takeStartedAt = System.currentTimeMillis(), greetingProblem = null) }
            // The recorder stops by itself at thirty seconds; so does this.
            delay(Greeting.MAX_SECONDS * 1000L + 500)
            if (_state.value.recordingTake) stopTake()
        }
    }

    fun stopTake() {
        if (!_state.value.recordingTake) return
        _state.update { it.copy(recordingTake = false) }
        // The take's thirty-second wait goes with it, or Record again would
        // be refused until then. Record stays refused while this fetches:
        // a new take would overwrite the file being read.
        take?.cancel()
        take = scope.launch {
            CaptureDaemon.stopTake(app)
            val pcm = CaptureDaemon.fetchTake(app)
            val processed = withContext(Dispatchers.Default) { pcm?.let(Greeting::process) }
            when (processed) {
                is Greeting.Take.Usable -> {
                    withContext(Dispatchers.IO) { Greeting.save(app, processed.pcm) }
                    val sent = CaptureDaemon.uploadGreeting(app, processed.pcm)
                    if (!sent) say("the recorder will get the new greeting when it next restarts")
                    _state.update { it.copy(greetingSeconds = processed.seconds, greetingProblem = null) }
                    push()
                }
                Greeting.Take.Silent, null -> _state.update {
                    it.copy(greetingProblem = "Nothing was heard. Hold the phone as for a call, and speak.")
                }
                Greeting.Take.TooShort -> _state.update {
                    it.copy(greetingProblem = "That was too short for a greeting. Try again.")
                }
            }
        }
    }

    fun togglePreview() {
        if (_state.value.playing) {
            stopPreview()
            return
        }
        quiet()
        scope.launch {
            val pcm = withContext(Dispatchers.IO) { Greeting.file(app).takeIf { it.isFile }?.readBytes() } ?: return@launch
            val track = Greeting.preview(pcm) ?: return@launch
            preview = track
            _state.update { it.copy(playing = true) }
            delay((pcm.size / 2.0 / Greeting.RATE * 1000).toLong() + 300)
            if (preview === track) stopPreview()
        }
    }

    private fun stopPreview() {
        preview?.let { runCatching { it.stop(); it.release() } }
        preview = null
        _state.update { it.copy(playing = false) }
    }

    fun deleteGreeting() {
        stopPreview()
        Greeting.delete(app)
        AnsweringMachine.setEnabled(app, false)
        _state.update { it.copy(greetingSeconds = null, enabled = false) }
        scope.launch {
            CaptureDaemon.syncGreeting(app)
            CaptureDaemon.setAnswering(app)
        }
    }

    /** Stage one: the no-call checks, on the recorder. */
    fun runChecks() {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true) }
        scope.launch {
            val fields = CaptureDaemon.stageOne(app)
            if (fields == null) {
                say("the answering machine's checks need the recorder running")
            } else {
                AnswerDiagnostics.rememberStageOne(app, fields)
                val stage = AnswerDiagnostics.stageOne(fields)
                // Only the call-screening route here: that is the one to try.
                // Not on a setup that could not be read: that is no "no".
                if (!stage.inCallMusic && !stage.audioUnread && stage.screening &&
                    AnsweringMachine.route(app) != AnsweringMachine.ROUTE_SCREENING
                ) {
                    AnsweringMachine.setRoute(app, AnsweringMachine.ROUTE_SCREENING)
                    push()
                }
                _state.update { it.copy(stageOne = stage) }
            }
            _state.update { it.copy(checking = false) }
        }
    }

    /**
     * Stage two: a test call. Incoming - the next call in three minutes is
     * answered, whoever makes it; or outgoing - the recorder dials [number],
     * a line that answers by itself. The recorder plays the check tone and
     * the greeting, and measures.
     */
    fun startTest(incoming: Boolean, number: String = "") {
        // A test answers whoever calls in its three minutes: never in the day
        // after an emergency call, when that may be them calling back. Nor is
        // an emergency number ever called for one (the screen says so too).
        if (incoming && EmergencyPause.pausedUntil(app) != null) {
            say("no test call for a day after an emergency call - unless you answer again now")
            return
        }
        if (!incoming && EmergencyPause.isEmergencyNumber(app, number)) {
            say("an emergency number is never called for a test")
            return
        }
        scope.launch {
            // The test plays the greeting the recorder has, on the route the
            // recorder has: both brought up to date first.
            CaptureDaemon.syncGreeting(app)
            CaptureDaemon.setAnswering(app)
            val reply = CaptureDaemon.armTest(app, incoming, number)
            if (reply != "TEST armed") {
                say(
                    when (reply) {
                        "TEST bad-number" -> "that number cannot be dialled"
                        "TEST emergency" -> "an emergency number is never called for a test"
                        "TEST no-greeting" -> "the recorder does not have your greeting yet - try again in a moment"
                        "TEST busy" -> "the last test call is still going on"
                        else -> "the recorder could not arm a test call"
                    },
                )
                return@launch
            }
            // The recorder's own windows: three minutes for a caller, and
            // fifteen seconds for the call it dials to go out.
            val window = if (incoming) 180_000L else 15_000L
            _state.update {
                it.copy(test = "armed", testIncoming = incoming, testUntil = System.currentTimeMillis() + window, askHeard = null)
            }
            watchTest(window)
        }
    }

    private fun watchTest(window: Long) {
        testWatch?.cancel()
        testWatch = scope.launch {
            val until = System.currentTimeMillis() + window + 120_000L
            while (System.currentTimeMillis() < until) {
                delay(2_000)
                val result = CaptureDaemon.testResult(app) ?: continue
                when (result["state"]) {
                    "running" -> _state.update { it.copy(test = "running") }
                    "done" -> {
                        finishTest(result)
                        return@launch
                    }
                }
                // A call that rang in the window's last moment is answered
                // three seconds on, and is "running" once its audio is up.
                if (result["state"] == "armed" && System.currentTimeMillis() > _state.value.testUntil + ANSWER_GRACE_MS) break
            }
            _state.update { it.copy(test = null) }
            say("no test call came")
        }
    }

    private fun finishTest(result: Map<String, String>) {
        val type = AnswerDiagnostics.callType(result["voice"]?.toIntOrNull() ?: -1)
        val verdict = AnswerDiagnostics.verdict(result)
        Log.i(TAG, "answer: test on $type -> $verdict ($result)")
        AnswerDiagnostics.record(app, type, verdict)
        if (verdict == AnswerDiagnostics.Verdict.FAILS) {
            // A microphone that would not mute is this phone's, whichever way
            // the greeting goes: no reason to try the other.
            if (result["mute"] == "0") {
                say("the microphone did not stay muted, so the caller would hear the room")
            } else {
                tryTheOtherRoute(type)
            }
        }
        _state.update {
            it.copy(
                test = null,
                results = AnswerDiagnostics.results(app),
                // Only someone who called can say. An outgoing test reached a
                // machine; its verdict is the measurements'.
                askHeard = if (it.testIncoming && verdict != AnswerDiagnostics.Verdict.FAILS) type else null,
            )
        }
    }

    /** Stage three: what the caller heard. */
    fun heard(type: String, heard: Boolean) {
        AnswerDiagnostics.record(app, type, if (heard) AnswerDiagnostics.Verdict.WORKS else AnswerDiagnostics.Verdict.FAILS)
        // Unheard is as failed as measured wrong.
        if (!heard) tryTheOtherRoute(type)
        _state.update { it.copy(askHeard = null, results = AnswerDiagnostics.results(app)) }
    }

    /**
     * The route just failed on a [type] of call is not the only one: try the
     * other next time - unless this one passed on another call type, which
     * one route for every call would break; and back again if that fails
     * too. On the Honor the screening route reached nobody
     * (docs/ARCHITECTURE.md).
     */
    private fun tryTheOtherRoute(type: String) {
        val route = AnsweringMachine.route(app)
        val passedElsewhere = AnswerDiagnostics.results(app).any { (other, v) ->
            other != type && (v == AnswerDiagnostics.Verdict.WORKS || v == AnswerDiagnostics.Verdict.LIKELY)
        }
        val stage = _state.value.stageOne
        val other = when {
            passedElsewhere -> null
            route == AnsweringMachine.ROUTE_MUSIC && stage?.screening == true -> AnsweringMachine.ROUTE_SCREENING
            // Back as well where the checks could not read the audio setup:
            // no "no" to in-call music, as for the switch (StageOne.possible).
            route == AnsweringMachine.ROUTE_SCREENING && (stage?.inCallMusic == true || stage?.audioUnread == true) ->
                AnsweringMachine.ROUTE_MUSIC
            else -> null
        } ?: return
        AnsweringMachine.setRoute(app, other)
        push()
        say("the greeting did not reach the call; the next test tries the other way")
    }

    fun dismissHeard() = _state.update { it.copy(askHeard = null) }

    /** The carrier's own voicemail timer - slow: the network is asked. */
    fun checkCarrier() {
        if (_state.value.checkingCarrier) return
        _state.update { it.copy(checkingCarrier = true) }
        scope.launch {
            val answer = CaptureDaemon.forwarding(app)
            _state.update { it.copy(checkingCarrier = false, carrier = answer ?: "error") }
        }
    }

    /** Keep the carrier's voicemail, after 30 s - on the user's tap only. */
    fun carrierWaitLonger() {
        if (_state.value.checkingCarrier) return
        _state.update { it.copy(checkingCarrier = true) }
        scope.launch {
            val answer = CaptureDaemon.setForwarding(app, 30)
            _state.update {
                it.copy(checkingCarrier = false, carrier = answer?.removePrefix("set ")?.removePrefix("unchanged ") ?: "error")
            }
            if (answer?.startsWith("set ") != true) say("the carrier did not take the change: $answer")
        }
    }

    fun copyReport() {
        val text = AnswerDiagnostics.report(
            AnswerDiagnostics.rawStageOne(app), AnswerDiagnostics.results(app), AnsweringMachine.route(app),
            appVersion(app),
        )
        reportToClipboard(app, "JemRec answering machine report", text)
    }

    private companion object {
        const val TAG = "JemRec"
        /** The recorder's three seconds to answer a test, its wait of up to
         *  eight for the call's audio, and a poll. */
        const val ANSWER_GRACE_MS = 15_000L
    }
}
