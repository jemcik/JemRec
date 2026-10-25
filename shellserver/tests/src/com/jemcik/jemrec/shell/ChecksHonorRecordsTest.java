package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.Test;

/**
 * The daemon's background-stop rule on records the Honor itself printed
 * (MagicOS 11, 2026-10-25), joined into one line the way Main.slurp reads a
 * dump. Another app's records, kept for what they show, carry a made-up
 * package name (com.example.chat): which apps are on the phone is no one's
 * business.
 */
public class ChecksHonorRecordsTest {

    private static final BooleanSupplier RUNNING = () -> false;
    private static final BooleanSupplier STOPPED = () -> true;

    private static String dump(String name) throws IOException {
        InputStream stream = ChecksHonorRecordsTest.class.getResourceAsStream("/" + name);
        if (stream == null) {
            throw new IllegalStateException(name + " is not on the test classpath (shellserver/tests/fixtures)");
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return reader.lines().collect(Collectors.joining(" | ")).trim();
        }
    }

    @Test
    public void jemrecsOwnRecordsFindTheKillWhileItWorked() throws IOException {
        // Sixteen exits: app updates on top, and the 13:52 iAwareR kill at
        // importance 125, a foreground service running.
        assertEquals("2026-10-25 13:52:29.117",
                Checks.backgroundKillStamp(dump("honor-exit-info-jemrec.txt"), RUNNING));
    }

    @Test
    public void aWeekOfAnotherAppsRoutineCleanUpsIsNoStop() throws IOException {
        // Sixteen exits over a week, eleven of them reason 13, every one at
        // importance 400: Android cleaning up an idle app. Nothing to tell -
        // the false alarm the rule was changed for.
        assertNull(Checks.backgroundKillStamp(dump("honor-exit-info-idle-app.txt"), RUNNING));
    }

    @Test
    public void theSameAppLeftStoppedIsAStop() throws IOException {
        // Its newest exit, an iAwareR clean-up, left it stopped=true.
        assertEquals("2026-10-25 16:38:30.194",
                Checks.backgroundKillStamp(dump("honor-exit-info-idle-app.txt"), STOPPED));
    }

    @Test
    public void stoppedIsReadFromTheRealUserZeroLines() throws IOException {
        String running = dump("honor-package-user0-running.txt");
        String stopped = dump("honor-package-user0-stopped.txt");
        assertFalse(Checks.packageStopped(running));
        assertTrue(Checks.packageStopped(stopped));
        // The Honor also prints iAwareStopped= on that line, which is not it.
        // On these two lines the flags agree, so each is read again with its
        // iAwareStopped turned the other way: stopped= still decides.
        String runningMarked = running.replace("iAwareStopped=false", "iAwareStopped=true");
        String stoppedUnmarked = stopped.replace("iAwareStopped=true", "iAwareStopped=false");
        assertNotEquals(running, runningMarked);
        assertNotEquals(stopped, stoppedUnmarked);
        assertFalse(Checks.packageStopped(runningMarked));
        assertTrue(Checks.packageStopped(stoppedUnmarked));
    }
}
