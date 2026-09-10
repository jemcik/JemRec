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
     * Empty below Android 13, where the equivalent is READ_EXTERNAL_STORAGE:
     * blanket access to every file on the phone, which is not a trade this app
     * makes. There the list simply keeps its pre-reinstall behaviour.
     */
    val PERMISSIONS: Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            emptyArray()
        }

    /** True when there is nothing left to ask for - including having nothing to ask. */
    fun granted(context: Context): Boolean =
        PERMISSIONS.all {
            context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
        }
}
