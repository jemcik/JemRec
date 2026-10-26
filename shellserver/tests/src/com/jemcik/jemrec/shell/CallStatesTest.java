package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;

/**
 * The daemon's call states: what a run of telephony's events means - which
 * calls started, which way they went, and when they ended.
 */
public class CallStatesTest {

    /** Notes what the states did; the end check runs when a test says. */
    private static final class World implements CallStates.Effects {
        final List<String> did = new ArrayList<>();
        boolean audioInCall;
        boolean checkScheduled;

        @Override
        public void callStarted(boolean incoming) {
            did.add(incoming ? "start in" : "start out");
        }

        @Override
        public void callEnded() {
            did.add("end");
        }

        /** End checks scheduled and not yet cancelled: never more than one,
         *  or a stale one would end the next call. */
        int checksPending;

        @Override
        public void scheduleEndCheck(long delayMs) {
            assertEquals(CallStates.END_CHECK_MS, delayMs);
            checkScheduled = true;
            checksPending++;
        }

        @Override
        public void cancelEndCheck() {
            checkScheduled = false;
            if (checksPending > 0) {
                checksPending--;
            }
        }

        @Override
        public boolean audioInCall() {
            return audioInCall;
        }

        /** Telecom's answer to "is a phone call up?": null, it cannot be
         *  asked - as everything behaved before it was asked. */
        Boolean phoneCallInTelecom;

        @Override
        public Boolean phoneCallInTelecom() {
            return phoneCallInTelecom;
        }

        @Override
        public void callGoesOn() {
            did.add("goes on");
        }

        @Override
        public void log(String message) {
        }
    }

    private final World world = new World();
    private final CallStates states = new CallStates(world);

    /** The scheduled end check comes due. */
    private void checkDue() {
        assertTrue("no end check was scheduled", world.checkScheduled);
        world.checkScheduled = false;
        states.endCheck();
    }

    /** A ring, as Main.CallWatch hands it on: not if it is another app's. */
    private void ring() {
        if (!states.appsRinging()) {
            states.ringing();
        }
    }

    /** An off-hook, the same way. */
    private void pickUp() {
        if (!states.appsOffHook()) {
            states.offHook();
        }
    }

    private void assertDid(String... what) {
        assertEquals(Arrays.asList(what), world.did);
    }

    @Test
    public void anIncomingCall() {
        states.ringing();
        states.offHook();
        states.idle();
        checkDue();
        assertDid("start in", "end");
    }

    @Test
    public void anOutgoingCall() {
        states.offHook();
        states.idle();
        checkDue();
        assertDid("start out", "end");
    }

    @Test
    public void aMissedCallLeavesNoRingForTheNextOutgoingOne() {
        states.ringing();
        states.idle();
        assertFalse(world.checkScheduled);
        states.offHook();
        assertDid("start out");
    }

    @Test
    public void anIdleBeforeAnyCallIsIgnored() {
        // The state handed over at registration.
        states.idle();
        assertFalse(world.checkScheduled);
        assertDid();
    }

    @Test
    public void anIdleWhileTheAudioIsStillInACallIsNotTheEnd() {
        // Google Phone: idle mid-call, and it stays idle.
        states.offHook();
        states.idle();
        world.audioInCall = true;
        checkDue();
        assertDid("start out", "goes on");
        world.audioInCall = false;
        checkDue();
        assertDid("start out", "goes on", "end");
    }

    @Test
    public void offHookBeforeTheEndCheckIsTheSameCallGoingOn() {
        states.offHook();
        states.idle();
        states.offHook();
        assertFalse("the end check should be off", world.checkScheduled);
        // An end check already on its way when it was taken back does nothing.
        states.endCheck();
        assertFalse(world.did.contains("end"));
    }

    @Test
    public void whatIsUpCanBeAsked() {
        // Main asks, when a messenger call gives way to a phone call.
        assertFalse(states.inCall());
        states.ringing();
        states.offHook();
        assertTrue(states.inCall());
        assertTrue(states.rang());
        states.idle();
        checkDue();
        assertFalse(states.inCall());
    }

    @Test
    public void aCallRingingInsideTheEndCheckIsIncoming() {
        // Measured on the emulator, 2026-10-21: an outgoing call ended, the
        // next call rang inside the two-second end check, and the check's
        // end then forgot that ring - the incoming call was recorded as
        // outgoing, and the saver took the call before for it.
        states.offHook();
        states.idle();
        states.ringing();
        checkDue();
        states.offHook();
        assertDid("start out", "end", "start in");
    }

    @Test
    public void aSecondIdleReplacesTheEndCheckRatherThanAddingOne() {
        states.offHook();
        states.idle();
        states.idle();
        assertEquals(1, world.checksPending);
    }

    @Test
    public void anOutgoingCallDidNotRing() {
        states.offHook();
        assertFalse(states.rang());
    }

    // --- another app's calls through Telecom: Google Meet's ------------------

    @Test
    public void aMeetCallIsNoPhoneCall() {
        // The Honor, 2026-10-26: the state rang and went off-hook for Meet's
        // calls, and each was recorded as a phone call.
        world.phoneCallInTelecom = false;
        ring();
        pickUp();
        states.idle();
        assertFalse(states.inCall());
        assertFalse(world.checkScheduled);
        assertDid();
    }

    @Test
    public void aPhoneCallBackAfterADeclinedMeetCallIsItsOwnCall() {
        // The same day: a Meet call the other person declined, and their phone
        // call back, which rang before the state ever went idle - one
        // recording of both, and their side of the phone call not in it.
        world.phoneCallInTelecom = false;
        pickUp();
        world.phoneCallInTelecom = true;
        ring();
        // Meet's call ends while theirs rings: the state goes on ringing.
        pickUp();
        states.idle();
        checkDue();
        assertDid("start in", "end");
    }

    @Test
    public void aPhoneCallThatEndsIntoAMeetCallEndsThere() {
        // Hung up for a Meet call that goes on: the state stays off-hook for
        // it, and only the audio mode changes.
        world.phoneCallInTelecom = true;
        ring();
        pickUp();
        world.audioInCall = true;
        world.phoneCallInTelecom = false;
        states.audioModeChanged();
        checkDue();
        assertDid("start in", "end");
    }

    @Test
    public void aPhoneCallHungUpIntoAMessengerCallEndsThere() {
        // WhatsApp's call is not Telecom's, so the state does go idle - but
        // its audio mode is a call's, which kept this one recording on.
        world.phoneCallInTelecom = true;
        pickUp();
        states.idle();
        world.audioInCall = true;
        world.phoneCallInTelecom = false;
        checkDue();
        assertDid("start out", "end");
    }

    @Test
    public void aModeChangeInAPhoneCallIsNotItsEnd() {
        world.phoneCallInTelecom = true;
        pickUp();
        world.audioInCall = true;
        states.audioModeChanged();
        assertFalse(world.checkScheduled);
        states.idle();
        checkDue();
        assertTrue("still in the call: no end yet", states.inCall());
        assertDid("start out", "goes on");
    }

    @Test
    public void aModeChangeDuringAnEndCheckAddsNone() {
        world.phoneCallInTelecom = true;
        pickUp();
        states.idle();
        world.phoneCallInTelecom = false;
        states.audioModeChanged();
        assertEquals(1, world.checksPending);
    }

    @Test
    public void whenTelecomCannotBeAskedEveryCallIsAPhoneCall() {
        ring();
        pickUp();
        world.audioInCall = true;
        states.audioModeChanged();
        states.idle();
        checkDue();
        assertDid("start in", "goes on");
    }

    @Test
    public void aMeetCallThatRangIsIncomingAndOneThatDidNotIsOutgoing() {
        // Meet's ring and in-call notifications name the caller differently
        // (the Honor, 2026-10-26), so only the ring Telecom reports says.
        world.phoneCallInTelecom = false;
        ring();
        pickUp();
        assertEquals(Boolean.TRUE, states.appCallIncoming());
        states.idle();
        assertEquals("nothing is up after an idle", null, states.appCallIncoming());
        pickUp();
        assertEquals(Boolean.FALSE, states.appCallIncoming());
    }

    @Test
    public void aMeetRingDeclinedIsNotTheNextCallsRing() {
        world.phoneCallInTelecom = false;
        ring();
        states.idle();
        pickUp();
        assertEquals(Boolean.FALSE, states.appCallIncoming());
    }

    @Test
    public void aPhoneCallsRingIsNotAnAppCallsRing() {
        world.phoneCallInTelecom = true;
        ring();
        assertEquals(null, states.appCallIncoming());
        assertTrue(states.rang());
    }

    @Test
    public void aMeetRingIsNotAPhoneCallsRing() {
        // Main hands a ring on - to this watch and to the answering machine -
        // only when appsRinging says no: the machine could not play a
        // greeting into an app's call. The watch's side is what shows here.
        world.phoneCallInTelecom = false;
        ring();
        assertFalse(states.rang());
    }

    @Test
    public void aMeetCallFoundBeforeItsOffHookIsHeardIsIncoming() {
        // The messenger watch can find the call answered before telephony's
        // off-hook reaches the call watch's thread.
        world.phoneCallInTelecom = false;
        ring();
        assertEquals(Boolean.TRUE, states.appCallIncoming());
        pickUp();
        assertEquals(Boolean.TRUE, states.appCallIncoming());
    }
}
