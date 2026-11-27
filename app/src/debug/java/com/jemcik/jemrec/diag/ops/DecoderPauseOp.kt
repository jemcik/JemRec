package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import androidx.core.content.edit
import com.jemcik.jemrec.audio.PcmDecoder
import com.jemcik.jemrec.diag.DiagPrefs
import java.io.File

// Every packet read of the app's decoders - the waveforms, the
// silence check, Share's conversion - and every chunk PcmDecoder hands
// on, held for as long as the file files/decoder-pause is there, 30 s
// at most. The emulator checks stop the app with a decode under way,
// and its thread parks there (decoder_pause). 1.9 read with the
// decoder's buffer out; output read inside the buffer would sit
// there too. A file, as a stopped app takes no broadcast. Kept on across
// the app's restarts until --ez on false (DemoProvider puts it
// back), so it holds from a process's first read.
internal object DecoderPauseOp : DiagOp {
    const val KEY = "decoder_pause"
    private const val MOST_MS = 30_000L

    /** The hold on or off, in this process. */
    fun install(context: Context, on: Boolean) {
        val flag = File(context.filesDir, "decoder-pause")
        val hold: (() -> Unit)? = if (on) {
            {
                val until = android.os.SystemClock.uptimeMillis() + MOST_MS
                while (flag.exists() && android.os.SystemClock.uptimeMillis() < until) Thread.sleep(10)
            }
        } else {
            null
        }
        PcmDecoder.beforeRead = hold
        PcmDecoder.beforePcm = hold
    }

    override suspend fun run(appContext: Context, intent: Intent) {
        val on = intent.getBooleanExtra("on", true)
        DiagPrefs.of(appContext).edit { putBoolean(KEY, on) }
        install(appContext, on)
        report(appContext, "decoderpause", if (on) "each read held while files/decoder-pause is there" else "reads as they come")
    }
}
