package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent

// A file written as a save writes one, under a name a test
// chose (the emulator check name_taken), and what the place
// called it; into Recordings/JemRec with a folder chosen, if
// asked:
//   --es op store --es name <file name> [--ez recordings true]
internal object StoreOp : DiagOp {
    override suspend fun run(appContext: Context, intent: Intent) {
        val name = intent.getStringExtra("name").orEmpty()
        val target = if (intent.getBooleanExtra("recordings", false)) {
            com.jemcik.jemrec.recordings.MediaStoreRecordings.open(appContext, name)
        } else {
            com.jemcik.jemrec.recordings.RecordingStore.open(appContext, name)
        }
        if (target == null) {
            report(appContext, "store", "$name: nowhere to write it")
        } else {
            java.io.FileOutputStream(target.descriptor.fileDescriptor).run {
                write(ByteArray(64) { 1 })
                flush()
            }
            report(appContext, "store", if (target.publish()) "published as ${target.name}" else "not published")
        }
    }
}
