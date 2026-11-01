package com.jemcik.jemrec.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * What the per-recording marks keep when they fill up: a mark outlives its
 * recording, so messages, directions and messenger callers keep only the
 * 2000 newest by the name's time; keep marks have no cap at all.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class MarkCapsTest {

    private val app = RuntimeEnvironment.getApplication()
    private val start = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse("20261001_000000")!!.time

    /** [count] call times a minute apart, oldest first. */
    private fun minutes(count: Int, from: Int = 0) = (from until from + count).map { start + it * 60_000L }

    private fun dateName(at: Long) = RecordingStore.nameFor(java.util.Date(at), incoming = true)

    /** Honor's format: no direction in it, so its direction is noted. */
    private fun honorName(at: Long) = "477@477_${SimpleDateFormat("yyyyMMddHHmmss", Locale.US).format(at)}.ogg"

    /** A messenger call in the date format: no caller in it, so its caller is noted. */
    private fun viberName(at: Long) = FileNames.buildVoip(FileNames.Format.DATE, at, true, "viber", "Priya Raman")

    @Test fun messagesKeepTheNewest2000() {
        val names = minutes(2001).map(::dateName)
        MessageMarks.mark(app, names)
        assertEquals(names.drop(1).toSet(), MessageMarks.all(app))
        // One older than all of them is dropped as it is marked.
        MessageMarks.mark(app, dateName(start - 60_000))
        assertEquals(names.drop(1).toSet(), MessageMarks.all(app))
        // One newer pushes out the oldest.
        val newest = dateName(start + 5000 * 60_000L)
        MessageMarks.mark(app, newest)
        assertEquals((names.drop(2) + newest).toSet(), MessageMarks.all(app))
    }

    @Test fun aMessageWhoseNameHasNoTimeCountsAsTheOldest() {
        val names = minutes(2000).map(::dateName)
        MessageMarks.mark(app, names + "voice-note.ogg")
        assertEquals(2000, MessageMarks.all(app).size)
        assertFalse("voice-note.ogg" in MessageMarks.all(app))
    }

    @Test fun directionsKeepTheNewest2000() {
        val names = minutes(2001).map(::honorName)
        FileNames.noteDirections(app, names.associateWith { false })
        val noted = FileNames.directions(app)
        assertEquals(names.drop(1).toSet(), noted.keys)
        assertTrue(noted.values.all { !it })
        // One older than all of them is dropped as it is noted.
        FileNames.noteDirection(app, honorName(start - 60_000), incoming = true)
        assertEquals(names.drop(1).toSet(), FileNames.directions(app).keys)
        // One newer pushes out the oldest.
        val newest = honorName(start + 5000 * 60_000L)
        FileNames.noteDirection(app, newest, incoming = true)
        assertEquals((names.drop(2) + newest).toSet(), FileNames.directions(app).keys)
        assertEquals(true, FileNames.directions(app)[newest])
    }

    @Test fun aNameThatSaysItsDirectionIsNeverNoted() {
        FileNames.noteDirection(app, dateName(start), incoming = false)
        assertTrue(FileNames.directions(app).isEmpty())
    }

    @Test fun messengerCallersKeepTheNewest2000() {
        val names = minutes(2001).map(::viberName)
        FileNames.noteCallers(app, names.associateWith { "Priya Raman" })
        assertEquals(names.drop(1).toSet(), FileNames.callers(app).keys)
        // One older than all of them is dropped as it is noted.
        FileNames.noteCaller(app, viberName(start - 60_000), "Sam")
        assertEquals(names.drop(1).toSet(), FileNames.callers(app).keys)
        // One newer pushes out the oldest; a second note replaces the first.
        val newest = viberName(start + 5000 * 60_000L)
        FileNames.noteCaller(app, newest, "Sam")
        FileNames.noteCaller(app, newest, "Sam Lee")
        assertEquals((names.drop(2) + newest).toSet(), FileNames.callers(app).keys)
        assertEquals("Sam Lee", FileNames.callers(app)[newest])
    }

    @Test fun keepMarksHaveNoCap() {
        val names = minutes(2500).map(::dateName)
        KeepMarks.set(app, names, kept = true)
        assertEquals(names.toSet(), KeepMarks.all(app))
        KeepMarks.set(app, listOf(names.first()), kept = false)
        assertEquals(names.drop(1).toSet(), KeepMarks.all(app))
    }
}
