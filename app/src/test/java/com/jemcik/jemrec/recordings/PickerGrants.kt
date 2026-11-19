package com.jemcik.jemrec.recordings

import android.content.ContentResolver
import android.net.Uri
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.RuntimeEnvironment
import org.robolectric.shadows.ShadowContentResolver
import java.util.concurrent.ConcurrentHashMap

/**
 * Android's rule for a folder's grant, which Robolectric's resolver does not
 * keep: one can be taken only where the folder picker offered it, and Android
 * throws for any other - "No persistable permission grants found"
 * (UriGrantsManagerService.takePersistableUriPermission). Robolectric takes
 * any - 4.17, and 4.18 still (its ShadowContentResolver, read 2026-11-19) -
 * so the refusal and what JemRec does with it went untested, and tests that
 * set a folder no picker gave passed by that leniency (review, 2026-11-18). Every test has it (robolectric.properties); FakeFolder offers
 * its tree, as the picker would, and a test that means a folder chosen
 * offers it first ([offer]).
 */
@Implements(ContentResolver::class)
class PickerGrants : ShadowContentResolver() {

    @Implementation
    override fun takePersistableUriPermission(uri: Uri, modeFlags: Int) {
        if (uri !in offers()) throw SecurityException("No persistable permission grants found for UID 10000 and Uri $uri [user 0]")
        super.takePersistableUriPermission(uri, modeFlags)
    }

    companion object {
        /** By the test's application, which Robolectric makes anew for each
         *  test: an offer never outlives its test. */
        private val offered = java.util.Collections.synchronizedMap(java.util.WeakHashMap<android.app.Application, MutableSet<Uri>>())

        private fun offers(): MutableSet<Uri> =
            offered.getOrPut(RuntimeEnvironment.getApplication()) { ConcurrentHashMap.newKeySet() }

        /** The picker gave this folder. */
        fun offer(uri: Uri) {
            offers() += uri
        }
    }
}
