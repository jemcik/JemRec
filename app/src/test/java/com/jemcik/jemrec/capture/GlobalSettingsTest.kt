package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.GlobalSettings.Reading
import org.junit.Assert.assertEquals
import org.junit.Test

/** What a setting's value means, given that Android 17 may hide two of them. */
class GlobalSettingsTest {

    private fun read(name: String, value: Int, sdk: Int) = GlobalSettings.classify(name, value, sdk)

    @Test fun beforeAndroid17AZeroIsOff() {
        assertEquals(Reading.OFF, read(GlobalSettings.ADB_ENABLED, 0, 36))
        assertEquals(Reading.OFF, read(GlobalSettings.DEVELOPMENT_SETTINGS_ENABLED, 0, 36))
    }

    @Test fun fromAndroid17AZeroFromTheHiddenTwoCannotBeTrusted() {
        // A Pixel on the September 2026 update reports 0 whatever the switch says.
        assertEquals(Reading.UNKNOWN, read(GlobalSettings.ADB_ENABLED, 0, 37))
        assertEquals(Reading.UNKNOWN, read(GlobalSettings.DEVELOPMENT_SETTINGS_ENABLED, 0, 37))
    }

    @Test fun aOneIsAlwaysTheTruth() {
        for (name in listOf(GlobalSettings.ADB_ENABLED, GlobalSettings.DEVELOPMENT_SETTINGS_ENABLED, GlobalSettings.ADB_WIFI_ENABLED)) {
            assertEquals(Reading.ON, read(name, 1, 36))
            assertEquals(Reading.ON, read(name, 1, 37))
        }
    }

    @Test fun wirelessDebuggingWasNotHiddenSoItsZeroStillMeansOff() {
        assertEquals(Reading.OFF, read(GlobalSettings.ADB_WIFI_ENABLED, 0, 37))
    }
}
