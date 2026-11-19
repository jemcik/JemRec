package com.jemcik.jemrec.shell;

/**
 * Where a phone call's capture has been put, and what is known of the call
 * so far: one value, replaced whole (PhoneCapture), so a capture made anew
 * after a failed read (Restarting) takes it up as it stood - and decided
 * here, in plain Java, so it is tested off the phone (CallPathTest).
 *
 * Until 1.2's reviews a capture made anew was made as at off-hook: a call in
 * "communication" mode went straight on the mixer, past the checks for
 * another app's call up over the phone call (Checks.mixerAfterModeChange: a
 * Meet call over a held phone call would have been recorded into it), the
 * once-a-second looks at a call put on the mixer mid-call stopped, and the
 * microphone the guard had given back to the call was opened again. Then a
 * capture made anew whose own start failed - the audio server still down -
 * handed on nothing, and the one after it began from off-hook again; a mixer
 * refused at the call's first look was never asked for again, and the call
 * not taken for one that began on the mixer; and the fields were read one at
 * a time while another thread changed them (reviews, 2026-11-18).
 */
final class CallPath {

    /** A mixer wanted and not had - the copy refused, or a capture that
     *  would not start - is tried again each second this many times
     *  (recheck): a copy Android goes on refusing is not asked for all call.
     *  Each time it is had the count starts again, and a capture made anew
     *  has its own: made after a failed read, the audio server may have
     *  come back. */
    static final int MIXER_TRIES = 5;

    /** The first look at the mode has answered: the start was decided. */
    final boolean asked;
    /** The mode said "communication" as the call went off-hook: 0.23's mixer
     *  call, left on the mixer whatever else plays (Checks.mixerAfterModeChange).
     *  A fact of the call, kept whether or not a mixer could be had. */
    final boolean startedOnMixer;
    /** Whether the capture being read is the mixer's. */
    final boolean onMixer;
    /** On the mixer by a change during the call: looked at again each second. */
    final boolean mixerMidCall;
    /** The last mode told, or -1. */
    final int lastMode;
    /** The microphone still to be had: false once the guard gave it back to
     *  the call (Voip.Capture.dropMic), for the rest of the call. */
    final boolean micKept;
    /** How many more times a mixer wanted and not had is tried (MIXER_TRIES). */
    final int mixerTriesLeft;

    private CallPath(boolean asked, boolean startedOnMixer, boolean onMixer, boolean mixerMidCall, int lastMode,
            boolean micKept, int mixerTriesLeft) {
        this.asked = asked;
        this.startedOnMixer = startedOnMixer;
        this.onMixer = onMixer;
        this.mixerMidCall = mixerMidCall;
        this.lastMode = lastMode;
        this.micKept = micKept;
        this.mixerTriesLeft = mixerTriesLeft;
    }

    /** As the call goes off-hook: nothing asked, the call capture. */
    static final CallPath OFF_HOOK = new CallPath(false, false, false, false, -1, true, MIXER_TRIES);

    /** The first look's answer, [mode] on Android [sdk]: asked, and a call
     *  that began on the mixer if the mode says it plays through it - set
     *  before the mixer is asked for, whether or not it comes, so a refusal
     *  is tried again (afterLook) and the call still counted as one that
     *  began there. Refused, it was never asked for again, and the call lost
     *  its other side, which VOICE_CALL does not hear in such a call
     *  (review, 2026-11-18). */
    CallPath firstLook(int mode, int sdk) {
        return asked(mode, Checks.phoneCallInMixer(mode, sdk));
    }

    /** What a look leaves to do (afterLook). */
    enum Next {
        /** Nothing more until the mode changes. */
        DONE,
        /** Decided again in a second: on the mixer by a change mid-call. */
        LOOK_AGAIN,
        /** Decided again in a second, one of the mixer's tries spent: wanted
         *  and not had. */
        TRY_AGAIN,
        /** Wanted and not had, and the tries spent: the call capture stays,
         *  said. */
        WOULD_NOT_COME,
    }

    /** After a look that [wantsMixer]: what is left to do. */
    Next afterLook(boolean wantsMixer) {
        boolean wantedNotHad = wantsMixer && !onMixer;
        if (!recheck(wantsMixer)) {
            return wantedNotHad ? Next.WOULD_NOT_COME : Next.DONE;
        }
        return wantedNotHad ? Next.TRY_AGAIN : Next.LOOK_AGAIN;
    }

    /** The first look at the mode: [mode], and whether it says the call
     *  plays through the mixer - a fact of the call whether or not the mixer
     *  can be had, which only swapped() puts it on. */
    CallPath asked(int mode, boolean beganOnMixer) {
        return new CallPath(true, beganOnMixer, onMixer, mixerMidCall, mode, micKept, mixerTriesLeft);
    }

    /** A mode told during the call: a change, and its own tries - spent on
     *  an earlier one, a later move back to "communication" got one try
     *  (review, 2026-11-18). */
    CallPath mode(int mode) {
        return new CallPath(asked, startedOnMixer, onMixer, mixerMidCall, mode, micKept, MIXER_TRIES);
    }

    /** Handed to the [mixer] or the call capture; [midCall] by a change. A
     *  call that began on the mixer is not looked at again: only the modem's
     *  voice path takes it off. On the mixer, its tries start again. */
    CallPath swapped(boolean mixer, boolean midCall) {
        return new CallPath(asked, startedOnMixer, mixer, mixer && midCall && !startedOnMixer, lastMode, micKept,
                mixer ? MIXER_TRIES : mixerTriesLeft);
    }

    /** The guard gave the microphone back to the call. */
    CallPath micGone() {
        return new CallPath(asked, startedOnMixer, onMixer, mixerMidCall, lastMode, false, mixerTriesLeft);
    }

    /** A look again in a second, for a mixer wanted and not had: one of
     *  its tries spent. */
    CallPath mixerTried() {
        return new CallPath(asked, startedOnMixer, onMixer, mixerMidCall, lastMode, micKept,
                Math.max(0, mixerTriesLeft - 1));
    }

    /** What a capture made anew starts on: the mixer, if the call was there. */
    boolean startOnMixer() {
        return asked && onMixer;
    }

    /** Whether a capture made anew decides again from where the call was -
     *  not asking as at off-hook, which only a call whose first look had not
     *  answered does. */
    boolean decideAgain() {
        return asked;
    }

    /** Made anew, on the mixer or not ([hadMixer]: the call capture where
     *  the mixer was wanted and would not come): the call's facts kept, and
     *  where it is now. */
    CallPath madeAnew(boolean hadMixer) {
        return new CallPath(asked, startedOnMixer, hadMixer, hadMixer && mixerMidCall, lastMode, micKept,
                MIXER_TRIES);
    }

    /** Whether to decide again in a second: a call put on the mixer mid-call
     *  is looked at again, and one that [wantsMixer] but is not on it - the
     *  copy refused, a capture that would not start, at the first look or
     *  later - is tried again while it has tries left (mixerTried). */
    boolean recheck(boolean wantsMixer) {
        return (onMixer && mixerMidCall) || (wantsMixer && !onMixer && mixerTriesLeft > 0);
    }

    @Override
    public String toString() {
        return "CallPath{asked=" + asked + ", startedOnMixer=" + startedOnMixer + ", onMixer=" + onMixer
                + ", mixerMidCall=" + mixerMidCall + ", lastMode=" + lastMode + ", micKept=" + micKept
                + ", mixerTriesLeft=" + mixerTriesLeft + "}";
    }
}
