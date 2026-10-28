package com.jemcik.jemrec.ui

import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Starting while any of the screen's starts is under way, whichever ends first. */
class StartsUnderWayTest {

    private val state = MutableStateFlow(UiState())
    private val starts = StartsUnderWay(state)

    @Test fun oneStartEndingLeavesAnotherStarting() {
        // A revive ends while a turn-on still waits its turn: before, it
        // cleared Starting under the turn-on.
        starts.begin()
        starts.begin()
        starts.end()
        assertTrue(state.value.starting)
        starts.end()
        assertFalse(state.value.starting)
    }

    @Test fun inEitherOrder() {
        starts.begin()
        starts.end()
        assertFalse(state.value.starting)
        starts.begin()
        starts.begin()
        starts.end()
        starts.begin()
        starts.end()
        assertTrue(state.value.starting)
        starts.end()
        assertFalse(state.value.starting)
    }
}
