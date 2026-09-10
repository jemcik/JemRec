package com.jemcik.jemrec.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.net.Socket

/**
 * The daemon's wire format, as the app reads it. Every saved recording goes
 * through this reader, so it is pinned byte by byte: flag bits out of the pts,
 * clean ends and cut ends both as end-of-stream, and an implausible length as
 * a loud failure rather than an allocation.
 */
class AudioStreamTest {

    /** An unconnected Socket whose input is canned bytes; AudioStream only calls getInputStream() and close(). */
    private class CannedSocket(private val bytes: ByteArray) : Socket() {
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
    }

    private fun stream(build: DataOutputStream.() -> Unit): AudioStream {
        val buf = ByteArrayOutputStream()
        DataOutputStream(buf).apply { build(); flush() }
        return AudioStream(CannedSocket(buf.toByteArray()))
    }

    private fun DataOutputStream.codec(id: String) = write(id.toByteArray(Charsets.US_ASCII))
    private fun DataOutputStream.packet(pts: Long, payload: ByteArray, flags: Long = 0L) {
        writeLong(pts or flags); writeInt(payload.size); write(payload)
    }

    private val flagSession = 1L shl 63
    private val flagConfig = 1L shl 62
    private val flagKeyFrame = 1L shl 61

    @Test fun codecIdIsReadOnceAndTrimmed() {
        assertEquals("raw", stream { codec("raw ") }.codecId)
        assertEquals("opus", stream { codec("opus") }.codecId)
        // What the daemon answers, in the codec id's slot, for a file it no
        // longer has - RecordingSaver reads it before expecting any packet.
        assertEquals("gone", stream { codec("gone") }.codecId)
        assertNull(stream { codec("gone") }.readPacket())
    }

    @Test fun configFlagIsSeparatedFromThePts() {
        val s = stream { codec("opus"); packet(1234, byteArrayOf(0x4f, 0x70), flags = flagConfig) }
        val p = s.readPacket()
        assertNotNull(p)
        assertEquals(1234L, p!!.pts)
        assertTrue(p.isConfig)
        assertFalse(p.isKeyFrame)
        assertArrayEquals(byteArrayOf(0x4f, 0x70), p.data)
    }

    @Test fun keyFrameAndSessionFlagsAreMaskedOutOfThePts() {
        val s = stream {
            codec("opus")
            packet(5, byteArrayOf(1), flags = flagKeyFrame)
            packet(7, byteArrayOf(2), flags = flagSession)
        }
        val key = s.readPacket()!!
        assertEquals(5L, key.pts); assertTrue(key.isKeyFrame); assertFalse(key.isConfig)
        val session = s.readPacket()!!
        assertEquals(7L, session.pts); assertFalse(session.isKeyFrame); assertFalse(session.isConfig)
    }

    @Test fun endsCleanlyOnAPacketBoundaryAndStaysEnded() {
        val s = stream { codec("opus"); packet(0, byteArrayOf(9)); packet(20_000, byteArrayOf()) }
        assertEquals(0L, s.readPacket()!!.pts)
        assertEquals(0, s.readPacket()!!.data.size)
        assertNull(s.readPacket())
        assertNull(s.readPacket())
    }

    @Test fun eofInsideAHeaderOrPayloadReadsAsEndOfStream() {
        // Cut inside the pts.
        assertNull(stream { codec("opus"); writeInt(0) }.readPacket())
        // Cut inside the length.
        assertNull(stream { codec("opus"); writeLong(0); writeShort(0) }.readPacket())
        // Cut inside the payload. RecordingSaver treats this as a complete read
        // and saves what it has - right for a crash-truncated at-rest file, and
        // pinned here so it stays a decision rather than an accident.
        assertNull(stream { codec("opus"); writeLong(0); writeInt(10); write(ByteArray(5)) }.readPacket())
    }

    @Test fun implausibleLengthFailsLoudlyInsteadOfAllocating() {
        assertThrows(IllegalStateException::class.java) {
            stream { codec("opus"); writeLong(0); writeInt((1 shl 20) + 1) }.readPacket()
        }
        assertThrows(IllegalStateException::class.java) {
            stream { codec("opus"); writeLong(0); writeInt(-1) }.readPacket()
        }
    }

    @Test fun packetEqualityIsByPtsAndBytesOnly() {
        val a = AudioStream.Packet(1, isConfig = false, isKeyFrame = false, data = byteArrayOf(1, 2))
        val b = AudioStream.Packet(1, isConfig = true, isKeyFrame = true, data = byteArrayOf(1, 2))
        val c = AudioStream.Packet(1, isConfig = false, isKeyFrame = false, data = byteArrayOf(1, 3))
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertFalse(a == c)
    }
}
