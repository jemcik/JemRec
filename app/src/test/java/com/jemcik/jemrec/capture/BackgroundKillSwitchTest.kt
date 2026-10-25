package com.jemcik.jemrec.capture

import com.jemcik.jemrec.Prefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * A stop while recording was switched off cost no call, so switching it back
 * on moves the background-kill warning's "count from" to now. Without it, the
 * first open after switching back on reported a kill from the days it was
 * off, as "a call could go unrecorded".
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class BackgroundKillSwitchTest {

    private val context = RuntimeEnvironment.getApplication()
    private fun countFrom() = Prefs.of(context).getLong(BackgroundKill.KEY_LAST, 0L)

    @Test fun switchingBackOnForgetsStopsFromWhileItWasOff() {
        RecorderSwitch.set(context, false)
        Prefs.of(context).edit().putLong(BackgroundKill.KEY_LAST, 1_000L).apply()
        val before = System.currentTimeMillis()
        RecorderSwitch.set(context, true)
        assertTrue(countFrom() >= before)
    }

    @Test fun switchingOnWhatIsAlreadyOnMovesNothing() {
        RecorderSwitch.set(context, true)
        Prefs.of(context).edit().putLong(BackgroundKill.KEY_LAST, 1_000L).apply()
        RecorderSwitch.set(context, true)
        assertEquals(1_000L, countFrom())
    }

    @Test fun switchingOffMovesNothing() {
        RecorderSwitch.set(context, true)
        Prefs.of(context).edit().putLong(BackgroundKill.KEY_LAST, 1_000L).apply()
        RecorderSwitch.set(context, false)
        assertEquals(1_000L, countFrom())
    }
}
