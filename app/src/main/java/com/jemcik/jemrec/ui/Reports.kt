package com.jemcik.jemrec.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast

/**
 * A report onto the clipboard, for every Copy a report. From Android 13 the
 * system confirms a copy itself, showing what was copied; before it nothing
 * does, and Android's guidance is a toast of the app's own there, and only
 * there, so the two never show at once ("Copy and paste", developer.android
 * .com, read 2026-10-27). JemRec runs from Android 12: the answering
 * machine's report was copied without a word on 12 and 12L until this.
 */
internal fun reportToClipboard(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
        Toast.makeText(context, "Report copied", Toast.LENGTH_SHORT).show()
    }
}

/** This app's version, as a report gives it; "?" if Android will not say. */
internal fun appVersion(context: Context): String =
    runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?"
