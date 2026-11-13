package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The messenger copy's policy (PolicyUse): registered when asked, held by the
 * captures reading through it, let go only when none does - the two races
 * Voip had, as sequences.
 */
public class PolicyUseTest {

    /** Android's registry, as a list of what is registered. */
    private static final class Fake implements PolicyUse.Registry<String> {
        final List<String> registered = new ArrayList<>();
        int made;

        @Override
        public String register() {
            String p = "policy" + ++made;
            registered.add(p);
            return p;
        }

        @Override
        public void unregister(String policy) {
            registered.remove(policy);
        }
    }

    private final Fake android = new Fake();
    private final PolicyUse<String> use = new PolicyUse<>(android);

    @Test
    public void armedOnceAndLetGoWhenNothingHoldsIt() throws Exception {
        assertNull(use.armed());
        String p = use.arm();
        assertSame(p, use.arm());
        assertEquals(List.of(p), android.registered);
        assertTrue(use.disarmIfIdle());
        assertNull(use.armed());
        assertTrue(android.registered.isEmpty());
        // Nothing registered: nothing to let go, and nothing in the way.
        assertTrue(use.disarmIfIdle());
    }

    @Test
    public void aCaptureReadingThroughItKeepsItUntilItStops() throws Exception {
        use.arm();
        String held = use.claim(false);
        assertFalse(use.disarmIfIdle());
        assertEquals(List.of(held), android.registered);
        use.release();
        assertTrue(use.disarmIfIdle());
        assertTrue(android.registered.isEmpty());
    }

    /** A phone call through the mixer, flipped to the modem's path and back:
     *  the second mixer capture handed out while the first's end let the
     *  policy go. It finds none as it starts, and registers its own - where
     *  it read through one unregistered under it. */
    @Test
    public void aPhoneCaptureHandedOutBeforeADisarmStillHasAPolicyAsItStarts() throws Exception {
        String first = use.claim(true);
        use.release();
        String handedOut = use.arm();
        assertTrue(use.disarmIfIdle());
        assertFalse(android.registered.contains(handedOut));
        String live = use.claim(true);
        assertEquals(List.of(live), android.registered);
        assertFalse(live.equals(first));
        assertFalse(use.disarmIfIdle());
    }

    /** The app emptied the messenger list as a messenger call began: the
     *  capture handed out finds the policy gone, and records nothing - the
     *  user's off - rather than read through one unregistered. */
    @Test
    public void aMessengerCaptureAfterTheListEmptiedGetsNone() throws Exception {
        use.arm();
        assertTrue(use.disarmIfIdle());
        assertNull(use.claim(false));
        assertTrue(android.registered.isEmpty());
        // And holds nothing: the next disarm is not kept waiting.
        assertTrue(use.disarmIfIdle());
    }

    @Test
    public void twoCapturesBothStopBeforeItGoes() throws Exception {
        use.claim(true);
        use.claim(true);
        assertEquals(1, android.made);
        use.release();
        assertFalse(use.disarmIfIdle());
        use.release();
        assertTrue(use.disarmIfIdle());
        // A release too many changes nothing.
        use.release();
        use.arm();
        use.claim(false);
        assertFalse(use.disarmIfIdle());
    }

    @Test
    public void aRefusedRegistrationHoldsNothing() {
        PolicyUse<String> refused = new PolicyUse<>(new PolicyUse.Registry<String>() {
            @Override
            public String register() throws Exception {
                throw new IllegalStateException("registerAudioPolicyStatic returned -1");
            }

            @Override
            public void unregister(String policy) {
            }
        });
        try {
            refused.claim(true);
        } catch (Exception expected) {
            // As Voip.newPhoneCapture: the call is recorded as any other.
        }
        assertNull(refused.armed());
        assertTrue(refused.disarmIfIdle());
    }
}
