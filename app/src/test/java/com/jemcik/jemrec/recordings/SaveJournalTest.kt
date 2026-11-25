package com.jemcik.jemrec.recordings

import android.net.Uri
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.calls.CallLedger
import com.jemcik.jemrec.calls.CallLedger.Recorded
import com.jemcik.jemrec.recordings.RecordingSaver.Outcome
import com.jemcik.jemrec.recordings.SaverFakes.Companion.content
import com.jemcik.jemrec.recordings.SaverFakes.Companion.stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.shadows.ShadowLog

/**
 * A save cut short - the app's process killed in the middle of it - finished
 * or undone by the next save of the same recorder file (SaveJournal). The
 * kill is the emulator check's own hold point (SaveHold, killed_mid_save),
 * where the hold throws instead of sleeping: nothing after it runs, as after
 * a SIGKILL, and nothing the saver cleans up on a failure runs either (a
 * cancellation, which it never catches). Measured first on the Android 17
 * emulator (2026-11-18): killed just after publishing, the call was saved
 * twice, the second as "(1)".
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class SaveJournalTest {

    private val context = RuntimeEnvironment.getApplication()
    private val fakes = SaverFakes(context)

    /** An hour ago, on the second: inside the ledger's week by the machine's
     *  clock, as RecordingSaverTest's. */
    private val t = (System.currentTimeMillis() - 60 * 60_000L) / 1000 * 1000
    private val now = t + 10 * 60_000L
    private val file = "jemrec_rec_${t}_in.dat"
    private val name = FileNames.build(FileNames.Format.DATE, t, true, null)

    private class Killed : CancellationException("the app's process killed")

    private val holding = SaveHold.hold

    @Before fun setUp() {
        ShadowLog.clear()
        fakes.install(now)
    }

    @After fun tearDown() {
        SaveHold.at = null
        SaveHold.hold = holding
        fakes.restore()
    }

    private fun save(): Outcome = runBlocking { RecordingSaver.save(context, file, incoming = true, justEnded = false) }

    /** A save of [file] killed at [at]: what it leaves, as a kill leaves it. */
    private fun killedAt(at: SaveHold.At) {
        SaveHold.at = at
        SaveHold.hold = { throw Killed() }
        try {
            save()
            fail("not killed at $at")
        } catch (_: Killed) {
        }
        SaveHold.hold = holding
    }

    private fun ledger() = CallLedger.read(context).recordings
    private fun logged(line: String) = line in ShadowLog.getLogsForTag("JemRec").map { it.msg }

    @Test fun killedAfterPublishingTheNextSaveFinishesItAndSavesNothingTwice() {
        fakes.files[file] = stream(50)
        killedAt(SaveHold.At.PUBLISHED)
        // As the kill left it: published, but neither in the ledger nor
        // deleted from the recorder - which made the next save a "(1)".
        assertEquals(listOf(name), fakes.published)
        assertTrue(ledger().isEmpty())
        assertTrue(fakes.deleted.isEmpty())

        assertEquals(name, (save() as Outcome.Saved).name)

        assertEquals("nothing opened again", listOf(name), fakes.opened)
        assertEquals(listOf(name), fakes.published)
        assertEquals(listOf(Recorded(t, true, 1)), ledger())
        assertEquals(listOf(file), fakes.deleted)
        assertNull(SaveJournal.of(context, file))
        assertTrue(logged("save: $file was cut short while publishing; 1 file(s) finished"))
    }

    @Test fun killedWhileWritingTheNextSaveDeletesWhatItLeftAndSavesItWhole() {
        fakes.files[file] = stream(100)
        killedAt(SaveHold.At.TRANSFER)
        assertTrue("its place made", fakes.file(name).exists())
        assertTrue(fakes.published.isEmpty())
        var leftAtRefetch: Boolean? = null
        fakes.onFetch = { leftAtRefetch = fakes.file(name).exists() }

        val saved = save() as Outcome.Saved

        // Deleted before the save went again: a place a provider still held
        // gave the retry another name ("(1)").
        assertEquals(false, leftAtRefetch)
        assertEquals(name, saved.name)
        assertEquals(listOf(name), fakes.published)
        assertArrayEquals(content(0 until 100), fakes.file(name).readBytes())
        assertEquals(listOf(Recorded(t, true, 2)), ledger())
        assertNull(SaveJournal.of(context, file))
        assertTrue(logged("save: $file was cut short while writing; 1 unfinished file(s), 1 deleted"))
    }

    @Test fun killedBeforePublishingTheNextSaveDeletesTheUnpublishedFileAndSavesIt() {
        fakes.files[file] = stream(50)
        killedAt(SaveHold.At.PUBLISH)
        assertTrue(fakes.published.isEmpty())
        assertTrue(fakes.deleted.isEmpty())
        var leftAtRefetch: Boolean? = null
        fakes.onFetch = { leftAtRefetch = fakes.file(name).exists() }

        assertEquals(name, (save() as Outcome.Saved).name)

        assertEquals(false, leftAtRefetch)
        assertEquals(listOf(name), fakes.published)
        assertEquals(listOf(Recorded(t, true, 1)), ledger())
        assertEquals(listOf(file), fakes.deleted)
    }

    @Test fun whatPublishingWillDoIsWrittenDownBeforeItsFirstPublish() {
        fakes.files[file] = stream(50)
        var atHold: SaveJournal.Entry? = null
        SaveHold.at = SaveHold.At.PUBLISHED
        SaveHold.hold = { atHold = SaveJournal.of(context, file) }
        save() as Outcome.Saved
        val written = atHold as SaveJournal.Publishing
        val part = written.parts.single()
        assertEquals(name, part.name)
        assertEquals(t, part.startMillis)
        assertTrue(part.incoming)
        assertEquals(1, part.seconds)
        assertTrue(part.phoneCall)
        assertFalse(part.message)
        assertEquals(fakes.file(name).path, part.place.filePath)
        assertNull("cleared once the save ended", SaveJournal.of(context, file))
    }

    @Test fun aSaveThatFailsClearsItsEntry() {
        fakes.files[file] = stream(50)
        fakes.finishes = false
        assertTrue(save() is Outcome.Failed)
        assertNull(SaveJournal.of(context, file))
    }

    @Test fun aPublishingCutShortWhoseRecorderCopyIsGoneIsFinishedByTheReconcile() {
        val place = fakes.file(name).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        SaveJournal.publishing(
            context, file,
            listOf(SaveJournal.Part(SaveJournal.Place(Uri.fromFile(place), place.path), name, t, true, 3, phoneCall = true, message = false)),
        )
        fakes.listing = emptyList()
        runBlocking { RecordingSaver.reconcile(context) }
        assertEquals(listOf(Recorded(t, true, 3)), ledger())
        assertTrue("left where it is", place.exists())
        assertNull(SaveJournal.of(context, file))
    }

    @Test fun aWritingCutShortWhoseRecorderCopyIsGoneIsDeletedByTheReconcile() {
        val place = fakes.file(name).apply { parentFile?.mkdirs(); writeBytes(ByteArray(0)) }
        SaveJournal.opened(context, file, SaveJournal.Place(Uri.fromFile(place), place.path))
        fakes.listing = emptyList()
        runBlocking { RecordingSaver.reconcile(context) }
        assertFalse(place.exists())
        assertTrue(ledger().isEmpty())
        assertNull(SaveJournal.of(context, file))
    }

    @Test fun anUnansweredRecorderLeavesTheJournalAlone() {
        SaveJournal.opened(context, file, SaveJournal.Place(Uri.parse("content://media/external/audio/media/7"), null))
        fakes.listing = null
        runBlocking { RecordingSaver.reconcile(context) }
        assertTrue(SaveJournal.of(context, file) is SaveJournal.Writing)
    }

    @Test fun entriesReadBackAsWritten() {
        val writing = SaveJournal.Writing(
            file,
            listOf(
                SaveJournal.Place(Uri.parse("content://media/external/audio/media/7"), null),
                SaveJournal.Place(Uri.parse("file:///x/a%7Cb.ogg"), "/x/a|b \"q\".ogg"),
            ),
        )
        assertEquals(writing, SaveJournal.decode(SaveJournal.encode(writing)))
        val publishing = SaveJournal.Publishing(
            file,
            listOf(
                SaveJournal.Part(
                    SaveJournal.Place(Uri.parse("content://com.android.externalstorage.documents/tree/x/document/y"), null),
                    "Zoë|\"x\".ogg", t, false, 61, phoneCall = false, message = true,
                ),
            ),
        )
        assertEquals(publishing, SaveJournal.decode(SaveJournal.encode(publishing)))
        // With the calls the rules left out (the 1.2 capped review).
        val skipping = publishing.copy(skips = listOf(SaveJournal.Skip(t, true, 30), SaveJournal.Skip(t + 8_000, false, 5, t + 8_000)))
        assertEquals(skipping, SaveJournal.decode(SaveJournal.encode(skipping)))
        assertNull(SaveJournal.decode("not json"))
        assertNull(SaveJournal.decode("""{"recfile":"x","stage":"elsewhere"}"""))
    }

    @Test fun eachFileHasOneEntryAndOthersAreKept() {
        val other = "jemrec_rec_${t + 1}_out.dat"
        val a = SaveJournal.Place(Uri.parse("content://media/external/audio/media/1"), null)
        val b = SaveJournal.Place(Uri.parse("content://media/external/audio/media/2"), null)
        SaveJournal.opened(context, file, a)
        SaveJournal.opened(context, file, b)
        SaveJournal.opened(context, other, a)
        assertEquals(SaveJournal.Writing(file, listOf(a, b)), SaveJournal.of(context, file))
        SaveJournal.done(context, file)
        assertNull(SaveJournal.of(context, file))
        assertEquals(SaveJournal.Writing(other, listOf(a)), SaveJournal.of(context, other))
    }

    // ---- what is left, read before anything is changed (review, 2026-11-18) ----

    private fun publishing(place: SaveJournal.Place, published: Boolean = false) = SaveJournal.publishing(
        context, file,
        listOf(SaveJournal.Part(place, name, t, true, 1, phoneCall = true, message = false, published = published)),
    )

    /** A part lost - never published, its place gone (a pending row expired,
     *  a part abandoned) - is saved again from the recorder's copy, which is
     *  never deleted for it: an unanswered "is it there?" taken for
     *  "published" deleted the only copy. */
    @Test fun aPartLostBeforeItWasPublishedIsSavedAgainFromTheRecorder() {
        fakes.files[file] = stream(50)
        publishing(SaveJournal.Place(Uri.fromFile(fakes.file("lost.ogg")), fakes.file("lost.ogg").path))
        val saved = save() as Outcome.Saved
        assertEquals(name, saved.name)
        assertEquals(listOf(file), fakes.fetched)
        assertEquals(listOf(Recorded(t, true, 1)), ledger())
        assertNull(SaveJournal.of(context, file))
    }

    /** A place whose provider will not say what it holds: nothing touched,
     *  the recorder's copy kept, the journal kept for a later collection. */
    @Test fun aPlaceThatWillNotSayLeavesItAllForLater() {
        fakes.files[file] = stream(50)
        publishing(SaveJournal.Place(Uri.parse("content://no.such.provider/document/1"), null))
        assertEquals(Outcome.Busy, save())
        assertTrue(fakes.deleted.isEmpty())
        assertTrue(fakes.fetched.isEmpty())
        assertTrue(SaveJournal.of(context, file) is SaveJournal.Publishing)
        val said = "save: $file was cut short while publishing, and where it was written would not say; left for later"
        assertTrue(ShadowLog.getLogsForTag("JemRec").map { it.msg }.toString(), logged(said))
    }

    /**
     * A picked folder's provider as Android's own answers (review round 10):
     * a document gone is a null cursor - DocumentsProvider.query swallows
     * FileNotFoundException - however reachable the folder; the folder's
     * own document answers while it is there ([answering]). [sizes]: each
     * document's COLUMN_SIZE, null for "unknown"; [bytes]: what reading it
     * gives.
     */
    class FakeFolder : android.content.ContentProvider() {
        companion object {
            const val AUTHORITY = "com.jemcik.test.folder"
            var answering = true
            val sizes = mutableMapOf<String, Long?>()
            val bytes = mutableMapOf<String, ByteArray>()
            fun doc(id: String): Uri = android.provider.DocumentsContract.buildDocumentUriUsingTree(
                android.provider.DocumentsContract.buildTreeDocumentUri(AUTHORITY, "root"), id,
            )
        }

        override fun onCreate() = true
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, order: String?): android.database.Cursor? {
            if (!answering) return null
            val id = android.provider.DocumentsContract.getDocumentId(uri)
            val cursor = android.database.MatrixCursor(projection ?: arrayOf(android.provider.DocumentsContract.Document.COLUMN_SIZE))
            when {
                id == "root" -> cursor.addRow(arrayOf<Any?>("root"))
                id in sizes -> cursor.addRow(arrayOf<Any?>(sizes[id]))
                else -> return null
            }
            return cursor
        }
        override fun openFile(uri: Uri, mode: String): android.os.ParcelFileDescriptor? {
            val id = android.provider.DocumentsContract.getDocumentId(uri)
            val content = bytes[id] ?: throw java.io.FileNotFoundException(id)
            val f = java.io.File.createTempFile("folder", id).apply { writeBytes(content) }
            return android.os.ParcelFileDescriptor.open(f, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: android.content.ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?): Int = 0
        override fun update(uri: Uri, values: android.content.ContentValues?, selection: String?, args: Array<out String>?): Int = 0
    }

    private fun folder(): Unit {
        org.robolectric.Robolectric.setupContentProvider(FakeFolder::class.java, FakeFolder.AUTHORITY)
        FakeFolder.answering = true
        FakeFolder.sizes.clear()
        FakeFolder.bytes.clear()
    }

    /** In a picked folder, a part trashed or deleted after the save was cut
     *  short, the folder there: the folder answers, so the part is gone,
     *  and saved again from the recorder's copy - not left waiting for good
     *  as a place that would not say. */
    @Test fun aPickedFoldersPartGoneWhileTheFolderAnswersIsSavedAgain() {
        folder()
        fakes.files[file] = stream(50)
        publishing(SaveJournal.Place(FakeFolder.doc("part"), null))
        val saved = save() as Outcome.Saved
        assertEquals(name, saved.name)
        assertEquals(listOf(file), fakes.fetched)
    }

    /** The folder itself not answering - removed, its grant let go - waits;
     *  a week on, the recorder's copy is saved again, nothing deleted. */
    @Test fun aPlaceSilentForAWeekIsGivenUpOnAndSavedAgain() {
        folder()
        FakeFolder.answering = false
        fakes.files[file] = stream(50)
        publishing(SaveJournal.Place(FakeFolder.doc("part"), null))
        assertEquals(Outcome.Busy, save())
        RecordingSaver.clock = { now + 8L * 24 * 60 * 60 * 1000 }
        val saved = save() as Outcome.Saved
        assertEquals(name, saved.name)
        assertNull(SaveJournal.of(context, file))
    }

    /** A message's save cut short and finished by the reconcile - the
     *  recorder's copy gone - is a save like any other: its notice is
     *  posted, as for any message, where until round 10 it went unsaid. */
    @Test fun aMessageFinishedByTheReconcileIsToldAsAnyMessage() {
        val place = fakes.file(name).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        SaveJournal.publishing(
            context, file,
            listOf(SaveJournal.Part(SaveJournal.Place(Uri.fromFile(place), place.path), name, t, true, 9, phoneCall = true, message = true, published = true)),
        )
        fakes.listing = emptyList()
        runBlocking { RecordingSaver.reconcile(context) }
        assertNull(SaveJournal.of(context, file))
        val notes = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).allNotifications
        assertTrue(notes.map { it.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() }.toString(),
            notes.any { it.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.contains("message", ignoreCase = true) == true })
    }

    /** An older recorder's list - no end to it, perhaps cut short unseen -
     *  is no proof a file is gone: the stale pass leaves its entry (review
     *  round 11). */
    @Test fun anOlderRecordersListIsNoProofAFileIsGone() {
        val place = fakes.file(name).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        SaveJournal.publishing(
            context, file,
            listOf(SaveJournal.Part(SaveJournal.Place(Uri.fromFile(place), place.path), name, t, true, 1, phoneCall = true, message = false, published = true)),
        )
        fakes.listing = emptyList()
        fakes.listingWhole = false
        runBlocking { RecordingSaver.reconcile(context) }
        assertTrue("left as it was", SaveJournal.of(context, file) is SaveJournal.Publishing)
        fakes.listingWhole = true
        runBlocking { RecordingSaver.reconcile(context) }
        assertNull(SaveJournal.of(context, file))
    }

    /** A size not given - "null if unknown" - with bytes there: the part is
     *  there, finished, not saved again as lost. */
    @Test fun aSizeNotGivenIsReadNotTakenForNothing() {
        folder()
        FakeFolder.sizes["part"] = null
        FakeFolder.bytes["part"] = byteArrayOf(1, 2, 3)
        fakes.files[file] = stream(50)
        publishing(SaveJournal.Place(FakeFolder.doc("part"), null))
        assertEquals(name, (save() as Outcome.Saved).name)
        assertTrue("not fetched again", fakes.fetched.isEmpty())
        assertEquals(listOf(Recorded(t, true, 1)), ledger())
    }

    /** A part the save published, gone since - the user's delete - is noted
     *  as saved, and not saved again. */
    @Test fun aPartPublishedThenDeletedByTheUserIsNotSavedAgain() {
        fakes.files[file] = stream(50)
        publishing(SaveJournal.Place(Uri.fromFile(fakes.file("deleted.ogg")), fakes.file("deleted.ogg").path), published = true)
        assertEquals("noted as saved, nothing to tell of a file gone (review round 11)", Outcome.Gone, save())
        assertTrue(fakes.fetched.isEmpty())
        assertEquals(listOf(file), fakes.deleted)
        assertEquals(listOf(Recorded(t, true, 1)), ledger())
    }

    /** Killed straight after what publishing will do was written, nothing
     *  published: the next save finishes it, nothing saved twice. */
    @Test fun killedJustAfterThePublishingNoteTheNextSaveFinishesIt() {
        fakes.files[file] = stream(50)
        killedAt(SaveHold.At.PUBLISHING)
        assertTrue(SaveJournal.of(context, file) is SaveJournal.Publishing)
        assertTrue(fakes.published.isEmpty())
        assertEquals(name, (save() as Outcome.Saved).name)
        assertEquals(listOf(name), fakes.opened)
        assertEquals(listOf(Recorded(t, true, 1)), ledger())
        assertEquals(listOf(file), fakes.deleted)
    }

    /** Killed after the recorder's copy went, before the journal was told:
     *  the reconcile's own pick-up notes it and undoes nothing. */
    @Test fun killedBeforeTheJournalWasToldTheReconcileFinishesIt() {
        fakes.files[file] = stream(50)
        killedAt(SaveHold.At.DONE)
        assertEquals(listOf(file), fakes.deleted)
        assertTrue(SaveJournal.of(context, file) is SaveJournal.Publishing)
        fakes.listing = emptyList()
        runBlocking { RecordingSaver.reconcile(context) }
        assertNull(SaveJournal.of(context, file))
        assertTrue("its file left where it is", fakes.file(name).exists())
        assertEquals(listOf(Recorded(t, true, 1)), ledger())
    }

    /** Each part is written down as published as it is. */
    @Test fun eachPartPublishedIsWrittenDown() {
        fakes.files[file] = stream(50)
        killedAt(SaveHold.At.PUBLISHED)
        assertTrue((SaveJournal.of(context, file) as SaveJournal.Publishing).parts.single().published)
    }

    @Test fun anEntryThatCannotBeReadIsDroppedAtTheNextWrite() {
        Prefs.of(context).edit().putStringSet("save_journal", setOf("not json")).commit()
        SaveJournal.opened(context, file, SaveJournal.Place(Uri.parse("content://media/external/audio/media/1"), null))
        assertEquals(setOf(file), SaveJournal.all(context).map { it.recfile }.toSet())
        assertEquals(1, Prefs.of(context).getStringSet("save_journal", emptySet())!!.size)
    }

    /** That it stopped early, decided before the publish and written down
     *  with it, is said by the save that finishes it (review round 9). */
    @Test fun aSaveCutShortThatStoppedEarlyIsSaidByTheOneThatFinishesIt() {
        fakes.files[file] = stream(50)
        val place = fakes.file(name).apply { parentFile?.mkdirs(); writeBytes(byteArrayOf(1)) }
        SaveJournal.publishing(
            context, file,
            listOf(SaveJournal.Part(SaveJournal.Place(Uri.fromFile(place), place.path), name, t, true, 1, phoneCall = true, message = false, published = true)),
            StoppedEarly.Early(t, 12_000L, t + 13_000L),
        )
        assertEquals(name, (save() as Outcome.Saved).name)
        val notes = org.robolectric.Shadows.shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).allNotifications
        assertTrue(notes.any { it.extras.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString() == "Recording stopped early" })
    }

    /** Told, the early stop is taken out of the entry: a pick-up cut short
     *  after the telling - its recorder delete cancelled - does not say it
     *  again (review round 10). */
    @Test fun anEarlyStopToldIsNotToldAgain() {
        SaveJournal.publishing(context, file, emptyList(), StoppedEarly.Early(t, 12_000L, t + 13_000L))
        SaveJournal.told(context, file)
        assertNull((SaveJournal.of(context, file) as SaveJournal.Publishing).early)
    }

    @Test fun anEarlyStopReadsBackAsWritten() {
        val publishing = SaveJournal.Publishing(
            file,
            listOf(SaveJournal.Part(SaveJournal.Place(Uri.parse("content://media/external/audio/media/3"), null), name, t, true, 9, true, false)),
            StoppedEarly.Early(t, 7_000L, t + 16_000L),
        )
        assertEquals(publishing, SaveJournal.decode(SaveJournal.encode(publishing)))
    }
}
