package com.jemcik.jemrec.diag

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.provider.Settings
import android.util.Log
import androidx.core.content.edit
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

                    // The answering machine, for tools/emulator_checks.py: a
                    // greeting from a raw 48 kHz mono PCM file the shell put
                    // where this app can read it, the switch on, and both
                    // handed to the recorder.
                    //   --es op answer --es greeting /data/local/tmp/jemrec-test-greeting.pcm
                    "answer" -> {
                        val path = intent.getStringExtra("greeting")
                        val text = runCatching {
                            if (path != null) {
                                com.jemcik.jemrec.capture.Greeting.save(appContext, java.io.File(path).readBytes())
                            }
                            com.jemcik.jemrec.capture.AnsweringMachine.setEnabled(appContext, intent.getBooleanExtra("on", true))
                            // All of it: the recording mode a check set too.
                            com.jemcik.jemrec.capture.CaptureDaemon.pushMode(appContext)
                            val set = com.jemcik.jemrec.capture.CaptureDaemon.setAnswering(appContext)
                            "greeting ${com.jemcik.jemrec.capture.Greeting.seconds(appContext)} s, " +
                                "wire ${com.jemcik.jemrec.capture.AnsweringMachine.wire(appContext)}, recorder took it: $set, " +
                                "recorder has ${com.jemcik.jemrec.capture.CaptureDaemon.greetingBytes(appContext)} bytes"
                        }.getOrElse { "failed: $it" }
                        report(appContext, "answer", text)
                    }

                    // Talk to them, as the "Taking a message" notification's
                    // button does, for tools/emulator_checks.py:
                    //   --es op talk
                    "talk" -> {
                        val ok = com.jemcik.jemrec.capture.CaptureDaemon.handOver(appContext, true)
                        report(appContext, "talk", "talk to them -> $ok")
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

                    // The messenger-call answering spike (docs/VOIP-ANSWER-SPIKE.md):
                    //   --es op voipanswer --ei after 5 --ei minutes 15
                    "voipanswer" -> {
                        val after = intent.getIntExtra("after", 5)
                        val minutes = intent.getIntExtra("minutes", 5)
                        VoipAnswerSpike.arm(appContext, after, minutes)
                        report(appContext, "voipanswer", "armed: the next messenger call is answered after $after s, for $minutes min; see files/voip-answer.log")
                    }

                    // A stand-in for a Google Meet call, for tools/emulator_checks.py:
                    //   --es op appcall --es do start|end
                    "appcall" -> {
                        val text = runCatching {
                            if (intent.getStringExtra("do") == "end") FakeAppCall.end() else FakeAppCall.start(appContext)
                        }.getOrElse { "failed: $it" }
                        report(appContext, "appcall", text)
                    }

                    // The calls since [since] that the missed-recording check and
                    // the self-test take for phone calls that went off-hook here,
                    // for tools/emulator_checks.py:
                    //   --es op answered --el since 1793016000000
                    "answered" -> {
                        val since = intent.getLongExtra("since", 0L)
                        val calls = com.jemcik.jemrec.capture.CallLogLookup.answeredCallsSince(appContext, since)
                        report(
                            appContext, "answered",
                            calls.joinToString("\n") { "${it.at} ${if (it.incoming) "in" else "out"} ${it.label}" }
                                .ifEmpty { "none" },
                        )
                    }

                    // Set one of the app's preferences, for tools/emulator_checks.py:
                    //   --es op pref --es key file_names --es value DATE_AND_CALLER
                    //   --es op pref --es key rules_other_numbers --ez bool false
                    //   --es op pref --es key emergency_call_at --el long 1792680000000
                    //   --es op pref --es key emergency_call_at   (no value: removed)
                    // Through the app's own SharedPreferences, because writing the
                    // file from outside loses to a running app process, which
                    // writes its own copy back over it (measured on Android 13).
                    "pref" -> {
                        val key = intent.getStringExtra("key").orEmpty()
                        val edit = com.jemcik.jemrec.Prefs.of(appContext).edit()
                        // commit, not apply: the value is on disk before the
                        // reply, which the tools act on at once.
                        @SuppressLint("ApplySharedPref")
                        val text = when {
                            key.isEmpty() -> "no key"
                            intent.hasExtra("bool") -> {
                                edit.putBoolean(key, intent.getBooleanExtra("bool", false)).commit()
                                "$key = ${intent.getBooleanExtra("bool", false)}"
                            }
                            intent.hasExtra("int") -> {
                                edit.putInt(key, intent.getIntExtra("int", 0)).commit()
                                "$key = ${intent.getIntExtra("int", 0)}"
                            }
                            intent.hasExtra("long") -> {
                                edit.putLong(key, intent.getLongExtra("long", 0L)).commit()
                                "$key = ${intent.getLongExtra("long", 0L)}"
                            }
                            intent.hasExtra("value") -> {
                                edit.putString(key, intent.getStringExtra("value")).commit()
                                "$key = ${intent.getStringExtra("value")}"
                            }
                            else -> {
                                edit.remove(key).commit()
                                "$key removed"
                            }
                        }
                        report(appContext, "pref", text)
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

                    // What THIS process reads for the settings setup decides
                    // by, to set against `settings get global` from a shell.
                    // Android 17's docs say adb_enabled and
                    // development_settings_enabled "always return 0 for all
                    // third-party apps"; this is how to see it, and whether
                    // adb_wifi_enabled went the same way. With --es write <name>
                    // --ei value <0|1> it writes first, to see whether writes
                    // still land.
                    "settings" -> {
                        val resolver = appContext.contentResolver
                        val write = intent.getStringExtra("write")
                        val wrote = write?.let { name ->
                            val value = intent.getIntExtra("value", 1)
                            runCatching { Settings.Global.putInt(resolver, name, value) }
                                .fold({ "putInt $name=$value accepted=$it" }, { "putInt $name FAILED: ${it.javaClass.simpleName}: ${it.message}" })
                        }
                        val reads = listOf(
                            "adb_enabled", "adb_wifi_enabled", "development_settings_enabled",
                            // A control nobody would hide.
                            "airplane_mode_on",
                        ).joinToString("\n") { name ->
                            val raw = runCatching { Settings.Global.getString(resolver, name) }
                                .getOrElse { "threw ${it.javaClass.simpleName}" }
                            "$name=$raw"
                        }
                        val about = "sdk=${android.os.Build.VERSION.SDK_INT}" +
                            (if (android.os.Build.VERSION.SDK_INT >= 36) " sdkFull=${android.os.Build.VERSION.SDK_INT_FULL}" else "") +
                            " target=${appContext.applicationInfo.targetSdkVersion}" +
                            " secureSettings=${com.jemcik.jemrec.ui.Setup.hasSecureSettings(appContext)}"
                        val concluded = "app concludes: usb=${com.jemcik.jemrec.ui.Setup.usbDebugging(appContext)}" +
                            " wireless=${com.jemcik.jemrec.ui.Setup.wirelessDebuggingOn(appContext)}" +
                            " devOptions=${com.jemcik.jemrec.ui.Setup.developerOptions(appContext)}" +
                            " actAsHidden=${com.jemcik.jemrec.capture.GlobalSettings.actAsHidden}"
                        report(appContext, "settings", listOfNotNull(about, wrote, reads, concluded).joinToString("\n"))
                    }

                    // Act like a Pixel on Android 17 that hides USB debugging
                    // and Developer options from apps: --ez on true|false.
                    // Kept across restarts; see DemoProvider.
                    "hidesettings" -> {
                        val on = intent.getBooleanExtra("on", true)
                        appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                            .edit { putBoolean(HIDE_SETTINGS_KEY, on) }
                        com.jemcik.jemrec.capture.GlobalSettings.actAsHidden = on
                        report(appContext, "hidesettings", "acting as if hidden: $on")
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
                    // Recently deleted's emulator checks (tools/emulator_checks.py,
                    // recently_deleted and recently_deleted_folder): a recording
                    // moved to the trash, as the list's delete moves it, and
                    // where it is now - in the list, in Recently deleted, or in
                    // neither - after the daily pass removes what has waited its
                    // 30 days, if asked:
                    //   --es op trash --es name <file name> [--ez move true] [--ez purge true]
                    "trash" -> {
                        val name = intent.getStringExtra("name").orEmpty()
                        val recordings = com.jemcik.jemrec.capture.Recordings
                        if (intent.getBooleanExtra("purge", false)) com.jemcik.jemrec.capture.Trash.purgeExpired(appContext)
                        val moved = if (intent.getBooleanExtra("move", false)) {
                            recordings.list(appContext, withCallers = false).firstOrNull { it.name == name }
                                ?.let { com.jemcik.jemrec.capture.Trash.trashAll(appContext, listOf(it)) }
                                ?.let { " (moved ${it.deleted}, failed ${it.failed}, asks ${it.needsConsent != null})" }
                                ?: " (no recording named so)"
                        } else {
                            ""
                        }
                        val listed = recordings.list(appContext, withCallers = false).any { it.name == name }
                        val trashed = com.jemcik.jemrec.capture.Trash.list(appContext).any { it.name == name }
                        report(appContext, "trash", "$name: listed=$listed trashed=$trashed$moved")
                    }

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

                    // Run the after-call audio check on any file, and post the
                    // notification a call with that verdict would get. For
                    // trying the thresholds on real recordings, on the phone,
                    // without the recording ever leaving it.
                    "audiocheck" -> {
                        val file = java.io.File(intent.getStringExtra("path").orEmpty())
                        val uri = android.net.Uri.fromFile(file)
                        val verdict = com.jemcik.jemrec.capture.AudioCheck.check(appContext, uri)
                        // Only with --ez notify true: run over a folder of
                        // recordings, one notification each is noise.
                        if (intent.getBooleanExtra("notify", false)) com.jemcik.jemrec.capture.AfterCall.show(
                            appContext,
                            com.jemcik.jemrec.capture.AfterCall.Call(
                                file.name, uri, file.absolutePath, 60.0, true,
                                System.currentTimeMillis(), "Test call", verdict,
                            ),
                        )
                        report(appContext, "audiocheck", "${file.name}: $verdict (levels in files/audiocheck.log)")
                    }

                    // How long a waveform takes to work out, one way or
                    // another, on the phone - nothing cached, nothing kept:
                    //   --es op waveform --es name <file name>
                    //   [--ei workers N] [--ez join false]
                    // One way per broadcast: a long call's can take longer
                    // than a broadcast may.
                    "waveform" -> {
                        val name = intent.getStringExtra("name").orEmpty()
                        val recording = com.jemcik.jemrec.capture.Recordings.list(appContext, withCallers = false)
                            .firstOrNull { it.name == name }
                        if (recording == null) {
                            report(appContext, "waveform", "no recording named '$name'")
                        } else {
                            val started = android.os.SystemClock.elapsedRealtime()
                            val levels = com.jemcik.jemrec.capture.Waveforms.measure(
                                appContext, recording.name, recording.uri,
                                workers = intent.getIntExtra("workers", 0),
                                join = intent.getBooleanExtra("join", true),
                            )
                            report(
                                appContext, "waveform",
                                // The levels themselves, to compare the ways of working them out.
                                "$name: ${if (levels == null) "could not be worked out" else "levels " + com.jemcik.jemrec.capture.Waveforms.toBytes(levels).joinToString(",")} " +
                                    "in ${android.os.SystemClock.elapsedRealtime() - started} ms (timings in files/waveform.log)",
                            )
                        }
                    }

                    // Whether two ways of decoding a recording give the same
                    // sound: a CRC of every sample, the samples counted, and
                    // when the first chunk starts.
                    //   --es op pcmhash --es name <file name> [--ez join true]
                    //   [--el from <us>] [--el until <us>] [--ei skip <samples>]
                    "pcmhash" -> {
                        val name = intent.getStringExtra("name").orEmpty()
                        val recording = com.jemcik.jemrec.capture.Recordings.list(appContext, withCallers = false)
                            .firstOrNull { it.name == name }
                        if (recording == null) {
                            report(appContext, "pcmhash", "no recording named '$name'")
                        } else {
                            val crc = java.util.zip.CRC32()
                            var samples = 0L
                            var chunks = 0
                            var firstUs = -1L
                            val formats = StringBuilder()
                            val bytes = ByteArray(64 * 1024)
                            // Samples (all channels) left out of the CRC at the start.
                            var skip = intent.getIntExtra("skip", 0)
                            com.jemcik.jemrec.capture.PcmDecoder.decode(
                                appContext, recording.uri,
                                onFormat = { rate, channels -> formats.append("$rate/$channels ") },
                                onPcm = { pcm, atUs ->
                                    if (firstUs < 0) firstUs = atUs
                                    chunks++
                                    val n = pcm.remaining()
                                    samples += n
                                    var at = 0
                                    while (pcm.hasRemaining()) {
                                        val v = pcm.get().toInt()
                                        if (skip > 0) { skip--; continue }
                                        bytes[at++] = v.toByte()
                                        bytes[at++] = (v shr 8).toByte()
                                        if (at == bytes.size) { crc.update(bytes, 0, at); at = 0 }
                                    }
                                    crc.update(bytes, 0, at)
                                    true
                                },
                                fromUs = intent.getLongExtra("from", 0L),
                                untilUs = intent.getLongExtra("until", Long.MAX_VALUE),
                                join = intent.getBooleanExtra("join", false),
                            )
                            report(appContext, "pcmhash", "$name: crc ${"%08x".format(crc.value)}, $samples samples in $chunks chunks, first at $firstUs us, formats $formats")
                        }
                    }

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
        prefs.edit { putString(KEY_TOKEN, token) }
        return token
    }
}
