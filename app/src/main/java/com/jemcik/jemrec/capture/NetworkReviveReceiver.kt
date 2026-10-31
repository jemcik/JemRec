package com.jemcik.jemrec.capture

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Woken by the system when Wi-Fi comes back (see [NetworkRevive]). Starts a
 * keep-alive run to bring the daemon up at once, so a call made moments after
 * returning to Wi-Fi still records instead of waiting out the 15-minute period.
 *
 * A run of the keep-alive job rather than a revive inline: the job already
 * owns bring-up, its retry/backoff handles the case where Wi-Fi flickers, and
 * revive() no-ops when the daemon is already alive - so a burst of network
 * events costs nothing. Its own run, not the kick: a kick waiting out a retry
 * took this one's place until 0.25.3 (RecorderKeepAlive.kickForWifi).
 */
class NetworkReviveReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Gated on setup, not the switch: the daemon is kept alive whenever the
        // phone is set up, so it can be revived the instant Wi-Fi returns even
        // if recording is currently switched off.
        if (!CaptureDaemon.isSetUp(context)) return
        Log.i(TAG, "netrevive: Wi-Fi is back, reviving the recorder now")
        RecorderKeepAlive.kickForWifi(context)
    }

    private companion object {
        const val TAG = "JemRec"
    }
}
