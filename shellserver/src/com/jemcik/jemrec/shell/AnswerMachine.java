package com.jemcik.jemrec.shell;

/**
 * THE ANSWERING MACHINE: answer a call nobody took, play the user's greeting
 * to the caller with this phone's microphone muted, and let the recording run
 * as the message. Plain Java, nothing of Android, so it can be tested
 * (shellserver/tests: AnswerMachineTest); Main feeds it the call's events and
 * does what it says through {@link Effects}.
 *
 * THE APP DECIDES WHO, THIS DECIDES WHEN. The app's call tracker knows the
 * caller while the phone rings and applies the user's rules; it sends a
 * go-ahead with the delay. This process runs the timer, because the app can be
 * frozen while the dialer is in front, and a call is answered only on a
 * go-ahead for the ring in progress. No go-ahead, nothing is answered: the
 * failure mode is the carrier's voicemail, never a call answered against the
 * user's rules.
 *
 * Measured on an Honor (Snapdragon, Android 17), 2026-10-22, with a probe that
 * did these steps by hand: the shell's acceptRingingCall answers; a media
 * track aimed at the call's uplink plays as Qualcomm's in-call music, which the
 * caller hears; setMicrophoneMute keeps the room out without muting that
 * track; Telecom adopts the mute and shows it on the call screen, keeps it
 * through a switch to speaker and back, and clears it when the call ends.
 */
final class AnswerMachine {

    /** A go-ahead may land before the ring reaches this process; it holds
     *  this long. */
    static final long GO_AHEAD_EARLY_MS = 3_000L;
    /** After answering, how long to wait for Telecom to put the call's audio
     *  up, and how often to look. */
    static final long AUDIO_WAIT_MS = 8_000L;
    static final long AUDIO_POLL_MS = 50L;
    /** How often the mute is looked at during a message: an unmute from the
     *  call screen's own button means the user took the call. */
    static final long MUTE_WATCH_MS = 500L;
    /** A message starts this long before the caller's first words. */
    static final long PRE_ROLL_MS = 500L;
    /**
     * Words, not a blip: a run of sound - its loud moments no more than
     * RUN_GAP_MS apart - with at least SPEECH_MS of sound in it. On the Honor,
     * 2026-10-22, a sound as the call connected started a message there, and
     * the caller's ten silent seconds of listening to the greeting were kept.
     */
    static final long RUN_GAP_MS = 1_500L;
    static final long SPEECH_MS = 500L;
    /** Levels in the first moments after the mute may still be the room. */
    static final long SETTLE_MS = 300L;
    /** -45 dBFS, the level JemRec's silence check calls speech. */
    static final double SOUND_RMS = 32768 * Math.pow(10, -45 / 20.0);
    /** With no limit on the message, it ends after this long without a sound
     *  from the caller - a pocket dial or a line left open would otherwise
     *  hold the phone in the call for as long as it lasted. Looked at every
     *  SILENCE_CHECK_MS. */
    static final long SILENCE_HANGUP_MS = 30_000L;
    static final long SILENCE_CHECK_MS = 1_000L;
    /**
     * An idle from telephony that the call outlives by this long was not its
     * end: some dialers report one mid-call, and it stays (Google Phone - see
     * CallStates, where it was measured). A real end's audio mode lingers a
     * moment - a third of a second on the Honor, 2026-10-22 - or up to four
     * seconds for a busy or congestion tone, the longest AOSP's Telecom waits
     * for one (InCallController, read 2026-10-22), which this is past.
     */
    static final long FALSE_IDLE_MS = 6_000L;
    /** A test call's message part: long enough to say something. */
    static final long TEST_MESSAGE_MS = 20_000L;

    enum State {
        /** No call this machine is part of. */
        IDLE,
        /** A call is ringing; no go-ahead for it yet. */
        RINGING,
        /** Ringing, with a go-ahead: the answer timer runs. */
        ARMED,
        /** Answered; waiting for the call's audio. */
        ANSWERING,
        /** The greeting is playing. */
        GREETING,
        /** After the beep: the caller is leaving the message. */
        MESSAGE,
        /** The user took the call over; a normal call from here on. */
        TALKING,
    }

    interface Effects {
        /** The machine's clock, in ms: SystemClock.elapsedRealtime on the
         *  phone, which a wall-clock step mid-call does not move. Every time
         *  here, and the recording's start it is measured against, is on it. */
        long now();

        Object schedule(Runnable task, long delayMs);

        void cancel(Object handle);

        /** A call is already up: off-hook, its end not confirmed. */
        boolean callUp();

        /** Another app is in a call - a messenger, VoIP or a meeting. Answering
         *  would take the user out of it, and the mute is the whole phone's:
         *  it would silence them in that call too. */
        boolean otherAppCallUp();

        /** The phone waits for an emergency service to call back: never
         *  answer for the user then. */
        boolean emergencyCallback();

        /** The greeting is here to be played - this process's copy, not the
         *  app's, which can be there while an upload of it failed. */
        boolean greetingPresent();

        /** Accept the ringing call. False if that could not even be asked;
         *  true says only that it was asked - Telecom says nothing when
         *  there was no ringing call left to accept. */
        boolean answer();

        void hangUp();

        /** Telecom holds the call's audio mode. */
        boolean audioInCall();

        /** Mute or unmute the microphone - and remember holding the mute,
         *  so a restart can undo it. */
        void setMuted(boolean on);

        boolean muted();

        /** Start the greeting: lead-in, [checkTone] first if asked, the
         *  greeting, the beep. Asynchronous; calls back checkToneDone (only
         *  with a check tone) and greetingDone. */
        void playGreeting(boolean checkTone);

        void stopGreeting();

        /** The greeting is making a sound about now, and this phone's own
         *  side of the recording - muted - is not silent: a phone that mixes
         *  the greeting into the call's recording. That sound is not the
         *  caller. On the Honor its side was all zeros while muted
         *  (2026-10-22). */
        boolean greetingLoud();

        /** A message is being left: the app shows "Taking a message". */
        void messageStarted();

        /** The user took the call over - Talk to them, unmuting on the call
         *  screen, or answering a second call: the app's "Taking a message"
         *  is over, and in Ask first the message with it (Main). */
        void callTaken();

        void log(String message);
    }

    /** What happened in the call this machine answered, for the recording's
     *  name and the app. Millis on the machine's clock; 0 for "did not
     *  happen". */
    static final class Outcome {
        final long mutedAt;
        final long beepEndedAt;
        final long firstSoundAt;
        final boolean talked;
        final boolean test;
        /** The machine hung up without taking a message: the greeting could
         *  not be played, the microphone would not mute, or the call's audio
         *  never came (failAndHangUp). */
        final boolean greetingFailed;

        /** When the caller's first words began (see SPEECH_MS); 0 if none. */
        final long speechAt;
        /** When the user took the call over; 0 if they did not. */
        final long talkedAt;

        /** The call as the machine leaves it, at its end. */
        Outcome(AnswerMachine m) {
            this(m, m.talked);
        }

        /** As the machine leaves it, [talked] or not. */
        private Outcome(AnswerMachine m, boolean talked) {
            mutedAt = m.mutedAt;
            beepEndedAt = m.beepEndedAt;
            firstSoundAt = m.firstSoundAt;
            speechAt = m.speechAt;
            talkedAt = m.talkedAt;
            this.talked = talked;
            test = m.testCall;
            greetingFailed = m.greetingFailed;
        }

        /**
         * Where the message starts: half a second before the caller's first
         * words, and never later than the end of the beep - so the silent
         * greeting is cut and words said over it are kept. A blip (a click as
         * the call connects) does not move it. 0 if neither happened.
         */
        long messageStart() {
            return earliest(words(), beepEndedAt);
        }

        /** A message, as the app shows it - not a call the user took, which
         *  is saved as an ordinary recording, and not a test. */
        boolean isMessage() {
            return !talked && !test;
        }

        /**
         * Where a call the user took over starts: half a second before they
         * took it, or before the caller's first words if those came first -
         * not at the answer, which kept the user listening to their own
         * greeting, in silence, on the Honor (2026-10-22). 0 if neither is
         * known: all of it.
         */
        long takenStart() {
            return earliest(words(), talkedAt > 0 ? talkedAt - PRE_ROLL_MS : 0);
        }

        /**
         * Nothing was left: the greeting went out, and the caller hung up
         * before the beep without a word - a click as the call connected is
         * not one (see SPEECH_MS). Never for a call the user took, or one the
         * greeting never reached - there, all of the recording is the call.
         */
        boolean nothingLeft() {
            return !talked && !greetingFailed && mutedAt > 0 && beepEndedAt == 0 && speechAt == 0;
        }

        private long words() {
            return speechAt > 0 ? speechAt - PRE_ROLL_MS : 0;
        }

        /** The earlier of two times, either 0 for unknown. */
        private static long earliest(long a, long b) {
            return Math.max(0, a > 0 && b > 0 ? Math.min(a, b) : Math.max(a, b));
        }
    }

    private final Effects effects;

    /** Read by level(), on the encoder's thread. */
    private volatile State state = State.IDLE;
    private boolean enabled;
    private long maxMessageMs = 60_000L;

    private long ringAt;
    /** When a ring last ended without this machine answering it: the user
     *  answered, or the caller gave up. A go-ahead for a ring older than that
     *  is that ring's, come late, and never the next caller's. */
    private long ringEndedAt;
    /** The ring in progress is one this machine will not answer - it rang
     *  over a messenger or meeting call - so no go-ahead may arm it. */
    private boolean refused;
    private long goAheadAt;
    private long goAheadDelayMs;
    private Object answerTimer;
    private Object audioPoll;
    private long answeredAt;
    private Object muteWatch;
    private Object messageLimit;
    private Object silenceWatch;

    /** Armed by the diagnostics: answer the next ring, or take the next call
     *  this phone places, before [testUntil]. */
    private long testUntil;
    private boolean testIncoming;
    private volatile boolean testCall;

    private volatile long mutedAt;
    private volatile long beepEndedAt;
    private volatile long firstSoundAt;
    private volatile long lastSoundAt;
    /** The run of sound under way (see RUN_GAP_MS), and how much sound it
     *  has held; and when the first run long enough to be words began. */
    private volatile long runStartAt;
    private volatile long runSoundMs;
    private volatile long speechAt;
    private boolean talked;
    private long talkedAt;
    /** This machine asked for the hang-up: what follows is the call ending,
     *  not the user taking it - the Honor clears the mute as a call ends. */
    private boolean ending;
    private boolean unmutedSeen;
    private volatile boolean answered;
    /** An off-hook came after the answer: there is a call, and its end will
     *  come. Without one, the answer took nothing (see idleSignal), and no
     *  greeting starts. */
    private boolean offHookSeen;
    /** Telephony says the call this machine answered is over: what the
     *  recording hears from here on is the line going down, not a caller -
     *  unless the call outlives the claim (see FALSE_IDLE_MS). */
    private volatile boolean over;
    private long overAt;
    private boolean greetingFailed;

    AnswerMachine(Effects effects) {
        this.effects = effects;
    }

    State state() {
        return state;
    }

    /** The user's switch and the longest message, 0 for no limit. Off
     *  disarms a waiting answer, and nothing else: a message under way is
     *  left to finish. */
    void configure(boolean on, long maxMessageMs) {
        this.enabled = on;
        this.maxMessageMs = maxMessageMs;
        if (!on) {
            goAheadAt = 0;
            if (state == State.ARMED && !testCall) {
                disarm("switched off");
            }
        }
    }

    void ringing() {
        if (state != State.IDLE) {
            return;
        }
        if (effects.callUp()) {
            // Call waiting. Answering would put the call in progress on hold.
            effects.log("answer: a call rings during another one; not answering it");
            return;
        }
        boolean testRing = testIncoming && effects.now() < testUntil;
        // Asked only when the answer can matter: three calls into the audio
        // service, on every ring of a machine that is off unless switched on.
        boolean otherAppCall = (enabled || testRing) && effects.otherAppCallUp();
        reset();
        state = State.RINGING;
        ringAt = effects.now();
        if (otherAppCall || !enabled && !testRing) {
            if (otherAppCall) {
                effects.log("answer: a call rings during a messenger or meeting call; not answering it");
            }
            // Ringing, as far as go-aheads go: the app may say yes to it -
            // the ring can take the audio mode from the messenger before the
            // app looks, and a machine switched on mid-ring was not asked -
            // and a go-ahead held for "a ring about to come" would answer the
            // next caller instead.
            refused = true;
            goAheadAt = 0;
            return;
        }
        if (testRing) {
            testCall = true;
            testUntil = 0;
            arm(TEST_ANSWER_MS, "test call");
            return;
        }
        boolean early = goAheadAt > 0 && ringAt - goAheadAt <= GO_AHEAD_EARLY_MS;
        goAheadAt = 0;
        if (early) {
            arm(goAheadDelayMs, "go-ahead came before the ring");
        }
    }

    /** A test call answers after three seconds, whoever it is. */
    static final long TEST_ANSWER_MS = 3_000L;

    /** The app's word for the ringing call: answer it [delayMs] after the
     *  ring began. [rangAt] is when the app saw that ring, on this machine's
     *  clock (elapsedRealtime, the app's too); 0 if it did not say. */
    void goAhead(long delayMs, long rangAt) {
        if (!enabled) {
            effects.log("answer: go-ahead while switched off, ignored");
            return;
        }
        if (rangAt > 0 && rangAt < ringEndedAt) {
            // Decided for a ring that has ended since - the caller gave up
            // while the app looked them up. The ring now, if any, is someone
            // else's, whom the app has not judged.
            effects.log("answer: a go-ahead for a ring that is over, ignored");
            return;
        }
        if (state == State.RINGING && refused) {
            effects.log("answer: a go-ahead for a ring it will not answer, ignored");
        } else if (state == State.RINGING) {
            arm(delayMs, "go-ahead");
        } else if (state == State.IDLE) {
            goAheadAt = effects.now();
            goAheadDelayMs = delayMs;
        } else {
            effects.log("answer: go-ahead in state " + state + ", ignored");
        }
    }

    /** The app takes its go-ahead back. */
    void cancelGoAhead() {
        goAheadAt = 0;
        if (state == State.ARMED) {
            disarm("go-ahead withdrawn");
        }
    }

    private void arm(long delayMs, String why) {
        state = State.ARMED;
        long wait = Math.max(0, ringAt + delayMs - effects.now());
        answerTimer = effects.schedule(this::answerNow, wait);
        effects.log("answer: " + why + " - answering in " + wait + " ms");
    }

    private void disarm(String why) {
        cancel(answerTimer);
        answerTimer = null;
        state = State.RINGING;
        effects.log("answer: not answering - " + why);
    }

    private void answerNow() {
        answerTimer = null;
        if (state != State.ARMED) {
            return;
        }
        if (effects.callUp() || effects.audioInCall()) {
            // Off-hook already: the user answered it, in the moment before -
            // the call's audio comes up a moment before telephony says so,
            // and accepting a call that is no longer ringing does nothing
            // that can be seen, so the greeting would go into their call.
            state = State.IDLE;
            effects.log("answer: the call was answered already");
            return;
        }
        if (effects.otherAppCallUp()) {
            // A messenger or meeting call that began while this one rang.
            state = State.IDLE;
            effects.log("answer: a messenger or meeting call is up - not answering");
            return;
        }
        if (effects.emergencyCallback()) {
            // Not even a test: whoever rings in its window is answered.
            state = State.IDLE;
            effects.log("answer: emergency callback mode - not answering");
            return;
        }
        if (!effects.greetingPresent()) {
            // Answered with nothing to say, the caller is hung up on and
            // loses the carrier's voicemail too. A test plays the same file.
            state = State.IDLE;
            effects.log("answer: no greeting here - not answering");
            return;
        }
        effects.log("answer: answering");
        if (!effects.answer()) {
            state = State.IDLE;
            effects.log("answer: could not ask Telecom to answer");
            return;
        }
        answered = true;
        answeredAt = effects.now();
        state = State.ANSWERING;
        audioPoll = effects.schedule(this::pollAudio, AUDIO_POLL_MS);
    }

    private void pollAudio() {
        audioPoll = null;
        if (state != State.ANSWERING) {
            return;
        }
        boolean audio = effects.audioInCall();
        if (audio && !testCall && mutedAt == 0) {
            // The room out as soon as the call's audio is up: on the Honor
            // that is before the network connects the call (see idleSignal).
            // A test plays its check tone unmuted - the phone's own recording
            // can only carry it before the mute - and mutes after it.
            mute();
        }
        if (audio && offHookSeen && !ending) {
            // Not before the off-hook: a greeting, and the app's "Taking a
            // message", are for a call that is there.
            startGreeting();
        } else if (effects.now() - answeredAt >= AUDIO_WAIT_MS) {
            if (!offHookSeen) {
                // Nothing went off-hook: there was no call left to answer.
                nothingAnswered("no call came up after answering");
            } else if (!ending) {
                // Answered, and the audio never came: no greeting can be
                // played into it, and the caller would hear the room.
                failAndHangUp("the call's audio never came up");
            }
        } else {
            audioPoll = effects.schedule(this::pollAudio, AUDIO_POLL_MS);
        }
    }

    private void startGreeting() {
        state = State.GREETING;
        effects.playGreeting(testCall);
        effects.messageStarted();
        muteWatch = effects.schedule(this::watchMute, MUTE_WATCH_MS);
    }

    /** Mute - and hang up if that does not take: the caller would hear the
     *  room, and the mute watch would take it for the user taking the call. */
    private void mute() {
        effects.setMuted(true);
        mutedAt = effects.now();
        if (!effects.muted()) {
            failAndHangUp("the microphone did not mute");
        }
    }

    /** Hang up on a caller no message can be taken from. The recording is
     *  then the call's, not a message (Outcome.greetingFailed). */
    private void failAndHangUp(String why) {
        effects.log("answer: " + why + "; hanging up");
        greetingFailed = true;
        ending = true;
        effects.stopGreeting();
        effects.hangUp();
    }

    /** The test's check tone has gone out: mute now. */
    void checkToneDone() {
        if (state == State.GREETING && testCall && mutedAt == 0) {
            mute();
        }
    }

    /** The greeting could not be played. The caller hears a silent line, and
     *  has not been told they are leaving a message: hang up. */
    void greetingFailed() {
        if (state != State.GREETING) {
            return;
        }
        failAndHangUp("the greeting could not be played");
    }

    /** The beep has finished playing. */
    void greetingDone() {
        if (state != State.GREETING) {
            return;
        }
        beepEndedAt = effects.now();
        state = State.MESSAGE;
        if (!testCall && maxMessageMs <= 0) {
            silenceWatch = effects.schedule(this::watchSilence, SILENCE_CHECK_MS);
            effects.log("answer: greeting played; no limit - hanging up after "
                    + SILENCE_HANGUP_MS / 1000 + " s of silence");
            return;
        }
        long limit = testCall ? TEST_MESSAGE_MS : maxMessageMs;
        messageLimit = effects.schedule(this::limitReached, limit);
        effects.log("answer: greeting played; up to " + limit / 1000 + " s of message");
    }

    /** No limit: the message ends when the caller has been silent too long,
     *  counted from the beep or from the last sound they made. */
    private void watchSilence() {
        silenceWatch = null;
        if (state != State.MESSAGE) {
            return;
        }
        long quietSince = Math.max(lastSoundAt, beepEndedAt);
        if (effects.now() - quietSince >= SILENCE_HANGUP_MS) {
            effects.log("answer: " + SILENCE_HANGUP_MS / 1000 + " s without a sound from the caller, hanging up");
            ending = true;
            effects.hangUp();
            return;
        }
        silenceWatch = effects.schedule(this::watchSilence, SILENCE_CHECK_MS);
    }

    private void limitReached() {
        messageLimit = null;
        if (state == State.MESSAGE) {
            effects.log("answer: longest message reached, hanging up");
            ending = true;
            effects.hangUp();
        }
    }

    private void watchMute() {
        muteWatch = null;
        if (state != State.GREETING && state != State.MESSAGE || ending) {
            return;
        }
        if (!over && mutedAt > 0 && !effects.muted() && effects.audioInCall()) {
            // Unmuted, and seen so twice in a row with the call still up: the
            // user took the call from the call screen. Once could be the
            // caller hanging up - the Honor clears the mute as a call ends,
            // and the audio mode may follow a moment later; and after
            // telephony said the call is over, the unmute is the end's.
            if (unmutedSeen) {
                effects.log("answer: unmuted from the call screen - the user took the call");
                talk();
                return;
            }
            unmutedSeen = true;
        } else {
            unmutedSeen = false;
        }
        muteWatch = effects.schedule(this::watchMute, MUTE_WATCH_MS);
    }

    /** Talk to them: the greeting stops, the microphone opens, the limit
     *  goes, and the call carries on as a normal call. */
    void talk() {
        if (state != State.GREETING && state != State.MESSAGE && state != State.ANSWERING) {
            return;
        }
        effects.stopGreeting();
        cancelTimers();
        if (mutedAt > 0) {
            effects.setMuted(false);
        }
        talked = true;
        talkedAt = effects.now();
        state = State.TALKING;
        effects.callTaken();
    }

    /** The message as it stands, as if the call ended here untaken: what a
     *  call taken over in Ask first keeps of it (Main). */
    Outcome messageSoFar() {
        return new Outcome(this, false);
    }

    /** Hang up now, keeping what was said - while a message is taken. A
     *  call the user took over is theirs to end. */
    void hangUpNow() {
        if (takingMessage()) {
            ending = true;
            effects.hangUp();
        }
    }

    /** Off-hook. Before the answer timer fires, that is the user answering. */
    void offHook(boolean incoming) {
        if (answered) {
            offHookSeen = true;
            if (over) {
                // Up again after telephony's idle (CallStates takes it for the
                // same call going on): what is heard is a caller again, and an
                // unmute the user's - or the limit would hang up a call that
                // goes on.
                over = false;
                effects.log("answer: off-hook again after the idle; listening on");
            }
        }
        if (state == State.IDLE && !incoming && testUntil > effects.now() && !testIncoming) {
            // The diagnostics' outgoing test: the far end answers by itself.
            testCall = true;
            testUntil = 0;
            answered = true;
            offHookSeen = true;
            answeredAt = effects.now();
            state = State.ANSWERING;
            audioPoll = effects.schedule(this::pollAudio, TEST_OUTGOING_WAIT_MS);
            return;
        }
        if (testUntil > 0 && !answered) {
            // Any other call while a test waits is the user's own - an
            // emergency call among them: the test is off, so a call back
            // after it is never answered as the test's caller.
            testUntil = 0;
            effects.log("answer: a call of the user's while a test waited; the test is off");
        }
        if (state == State.ARMED || state == State.RINGING) {
            cancel(answerTimer);
            answerTimer = null;
            state = State.IDLE;
            ringEndedAt = effects.now();
            effects.log("answer: the user answered");
        }
    }

    /** An outgoing test waits this long for the far end to pick up. */
    static final long TEST_OUTGOING_WAIT_MS = 4_000L;

    /** An idle from telephony. While ringing: the caller gave up, or the
     *  call was declined. A call's end is {@link #callEnded}. */
    void idleSignal() {
        if (state == State.RINGING || state == State.ARMED) {
            cancel(answerTimer);
            answerTimer = null;
            state = State.IDLE;
            goAheadAt = 0;
            ringEndedAt = effects.now();
            effects.log("answer: stopped ringing");
        } else if (answered && !offHookSeen) {
            // Answered as the ring ended - the caller gave up, or the
            // carrier's voicemail took it, in that moment. Nothing went
            // off-hook, so no end will come to undo the answer. On the Honor
            // the call's audio comes up as the answer is sent, before the
            // network connects it (2026-10-22, dumpsys telecom: 14 ms, and
            // 235 ms to connect), so the microphone may be muted already; no
            // greeting starts before the off-hook.
            nothingAnswered("the ring ended as it was answered");
        } else if (answered) {
            over = true;
            overAt = effects.now();
        }
    }

    /**
     * Telephony's idle was not the call's end: the audio mode still holds the
     * call (CallStates.endCheck). Once that has lasted FALSE_IDLE_MS, the
     * caller is listened to again - and an unmute is the user's again.
     */
    void callGoesOn() {
        if (over && effects.now() - overAt >= FALSE_IDLE_MS) {
            over = false;
            effects.log("answer: the idle was not the call's end; listening on");
        }
    }

    /** An answer that took nothing: undone as at a call's end - the mute
     *  above all - and back to waiting, as if never answered. No greeting
     *  started, so the app heard of no message. */
    private void nothingAnswered(String why) {
        callEnded();
        ringEndedAt = effects.now();
        effects.log("answer: " + why + " - nothing was answered");
    }

    /**
     * The call's end, confirmed. Everything this machine started is undone -
     * the mute above all - and what happened is returned for the recording,
     * or null if this machine had no part in the call.
     */
    Outcome callEnded() {
        cancelTimers();
        Outcome outcome = null;
        if (answered) {
            effects.stopGreeting();
            if (mutedAt > 0 && !talked) {
                effects.setMuted(false);
            }
            outcome = new Outcome(this);
        }
        reset();
        state = State.IDLE;
        return outcome;
    }

    /** Whether this machine answered the call in progress - taken over
     *  since, or not. */
    boolean answeredThisCall() {
        return answered;
    }

    /** Whether a message is being taken: answered, and not taken over. What
     *  the recording of the call is, until then (Main). */
    boolean takingMessage() {
        return state == State.ANSWERING || state == State.GREETING || state == State.MESSAGE;
    }

    /** Whether a diagnostics test call is under way: its ring armed, or the
     *  call answered - not a test ring that ended unanswered. */
    boolean testing() {
        return testCall && state != State.IDLE;
    }

    /**
     * A chunk of the call's audio, as it is recorded: [rms] of 16-bit samples,
     * both sides together - this phone's side is muted, so a sound is the
     * caller's. [chunkMs] of audio. Called on the encoder's thread, often; it
     * notes the first words, for the message's start, and the last sound,
     * for a message with no limit to end on silence.
     */
    void level(double rms, long chunkMs) {
        if (mutedAt == 0 || over || rms < SOUND_RMS) {
            return;
        }
        if (state == State.GREETING && effects.greetingLoud()) {
            // The greeting itself, heard in the recording on this phone's
            // own side: what the caller says over it counts in its pauses,
            // and after the beep.
            return;
        }
        long now = effects.now();
        if (now - mutedAt < SETTLE_MS) {
            return;
        }
        if (lastSoundAt == 0 || now - lastSoundAt > RUN_GAP_MS) {
            runStartAt = now;
            runSoundMs = 0;
        }
        runSoundMs += chunkMs;
        if (speechAt == 0 && runSoundMs >= SPEECH_MS) {
            speechAt = runStartAt;
        }
        lastSoundAt = now;
        if (firstSoundAt == 0) {
            firstSoundAt = now;
        }
    }

    /** Arm a diagnostics test: the next incoming call, or the next call this
     *  phone places, within [windowMs]. */
    void armTest(boolean incoming, long windowMs) {
        testIncoming = incoming;
        testUntil = effects.now() + windowMs;
        effects.log("answer: test armed for the next " + (incoming ? "incoming" : "outgoing")
                + " call in " + windowMs / 1000 + " s");
    }

    private void reset() {
        ringAt = 0;
        answeredAt = 0;
        mutedAt = 0;
        beepEndedAt = 0;
        firstSoundAt = 0;
        lastSoundAt = 0;
        runStartAt = 0;
        runSoundMs = 0;
        speechAt = 0;
        talked = false;
        talkedAt = 0;
        ending = false;
        unmutedSeen = false;
        answered = false;
        offHookSeen = false;
        over = false;
        overAt = 0;
        refused = false;
        greetingFailed = false;
        testCall = false;
    }

    private void cancelTimers() {
        cancel(answerTimer);
        cancel(audioPoll);
        cancel(muteWatch);
        cancel(messageLimit);
        cancel(silenceWatch);
        answerTimer = audioPoll = muteWatch = messageLimit = silenceWatch = null;
    }

    private void cancel(Object handle) {
        if (handle != null) {
            effects.cancel(handle);
        }
    }
}
