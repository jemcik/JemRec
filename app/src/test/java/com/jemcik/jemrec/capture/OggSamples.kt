package com.jemcik.jemrec.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Ogg Opus files for the tests, made here - not by RecordingTags, which they
 *  check - from the headers a real recording starts with. */
internal object OggSamples {

    /**
     * The first two pages of a recording JemRec saved on the Honor
     * (2026-10-24), as MediaMuxer wrote them: OpusHead, then OpusTags with
     * vendor "libopus" and no fields. Headers only - no audio.
     */
    val honorHeaders = hex(
        "4f676753000200000000000000007348" +
            "336600000000c2b0d21101134f707573" +
            "486561640102380180bb00000000004f" +
            "67675300000000000000000000734833" +
            "6601000000fc597fa901174f70757354" +
            "616773070000006c69626f7075730000" +
            "0000",
    )

    /** The Honor's two header pages, then audio pages of made-up packets - one
     *  over 255 bytes, laced across segments - and the last marked as the
     *  stream's end. */
    fun recording(): ByteArray {
        val serial = ByteBuffer.wrap(honorHeaders, 14, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val audio = (0 until 4).map { i ->
            val body = ByteArray(if (i == 1) 700 else 120) { (it + i).toByte() }
            page(serial, seq = i + 2, granule = (i + 1) * 960L + 312, type = if (i == 3) 4 else 0, body = body)
        }
        return honorHeaders + audio.reduce { a, b -> a + b }
    }

    fun splice(file: ByteArray, facts: RecordingTags.Facts): ByteArray {
        val out = ByteArrayOutputStream()
        assertTrue(RecordingTags.splice(file.inputStream(), out, facts))
        return out.toByteArray()
    }

    fun applied(file: ByteArray, rewrite: Pair<Int, ByteArray>): ByteArray =
        file.copyOf().also { rewrite.second.copyInto(it, rewrite.first) }

    /** A page, made here without RecordingTags: the test's own reading of
     *  the format. */
    fun page(serial: Int, seq: Int, granule: Long, type: Int, body: ByteArray): ByteArray {
        val lacing = ByteArray(body.size / 255 + 1) { i -> if (i < body.size / 255) 255.toByte() else (body.size % 255).toByte() }
        val header = ByteBuffer.allocate(27).order(ByteOrder.LITTLE_ENDIAN)
            .put("OggS".toByteArray()).put(0).put(type.toByte()).putLong(granule).putInt(serial).putInt(seq).putInt(0)
            .put(lacing.size.toByte()).array()
        val page = header + lacing + body
        ByteBuffer.wrap(page, 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crcOf(page))
        return page
    }

    fun pages(file: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var at = 0
        while (at < file.size) {
            assertEquals("OggS", String(file, at, 4, Charsets.US_ASCII))
            val segments = file[at + 26].toInt() and 0xff
            val body = (0 until segments).sumOf { file[at + 27 + it].toInt() and 0xff }
            val end = at + 27 + segments + body
            out += file.copyOfRange(at, end)
            at = end
        }
        return out
    }

    /** The page's CRC as Ogg computes it: with the CRC's own four bytes zero. */
    fun crcOf(page: ByteArray): Int =
        RecordingTags.crc(page.copyOf().also { for (i in 22 until 26) it[i] = 0 })

    fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
