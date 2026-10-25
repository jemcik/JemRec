package com.jemcik.jemrec.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the background-start card and notification tell each maker's users -
 * the screen's name and what to do on it - so a phone never gets another
 * maker's words. Honor's is measured; the rest follow dontkillmyapp.com (see
 * AutoStart.instructionFor).
 */
class AutoStartTest {

    @Test fun anHonorIsToldManageManuallyOnAppLaunch() {
        assertEquals("App launch", AutoStart.labelFor("com.hihonor.systemmanager"))
        assertEquals(
            "Find JemRec, switch it to Manage manually, and leave its switches on.",
            AutoStart.instructionFor("com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        )
    }

    @Test fun huaweisOlderProtectedAppsScreenGetsItsOwnLine() {
        val line = AutoStart.instructionFor("com.huawei.systemmanager",
            "com.huawei.systemmanager.optimize.process.ProtectActivity")
        assertEquals("Find JemRec and switch it on, so it is protected.", line)
    }

    @Test fun switchPerAppScreensAreToldToTurnTheSwitchOn() {
        for (pkg in listOf("com.miui.securitycenter", "com.coloros.safecenter", "com.oppo.safe", "com.vivo.permissionmanager")) {
            assertEquals(pkg, "Find JemRec and turn its switch on.", AutoStart.instructionFor(pkg, null))
        }
        assertEquals("Autostart", AutoStart.labelFor("com.miui.securitycenter"))
    }

    @Test fun aSamsungIsSentToNeverSleepingApps() {
        assertEquals("Battery", AutoStart.labelFor("com.samsung.android.lool"))
        assertEquals(
            "Open Background usage limits, then Never sleeping apps, and add JemRec.",
            AutoStart.instructionFor("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        )
    }

    @Test fun nobodyButHonorAndHuaweiHearsManageManually() {
        for (pkg in listOf("com.miui.securitycenter", "com.coloros.safecenter", "com.vivo.permissionmanager",
            "com.samsung.android.lool", "com.asus.mobilemanager", null)) {
            assertTrue(pkg.toString(), "Manage manually" !in AutoStart.instructionFor(pkg, null))
        }
    }

    @Test fun anUnknownScreenGetsTheGenericLine() {
        assertEquals("Find JemRec and allow it to start and run in the background.", AutoStart.instructionFor(null, null))
        assertNull(AutoStart.labelFor("com.asus.mobilemanager"))
    }

    @Test fun anUnknownScreensButtonReadsAsAButton() {
        // Not "the app's start-up settings", which is for the middle of
        // Settings' sentence: the card's button and step name it.
        val asus = AutoStart.screenFor("com.asus.mobilemanager", "com.asus.mobilemanager.entry.FunctionActivity")
        assertEquals("Start-up settings", asus.label)
        assertEquals(AutoStart.Screen("App launch", AutoStart.instructionFor("com.hihonor.systemmanager", null)),
            AutoStart.screenFor("com.hihonor.systemmanager", null))
    }
}
