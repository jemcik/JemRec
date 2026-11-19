package com.jemcik.jemrec.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.ConscryptMode

/**
 * One of a reading's reads (Reading's read): a value read is put, and one
 * that throws leaves the state's own value as it was - thrown on, it kept
 * the window unread, home at Checking and every other screen blank, until
 * the next return to the app (review, 2026-11-18). A cancellation is not a
 * failed read: it goes on up, and the reading stops.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class OneReadTest {

    private val app = RuntimeEnvironment.getApplication()

    private val battery = { restricted: suspend () -> Boolean ->
        read({ restricted() }) { copy(batteryRestricted = it) }
    }

    @Test fun aValueReadIsPut() = runBlocking {
        val put = battery { true }(app)
        assertEquals(true, put(UiState(batteryRestricted = false)).batteryRestricted)
    }

    @Test fun aReadThatThrowsKeepsTheValueThereWas() = runBlocking {
        val put = battery { error("the provider went away") }(app)
        val before = UiState(batteryRestricted = false, listUnread = true)
        assertEquals(before, put(before))
    }

    @Test fun aCancellationGoesOnUp() {
        val thrown = runCatching { runBlocking { battery { throw CancellationException("left") }(app) } }
        assertTrue(thrown.exceptionOrNull() is CancellationException)
    }
}
