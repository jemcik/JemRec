package com.jemcik.jemrec.recorder

import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.capture.TestPhone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * A failed revive's verdict, judged under the alert's lock after Android was
 * asked (RecorderHealth.onRevive, then judge): whether JemRec is watched is
 * read again there, for a switch-off's standingDown can land between the two
 * (review round 4).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class RecorderHealthJudgeTest {

    private val context = RuntimeEnvironment.getApplication()
    private val downOffWifi = RecorderHealth.Down(onWifi = false, unadvertised = false, inCall = false)

    @Before fun setUpAndOn() {
        Prefs.of(context).edit().clear().commit()
        TestPhone.switchesOn(context)
        RecorderSwitch.set(context, true)
        TestPhone.setUp(context)
    }

    /** Switched off, and stood down, while the revive asked Android: its
     *  verdict keeps nothing - kept, the first failed revive after switching
     *  on again alerted at once, with no grace. Still on, the same verdict is
     *  kept. */
    @Test fun aVerdictPastTheSwitchOffKeepsNothing() {
        RecorderSwitch.set(context, false)
        RecorderHealth.standingDown(context)
        RecorderHealth.judge(context, alive = false, seen = AdbTransport.ConnectOutcome.NONE, down = downOffWifi)
        assertNull("nothing kept while off", RecorderHealth.cause(context))
        RecorderSwitch.set(context, true)
        RecorderHealth.judge(context, alive = false, seen = AdbTransport.ConnectOutcome.NONE, down = downOffWifi)
        assertEquals(RecorderHealth.Problem.NEEDS_WIFI, RecorderHealth.cause(context))
    }
}
