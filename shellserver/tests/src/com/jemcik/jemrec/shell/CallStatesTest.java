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

        @Override
        public void scheduleEndCheck(long delayMs) {
            assertEquals(CallStates.END_CHECK_MS, delayMs);
            checkScheduled = true;
        }

        @Override
        public void cancelEndCheck() {
            checkScheduled = false;
        }

        @Override
        public boolean audioInCall() {
            return audioInCall;
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
}
