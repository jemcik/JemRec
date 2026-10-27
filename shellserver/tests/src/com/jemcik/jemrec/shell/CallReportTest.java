package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Test;

public class CallReportTest {

    private long now = 1_000_000L;

    private CallReport report() {
        return new CallReport("a phone call, incoming", () -> now);
    }

    /** [frames] frames of interleaved stereo, each channel at its own level. */
    private static short[] stereo(int frames, int left, int right) {
        short[] pcm = new short[frames * 2];
        for (int f = 0; f < frames; f++) {
            pcm[2 * f] = (short) ((f % 2 == 0) ? left : -left);
            pcm[2 * f + 1] = (short) ((f % 2 == 0) ? right : -right);
        }
        return pcm;
    }

    @Test
    public void eachWayTheCallWasRecordedHasItsOwnLevels() {
        CallReport r = report();
        r.route(CallReport.Route.CALL_CAPTURE, -1);
        r.chunk(stereo(960, 3277, 3277), 960, 2); // -20 dB both
        now += 4100;
        r.route(CallReport.Route.PHONE_MIXER, 3);
        r.chunk(stereo(960, 1036, 0), 960, 2); // -30 dB, and nothing
        now += 2000;
        r.ended();
        String text = r.text();
        assertTrue(text, text.startsWith("Last call: a phone call, incoming. Recorded for 6.1 s."));
        assertTrue(text, text.contains("    0.0 s  the call capture (VOICE_CALL)\n"
                + "         left -20.0 dB (peak -20.0 dB); right -20.0 dB (peak -20.0 dB)"));
        assertTrue(text, text.contains("    4.1 s  the mixer: a copy of the call's audio, and the microphone, "
                + "audio mode 3\n"
                + "         left (you, the microphone) -30.0 dB (peak -30.0 dB); "
                + "right (the other side, the copy) silent: every sample zero"));
    }

    @Test
    public void aChannelMostlyOfExactZerosSaysSo() {
        CallReport r = report();
        r.route(CallReport.Route.MESSENGER, -1);
        short[] pcm = stereo(100, 1000, 1000);
        // The copy, on the right, mostly missing: what a far side the copy
        // did not reach looks like.
        for (int f = 0; f < 70; f++) {
            pcm[2 * f + 1] = 0;
        }
        r.chunk(pcm, 100, 2);
        String text = r.text();
        assertTrue(text, text.contains("right (the other side, the copy) -35.5 dB (peak -30.3 dB), 70% of it exact zeros"));
        // The other channel had none: nothing said about zeros.
        assertTrue(text, text.contains("left (you, the microphone) -30.3 dB (peak -30.3 dB); "));
    }

    @Test
    public void aWayThatGotNoSoundSaysSoAndAnUnfinishedCallTooSoFar() {
        CallReport r = report();
        r.route(CallReport.Route.CALL_CAPTURE, -1);
        now += 1500;
        String text = r.text();
        assertTrue(text, text.startsWith("Last call: a phone call, incoming. Still being recorded, 1.5 s so far."));
        assertTrue(text, text.contains("no sound reached the recording this way"));
        assertFalse(text, text.contains("Along the way"));
        assertFalse(text, text.contains("What was playing"));
    }

    @Test
    public void nothingRecordedAtAll() {
        CallReport r = report();
        r.ended();
        assertTrue(r.text(), r.text().contains("How it was recorded:\n  nothing was recorded"));
    }

    @Test
    public void notesAndLooksAroundAreKeptToAFew() {
        CallReport r = report();
        for (int i = 0; i < CallReport.MAX_NOTES + 3; i++) {
            now += 100;
            r.note("audio mode " + (i % 2 == 0 ? 3 : 2));
        }
        for (int i = 0; i < CallReport.MAX_SCENES + 2; i++) {
            r.scene(3, true, Collections.emptyList(), Collections.emptyList());
        }
        String text = r.text();
        assertTrue(text, text.contains("    0.1 s  audio mode 3\n"));
        assertTrue(text, text.contains("         and 3 more"));
        assertEquals(text, CallReport.MAX_SCENES, text.split("the copy armed", -1).length - 1);
    }

    @Test
    public void aLookAroundListsWhatPlaysAndCaptures() {
        CallReport r = report();
        now += 3000;
        r.scene(3, false,
                Arrays.asList(CallReport.player(1000, "system", 2, 1, 1, 1, 0, 1),
                        CallReport.player(10192, "com.google.android.apps.tachyon", 2, 1, 3, 13, 0x1800, 2)),
                Collections.singletonList(CallReport.capture(2000, "the shell: this recorder", 1, false, 15)));
        String text = r.text();
        assertTrue(text, text.contains("What was playing and capturing:\n"
                + "    3.0 s  audio mode 3, the copy not armed\n"
                + "         playing: uid 1000 (system): voice call, speech; any app may capture it; AudioTrack; "
                + "flags 0x0; to earpiece\n"
                + "                  uid 10192 (com.google.android.apps.tachyon): voice call, speech; "
                + "NOTHING may capture it; AAudio; flags 0x1800; to speaker\n"
                + "         capturing: uid 2000 (the shell: this recorder): microphone; not silenced; "
                + "from built-in microphone"));
    }

    @Test
    public void aPlayerWhoseFlagsCouldNotBeReadLeavesThemOut() {
        assertEquals("uid 1000 (system): voice call, speech; only the system may capture it; player type unknown; "
                + "to no device Android named", CallReport.player(1000, "system", 2, 1, 2, -1, -1, -1));
    }

    @Test
    public void whoseUidItIs() {
        assertEquals("the shell: this recorder", CallReport.who(2000, 2000, new String[0]));
        assertEquals("system", CallReport.who(1000, 2000, new String[] {"android", "com.android.settings"}));
        assertEquals("phone", CallReport.who(1001, 2000, new String[0]));
        assertEquals("com.whatsapp", CallReport.who(10150, 2000, new String[] {"com.whatsapp"}));
        assertEquals("com.a and 2 more", CallReport.who(10151, 2000, new String[] {"com.a", "com.b", "com.c"}));
        assertEquals("an app", CallReport.who(10152, 2000, new String[0]));
        assertEquals("a system uid", CallReport.who(1068, 2000, new String[0]));
        // Android 12 hides every owner from the shell.
        assertEquals("not named by Android", CallReport.who(-1, 2000, new String[0]));
    }

    @Test
    public void namesForAndroidsNumbers() {
        assertEquals("voice call", CallReport.usage(2));
        assertEquals("usage 99", CallReport.usage(99));
        assertEquals("voice communication", CallReport.source(7));
        assertEquals("voice call", CallReport.source(4));
        assertEquals("NOTHING may capture it", CallReport.capturePolicy(3));
        assertEquals("AAudio", CallReport.playerType(13));
        assertEquals("telephony", CallReport.device(18));
        assertEquals("device 99", CallReport.device(99));
        assertEquals(-120.0, CallReport.db(0), 0);
        assertEquals(0.0, CallReport.db(32768), 1e-9);
    }
}
