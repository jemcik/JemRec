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
        // it: this is the path the real pairing wizard takes. Not
        // that the phone's own NsdManager sees only what is live -
        // it can serve the connect service's old port from its cache
        // (#140, tmp/issue-140/PLAN.md) - but for the pairing
        // service four dialogs in a row on the Android 16 AVD were
        // each found on their own new port (2026-11-26).
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
