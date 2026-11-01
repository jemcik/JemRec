package com.jemcik.jemrec.capture

import android.content.SharedPreferences
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.capture.FakeStorage.appFile
import com.jemcik.jemrec.capture.FakeStorage.appUri
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
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
import java.util.Date

/**
 * Auto-delete's daily pass end to end, on app storage: what it moves to
 * Recently deleted, what it never touches, and when it runs at all.
 * RetentionTest has the choice itself (select); this is everything around it.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class RetentionPassTest {

    private val app = RuntimeEnvironment.getApplication()
    private val day = 24L * 60 * 60 * 1000
    private val folder get() = FakeStorage.appFolder(app)

    @Before fun setUp() {
        FakeStorage.forgetFileProviderFolders()
    }

    @After fun noDebugList() {
        Recordings.override = null
    }

    /** A recording of a call [daysAgo], lasting [seconds] as the file is read. */
    private fun call(daysAgo: Long, seconds: Long = 60, incoming: Boolean = true): File {
        val name = RecordingStore.nameFor(Date(System.currentTimeMillis() - daysAgo * day), incoming)
        return appFile(app, name).also { FakeStorage.lasts(app, appUri(app, it), seconds * 1000) }
    }

    private fun trashed(): Set<String> =
        folder.listFiles()!!.mapNotNull { Trash.parse(it.name)?.first }.toSet()

    private fun lastRun() = Prefs.of(app).getLong("auto_delete_last_run", -1)

    /** What the list was told while [block] ran. */
    private fun announced(block: () -> Unit): List<String> = runBlocking {
        val heard = mutableListOf<String>()
        val listening = launch(start = CoroutineStart.UNDISPATCHED) { RecordingSaver.saved.collect { heard += it } }
        block()
        repeat(3) { yield() }
        listening.cancel()
        heard
    }

    @Test fun offUntilTheUserTurnsItOn() {
        assertEquals(0, Retention.maxAgeDays(app))
        assertEquals(0, Retention.minSeconds(app))
        assertEquals(listOf(0, 30, 90, 180, 365), Retention.AGE_CHOICES)
        assertEquals(listOf(0, 5, 10, 30), Retention.SHORT_CHOICES)
    }

    @Test fun oldCallsAndShortCallsGoToTheTrashAndNothingElseDoes() {
        val old = call(daysAgo = 40, incoming = true)
        val short = call(daysAgo = 1, seconds = 3, incoming = true)
        val keptOld = call(daysAgo = 41, incoming = false)
        val shortMessage = call(daysAgo = 2, seconds = 3, incoming = true)
        val recent = call(daysAgo = 3)
        // Not one of ours: a voice note in the same folder is short too.
        val foreign = appFile(app, "voice-note.ogg").also { FakeStorage.lasts(app, appUri(app, it), 3_000) }
        KeepMarks.set(app, listOf(keptOld.name), kept = true)
        MessageMarks.mark(app, shortMessage.name)
        Retention.setMaxAgeDays(app, 30)
        Retention.setMinSeconds(app, 10)

        val result = Retention.run(app)

        assertEquals(2, result.deleted)
        assertEquals(0, result.failed)
        assertNull(result.needsConsent)
        // Moved, not deleted: renamed in place, there for 30 days.
        assertEquals(setOf(old.name, short.name), trashed())
        listOf(keptOld, shortMessage, recent, foreign).forEach { assertTrue(it.name, it.exists()) }
        assertEquals(setOf(old.name, short.name), Trash.list(app).map { it.name }.toSet())
    }

    @Test fun theLastRunIsMarkedBeforeAnythingIsDone() {
        val now = System.currentTimeMillis()
        val expired = appFile(app, Trash.nameFor("20261001_101010_in.ogg", now - day))
        val old = call(daysAgo = 40)
        Retention.setMaxAgeDays(app, 30)
        var whenMarked: Pair<Boolean, Boolean>? = null
        // Held here: preferences keep only a weak reference to it.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "auto_delete_last_run" && whenMarked == null) whenMarked = expired.exists() to old.exists()
        }
        Prefs.of(app).registerOnSharedPreferenceChangeListener(listener)
        val before = System.currentTimeMillis()
        Retention.run(app)
        Prefs.of(app).unregisterOnSharedPreferenceChangeListener(listener)

        // A pass that dies half way is not tried again at once, every 15 min.
        assertEquals(true to true, whenMarked)
        assertTrue(lastRun() >= before)
        assertFalse(expired.exists())
        assertFalse(old.exists())
    }

    @Test fun theListIsToldOnlyWhenSomethingMoved() {
        call(daysAgo = 40)
        Retention.setMaxAgeDays(app, 30)
        assertEquals(listOf("retention"), announced { Retention.run(app) })
        // Again: nothing left that is due.
        assertEquals(emptyList<String>(), announced { Retention.run(app) })
    }

    @Test fun withBothRulesOffItStillEmptiesTheTrash() {
        val now = System.currentTimeMillis()
        val expired = appFile(app, Trash.nameFor("20261001_101010_in.ogg", now - day))
        val old = call(daysAgo = 400, seconds = 1)
        val heard = announced {
            val result = Retention.run(app)
            assertEquals(BulkDeleteResult(0, 0, null), result)
        }
        assertFalse(expired.exists())
        assertTrue(old.exists())
        assertTrue(lastRun() > 0)
        assertEquals(emptyList<String>(), heard)
    }

    @Test fun theDebugBuildsInventedListIsNeverTouched() {
        val expired = appFile(app, Trash.nameFor("20261001_101010_in.ogg", System.currentTimeMillis() - day))
        val old = call(daysAgo = 40)
        Retention.setMaxAgeDays(app, 30)
        Recordings.override = { emptyList() }
        assertEquals(BulkDeleteResult(0, 0, null), Retention.run(app))
        // Not even the last run, nor the purge.
        assertEquals(0L, lastRun())
        assertTrue(expired.exists())
        assertTrue(old.exists())
    }

    @Test fun theDailyPassRunsAtMostOnceADay() {
        val old = call(daysAgo = 40)
        Retention.setMaxAgeDays(app, 30)
        // Ran a little under a day ago: not yet.
        Prefs.of(app).edit { putLong("auto_delete_last_run", System.currentTimeMillis() - day + 60_000) }
        Retention.runIfDue(app)
        assertTrue(old.exists())
        // A day and a moment ago: now.
        Prefs.of(app).edit { putLong("auto_delete_last_run", System.currentTimeMillis() - day - 1_000) }
        Retention.runIfDue(app)
        assertFalse(old.exists())
        assertEquals(setOf(old.name), trashed())
        // And having run, not again the same day.
        val later = call(daysAgo = 50)
        Retention.runIfDue(app)
        assertTrue(later.exists())
    }

    private var calls = 0

    /** A call just saved to app storage, [seconds] long as the saver counted
     *  it; each a minute before the last, so no two share a name. */
    private fun saved(seconds: Double, message: Boolean = false): RecordingSaver.Outcome.Saved {
        val name = RecordingStore.nameFor(Date(System.currentTimeMillis() - ++calls * 60_000L), incoming = true)
        val file = appFile(app, name)
        return RecordingSaver.Outcome.Saved(
            name = file.name, seconds = seconds, uri = appUri(app, file), filePath = file.absolutePath,
            incoming = true, startMillis = FileNames.parse(file.name)!!.startedAt, message = message,
        )
    }

    @Test fun aCallJustSavedUnderTheRuleGoesStraightToTheTrash() {
        Retention.setMinSeconds(app, 10)
        val short = saved(seconds = 3.0)
        val heard = announced { assertTrue(Retention.dropIfTooShort(app, short)) }
        assertEquals(setOf(short.name), trashed())
        assertEquals(listOf(short.name), heard)
    }

    @Test fun aCallJustSavedIsKeptWhenTheRuleDoesNotApply() {
        Retention.setMinSeconds(app, 10)
        // Never a message; not at or over the minimum; not one with no length.
        listOf(saved(3.0, message = true), saved(10.0), saved(0.0)).forEach { kept ->
            assertFalse(Retention.dropIfTooShort(app, kept))
            assertTrue(kept.name, File(kept.filePath!!).exists())
            File(kept.filePath!!).delete()
        }
        // And nothing at all with the rule off.
        Retention.setMinSeconds(app, 0)
        assertFalse(Retention.dropIfTooShort(app, saved(1.0)))
        assertTrue(trashed().isEmpty())
    }

    @Test fun changingARuleRunsTheNextPassAtOnce() {
        Retention.run(app)
        assertTrue(lastRun() > 0)
        val short = call(daysAgo = 1, seconds = 3)
        Retention.setMinSeconds(app, 10)
        assertEquals(0L, lastRun())
        Retention.runIfDue(app)
        assertFalse(short.exists())
        // Set to what it already was, it still resets.
        Retention.setMaxAgeDays(app, 0)
        assertEquals(0L, lastRun())
    }
}
