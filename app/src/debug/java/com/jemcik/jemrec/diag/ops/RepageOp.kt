package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer

// Whether Android hears a recording the same with its pages at the
// positions Ogg Opus means (OggPages.mend) as with the positions it was
// saved with: two copies, one as it is and one mended, each decoded through
// MediaExtractor and MediaCodec (a CRC of every sample, the samples counted,
// the first chunk's time) and measured by MediaMetadataRetriever and
// MediaPlayer. The mended copy is CRC'd again with as many samples left out
// at its start as it has more than the other: whether that is all that
// differs. Both copies stay in the app's external files, repage/, to pull.
//   --es op repage --es name <file name>
internal object RepageOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val name = intent.getStringExtra("name").orEmpty()
        val recording = com.jemcik.jemrec.recordings.Recordings.list(appContext, withCallers = false)
            .firstOrNull { it.name == name }
        if (recording == null) {
            report(appContext, "repage", "no recording named '$name'")
            return
        }
        val dir = File(appContext.getExternalFilesDir(null), "repage").apply { deleteRecursively(); mkdirs() }
        val asSaved = File(dir, "as-saved.ogg")
        appContext.contentResolver.openInputStream(recording.uri)!!.use { input ->
            asSaved.outputStream().use { input.copyTo(it) }
        }
        val mended = File(dir, "mended.ogg")
        asSaved.copyTo(mended)
        var writes = 0
        val whole = RandomAccessFile(mended, "rw").use { file ->
            val channel = file.channel
            com.jemcik.jemrec.audio.OggPages.mend(
                read = { offset, into, at, length -> channel.read(ByteBuffer.wrap(into, at, length), offset) },
                write = { offset, page -> channel.write(ByteBuffer.wrap(page), offset); writes++ },
            )
        }
        val saved = decoded(appContext, asSaved, dump = File(dir, "as-saved.pcm"))
        val fixed = decoded(appContext, mended, dump = File(dir, "mended.pcm"))
        val more = (fixed.samples - saved.samples).toInt()
        val tail = decoded(appContext, mended, skip = maxOf(0, more))
        report(
            appContext, "repage",
            "$name: $writes page(s) rewritten, pages read through: $whole\n" +
                "as saved: $saved; ${lengths(appContext, asSaved)}\n" +
                "mended: $fixed; ${lengths(appContext, mended)}\n" +
                "mended less its first $more samples: crc ${"%08x".format(tail.crc)}, the same as saved: ${tail.crc == saved.crc}",
        )
    }

    private class Decoded(val crc: Long, val samples: Long, val firstUs: Long) {
        override fun toString() = "crc ${"%08x".format(crc)}, $samples samples, first at $firstUs us"
    }

    /** [file] decoded, the first [skip] samples (all channels) left out of
     *  the CRC and the count; into [dump] as 16-bit little-endian PCM too. */
    private fun decoded(context: Context, file: File, skip: Int = 0, dump: File? = null): Decoded {
        val out = dump?.outputStream()?.buffered()
        val crc = java.util.zip.CRC32()
        var samples = 0L
        var firstUs = -1L
        var left = skip
        val bytes = ByteArray(2)
        com.jemcik.jemrec.audio.PcmDecoder.decode(
            context, Uri.fromFile(file),
            onFormat = { _, _ -> },
            onPcm = { pcm, atUs ->
                if (firstUs < 0) firstUs = atUs
                while (pcm.hasRemaining()) {
                    val v = pcm.get().toInt()
                    if (left > 0) { left--; continue }
                    samples++
                    bytes[0] = v.toByte()
                    bytes[1] = (v shr 8).toByte()
                    crc.update(bytes, 0, 2)
                    out?.write(bytes)
                }
                true
            },
        )
        out?.close()
        return Decoded(crc.value, samples, firstUs)
    }

    private fun lengths(context: Context, file: File): String {
        val retrieved = MediaMetadataRetriever().use {
            it.setDataSource(file.path)
            it.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
        }
        val played = runCatching {
            MediaPlayer().run {
                try {
                    setDataSource(context, Uri.fromFile(file))
                    prepare()
                    duration
                } finally {
                    release()
                }
            }
        }.getOrElse { "failed: ${it.javaClass.simpleName}" }
        return "MediaMetadataRetriever $retrieved ms, MediaPlayer $played ms"
    }
}
