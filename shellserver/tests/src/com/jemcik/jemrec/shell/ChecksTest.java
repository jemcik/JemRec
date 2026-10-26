package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChecksTest {

    @Test
    public void aPhoneCallPlayedThroughTheMixerIsRecordedAsAMessengerCall() {
        // Wi-Fi Calling on a Galaxy S23: the call in communication mode.
        assertTrue(Checks.phoneCallInMixer(Checks.MODE_IN_COMMUNICATION, 36));
        // Every other phone call measured: the modem's voice path, as always.
        assertFalse(Checks.phoneCallInMixer(Checks.MODE_IN_CALL, 36));
        // Before Android 14 the shell may not copy a call's audio.
        assertFalse(Checks.phoneCallInMixer(Checks.MODE_IN_COMMUNICATION, 33));
        assertTrue(Checks.phoneCallInMixer(Checks.MODE_IN_COMMUNICATION, Checks.SDK_VOICE_COPY));
        // A mode that does not say a call is up decides nothing.
        assertFalse(Checks.phoneCallInMixer(0, 36));
        assertFalse(Checks.phoneCallInMixer(1, 36));
    }

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
    public void aCableIsAnyPortModeButNone() {
        // Measured on the Honor: a computer, a wall charger, nothing.
        assertTrue(Checks.cableIn(1));
        assertTrue(Checks.cableIn(8));
        assertFalse(Checks.cableIn(0));
        // A port that could not be read is not a cable.
        assertFalse(Checks.cableIn(-1));
    }

    @Test
    public void anyPortWithACableInIsACableIn() {
        assertEquals(1, Checks.portsReading(0, 1));
        assertEquals(8, Checks.portsReading(8, -1));
        assertEquals(0, Checks.portsReading(0, 0));
        // A port that could not be read says nothing; none read, nothing known.
        assertEquals(0, Checks.portsReading(-1, 0));
        assertEquals(-1, Checks.portsReading(-1, -1));
        assertEquals(-1, Checks.portsReading());
    }

    @Test
    public void onlyAChangeOfCableWakesTheApp() {
        // The first reading is how things are.
        assertEquals(null, Checks.cableChange(null, true));
        assertEquals(null, Checks.cableChange(null, false));
        assertEquals(null, Checks.cableChange(true, true));
        assertEquals(null, Checks.cableChange(false, false));
        assertEquals("unplugged", Checks.cableChange(true, false));
        assertEquals("plugged in", Checks.cableChange(false, true));
    }

    @Test
    public void usbDebuggingIsTheAdbFunctionItself() {
        // The Honor plugged into a computer, then unplugged or on a charger.
        assertTrue(Checks.usbAdb("hisuite,mtp,mass_storage,adb"));
        assertFalse(Checks.usbAdb("hisuite,mtp,mass_storage"));
        assertTrue(Checks.usbAdb("adb"));
        assertTrue(Checks.usbAdb("mtp,adb"));
        // Honor's HiSuite bridge, and anything else with the letters in it.
        assertFalse(Checks.usbAdb("hisuite,hdb"));
        assertFalse(Checks.usbAdb("adbx"));
        assertFalse(Checks.usbAdb(""));
        assertFalse(Checks.usbAdb(null));
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

    private static final java.util.function.BooleanSupplier RUNNING = () -> false;
    private static final java.util.function.BooleanSupplier STOPPED = () -> true;

    // A background system kill (reason 13), as the Honor reported it, the
    // newest record - at importance 125, a foreground service running; the
    // older USER_REQUESTED exit below it is no kill.
    private static final String EXIT_INFO_SYSTEM_KILL =
            "  Historical Process Exit for com.jemcik.jemrec: | "
                + "    ApplicationExitInfo #0: | "
                + "      timestamp=2026-10-25 13:52:29.117 pid=21597 realUid=10240 | "
                + "      process=com.jemcik.jemrec reason=13 (OTHER KILLS BY SYSTEM) subreason=0 status=0 | "
                + "      importance=125 pss=0.00 rss=313MB state=empty trace=null | "
                + "      description=iAwareR[SmartClean] -1ms(fg-service) | "
                + "    ApplicationExitInfo #1: | "
                + "      timestamp=2026-10-24 23:35:35.912 pid=7605 realUid=10240 | "
                + "      process=com.jemcik.jemrec reason=10 (USER REQUESTED) subreason=22 status=0 | "
                + "      importance=100 pss=0.00 rss=0.00 state=empty trace=null";

    // Android's routine clean-up of an idle app, as the Honor printed it for
    // 601 others on 2026-10-25.
    private static final String IDLE_KILL =
            "    ApplicationExitInfo #0: | "
                + "      timestamp=2026-10-25 18:00:00.000 pid=3 | "
                + "      process=com.jemcik.jemrec reason=13 (OTHER KILLS BY SYSTEM) subreason=3 (TOO MANY EMPTY PROCS) status=0 | "
                + "      importance=400 pss=0.00 rss=90MB state=empty trace=null | "
                + "      description=too many empty | ";

    @Test
    public void aKillWhileWorkingIsReportedWithItsTimestamp() {
        assertEquals("2026-10-25 13:52:29.117", Checks.backgroundKillStamp(EXIT_INFO_SYSTEM_KILL, RUNNING));
    }

    @Test
    public void anIdleAppCleanedUpIsNotAStopToTellAbout() {
        assertNull(Checks.backgroundKillStamp(IDLE_KILL, RUNNING));
        // ...and an older stop that was one is still the one found.
        assertEquals("2026-10-25 13:52:29.117",
                Checks.backgroundKillStamp(IDLE_KILL + EXIT_INFO_SYSTEM_KILL.replace("#0", "#1"), RUNNING));
    }

    @Test
    public void anIdleKillThatLeftTheAppStoppedIsOne() {
        // As the Honor's iAwareR left four idle apps: stopped, so no job and
        // no boot broadcast would bring a recorder back.
        assertEquals("2026-10-25 18:00:00.000", Checks.backgroundKillStamp(IDLE_KILL, STOPPED));
    }

    @Test
    public void stoppedIsAskedOnlyAboutAnIdleNewestKill() {
        java.util.concurrent.atomic.AtomicInteger asked = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.BooleanSupplier counting = () -> {
            asked.incrementAndGet();
            return true;
        };
        Checks.backgroundKillStamp(EXIT_INFO_SYSTEM_KILL, counting);
        assertEquals("not for a kill that counts anyway", 0, asked.get());
        Checks.backgroundKillStamp(IDLE_KILL, counting);
        assertEquals(1, asked.get());
    }

    @Test
    public void theUsersForceStopDoesNotLendItsStoppedToAnOlderIdleKill() {
        // Force stop leaves the app stopped too; the idle kill under it is
        // still routine.
        String forceStopOverIdle =
                "    ApplicationExitInfo #0: | "
                    + "      timestamp=2026-10-25 19:00:00.000 pid=4 | "
                    + "      process=com.jemcik.jemrec reason=10 (USER REQUESTED) subreason=22 status=0 | "
                    + "      importance=100 | "
                    + IDLE_KILL.replace("#0", "#1");
        assertNull(Checks.backgroundKillStamp(forceStopOverIdle, STOPPED));
    }

    @Test
    public void theUsersOwnForceStopIsNotABackgroundKill() {
        String forceStopOnly =
                "    ApplicationExitInfo #0: | "
                    + "      timestamp=2026-10-25 14:00:00.000 pid=1 | "
                    + "      process=com.jemcik.jemrec reason=10 (USER REQUESTED) subreason=22 status=0 | "
                    + "      importance=100";
        assertNull(Checks.backgroundKillStamp(forceStopOnly, STOPPED));
    }

    @Test
    public void aKillBehindALaterExitIsStillFound() {
        // Killed at 13:52, then swiped away before the daemon's next look:
        // the kill is still the one to report, as the app's own check sees it.
        String killThenSwipe =
                "    ApplicationExitInfo #0: | "
                    + "      timestamp=2026-10-25 14:00:00.000 pid=1 | "
                    + "      process=com.jemcik.jemrec reason=10 (USER REQUESTED) subreason=22 status=0 | "
                    + "    ApplicationExitInfo #1: | "
                    + "      timestamp=2026-10-25 13:52:29.117 pid=2 | "
                    + "      process=com.jemcik.jemrec reason=13 (OTHER KILLS BY SYSTEM) status=0 | "
                    + "      importance=125";
        assertEquals("2026-10-25 13:52:29.117", Checks.backgroundKillStamp(killThenSwipe, RUNNING));
    }

    @Test
    public void aSubreasonIsNotReadAsTheReason() {
        // subreason printed first, as another build might: reason=10 is the
        // reason, and subreason=13 must not pass for a system kill.
        String subreasonFirst = "    ApplicationExitInfo #0: | "
                + "      timestamp=2026-10-25 14:00:00.000 pid=1 | "
                + "      process=com.jemcik.jemrec subreason=13 (UNKNOWN) reason=10 (USER REQUESTED) status=0";
        assertNull(Checks.backgroundKillStamp(subreasonFirst, STOPPED));
    }

    @Test
    public void aFreezerKillWhileWorkingCounts() {
        String freezer = "    ApplicationExitInfo #0: | "
                + "      timestamp=2026-10-25 09:00:00.500 pid=9 | "
                + "      process=com.jemcik.jemrec reason=14 (FREEZER) status=0 | "
                + "      importance=300";
        assertEquals("2026-10-25 09:00:00.500", Checks.backgroundKillStamp(freezer, RUNNING));
    }

    @Test
    public void aKillWithNoImportancePrintedStillCounts() {
        String unprinted = "    ApplicationExitInfo #0: | "
                + "      timestamp=2026-10-25 09:00:00.500 pid=9 | "
                + "      process=com.jemcik.jemrec reason=13 (OTHER KILLS BY SYSTEM) status=0";
        assertEquals("2026-10-25 09:00:00.500", Checks.backgroundKillStamp(unprinted, RUNNING));
    }

    @Test
    public void noExitRecordsIsNull() {
        assertNull(Checks.backgroundKillStamp("  Historical Process Exit for com.jemcik.jemrec: (none)", STOPPED));
        assertNull(Checks.backgroundKillStamp(null, STOPPED));
    }

    @Test
    public void aStopIsToldOnceEvenWhenTheNewestFallsBackToAnOlderOne() {
        java.util.Set<String> told = new java.util.HashSet<>();
        // The baseline: the 13:52 kill, from before the watch began.
        assertTrue(Checks.newStop(told, "2026-10-25 13:52:29.117"));
        // An idle kill that left the app stopped: new, told.
        assertTrue(Checks.newStop(told, "2026-10-25 19:30:00.000"));
        // The wake started the app, so that kill no longer counts and the
        // newest that does is 13:52 again: not new.
        assertFalse(Checks.newStop(told, "2026-10-25 13:52:29.117"));
        assertFalse(Checks.newStop(told, "2026-10-25 19:30:00.000"));
        assertFalse(Checks.newStop(told, null));
    }

    @Test
    public void stoppedIsReadFromUserZerosLineNotHonorsOwnFlag() {
        // The Honor's line, 2026-10-25, joined as Main.slurp joins lines.
        String line = "  Package [com.jemcik.jemrec] (abc): | "
                + "    User 0: ceDataInode=284428 installed=true hidden=false suspended=false "
                + "distractionFlags=0 stopped=%s iAwareStopped=%s notLaunched=false enabled=0 | "
                + "    User 0: ";
        assertFalse(Checks.packageStopped(String.format(line, "false", "true")));
        assertTrue(Checks.packageStopped(String.format(line, "true", "false")));
        assertFalse(Checks.packageStopped(null));
    }

    // EDGES THE TESTS ABOVE DID NOT PIN - each one a change PIT made to the
    // code that no test noticed (./gradlew :daemon-tests:pitest).

    @Test
    public void aDumpThatBeginsWithItsFirstRecordIsReadFromTheStart() {
        String atZero = "ApplicationExitInfo #0: | timestamp=2026-10-25 13:52:29.117 pid=1 | "
                + "process=com.jemcik.jemrec reason=13 (OTHER KILLS BY SYSTEM) status=0 | importance=125";
        assertEquals("2026-10-25 13:52:29.117", Checks.backgroundKillStamp(atZero, RUNNING));
    }

    @Test
    public void anAppUidOfZeroLetsNoOneElseIn() {
        // Only a NEGATIVE app uid means "not known yet, let the handshake
        // decide"; 0 is root's, and is no wildcard.
        assertFalse(Checks.callerAllowed(10200, 0));
    }

    @Test
    public void aPolicyDumpThatBeginsWithItsOutputsIsRead() {
        // The Honor's cut with its leading space gone: the outputs' heading at 0.
        assertEquals(1, Checks.inCallMusic(HONOR_POLICY.trim()));
    }

    private static byte[] pcm16(int frames, int amplitude) {
        byte[] pcm = new byte[2 * frames];
        for (int i = 0; i < frames; i++) {
            pcm[2 * i] = (byte) amplitude;
            pcm[2 * i + 1] = (byte) (amplitude >> 8);
        }
        return pcm;
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] out = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, out, a.length, b.length);
        return out;
    }

    @Test
    public void aBlockExactlyAtTheThresholdIsLoud() {
        // "At or above", so a constant 1000 against a 1000 threshold counts.
        assertArrayEquals(new boolean[]{true}, Checks.loudBlocks(pcm16(100, 1000), 100, 1000));
    }

    @Test
    public void eachBlockIsMeasuredOverItsOwnFrames() {
        // The second block, as loud as the first, must not read quieter for
        // where it starts; and a quiet block stays quiet.
        byte[] loudLoud = concat(pcm16(100, 1000), pcm16(100, 1000));
        assertArrayEquals(new boolean[]{true, true}, Checks.loudBlocks(loudLoud, 100, 1000));
        assertArrayEquals(new boolean[]{false}, Checks.loudBlocks(pcm16(100, 10), 100, 1000));
    }

    @Test
    public void loudnessReachesBackAsFarAsForward() {
        boolean[] loud = new boolean[10];
        loud[3] = true;
        // Block 5 with a margin of 2 looks at 3..7.
        assertTrue(Checks.loudAround(loud, 5, 2));
    }

    @Test
    public void aTakeOverBeforeAnyCaptureStartsAtZero() {
        // No capture start known (0): the call is kept from its first sample.
        assertEquals("jemrec_rec_1_from0_in.dat", Checks.takenRecfile("jemrec_rec_1_in.dat", 5000, 0));
    }

    @Test
    public void rmsCountsEveryChannelOfEveryFrame() {
        // Two stereo frames, one sample loud: the root mean square of four.
        assertEquals(500, Checks.rms(new short[]{1000, 0, 0, 0}, 2, 2), 1e-9);
    }

    @Test
    public void aCleanToneIsAllOneTone() {
        short[] pcm = stereo(4800, 1400, 0, 8000);
        double share = Checks.toneShare(pcm, 2, 0, 4800, 1400, 48000);
        assertEquals(1.0, share, 0.05);
    }

    private static short[] chimeSamples(int rate) {
        byte[] pcm = Checks.chime(rate, 4000);
        short[] s = new short[pcm.length / 2];
        for (int i = 0; i < s.length; i++) {
            s[i] = (short) ((pcm[2 * i] & 0xff) | (pcm[2 * i + 1] << 8));
        }
        return s;
    }

    private static double windowRms(short[] s, int from, int to) {
        double sum = 0;
        for (int i = from; i < to; i++) {
            sum += (double) s[i] * s[i];
        }
        return Math.sqrt(sum / (to - from));
    }

    @Test
    public void theChimesFirstNoteStrikesThenDecays() {
        int rate = 16000;
        short[] s = chimeSamples(rate);
        int ms = rate / 1000;
        // A 5 ms attack: the first millisecond is far quieter than the
        // strike just after it...
        assertTrue(windowRms(s, 0, ms) < 0.5 * windowRms(s, 6 * ms, 20 * ms));
        // ...then a bell's decay, before the second note comes in at 220 ms.
        assertTrue(windowRms(s, 150 * ms, 210 * ms) < 0.7 * windowRms(s, 10 * ms, 70 * ms));
        // It is the first note's E, and no C has started yet.
        short[] early = java.util.Arrays.copyOfRange(s, 10 * ms, 200 * ms);
        assertTrue(Checks.toneShare(early, 1, 0, early.length, 659.25, rate) > 0.6);
        assertTrue(Checks.toneShare(early, 1, 0, early.length, 523.25, rate) < 0.05);
    }

    @Test
    public void theChimesLastNoteFadesOutAndRingsWithItsOctave() {
        int rate = 16000;
        short[] s = chimeSamples(rate);
        int ms = rate / 1000;
        // The C runs 220 to 820 ms and fades over its last 20 ms, so it
        // never ends on a click: its last 5 ms are far quieter than the
        // 40 ms before the fade.
        assertTrue(windowRms(s, 815 * ms, 820 * ms) < 0.4 * windowRms(s, 760 * ms, 800 * ms));
        // Each note carries its octave at a quarter of its strength - a bell,
        // not a sine: about 6% of the E's energy.
        short[] early = java.util.Arrays.copyOfRange(s, 10 * ms, 200 * ms);
        double octave = Checks.toneShare(early, 1, 0, early.length, 2 * 659.25, rate);
        assertTrue(octave > 0.03 && octave < 0.12);
    }

    @Test
    public void theLastNoteRingsOnUntilItsLastTwentyMilliseconds() {
        // Only the last 20 ms fade: until then the C dies away as a bell does,
        // by its own time constant (0.22 s), not by a fade spread over the
        // note. Both windows lie past the E (which ends at 450 ms) and before
        // the fade (800 to 820 ms), 260 ms apart.
        int rate = 16000;
        short[] s = chimeSamples(rate);
        int ms = rate / 1000;
        double ratio = windowRms(s, 760 * ms, 800 * ms) / windowRms(s, 500 * ms, 540 * ms);
        assertEquals(Math.exp(-0.26 / 0.22), ratio, 0.03);
    }

    @Test
    public void localLogsAndInputThreadsNeverCountEvenFirstInTheDump() {
        // What AudioFlinger lists under "Local log:" are past tracks, and an
        // input thread holds no output: neither says a greeting is playing
        // now, wherever in the dump it stands.
        String track = "\n   2 yes 31494/   2000 AUDIO_OUTPUT_FLAG_INCALL_MUSIC\n";
        assertEquals(-1, Checks.trackOutput("Local log:" + track, 31494));
        assertEquals(-1, Checks.trackOutput("\nInput thread 0x7a:" + track, 31494));
    }

    @Test
    public void aRecfileSaysWhichWayAnAppsCallWentOnlyWhereTelecomSaid() {
        assertEquals("_in", Checks.recfileTag(true, null, null));
        assertEquals("_out", Checks.recfileTag(false, null, null));
        // WhatsApp: not through Telecom, the way left to its notification.
        assertEquals("_voip-com.whatsapp", Checks.recfileTag(false, "com.whatsapp", null));
        // Google Meet, through Telecom.
        assertEquals("_in_voip-com.google.android.apps.tachyon",
                Checks.recfileTag(true, "com.google.android.apps.tachyon", true));
        assertEquals("_out_voip-com.google.android.apps.tachyon",
                Checks.recfileTag(false, "com.google.android.apps.tachyon", false));
    }
}

