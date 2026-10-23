package com.jemcik.jemrec.capture

import android.net.Uri
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.io.File

/** Where the waveforms are kept: what the list's fill-in asks, what waits
 *  for a recording to be finished, and what goes with a deleted one. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class WaveformsCacheTest {

    private val app = RuntimeEnvironment.getApplication()

    private fun keep(name: String, bytes: Int = Waveforms.LEVELS): File =
        Waveforms.cacheFile(app, name).apply {
            parentFile?.mkdirs()
            writeBytes(ByteArray(bytes))
        }

    @Before fun noStandIn() {
        Waveforms.override = null
    }

    @After fun clean() {
        File(app.cacheDir, "waveforms-v2").deleteRecursively()
    }

    @Test fun whetherARecordingHasItsLevelsIsTheFilesSize() {
        assertFalse(Waveforms.has(app, "a.ogg"))
        keep("a.ogg")
        assertTrue(Waveforms.has(app, "a.ogg"))
        keep("b.ogg", bytes = 10)
        assertFalse(Waveforms.has(app, "b.ogg"))
    }

    @Test fun aRecordingBeingWrittenGetsNoLevelsAndFinishingItForgetsAnyItHad() {
        keep("c.ogg")
        Waveforms.writingStarted("c.ogg")
        try {
            // Not read, not worked out, not kept: the file is not finished.
            assertNull(Waveforms.levels(app, "c.ogg", Uri.EMPTY))
            assertTrue(Waveforms.isBeingWritten("c.ogg"))
        } finally {
            Waveforms.writingEnded(app, "c.ogg")
        }
        assertFalse(Waveforms.isBeingWritten("c.ogg"))
        // What was kept under the name was another file's.
        assertFalse(Waveforms.has(app, "c.ogg"))
    }

    @Test fun theLevelsOfRecordingsNoLongerKeptGo() {
        val hourAgo = System.currentTimeMillis() - 3_600_000
        keep("kept.ogg").setLastModified(hourAgo)
        keep("deleted.ogg").setLastModified(hourAgo)
        keep("just-saved.ogg")
        Waveforms.prune(app, listOf("kept.ogg"))
        assertTrue(Waveforms.has(app, "kept.ogg"))
        assertFalse(Waveforms.has(app, "deleted.ogg"))
        // Kept a moment ago: a call saved since the list was read.
        assertTrue(Waveforms.has(app, "just-saved.ogg"))
    }
}
