package com.jemcik.jemrec.recordings

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.audio.OggPages
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * Recordings saved before 1.2 given the page positions Ogg Opus means and
 * their last page marked the stream's end, where their pages lie
 * (OggPages.mend): saved with the muxer's, libopusfile would refuse to open
 * them (read in its source, not run)
 * and Android's own decoder cut 6.5 ms out 13.5 ms in (OggPages). Each page
 * keeps its length, so a recording changes in its page headers alone, and one
 * stopped part way - the app killed - plays on, its positions going on.
 *
 * Run from each keep-alive run (RecorderKeepAlive), every quarter hour or
 * so, a minute at a time, until it has been through every recording once -
 * once for each folder chosen in turn, and only with the folder there to be
 * gone through: marked done after one pass over whatever was listed, a
 * folder out of reach then, or chosen after, was never mended (review,
 * 2026-11-18) - then never again,
 * as everything saved since is written so (OggPages.join). A recording
 * already marked is passed over after a look at its last page. Only
 * JemRec's own - a name it gives (FileNames.parse), an .ogg - and only one
 * this install may write: after a reinstall Android counts the old
 * install's files as nobody's, and those play as they did. Recently deleted
 * ones too, which may come back.
 */
internal object OldRecordingPages {

    private const val TAG = "JemRec"
    /** The places gone through: a chosen folder's tree, or [STANDARD]. */
    private const val KEY_DONE = "old_pages_done_in"
    private const val STANDARD = "standard"

    /** How long one turn may take before the rest wait for the next. */
    private const val TURN_MS = 60_000L

    fun runIfDue(context: Context) {
        val place = RecordingStore.treeUri(context)?.toString() ?: STANDARD
        val done = Prefs.of(context).getStringSet(KEY_DONE, emptySet()).orEmpty()
        if (place in done) return
        // A folder out of reach lists nothing, and is gone through once it
        // is back: until then each run - every quarter hour - opened every
        // other recording and read its end again for nothing, for as long as
        // the folder stayed lost (review, 2026-11-18).
        if (place != STANDARD && RecordingStore.folderReachable(context) != true) return
        val began = SystemClock.elapsedRealtime()
        val scan = RecordingLibrary.scan(context)
        val all = RecordingLibrary.list(context, scan, withCallers = false) + RecordingLibrary.trashed(context, scan)
        var mended = 0
        var notOurs = 0
        for (recording in all) {
            if (SystemClock.elapsedRealtime() - began > TURN_MS) {
                Log.i(TAG, "pages: $mended recording(s) given Ogg Opus positions; the rest next time")
                return
            }
            if (!recording.name.endsWith(".${RecordingStore.EXTENSION}") || FileNames.parse(recording.name) == null) continue
            if (Waveforms.isBeingWritten(recording.name)) continue
            val pfd = runCatching { recording.location.change(context) }.getOrNull()
            if (pfd == null) {
                notOurs++
                continue
            }
            val changed = runCatching { pfd.use(::mend) }
                .onFailure { Log.w(TAG, "pages: ${FileNames.forLog(recording.name)} could not be mended: ${it.javaClass.simpleName}") }
                .getOrDefault(false)
            if (changed) mended++
        }
        // Lost while this pass ran, or its listing failed: not gone through,
        // so it is again (review, 2026-11-18).
        if (place != STANDARD && (!scan.folderListed || RecordingStore.folderReachable(context) != true)) {
            Log.i(TAG, "pages: $mended given Ogg Opus positions; the folder chosen out of reach, gone through again later")
            return
        }
        // And MediaStore not answering - busy, restarting after a module
        // update: Recordings/JemRec read as empty was taken for gone
        // through, its old recordings never mended (review, 2026-11-18).
        if (!scan.standardListed) {
            Log.i(TAG, "pages: $mended given Ogg Opus positions; MediaStore did not answer, gone through again later")
            return
        }
        Prefs.of(context).edit { putStringSet(KEY_DONE, done + place) }
        Log.i(TAG, "pages: every recording through - $mended given Ogg Opus positions, $notOurs not this install's to write")
    }

    /** Whether the recordings where JemRec saves now have been gone
     *  through (the debug build's oldpages op). */
    internal fun goneThrough(context: Context): Boolean {
        val place = RecordingStore.treeUri(context)?.toString() ?: STANDARD
        return place in Prefs.of(context).getStringSet(KEY_DONE, emptySet()).orEmpty()
    }

    /** Whether [pfd]'s file was mended now: false if it needed nothing. */
    private fun mend(pfd: ParcelFileDescriptor): Boolean {
        // Positional reads and writes on the one descriptor: neither moves it.
        val reading = FileInputStream(pfd.fileDescriptor).channel
        val writing = FileOutputStream(pfd.fileDescriptor).channel
        val read = { offset: Long, into: ByteArray, at: Int, length: Int -> reading.read(ByteBuffer.wrap(into, at, length), offset) }
        if (OggPages.endsTheStream(read, pfd.statSize)) return false
        if (!OggPages.mend(read) { offset, page -> writing.write(ByteBuffer.wrap(page), offset) }) return false
        pfd.fileDescriptor.sync()
        return true
    }
}
