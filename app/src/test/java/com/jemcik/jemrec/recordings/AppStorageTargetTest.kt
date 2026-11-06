package com.jemcik.jemrec.recordings

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
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
 * back to (no folder chosen, and a MediaStore that makes no row): a file that
 * has the name is left as it is, as the other two places leave it.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class AppStorageTargetTest {

    private val context = RuntimeEnvironment.getApplication()
    private val name = "20261101_120000_in.ogg"
    private val taken = "20261101_120000_in (1).ogg"
    private val bytes = byteArrayOf(1, 2, 3)

    @Before fun setUp() {
        FakeStorage.forgetFileProviderFolders()
        FakeMediaStore.install()
    }

    private fun write(target: RecordingStore.Target) =
        FileOutputStream(target.descriptor.fileDescriptor).run { write(bytes); flush() }

    @Test fun aFinishedFileOfTheNameIsLeftAlone() {
        // A part of a call that was published, its recording tried again for
        // the part that was not.
        val finished = File(FakeStorage.appFolder(context), name).apply { writeBytes(ByteArray(100) { 7 }) }
        val target = RecordingStore.open(context, name)!!
        assertEquals(taken, target.name)
        assertEquals(File(FakeStorage.appFolder(context), taken).absolutePath, target.filePath)
        write(target)
        assertTrue(target.publish())
        assertEquals(100L, finished.length())
        assertArrayEquals(bytes, File(target.filePath!!).readBytes())
    }

    @Test fun givenUpItLeavesTheFinishedFileToo() {
        val finished = File(FakeStorage.appFolder(context), name).apply { writeBytes(ByteArray(100) { 7 }) }
        val target = RecordingStore.open(context, name)!!
        write(target)
        target.abandon()
        assertEquals(100L, finished.length())
        assertFalse(File(FakeStorage.appFolder(context), taken).exists())
    }

    @Test fun anEmptyFileOfTheNameIsNoRecordingAndIsWrittenOver() {
        // A save killed before its copy in: nothing there to keep.
        val empty = File(FakeStorage.appFolder(context), name).apply { writeBytes(ByteArray(0)) }
        val target = RecordingStore.open(context, name)!!
        assertEquals(name, target.name)
        write(target)
        assertTrue(target.publish())
        assertArrayEquals(bytes, empty.readBytes())
    }
}
