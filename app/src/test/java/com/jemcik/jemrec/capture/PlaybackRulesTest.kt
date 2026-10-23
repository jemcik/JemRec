package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.PlaybackRules.Access
import com.jemcik.jemrec.capture.PlaybackRules.Focus
import com.jemcik.jemrec.capture.PlaybackRules.Playing
import com.jemcik.jemrec.capture.PlaybackRules.Titles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** What background playback decides: the names the system's controls show,
 *  what audio focus and a pulled plug do, who may connect. */
class PlaybackRulesTest {

    @Test fun aKnownCallerIsTheTitleAndTheCallTheLineUnder() {
        assertEquals(
            Titles("Olivia Bennett", "Phone call · Today 14:05"),
            PlaybackRules.titles("Olivia Bennett", app = null, message = false, whenLabel = "Today 14:05"),
        )
        assertEquals(
            Titles("Marcus Chen", "WhatsApp call · 3 Oct, 09:12"),
            PlaybackRules.titles("Marcus Chen", app = "WhatsApp", message = false, whenLabel = "3 Oct, 09:12"),
        )
        assertEquals(
            Titles("Olivia Bennett", "Message · Today 14:05"),
            PlaybackRules.titles("Olivia Bennett", app = null, message = true, whenLabel = "Today 14:05"),
        )
    }

    @Test fun withNobodyKnownItIsTheCallAndWhen() {
        assertEquals(Titles("Phone call", "Today 14:05"), PlaybackRules.titles(null, null, false, "Today 14:05"))
    }

    @Test fun lockedNothingOfWhoIsShown() {
        val unlocked = Titles("Olivia Bennett", "Phone call · Today 14:05")
        assertEquals(Titles("Call recording", null), PlaybackRules.shown(unlocked, locked = true))
        assertEquals(unlocked, PlaybackRules.shown(unlocked, locked = false))
    }

    @Test fun aBriefInterruptionHoldsPlayingBackAndRegainingTheAudioPlaysOn() {
        val held = PlaybackRules.onFocus(Focus.LOSS_TRANSIENT, Playing(wanted = true))
        assertFalse(held.sounding)
        assertTrue(held.wanted)
        assertTrue(PlaybackRules.onFocus(Focus.GAIN, held).sounding)
    }

    @Test fun askedToDuckSpeechPausesInsteadAndPlaysOnAfter() {
        val held = PlaybackRules.onFocus(Focus.LOSS_TRANSIENT_CAN_DUCK, Playing(wanted = true))
        assertFalse(held.sounding)
        assertTrue(PlaybackRules.onFocus(Focus.GAIN, held).sounding)
    }

    @Test fun anotherAppTakingTheAudioPausesUntilTheUserPlays() {
        val lost = PlaybackRules.onFocus(Focus.LOSS, Playing(wanted = true))
        assertEquals(Playing(wanted = false), lost)
        assertFalse(PlaybackRules.onFocus(Focus.GAIN, lost).sounding)
    }

    @Test fun regainingTheAudioNeverStartsWhatWasPaused() {
        val paused = Playing(wanted = false)
        assertEquals(paused, PlaybackRules.onFocus(Focus.LOSS_TRANSIENT, paused))
        assertFalse(PlaybackRules.onFocus(Focus.GAIN, PlaybackRules.onFocus(Focus.LOSS_TRANSIENT, paused)).sounding)
    }

    @Test fun headphonesOutPauseAndAnInterruptionEndingDoesNotUndoIt() {
        assertEquals(Playing(wanted = false), PlaybackRules.onBecomingNoisy(Playing(wanted = true)))
        val held = PlaybackRules.onFocus(Focus.LOSS_TRANSIENT, Playing(wanted = true))
        val unplugged = PlaybackRules.onBecomingNoisy(held)
        assertFalse(PlaybackRules.onFocus(Focus.GAIN, unplugged).sounding)
    }

    @Test fun jemRecChoosesWhatPlaysTheSystemOnlyPlaysItAndOthersAreTurnedAway() {
        assertEquals(Access.ALL, PlaybackRules.access(ownApp = true, trusted = true))
        assertEquals(Access.ALL, PlaybackRules.access(ownApp = true, trusted = false))
        assertEquals(Access.TRANSPORT, PlaybackRules.access(ownApp = false, trusted = true))
        assertEquals(Access.NONE, PlaybackRules.access(ownApp = false, trusted = false))
    }
}
