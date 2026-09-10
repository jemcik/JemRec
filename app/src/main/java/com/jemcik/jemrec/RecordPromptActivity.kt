package com.jemcik.jemrec

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.jemcik.jemrec.capture.CallMonitorService
import com.jemcik.jemrec.ui.theme.JemRecTheme

/**
 * The full-screen half of the mid-call prompt.
 *
 * IT IS MOSTLY NOT SEEN, AND THAT IS THE POINT
 *
 * This activity exists to be attached to the notification as a full-screen
 * intent, and a full-screen intent only actually opens full screen when the
 * device is locked. That case is real - a phone held to an ear has a dark,
 * locked screen, and there is no shade to pull down - so it needs an answer.
 *
 * The rest of the time the system demotes the full-screen intent to a banner,
 * and demoting is exactly what makes the banner STICKY: SystemUI treats a
 * heads-up backed by a full-screen intent as important enough to leave up for
 * about a minute instead of the five seconds an ordinary one gets. That is the
 * only supported way to keep the prompt on screen longer. There is no API for
 * the heads-up timeout; it belongs to SystemUI, not to the app.
 *
 * So the activity earns its place twice: it answers the locked-screen case, and
 * its mere existence is what buys the longer banner everywhere else.
 */
class RecordPromptActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Shows over the lock screen: a locked phone has no shade to pull down.
        //
        // It deliberately does NOT turn the screen on. During a call the phone
        // is often against a face with the proximity sensor holding the screen
        // dark, and waking it there puts two buttons that decide whether a
        // private call is recorded directly under someone's cheek. The
        // vibration already says something is waiting, and the prompt is still
        // there when they look.
        setShowWhenLocked(true)

        val incoming = intent?.getBooleanExtra(CallMonitorService.EXTRA_INCOMING, false) == true

        setContent {
            JemRecTheme {
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Card {
                        Column(
                            Modifier.padding(24.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text(
                                "Record this call?",
                                style = MaterialTheme.typography.headlineSmall,
                            )
                            Text(
                                if (incoming) "Incoming call in progress."
                                else "Outgoing call in progress.",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(onClick = { answer(CallMonitorService.ACTION_RECORD_NOW) }) {
                                    Text("Record")
                                }
                                OutlinedButton(
                                    onClick = { answer(CallMonitorService.ACTION_DECLINE) },
                                ) { Text("Not now") }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Both buttons go to the same place the notification's buttons go, so there
     * is one path that starts a recording rather than two that can drift.
     */
    private fun answer(action: String) {
        val incoming = intent?.getBooleanExtra(CallMonitorService.EXTRA_INCOMING, false) == true
        runCatching {
            startForegroundService(
                Intent(this, CallMonitorService::class.java)
                    .setAction(action)
                    .putExtra(CallMonitorService.EXTRA_INCOMING, incoming)
            )
        }
        finish()
    }
}
