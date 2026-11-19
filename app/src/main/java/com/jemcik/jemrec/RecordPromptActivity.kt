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
import com.jemcik.jemrec.calls.RecordPrompt
import com.jemcik.jemrec.capture.CallMonitorService
import com.jemcik.jemrec.ui.theme.JemRecTheme
import com.jemcik.jemrec.ui.theme.Looks

/**
 * The full-screen half of the mid-call prompt.
 *
 * IT IS MOSTLY NOT SEEN, AND THAT IS THE POINT
 *
 * This activity exists to be attached to the notification as a full-screen
 * intent, and SystemUI opens it while the phone is asleep or its lock
 * screen itself is in front; anything else that can be a heads-up is one
 * instead - and where none can show (Do Not Disturb set not to pop
 * notifications, say) it may open over a locked call's screen
 * (FSI_KEYGUARD_OCCLUDED; review round 11, not measured)
 * (AOSP SystemUI, FullScreenIntentDecisionProvider: FSI_DEVICE_NOT_INTERACTIVE
 * and FSI_KEYGUARD_SHOWING come before NO_FSI_EXPECTED_TO_HUN). During a call
 * the call's own screen is in front, over the lock screen too, and keeps the
 * phone awake: measured on the Android 17 emulator (2026-11-18), the question
 * came as a heads-up and this activity never opened - unlocked, locked before
 * the call rang, and with the proximity sensor near (wakefulness stayed
 * Awake; whether a phone's screen goes dark at the ear is the proximity
 * sensor's, and SystemUI asks only whether the phone is awake). It opens for
 * a question posted while the phone sleeps - not seen in a call yet.
 *
 * What it buys everywhere else is how long the banner stays. A plain heads-up
 * goes after five seconds (SystemUI's heads_up_notification_decay); one backed
 * by a full-screen intent stays until it is answered, or, with full-screen
 * intents turned off for JemRec (Android 14 on: Settings, Special app access),
 * a minute (sticky_heads_up_notification_time). Measured in the same calls:
 * up all 100 s watched allowed, gone between 57 and 60 s turned off. That is
 * the only supported way to keep the prompt on screen longer. There is no API
 * for the heads-up timeout; it belongs to SystemUI, not to the app.
 */
class RecordPromptActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Over the lock screen: SystemUI opens this while the phone sleeps or
        // its lock screen is in front (the class's KDoc), and it
        // wakes the phone for it itself (StatusBarNotificationActivityStarter,
        // wakeUpForFullScreenIntent) - so it does not ask to turn the screen
        // on (setTurnScreenOn) as well. In the calls measured it was not opened:
        // the call's own screen is in front, and the question is a heads-up
        // (measured on the Android 17 emulator, 2026-11-18) - unless no
        // heads-up can show, when it may open over it (the class's KDoc).
        setShowWhenLocked(true)

        val incoming = intent?.getBooleanExtra(CallMonitorService.EXTRA_INCOMING, false) == true
        val app = intent?.getStringExtra(CallMonitorService.EXTRA_APP)

        setContent {
            JemRecTheme(Looks.choice(this)) {
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
                                RecordPrompt.callText(incoming, app),
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
                    // Passed on from the prompt that opened this, because the
                    // service only takes an answer that carries it. See
                    // RecordPrompt.claim.
                    .putExtra(
                        CallMonitorService.EXTRA_TICKET,
                        intent?.getStringExtra(CallMonitorService.EXTRA_TICKET),
                    )
            )
        }
        finish()
    }
}
