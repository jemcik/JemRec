package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent

// The emulator's sounds turned down for a check run
// (tools/emulator_checks.py, quieten): --es set "2:3,3:4"
// is stream:index pairs. From the app in front, not the
// shell: Android 17's audio hardening ignores the shell's
// volume changes ("AudioHardening volume control ... would
// be ignored for com.android.server.media (2000)", the
// 37.2 AVD, 2026-10-28).
internal object VolumeOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val audio = appContext.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
        val text = intent.getStringExtra("set").orEmpty().split(',').filter { ':' in it }
            .joinToString(" ") { pair ->
                runCatching {
                    val stream = pair.substringBefore(':').trim().toInt()
                    audio.setStreamVolume(stream, pair.substringAfter(':').trim().toInt(), 0)
                    "$stream=${audio.getStreamVolume(stream)}"
                }.getOrElse { "$pair failed: ${it.message}" }
            }
        report(appContext, "volume", text)
    }
}
