package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChecksTest {

    @Test
    public void theUidsOwnLineDecides() {
        // As Main.slurp joins it, and as the device prints it.
        assertTrue(Checks.uidAllowed("Uid mode: MANAGE_ONGOING_CALLS: allow | MANAGE_ONGOING_CALLS: allow; time=+1m"));
        assertTrue(Checks.uidAllowed("Uid mode: MANAGE_ONGOING_CALLS: allow\nMANAGE_ONGOING_CALLS: default"));
        // The package allowed, the uid not: not enough - measured on an Honor.
        assertFalse(Checks.uidAllowed("MANAGE_ONGOING_CALLS: allow; time=+24m15s663ms ago"));
        assertFalse(Checks.uidAllowed("Uid mode: MANAGE_ONGOING_CALLS: default | MANAGE_ONGOING_CALLS: allow"));
        assertFalse(Checks.uidAllowed("No operations."));
        assertFalse(Checks.uidAllowed(null));
    }

    @Test
    public void onlyJemRecAndTheShellAreLetIn() {
        int app = 10083;
        assertTrue(Checks.callerAllowed(app, app));
        assertTrue(Checks.callerAllowed(Checks.SHELL_UID, app));
        assertTrue(Checks.callerAllowed(0, app));
        assertFalse(Checks.callerAllowed(10200, app));
        // Unknown either side: the handshake decides, as before.
        assertTrue(Checks.callerAllowed(-1, app));
        assertTrue(Checks.callerAllowed(10200, -1));
    }
}
