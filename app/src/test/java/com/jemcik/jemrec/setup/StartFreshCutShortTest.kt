package com.jemcik.jemrec.setup

import com.jemcik.jemrec.adb.AdbIdentity
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.TestPhone
import com.jemcik.jemrec.recorder.DebugSwitchesOff
import com.jemcik.jemrec.recorder.OffMode
import com.jemcik.jemrec.recorder.RecorderSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * Start fresh (Setup.reset) cut short: it runs from the screen, whose scope
 * Back takes away, and waits for the recorder's lock behind a revive. Once
 * begun, it has to finish - half a reset leaves a forgotten pairing under a
 * setup still marked complete.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class StartFreshCutShortTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test fun aStartFreshCancelledWhileItWaitsForTheLockStillFinishes() = runBlocking {
        TestPhone.switchesOn(context)
        TestPhone.offWifi(context)
        TestPhone.setUp(context)
        TestPhone.clearReviveLog(context)
        // JemRec off with Developer options hidden: the recorder kept, so a
        // revive goes on.
        DebugSwitchesOff.forget(context)
        DebugSwitchesOff.setMode(context, OffMode.HIDE_DEVELOPER_OPTIONS)
        RecorderSwitch.set(context, false)
        DebugSwitchesOff.switchOff(context)
        assertEquals(0, TestPhone.get(context, TestPhone.DEV))

        // A revive holds the recorder's lock, probing a recorder that is not
        // there (about 2.4 s)...
        val revive = async(Dispatchers.IO) { CaptureDaemon.revive(context) }
        withTimeout(5_000) { while ("revive: start" !in TestPhone.reviveLog(context)) delay(20) }
        // ...when Start fresh begins, and waits for it; then Back.
        val reset = launch(Dispatchers.IO) { Setup.reset(context) }
        delay(500)
        reset.cancel()
        reset.join()
        assertFalse(revive.await())

        // All of it done all the same: the pairing gone and setup with it,
        // Developer options back for setup's second step.
        assertFalse(AdbIdentity.exists(context))
        assertFalse(Setup.isComplete(context))
        assertEquals(1, TestPhone.get(context, TestPhone.DEV))
        assertTrue(DebugSwitchesOff.switchedOff(context).isEmpty())
    }
}
