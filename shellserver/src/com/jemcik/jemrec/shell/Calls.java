package com.jemcik.jemrec.shell;

import com.genymobile.scrcpy.util.Ln;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The call and recording state, and what is done about a call as it starts
 * and ends - the rules being CallDecisions'. One lock for all of it (LOCK).
 */
final class Calls {

    private Calls() {
    }

    /**
     * The one lock of the call and recording state. Its holders: a call's
     * start and end, a recording begun and stopped, a message split off,
     * 'S', 'K' and LIST, a quit's and debugging off's look at busy, and the
     * debugging-off wait. It was Main.class, which Main's synchronized
     * methods held too; every holder takes this one. (The refusals' log held
     * it too, until it was given its own: LoopbackServer.refuse.)
     */
    static final Object LOCK = new Object();

    /**
     * Mid-call - recording, ringing with an answer on its way, or waiting on
     * an ask-first answer - or a file being fetched: what a QUIT, or
     * debugging off, waits out. A fetch cut short by the exit reads as a
     * complete, shorter recording. Call with LOCK held.
     */
    static boolean busyLocked() {
        AnswerMachine machine = Answering.answerMachine;
        return CallDecisions.busy(activeRecording != null, callActive, fetches.get(),
                machine == null ? null : machine.state());
    }

    /** The mode in force: one of CallDecisions.MODE_*, OFF until told
     *  otherwise (see there). */
    static volatile int recordingMode = CallDecisions.MODE_OFF;

    /** The call being recorded right now, or null between calls. */
    static volatile @Nullable RecordingSession activeRecording;

    /** Whether a call is up, and its direction - so an on-demand yes that
     *  arrives mid-call knows what it is starting. */
    static volatile boolean callActive;
    /** Calls begun, and the latest of them that has ended: a CALL_STARTED
     *  still being retried for a call already over is dropped (see Wakes.wake). */
    static volatile long callSeq;
    static volatile long endedSeq;
    static volatile boolean callIncoming;

    /** The messenger app of the call in progress; null for a phone call or none. */
    static volatile @Nullable String callVoipApp;
    /** Which way that app's call went, where Telecom said (Google Meet's);
     *  null where it did not, or for a phone call. */
    static volatile @Nullable Boolean callAppIncoming;

    static volatile long skipUntil;

    /** When the recording in progress captured its first sample, on the
     *  answering machine's clock (SystemClock.elapsedRealtime): the
     *  message's start is counted from here, as the app counts packets. */
    static volatile long captureStartedAt;

    /** Start capturing the current call to a file. Returns its name, or null if
     *  it could not start or one is already running. Does NOT wake the app. */
    static @Nullable String beginRecording(boolean incoming, @Nullable String app,
            @Nullable Boolean appIncoming) {
        synchronized (LOCK) {
            if (activeRecording != null) {
                Ln.w("recording: a call is already being recorded");
                return activeRecording.name;
            }
            try {
                captureStartedAt = 0;
                activeRecording = RecordingSession.start(incoming, app, appIncoming);
            } catch (Throwable t) {
                Ln.e("recording: could not start", t);
                activeRecording = null;
                return null;
            }
            return activeRecording.name;
        }
    }

    /** Off-hook handling, by mode: automatic records at once; on-demand asks
     *  first and records nothing until the yes comes back as Wire.COMMAND_START. */
    static void onCallStart(boolean incoming) {
        onCallStart(incoming, null, null);
    }

    /**
     * A call starting - a phone call ([app] null), or a messenger call in
     * [app]. One at a time: a phone call during a messenger call, or the other
     * way round, leaves the first one's recording running and is not recorded
     * itself, and neither one's end can stop the other's recording.
     * [appIncoming]: which way an app's call went, where Telecom said.
     */
    static void onCallStart(boolean incoming, @Nullable String app, @Nullable Boolean appIncoming) {
        synchronized (LOCK) {
            AnswerMachine machine = Answering.answerMachine;
            CallDecisions.Start start = CallDecisions.start(callActive, app != null, callVoipApp != null,
                    machine != null && machine.takingMessage(),
                    CallDecisions.skipOpen(skipUntil, System.currentTimeMillis()));
            if (start == CallDecisions.Start.IGNORED) {
                Ln.w("recording: a " + (app != null ? "messenger" : "phone") + " call started during "
                        + (callVoipApp != null ? "a messenger call" : "a phone call") + "; not recording it");
                return;
            }
            if (!callActive) {
                callSeq++;
            }
            callActive = true;
            callIncoming = incoming;
            callVoipApp = app;
            callAppIncoming = app != null ? appIncoming : null;
            if (start == CallDecisions.Start.MESSAGE) {
                // A call this process answered: the recording IS the message,
                // whatever the mode - switching the answering machine on is
                // the consent to record one. No start-of-call wake: the
                // message's own (MESSAGE_STARTED) follows with the greeting.
                // Not once the user took the call: an off-hook then - a
                // waiting call - is theirs, asked about as in any call.
                beginRecording(incoming, null, null);
                return;
            }
            if (start == CallDecisions.Start.SKIPPED) {
                skipUntil = 0;
                Ln.i("recording: the rules leave this call out; not recording it");
                return;
            }
        }
        RecordingSession running = activeRecording;
        switch (CallDecisions.byMode(recordingMode, running != null)) {
            case RECORD:
                String name = beginRecording(incoming, app, appIncoming);
                if (name != null) {
                    // The app shows a "recording" notification; no audio needed.
                    Wakes.wake(Wakes.ACTION_CALL_STARTED, incoming, name, app);
                }
                break;
            case RECORDING_ALREADY:
                // Already said yes to, and recording: a second off-hook
                // in the same call (call waiting) is not a new question -
                // asked again, "Not now" would stop nothing. (It runs: the
                // decision is this only while one does.)
                if (running != null) {
                    Wakes.wake(Wakes.ACTION_CALL_STARTED, incoming, running.name, app);
                }
                break;
            case ASK:
                // Nothing is captured yet. Ask; an empty rec-file says "prompt".
                Ln.i("recording: on-demand, asking before recording");
                Wakes.wake(Wakes.ACTION_CALL_STARTED, incoming, "", app);
                break;
            default:
                Ln.i("recording: switched off, not recording this call");
                break;
        }
    }

    /**
     * Ask first, and the user took over a call the answering machine answered:
     * the recording so far is the message - kept as one, or dropped with no
     * word in it - and nothing more is recorded unless the user says so at the
     * Record prompt this puts up. A recording started there is the user's
     * own: at the call's end it is named as taken, from before its first
     * sample (Checks.takenRecfile: "_from0"), and kept whole.
     */
    static void splitOffMessage(AnswerMachine.Outcome part) {
        synchronized (LOCK) {
            RecordingSession rec = activeRecording;
            CallDecisions.Split split = CallDecisions.split(callActive, rec != null, part.speechAt != 0);
            if (split == CallDecisions.Split.NOTHING) {
                // The call ended as it was taken: its end has named it.
                Ln.i("answer: the call was taken as it ended; nothing to split");
                return;
            }
            if (rec == null) {
                // NO_RECORDING
                Ln.w("answer: the call was taken; there was no recording of it to split");
            } else {
                rec.stop();
                File file = new File(Paths.REC_DIR, rec.name);
                if (split == CallDecisions.Split.DROP) {
                    Ln.i("answer: the call was taken before a word was left; " + rec.name + " dropped -> "
                            + file.delete());
                } else {
                    String renamed = Checks.messageRecfile(rec.name, part.messageStart(), captureStartedAt, false,
                            false);
                    if (file.renameTo(new File(Paths.REC_DIR, renamed))) {
                        Ln.i("answer: the call was taken; the message so far -> " + renamed);
                    } else {
                        Ln.w("answer: the call was taken; could not name " + rec.name + " as a message");
                    }
                }
                activeRecording = null;
            }
        }
        // Even with nothing recorded - a recording that could not start: the
        // prompt is what tells the app the message is over.
        Wakes.wake(Wakes.ACTION_CALL_STARTED, callIncoming, "", null);
    }

    private static void stopRecording(@Nullable AnswerMachine.Outcome outcome) {
        synchronized (LOCK) {
            RecordingSession rec = activeRecording;
            if (rec == null) {
                return;
            }
            rec.stop();
            String name = rec.name;
            CallDecisions.Ending ending = CallDecisions.ending(outcome != null, outcome != null && outcome.test,
                    outcome != null && outcome.isMessage());
            if (ending == CallDecisions.Ending.TEST) {
                // A diagnostics test: what it showed is kept for 'X' 'R'; the
                // recording is not the user's, and goes. The app is told when the
                // call began, to account for it in the call log.
                boolean gone = new File(Paths.REC_DIR, name).delete();
                Ln.i("answer: test call over, its recording deleted -> " + gone);
                activeRecording = null;
                Wakes.wake(Wakes.ACTION_CALL_ENDED, rec.incoming, "", null,
                        "--ez", "test", "true", "--el", "started", String.valueOf(rec.startedAt));
                return;
            }
            if (outcome != null) {
                // A message: named with where it starts, so whichever collector
                // takes it - the hang-up's, or a reconcile days later - trims it
                // the same way and knows it for a message. A call the user took
                // over: theirs, an ordinary recording, from when they took it -
                // or the caller's first words, if earlier; in Ask first, all of
                // what they said Record to (see splitOffMessage).
                boolean taken = ending == CallDecisions.Ending.TAKEN;
                String renamed = taken
                        ? Checks.takenRecfile(name, outcome.takenStart(), captureStartedAt)
                        : Checks.messageRecfile(name, outcome.messageStart(), captureStartedAt, outcome.nothingLeft(),
                                outcome.greetingFailed);
                if (new File(Paths.REC_DIR, name).renameTo(new File(Paths.REC_DIR, renamed))) {
                    name = renamed;
                } else {
                    Ln.w("answer: could not name " + name + (taken ? " with where the call was taken" : " as a message"));
                }
                Ln.i(taken ? "answer: the user took the call - " + name + " is an ordinary recording"
                        : "answer: message recorded -> " + name);
            }
            // ONLY NOW. LIST hides the active recording so the app never collects
            // a file that is still being written - and until stop() has returned,
            // it still is. Clearing this first, as it used to, opened a window in
            // which a list from the app's reconcile would have offered it a
            // truncated file, and a truncated file reads as a complete one.
            activeRecording = null;
            // Now the file is complete and at rest, tell the app to come and get it.
            Wakes.wake(Wakes.ACTION_CALL_ENDED, rec.incoming, name, rec.app);
        }
    }

    /** Call end handling. A recording that ran is finished and handed over; an
     *  on-demand call that was never said yes to just tells the app to clear its
     *  prompt, with nothing to fetch. */
    static void onCallEnd() {
        if (callVoipApp != null) {
            Ln.i("callwatch: a phone call ended during a messenger call; that recording carries on");
            return;
        }
        endCall();
        // The other way round from onVoipCallEnd: a messenger or Meet call
        // that began during the phone call - taken as the phone call was hung
        // up - was turned away while that one ran; it is the call now.
        String app = Voip.followedApp();
        if (app != null) {
            Ln.i("recording: the phone call gave way to a call in " + app + "; recording that");
            onAppCallStart(app);
        }
    }

    /** A messenger or Meet call starting, and which way it went: known for an
     *  app whose calls go through Telecom - Meet's ring reaches the call
     *  watch - and for no other. */
    static void onAppCallStart(String app) {
        CallWatch watch = CallWatch.callWatch;
        Boolean incoming = watch != null ? watch.appCallIncoming() : null;
        onCallStart(Boolean.TRUE.equals(incoming), app, incoming);
    }

    /** A messenger call's end. Only ends a messenger call. */
    static void onVoipCallEnd() {
        if (callVoipApp == null) {
            return;
        }
        endCall();
        // A phone call answered during the messenger call - which is what
        // ended it - was turned away while that one ran; it is the call now.
        CallWatch watch = CallWatch.callWatch;
        if (watch != null && watch.phoneCallUp()) {
            Ln.i("recording: the messenger call gave way to a phone call; recording that");
            onCallStart(watch.phoneCallRang(), null, null);
        }
    }

    private static void endCall() {
        boolean wasRecording;
        String app;
        // The answering machine first: it gives the microphone back, and says
        // what became of the message. Only a phone call is ever its business.
        AnswerMachine machine = Answering.answerMachine;
        AnswerMachine.Outcome outcome = callVoipApp == null && machine != null ? machine.callEnded() : null;
        if (outcome == null) {
            // Not a call this process answered: a mute marker now is one a
            // predecessor left, kept while this call was up.
            Answering.giveBackStaleMute();
        }
        // Under the same lock as an on-demand start, so a Record tap landing as
        // the call ends either starts before the end - and is stopped here - or
        // finds the call over and is refused. Between the two, a recording
        // would start with nothing left to ever stop it: this end has already
        // happened, and the next one is a whole call away.
        synchronized (LOCK) {
            callActive = false;
            endedSeq = callSeq;
            app = callVoipApp;
            callVoipApp = null;
            callAppIncoming = null;
            wasRecording = activeRecording != null;
            if (wasRecording) {
                stopRecording(outcome);
            }
        }
        if (outcome != null && outcome.test) {
            // Recorded or not: its results are complete.
            AnswerTest.testState = "done";
        }
        if (CallDecisions.endWakes(wasRecording, recordingMode)) {
            Wakes.wake(Wakes.ACTION_CALL_ENDED, callIncoming, "", app);
        }
    }

    /** Fetches under way, which QUIT waits out. */
    static final AtomicInteger fetches = new AtomicInteger();

    /** The name of the file being recorded into, or null. */
    static @Nullable String activeName() {
        RecordingSession active = activeRecording;
        return active == null ? null : active.name;
    }
}
