package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat

// What a recording's decoder says one input buffer takes
// (max-input-size, on its input format) against what each buffer
// holds: Android makes each at that size (CCodecBuffers.cpp,
// LinearInputBuffers::Alloc). PcmDecoder joins up to each buffer's
// own capacity, read before the packets are; this is the record of
// the two on a device (tmp/decoder-crash/PLAN.md, 2026-11-26).
//   --es op codecroom --es name <file name>
internal object CodecRoomOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val name = intent.getStringExtra("name").orEmpty()
        val recording = com.jemcik.jemrec.recordings.Recordings.list(appContext, withCallers = false)
            .firstOrNull { it.name == name }
        if (recording == null) {
            report(appContext, "codecroom", "no recording named '$name'")
            return
        }
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(appContext, recording.uri, null)
            val format = extractor.getTrackFormat(0)
            val dec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = dec
            dec.configure(format, null, null, 0)
            dec.start()
            val said = dec.inputFormat.let { if (it.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) it.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else -1 }
            val asked = if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else -1
            val capacities = ArrayList<Int>()
            while (true) {
                val index = dec.dequeueInputBuffer(100_000)
                if (index < 0) break
                capacities += dec.getInputBuffer(index)!!.capacity()
            }
            report(
                appContext, "codecroom",
                "$name: ${dec.name}, the file's max-input-size $asked, the codec's $said, " +
                    "${capacities.size} buffers of ${capacities.distinct().joinToString("/")} bytes",
            )
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }
}
