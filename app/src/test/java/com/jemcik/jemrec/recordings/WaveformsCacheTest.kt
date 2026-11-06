package com.jemcik.jemrec.recordings

import android.net.Uri
import org.junit.After
import org.junit.Assert.assertEquals
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

    private fun levelsIn(name: String): List<Float> = List(Waveforms.LEVELS) { if (it == 0) 1f else 0f }.also {
        keep(name)
        Waveforms.cacheFile(app, name).writeBytes(Waveforms.toBytes(it))
    }

    @Test fun whatIsReadIsRememberedAndAskedOfMemoryFirst() {
        val levels = levelsIn("mem.ogg")
        assertNull(Waveforms.peek("mem.ogg"))
        assertEquals(levels, Waveforms.levels(app, "mem.ogg", Uri.EMPTY))
        assertEquals(levels, Waveforms.peek("mem.ogg"))
        // The file gone, memory still has it: the row scrolled back into view.
        Waveforms.cacheFile(app, "mem.ogg").delete()
        assertEquals(levels, Waveforms.cached(app, "mem.ogg"))
        assertEquals(levels, Waveforms.levels(app, "mem.ogg", Uri.EMPTY))
        Waveforms.prune(app, emptyList())
    }

    @Test fun readingOneWithNoLevelsLeavesNothing() {
        assertNull(Waveforms.cached(app, "none.ogg"))
        assertNull(Waveforms.peek("none.ogg"))
        keep("short.ogg", bytes = 10)
        assertNull(Waveforms.cached(app, "short.ogg"))
    }

    @Test fun aHundredAreRememberedTheOldestReadGoingFirst() {
        (0..100).forEach { i ->
            levelsIn("r$i.ogg")
            Waveforms.cached(app, "r$i.ogg")
        }
        assertNull(Waveforms.peek("r0.ogg"))
        assertTrue(Waveforms.peek("r1.ogg") != null)
        assertTrue(Waveforms.peek("r100.ogg") != null)
        Waveforms.prune(app, emptyList())
    }

    @Test fun pruningForgetsWhatMemoryHadOfTheOthers() {
        levelsIn("gone.ogg")
        levelsIn("stays.ogg")
        Waveforms.cached(app, "gone.ogg")
        Waveforms.cached(app, "stays.ogg")
        Waveforms.prune(app, listOf("stays.ogg"))
        assertNull(Waveforms.peek("gone.ogg"))
        assertTrue(Waveforms.peek("stays.ogg") != null)
        Waveforms.prune(app, emptyList())
    }

    @Test fun beginningToWriteOneForgetsNothingUntilItEnds() {
        val levels = levelsIn("w.ogg")
        Waveforms.cached(app, "w.ogg")
        Waveforms.writingStarted("w.ogg")
        try {
            assertEquals(levels, Waveforms.peek("w.ogg"))
        } finally {
            Waveforms.writingEnded(app, "w.ogg")
        }
        assertNull(Waveforms.peek("w.ogg"))
    }

    @Test fun theStandInAnswersEverything() {
        val invented = Waveforms.invented(7)
        Waveforms.override = { invented }
        try {
            assertEquals(invented, Waveforms.levels(app, "any.ogg", Uri.EMPTY))
            assertEquals(invented, Waveforms.cached(app, "any.ogg"))
            assertEquals(invented, Waveforms.peek("any.ogg"))
            assertTrue(Waveforms.has(app, "any.ogg"))
            // Even for one being written.
            Waveforms.writingStarted("any.ogg")
            assertEquals(invented, Waveforms.levels(app, "any.ogg", Uri.EMPTY))
        } finally {
            Waveforms.override = null
            Waveforms.writingEnded(app, "any.ogg")
        }
    }

    @Test fun theFolderOfTheWayBeforeIsCleared() {
        val old = File(app.cacheDir, "waveforms-v1").apply { mkdirs(); resolve("x.lvl").writeText("x") }
        Waveforms.cacheFile(app, "a.ogg")
        assertFalse(old.exists())
    }
}
