package com.jemcik.jemrec.shell;

/**
 * How many bits a second a call's recording is given: the app's Quality
 * setting, in the environment at spawn (Main.QUALITY_ENV) and over
 * Wire.COMMAND_SET_QUALITY whenever the user changes it, the way the mode
 * comes. Plain Java, tested in ../tests.
 *
 * It was 128 kbps until 1.2 - scrcpy's default, about 63 MB an hour once
 * the Ogg pages were added (the 101 recordings on the Honor, 2026-11-16).
 * A call carries far less: AMR-WB or EVS on the phone network, Opus at a few
 * tens of kbps in a messenger. In a blind test on the Honor the same day,
 * three recordings encoded on the phone at 24, 32, 48, 64 and 128 kbps
 * sounded the same through its speakers and through earbuds, but for 24 kbps
 * on a messenger call, which "sounds in mono" - as libopus's source says its
 * speech mode narrows the stereo when the bits run short
 * (silk/stereo_LR_to_MS.c). So 32 kbps is the default, and the app offers 24
 * saying so.
 */
final class Quality {

    /** What a recording is given until the app says otherwise. */
    static final int DEFAULT_BPS = 32_000;

    /** Opus's range (RFC 6716): 6 to 510 kbit/s. */
    static final int LOWEST_BPS = 6_000;
    static final int HIGHEST_BPS = 510_000;

    /** The rate the next recording starts with; one under way keeps its own. */
    static volatile int bitRate = DEFAULT_BPS;

    private Quality() {
    }

    /** The rate in [wire], in bits a second; null if there is none there, or
     *  it is not one Opus takes. */
    static @Nullable Integer parse(@Nullable String wire) {
        if (wire == null) {
            return null;
        }
        try {
            int bps = Integer.parseInt(wire.trim());
            return bps >= LOWEST_BPS && bps <= HIGHEST_BPS ? bps : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** [wire]'s rate, or [current] where it has none: a value that is not a
     *  rate keeps the one there is, as an unusable mode does. */
    static int from(@Nullable String wire, int current) {
        Integer bps = parse(wire);
        return bps != null ? bps : current;
    }

    /** A rate in words, for logs and the call's report: "32 kbps". */
    static String words(int bps) {
        return bps % 1000 == 0 ? (bps / 1000) + " kbps" : bps + " bps";
    }
}
