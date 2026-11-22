package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

/**
 * Wireless debugging off after a start (WirelessOff.off), against settings
 * of the test's own: each of its four answers, and what it writes for each -
 * nothing at all unless USB debugging reads on, as off then could stop adbd.
 */
public class WirelessOffTest {

    /** Settings as `settings get` prints them; a name not here reads as
     *  unreadable (null). A put lands unless [refusing], and what was put
     *  goes to [puts]. */
    private static final class Settings implements WirelessOff.Globals {
        final Map<String, String> values = new HashMap<>();
        final List<String> puts = new ArrayList<>();
        boolean refusing;

        Settings(String usb, String wireless) {
            if (usb != null) {
                values.put(WirelessOff.USB, usb);
            }
            if (wireless != null) {
                values.put(WirelessOff.WIRELESS, wireless);
            }
        }

        @Override
        public @Nullable String read(String name) {
            return values.get(name);
        }

        @Override
        public boolean put(String name, String value) {
            puts.add(name + "=" + value);
            if (!refusing) {
                values.put(name, value);
            }
            return value.equals(values.get(name));
        }
    }

    @Test
    public void onWithUsbDebuggingOnIsSwitchedOff() {
        Settings s = new Settings("1", "1");
        assertEquals("WIRELESS off", WirelessOff.off(s));
        assertEquals(List.of("adb_wifi_enabled=0"), s.puts);
        assertEquals("0", s.values.get(WirelessOff.WIRELESS));
    }

    @Test
    public void unreadWithUsbDebuggingOnIsWrittenOffAll() {
        // Unread is not off: with USB debugging on the write is safe, and its
        // read-back says how it went.
        Settings s = new Settings("1", null);
        assertEquals("WIRELESS off", WirelessOff.off(s));
        assertEquals(List.of("adb_wifi_enabled=0"), s.puts);
    }

    @Test
    public void offAlreadyWritesNothing() {
        // LineageOS switches it off a second after a start (the OnePlus,
        // 2026-11-12); "null" is a phone where nobody ever set it.
        for (String off : new String[] {"0", "null"}) {
            Settings s = new Settings("1", off);
            assertEquals("WIRELESS already-off", WirelessOff.off(s));
            assertEquals(List.of(), s.puts);
        }
    }

    @Test
    public void usbDebuggingNotOnWritesNothing() {
        // Off - the Honor's Developer options in between - unread, or
        // anything but "1": Wireless debugging off then could stop adbd.
        for (String usb : new String[] {"0", "null", "", "Exception occurred", null}) {
            Settings s = new Settings(usb, "1");
            assertEquals("usb " + usb, "WIRELESS usb-off", WirelessOff.off(s));
            assertEquals("usb " + usb, List.of(), s.puts);
            assertEquals("1", s.values.get(WirelessOff.WIRELESS));
        }
    }

    @Test
    public void aWriteThatDoesNotReadBackIsRefused() {
        // OPPO, OnePlus, realme with their Developer options switch off.
        Settings s = new Settings("1", "1");
        s.refusing = true;
        assertEquals("WIRELESS refused", WirelessOff.off(s));
        assertEquals(List.of("adb_wifi_enabled=0"), s.puts);
        assertEquals("1", s.values.get(WirelessOff.WIRELESS));
    }

    @Test
    public void usbDebuggingIsNeverWritten() {
        for (String usb : new String[] {"1", "0", null}) {
            for (String wireless : new String[] {"1", "0", null}) {
                Settings s = new Settings(usb, wireless);
                WirelessOff.off(s);
                for (String put : s.puts) {
                    assertEquals(put, true, put.startsWith(WirelessOff.WIRELESS + "="));
                }
            }
        }
    }
}
