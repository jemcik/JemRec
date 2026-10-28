package com.jemcik.jemrec.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicInteger

/**
 * The starts the screen shows as Starting - a revive, the wait for a
 * recorder started elsewhere, a turn on, the switch's change of Start without
 * Wi-Fi's port - counted together: Starting while any is under way, so none
 * can end another's. Four flags, each cleared by its own start, did - found
 * one writer at a time over four review rounds (2026-10-28), the header
 * saying Ready, or Cannot record, while a start was still going.
 *
 * Each [begin] has exactly one [end]. The count is changed outside the
 * state's update, which may run its block again, and read inside it.
 */
internal class StartsUnderWay(private val state: MutableStateFlow<UiState>) {

    private val count = AtomicInteger(0)

    fun begin() {
        count.incrementAndGet()
        state.update { it.copy(starting = true) }
    }

    fun end() {
        count.decrementAndGet()
        state.update { it.copy(starting = count.get() > 0) }
    }
}
