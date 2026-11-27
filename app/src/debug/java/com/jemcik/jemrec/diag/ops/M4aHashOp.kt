package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import android.media.MediaExtractor
import java.io.File
import java.nio.ByteBuffer

// A recording converted as Share converts it (Transcoder.toM4a),
// and the .m4a's AAC frames read back: a CRC of every frame's
// bytes and time, so two builds' conversions can be compared.
//   --es op m4ahash --es name <file name> [--ez both true]
internal object M4aHashOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val name = intent.getStringExtra("name").orEmpty()
        val both = intent.getBooleanExtra("both", false)
        val recording = com.jemcik.jemrec.recordings.Recordings.list(appContext, withCallers = false)
            .firstOrNull { it.name == name }
        if (recording == null) {
            report(appContext, "m4ahash", "no recording named '$name'")
            return
        }
        val out = File(appContext.cacheDir, "m4ahash.m4a")
        val started = android.os.SystemClock.elapsedRealtime()
        val made = com.jemcik.jemrec.audio.Transcoder.toM4a(appContext, recording.uri, out, both)
        val took = android.os.SystemClock.elapsedRealtime() - started
        if (!made) {
            report(appContext, "m4ahash", "$name both=$both: not converted")
            return
        }
        val crc = java.util.zip.CRC32()
        var frames = 0
        var bytes = 0L
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(out.path)
            extractor.selectTrack(0)
            val buffer = ByteBuffer.allocate(64 * 1024)
            val time = ByteBuffer.allocate(8)
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                crc.update(buffer.array(), 0, size)
                time.clear()
                time.putLong(extractor.sampleTime)
                crc.update(time.array(), 0, 8)
                frames++
                bytes += size
                extractor.advance()
            }
        } finally {
            runCatching { extractor.release() }
            out.delete()
        }
        report(appContext, "m4ahash", "$name both=$both: $frames AAC frames, $bytes bytes, crc ${"%08x".format(crc.value)}, in $took ms")
    }
}
