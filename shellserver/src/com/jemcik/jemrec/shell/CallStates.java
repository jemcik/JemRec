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
 *
 * NOT EVERY CALL THE STATE REPORTS IS A PHONE CALL.
 *
 * An app can put its calls through Telecom, Android's call service, and the
 * call state then rings and goes off-hook for them as for a phone call.
 * Google Meet does (its own "self-managed" account; so do Gmail and Slack):
 * on an Honor, Android 17, 2026-10-26, every Meet call and a meeting was taken
 * for a phone call and recorded as one, with no switch for it. And when the
 * other person declined a Meet call and phoned back at once, their call rang
 * before the state ever went idle - so the declined Meet call and their phone
 * call were one recording, and their side of the phone call was not in it.
 * Telecom knows which calls are the phone's own ("managed"), so a ring or an
 * off-hook while it holds none is another app's call: left to the messenger
 * watch (Voip), which records it only if the user switched that app on.
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

        /** Whether Telecom holds a phone call - the phone's own, not an
         *  app's (TelecomManager.isInManagedCall); null when it cannot be
         *  asked, which counts as yes, as everything did before. */
        @Nullable Boolean phoneCallInTelecom();

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
    /** Another app's call through Telecom rang, and has not gone off-hook. */
    private boolean appRang;
    /** Which way the app's call that is up went: whether it rang first. Null
     *  with none up since the last idle. */
    private @Nullable Boolean appCallIncoming;

    CallStates(Effects effects) {
        this.effects = effects;
    }

    /** Whether a ring is another app's call (appsCall). One that is, rang. */
    boolean appsRinging() {
        if (!appsCall("ringing")) {
            return false;
        }
        appRang = true;
        return true;
    }

    /** Whether an off-hook is another app's call (appsCall), and if it is,
     *  which way it went: incoming if Telecom rang for it first. */
    boolean appsOffHook() {
        if (!appsCall("off-hook")) {
            return false;
        }
        appCallIncoming = appRang;
        appRang = false;
        return true;
    }

    /**
     * Which way the app's call that is up went - for Google Meet, which the
     * app's own notifications cannot say: its ring names the caller by one
     * name and its in-call notification by another (the Honor, 2026-10-26),
     * and an incoming call read as a missed ring and a call placed to someone
     * else. Null for none, and for an app's call that began during a phone
     * call: the state, off-hook already, said nothing of it. Incoming, too,
     * while an app's ring is up and its off-hook not yet heard: the
     * messenger watch could find the call answered - the audio mode, the app
     * playing and capturing - before telephony's off-hook reaches this
     * thread, the two coming from different system services (read in the
     * code, not seen: on the Honor, 2026-10-26, the off-hook came first in
     * every Meet call), and a call it finds while the app's ring is up is
     * that ring, answered.
     */
    @Nullable Boolean appCallIncoming() {
        if (appCallIncoming != null) {
            return appCallIncoming;
        }
        return appRang ? Boolean.TRUE : null;
    }

    /**
     * Whether the call the state speaks of now - [event], "ringing" or
     * "off-hook" - is another app's, not a phone call: Telecom holds no phone
     * call. Asked before the event goes anywhere, the answering machine
     * included. Said once each time, in the log, as nothing else will.
     */
    private boolean appsCall(String event) {
        if (!Boolean.FALSE.equals(effects.phoneCallInTelecom())) {
            return false;
        }
        // Worded so that tools/emulator_checks.py, counting "callwatch:
        // ringing" and "off-hook (", never takes it for a phone call's.
        effects.log("callwatch: another app's call through Telecom is " + event + " - not a phone call");
        return true;
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
        // Nothing is up, an app's call included: its ring and its way go.
        appRang = false;
        appCallIncoming = null;
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
        // The audio mode is also a messenger's, or Meet's: a phone call hung up
        // while one of those goes on leaves the mode in a call. Telecom saying
        // it holds no phone call any more is the end, whatever the mode.
        boolean audioUp = effects.audioInCall();
        if (audioUp && !Boolean.FALSE.equals(effects.phoneCallInTelecom())) {
            // Still up. Telephony won't tell us again; keep watching the
            // audio mode, which will.
            endCheckPending = true;
            effects.scheduleEndCheck(END_CHECK_MS);
            effects.callGoesOn();
            return;
        }
        inCall = false;
        // Which of the two, said: the audio mode's own line has just called
        // the call still up.
        effects.log(audioUp
                ? "callwatch: end confirmed (no phone call left in Telecom; the audio mode is another app's call)"
                : "callwatch: end confirmed (audio mode left in-call)");
        effects.callEnded();
    }

    /**
     * The audio mode changed. A phone call hung up while another app's call
     * goes on - Meet's, taken while the phone call was up - never gets an
     * idle: the state stays off-hook for the app's call, and the phone call's
     * recording would run on into it. So a change of mode with no phone call
     * left in Telecom stands in for that idle, confirmed as any idle is.
     */
    void audioModeChanged() {
        if (inCall && !endCheckPending && Boolean.FALSE.equals(effects.phoneCallInTelecom())) {
            effects.log("callwatch: no phone call left in Telecom, though the state is still off-hook");
            idle();
        }
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
