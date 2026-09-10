package com.jemcik.jemrec.ui

import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import com.jemcik.jemrec.adb.AdbTransport
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Pairs, then WAITS for the phone to become reachable, then finishes setup -
 * as a foreground service, under the pairing notification.
 *
 * WHY A SERVICE, AND WHY IT WAITS
 *
 * This used to be a BroadcastReceiver that paired and then took one shot at
 * connecting. On this phone that is a coin toss, because the two switches the
 * connect depends on - USB debugging and Wireless debugging - do not reliably
 * hold after being flipped in Settings. USB debugging in particular switches
 * ITSELF off shortly after being turned on with no cable attached, in whatever
 * order the two are flipped (measured across several fresh-install runs), and
 * with it gone Wireless debugging drops. Pairing itself always succeeds; only
 * the connect finds nothing to reach. Every miss then cost the user a whole
 * re-pair: the dialog, a fresh code, the notification again.
 *
 * So this does not take a shot. Having paired - a one-way door, adbd keeps the
 * key - it sits and watches both settings, tells the user in this same
 * notification exactly which switch is off RIGHT NOW, and connects the moment
 * both are on. The user fixes a switch once; nothing is re-typed.
 *
 * A foreground service because the user is over in Settings while this runs,
 * and a backgrounded process here is frozen by iAware within seconds. The
 * shortService type on Android 14+ is built for exactly this - a bounded job
 * that must not be killed part-way - and needs no extra permission. It is the
 * same shape as Shizuku's AdbPairingService, which is why the notification's
 * action points straight at this service rather than through a receiver: the
 * system starts it as the result of the tap, so a background start is never
 * in question.
 */
class PairingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var lastText: String? = null

    @Volatile
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Foreground FIRST, inside the window the system allows for it, under
        // the pairing notification itself - one card for the whole of pairing.
        promote(PairingNotification.build(this, "Pairing…", withReply = false))

        if (running) {
            // A second tap on Enter code while the first is in flight would
            // pair twice. The first run's card already says what is happening.
            return START_NOT_STICKY
        }

        val code = pairingCodeFrom(
            intent?.let { RemoteInput.getResultsFromIntent(it) }
                ?.getCharSequence(PairingNotification.KEY_CODE),
        )

        if (code == null) {
            finishWith("That was not six digits. Try again.", withReply = true)
            return START_NOT_STICKY
        }

        running = true
        scope.launch {
            try {
                run(code)
            } finally {
                running = false
            }
        }
        return START_NOT_STICKY
    }

    private fun promote(notification: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    PairingNotification.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE,
                )
            } else {
                startForeground(PairingNotification.NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // Shizuku's fallback, for the same reason: if the platform refuses
            // the foreground start (a background-start restriction, or a policy
            // on the type), the pairing must still proceed and the user must
            // still see the card. Post it plainly and carry on; the work runs at
            // background priority, which is worse than foreground but far better
            // than a crash with the code already typed.
            Log.e(TAG, "pairing service: startForeground refused, continuing plain", t)
            getSystemService(NotificationManager::class.java)
                .notify(PairingNotification.NOTIFICATION_ID, notification)
        }
    }

    private suspend fun run(code: String) {
        val app = applicationContext
        try {
            // Discovered, not typed. The port changes every time the dialog is
            // opened and means nothing to anyone.
            val port = AdbTransport.discoverPort(
                app, AdbMdns.SERVICE_TYPE_TLS_PAIRING, timeoutMs = 15_000,
            )
            if (port == null) {
                finishWith(
                    "No pairing dialog found. Open it in Settings and tap Enter code again.",
                    withReply = true,
                )
                return
            }

            if (AdbTransport.pair(app, AdbTransport.LOOPBACK, port, code).isFailure) {
                finishWith(
                    "Pairing failed. Reopen the dialog for a fresh code and try again.",
                    withReply = true,
                )
                return
            }

            // One-way door: adbd keeps the key. From here a connect that does not
            // land is "come back and finish", never "pair again" - the app reads
            // this to show the short reconnect card instead of the pairing flow.
            Setup.markPaired(app)

            if (!waitAndConnect(app)) {
                finishWith(TIMED_OUT, withReply = false)
                return
            }

            say("Paired. Starting the recorder…")
            if (Setup.finish(app).isSuccess) {
                Log.i(TAG, "pairing service: done")
                stopForeground(STOP_FOREGROUND_REMOVE)
                PairingNotification.dismiss(app)
                stopSelf()
            } else {
                finishWith(
                    "Paired, but the recorder did not start. Reopen JemRec - it will " +
                        "finish on its own.",
                    withReply = false,
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "pairing service: failed", t)
            finishWith("Something went wrong: ${t.message}", withReply = false)
        }
    }

    /**
     * Watch both switches and connect the moment both are on.
     *
     * Returns false only at the deadline. A connect attempt that fails just goes
     * round again, because the usual reason is that a switch dropped during it -
     * and the next reading says which one, in the card, where the user is
     * looking. The deadline sits under shortService's cap so the normal ending
     * is this method's, not the system's.
     */
    private suspend fun waitAndConnect(app: Context): Boolean {
        val deadline = SystemClock.elapsedRealtime() + WAIT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val usb = Setup.usbDebuggingOn(app)
            val wd = Setup.wirelessDebuggingOn(app)
            when {
                !usb && !wd -> say(
                    "Paired! In Developer options turn on USB debugging and " +
                        "Wireless debugging - any order. I connect the moment both are on."
                )
                !usb -> say(
                    "Paired! USB debugging switched itself off - turn it on again " +
                        "(a couple of rows above Wireless debugging). I connect the " +
                        "moment it is on."
                )
                !wd -> say(
                    "Paired! Wireless debugging switched itself off - turn it on " +
                        "again. I connect the moment it is on."
                )
                else -> {
                    say("Paired. Connecting…")
                    if (AdbTransport.autoConnect(app, timeoutMs = 5_000).isSuccess) return true
                }
            }
            delay(POLL_MS)
        }
        return false
    }

    /**
     * Update the card only when the words change. Every notify() re-renders,
     * and a shade that flickers once a second is worse than none.
     */
    private fun say(text: String) {
        if (text == lastText) return
        lastText = text
        getSystemService(NotificationManager::class.java).notify(
            PairingNotification.NOTIFICATION_ID,
            PairingNotification.build(this, text, withReply = false),
        )
    }

    /**
     * Leave a final message on the card and stop - DETACHED, so the message
     * outlives the service instead of vanishing with it.
     */
    private fun finishWith(text: String, withReply: Boolean) {
        stopForeground(STOP_FOREGROUND_DETACH)
        PairingNotification.show(this, text, withReply)
        stopSelf()
    }

    /**
     * shortService's backstop: the system calls this just before its cap and
     * then kills the service. Leaving a message beats an ANR. WAIT_MS is set
     * under the cap, so this should never be the normal ending.
     */
    override fun onTimeout(startId: Int) {
        finishWith(TIMED_OUT, withReply = false)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "JemRec"

        /** Under shortService's three-minute cap, with margin. */
        const val WAIT_MS = 150_000L
        const val POLL_MS = 1_000L

        const val TIMED_OUT =
            "Paired! I could not connect in time. Reopen JemRec to finish - it " +
                "will say which switch to turn on. No need to pair again."
    }
}

/**
 * The six digits out of whatever was typed into the reply field. "123 456",
 * "123-456", a stray letter from the keyboard's autocorrect: all fine. Five
 * digits or seven are not a code, and null says so.
 */
internal fun pairingCodeFrom(text: CharSequence?): String? =
    text?.toString()?.filter(Char::isDigit)?.takeIf { it.length == 6 }
