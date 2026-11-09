package com.jemcik.jemrec.ui

import android.app.Application
import android.media.AudioTrack
import android.util.Log
import com.jemcik.jemrec.answering.AnswerConditions
import com.jemcik.jemrec.answering.AnswerDiagnostics
import com.jemcik.jemrec.answering.AnsweringMachine
import com.jemcik.jemrec.answering.EmergencyPause
import com.jemcik.jemrec.answering.Greeting
import com.jemcik.jemrec.calls.CallTracking
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.recorder.DaemonClient.TestArming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A test call being armed, by any screen - one whose arming outlived it
 *  included: one at a time. */
internal val testArming = java.util.concurrent.atomic.AtomicBoolean(false)

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
    /** A take stopped, and being fetched, made a greeting of and saved. */
    val processingTake: Boolean = false,
    /** The recorder's microphone may be open for a take: from Record until
     *  it has answered Stop, or the start has failed. */
    val takeListening: Boolean = false,
    val takeStartedAt: Long = 0L,
    val playing: Boolean = false,
    val greetingProblem: String? = null,
    val callTracking: Boolean = true,
    /** The no-call checks: null until run once. */
    val stageOne: AnswerDiagnostics.StageOne? = null,
    val checking: Boolean = false,
    val results: Map<String, AnswerDiagnostics.Verdict> = emptyMap(),
    /** A test call: "arming" (the recorder asked), "armed", "running" or
     *  null; and until when it is armed. */
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
    /** The greeting's player, made for each preview: a test counts them. */
    private val previewOf: (ByteArray) -> AudioTrack? = Greeting::preview,
    /** A snackbar as well as [say]'s line in Diagnostics' log: for what
     *  answers a tap on this screen, and - while this screen is shown
     *  ([shown]) - for a test call's settled ending (no call came, the
     *  recorder restarted, the verdict's words) and a setting that did not
     *  reach the recorder. The rest - the checks run on opening, a test kept
     *  for the next opening ("still going on", "could not be asked"), a take
     *  let go as the screen was left - is the log's alone, as every word
     *  here was until 0.30. */
    private val tell: (String) -> Unit = say,
    /** This screen is the one shown: a test call's ending is told then,
     *  and only logged over another. */
    private val shown: () -> Boolean = { true },
) {
    private val _state = MutableStateFlow(initial ?: AnswerUi())
    val state: StateFlow<AnswerUi> = _state.asStateFlow()

    private var take: Job? = null
    private var preview: AudioTrack? = null
    private var testWatch: Job? = null
    private var previewing: Job? = null
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

    /** The screen opened: the checks, if never run, and the SIMs - and a
     *  test call armed before, watched on to its end. */
    fun opened() {
        if (seeded) return
        reload()
        if (_state.value.stageOne == null) runChecks(asked = false)
        readPendingTest()
        scope.launch {
            CaptureDaemon.sims(app)?.let { sims ->
                AnswerConditions.remember(app, sims)
                _state.update { it.copy(sims = sims.all, defaultSim = sims.defaultId) }
            }
        }
    }

    /**
     * Where a test call kept past its screen stands, read as the screen opens
     * and each time it comes back to the front. A test from a screen that has
     * gone since - JemRec closed from Recents - is this one's now: the
     * recorder answers the call all the same, and only a screen reads how it
     * went (the verdict, the other route on a failure, "Did they hear it?").
     * Each went with the screen that armed it. Read again at the front: a
     * test kept when its watch ended unanswered, or still under way, is
     * asked about again.
     */
    fun readPendingTest() {
        // While this screen arms a test, the arming's answer says what it is:
        // an older test still kept would be taken up as this one's, and its
        // watch would end the new arming's state.
        if (seeded || _state.value.test == "arming") return
        when (val pending = AnswerDiagnostics.pendingTest(app)) {
            is AnswerDiagnostics.PendingTest.Armed -> if (testWatch?.isActive != true) {
                _state.update { it.copy(test = "armed", testIncoming = pending.incoming, testUntil = pending.until, askHeard = null) }
                watchTest(pending.until - System.currentTimeMillis())
            }
            is AnswerDiagnostics.PendingTest.Asking -> _state.update { it.copy(askHeard = pending.callType) }
            // Answered, or put aside, on another screen meanwhile.
            null -> _state.update { it.copy(askHeard = null) }
        }
    }

    private fun push() {
        scope.launch {
            if (!CaptureDaemon.setAnswering(app)) {
                told("the recorder will pick up the answering machine when it next restarts")
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
        if (take?.isActive == true || _state.value.processingTake) return
        stopPreview()
        quiet()
        _state.update { it.copy(takeListening = true) }
        take = scope.launch {
            if (!CaptureDaemon.startTake(app)) {
                _state.update {
                    it.copy(takeListening = false, greetingProblem = "The recorder could not start recording. Is it running?")
                }
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
        // Record and Delete are greyed meanwhile: a Delete then was undone
        // when the new greeting was saved, the machine left off.
        _state.update { it.copy(processingTake = true) }
        take = scope.launch {
            try {
                processTake()
            } finally {
                _state.update { it.copy(processingTake = false) }
            }
        }
    }

    /** The stopped take fetched from the recorder, made a greeting of, saved
     *  and sent back. */
    private suspend fun processTake() {
        // Answered, the microphone is shut, and a preview cannot reach the
        // take; not answered, the recorder may record on to its own limit.
        if (CaptureDaemon.stopTake(app) != null) {
            _state.update { it.copy(takeListening = false) }
        } else {
            scope.launch { untilTheRecorderStopsByItself() }
        }
        val pcm = CaptureDaemon.fetchTake(app)
        val processed = withContext(Dispatchers.Default) { pcm?.let(Greeting::process) }
        when (processed) {
            is Greeting.Take.Usable -> {
                // A full disk, or a rename refused: said on the row, where a
                // throw here ended the app.
                val saved = withContext(Dispatchers.IO) { runCatching { Greeting.save(app, processed.pcm) } }
                if (saved.isFailure) {
                    Log.w(TAG, "answer: the greeting could not be saved", saved.exceptionOrNull())
                    _state.update { it.copy(greetingProblem = "The greeting could not be saved. Is the phone's storage full?") }
                    return
                }
                // A preview of the greeting this replaces, played while it
                // was made, ends with it: the row now tells of the new one.
                stopPreview()
                val sent = CaptureDaemon.uploadGreeting(app, processed.pcm)
                if (!sent) tell("the recorder will get the new greeting when it next restarts")
                _state.update { it.copy(greetingSeconds = processed.seconds, greetingProblem = null) }
                push()
            }
            // Not sent at all - the recorder restarted, a socket cut: no
            // word of the voice, which "Nothing was heard" blamed.
            null -> _state.update {
                it.copy(greetingProblem = "The recorder did not send the take. Try again.")
            }
            Greeting.Take.Silent -> _state.update {
                it.copy(greetingProblem = "Nothing was heard. Hold the phone as for a call, and speak.")
            }
            Greeting.Take.TooShort -> _state.update {
                it.copy(greetingProblem = "That was too short for a greeting. Try again.")
            }
        }
    }

    /** A take's microphone held until the recorder's own limit - thirty
     *  seconds from its start - when Stop went unanswered. */
    private suspend fun untilTheRecorderStopsByItself() {
        val limit = _state.value.takeStartedAt + Greeting.MAX_SECONDS * 1000L + 1_000L
        delay((limit - System.currentTimeMillis()).coerceAtLeast(0))
        _state.update { it.copy(takeListening = false) }
    }

    /** The take stopped on the recorder from outside this screen's scope -
     *  the screen gone, or left - and the microphone held until it answers,
     *  or its own limit. */
    private fun stopOnTheRecorder() {
        outliving.launch {
            if (runCatching { CaptureDaemon.stopTake(app) }.getOrNull() != null) {
                _state.update { it.copy(takeListening = false) }
            } else {
                untilTheRecorderStopsByItself()
            }
        }
    }

    /**
     * The screen left - Back, a link to another: the greeting's preview
     * stops, and a take still being started or recorded is stopped on the
     * recorder and not kept. It went on unseen, and over a call played on
     * the loudspeaker meanwhile, saved that call as the greeting every caller
     * hears. A take stopped and being made a greeting of finishes.
     */
    fun left() {
        stopPreview()
        if (!_state.value.takeListening || _state.value.processingTake) return
        take?.cancel()
        _state.update { it.copy(recordingTake = false) }
        stopOnTheRecorder()
        say("the greeting's take was stopped as its screen was left, and not kept")
    }

    fun togglePreview() {
        if (_state.value.playing) {
            stopPreview()
            return
        }
        // One being started - its file still being read - is the one: a
        // second was made (the app's tests) and would play on under it,
        // never let go. Nor while the recorder's microphone may be open for a
        // take - asked to start, recording, or stopped and not yet answered:
        // over the loudspeaker the greeting would go into the new one. Once
        // it has answered Stop, a preview plays the greeting there is while
        // the take is made a greeting of.
        if (previewing?.isActive == true || _state.value.takeListening) return
        quiet()
        previewing = scope.launch {
            // Read where it may be gone: deleted just then, it threw (the
            // app's tests), and nothing caught it.
            val pcm = withContext(Dispatchers.IO) {
                runCatching { Greeting.file(app).takeIf { it.isFile }?.readBytes() }.getOrNull()
            } ?: return@launch
            val track = previewOf(pcm) ?: return@launch
            // Stopped while its player was made - which only a scope off the
            // main thread allows, as the tests' is: not started.
            if (!isActive) {
                runCatching { track.stop(); track.release() }
                return@launch
            }
            preview = track
            _state.update { it.copy(playing = true) }
            delay((pcm.size / 2.0 / Greeting.RATE * 1000).toLong() + 300)
            if (preview === track) stopPreview()
        }
    }

    /**
     * The screen is gone for good: the greeting's preview stops and its
     * player is let go, and a take under way is stopped on the recorder.
     * Closed from Recents mid-preview, the preview played on to its end - the
     * wait that stops it went with the screen - and was never released;
     * mid-take, the recorder went on recording the microphone for up to
     * thirty seconds, and refused a new take until then. The take is told to
     * stop in a scope of its own, as this screen's has ended by now, and is
     * not kept: recording, asked to start, or Stop tapped and not yet
     * answered - a stop not yet sent went with the screen. One sent before
     * the recorder's yes to Record can reach it before the start, and the
     * take then runs to its own limit.
     */
    fun close() {
        stopPreview()
        if (_state.value.takeListening) stopOnTheRecorder()
    }

    private fun stopPreview() {
        // The preview's wait as well, and one still being started: left, it
        // held every tap off until its length was out, and a take could
        // start under it.
        previewing?.cancel()
        previewing = null
        preview?.let { runCatching { it.stop(); it.release() } }
        preview = null
        _state.update { it.copy(playing = false) }
    }

    fun deleteGreeting() {
        if (_state.value.processingTake || _state.value.takeListening) return
        stopPreview()
        Greeting.delete(app)
        AnsweringMachine.setEnabled(app, false)
        _state.update { it.copy(greetingSeconds = null, enabled = false) }
        scope.launch {
            val dropped = CaptureDaemon.syncGreeting(app)
            val off = CaptureDaemon.setAnswering(app)
            // Missed, the recorder answered calls with the deleted greeting,
            // without a word, until it next took JemRec's settings - at its
            // next start (Revive's pushMode).
            if (!dropped || !off) tell("the recorder will drop the greeting when it next restarts")
        }
    }

    /** Stage one: the no-call checks, on the recorder - [asked] by a tap on
     *  their row, or run as the screen opens with none run yet. */
    fun runChecks(asked: Boolean = true) {
        if (_state.value.checking) return
        _state.update { it.copy(checking = true) }
        scope.launch {
            val fields = CaptureDaemon.stageOne(app)
            if (fields == null) {
                // Told only when asked: run on each opening (and each turn of
                // the phone, which opens the screen again), it was a snackbar
                // every time while the recorder was down.
                (if (asked) tell else say)("the answering machine's checks need the recorder running")
            } else {
                AnswerDiagnostics.rememberStageOne(app, fields)
                val stage = AnswerDiagnostics.stageOne(fields)
                AnswerDiagnostics.routeAfterChecks(stage, AnsweringMachine.route(app))?.let {
                    AnsweringMachine.setRoute(app, it)
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
        // One at a time: the row takes no tap while a test is on, and a tap
        // that came as one began arms nothing more.
        if (_state.value.test != null) return
        // A test answers whoever calls in its three minutes: never in the day
        // after an emergency call, when that may be them calling back. Nor is
        // an emergency number ever called for one (the screen says so too).
        if (incoming && EmergencyPause.pausedUntil(app) != null) {
            tell("no test call for a day after an emergency call - unless you answer again now")
            return
        }
        if (!incoming && EmergencyPause.isEmergencyNumber(app, number)) {
            tell("an emergency number is never called for a test")
            return
        }
        // One arming at a time, whichever screen makes it - JemRec swiped
        // away and opened again while the recorder armed, say: the entry
        // keeps whichever answer came last, and the recorder whichever
        // arming reached it last, so two at once would be filed under each
        // other.
        if (!testArming.compareAndSet(false, true)) {
            tell("a test call is being armed already")
            return
        }
        // A test from before still to be read is read first. Armed by the
        // recorder and not answered, the new one would leave the old entry
        // to be read with its result (the recorder's result names no
        // arming).
        if (AnswerDiagnostics.pendingTest(app) is AnswerDiagnostics.PendingTest.Armed) {
            testArming.set(false)
            tell("the last test call is read first; start the next one when it is")
            readPendingTest()
            return
        }
        // Shown at once: arming takes the recorder a moment - an outgoing
        // test's call goes out before it answers - and the greeting deleted
        // meanwhile would leave the test nothing to play.
        _state.update { it.copy(test = "arming", testIncoming = incoming) }
        // Armed, and noted, in the background, not in the screen's scope:
        // with the screen closed meanwhile (from Recents) the recorder arms
        // all the same and answers the caller, and the next screen reads the
        // test only if it was noted.
        val armed = outliving.async {
            try {
                // The test plays the greeting the recorder has, on the route
                // the recorder has: both brought up to date first.
                CaptureDaemon.syncGreeting(app)
                CaptureDaemon.setAnswering(app)
                val reply = CaptureDaemon.armTest(app, incoming, number)
                // The recorder's own windows: three minutes for a caller, and
                // fifteen seconds for the call it dials to go out.
                val window = if (incoming) 180_000L else 15_000L
                val until = System.currentTimeMillis() + window
                if (reply == TestArming.ARMED) AnswerDiagnostics.noteTestArmed(app, incoming, until)
                Triple(reply, window, until)
            } finally {
                testArming.set(false)
            }
        }
        scope.launch {
            val (reply, window, until) = armed.await()
            if (reply != TestArming.ARMED) {
                _state.update { it.copy(test = null) }
                tell(
                    when (reply) {
                        TestArming.BAD_NUMBER -> "that number cannot be dialled"
                        TestArming.EMERGENCY -> "an emergency number is never called for a test"
                        TestArming.NO_GREETING -> "the recorder does not have your greeting yet - try again in a moment"
                        TestArming.BUSY -> "the last test call is still going on"
                        // It may have armed all the same: told, not watched
                        // (DaemonClient.armTest).
                        TestArming.UNANSWERED -> if (incoming) {
                            "the recorder did not answer, and may have armed the test all the same: " +
                                "a call in the next three minutes may be answered with your greeting, unread"
                        } else {
                            "the recorder did not answer, and may be placing the call all the same; how it goes is not read"
                        }
                        else -> "the recorder could not arm a test call"
                    },
                )
                return@launch
            }
            _state.update {
                it.copy(test = "armed", testIncoming = incoming, testUntil = until, askHeard = null)
            }
            watchTest(window)
        }
    }

    /**
     * The recorder asked how the test goes, at once and then every two
     * seconds - at least once, however late: a screen opened after the
     * window, the call long over, still finds the result the recorder keeps
     * until the next test. The test is done with on the recorder's word
     * only: its result, a recorder started since, or the window out with
     * no call. Anything else as the watch ends - the recorder not to be
     * asked, or the call still under way - keeps it for the next opening.
     */
    private fun watchTest(window: Long) {
        testWatch?.cancel()
        testWatch = scope.launch {
            val until = System.currentTimeMillis() + window + 120_000L
            var ending: String? = null
            // The state the last ask was answered with; null, not answered.
            var last: String? = null
            while (true) {
                val result = CaptureDaemon.testResult(app)
                last = result?.get("state")
                when (last) {
                    "running" -> _state.update { it.copy(test = "running") }
                    "done" -> {
                        finishTest(result!!)
                        return@launch
                    }
                    // A recorder started since keeps no test: nothing will
                    // come, and it is said now, not when the window is out.
                    "none" -> {
                        ending = "the recorder restarted, so no test call is armed now"
                        break
                    }
                    // A call that rang in the window's last moment is
                    // answered three seconds on, and is "running" once its
                    // audio is up.
                    "armed" -> if (System.currentTimeMillis() > _state.value.testUntil + ANSWER_GRACE_MS) {
                        ending = "no test call came"
                        break
                    }
                }
                if (System.currentTimeMillis() >= until) break
                delay(2_000)
            }
            // Taken meanwhile elsewhere: what was left is this screen's to
            // show, not this watch's ending (followOther).
            val ours = (AnswerDiagnostics.pendingTest(app) as? AnswerDiagnostics.PendingTest.Armed)?.until ==
                _state.value.testUntil
            when {
                !ours -> if (followOther()) return@launch
                ending != null -> if (AnswerDiagnostics.takeArmedTest(app, _state.value.testUntil, asking = null)) {
                    told(ending)
                } else if (followOther()) {
                    return@launch
                }
                // Still under way: its result comes when it ends.
                last == "running" -> say("the test call is still going on; how it went is read when this screen next opens")
                // The recorder not to be asked - down, say: the same one,
                // up again, still holds the result.
                else -> say("the recorder could not be asked how the test call went; it is asked again when this screen next opens")
            }
            _state.update { it.copy(test = null) }
        }
    }

    private fun finishTest(result: Map<String, String>) {
        val type = AnswerDiagnostics.callType(result["voice"]?.toIntOrNull() ?: -1)
        val verdict = AnswerDiagnostics.verdict(result)
        // Only someone who called can say. An outgoing test reached a
        // machine; its verdict is the measurements'.
        val asking = if (_state.value.testIncoming && verdict != AnswerDiagnostics.Verdict.FAILS) type else null
        // Taken elsewhere first: shown here, not acted on twice
        // (followOther).
        if (!AnswerDiagnostics.takeArmedTest(app, _state.value.testUntil, asking)) {
            followOther()
            return
        }
        Log.i(TAG, "answer: test on $type -> $verdict ($result)")
        AnswerDiagnostics.record(app, type, verdict)
        if (verdict == AnswerDiagnostics.Verdict.FAILS) {
            // A microphone that would not mute is this phone's, whichever way
            // the greeting goes: no reason to try the other.
            if (result["mute"] == "0") {
                told("the microphone did not stay muted, so the caller would hear the room")
            } else {
                tryTheOtherRoute(type, ::told)
            }
        }
        _state.update { it.copy(test = null, results = AnswerDiagnostics.results(app), askHeard = asking) }
    }

    /**
     * The test this screen watched, taken elsewhere: what was left is shown
     * here, as an opening would show it - the results put down, the question
     * if one is asked, and a newer test armed, watched on. True for a newer
     * test: called from the watch, which the new watch replaces, so the
     * caller returns. Since 0.30 JemRec's own ways in bring its one window
     * forward, so a second screen is open only if the tile's long press
     * started one over a picker; until then the tile, the media notification
     * and a notification's tap each could.
     */
    private fun followOther(): Boolean {
        val pending = AnswerDiagnostics.pendingTest(app)
        val results = AnswerDiagnostics.results(app)
        if (pending is AnswerDiagnostics.PendingTest.Armed && pending.until != _state.value.testUntil) {
            _state.update {
                it.copy(test = "armed", testIncoming = pending.incoming, testUntil = pending.until, askHeard = null, results = results)
            }
            watchTest(pending.until - System.currentTimeMillis())
            return true
        }
        _state.update {
            it.copy(test = null, results = results, askHeard = (pending as? AnswerDiagnostics.PendingTest.Asking)?.callType)
        }
        return false
    }

    /** A test call's ending: told on this screen, only logged over another
     *  (a watch runs on while the user is elsewhere in JemRec). */
    private fun told(text: String) = if (shown()) tell(text) else say(text)

    /** Stage three: what the caller heard. */
    fun heard(type: String, heard: Boolean) {
        // Answered on another screen already: that answer stands.
        if (AnswerDiagnostics.takeQuestion(app, type)) {
            AnswerDiagnostics.record(app, type, if (heard) AnswerDiagnostics.Verdict.WORKS else AnswerDiagnostics.Verdict.FAILS)
            // Unheard is as failed as measured wrong.
            if (!heard) tryTheOtherRoute(type, tell)
        }
        _state.update { it.copy(askHeard = null, results = AnswerDiagnostics.results(app)) }
    }

    /** The route just failed on a [type] of call: the other one next time,
     *  where AnswerDiagnostics.routeAfterFailure says so. */
    private fun tryTheOtherRoute(type: String, speak: (String) -> Unit) {
        val other = AnswerDiagnostics.routeAfterFailure(
            AnsweringMachine.route(app), _state.value.stageOne, AnswerDiagnostics.results(app), type,
        ) ?: return
        AnsweringMachine.setRoute(app, other)
        push()
        speak("the greeting did not reach the call; the next test tries the other way")
    }

    /** The question put aside: not asked again. */
    fun dismissHeard() {
        _state.value.askHeard?.let { AnswerDiagnostics.takeQuestion(app, it) }
        _state.update { it.copy(askHeard = null) }
    }

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
            carrierRefusal(answer)?.let(tell)
        }
    }

    /** The carrier's answer to Wait 30 seconds, in words; null when it took.
     *  Until 0.30 the recorder's own words went to the log: "null", "error
     *  3", "unchanged off 0". */
    internal fun carrierRefusal(answer: String?): String? {
        val code = answer?.removePrefix("error ")?.toIntOrNull()
        return when {
            // The app's own wait ran out, or no recorder: the change may have
            // gone through all the same.
            answer == null -> "no answer came from the recorder; ask your carrier again to see if it took"
            answer.startsWith("set ") -> null
            answer.startsWith("unchanged off") -> "your carrier's voicemail is off, so there was nothing to change"
            answer.startsWith("unchanged error") -> "the phone's forwarding could not be read, so nothing was changed"
            answer.startsWith("unchanged") -> "your carrier did not say when its voicemail answers, so nothing was changed"
            answer == "error timeout" -> "no answer came in time"
            // TelephonyManager.CallForwardingInfoCallback's results, read from
            // Android 16's framework classes (2026-11-08): 2 the fixed dialing
            // numbers' check, 3 not supported, 1 no reason given.
            code == 2 -> "the phone's fixed dialing numbers did not allow the change"
            code == 3 -> "this phone or carrier does not take this change"
            code != null -> "the change did not go through, and no reason was given"
            // Not a result of the call's at all: it could not be made - an
            // exception through the recorder's reflection, no telephony.
            else -> "the recorder could not make the change on this phone"
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
        /** For what has to reach the recorder after the screen is gone. */
        val outliving = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        const val TAG = "JemRec"
        /** The recorder's three seconds to answer a test, its wait of up to
         *  eight for the call's audio, and a poll. */
        const val ANSWER_GRACE_MS = 15_000L
    }
}
