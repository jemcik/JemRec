package com.jemcik.jemrec.diag.ops

import android.content.Context
import android.content.Intent

// A file written as a save writes one, under a name a test
// chose (the emulator check name_taken), and what the place
// called it; into Recordings/JemRec with a folder chosen, if
// asked; [bytes], base64, what it holds - a recording paged as
// one saved before 1.2 (opus_valid) - else 64 bytes of nothing:
//   --es op store --es name <file name> [--ez recordings true] [--es bytes <base64>]
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
            val bytes = intent.getStringExtra("bytes")?.let { android.util.Base64.decode(it, android.util.Base64.DEFAULT) }
            java.io.FileOutputStream(target.descriptor.fileDescriptor).run {
                write(bytes ?: ByteArray(64) { 1 })
                flush()
            }
            report(appContext, "store", if (target.publish()) "published as ${target.name}" else "not published")
        }
    }
}
