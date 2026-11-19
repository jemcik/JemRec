package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Where a phone call's capture stands, and what a capture made anew after a
 * failed read takes up (CallPath, PhoneCapture, RecordingSession's maker).
 * Each case here was a way the capture made anew lost the call's state
 * (reviews, 2026-11-18).
 */
public class CallPathTest {

    private static final int COMMUNICATION = Checks.MODE_IN_COMMUNICATION;

    /** Nothing known at off-hook: the call capture, and the mode asked. */
    @Test
    public void atOffHookItAsks() {
        CallPath p = CallPath.OFF_HOOK;
        assertFalse(p.startOnMixer());
        assertFalse(p.decideAgain());
        assertTrue(p.micKept);
        assertEquals(-1, p.lastMode);
    }

    /** A call that began on the mixer is made anew on it, and decided
     *  again - not asked as at off-hook, which put any "communication" call
     *  on the mixer past the checks for another app's call. */
    @Test
    public void aCallThatBeganOnTheMixerIsMadeAnewOnIt() {
        CallPath p = CallPath.OFF_HOOK.asked(COMMUNICATION, true).swapped(true, false);
        assertTrue(p.startedOnMixer);
        assertTrue(p.startOnMixer());
        assertTrue(p.decideAgain());
        assertFalse("not looked at each second", p.mixerMidCall);
        assertFalse(p.recheck(true));
    }

    /** The first look says the mixer and the copy is refused: still a call
     *  that began on the mixer, on the call capture for now, and the mixer
     *  tried again each second - it was never asked for again, and the
     *  call's other side, which VOICE_CALL does not hear in such a call, was
     *  lost for the rest of it. */
    @Test
    public void aMixerRefusedAtTheFirstLookIsTriedAgain() {
        CallPath p = CallPath.OFF_HOOK.asked(COMMUNICATION, true);
        assertTrue(p.startedOnMixer);
        assertFalse("not on it until a swap puts it there", p.onMixer);
        assertFalse(p.startOnMixer());
        assertTrue(p.recheck(true));
        // Asked again with the mode, it is a call for the mixer.
        assertTrue(Checks.mixerAfterModeChange(p.onMixer, p.startedOnMixer, COMMUNICATION, 34, null, true));
        // Had at a try: on it, and not looked at again.
        CallPath had = p.mixerTried().swapped(true, true);
        assertTrue(had.onMixer);
        assertFalse(had.mixerMidCall);
        assertFalse(had.recheck(true));
    }

    /** The tries run out, and start again each time the mixer is had and
     *  for each capture made anew. */
    @Test
    public void theTriesRunOutAndStartAgain() {
        CallPath p = CallPath.OFF_HOOK.asked(COMMUNICATION, true);
        for (int i = 0; i < CallPath.MIXER_TRIES; i++) {
            assertTrue("try " + i, p.recheck(true));
            p = p.mixerTried();
        }
        assertFalse("not for ever", p.recheck(true));
        assertEquals(0, p.mixerTried().mixerTriesLeft);
        assertEquals(CallPath.MIXER_TRIES, p.swapped(true, true).mixerTriesLeft);
        assertEquals(CallPath.MIXER_TRIES, p.madeAnew(false).mixerTriesLeft);
        assertEquals("the call capture does not refill them", 0, p.swapped(false, true).mixerTriesLeft);
    }

    /** One put on the call capture at its start, its mode later
     *  "communication" with another app's call up, stays on the call
     *  capture when made anew - and is decided again with the checks. */
    @Test
    public void aCallKeptOffTheMixerIsMadeAnewOffIt() {
        CallPath p = CallPath.OFF_HOOK.asked(Checks.MODE_IN_CALL, false).mode(COMMUNICATION);
        assertFalse(p.startOnMixer());
        assertTrue(p.decideAgain());
        assertFalse(p.startedOnMixer);
        assertEquals(COMMUNICATION, p.lastMode);
    }

    /** Put on the mixer mid-call: made anew on it, and still looked at
     *  each second. */
    @Test
    public void aCallMovedToTheMixerMidCallIsStillLookedAt() {
        CallPath p = CallPath.OFF_HOOK.asked(Checks.MODE_IN_CALL, false).swapped(true, true);
        assertTrue(p.mixerMidCall);
        CallPath anew = p.madeAnew(true);
        assertTrue(anew.onMixer);
        assertTrue(anew.mixerMidCall);
        assertTrue(anew.recheck(true));
    }

    /** A read failed before the first look answered: the next capture asks
     *  as at off-hook. */
    @Test
    public void madeAnewBeforeTheFirstLookItAsks() {
        assertFalse(CallPath.OFF_HOOK.mode(COMMUNICATION).decideAgain());
    }

    /** The microphone the guard gave back stays the call's, through swaps
     *  and captures made anew. */
    @Test
    public void theMicrophoneGivenBackStaysGiven() {
        CallPath p = CallPath.OFF_HOOK.asked(COMMUNICATION, true).micGone();
        assertFalse(p.micKept);
        assertFalse(p.swapped(false, true).swapped(true, true).micKept);
        assertFalse(p.madeAnew(true).micKept);
        assertFalse(p.mode(Checks.MODE_IN_CALL).micKept);
    }

    /** Made anew where the mixer would not come: on the call capture for
     *  now, the call still one that began on the mixer, and the mixer tried
     *  again each second, a few times - a call left on a capture that hears
     *  nothing (the S23's Wi-Fi Calling) was silent for the rest. */
    @Test
    public void aMixerThatWouldNotComeIsTriedAgain() {
        CallPath p = CallPath.OFF_HOOK.asked(COMMUNICATION, true).madeAnew(false);
        assertFalse(p.onMixer);
        assertTrue("still a call that began on the mixer", p.startedOnMixer);
        assertTrue(Checks.mixerAfterModeChange(p.onMixer, p.startedOnMixer, COMMUNICATION, 34, null, true));
        assertTrue(p.recheck(true));
        assertFalse("not when it is not wanted", p.recheck(false));
    }

    /** Made anew on the mixer, everything else is as it was: the call's
     *  facts, the microphone, the mode - and its tries, full again. */
    @Test
    public void madeAnewOnTheMixerKeepsEverything() {
        CallPath given = CallPath.OFF_HOOK.asked(COMMUNICATION, true).swapped(true, false).micGone();
        assertEquals(given.toString(), given.madeAnew(true).toString());
    }

    /** The first look: a "communication" call on Android 14 began on the
     *  mixer before the mixer is had, and a refusal is tried again, a try
     *  spent each time, until the tries are gone and it is said; one on the
     *  voice path, or before Android 14, never wants it. */
    @Test
    public void theFirstLookDecidesBeforeTheMixerIsHad() {
        CallPath p = CallPath.OFF_HOOK.firstLook(COMMUNICATION, 34);
        assertTrue(p.asked);
        assertTrue(p.startedOnMixer);
        assertFalse(p.onMixer);
        assertEquals(CallPath.Next.TRY_AGAIN, p.afterLook(true));
        for (int i = 0; i < CallPath.MIXER_TRIES; i++) {
            p = p.mixerTried();
        }
        assertEquals(CallPath.Next.WOULD_NOT_COME, p.afterLook(true));
        assertEquals("had: nothing more", CallPath.Next.DONE, p.swapped(true, false).afterLook(true));
        assertFalse(CallPath.OFF_HOOK.firstLook(Checks.MODE_IN_CALL, 34).startedOnMixer);
        assertFalse("no copy before Android 14", CallPath.OFF_HOOK.firstLook(COMMUNICATION, 33).startedOnMixer);
        assertEquals(CallPath.Next.DONE, CallPath.OFF_HOOK.firstLook(Checks.MODE_IN_CALL, 34).afterLook(false));
    }

    /** Put on the mixer by a change mid-call: looked at again each second,
     *  no try spent. */
    @Test
    public void aMidCallMixerIsLookedAtAgainWithoutATry() {
        CallPath p = CallPath.OFF_HOOK.firstLook(Checks.MODE_IN_CALL, 34).swapped(true, true);
        assertEquals(CallPath.Next.LOOK_AGAIN, p.afterLook(true));
    }
}
