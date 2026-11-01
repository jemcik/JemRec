package com.jemcik.jemrec.capture

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs

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
 * Wi-Fi coming back and revives the daemon within seconds, so "on Wi-Fi" and
 * "recording" line up as closely as the platform allows: on the Honor,
 * 2026-10-31, the run began 3 s after Wi-Fi and the recorder answered at 9 s.
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
 * Off leaves it armed, as it leaves the keep-alive: Off is soft, and the
 * recorder stays (RecorderSwitch.turn).
 *
 * AND IT IS ONE-SHOT, WHICH IS WHY THE KEEP-ALIVE ARMS IT TOO
 *
 * The system sends the PendingIntent the first time a matching network is
 * available - at once, if one already is - and five seconds after sending it
 * RELEASES the registration ("so the receiving client has an opportunity to
 * put in its own request", ConnectivityService). Seen on the emulator, every
 * time: REGISTER, "Sending PendingIntent", "Finished sending", RELEASE five
 * seconds later. Armed on app open - which is nearly always on Wi-Fi - it
 * fired for the Wi-Fi it was already on and was gone long before the phone
 * left that network, so the Wi-Fi return it exists for never woke anything.
 * It only ever worked armed off Wi-Fi, which boot away from home happened to
 * do. So the keep-alive job arms it whenever it runs off Wi-Fi: the one state
 * in which the watch has something to wait for.
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
     *
     * Any Wi-Fi the phone has joined, not only Android's default network: a
     * Wi-Fi without internet stays joined while Android sends everything else
     * over mobile data, and Wireless debugging - which asks only whether
     * Wi-Fi is joined - works on it, as the alert promises ("Any network will
     * do - it does not need internet"). Asking the default network said "not
     * on Wi-Fi" there: the Android 12 AVD, 2026-10-28, its Wi-Fi joined with
     * its internet blocked, mobile data the default, and the revive stopped.
     */
    fun onWifi(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return true
        wifiNetwork(cm) != null
    }.getOrDefault(true)

    /** The Wi-Fi network the phone has joined, default or not - not a VPN
     *  over it, which carries its transports. */
    @Suppress("DEPRECATION") // allNetworks: its replacement is a callback, and this is asked in passing
    private fun wifiNetwork(cm: ConnectivityManager): Network? = cm.allNetworks.firstOrNull { network ->
        cm.getNetworkCapabilities(network)?.let {
            it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
        } == true
    }

    /**
     * Android refused Wireless debugging on the Wi-Fi the phone is on now:
     * switched on, it read off again a moment later, with "Allow wireless
     * debugging on this network?" on screen. Android 12 keeps the switch off
     * while it asks and after Cancel, so every revive that switched it on
     * asked again; on an allowed Wi-Fi it stays on - straight after all
     * debugging comes back on too. Measured on the Android 12 AVD,
     * 2026-10-28: refused, it read 0 with the question up, and a keep-alive
     * after Cancel asked again; allowed, it read 1 for 25 s after Turn all
     * debugging off and back on, and after the revive's own switch-on.
     * (Android 17 leaves it on after Cancel and asks once - 0.10's notes.)
     */
    fun noteWirelessRefused(context: Context) = noteHere(context, KEY_REFUSED_ON)

    /** Asked again: the user, at Start without Wi-Fi's switch, or by Allow
     *  this Wi-Fi's revive as it switches Wireless debugging off and on. */
    fun forgetWirelessRefused(context: Context) {
        Prefs.of(context).edit { remove(KEY_REFUSED_ON) }
    }

    /**
     * Refused here and still off. Wireless debugging found [on] after all is
     * an Allow - tapped on the question that stayed up, or from "Allow this
     * Wi-Fi" - and the refusal is forgotten: kept, it would have revives
     * leave the switch off the next time it goes off on this connection (an
     * adbd restart can do that: on the Honor, 2026-10-28, it read 0 after
     * the port was opened).
     */
    fun stillRefusedHere(context: Context, on: Boolean): Boolean {
        if (!wirelessRefusedHere(context)) return false
        if (on) forgetWirelessRefused(context)
        return !on
    }

    fun wirelessRefusedHere(context: Context): Boolean = notedHere(context, KEY_REFUSED_ON)

    /**
     * A revive switched Wireless debugging on, or off and on, on the Wi-Fi
     * connection the phone is on now, and the recorder has not been up since
     * (CaptureDaemon.revive forgets it then). A routine revive switches it
     * off and on once per connection at most (wirelessDebuggingStep), and
     * RecorderHealth takes nothing advertised after such a switch for a
     * Wi-Fi not allowed.
     */
    fun noteSwitchedOn(context: Context) = noteHere(context, KEY_SWITCHED_ON)

    fun forgetSwitchedOn(context: Context) {
        Prefs.of(context).edit { remove(KEY_SWITCHED_ON) }
    }

    fun switchedOnHere(context: Context): Boolean = notedHere(context, KEY_SWITCHED_ON)

    /** A note under [key] for the Wi-Fi connection the phone is on now
     *  ([connection]); none off Wi-Fi. */
    private fun noteHere(context: Context, key: String) {
        val here = connection(context) ?: return
        Prefs.of(context).edit { putString(key, here) }
    }

    private fun notedHere(context: Context, key: String): Boolean {
        val here = connection(context) ?: return false
        return Prefs.of(context).getString(key, null) == here
    }

    /**
     * This Wi-Fi connection: the boot and the Wi-Fi network's handle (the
     * Wi-Fi's, as for [onWifi], not the default network's). A handle is new
     * for each join but not across boots - network ids start again, and the
     * Android 12 AVD's Wi-Fi was network 101 or 102 at each boot - so a
     * refusal noted before a restart would otherwise stand for a Wi-Fi
     * allowed since.
     */
    private fun connection(context: Context): String? {
        val handle = runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.let(::wifiNetwork)?.networkHandle
        }.getOrNull()?.takeIf { it != 0L } ?: return null
        val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
        return "$boot:$handle"
    }

    private const val KEY_REFUSED_ON = "wireless_debugging_refused_on"
    private const val KEY_SWITCHED_ON = "wireless_debugging_switched_on"

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
