package com.jemcik.jemrec.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which Settings menu the setup steps name, and how they say it.
 *
 * The step leads with Settings' own search and offers the menu as "usually",
 * so a wrong answer here costs a slightly-off hint rather than a dead end -
 * but it should still be right, and the ROM cases below are the ones where it
 * was not.
 */
class DeviceSkinTest {

    @Test fun honorAndHuaweiKeepDeveloperOptionsUnderSystemAndUpdates() {
        assertEquals(DeviceSkin.HONOR, DeviceSkin.of("HONOR", "HONOR"))
        assertEquals(DeviceSkin.HUAWEI, DeviceSkin.of("HUAWEI", "HUAWEI"))
        assertEquals("System & updates → Developer options", DeviceSkin.HONOR.developerOptionsPath)
        assertEquals("under System & updates", DeviceSkin.HONOR.developerOptionsHint)
    }

    @Test fun samsungHasDeveloperOptionsAtTheTopLevel() {
        assertEquals(DeviceSkin.SAMSUNG, DeviceSkin.of("samsung", "samsung"))
        assertEquals("Developer options", DeviceSkin.SAMSUNG.developerOptionsPath)
        // "usually under Developer options" is not a sentence anyone can act on.
        assertEquals("in the main Settings list", DeviceSkin.SAMSUNG.developerOptionsHint)
    }

    @Test fun subBrandsResolveToTheirSettingsFamily() {
        assertEquals(DeviceSkin.XIAOMI, DeviceSkin.of("Xiaomi", "Redmi"))
        assertEquals(DeviceSkin.XIAOMI, DeviceSkin.of("Xiaomi", "POCO"))
        assertEquals(DeviceSkin.OPLUS, DeviceSkin.of("OnePlus", "OnePlus"))
        assertEquals(DeviceSkin.OPLUS, DeviceSkin.of("realme", "realme"))
        assertEquals(DeviceSkin.OPLUS, DeviceSkin.of("OPPO", "OPPO"))
        assertEquals("under Additional settings", DeviceSkin.XIAOMI.developerOptionsHint)
    }

    @Test fun stockAndUnknownBothPointAtSystem() {
        assertEquals(DeviceSkin.STOCK, DeviceSkin.of("Google", "google"))
        assertEquals(DeviceSkin.STOCK, DeviceSkin.of("Sony", "Sony"))
        assertEquals(DeviceSkin.UNKNOWN, DeviceSkin.of("Fairphone", "Fairphone"))
        assertEquals(DeviceSkin.UNKNOWN, DeviceSkin.of(null, null))
        assertEquals("under System", DeviceSkin.UNKNOWN.developerOptionsHint)
        assertEquals(DeviceSkin.STOCK.developerOptionsHint, DeviceSkin.UNKNOWN.developerOptionsHint)
    }

    /**
     * The bug this was reported for: a OnePlus running LineageOS still says
     * OnePlus in Build.MANUFACTURER, and was sent to OxygenOS's "Additional
     * settings" - a menu that phone does not have. A custom ROM is an AOSP
     * Settings tree whoever made the handset.
     */
    @Test fun aCustomRomBeatsTheManufacturer() {
        assertEquals(
            DeviceSkin.STOCK,
            DeviceSkin.of("OnePlus", "OnePlus", "lineage_instantnoodle-userdebug 14 UP1A.231005.007"),
        )
        assertEquals(
            DeviceSkin.STOCK,
            DeviceSkin.of("Xiaomi", "Redmi", "crDroidAndroid-14.0-20240101-lisa"),
        )
        assertEquals(
            DeviceSkin.STOCK,
            DeviceSkin.of("samsung", "samsung", "LineageOS 21"),
        )
        assertEquals(
            DeviceSkin.STOCK,
            DeviceSkin.of("Google", "google", "GrapheneOS 2024010100"),
        )
    }

    @Test fun theMakersOwnRomStillGetsTheMakersMenu() {
        // The stock strings from an Honor and a OnePlus: nothing in them names
        // another ROM, so the manufacturer decides as before.
        assertEquals(
            DeviceSkin.HONOR,
            DeviceSkin.of("HONOR", "HONOR", "BKQ-N49 10.0.0.199 HONOR/BKQ-N49/HNBKQ:16/user"),
        )
        assertEquals(
            DeviceSkin.OPLUS,
            DeviceSkin.of("OnePlus", "OnePlus", "OnePlus/OnePlus8/OnePlus8:13/RKQ1.211119.001"),
        )
        // No ROM string at all is the old two-argument behaviour.
        assertEquals(DeviceSkin.OPLUS, DeviceSkin.of("OnePlus", "OnePlus"))
    }
}
