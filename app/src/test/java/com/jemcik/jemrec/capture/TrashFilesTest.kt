package com.jemcik.jemrec.capture

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/** Recently deleted's two buttons, on recordings trashed as files - app
 *  storage's way, a rename: Restore all puts every one back under its own
 *  name; Delete all for good leaves none. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class TrashFilesTest {

    private val app = RuntimeEnvironment.getApplication()
    private val folder = File(app.filesDir, "recordings").apply { mkdirs() }

    private fun trashed(name: String): Recording {
        val file = File(folder, Trash.nameFor(name, expiresAtMillis = System.currentTimeMillis() + 86_400_000L))
        file.writeText("not audio")
        return Recording(
            name = name, uri = Uri.fromFile(file), whenLabel = "", incoming = true,
            durationLabel = "0:10", sizeLabel = "1 KB", filePath = file.absolutePath,
        )
    }

    @Test fun restoreAllPutsEveryOneBackUnderItsOwnName() {
        val two = listOf(trashed("20261020_101010_in.ogg"), trashed("20261021_111111_out.ogg"))
        val result = Trash.restoreAll(app, two)
        assertEquals(2, result.deleted)
        assertEquals(0, result.failed)
        assertNull(result.needsConsent)
        assertTrue(File(folder, "20261020_101010_in.ogg").exists())
        assertTrue(File(folder, "20261021_111111_out.ogg").exists())
        assertFalse(folder.listFiles()!!.any { Trash.isTrashedName(it.name) })
    }

    @Test fun deleteAllForGoodLeavesNone() {
        val two = listOf(trashed("20261020_101010_in.ogg"), trashed("20261021_111111_out.ogg"))
        val result = Trash.deleteForGood(app, two)
        assertEquals(2, result.deleted)
        assertEquals(0, result.failed)
        assertTrue(folder.listFiles()!!.isEmpty())
    }

    @Test fun oneAlreadyGoneCountsAsDeleted() {
        val gone = trashed("20261020_101010_in.ogg").also { File(it.filePath!!).delete() }
        assertEquals(1, Trash.deleteForGood(app, listOf(gone)).deleted)
    }
}
