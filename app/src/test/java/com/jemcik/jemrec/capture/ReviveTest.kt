package com.jemcik.jemrec.capture

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * A revive and a start of the recorder (CaptureDaemon.revive, ensureRunning)
 * with no recorder answering and no ADB session, as they are today. What
 * either does past a connect needs a recorder and adbd, or a seam in front
 * of them; staying off is in DebugSwitchesOffStateTest.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class ReviveTest {

    private val context = RuntimeEnvironment.getApplication()

    @Before fun aPhoneOffWifiWithJemRecOn() {
        TestPhone.switchesOn(context)
        TestPhone.offWifi(context)
        DebugSwitchesOff.forget(context)
        RecorderSwitch.set(context, true)
        TestPhone.clearReviveLog(context)
    }

    @Test fun beforeSetupItWritesNothingAndStopsAfterTheProbe() = runBlocking {
        TestPhone.put(context, TestPhone.USB, 0)
        val writes = TestPhone.Writes(context)

        assertFalse(CaptureDaemon.revive(context))

        // No armShield before setup, and the setup gate comes after the
        // probe and before Wi-Fi is asked about.
        assertEquals(emptyList<String>(), writes.seen())
        assertEquals(
            listOf("revive: start", "revive: recorder does not answer", "revive: recorder down (connect NONE)"),
            TestPhone.reviveLog(context),
        )
        // Not set up is not down: nothing for the home screen to show.
        assertEquals(null, RecorderHealth.cause(context))
        writes.close()
    }

    @Test fun ensureRunningWithNoSessionStartsNothing() = runBlocking {
        TestPhone.setUp(context)

        val started = CaptureDaemon.ensureRunning(context)

        // It never connects: a revive does that first, under the same lock.
        // The Start recorder button (MainViewModel.bootstrapDaemon) calls
        // this alone, and a revive's standDown closes the session once a
        // recorder is up, so the button gets this answer unless a session
        // happens to be open. Pinned here, where it is decided; the button
        // itself is MainViewModel's.
        assertTrue(started.isFailure)
        assertEquals(
            "ADB is not connected, so the daemon cannot be started. " +
                "Connect once over Wi-Fi; after that recording needs no network.",
            started.exceptionOrNull()?.message,
        )
        // A confirmed probe first (about 2.4 s here), then nothing else.
        assertEquals(listOf("start: old recorder does not answer"), TestPhone.reviveLog(context))
    }
}
