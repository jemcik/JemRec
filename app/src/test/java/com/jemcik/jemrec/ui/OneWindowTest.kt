package com.jemcik.jemrec.ui

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import com.jemcik.jemrec.MainActivity
import com.jemcik.jemrec.contract.Repo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/**
 * One window, by JemRec's own ways in: the Quick Settings tile, the media
 * notification and every notification start JemRec with MainActivity.open,
 * so the window there is comes forward rather than a second one over it.
 * Until 0.30 the tile, the media notification and a notification's tap each
 * could open a second (read from the code: their intents had neither
 * CLEAR_TOP nor, but the media notification's, SINGLE_TOP), and two
 * answering machine screens could then arm a test, or play the greeting
 * into a take, unseen by each other.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class OneWindowTest {

    private val app = RuntimeEnvironment.getApplication()

    /** Its own ways in bring the one window forward, finishing what is on
     *  top of it in its task, as deep links did already. */
    @Test fun jemRecOpensTheWindowThereIs() {
        val flags = MainActivity.open(app).flags
        val wanted = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        assertEquals(wanted, flags and wanted)
    }

    /** singleTop, not singleTask: the tile's long press, which Android
     *  starts, reaches the window on top; and the launcher only brings the
     *  task forward, a picker left open still there (singleTask finished it,
     *  and a permission's question, and "delete this recording?"). */
    @Test fun theScreenIsSingleTop() {
        val info = app.packageManager.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TOP, info.launchMode)
    }

    /** Nothing else in the app starts the screen: a way in made without
     *  MainActivity.open would bring the second window back. */
    @Test fun onlyMainActivityOpenMakesTheIntent() {
        val makers = Repo.file("app/src/main/java").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "MainActivity::class.java" in it.readText() }
            .map { it.name }
            .toList()
        assertEquals(listOf("MainActivity.kt"), makers)
    }
}
