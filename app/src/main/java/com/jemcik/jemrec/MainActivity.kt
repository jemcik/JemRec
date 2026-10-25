package com.jemcik.jemrec

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.jemcik.jemrec.capture.BackgroundKill
import com.jemcik.jemrec.capture.DebugNotificationCleaner
import com.jemcik.jemrec.capture.RecorderKeepAlive
import com.jemcik.jemrec.capture.RecorderSwitch
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
        enableEdgeToEdge()

        // Opening the app no longer starts a permanent service. It arms the
        // keep-alive job, which revives the daemon if it is down and keeps it
        // that way. Idempotent, and a no-op while switched off.
        if (RecorderSwitch.isOn(this)) {
            RecorderKeepAlive.schedule(this)
            RecorderKeepAlive.kickNow(this)
        }

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

    /** The app was already open when a notification's button was tapped. */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        DeepLink.offer(this, intent)
    }
}
