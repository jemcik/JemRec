package com.jemcik.jemrec

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.jemcik.jemrec.calls.RecordPrompt
import com.jemcik.jemrec.capture.CallMonitorService
import com.jemcik.jemrec.ui.RecordThisCallDialog
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
 * a question posted while the phone sleeps - not seen in a call yet - and
 * where the screen is turned off by the power key with the question's banner
 * up: SystemUI sends every banner's full-screen intent as the phone goes to
 * sleep (CentralSurfacesImpl.maybeEscalateHeadsUp, Android 16 QPR2). Measured
 * on the Android 16 emulator in a locked call (2026-11-24): woken again, it
 * filled the screen over the call's, the hang-up key under it. And it opens
 * at a tap on the question. So since 1.8 it is see-through but for its dialog,
 * and a tap outside that gives the call's screen back, unanswered.
 *
 * With Ask first's button over the call (CallButton) there is no banner and no
 * full-screen intent while the phone is awake: only that tap opens it.
 *
 * What it buys everywhere else is how long the banner stays. A plain heads-up
 * goes after five seconds (SystemUI's heads_up_notification_decay); one backed
 * by a full-screen intent stays until it is answered, or, with full-screen
 * intents turned off for JemRec (Android 14 on: Settings, Special app access),
 * a minute (sticky_heads_up_notification_time). Measured in the same calls:
 * up all 100 s watched allowed, gone between 57 and 60 s turned off. That is
 * the only supported way to keep the banner on screen longer. There is no API
 * for the heads-up timeout; it belongs to SystemUI, not to the app - and a
 * phone maker's may go by rules of its own (CallButton).
 */
class RecordPromptActivity : ComponentActivity() {

    private var stopWatching: (() -> Unit)? = null

    override fun onDestroy() {
        stopWatching?.invoke()
        super.onDestroy()
    }

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

        // Gone with its question: answered elsewhere, or the call over, it
        // stayed over the lock screen with buttons that did nothing.
        val ticket = intent?.getStringExtra(CallMonitorService.EXTRA_TICKET)
        stopWatching = RecordPrompt.watch(this, ticket) { finish() }
        if (isFinishing) return

        val incoming = intent?.getBooleanExtra(CallMonitorService.EXTRA_INCOMING, false) == true
        val app = intent?.getStringExtra(CallMonitorService.EXTRA_APP)

        setContent {
            JemRecTheme(Looks.choice(this)) {
                RecordThisCallDialog(
                    call = RecordPrompt.callText(incoming, app),
                    onRecord = { answer(CallMonitorService.ACTION_RECORD_NOW) },
                    onNotNow = { answer(CallMonitorService.ACTION_DECLINE) },
                    onClose = { finish() },
                )
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
