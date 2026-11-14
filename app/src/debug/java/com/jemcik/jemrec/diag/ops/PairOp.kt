package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import android.util.Log
import com.jemcik.jemrec.adb.AdbTransport
import io.github.muntashirakon.adb.android.AdbMdns

internal object PairOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val code = intent.getStringExtra("code").orEmpty()
        // Discover the pairing port here rather than being told
        // it. `adb mdns services` on a host keeps serving
        // recently-seen entries after the service is gone, and
        // a stale port produces ECONNREFUSED that reads exactly
        // like a loopback problem. The phone's own NsdManager
        // sees only what is live, and this is the path the real
        // pairing wizard will take anyway.
        val given = intent.getIntExtra("port", -1)
        val port = if (given > 0) given else AdbTransport.discoverPort(
            appContext, AdbMdns.SERVICE_TYPE_TLS_PAIRING, timeoutMs = 15_000,
        ) ?: -1

        val text = if (port <= 0) {
            "no pairing service is being advertised - is the " +
                "'Pair device with pairing code' dialog still open?"
        } else {
            Log.i(TAG, "diag[pair] using pairing port $port")
            AdbTransport.pair(appContext, AdbTransport.LOOPBACK, port, code)
                .fold({ "paired on port $port" }, { "pair failed on port $port: ${it.message}" })
        }
        report(appContext, "pair", text)
    }
}
