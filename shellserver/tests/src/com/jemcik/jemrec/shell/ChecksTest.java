package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
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

    /** Cut from the Honor's dump (Snapdragon SM8850, Android 17). */
    private static final String HONOR_POLICY = String.join("\n",
            " Available output devices (3):",
            "  1. Port ID: 1; \"earpiece\"; {AUDIO_DEVICE_OUT_EARPIECE, @:}",
            "  3. Port ID: 3; \"telephony_tx\"; {AUDIO_DEVICE_OUT_TELEPHONY_TX, @:}",
            " Available input devices (6):",
            "  2. Port ID: 5; \"telephony_rx\"; {AUDIO_DEVICE_IN_TELEPHONY_RX, @:}",
            "      9. \"in_call_music\"; 0x10000 (AUDIO_OUTPUT_FLAG_INCALL_MUSIC)",
            "       - Supported devices (1):",
            "        1. Port ID: 3; \"telephony_tx\"; {AUDIO_DEVICE_OUT_TELEPHONY_TX, @:}");

    /** Cut from the Android 17 emulator's: the devices, no in-call music output. */
    private static final String EMULATOR_POLICY = String.join("\n",
            " Available output devices (2):",
            "  1. Port ID: 2; \"Speaker\"; {AUDIO_DEVICE_OUT_SPEAKER, @:}",
            "  2. Port ID: 5; \"Telephony Tx\"; {AUDIO_DEVICE_OUT_TELEPHONY_TX, @:}",
            " Available input devices (4):",
            "      2. \"telephony_tx\"; 0x0000 (AUDIO_OUTPUT_FLAG_NONE)");

    @Test
    public void inCallMusicNeedsTheOutputAndTheDevice() {
        assertEquals(1, Checks.inCallMusic(HONOR_POLICY));
        assertEquals(0, Checks.inCallMusic(EMULATOR_POLICY));
        // The flag declared, but the uplink device not available now.
        assertEquals(0, Checks.inCallMusic(HONOR_POLICY.replace(
                "  3. Port ID: 3; \"telephony_tx\"; {AUDIO_DEVICE_OUT_TELEPHONY_TX, @:}\n", "")));
        // A dump that failed, or reads like no other: not a "no", which
        // would block the answering machine as certain.
        assertEquals(-1, Checks.inCallMusic(""));
        assertEquals(-1, Checks.inCallMusic("Can't find service: media.audio_policy"));
        assertEquals(-1, Checks.inCallMusic(null));
    }

    /** The Honor's audio dump mid-greeting: the probe's pid 31494 on the
     *  in-call music output, and an earlier track of it in a log. */
    private static final String FLINGER = String.join("\n",
            "Output thread 0xb400006e5db29040, name AudioOut_25, tid 3282, type 0 (MIXER):",
            "  AudioStreamOut: 0xb400006fdda12550 flags 0 (AUDIO_OUTPUT_FLAG_NONE)",
            "    Type     Id Active Client(pid/uid) Session Port Id S  Flags",
            "            184     no   31494/   2000    1097     196 T  0x000",
            "Output thread 0xb400006e5dbe9060, name AudioOut_35, tid 3286, type 0 (MIXER):",
            "  AudioStreamOut: 0xb400006fdda148f0 flags 0x10000 (AUDIO_OUTPUT_FLAG_INCALL_MUSIC)",
            "  1 Tracks of which 1 are active",
            "    Type     Id Active Client(pid/uid) Session Port Id S  Flags",
            "            185    yes   31494/   2000    1097     197 A  0x000",
            "  Local log:",
            "   10-22 12:40:51.186 AT::add       (0xb400006e0da13370)         185     no   77777/   2000");

    @Test
    public void theGreetingMustBeActiveOnTheInCallMusicOutput() {
        assertEquals(1, Checks.trackOutput(FLINGER, 31494));
        // Active, but on the plain uplink output the caller never hears.
        assertEquals(0, Checks.trackOutput(
                FLINGER.replace("185    yes", "185     no").replace("184     no", "184    yes"), 31494));
    }

    @Test
    public void aTrackNotFoundProvesNothing() {
        // Someone else's track; a pid only in the log; no dump at all; a
        // table this cannot read: none of them says the greeting went astray.
        assertEquals(-1, Checks.trackOutput(FLINGER, 12345));
        assertEquals(-1, Checks.trackOutput(FLINGER, 77777));
        assertEquals(-1, Checks.trackOutput(null, 31494));
        assertEquals(-1, Checks.trackOutput("", 31494));
        assertEquals(-1, Checks.trackOutput(FLINGER.replace("31494/   2000", "31494"), 31494));
    }

    private static short[] stereo(int frames, double hzLeft, double hzRight, double amplitude) {
        short[] pcm = new short[frames * 2];
        for (int i = 0; i < frames; i++) {
            pcm[2 * i] = (short) (amplitude * Math.sin(2 * Math.PI * hzLeft * i / 48000));
            pcm[2 * i + 1] = (short) (amplitude * Math.sin(2 * Math.PI * hzRight * i / 48000));
        }
        return pcm;
    }

    @Test
    public void theCheckToneIsFoundOnItsChannelOnly() {
        short[] pcm = stereo(1024, 1400, 300, 8000);
        assertTrue(Checks.toneShare(pcm, 2, 0, 1024, 1400, 48000) > 0.8);
        assertTrue(Checks.toneShare(pcm, 2, 1, 1024, 1400, 48000) < 0.1);
        // The 1000 Hz beep is not the check tone.
        assertTrue(Checks.toneShare(stereo(1024, 1000, 0, 8000), 2, 0, 1024, 1400, 48000) < 0.1);
        assertEquals(0, Checks.toneShare(new short[2048], 2, 0, 1024, 1400, 48000), 0);
    }

    @Test
    public void aMessageIsNamedWithItsStart() {
        assertEquals("jemrec_rec_1792661198533_msg8120_in.dat",
                Checks.messageRecfile("jemrec_rec_1792661198533_in.dat", 1_000_008_120L, 1_000_000_000L, false, false));
        assertEquals("jemrec_rec_1792661198533_msgnone_in.dat",
                Checks.messageRecfile("jemrec_rec_1792661198533_in.dat", 0, 1_000_000_000L, true, false));
        // A start before the capture's first sample is the first sample.
        assertEquals("jemrec_rec_5_msg0_out.dat", Checks.messageRecfile("jemrec_rec_5_out.dat", 900, 1_000, false, false));
        // The direction tag the app reads is still where it was.
        assertTrue(Checks.messageRecfile("jemrec_rec_5_in.dat", 2_000, 1_000, false, false).contains("_in."));
    }

    @Test
    public void aGreetingThatCouldNotPlayIsSaidSo() {
        // Not "nothing left": the app tells the user the greeting failed.
        assertEquals("jemrec_rec_5_msgfail_in.dat", Checks.messageRecfile("jemrec_rec_5_in.dat", 0, 1_000, false, true));
    }

    @Test
    public void aCallWithNoKnownStartIsKeptWhole() {
        // Taken over before anyone spoke, or the greeting never played: not
        // "nothing left" - the whole recording is the call.
        assertEquals("jemrec_rec_5_msg0_in.dat", Checks.messageRecfile("jemrec_rec_5_in.dat", 0, 1_000, false, false));
    }

    @Test
    public void theGreetingsOwnLoudBlocks() {
        int block = 960;
        byte[] pcm = new byte[block * 2 * 4];
        // Block 2 holds a tone at about -18 dBFS; the rest is silence.
        for (int i = 2 * block; i < 3 * block; i++) {
            short v = (short) (4000 * Math.sin(2 * Math.PI * 1000 * i / 48000.0));
            pcm[2 * i] = (byte) v;
            pcm[2 * i + 1] = (byte) (v >> 8);
        }
        boolean[] loud = Checks.loudBlocks(pcm, block, 32768 * Math.pow(10, -45 / 20.0));
        assertArrayEquals(new boolean[]{false, false, true, false}, loud);
        assertTrue(Checks.loudAround(loud, 0, 2));
        assertFalse(Checks.loudAround(loud, 0, 1));
        // Past the end of what was written, nothing plays.
        assertFalse(Checks.loudAround(loud, 40, 15));
        assertFalse(Checks.loudAround(new boolean[0], 0, 15));
    }

    @Test
    public void rmsCoversEveryChannel() {
        short[] pcm = {1000, -1000, 1000, -1000};
        assertEquals(1000, Checks.rms(pcm, 2, 2), 1e-9);
        assertEquals(0, Checks.rms(new short[0], 2, 0), 0);
    }

    @Test
    public void aChannelsLevelIsItsOwn() {
        // Left the phone's own side, silent while muted; right the caller.
        short[] pcm = {0, 1000, 0, -1000, 0, 1000};
        assertEquals(0, Checks.channelRms(pcm, 2, 0, 3), 0);
        assertEquals(1000, Checks.channelRms(pcm, 2, 1, 3), 1e-9);
        assertEquals(0, Checks.channelRms(new short[0], 2, 1, 0), 0);
    }

    @Test
    public void aMessengerCallGoesOnThroughAPhoneRing() {
        // In communication: the call, as ever.
        assertTrue(Checks.messengerCallGoesOn(Checks.MODE_IN_COMMUNICATION, false));
        // A phone call ringing over it took the mode (1, the ringtone's),
        // and the app still talks: it goes on - the Telegram recording that
        // was cut in two at the ring.
        assertTrue(Checks.messengerCallGoesOn(1, true));
        // Over: the mode gone and the app silent.
        assertFalse(Checks.messengerCallGoesOn(0, false));
        // A phone call actually up: it is the call now, whatever the app does.
        assertFalse(Checks.messengerCallGoesOn(Checks.MODE_IN_CALL, true));
    }

    @Test
    public void aTakenOverCallIsNamedWithItsStart() {
        assertEquals("jemrec_rec_5_from7400_in.dat", Checks.takenRecfile("jemrec_rec_5_in.dat", 8_400, 1_000));
        assertEquals("jemrec_rec_5_from0_out.dat", Checks.takenRecfile("jemrec_rec_5_out.dat", 0, 1_000));
    }

    @Test
    public void theBeepIsAGentleDingDong() {
        int rate = 48000;
        byte[] pcm = Checks.chime(rate, 4000);
        short[] s = new short[pcm.length / 2];
        for (int i = 0; i < s.length; i++) {
            s[i] = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
        }
        assertEquals((int) (0.85 * rate), s.length);
        int peak = 0;
        for (short v : s) {
            peak = Math.max(peak, Math.abs(v));
        }
        // As loud as the old beep at its loudest, and no louder.
        assertEquals(4000, peak);
        // No click at either end.
        assertTrue(Math.abs(s[0]) < 50 && Math.abs(s[s.length - 1]) < 50);
        // E first, then C: a falling third.
        int head = rate / 5;
        assertTrue(Checks.toneShare(s, 1, 0, head, 659.25, rate) > Checks.toneShare(s, 1, 0, head, 523.25, rate));
        short[] tail = java.util.Arrays.copyOfRange(s, rate / 2, s.length);
        assertTrue(Checks.toneShare(tail, 1, 0, tail.length, 523.25, rate) > Checks.toneShare(tail, 1, 0, tail.length, 659.25, rate));
    }
}
