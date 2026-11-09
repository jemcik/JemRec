package com.jemcik.jemrec.ui

import android.content.ComponentName
import android.content.pm.ActivityInfo
import com.jemcik.jemrec.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * JemRec is one window. The launcher, the Quick Settings tile and its long
 * press, the media notification and every notification's link bring the same
 * one forward: the tile and the media notification each opened a second over
 * it when it was not on top, and two answering machine screens could then arm
 * a test, or play the greeting into a take, unseen by each other.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class OneWindowTest {

    @Test fun theScreenIsOneTasksOnly() {
        val app = RuntimeEnvironment.getApplication()
        val info = app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
    }
}
