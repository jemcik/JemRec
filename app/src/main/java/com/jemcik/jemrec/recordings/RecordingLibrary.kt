package com.jemcik.jemrec.recordings

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.io.File

/**
 * What is on the phone: the recordings and Recently deleted, read together.
 *
 * Everywhere a recording might be, because the default moved: recordings now
 * go to the phone's standard Recordings folder, a user may have chosen
 * somewhere else, and older ones are still in app storage from before.
 * Listing only the current default would make a user's earlier calls silently
 * vanish from the app.
 *
 * A chosen folder and app storage hold the live and the trashed side by side,
 * so one listing of each ([Scan]) answers for both: the screen's refresh and
 * auto-delete's pass each asked a chosen folder twice, and a recording moved
 * between the two questions was in both lists or in neither.
 */
internal object RecordingLibrary {

    /** What the screens show. */
    data class Library(val recordings: List<Recording>, val trashed: List<Recording>)

    /** The two places that are listed whole, trashed or not. */
    class Scan(val documents: List<FolderRecordings.Document>, val files: List<File>)

    fun scan(context: Context) = Scan(FolderRecordings.documents(context), AppRecordings.files(context))

    /** The recordings and Recently deleted, from one [Scan]. Only the debug
     *  build's invented list has no trash. */
    fun read(context: Context): Library {
        // A stand-in with an expiry is in Recently deleted, as a real one is.
        Recordings.override?.let { stand ->
            val all = stand(context)
            return Library(all.filter { it.expiresAtMillis == null }, all.filter { it.expiresAtMillis != null })
        }
        val scan = scan(context)
        return Library(list(context, scan), trashed(context, scan))
    }

    /** Every recording, newest first. */
    fun list(context: Context, scan: Scan = scan(context), withCallers: Boolean = true): List<Recording> {
        val listed = (
            MediaStoreRecordings.list(context) +
                FolderRecordings.list(context, scan.documents) +
                AppRecordings.list(context, scan.files)
            )
            // Not a recording yet. Its file is there under its name as it fills,
            // in a chosen folder or app storage, and that is known only here,
            // in the process writing it (RecordingStore.open).
            .filterNot { Waveforms.isBeingWritten(it.name) }
            .distinctBy { it.name }
            // By when the call was, not by name: a name can start with the
            // caller now (FileNames.Format.HONOR).
            .sortedWith(compareByDescending<Recording> { it.startedAtMillis ?: 0L }.thenByDescending { it.name })
        // What the files say of themselves, for any the preferences have not
        // heard of: every one, after a reinstall (RecordingTags). Before the
        // marks are read - and auto-delete reads them from here.
        RecordingMarks.recover(context, listed)
        val found = RecordingMarks.onto(context, listed)
        return if (withCallers) RecordingMarks.withCallers(context, found) else RecordingMarks.withDirections(context, found)
    }

    /**
     * Everything in the trash, soonest to go first, and last what does not
     * say when it goes (TrashNames.inFolder). Each has [Recording.name] as it
     * was before, and [Recording.expiresAtMillis] set where it is known.
     *
     * Every copy of a name, each its own row, unlike the list: a row of the
     * list stands for a call, but here each is a file with a time left, and
     * one not shown could be neither restored nor deleted, and went unseen
     * when its time was up. Delete all for good left it there. [Recording.uri]
     * tells them apart. But one file is one row: with Recordings/JemRec itself
     * chosen as the folder, a trashed file is both MediaStore's row and the
     * folder's document, by one name and, in the file's name as in
     * DATE_EXPIRES, one expiry (the emulator check name_taken holds the two
     * equal); its second row would fail on the first's restore, and count
     * twice in Delete all for good's question.
     */
    fun trashed(context: Context, scan: Scan = scan(context)): List<Recording> {
        val inMediaStore = MediaStoreRecordings.trashed(context)
        val sameFile = inMediaStore.mapTo(HashSet()) { it.name to it.expiresAtMillis }
        val found = (
            inMediaStore +
                FolderRecordings.trashed(context, scan.documents).filter { (it.name to it.expiresAtMillis) !in sameFile } +
                AppRecordings.trashed(context, scan.files)
            )
            .sortedWith(compareBy(nullsLast()) { it.expiresAtMillis })
        return RecordingMarks.withCallers(context, found)
    }

    private val _changed = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** Something on the disk changed - a call saved, a recording removed:
     *  an open list reads it again. Says which, for a line in the log. */
    val changed: SharedFlow<String> = _changed.asSharedFlow()

    fun announce(name: String) {
        _changed.tryEmit(name)
    }
}
