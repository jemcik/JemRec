package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress

// Does an untrusted_app SELinux domain get to connect to
// an abstract socket published by the shell domain? If yes,
// ADB is only a bootstrap and everything after it works with
// no adbd, no mDNS and no network at all - which is the only
// remaining route to recording with Wi-Fi off, since shell
// cannot set service.adb.tcp.port on this device.
internal object ProbeOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val name = intent.getStringExtra("name") ?: "jemrec_probe"
        val abstractResult = runCatching {
            LocalSocket().use { sock ->
                sock.connect(
                    LocalSocketAddress(name, LocalSocketAddress.Namespace.ABSTRACT)
                )
                sock.inputStream.bufferedReader().readLine() ?: "<connected, no data>"
            }
        }.fold(
            { "OK   abstract:$name -> $it" },
            { "FAIL abstract:$name -> ${it.javaClass.simpleName}: ${it.message}" },
        )

        val port = intent.getIntExtra("tcp", 28471)
        val tcpResult = runCatching {
            java.net.Socket().use { sock ->
                sock.connect(
                    java.net.InetSocketAddress("127.0.0.1", port), 3_000,
                )
                sock.getInputStream().bufferedReader().readLine()
                    ?: "<connected, no data>"
            }
        }.fold(
            { "OK   tcp:127.0.0.1:$port -> $it" },
            { "FAIL tcp:127.0.0.1:$port -> ${it.javaClass.simpleName}: ${it.message}" },
        )

        report(appContext, "probe", abstractResult + "\n" + tcpResult)
    }
}
