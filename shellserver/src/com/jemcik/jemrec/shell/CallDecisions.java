package com.jemcik.jemrec.shell;

/**
 * What the recorder decides about a call, in plain Java: whether a call
 * starting is recorded, asked about or left out; the rules' skip window; a
 * wake for a call already over; what becomes of a message the user took
 * over, and of a recording as its call ends; and what a quit or debugging
 * off waits out. The call state and what is done about each decision are
 * Calls'; these are the rules alone, so daemon-tests holds each to a table.
 */
final class CallDecisions {

    private CallDecisions() {
    }

    /**
     * How calls are recorded: from the environment at spawn, then pushed by
     * the app whenever the user changes it.
     *   OFF       - captures nothing (the recorder switch is off).
     *   AUTOMATIC - records from off-hook, every call.
     *   ON_DEMAND - records NOTHING until the user answers a start-of-call
     *               prompt with yes, which arrives as Wire.COMMAND_START. So a
     *               declined call is never written to disk at all.
     *
     * OFF UNTIL TOLD OTHERWISE. This defaulted to AUTOMATIC, with the app
     * pushing the real mode over the socket a moment after the spawn - and
     * that push is best-effort. A call in the gap, or after a push that did
     * not land, was recorded whatever the user had chosen, including "ask me
     * first" and "off". A recorder that fails must fail towards not
     * recording: a missed call gets noticed and fixed, a call recorded
     * against the user's setting is neither. So the mode now arrives in the
     * environment with the token, and a daemon that has not been told
     * anything captures nothing.
     */
    static final int MODE_OFF = 0;
    static final int MODE_AUTOMATIC = 1;
    static final int MODE_ON_DEMAND = 2;

    /** A mode this recorder knows; anything else is not taken. */
    static boolean isMode(int value) {
        return value >= MODE_OFF && value <= MODE_ON_DEMAND;
    }

    /** A skip is for the call starting now, not for one an hour later. */
    static final long SKIP_VALID_MS = 60_000L;
    static final long SKIP_CURRENT_MS = 8_000L;

    /** A call starting, before the mode is looked at. */
    enum Start {
        /** Another call is up, a messenger's or this one a messenger's: one
         *  at a time, the first one's recording runs on. */
        IGNORED,
        /** A phone call this process answered: the recording is the message,
         *  whatever the mode. */
        MESSAGE,
        /** The rules said no to it while it rang or was dialled. */
        SKIPPED,
        /** Recorded, asked about or not, by the mode. */
        BY_MODE,
    }

    /**
     * [callUp]: a call was up already; [messenger]: this one is a messenger
     * app's; [messengerUp]: a messenger app's call is up; [takingMessage]:
     * the answering machine is taking a message; [skipOpen]: the rules' skip
     * window is open. The last two count for a phone call only.
     */
    static Start start(boolean callUp, boolean messenger, boolean messengerUp, boolean takingMessage,
            boolean skipOpen) {
        if (callUp && (messenger || messengerUp)) {
            return Start.IGNORED;
        }
        if (!messenger && takingMessage) {
            return Start.MESSAGE;
        }
        if (!messenger && skipOpen) {
            return Start.SKIPPED;
        }
        return Start.BY_MODE;
    }

    /** By the mode, for a call that was not left out. */
    enum ByMode {
        /** Recorded at once, and the app told its name. */
        RECORD,
        /** Asked first, and said yes to already in this call: the app is
         *  told the recording's name, not asked again. */
        RECORDING_ALREADY,
        /** Asked first: the app puts up the Record prompt. */
        ASK,
        /** Switched off. */
        OFF,
    }

    static ByMode byMode(int mode, boolean recording) {
        switch (mode) {
            case MODE_AUTOMATIC:
                return ByMode.RECORD;
            case MODE_ON_DEMAND:
                return recording ? ByMode.RECORDING_ALREADY : ByMode.ASK;
            default:
                return ByMode.OFF;
        }
    }

    /** The skip window, at [now]. */
    static boolean skipOpen(long skipUntil, long now) {
        return skipUntil > now;
    }

    /** What 'K' does. */
    enum Skip {
        /** Taken back: the window closes. */
        CANCELLED,
        /** The phone call recorded a moment ago, which dialling started:
         *  stopped and deleted now. */
        CURRENT,
        /** The next phone call starting, within the window. */
        NEXT,
    }

    /** [value] the byte sent; [phoneRecording] a phone call is being
     *  recorded, started [ageMs] ago. */
    static Skip skip(int value, boolean phoneRecording, long ageMs) {
        if (value != 1) {
            return Skip.CANCELLED;
        }
        if (phoneRecording && ageMs < SKIP_CURRENT_MS) {
            return Skip.CURRENT;
        }
        return Skip.NEXT;
    }

    /** 'S': the user's yes to the Record prompt starts a recording only while
     *  a call is up and recording is not switched off. */
    static boolean startOnDemand(boolean callUp, int mode) {
        return callUp && mode != MODE_OFF;
    }

    /**
     * A wake still queued, or being retried, for a call already over: a start
     * - the call's, a message's, a message taken - is dropped once a later
     * end has been seen ([endedSeq] the last call ended, [seq] the call the
     * wake was for), or it would put up a prompt, a "Recording" or a "Taking
     * a message" for a call that has ended, until the next one did.
     */
    static boolean staleStart(boolean start, long endedSeq, long seq) {
        return start && endedSeq >= seq;
    }

    /** The user took a call the answering machine answered: in Ask first
     *  the message is split off and the prompt put up; otherwise, and for a
     *  test, the app is told the message was taken. */
    static boolean splitsOffMessage(int mode, boolean testing) {
        return mode == MODE_ON_DEMAND && !testing;
    }

    /** What the split does with the recording so far. */
    enum Split {
        /** The call ended as it was taken: its end has named it. */
        NOTHING,
        /** There was no recording to split. */
        NO_RECORDING,
        /** Taken before a word was left: the recording goes. */
        DROP,
        /** Kept as a message. */
        MESSAGE,
    }

    static Split split(boolean callUp, boolean recording, boolean wordLeft) {
        if (!callUp) {
            return Split.NOTHING;
        }
        if (!recording) {
            return Split.NO_RECORDING;
        }
        return wordLeft ? Split.MESSAGE : Split.DROP;
    }

    /** A recording as its call ends. */
    enum Ending {
        /** An ordinary call's. */
        PLAIN,
        /** A diagnostics test's: deleted, not the user's. */
        TEST,
        /** A message: named with where it starts. */
        MESSAGE,
        /** A call the user took over: theirs, named from when they took it. */
        TAKEN,
    }

    /** [answered] the answering machine answered the call (it has an
     *  outcome); [test] it was a test; [message] it stayed a message. */
    static Ending ending(boolean answered, boolean test, boolean message) {
        if (!answered) {
            return Ending.PLAIN;
        }
        if (test) {
            return Ending.TEST;
        }
        return message ? Ending.MESSAGE : Ending.TAKEN;
    }

    /** At a call's end with nothing recorded, the app is told - to take its
     *  prompt down - unless recording is switched off. */
    static boolean endWakes(boolean recorded, int mode) {
        return !recorded && mode != MODE_OFF;
    }

    /**
     * What a quit, or debugging off, waits out: a recording, a call, a file
     * being fetched (cut short, it would read as a complete, shorter
     * recording), or the answering machine past ringing - answering, taking
     * a message, or waiting on an ask-first answer.
     */
    static boolean busy(boolean recording, boolean callUp, int fetches, @Nullable AnswerMachine.State machine) {
        return recording || callUp || fetches > 0
                || (machine != null && machine != AnswerMachine.State.IDLE
                    && machine != AnswerMachine.State.RINGING);
    }

    /**
     * Whether a test call's mid-greeting check is the test's - the one armed
     * as [armedThen], as its greeting began - with [armedNow] the arming
     * now: the same arming, and the test still under way ([testing]). Run
     * after it ended, a check would read whatever call came next, and keep
     * what it found as the finished test's. A reading taken in time is kept
     * however it ends meanwhile ([testing] true then), but never for a test
     * armed since: those are not its results.
     */
    static boolean testCheckApplies(long armedNow, long armedThen, boolean testing) {
        return armedNow == armedThen && testing;
    }

    /** A test's Wi-Fi reading (Checks.callOnWifi) over the one kept before
     *  ([before]): one that could tell replaces it, the latest naming the
     *  path the call is on now; one that could not (-1) leaves it. */
    static int wifiAfter(int before, int reading) {
        return reading >= 0 ? reading : before;
    }

    /** A test's voice network type read mid-greeting over the one read
     *  before ([before]: as its message began, or an earlier greeting's): a
     *  type it could name (TelephonyManager's NETWORK_TYPE_*, not 0, unknown,
     *  nor -1, not read) replaces it. */
    static int voiceAfter(int before, int reading) {
        return reading > 0 ? reading : before;
    }

    /** What 'R' says of a test in [state] with [inFlight] of its checks'
     *  readings still being taken: "running" for a "done" one until they are
     *  kept, as the app reads a done test once - a reading kept after that
     *  would be lost. */
    static String testStateToSay(String state, int inFlight) {
        return "done".equals(state) && inFlight > 0 ? "running" : state;
    }
}
