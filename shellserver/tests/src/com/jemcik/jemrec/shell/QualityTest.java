package com.jemcik.jemrec.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/** The rate a recording is given, as the app sends it: a number Opus takes,
 *  or nothing - and nothing keeps what there was. */
public class QualityTest {

    @Test
    public void theAppsChoicesAreRates() {
        for (int bps : new int[]{24_000, 32_000, 64_000, 128_000}) {
            assertEquals(Integer.valueOf(bps), Quality.parse(Integer.toString(bps)));
        }
    }

    @Test
    public void opusRangeIsTheLimit() {
        assertEquals(Integer.valueOf(6_000), Quality.parse("6000"));
        assertEquals(Integer.valueOf(510_000), Quality.parse("510000"));
        assertNull(Quality.parse("5999"));
        assertNull(Quality.parse("510001"));
        assertNull(Quality.parse("0"));
        assertNull(Quality.parse("-32000"));
    }

    @Test
    public void whatIsNotARateIsNone() {
        assertNull(Quality.parse(null));
        assertNull(Quality.parse(""));
        assertNull(Quality.parse("32k"));
        assertNull(Quality.parse("99999999999"));
        assertEquals(Integer.valueOf(32_000), Quality.parse(" 32000\n"));
    }

    @Test
    public void noRateKeepsTheOneThereIs() {
        assertEquals(64_000, Quality.from(null, 64_000));
        assertEquals(64_000, Quality.from("loud", 64_000));
        assertEquals(24_000, Quality.from("24000", 64_000));
    }

    @Test
    public void theDefaultIsThirtyTwo() {
        assertEquals(32_000, Quality.DEFAULT_BPS);
    }

    @Test
    public void ratesInWords() {
        assertEquals("32 kbps", Quality.words(32_000));
        assertEquals("24 kbps", Quality.words(24_000));
        assertEquals("6500 bps", Quality.words(6_500));
    }
}
