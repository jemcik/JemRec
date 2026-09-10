package com.jemcik.jemrec.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/** The make-to-menu mapping the setup steps read out, and the paths it produces. */
class DeviceSkinTest {

    @Test fun honorAndHuaweiKeepDeveloperOptionsUnderSystemAndUpdates() {
        assertEquals(DeviceSkin.HONOR, DeviceSkin.of("HONOR", "HONOR"))
        assertEquals(DeviceSkin.HUAWEI, DeviceSkin.of("HUAWEI", "HUAWEI"))
        assertEquals("System & updates → Developer options", DeviceSkin.HONOR.developerOptionsPath)
        assertEquals(
            "System & updates → Developer options → Wireless debugging",
            DeviceSkin.HONOR.wirelessDebuggingPath,
        )
    }

    @Test fun samsungHasDeveloperOptionsAtTheTopLevel() {
        assertEquals(DeviceSkin.SAMSUNG, DeviceSkin.of("samsung", "samsung"))
        assertEquals("Developer options", DeviceSkin.SAMSUNG.developerOptionsPath)
        assertEquals("Developer options → Wireless debugging", DeviceSkin.SAMSUNG.wirelessDebuggingPath)
    }

    @Test fun subBrandsResolveToTheirSettingsFamily() {
        assertEquals(DeviceSkin.XIAOMI, DeviceSkin.of("Xiaomi", "Redmi"))
        assertEquals(DeviceSkin.XIAOMI, DeviceSkin.of("Xiaomi", "POCO"))
        assertEquals(DeviceSkin.OPLUS, DeviceSkin.of("OnePlus", "OnePlus"))
        assertEquals(DeviceSkin.OPLUS, DeviceSkin.of("realme", "realme"))
        assertEquals(DeviceSkin.OPLUS, DeviceSkin.of("OPPO", "OPPO"))
        assertEquals("Additional settings → Developer options", DeviceSkin.XIAOMI.developerOptionsPath)
    }

    @Test fun stockAndUnknownBothPointAtSystem() {
        assertEquals(DeviceSkin.STOCK, DeviceSkin.of("Google", "google"))
        assertEquals(DeviceSkin.STOCK, DeviceSkin.of("Sony", "Sony"))
        assertEquals(DeviceSkin.UNKNOWN, DeviceSkin.of("Fairphone", "Fairphone"))
        assertEquals(DeviceSkin.UNKNOWN, DeviceSkin.of(null, null))
        assertEquals("System → Developer options", DeviceSkin.UNKNOWN.developerOptionsPath)
        assertEquals(DeviceSkin.STOCK.developerOptionsPath, DeviceSkin.UNKNOWN.developerOptionsPath)
    }
}
