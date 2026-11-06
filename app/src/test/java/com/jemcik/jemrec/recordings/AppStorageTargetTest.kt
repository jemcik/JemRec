package com.jemcik.jemrec.recordings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File
import java.io.FileOutputStream

/**
 * A recording written into app storage, the last place RecordingStore falls
 * back to (no folder chosen, and a MediaStore that makes no row): what is
 * written is the whole file, whatever was there under that name before.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class AppStorageTargetTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test fun aFileWrittenAgainHoldsOnlyWhatWasWritten() {
        FakeStorage.forgetFileProviderFolders()
        FakeMediaStore.install()
        val name = "20261101_120000_in.ogg"
        val file = File(FakeStorage.appFolder(context), name)
        // A longer file of that name from a save before - tagged, say, and
        // this one untagged.
        file.writeBytes(ByteArray(100) { 7 })
        val target = RecordingStore.open(context, name)!!
        try {
            assertNotNull("app storage, not MediaStore", target.filePath)
            FileOutputStream(target.descriptor.fileDescriptor).apply { write(byteArrayOf(1, 2, 3)); flush() }
        } finally {
            // Ends the waveform's "being written" mark, which outlives a test.
            target.publish()
        }
        assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
    }
}
