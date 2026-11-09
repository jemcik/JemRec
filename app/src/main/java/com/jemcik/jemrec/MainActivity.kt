package com.jemcik.jemrec

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jemcik.jemrec.capture.DebugNotificationCleaner
import com.jemcik.jemrec.recorder.BackgroundKill
import com.jemcik.jemrec.recorder.RecorderLifecycle
import com.jemcik.jemrec.recorder.Trigger
import com.jemcik.jemrec.ui.MainScreen
import com.jemcik.jemrec.ui.theme.JemRecTheme
import com.jemcik.jemrec.ui.theme.Looks

/**
 * Single activity. It owns the theme decision and nothing else - the whole of
 * the interesting parts are the transport and the daemon, and an activity that grows logic is an activity
 * that has started keeping state the transport should own.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The launcher, over JemRec's task begun another way (a notification,
        // the tile) with a picker on top, makes a new window rather than
        // bringing the task forward: the task's first intent is not the
        // launcher's (measured on the Android 12 and 17 AVDs, 2026-11-08).
        // Gone at once, it leaves the task as it was - the picker on top - as
        // the launcher does for a task it began. Only over JemRec's own
        // window: started into another app's task, it opens there as ever.
        if (!isTaskRoot && intent?.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER) &&
            overJemRec()
        ) {
            finish()
            return
        }
        enableEdgeToEdge()

        cameIn()

        // A notification's button - play this call, run the self-test. Only
        // on a fresh start: a recreated activity carries the same intent, and
        // rotating the phone must not play the call again.
        if (savedInstanceState == null) DeepLink.offer(this, intent)

        setContent {
            // Watched, not read once: a theme picked in Settings repaints
            // the app there and then.
            val look by Looks.changes(this).collectAsState()
            JemRecTheme(look) {
                MainScreen()
            }
        }
    }

    /** The app was already open when a notification's button was tapped -
     *  or a plain notification, the tile, the media notification, the
     *  launcher over a task begun another way: the window there is, come
     *  forward. For an opening, what a new window did holds for it too -
     *  until 0.30 those opened a second window, which did it; a button's
     *  link to somewhere of its own never did. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!DeepLink.leadsElsewhere(intent)) cameIn()
        DeepLink.offer(this, intent)
    }

    /** This window's task is one JemRec's own screen began. */
    private fun overJemRec(): Boolean = runCatching {
        val home = ComponentName(this, MainActivity::class.java)
        getSystemService(ActivityManager::class.java).appTasks.any { task ->
            task.taskInfo?.let { it.taskId == taskId && it.baseActivity == home } == true
        }
    }.getOrDefault(false)

    /** What each way in does, a new window or the one there is. */
    private fun cameIn() {
        // Opening the app no longer starts a permanent service. It arms the
        // keep-alive job, which revives the daemon if it is down and keeps it
        // that way. Idempotent, and a no-op while switched off.
        if (RecorderLifecycle.goes(this, Trigger.APP_OPEN)) RecorderLifecycle.arm(this, Trigger.APP_OPEN)

        // Hide Android's debugging notification only if the user said to, and
        // rebind the listener if the ROM dropped it. See apply().
        DebugNotificationCleaner.apply(this)

        // If this phone's power management stopped JemRec in the background
        // since we last said so, tell the user and point them at the setting
        // that prevents it. JemRecApp looks at every start of the process;
        // this looks again on each open, for a check the start could not make
        // (setup or the recorder switch finished since). No-op where there is
        // no such restriction. See BackgroundKill. Off the main thread: it asks
        // the package manager about each vendor screen and reads the exit and
        // start records, binder calls all, and the first frame should not wait
        // for them.
        val app = applicationContext
        Thread({ BackgroundKill.checkOwnRecords(app, "on open") }, "jemrec-killcheck").start()
    }

    companion object {
        /**
         * The intent that opens JemRec - the one way in that JemRec itself
         * makes (OneWindowTest holds that no other is): the window there is
         * comes forward, and what is on top of it in its task is finished,
         * rather than a second window stacked on it.
         */
        fun open(context: Context): Intent =
            Intent(context, MainActivity::class.java).addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
    }
}
