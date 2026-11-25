package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

/** "Display over other apps" allowed and taken back for the app's button
 *  (Overlay), each answer what the op reads afterwards. */
public class OverlayTest {

    /** An op that takes what it is set to - or [refuses], as an OPPO phone
     *  with its Developer options switch off does the shell. */
    private static final class FakeOp implements Overlay.Op {
        String mode = "default";
        boolean refuses;
        boolean unreadable;
        final List<String> sets = new ArrayList<>();

        @Override
        public @Nullable String set(String mode) {
            sets.add(mode);
            if (!refuses) {
                this.mode = mode;
            }
            return "";
        }

        @Override
        public @Nullable String get() {
            return unreadable ? null : "SYSTEM_ALERT_WINDOW: " + mode + "; rejectTime=+1s957ms ago";
        }
    }

    @Test
    public void allowedAndTakenBack() {
        FakeOp op = new FakeOp();
        assertTrue(Overlay.set(1, op));
        assertTrue(Overlay.set(0, op));
        // Back to what an app never given it has, not denied.
        assertEquals(Arrays.asList("allow", "default"), op.sets);
    }

    @Test
    public void anOpThatDoesNotReadAsAskedIsSaidSo() {
        FakeOp op = new FakeOp();
        op.refuses = true;
        assertFalse(Overlay.set(1, op));
        op.mode = "allow";
        assertFalse(Overlay.set(0, op));
        op.refuses = false;
        op.unreadable = true;
        // Unread proves neither.
        assertFalse(Overlay.set(1, op));
        assertFalse(Overlay.set(0, op));
    }

    @Test
    public void onlyAnExplicitByteWrites() {
        FakeOp op = new FakeOp();
        op.mode = "allow";
        // A byte that never came reads as -1.
        assertFalse(Overlay.set(-1, op));
        assertFalse(Overlay.set(2, op));
        assertEquals(Collections.emptyList(), op.sets);
        assertEquals("allow", op.mode);
    }
}
