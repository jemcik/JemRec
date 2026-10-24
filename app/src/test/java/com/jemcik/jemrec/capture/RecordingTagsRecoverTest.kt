package com.jemcik.jemrec.capture

import androidx.core.content.FileProvider
import com.jemcik.jemrec.capture.OggSamples.recording
import com.jemcik.jemrec.capture.OggSamples.splice
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

/**
 * A reinstall: the recordings are there, the app's preferences are not. The
 * list must show each as it was - a message a message, a messenger call its
 * caller, a kept one kept, an Honor-named one its direction - from what each
 * file says of itself. (The Honor, 2026-10-24: without this every message
 * was an ordinary call, every messenger call "Telegram call".)
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class RecordingTagsRecoverTest {

    private val app = RuntimeEnvironment.getApplication()
    private val folder = File(app.getExternalFilesDir(null), "recordings").apply { mkdirs() }

    /**
     * FileProvider keeps each authority's folders in a static cache, and every
     * test here has folders of its own: from the second test on, it refused
     * the files and the list dropped them. A phone has one set of folders.
     */
    @Before fun forgetTheLastTestsFolders() {
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }
            .let { (it.get(null) as HashMap<*, *>).clear() }
    }

    private val message = "20261022_152242_in.ogg"
    private val telegram = "20261022_163752_in_telegram.ogg"
    private val kept = "20261023_093709_out.ogg"
    private val honor = "Priya Shah@+447700900123_20261020223141.ogg"
    private val untagged = "20261021_101010_out.ogg"

    private fun save(name: String, facts: RecordingTags.Facts?) {
        File(folder, name).writeBytes(if (facts == null) recording() else splice(recording(), facts))
    }

    private fun saveAll() {
        save(message, RecordingTags.Facts(message = true, incoming = true))
        save(telegram, RecordingTags.Facts(incoming = true, caller = "Priya Raman"))
        save(kept, RecordingTags.Facts(incoming = false, kept = true))
        save(honor, RecordingTags.Facts(incoming = false))
        save(untagged, null)
    }

    private fun listed() = Recordings.list(app).associateBy { it.name }

    @Test fun afterAReinstallEachRecordingIsShownAsItWas() {
        saveAll()
        val list = listed()
        assertTrue(list.getValue(message).message)
        assertEquals("Priya Raman", list.getValue(telegram).caller)
        assertTrue(list.getValue(kept).kept)
        assertFalse(list.getValue(honor).incoming)
        // One with no tags of ours is listed as it always was.
        val plain = list.getValue(untagged)
        assertFalse(plain.message || plain.kept)
        assertEquals(setOf(message), MessageMarks.all(app))
        assertEquals(setOf(kept), KeepMarks.all(app))
    }

    @Test fun eachFileIsReadOnceAndThePreferencesSayAfterThat() {
        saveAll()
        listed()
        // Un-kept since: the list says so, not the file it read before.
        KeepMarks.set(app, listOf(kept), kept = false)
        assertFalse(listed().getValue(kept).kept)
    }

    @Test fun aRecordingNoLongerListedIsForgotten() {
        saveAll()
        listed()
        File(folder, telegram).delete()
        listed()
        assertFalse(telegram in RecordingTags.readNames(app))
        assertTrue(message in RecordingTags.readNames(app))
    }

    @Test fun aFileThatCouldNotBeReadIsTriedAgain() {
        val gone = File(folder, message)
        val recording = Recordings.describe(message, android.net.Uri.fromFile(gone), 1, 1).copy(filePath = gone.absolutePath)
        RecordingTags.recover(app, listOf(recording))
        assertFalse(message in RecordingTags.readNames(app))
        save(message, RecordingTags.Facts(message = true, incoming = true))
        RecordingTags.recover(app, listOf(recording))
        assertTrue(message in RecordingTags.readNames(app))
        assertEquals(setOf(message), MessageMarks.all(app))
    }

    @Test fun aRowsTimeIsTheCallsNotTheFilesDate() {
        save(message, RecordingTags.Facts(message = true, incoming = true))
        // Written to just now - as marking it kept writes it.
        File(folder, message).setLastModified(System.currentTimeMillis())
        assertEquals(
            Recordings.whenLabel(FileNames.parse(message)!!.startedAt),
            listed().getValue(message).whenLabel,
        )
    }

    @Test fun theKeepMarkIsWrittenIntoTheFile() {
        save(message, RecordingTags.Facts(message = true, incoming = true))
        val recording = listed().getValue(message)
        KeepMarks.set(app, listOf(message), kept = true)
        assertEquals(1, RecordingTags.writeKept(app, listOf(recording)))
        assertEquals(
            RecordingTags.Facts(message = true, incoming = true, kept = true),
            RecordingTags.read(File(folder, message).inputStream()),
        )
        // A file with no room - from before - keeps its mark in the app only.
        save(untagged, null)
        KeepMarks.set(app, listOf(untagged), kept = true)
        assertEquals(0, RecordingTags.writeKept(app, listOf(listed().getValue(untagged))))
    }

    @Test fun theFileEndsAsThePreferencesWhateverOrderTheWritesRunIn() {
        save(message, RecordingTags.Facts(message = true, incoming = true))
        val recording = listed().getValue(message)
        // Kept, then not: the second tap's write runs first, the first's last.
        KeepMarks.set(app, listOf(message), kept = true)
        KeepMarks.set(app, listOf(message), kept = false)
        RecordingTags.writeKept(app, listOf(recording))
        RecordingTags.writeKept(app, listOf(recording))
        assertFalse(RecordingTags.read(File(folder, message).inputStream())!!.kept)
    }
}
