package com.jemcik.jemrec.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import java.nio.ByteBuffer

// CodecBuffers.kt's interfaces on Android's own classes: the only part of it
// that uses Android, kept out so the rest compiles and is tested without it.

/** [Packets] from a file. */
internal class FilePackets(private val extractor: MediaExtractor) : Packets {
    override val sampleTime: Long get() = extractor.sampleTime
    override val sampleSize: Long get() = extractor.sampleSize
    override fun readSampleData(buffer: ByteBuffer, offset: Int): Int {
        PcmDecoder.beforeRead?.invoke()
        return extractor.readSampleData(buffer, offset)
    }

    override fun advance() = extractor.advance()
}

/** [CodecInput] of a MediaCodec, each wait for a free buffer [timeoutUs]
 *  long at most. */
internal class MediaCodecInput(private val codec: MediaCodec, private val timeoutUs: Long) : CodecInput {
    override fun dequeue() = codec.dequeueInputBuffer(timeoutUs)

    // Asked again for the copy: MediaCodec hands an index's buffer out anew,
    // and the one before is not to be used (getInputBuffer's documentation).
    override fun capacity(index: Int) = codec.getInputBuffer(index)!!.capacity()
    override fun buffer(index: Int): ByteBuffer = codec.getInputBuffer(index)!!
    override fun queue(index: Int, size: Int, atUs: Long) = codec.queueInputBuffer(index, 0, size, atUs, 0)
    override fun end(index: Int) = codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
}
