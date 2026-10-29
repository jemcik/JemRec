package com.jemcik.jemrec.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Whether setup's grant took, and what a phone that refused it is told.
 *
 * The outputs are real: the read-backs from the Android 12 and 17 emulators
 * (2026-10-29), granted and revoked, and the refusal as issue #64's OnePlus
 * Nord CE5 (OxygenOS 16) quoted it. No OnePlus here, so the refusal's text is
 * the one thing about that phone this can hold to.
 */
class ShellGrantTest {

    private val pkg = "com.jemcik.jemrec"
    private val wss = "android.permission.WRITE_SECURE_SETTINGS"

    private fun outcome(grant: String, readBack: String?) = ShellGrant.outcome(pkg, wss, grant, readBack)

    private val granted = """
          Package [com.jemcik.jemrec] (25e1ee6):
              android.permission.WRITE_SECURE_SETTINGS
              android.permission.WRITE_SECURE_SETTINGS: granted=true
    """.trimIndent()

    /** Revoked: requested, and no granted= line at all - not granted=false. */
    private val notGranted = """
          Package [com.jemcik.jemrec] (c94df5a):
              android.permission.WRITE_SECURE_SETTINGS
    """.trimIndent()

    private val refusal = "Exception occurred while executing 'grant':\n" +
        "java.lang.SecurityException: grantRuntimePermission: Neither user 2000 nor current " +
        "process has android.permission.GRANT_RUNTIME_PERMISSIONS.\n" +
        "\tat android.app.ContextImpl.enforce(ContextImpl.java:2493)\npm-exit=255"

    @Test fun aGrantReadBackAsHeldIsGranted() {
        assertEquals(ShellGrant.Outcome.GRANTED, outcome("pm-exit=0", granted))
    }

    @Test fun theOnePlusRefusalIsRefused() {
        assertEquals(ShellGrant.Outcome.REFUSED, outcome(refusal, notGranted))
        // The fake the debug build answers with is the same refusal.
        assertEquals(ShellGrant.Outcome.REFUSED, outcome(ShellGrant.REFUSAL, null))
    }

    @Test fun heldAlreadyIsEnoughWhateverTheGrantSaid() {
        // A blocked phone refuses even a permission held already (CallVault,
        // OnePlus 9) - and held is all setup needs.
        assertEquals(ShellGrant.Outcome.GRANTED, outcome(refusal, granted))
    }

    @Test fun silentButNotHeldIsNotGranted() {
        // The HyperOS 3 report: nothing said, nothing given.
        assertEquals(ShellGrant.Outcome.NOT_GRANTED, outcome("pm-exit=0", notGranted))
    }

    @Test fun noReadBackFallsBackOnTheExitStatus() {
        // What setup trusted before the read-back, so a dumpsys that says it
        // some other way blocks no phone that granted.
        assertEquals(ShellGrant.Outcome.GRANTED, outcome("pm-exit=0", null))
        assertEquals(ShellGrant.Outcome.GRANTED, outcome("pm-exit=0", "dumpsys: not found"))
        assertEquals(ShellGrant.Outcome.NOT_GRANTED, outcome("pm-exit=255", null))
        assertEquals(ShellGrant.Outcome.NOT_GRANTED, outcome("", null))
    }

    @Test fun anotherPackagesRecordIsNoAnswer() {
        val other = "  Package [com.jemcik.jemrec.debug] (1):\n      android.permission.WRITE_SECURE_SETTINGS"
        assertEquals(ShellGrant.Outcome.GRANTED, outcome("pm-exit=0", other))
    }

    @Test fun thePropertyReadsOnlyAsTrueOrFalse() {
        assertEquals(true, ShellGrant.monitoring("true"))
        assertEquals(false, ShellGrant.monitoring("false\n"))
        assertNull(ShellGrant.monitoring(""))
        assertNull(ShellGrant.monitoring(null))
    }

    @Test fun thePairingCardNamesTheSwitchUnlessThePhoneSaysItIsOn() {
        // Unreadable to the app ("") or never read (null): named, to be safe.
        assertTrue(ShellGrant.switchMayBeOff(""))
        assertTrue(ShellGrant.switchMayBeOff(null))
        assertTrue(ShellGrant.switchMayBeOff("true"))
        assertFalse(ShellGrant.switchMayBeOff("false"))
    }

    @Test fun aOnePlusIsToldItsSwitch() {
        val said = ShellGrant.advice(ShellGrant.Outcome.REFUSED, DeviceSkin.OPLUS, monitoring = null)
        assertTrue(said.startsWith("Android would not let JemRec change debugging settings."))
        assertTrue(said.contains("Disable system optimization"))
        assertTrue(said.contains("Disable permission monitoring"))
        assertTrue(said.contains("English"))
        // The only card a phone paired before 0.25.2 shows: the recorder
        // needs the switch too (the uninstall's off).
        assertTrue(said.contains("leave it that way"))
        // Some builds call it just Permission monitoring, to be turned off.
        assertTrue(said.contains("where it says just Permission monitoring, turn that off"))
        // Issue #61 was told this, on a OnePlus.
        assertFalse(said.contains("Xiaomi"))
    }

    @Test fun thePropertyOutranksTheName() {
        // Off, on a phone whose name says nothing: the switch.
        assertTrue(
            ShellGrant.advice(ShellGrant.Outcome.REFUSED, DeviceSkin.UNKNOWN, monitoring = true)
                .contains("needs one more switch on first"),
        )
        // On already: not sent to turn on what is on.
        val on = ShellGrant.advice(ShellGrant.Outcome.REFUSED, DeviceSkin.OPLUS, monitoring = false)
        assertFalse(on.contains("turn on Disable"))
        assertTrue(on.contains("already set"))
        assertTrue(on.contains("restart the phone"))
    }

    @Test fun aXiaomiIsToldItsOwnSwitch() {
        val said = ShellGrant.advice(ShellGrant.Outcome.REFUSED, DeviceSkin.XIAOMI, monitoring = null)
        assertTrue(said.contains("USB debugging (Security settings)"))
        assertFalse(said.contains("Disable system optimization"))
    }

    @Test fun anyOtherPhoneIsToldBoth() {
        val said = ShellGrant.advice(ShellGrant.Outcome.NOT_GRANTED, DeviceSkin.STOCK, monitoring = null)
        assertTrue(said.startsWith("Android did not give JemRec the permission"))
        assertTrue(said.contains("Disable system optimization"))
        assertTrue(said.contains("USB debugging (Security settings)"))
    }

    @Test fun theCommandsCarryWhatTheDecisionReads() {
        val grant = ShellGrant.grantCommand(pkg, "android.permission.WRITE_SECURE_SETTINGS")
        assertTrue(grant.contains("2>&1"))
        assertTrue(grant.endsWith("echo pm-exit=\$?"))
        val readBack = ShellGrant.readBackCommand(pkg, "android.permission.WRITE_SECURE_SETTINGS")
        assertEquals(
            "dumpsys package $pkg | grep -F -e 'Package [$pkg]' -e 'WRITE_SECURE_SETTINGS'",
            readBack,
        )
    }
}
