package com.jemcik.jemrec.diag

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.provider.Settings
import android.util.Log
import com.jemcik.jemrec.adb.AdbTransport
import com.jemcik.jemrec.adb.ResultFile
import com.jemcik.jemrec.capture.SelfTest
import com.jemcik.jemrec.capture.CaptureDaemon
import com.jemcik.jemrec.capture.CallMonitorService
import com.jemcik.jemrec.capture.RecorderKeepAlive
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.security.SecureRandom

/**
 * Debug-only entry point that makes the transport checks scriptable from a
 * USB shell, so the three network states that matter can be tested without
 * anyone tapping through the UI in each one.
 *
 * WHY THERE IS A TOKEN
 *
 * This receiver runs arbitrary shell commands through an ADB connection that
 * already holds shell privilege. Exported, that is a local privilege
 * escalation: any installed app could broadcast to it. It is exported anyway
 * because `am broadcast` cannot reach a non-exported component, and a
 * non-scriptable diagnostic defeats the purpose.
 *
 * The guard is a random token stored on first use and printed to logcat.
 * Since Android 10 an app can read only its OWN logcat, while adb can read all
 * of it - so possession of the token is a decent proxy for "is a USB shell".
 * Send any command without a token and the receiver logs the expected value and
 * refuses; that bootstraps the caller without ever exposing the token to
 * another app.
 *
 *   adb shell am broadcast -n com.jemcik.jemrec/.diag.SpikeReceiver --es op selftest
 *   adb logcat -d -s JemRec | grep 'diag token'
 *   adb shell am broadcast -n com.jemcik.jemrec/.diag.SpikeReceiver \
 *       --es token <t> --es op selftest
 *
 * BY COMPONENT, not by action: this receiver declares no intent-filter, so
 * `am broadcast -a com.jemcik.jemrec.DIAG` resolves to nothing and reports
 * success while delivering to no one.
 *
 * AND IT IS NOT RELIABLE ON THIS PHONE. Honor's iAware drops broadcasts to a
 * manifest receiver whose app it has decided is idle - measured here as
 * "Enqueued broadcast ... : 0" in the system log with the receiver never
 * running, the same behaviour that stops the app being woken for a call. Start
 * the app first and it usually lands. Anything that must not be flaky writes
 * the preference directly with `run-as` instead; tools/shoot.py does.
 */
class SpikeReceiver : BroadcastReceiver() {

    private companion object {
        const val TAG = "JemRec"
        const val PREFS = "jemrec_diag"
        const val KEY_TOKEN = "token"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val appContext = context.applicationContext

        val expected = tokenFor(appContext)
        if (intent.getStringExtra("token") != expected) {
            Log.i(TAG, "diag token: $expected")
            Log.w(TAG, "diag rejected: wrong or missing token; resend with --es token <value>")
            return
        }

        val op = intent.getStringExtra("op").orEmpty()
        // goAsync keeps the receiver alive past onReceive's return. The budget
        // is around ten seconds, which is why nothing here waits 20s for mDNS
        // the way the UI's "Find port" button does.
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (op) {
                    "pair" -> {
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

                    "connect" -> {
                        val port = intent.getIntExtra("port", -1)
                        val result = if (port > 0) {
                            AdbTransport.connect(appContext, AdbTransport.LOOPBACK, port)
                        } else {
                            AdbTransport.autoConnect(appContext, timeoutMs = 4_000)
                        }
                        report(
                            appContext, "connect",
                            result.fold({ "connected" }, { "connect failed: ${it.message}" }),
                        )
                    }

                    "exec" -> {
                        val cmd = intent.getStringExtra("cmd").orEmpty()
                        report(
                            appContext, "exec",
                            AdbTransport.exec(cmd).fold({ it }, { "exec failed: ${it.message}" }),
                        )
                    }

                    // The same check the app's own Self-test button runs.
                    // It used to be the transport's acceptance check, which
                    // asked whether an ADB shell session existed - a question
                    // that became meaningless once the app started closing that
                    // session on purpose the moment the recorder was up.
                    "selftest" -> {
                        val text = SelfTest.run(appContext).report
                        ResultFile.write(appContext, text)
                        report(appContext, "selftest", text)
                    }

                    // Does an untrusted_app SELinux domain get to connect to
                    // an abstract socket published by the shell domain? If yes,
                    // ADB is only a bootstrap and everything after it works with
                    // no adbd, no mDNS and no network at all - which is the only
                    // remaining route to recording with Wi-Fi off, since shell
                    // cannot set service.adb.tcp.port on this device.
                    "probe" -> {
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

                    // Can the app re-arm Wireless debugging by itself, given
                    // WRITE_SECURE_SETTINGS? If so, recovering after a reboot
                    // needs no user action at all, and the one manual step left
                    // in the whole design disappears.
                    "armadb" -> {
                        val text = runCatching {
                            Settings.Global.putInt(
                                appContext.contentResolver, "adb_wifi_enabled", 1,
                            )
                        }.fold(
                            { accepted ->
                                val now = Settings.Global.getInt(
                                    appContext.contentResolver, "adb_wifi_enabled", -1,
                                )
                                "putInt accepted=$accepted; adb_wifi_enabled is now $now"
                            },
                            { "FAILED: ${it.javaClass.simpleName}: ${it.message}" },
                        )
                        report(appContext, "armadb", text)
                    }

                    // Start the shell-side daemon from the jar bundled in this
                    // APK. The one operation that genuinely needs ADB.
                    "bootstrap" -> {
                        val text = CaptureDaemon.ensureRunning(appContext).fold(
                            { "daemon running on 127.0.0.1:${CaptureDaemon.PORT}" },
                            { "bootstrap failed: ${it.message}" },
                        )
                        report(appContext, "bootstrap", text + "\n---\n" + CaptureDaemon.readLog())
                    }

                    "daemon" -> report(
                        appContext, "daemon",
                        if (CaptureDaemon.isRunning(appContext)) "running" else "not running",
                    )

                    // Arm the keep-alive job that revives the daemon, the way
                    // app-open and boot do. The permanent monitor service is
                    // gone; the daemon watches for calls itself.
                    "monitor" -> {
                        RecorderKeepAlive.kickNow(appContext)
                        kotlinx.coroutines.delay(2_000)
                        report(appContext, "monitor", "keep-alive kicked; see the log")
                    }

                    // The README's screenshots: swap the real list for invented
                    // calls, or put it back. See DemoRecordings.
                    "demo" -> {
                        val on = intent.getBooleanExtra("on", false)
                        DemoRecordings.set(appContext, on)
                        report(
                            appContext, "demo",
                            if (on) "invented recordings on - reopen the app to see them"
                            else "invented recordings off",
                        )
                    }

                    "check" -> {
                        CaptureDaemon.revive(appContext)
                        report(
                            appContext, "check",
                            if (CaptureDaemon.isRunning(appContext)) "daemon is running"
                            else "daemon still down",
                        )
                    }

                    "pairnotif" -> {
                        com.jemcik.jemrec.ui.PairingNotification.show(appContext)
                        kotlinx.coroutines.delay(1_500)
                        report(appContext, "pairnotif", "pairing notification posted")
                    }

                    "monitorstop" -> {
                        CallMonitorService.stop(appContext)
                        kotlinx.coroutines.delay(1_000)
                        report(appContext, "monitorstop", "monitor stop requested")
                    }

                    "status" -> report(
                        appContext, "status",
                        if (AdbTransport.isConnected) "connected" else "not connected",
                    )

                    else -> report(appContext, "?", "unknown op '$op'")
                }
            } catch (t: Throwable) {
                Log.e(TAG, "diag op '$op' threw", t)
                report(appContext, op, "threw ${t.javaClass.simpleName}: ${t.message}")
            } finally {
                pending.finish()
            }
        }
    }

    /** Logged line by line: logcat truncates very long single messages. */
    private fun report(context: Context, op: String, text: String) {
        Log.i(TAG, "diag[$op] >>>")
        text.lineSequence().forEach { Log.i(TAG, "diag[$op] $it") }
        Log.i(TAG, "diag[$op] <<<")
        ResultFile.write(context, text)
    }

    private fun tokenFor(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_TOKEN, null)?.let { return it }
        val bytes = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY_TOKEN, token).apply()
        return token
    }
}
