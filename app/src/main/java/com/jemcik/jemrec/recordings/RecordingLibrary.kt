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
        Recordings.override?.let { return Library(it(context), emptyList()) }
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

    /** Everything in the trash, soonest to go first, and last what does not
     *  say when it goes (TrashNames.inFolder). Each has [Recording.name] as it was
     *  before, and [Recording.expiresAtMillis] set where it is known. */
    fun trashed(context: Context, scan: Scan = scan(context)): List<Recording> {
        val found = (
            MediaStoreRecordings.trashed(context) +
                FolderRecordings.trashed(context, scan.documents) +
                AppRecordings.trashed(context, scan.files)
            )
            .distinctBy { it.name }
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
