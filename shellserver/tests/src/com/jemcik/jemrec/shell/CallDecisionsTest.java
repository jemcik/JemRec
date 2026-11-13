package com.jemcik.jemrec.shell;

import static com.jemcik.jemrec.shell.CallDecisions.MODE_AUTOMATIC;
import static com.jemcik.jemrec.shell.CallDecisions.MODE_OFF;
import static com.jemcik.jemrec.shell.CallDecisions.MODE_ON_DEMAND;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * The recorder's decisions about a call, as tables: each row the inputs and
 * what the recorder did with them before these rules had a class of their own
 * (read from 0.30's Main.java: a call's start and end, the split, a
 * recording's stop, the wake queue, 'K', 'S' and busy). '*' is either value;
 * every combination is checked, each against the first row it matches.
 */
public class CallDecisionsTest {

    private static final String ANY = "*";

    /** Every combination of [n] booleans, as arrays. */
    private static List<boolean[]> all(int n) {
        List<boolean[]> out = new ArrayList<>();
        for (int bits = 0; bits < 1 << n; bits++) {
            boolean[] row = new boolean[n];
            for (int i = 0; i < n; i++) {
                row[i] = (bits & (1 << i)) != 0;
            }
            out.add(row);
        }
        return out;
    }

    /** Whether [inputs] match [pattern], "T", "F" or "*" for each. */
    private static boolean matches(String[] pattern, boolean[] inputs) {
        for (int i = 0; i < inputs.length; i++) {
            if (!pattern[i].equals(ANY) && pattern[i].equals("T") != inputs[i]) {
                return false;
            }
        }
        return true;
    }

    private static String describe(boolean[] inputs) {
        StringBuilder s = new StringBuilder();
        for (boolean b : inputs) {
            s.append(b ? 'T' : 'F').append(' ');
        }
        return s.toString().trim();
    }

    // A call starting, before the mode. Columns: a call was up, this one is a
    // messenger's, a messenger's call is up, a message is being taken, the
    // skip window is open.
    private static final Object[][] START = {
        // One at a time: a messenger call during any call, any call during a
        // messenger call, is not recorded.
        {"T", "T", "*", "*", "*", CallDecisions.Start.IGNORED},
        {"T", "*", "T", "*", "*", CallDecisions.Start.IGNORED},
        // A phone call this process answered is the message, whatever the
        // mode - and before the skip window is looked at.
        {"*", "F", "*", "T", "*", CallDecisions.Start.MESSAGE},
        {"*", "F", "*", "F", "T", CallDecisions.Start.SKIPPED},
        // A messenger call (none up before it) is never a message or skipped.
        {"*", "*", "*", "*", "*", CallDecisions.Start.BY_MODE},
    };

    @Test
    public void aCallStartingIsLeftOutTakenAsTheMessageOrGoesByTheMode() {
        for (boolean[] in : all(5)) {
            Object expected = null;
            for (Object[] row : START) {
                String[] pattern = {(String) row[0], (String) row[1], (String) row[2], (String) row[3], (String) row[4]};
                if (matches(pattern, in)) {
                    expected = row[5];
                    break;
                }
            }
            assertEquals(describe(in), expected, CallDecisions.start(in[0], in[1], in[2], in[3], in[4]));
        }
    }

    @Test
    public void byTheModeACallIsRecordedAskedAboutOrNot() {
        assertEquals(CallDecisions.ByMode.RECORD, CallDecisions.byMode(MODE_AUTOMATIC, false));
        // A second off-hook in a call being recorded - call waiting - is
        // still "record": beginRecording hands back the running one's name.
        assertEquals(CallDecisions.ByMode.RECORD, CallDecisions.byMode(MODE_AUTOMATIC, true));
        assertEquals(CallDecisions.ByMode.ASK, CallDecisions.byMode(MODE_ON_DEMAND, false));
        // Said yes to already in this call: told, not asked again.
        assertEquals(CallDecisions.ByMode.RECORDING_ALREADY, CallDecisions.byMode(MODE_ON_DEMAND, true));
        assertEquals(CallDecisions.ByMode.OFF, CallDecisions.byMode(MODE_OFF, false));
        assertEquals(CallDecisions.ByMode.OFF, CallDecisions.byMode(MODE_OFF, true));
        // A mode the recorder does not know records nothing.
        assertEquals(CallDecisions.ByMode.OFF, CallDecisions.byMode(3, false));
        assertEquals(CallDecisions.ByMode.OFF, CallDecisions.byMode(-1, true));
    }

    @Test
    public void theSkipWindowIsOpenUntilItsTimeOnly() {
        assertTrue(CallDecisions.skipOpen(1_001, 1_000));
        assertFalse(CallDecisions.skipOpen(1_000, 1_000));
        assertFalse(CallDecisions.skipOpen(0, 1_000));
    }

    @Test
    public void aSkipTakesBackStopsTheCallJustRecordedOrWaitsForTheNext() {
        // Anything but 1 takes a skip back, a recording or not.
        for (int value : new int[]{0, 2, -1}) {
            assertEquals(CallDecisions.Skip.CANCELLED, CallDecisions.skip(value, true, 0));
            assertEquals(CallDecisions.Skip.CANCELLED, CallDecisions.skip(value, false, 0));
        }
        // A phone call recorded for less than eight seconds - dialling went
        // off-hook before the app could say - is the one skipped.
        assertEquals(CallDecisions.Skip.CURRENT, CallDecisions.skip(1, true, 0));
        assertEquals(CallDecisions.Skip.CURRENT, CallDecisions.skip(1, true, 7_999));
        assertEquals(CallDecisions.Skip.NEXT, CallDecisions.skip(1, true, 8_000));
        // A messenger call's recording, or none, leaves it to the next call.
        assertEquals(CallDecisions.Skip.NEXT, CallDecisions.skip(1, false, 0));
        assertEquals(60_000L, CallDecisions.SKIP_VALID_MS);
    }

    @Test
    public void aYesToThePromptRecordsOnlyDuringACallWithRecordingOn() {
        assertTrue(CallDecisions.startOnDemand(true, MODE_ON_DEMAND));
        assertTrue(CallDecisions.startOnDemand(true, MODE_AUTOMATIC));
        assertFalse(CallDecisions.startOnDemand(true, MODE_OFF));
        assertFalse(CallDecisions.startOnDemand(false, MODE_ON_DEMAND));
    }

    @Test
    public void aStartWakeForACallAlreadyOverIsDropped() {
        // Call 3 has ended (endedSeq 3): a start wake for it, or an earlier
        // one, is stale; one for call 4 is not.
        assertTrue(CallDecisions.staleStart(true, 3, 3));
        assertTrue(CallDecisions.staleStart(true, 3, 2));
        assertFalse(CallDecisions.staleStart(true, 3, 4));
        // An end, the cable's or a background kill's wake is never dropped.
        assertFalse(CallDecisions.staleStart(false, 3, 3));
        assertFalse(CallDecisions.staleStart(false, 3, 2));
    }

    @Test
    public void aMessageTakenOverIsSplitOffInAskFirstOnly() {
        assertTrue(CallDecisions.splitsOffMessage(MODE_ON_DEMAND, false));
        // A test call's message is the test's.
        assertFalse(CallDecisions.splitsOffMessage(MODE_ON_DEMAND, true));
        assertFalse(CallDecisions.splitsOffMessage(MODE_AUTOMATIC, false));
        assertFalse(CallDecisions.splitsOffMessage(MODE_OFF, false));
    }

    @Test
    public void theSplitKeepsAMessageWithWordsDropsOneWithoutOrFindsNothing() {
        // Columns: the call is up, a recording runs, a word was left.
        Object[][] table = {
            {"F", "*", "*", CallDecisions.Split.NOTHING},
            {"T", "F", "*", CallDecisions.Split.NO_RECORDING},
            {"T", "T", "F", CallDecisions.Split.DROP},
            {"T", "T", "T", CallDecisions.Split.MESSAGE},
        };
        for (boolean[] in : all(3)) {
            Object expected = null;
            for (Object[] row : table) {
                if (matches(new String[]{(String) row[0], (String) row[1], (String) row[2]}, in)) {
                    expected = row[3];
                    break;
                }
            }
            assertEquals(describe(in), expected, CallDecisions.split(in[0], in[1], in[2]));
        }
    }

    @Test
    public void aRecordingEndsPlainAsATestAsAMessageOrAsTaken() {
        // Columns: the machine answered, a test, still a message.
        Object[][] table = {
            {"F", "*", "*", CallDecisions.Ending.PLAIN},
            {"T", "T", "*", CallDecisions.Ending.TEST},
            {"T", "F", "T", CallDecisions.Ending.MESSAGE},
            {"T", "F", "F", CallDecisions.Ending.TAKEN},
        };
        for (boolean[] in : all(3)) {
            Object expected = null;
            for (Object[] row : table) {
                if (matches(new String[]{(String) row[0], (String) row[1], (String) row[2]}, in)) {
                    expected = row[3];
                    break;
                }
            }
            assertEquals(describe(in), expected, CallDecisions.ending(in[0], in[1], in[2]));
        }
    }

    @Test
    public void anEndWithNothingRecordedTellsTheAppUnlessRecordingIsOff() {
        assertTrue(CallDecisions.endWakes(false, MODE_ON_DEMAND));
        assertTrue(CallDecisions.endWakes(false, MODE_AUTOMATIC));
        assertFalse(CallDecisions.endWakes(false, MODE_OFF));
        // A recording's own end wakes the app with its name (stopRecording).
        assertFalse(CallDecisions.endWakes(true, MODE_ON_DEMAND));
        assertFalse(CallDecisions.endWakes(true, MODE_AUTOMATIC));
    }

    @Test
    public void aQuitWaitsOutARecordingACallAFetchOrTheMachinePastRinging() {
        assertFalse(CallDecisions.busy(false, false, 0, null));
        assertFalse(CallDecisions.busy(false, false, 0, AnswerMachine.State.IDLE));
        assertFalse(CallDecisions.busy(false, false, 0, AnswerMachine.State.RINGING));
        assertTrue(CallDecisions.busy(true, false, 0, null));
        assertTrue(CallDecisions.busy(false, true, 0, null));
        assertTrue(CallDecisions.busy(false, false, 1, null));
        for (AnswerMachine.State state : AnswerMachine.State.values()) {
            boolean waits = state != AnswerMachine.State.IDLE && state != AnswerMachine.State.RINGING;
            assertEquals(state.name(), waits, CallDecisions.busy(false, false, 0, state));
        }
    }

    @Test
    public void onlyTheThreeModesAreTaken() {
        assertTrue(CallDecisions.isMode(MODE_OFF));
        assertTrue(CallDecisions.isMode(MODE_AUTOMATIC));
        assertTrue(CallDecisions.isMode(MODE_ON_DEMAND));
        // Never guessed: 'E' keeps the mode in force, the environment means off.
        assertFalse(CallDecisions.isMode(-1));
        assertFalse(CallDecisions.isMode(3));
        assertFalse(CallDecisions.isMode(255));
    }

    @Test
    public void theModesAreTheWiresAndTheEnvironments() {
        // The app sends these over 'E' and in JEMREC_MODE (DaemonClient.MODE_*).
        assertEquals(0, MODE_OFF);
        assertEquals(1, MODE_AUTOMATIC);
        assertEquals(2, MODE_ON_DEMAND);
    }

    @Test
    public void aTestsMidGreetingCheckIsItsTestsAlone() {
        // Its test, under way: run, and what it read kept.
        assertTrue(CallDecisions.testCheckApplies(7, 7, true));
        // Its test over by the check's time: the next call would be read.
        assertFalse(CallDecisions.testCheckApplies(7, 7, false));
        // Another test armed since: not its results, under way or not.
        assertFalse(CallDecisions.testCheckApplies(8, 7, true));
        assertFalse(CallDecisions.testCheckApplies(8, 7, false));
    }

    @Test
    public void aWifiReadingThatCouldNotTellLeavesTheOneBefore() {
        // Read on LTE, then on Wi-Fi after the move: the latest.
        assertEquals(1, CallDecisions.wifiAfter(0, 1));
        assertEquals(0, CallDecisions.wifiAfter(1, 0));
        // Could not tell - no call listed by then: what was read stays.
        assertEquals(1, CallDecisions.wifiAfter(1, -1));
        assertEquals(0, CallDecisions.wifiAfter(0, -1));
        assertEquals(-1, CallDecisions.wifiAfter(-1, -1));
    }

    @Test
    public void aDoneTestIsRunningUntilItsReadingsAreKept() {
        assertEquals("running", CallDecisions.testStateToSay("done", 1));
        assertEquals("done", CallDecisions.testStateToSay("done", 0));
        // Only "done" waits: the app reads it once; the others it reads again.
        assertEquals("running", CallDecisions.testStateToSay("running", 2));
        assertEquals("armed", CallDecisions.testStateToSay("armed", 1));
        assertEquals("none", CallDecisions.testStateToSay("none", 0));
    }

    @Test
    public void aGreetingsCheckIsItsOwn() {
        // The test's arming and its greeting both still the ones checked for.
        assertTrue(CallDecisions.greetingCheckApplies(7L, 7L, 3, 3, true, false));
        // A greeting played again since: the old one's check reads the new track.
        assertFalse(CallDecisions.greetingCheckApplies(7L, 7L, 4, 3, true, false));
        // Another test armed, or this one over before it was read.
        assertFalse(CallDecisions.greetingCheckApplies(8L, 7L, 3, 3, true, false));
        assertFalse(CallDecisions.greetingCheckApplies(7L, 7L, 3, 3, false, false));
        // Read while its greeting is cut off, the call moving: neither way's.
        assertFalse(CallDecisions.greetingCheckApplies(7L, 7L, 3, 3, true, true));
    }

    @Test
    public void aTestsMixerMarkIsItsGreetingsWay() {
        assertEquals(1, CallDecisions.mixerOf(Checks.PATH_MIXER));
        assertEquals(0, CallDecisions.mixerOf(Checks.PATH_VOICE));
        assertEquals(-1, CallDecisions.mixerOf(Checks.PATH_NONE));
        // As each greeting begins, until a check kept one with the readings.
        assertEquals(1, CallDecisions.mixerAsGreetingBegins(false, 0, Checks.PATH_MIXER));
        assertEquals(0, CallDecisions.mixerAsGreetingBegins(false, -1, Checks.PATH_VOICE));
        assertEquals(0, CallDecisions.mixerAsGreetingBegins(true, 0, Checks.PATH_MIXER));
        assertEquals(1, CallDecisions.mixerAsGreetingBegins(true, 1, Checks.PATH_VOICE));
    }

    @Test
    public void aCallThroughTheMixerGetsTheCallScreeningRouteWhateverTheAppsIs() {
        // On a Galaxy S23: in-call music over VoLTE, call-screening over
        // Wi-Fi Calling, which goes through the mixer there.
        assertEquals("screening", CallDecisions.greetingRoute("music", Checks.PATH_MIXER));
        assertEquals("screening", CallDecisions.greetingRoute("screening", Checks.PATH_MIXER));
        // The call's voice path takes the app's, either.
        assertEquals("music", CallDecisions.greetingRoute("music", Checks.PATH_VOICE));
        assertEquals("screening", CallDecisions.greetingRoute("screening", Checks.PATH_VOICE));
        // No call mode read: the app's, as before the way was looked at.
        assertEquals("music", CallDecisions.greetingRoute("music", Checks.PATH_NONE));
    }

    @Test
    public void aVoiceTypeReadMidGreetingReplacesTheOneBeforeIfItNamesOne() {
        // On Wi-Fi as the message began (IWLAN, 18), on LTE mid-greeting.
        assertEquals(13, CallDecisions.voiceAfter(18, 13));
        // Unknown (0) or not read (-1) then: the one before stays.
        assertEquals(18, CallDecisions.voiceAfter(18, 0));
        assertEquals(18, CallDecisions.voiceAfter(18, -1));
        assertEquals(-1, CallDecisions.voiceAfter(-1, -1));
    }
}
