package com.jemcik.jemrec.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * Permission to see recordings this app made and no longer owns.
 *
 * MediaStore gives an app its OWN media with no permission at all, which is
 * why listing needed nothing for as long as one install did the recording and
 * the reading. Ownership does not outlive the install, though: uninstalling
 * leaves every file with owner_package_name = NULL, and the next install is
 * told its own folder is empty while the recordings sit there untouched.
 *
 * Asked for only where that loss is visible - the empty list - and refusing it
 * costs nothing but the old recordings.
 */
object AudioAccess {

    /**
     * Below Android 13 the one permission that shows them is
     * READ_EXTERNAL_STORAGE, which reads every photo, video and audio file,
     * not audio alone. It was not asked for there, and Android 12 then listed
     * no recording from before a reinstall at all; the user chose to ask for
     * it, the same way and for the same reason (2026-10-24). The manifest
     * caps it at 12.
     */
    val PERMISSIONS: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    /**
     * What the card asks to be allowed, in the words of Android's own
     * question after it: "music and audio" from 13, "photos and media" on 12
     * (both read off the emulators' dialogs, 2026-10-24) - with why it is
     * more than audio there.
     */
    val askFor: String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            "Allow access to audio files to show them."
        } else {
            "Allow access to photos and media to show them: Android 12 has no choice for audio alone."
        }

    /** True when there is nothing left to ask for - including having nothing to ask. */
    fun granted(context: Context): Boolean =
        PERMISSIONS.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
}
