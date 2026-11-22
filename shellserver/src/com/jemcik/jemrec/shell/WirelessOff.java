package com.jemcik.jemrec.shell;

/**
 * Wireless debugging switched off after a start, when the app asks
 * (Wire.COMMAND_WIRELESS_OFF): the app asks only for a Wireless debugging
 * JemRec switched on, once its ADB session is closed and this process is up
 * (the app's Revive.askWirelessOff). Plain Java, so each answer is tested
 * off the phone (WirelessOffTest); the settings are Shield's.
 *
 * This process decides, not the app: it is the shell, so it reads both
 * switches' true values - Android 17 hides USB debugging from apps on
 * Pixels, and Android 17 QPR3 may hide Wireless debugging too (Shizuku-Next
 * #224: a test app read 0 where the shell read 1).
 *
 * Written off only while USB debugging reads on: AOSP's AdbService.stopAdbd
 * stops adbd only once neither transport is enabled, so with USB debugging
 * on, Wireless debugging going off leaves adbd, and this process with it,
 * as they were - on the Honor (MagicOS 11, Android 17), 2026-10-26 with no
 * cable, this process kept through Wi-Fi off and on, a lock and a call;
 * 2026-11-21, a computer's cable in, the same process IDs 8 s after. With
 * USB debugging off it would stop adbd, and this process: nothing is
 * written then.
 */
final class WirelessOff {

    private WirelessOff() {
    }

    /** The two settings, as Shield reads and writes them. */
    interface Globals {
        /** `settings get global`, trimmed; null if it could not be read. */
        @Nullable String read(String name);

        /** `settings put global`, read back: true if it now says so. */
        boolean put(String name, String value);
    }

    static final String USB = "adb_enabled";
    static final String WIRELESS = "adb_wifi_enabled";

    /**
     * Switch Wireless debugging off if USB debugging reads on: one of
     * Wire's four answers. Already off - "0", or never set ("null") - is
     * said as that, and nothing written. USB debugging that does not read
     * "1", or could not be read, writes nothing: off then could stop adbd.
     * A write that does not read back as off is refused - an OPPO, OnePlus
     * or realme phone with its Developer options switch off refuses the
     * shell (Shield.putGlobal) - and Wireless debugging is left as it was.
     * One at a time: each command has a thread of its own (LoopbackServer).
     */
    static synchronized String off(Globals globals) {
        if (!"1".equals(globals.read(USB))) {
            return Wire.WIRELESS_USB_OFF;
        }
        String wireless = globals.read(WIRELESS);
        if ("0".equals(wireless) || "null".equals(wireless)) {
            return Wire.WIRELESS_ALREADY_OFF;
        }
        return globals.put(WIRELESS, "0") ? Wire.WIRELESS_SWITCHED_OFF : Wire.WIRELESS_REFUSED;
    }
}
