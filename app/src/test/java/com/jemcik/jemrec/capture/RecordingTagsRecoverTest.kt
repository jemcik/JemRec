package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.OggSamples.recording
import com.jemcik.jemrec.capture.OggSamples.splice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
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

    @Before fun forgetTheLastTestsFolders() {
        FakeStorage.forgetFileProviderFolders()
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

    @Test fun aKeepMarkInTheFileOnlyEverAdds() {
        // Kept in the app before its file was first read; the file says not.
        KeepMarks.set(app, listOf(message), kept = true)
        save(message, RecordingTags.Facts(message = true, incoming = true))
        assertTrue(listed().getValue(message).kept)
        assertEquals(setOf(message), KeepMarks.all(app))
    }

    @Test fun aDirectionIsNotedOnlyWhereTheNameDoesNotSayIt() {
        saveAll()
        // The name says in; a file that says otherwise is not believed.
        val contrary = "20261024_101010_in.ogg"
        save(contrary, RecordingTags.Facts(incoming = false))
        val list = listed()
        assertEquals(mapOf(honor to false), FileNames.directions(app))
        assertTrue(list.getValue(contrary).incoming)
    }

    @Test fun aCallerIsNotedOnlyForAMessengerNameWithoutOne() {
        saveAll()
        // A phone call's caller comes from the call log, never from its tags.
        save(untagged, RecordingTags.Facts(incoming = false, caller = "Sam Lee"))
        // A messenger name that carries its caller keeps the name's.
        val named = "20261022_170000_in_telegram_Priya Raman.ogg"
        save(named, RecordingTags.Facts(incoming = true, caller = "Somebody Else"))
        val list = listed()
        assertEquals(mapOf(telegram to "Priya Raman"), FileNames.callers(app))
        assertEquals("Priya Raman", list.getValue(named).caller)
    }

    @Test fun aNameThatIsNotOursIsNeverRead() {
        val foreign = "voice-note.ogg"
        save(foreign, RecordingTags.Facts(message = true, kept = true))
        val row = listed().getValue(foreign)
        assertFalse(row.message || row.kept)
        assertFalse(foreign in RecordingTags.readNames(app))
        assertTrue(MessageMarks.all(app).isEmpty())
    }

    @Test fun aRemovedKeepMarkComesBackWhenItsFileLeavesTheListAndReturns() {
        save(kept, RecordingTags.Facts(incoming = false, kept = true))
        assertTrue(listed().getValue(kept).kept)
        // Un-kept, on a file this app may not write - as after a reinstall,
        // when Android counts the old install's files as nobody's. Root writes
        // a read-only file all the same, so run as root this has nothing to show.
        val file = File(folder, kept).apply { setWritable(false) }
        assumeFalse("the tests run as root", file.canWrite())
        KeepMarks.set(app, listOf(kept), kept = false)
        assertEquals(0, RecordingTags.writeKept(app, listOf(listed().getValue(kept))))
        assertFalse(listed().getValue(kept).kept)
        // To Recently deleted and back.
        val row = listed().getValue(kept)
        Trash.trash(app, row.uri, row.filePath, row.name)
        listed()
        Trash.restore(app, Trash.list(app).single())
        // Pins today's behaviour, which looks like a bug: recover forgets a
        // name once it is not listed, reads the file again when it returns,
        // and the file's old keep mark undoes the user's unkeep
        // (to be fixed in its own commit).
        assertTrue(listed().getValue(kept).kept)
        assertEquals(setOf(kept), KeepMarks.all(app))
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
