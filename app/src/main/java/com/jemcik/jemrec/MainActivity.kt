package com.jemcik.jemrec

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.jemcik.jemrec.capture.DebugNotificationCleaner
import com.jemcik.jemrec.capture.RecorderKeepAlive
import com.jemcik.jemrec.capture.RecorderSwitch
import com.jemcik.jemrec.ui.MainScreen
import com.jemcik.jemrec.ui.theme.JemRecTheme

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

        setContent {
            JemRecTheme {
                MainScreen()
            }
        }
    }
}
