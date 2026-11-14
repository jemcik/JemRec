package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent

// Whether two ways of decoding a recording give the same
// sound: a CRC of every sample, the samples counted, and
// when the first chunk starts.
//   --es op pcmhash --es name <file name> [--ez join true]
//   [--el from <us>] [--el until <us>] [--ei skip <samples>]
internal object PcmHashOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val name = intent.getStringExtra("name").orEmpty()
        val recording = com.jemcik.jemrec.recordings.Recordings.list(appContext, withCallers = false)
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
            com.jemcik.jemrec.audio.PcmDecoder.decode(
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
}
