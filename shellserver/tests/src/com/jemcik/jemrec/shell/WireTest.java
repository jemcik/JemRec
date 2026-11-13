package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import org.junit.Test;

/**
 * Every reply in fixtures/protocol.txt, built by the recorder's own Wire: the
 * lines the app's tests read with the app's parsers (ProtocolTest there). The
 * two sides are written apart; an installed app talks to whatever recorder is
 * running, so a line reworded here breaks the phone and nothing else would
 * say so.
 */
public class WireTest {

    /** One row of the fixture: the command and an example reply. */
    private static final class Row {
        final String command;
        final String example;

        Row(String command, String example) {
            this.command = command;
            this.example = example;
        }
    }

    private static List<Row> rows() {
        List<Row> out = new ArrayList<>();
        try (InputStream in = WireTest.class.getResourceAsStream("/protocol.txt")) {
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] columns = line.split("\t");
                assertEquals("not four columns: " + line, 4, columns.length);
                out.add(new Row(columns[0], columns[3]));
            }
        } catch (IOException | NullPointerException e) {
            throw new AssertionError("fixtures/protocol.txt not readable", e);
        }
        return out;
    }

    /** Each example as Wire builds it, keyed "command example". */
    private static Map<String, Supplier<String>> built() {
        Map<String, Supplier<String>> b = new HashMap<>();
        b.put("P PONG 2 1a2b3c4d", () -> Wire.pong("1a2b3c4d"));
        b.put("Q BYE", () -> Wire.BYE);
        b.put("Q BUSY", () -> Wire.BUSY);
        b.put("O OFF NOW", () -> Wire.OFF_NOW);
        b.put("O OFF AFTER CALL", () -> Wire.OFF_AFTER_CALL);
        b.put("O OFF TOO LATE", () -> Wire.OFF_TOO_LATE);
        b.put("O OFF CANCELLED", () -> Wire.OFF_CANCELLED);
        b.put("T TRACK on", () -> Wire.track(true));
        b.put("B LISTENER ok", () -> Wire.listener(true));
        b.put("B LISTENER failed", () -> Wire.listener(false));
        b.put("K SKIP current", () -> Wire.skip("current"));
        b.put("V VOIP 3 armed", () -> Wire.voip(3, true));
        b.put("W WATCH 1 since=1761990000000 watching=1 looper=ok mode=1 call=0 events=4 lastevent=1761990100000"
                + " offhooks=1 lastoffhook=1761990050000 recording=0 voip=3 voiparmed=0 tracking=1 answer=0 greeting=0",
                () -> Wire.watch(1761990000000L, true, true, 1, false, 4, 1761990100000L, 1, 1761990050000L, 0, 3,
                        false, true, false, false));
        b.put("F gone", () -> Wire.GONE);
        b.put("D OK", () -> Wire.deleted(true));
        b.put("D NO", () -> Wire.deleted(false));
        b.put("N ANSWER 1,60,music", () -> Wire.answer("1,60,music"));
        b.put("Y GO ARMED", () -> Wire.go("ARMED"));
        b.put("Y GO none", () -> Wire.go(null));
        b.put("H HAND TALKING", () -> Wire.hand("TALKING"));
        b.put("H HAND none", () -> Wire.hand(null));
        b.put("GQ GREETING 96000", () -> Wire.greeting(96000));
        b.put("GR TAKE recording", () -> Wire.take(true));
        b.put("GR TAKE busy", () -> Wire.take(false));
        b.put("GS TAKE 96000", () -> Wire.taken(96000));
        b.put("GP GREETING 96000", () -> Wire.greeting(96000));
        b.put("GD GREETING 0", () -> Wire.greeting(0));
        b.put("XC DIAG 1 perms=ok incallmusic=1 screening=1 greeting=0 route=music sdk=37 voice=13 soc=SM8850"
                + " maker=HONOR model=BKQ-N49",
                () -> Wire.diag("ok", 1, 1, 0, "music", 37, 13, "SM8850", "HONOR", "BKQ-N49"));
        for (String outcome : new String[]{"armed", "bad-number", "emergency", "no-greeting", "busy", "none"}) {
            b.put("XA TEST " + outcome, () -> Wire.test(outcome));
        }
        b.put("XR RESULT 1 state=done route=1 realms=9416 audioms=9539 tone=0 tonechunks=0 mute=1 voice=16 played=1"
                + " arming=1762000180000", () -> Wire.result("done", 1, 9416, 9539, 0, 0, 1, 16, 1, 1762000180000L));
        b.put("XF FWD error no-telephony", () -> Wire.fwd("error no-telephony"));
        b.put("XS FWD error bad-seconds", () -> Wire.fwd("error bad-seconds"));
        b.put("XP SIMS default=1 1|SIM_1 2|SIM_2",
                () -> Wire.SIMS + Wire.simsDefault(Wire.word("1")) + Wire.sim("1", "SIM 1") + Wire.sim("2", "SIM 2"));
        b.put("E 1", () -> Wire.mode(1));
        return b;
    }

    /** Rows whose line is not a format of Wire's: a name, or free text. */
    private static final Map<String, String> NOT_BUILT = new HashMap<>();

    static {
        NOT_BUILT.put("L", "a recording's file name as it is on disk, one per line");
        NOT_BUILT.put("S", "the name of the recording started, or nothing");
        NOT_BUILT.put("C", "the last call's report, free text (CallReport)");
    }

    @Test
    public void wireBuildsEveryReplyInTheFixture() {
        Map<String, Supplier<String>> built = built();
        List<String> missing = new ArrayList<>();
        for (Row row : rows()) {
            if (NOT_BUILT.containsKey(row.command)) {
                continue;
            }
            Supplier<String> line = built.remove(row.command + " " + row.example);
            if (line == null) {
                missing.add(row.command + " " + row.example);
                continue;
            }
            assertEquals(row.command, row.example, line.get());
        }
        assertTrue("fixture rows Wire does not build:\n" + String.join("\n", missing), missing.isEmpty());
        assertTrue("built here but not in the fixture: " + built.keySet(), built.isEmpty());
    }

    @Test
    public void aReplyIsOneLineSentAtOnce() throws IOException {
        int[] flushed = {0};
        ByteArrayOutputStream out = new ByteArrayOutputStream() {
            @Override
            public void flush() {
                flushed[0]++;
            }
        };
        Wire.reply(out, "TEST armed");
        assertEquals("TEST armed\n", out.toString(StandardCharsets.UTF_8));
        assertEquals(1, flushed[0]);
    }

    /** The other word of each yes-or-no, which the fixture's examples do not
     *  show: the app reads "looper=ok", "watching=1" and the rest as they
     *  are, and anything else as no. */
    @Test
    public void eachNoIsItsOwnWord() {
        assertEquals("WATCH 1 since=0 watching=0 looper=stuck mode=0 call=1 events=0 lastevent=0 offhooks=0"
                        + " lastoffhook=0 recording=5 voip=0 voiparmed=1 tracking=0 answer=1 greeting=1",
                Wire.watch(0, false, false, 0, true, 0, 0, 0, 0, 5, 0, true, false, true, true));
        assertEquals("TRACK off", Wire.track(false));
        assertEquals("VOIP 0 off", Wire.voip(0, false));
        assertEquals("SIMS default=", Wire.SIMS + Wire.simsDefault(""));
        assertEquals(" unknown|SIM", Wire.sim("", null));
        assertEquals("DIAG 1 perms=mute,calls incallmusic=-1 screening=0 greeting=96000 route=screening sdk=31"
                        + " voice=-1 soc=unknown maker=unknown model=Pixel_6a",
                Wire.diag("mute,calls", -1, 0, 96000, "screening", 31, -1, "", null, "Pixel 6a"));
    }

    @Test
    public void aValueInAKeyValueLineHasNoSpaceOrEquals() {
        assertEquals("SIM_1", Wire.word("SIM 1"));
        assertEquals("a_b", Wire.word("a = \t b"));
        assertEquals("unknown", Wire.word(""));
        assertEquals("unknown", Wire.word(null));
    }

    private static byte[] frame(int length, byte[] body) {
        byte[] out = new byte[2 + body.length];
        out[0] = (byte) (length >> 8);
        out[1] = (byte) length;
        System.arraycopy(body, 0, out, 2, body.length);
        return out;
    }

    @Test
    public void aNameFrameIsTwoBytesOfLengthThenUtf8() throws IOException {
        byte[] name = "jemrec_rec_1_in.dat".getBytes(StandardCharsets.UTF_8);
        assertEquals("jemrec_rec_1_in.dat", Wire.readName(new ByteArrayInputStream(frame(name.length, name))));
        byte[] cyrillic = "Олена".getBytes(StandardCharsets.UTF_8);
        assertEquals("Олена", Wire.readName(new ByteArrayInputStream(frame(cyrillic.length, cyrillic))));
        assertEquals("", Wire.readName(new ByteArrayInputStream(frame(0, new byte[0]))));
        byte[] longest = new byte[512];
        assertEquals(512, Wire.readName(new ByteArrayInputStream(frame(512, longest))).length());
    }

    @Test
    public void aNameOverFiveHundredAndTwelveBytesIsRefusedUnread() {
        try {
            Wire.readName(new ByteArrayInputStream(frame(513, new byte[513])));
            fail("a 513-byte name was read");
        } catch (IOException e) {
            assertEquals("implausible name length 513", e.getMessage());
        }
    }

    @Test
    public void aFrameCutShortIsAnError() {
        try {
            Wire.readName(new ByteArrayInputStream(frame(10, new byte[4])));
            fail("a frame cut short was read");
        } catch (IOException expected) {
            // EOFException: the session ends, nothing is acted on.
        }
    }

    @Test
    public void theCommandBytesAreTheApps() {
        // DaemonClient.COMMAND_*: one byte each, the same on both sides.
        assertEquals('A', Wire.COMMAND_HELLO);
        assertEquals('P', Wire.COMMAND_PING);
        assertEquals('R', Wire.COMMAND_RECORD);
        assertEquals('F', Wire.COMMAND_FETCH);
        assertEquals('D', Wire.COMMAND_DELETE);
        assertEquals('E', Wire.COMMAND_SET_ENABLED);
        assertEquals('L', Wire.COMMAND_LIST);
        assertEquals('S', Wire.COMMAND_START);
        assertEquals('W', Wire.COMMAND_WATCH);
        assertEquals('Q', Wire.COMMAND_QUIT);
        assertEquals('O', Wire.COMMAND_DEBUGGING_OFF);
        assertEquals('V', Wire.COMMAND_SET_VOIP);
        assertEquals('T', Wire.COMMAND_SET_TRACKING);
        assertEquals('K', Wire.COMMAND_SKIP);
        assertEquals('N', Wire.COMMAND_ANSWER_SETTINGS);
        assertEquals('Y', Wire.COMMAND_GO_AHEAD);
        assertEquals('H', Wire.COMMAND_HANDOVER);
        assertEquals('G', Wire.COMMAND_GREETING);
        assertEquals('X', Wire.COMMAND_DIAGNOSE);
        assertEquals('C', Wire.COMMAND_REPORT);
        assertEquals(2, Wire.PROTOCOL_VERSION);
    }
}
