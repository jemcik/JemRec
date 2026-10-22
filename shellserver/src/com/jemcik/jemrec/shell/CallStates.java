package com.jemcik.jemrec.shell;

/**
 * The phone's call state, as telephony reports it, made into the start and
 * end of each call and which way it went. Plain Java, nothing of Android, so
 * it can be tested (shellserver/tests: CallStatesTest); Main.CallWatch feeds
 * it the events and does what it says through {@link Effects}.
 *
 * AN IDLE IS A CLAIM, NOT A FACT.
 *
 * Ending a recording is destructive - the app closes the socket, the encoder
 * dies of ECONNRESET mid-frame, and whatever was left of the call is gone - so
 * it must not be done on one unconfirmed signal.
 *
 * Measured on this phone: a twenty-second outgoing call recorded six seconds.
 * The daemon's own log shows off-hook, the client connecting on VOICE_CALL,
 * and then "idle" arriving while the call was still up, followed immediately
 * by the encoder's ECONNRESET. The call was not over; the state said it was.
 *
 * So an idle before any off-hook is ignored outright - that one is just the
 * current state being handed over at registration, which used to fire a
 * CALL_ENDED at every daemon start. And a real idle is confirmed against the
 * AUDIO MODE rather than the call state - see {@link #endCheck}.
 */
final class CallStates {

    /** What the call states do to the world. */
    interface Effects {
        /** A call went off-hook: record it (or ask), as [incoming] or not. */
        void callStarted(boolean incoming);

        /** The call is over. */
        void callEnded();

        /** Run {@link #endCheck} once, [delayMs] from now. */
        void scheduleEndCheck(long delayMs);

        /** The scheduled end check is not to run. */
        void cancelEndCheck();

        /** Whether telecom still holds a call's audio mode. */
        boolean audioInCall();

        /** An idle came, and the call is still up after it: whatever else
         *  took that idle for the end can think again. */
        void callGoesOn();

        void log(String message);
    }

    /** How long after an idle the end is checked, and checked again: long
     *  enough to outlast a momentary idle, short enough not to leave a
     *  finished call recording into silence. */
    static final long END_CHECK_MS = 2000L;

    private final Effects effects;
    /** The call starting now rang first: it is incoming. */
    private boolean sawRinging;
    private boolean inCall;
    private boolean endCheckPending;

    CallStates(Effects effects) {
        this.effects = effects;
    }

    void ringing() {
        sawRinging = true;
        effects.log("callwatch: ringing");
    }

    void offHook() {
        cancelEndCheck();
        inCall = true;
        effects.log("callwatch: off-hook (" + (sawRinging ? "incoming" : "outgoing") + ")");
        effects.callStarted(sawRinging);
    }

    void idle() {
        if (!inCall) {
            if (sawRinging) {
                // It rang and was never answered - missed, or declined.
                // Forget the ring, or the next OUTGOING call is labelled
                // incoming: the flag only ever cleared when a call ended.
                sawRinging = false;
                effects.log("callwatch: ringing ended without a call");
            } else {
                effects.log("callwatch: idle before any call, ignoring");
            }
            return;
        }
        // The ring was this call's, and this is its end: forget it now, not
        // when the end is confirmed. The next call can ring inside those two
        // seconds - and the confirmation then cleared ITS ring, and an
        // incoming call was recorded as outgoing (emulator, 2026-10-21: the
        // saver then took the call before for it).
        sawRinging = false;
        cancelEndCheck();
        endCheckPending = true;
        effects.scheduleEndCheck(END_CHECK_MS);
    }

    /**
     * CONFIRM THE END AGAINST THE AUDIO MODE, AND POLL - DO NOT TRUST ONE
     * IDLE.
     *
     * Measured on this phone: with some dialers (Google Phone here) telephony
     * CALL_STATE goes IDLE partway through a live call and STAYS idle - a 37s
     * call reported idle at 11s, a 30s call at 6s - so a single re-read of
     * getCallState() confirms an end that has not happened, and because the
     * state never leaves idle there is no later edge to correct it. The
     * recording was cut ~25s short, twice.
     *
     * Telecom, though, holds the audio mode at MODE_IN_CALL for the real
     * length of the call: its dumpsys setMode history lines up to the second
     * with the true spans. So the audio mode is the signal to trust, and
     * because telephony gives no second idle edge it is POLLED here rather
     * than checked once - re-armed until telecom actually leaves in-call, then
     * ended. A momentary idle with the audio still in-call simply keeps the
     * recording running, which is the whole point.
     */
    void endCheck() {
        if (!endCheckPending) {
            return;
        }
        endCheckPending = false;
        if (effects.audioInCall()) {
            // Still up. Telephony won't tell us again; keep watching the
            // audio mode, which will.
            endCheckPending = true;
            effects.scheduleEndCheck(END_CHECK_MS);
            effects.callGoesOn();
            return;
        }
        inCall = false;
        effects.log("callwatch: end confirmed (audio mode left in-call)");
        effects.callEnded();
    }

    /** A phone call is up: off-hook, its end not yet confirmed. */
    boolean inCall() {
        return inCall;
    }

    /** The call that is up rang first: it is incoming. */
    boolean rang() {
        return sawRinging;
    }

    private void cancelEndCheck() {
        if (endCheckPending) {
            endCheckPending = false;
            effects.cancelEndCheck();
        }
    }
}
