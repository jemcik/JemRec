package com.jemcik.jemrec.capture

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito
import java.util.Date

/** What auto-delete removes, and - more to the point - what it never does. */
class RetentionTest {

    private val uri: Uri = Mockito.mock(Uri::class.java)
    private val now = 1_795_000_000_000L
    private val day = 24L * 60 * 60 * 1000

    private fun rec(daysAgo: Long, seconds: Long = 60, kept: Boolean = false, name: String? = null) = Recording(
        name = name ?: RecordingStore.nameFor(Date(now - daysAgo * day), true),
        uri = uri, whenLabel = "", incoming = true, durationLabel = "", sizeLabel = "",
        durationMillis = seconds * 1000, kept = kept,
    )

    private fun select(list: List<Recording>, maxAgeDays: Int = 0, minSeconds: Int = 0) =
        Retention.select(list, now, maxAgeDays, minSeconds)

    @Test fun withBothRulesOffNothingGoes() {
        assertTrue(select(listOf(rec(daysAgo = 900, seconds = 1))).isEmpty())
    }

    @Test fun olderThanTheRuleGoes() {
        val old = rec(daysAgo = 31)
        val recent = rec(daysAgo = 29)
        assertEquals(listOf(old), select(listOf(old, recent), maxAgeDays = 30))
    }

    @Test fun shorterThanTheRuleGoes() {
        val short = rec(daysAgo = 1, seconds = 4)
        val long = rec(daysAgo = 1, seconds = 5)
        assertEquals(listOf(short), select(listOf(short, long), minSeconds = 5))
    }

    @Test fun keptIsNeverTouchedByEitherRule() {
        val keptOldAndShort = rec(daysAgo = 400, seconds = 2, kept = true)
        assertTrue(select(listOf(keptOldAndShort), maxAgeDays = 30, minSeconds = 30).isEmpty())
    }

    @Test fun whatCannotBeReadIsLeftAlone() {
        // No date in the name, and a duration nobody could read: no guessing.
        val undated = rec(daysAgo = 0, name = "not-one-of-ours.ogg")
        val unmeasured = rec(daysAgo = 1, seconds = 0)
        assertTrue(select(listOf(undated, unmeasured), maxAgeDays = 30, minSeconds = 30).isEmpty())
    }
}
