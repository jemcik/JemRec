package com.jemcik.jemrec.capture

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log

/**
 * Brings the recorder back the instant Wi-Fi returns.
 *
 * WHY THIS EXISTS ALONGSIDE THE KEEP-ALIVE JOB
 *
 * The recorder can only run while Wi-Fi is up (the shell daemon needs the ADB
 * session, which needs Wireless debugging, which this ROM ties to Wi-Fi). So
 * the whole of "records as much as it can" reduces to one thing: the moment the
 * phone is back on Wi-Fi, is the daemon alive again?
 *
 * The keep-alive job answers that on a 15-minute clock - which means a call in
 * the first quarter-hour after walking back onto Wi-Fi is missed even though
 * recording was possible the whole time. This closes that gap. It watches for
 * Wi-Fi coming back and revives the daemon within a second or two, so "on Wi-Fi"
 * and "recording" line up as closely as the platform allows.
 *
 * WHY A PENDING-INTENT CALLBACK, NOT A LISTENER
 *
 * The app is dormant between calls, by design - there is no permanent service
 * to hold a normal NetworkCallback, and one registered by the process would die
 * with it and never see Wi-Fi return. registerNetworkCallback with a
 * PendingIntent is the one form the SYSTEM keeps: it fires the intent - waking a
 * stopped app - when a Wi-Fi network appears, with nothing of ours running in
 * between. Same shape as the keep-alive job, but triggered by the exact event
 * that matters instead of a clock.
 *
 * It does not survive a reboot or a force-stop, so it is re-armed on boot and on
 * every app open, next to the job it complements. Re-registering the same
 * PendingIntent just replaces the old registration, so arming is idempotent.
 * Off disarms it: a switched-off recorder should watch for nothing.
 */
object NetworkRevive {

    private const val TAG = "JemRec"

    fun arm(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        runCatching {
            cm.registerNetworkCallback(request, pendingIntent(context))
            Log.i(TAG, "netrevive: armed; will revive the recorder when Wi-Fi returns")
        }.onFailure { Log.w(TAG, "netrevive: could not arm the Wi-Fi watch", it) }
    }

    fun disarm(context: Context) {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching { cm.unregisterNetworkCallback(pendingIntent(context)) }
    }

    /**
     * Whether the phone is on Wi-Fi right now. Wireless debugging exists only
     * on a Wi-Fi network - the framework turns it off the moment there is none
     * - so a revive attempted anywhere else cannot succeed: it re-arms the
     * switch for nothing, waits out a connect that has nowhere to go, and
     * shows "Starting..." for ten seconds before admitting it. Unknown reads as
     * on, so a phone that cannot answer still gets its attempt.
     */
    fun onWifi(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork ?: return false) ?: return false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(true)

    private fun pendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, NetworkReviveReceiver::class.java)
            .setAction(ACTION_WIFI_BACK)
        return PendingIntent.getBroadcast(
            context,
            0,
            intent,
            // MUTABLE because the system fills in the network extras when it
            // fires; UPDATE_CURRENT so re-arming replaces rather than stacks.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }

    private const val ACTION_WIFI_BACK = "com.jemcik.jemrec.WIFI_BACK"
}
