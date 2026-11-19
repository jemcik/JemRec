package com.jemcik.jemrec.recordings

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import com.jemcik.jemrec.calls.AfterCall

/**
 * Recordings saved for Honor's Phone app before 1.2 under a name it cannot
 * read, renamed to the name 1.2 gives the same call (FileNames.honorRenamed),
 * so their calls get their play button: a caller with no name was written
 * "<number>@<number>_<time>", which the Phone app reads as a broken pattern
 * (FileNames.build) - on the Honor, 2026-11-17, such a call had no button,
 * and the same recording renamed "<number>_<time>" got one at once.
 *
 * Only in Sounds/CallRecord, the one folder the Phone app looks in, and only
 * while it is the folder recordings go to: anywhere else a new name would
 * play nowhere new. Done as the folder is read (RecordingLibrary.scan), so a
 * recording restored from Recently deleted under its old name is put right
 * at the next read as well; a name that is right is left alone, so this is
 * a look at each name and nothing more once they are. A name already taken
 * is left as it is, rather than given one the provider makes up; a file
 * still being written is not touched. What the app remembers of each one by
 * name goes with it (RecordingMarks.renamed); what the file says of itself,
 * in its tags, is in the file; an after-call notification of the old name
 * goes, as for any recording that leaves the list (AfterCall.gone).
 */
internal object HonorRenames {

    private const val TAG = "JemRec"

    /** [documents], the folder's files as just listed - listed again if any
     *  of them was renamed, and null if that listing failed: read as empty,
     *  the folder's old recordings were taken for gone through
     *  (OldRecordingPages; review, 2026-11-18). */
    @Synchronized
    fun fix(context: Context, documents: List<FolderRecordings.Document>): List<FolderRecordings.Document>? {
        if (!FileNames.inHonorFolder(context)) return documents
        val names = documents.map { it.name }.toMutableSet()
        var renamed = 0
        var left = 0
        for (doc in documents) {
            if (doc.trashed != null || Waveforms.isBeingWritten(doc.name)) continue
            val to = FileNames.honorRenamed(doc.name) ?: continue
            if (to in names) {
                left++
                continue
            }
            val now = rename(context, doc.uri, to) ?: continue
            RecordingMarks.renamed(context, doc.name, now)
            // Its after-call notification, if one is up, acts on the old name.
            AfterCall.gone(context, doc.name)
            names -= doc.name
            names += now
            renamed++
        }
        // Said when something was done: a name left as it was stays so, and
        // would be said again at every read.
        if (renamed == 0) return documents
        Log.i(TAG, "honor names: renamed $renamed recording(s) for Honor's Phone app, $left left as they were: the name was taken")
        return FolderRecordings.listing(context)
    }

    /** [uri] renamed [to]: the name it has now - a provider may make its own
     *  of a name taken meanwhile - or null if it was not renamed. Android
     *  answers with the document's uri after the rename - the old one where
     *  the provider gave no new one - and with null for a rename that failed
     *  without throwing (DocumentsContract.renameDocument: AOSP returns null
     *  from its catch, rethrowing only runtime errors for apps targeting 8 and
     *  later); a null taken for done would move the marks to a name no file
     *  has. */
    private fun rename(context: Context, uri: Uri, to: String): String? = runCatching {
        val moved = DocumentsContract.renameDocument(context.contentResolver, uri, to)
            ?: error("the provider did not rename it")
        context.contentResolver.query(moved, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: to
    }.onFailure { Log.w(TAG, "honor names: a recording could not be renamed", it) }.getOrNull()
}
