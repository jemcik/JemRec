package com.jemcik.jemrec.recorder

import android.provider.Settings
import com.jemcik.jemrec.Prefs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

/**
 * Keep JemRec paired (KeepPaired): when setup asks, what waits for a
 * recorder and what goes once one has carried it out, and Start fresh -
 * against the real settings provider, with the recorder's part answered
 * here as a recorder would (the recorder's own part: ChecksTest).
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class KeepPairedTest {

    private val context = RuntimeEnvironment.getApplication()
    private val original = KeepPaired.recorder

    /** What the recorder is asked, in order, and how it answers. */
    private val asked = mutableListOf<Boolean>()
    private var answer: Boolean? = true

    private fun timeout(value: String?) = Settings.Global.putString(context.contentResolver, KeepPaired.SETTING, value)

    @Before fun aFreshInstall() {
        Prefs.of(context).edit().clear().commit()
        timeout(null)
        KeepPaired.recorder = { _, keep ->
            asked += keep
            // As the recorder does: off on a keep, back to what it was on a put back.
            if (answer == true) timeout(if (keep) "0" else null)
            answer
        }
    }

    @After fun theRecorderPutBack() {
        KeepPaired.recorder = original
    }

    @Test fun androidKeepsAPairingAWeekWhereNobodySetIt() {
        assertEquals(KeepPaired.DEFAULT_MS, KeepPaired.timeoutMs(context))
        assertFalse(KeepPaired.kept(context))
        assertEquals(7, KeepPaired.days(KeepPaired.timeoutMs(context)))
        // Developer options' "Disable adb authorization timeout" writes 0.
        timeout("0")
        assertTrue(KeepPaired.kept(context))
        // Only 0 keeps: Android forgets at once at a negative one.
        timeout("-1")
        assertFalse(KeepPaired.kept(context))
    }

    @Test fun daysAreWholeAndAtLeastOne() {
        assertEquals(1, KeepPaired.days(86_400_000L))
        assertEquals(2, KeepPaired.days(86_400_001L))
        assertEquals(1, KeepPaired.days(60_000L))
        assertEquals(1, KeepPaired.days(-1L))
        assertEquals(30, KeepPaired.days(30 * 86_400_000L))
        // However large someone set it: no overflow into a negative count.
        assertEquals(Int.MAX_VALUE, KeepPaired.days(Long.MAX_VALUE))
    }

    @Test fun setupAsksOnceAndNotWhereTheTimeoutIsOffByHand() = runBlocking {
        assertTrue(KeepPaired.needsChoice(context))
        assertEquals(KeepPaired.Outcome.DONE, KeepPaired.choose(context, false))
        assertFalse(KeepPaired.needsChoice(context))
        // A phone whose owner turned the timeout off: nothing to ask.
        Prefs.of(context).edit().clear().commit()
        timeout("0")
        assertFalse(KeepPaired.needsChoice(context))
    }

    @Test fun aKeepIsCarriedOutByTheRecorderAndThenNothingWaits() = runBlocking {
        assertEquals(KeepPaired.Outcome.DONE, KeepPaired.choose(context, true))
        assertEquals(listOf(true), asked)
        assertTrue(KeepPaired.kept(context))
        assertNull(KeepPaired.waiting(context))
        // Nothing waits: the next recorder is not asked again (pushMode).
        assertEquals(KeepPaired.Outcome.DONE, KeepPaired.carryOut(context))
        assertEquals(listOf(true), asked)
    }

    @Test fun aChoiceWithNoRecorderWaitsForTheNextOne() = runBlocking {
        // Setup asks before pairing: no recorder yet.
        answer = null
        assertEquals(KeepPaired.Outcome.WAITING, KeepPaired.choose(context, true))
        assertEquals(true, KeepPaired.waiting(context))
        assertFalse(KeepPaired.kept(context))
        // Asked all the same: setup moves on.
        assertFalse(KeepPaired.needsChoice(context))
        // The recorder setup starts.
        answer = true
        assertEquals(KeepPaired.Outcome.DONE, KeepPaired.carryOut(context))
        assertTrue(KeepPaired.kept(context))
        assertNull(KeepPaired.waiting(context))
        assertEquals(listOf(true, true), asked)
    }

    @Test fun aRefusalWaitsAndIsToldApartUntilATryWorks() = runBlocking {
        answer = false
        assertEquals(KeepPaired.Outcome.REFUSED, KeepPaired.choose(context, true))
        assertEquals(true, KeepPaired.waiting(context))
        assertTrue("the refusal is kept, for the switch to say", KeepPaired.refused(context))
        assertFalse(KeepPaired.kept(context))
        // Still refused at the next recorder: still said.
        assertEquals(KeepPaired.Outcome.REFUSED, KeepPaired.carryOut(context))
        assertTrue(KeepPaired.refused(context))
        // Allowed later - an OPPO-family phone's switch turned on.
        answer = true
        assertEquals(KeepPaired.Outcome.DONE, KeepPaired.carryOut(context))
        assertTrue(KeepPaired.kept(context))
        assertFalse(KeepPaired.refused(context))
    }

    @Test fun aNewChoiceClearsAnOldRefusal() = runBlocking {
        answer = false
        KeepPaired.choose(context, true)
        answer = null
        assertEquals(KeepPaired.Outcome.WAITING, KeepPaired.choose(context, false))
        assertFalse(KeepPaired.refused(context))
    }

    /** The screen's choice and a refresh's send one at a time: a put back
     *  read before the user turned the switch on, and slow to reach the
     *  recorder, goes first - then the keep - and the timeout ends off. */
    @Test fun anOlderChoiceIsNeverSentAfterANewerOne() = runBlocking {
        Prefs.of(context).edit().putBoolean("keep_paired_wish", false).commit()
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        KeepPaired.recorder = { _, keep ->
            if (!keep) {
                entered.complete(Unit)
                gate.await()
            }
            asked += keep
            timeout(if (keep) "0" else null)
            true
        }
        val refresh = launch(Dispatchers.Default) { KeepPaired.carryOut(context) }
        // The refresh holds the old wish, its send held up.
        entered.await()
        val tap = async(Dispatchers.Default) { KeepPaired.choose(context, true) }
        // Unordered, the tap's send would have gone by now.
        delay(200)
        gate.complete(Unit)
        refresh.join()
        assertEquals(KeepPaired.Outcome.DONE, tap.await())
        assertEquals(listOf(false, true), asked)
        assertTrue(KeepPaired.kept(context))
        assertNull(KeepPaired.waiting(context))
    }

    @Test fun aChoiceMadeWhileTheLastWasSentIsNotLost() = runBlocking {
        // The switch flipped back while the recorder carried out the keep.
        KeepPaired.recorder = { _, keep ->
            asked += keep
            if (keep) Prefs.of(context).edit().putBoolean("keep_paired_wish", false).commit()
            timeout(if (keep) "0" else null)
            true
        }
        assertEquals(KeepPaired.Outcome.WAITING, KeepPaired.choose(context, true))
        assertEquals(false, KeepPaired.waiting(context))
        assertEquals(KeepPaired.Outcome.DONE, KeepPaired.carryOut(context))
        assertEquals(listOf(true, false), asked)
        assertFalse(KeepPaired.kept(context))
    }

    @Test fun startFreshPutsItBackAndAsksAgain() = runBlocking {
        KeepPaired.choose(context, true)
        KeepPaired.forget(context)
        assertEquals(listOf(true, false), asked)
        assertFalse(KeepPaired.kept(context))
        assertNull(KeepPaired.waiting(context))
        assertTrue(KeepPaired.needsChoice(context))
    }

    @Test fun startFreshWithNoRecorderPutsItBackWithTheNextAndAsksBeforeIt() = runBlocking {
        KeepPaired.choose(context, true)
        answer = null
        KeepPaired.forget(context)
        // Still off, but JemRec's doing: setup asks again.
        assertTrue(KeepPaired.kept(context))
        assertEquals(false, KeepPaired.waiting(context))
        assertTrue(KeepPaired.needsChoice(context))
        // Leave it: the put back goes to the recorder the setup starts.
        assertEquals(KeepPaired.Outcome.WAITING, KeepPaired.choose(context, false))
        answer = true
        assertEquals(KeepPaired.Outcome.DONE, KeepPaired.carryOut(context))
        assertFalse(KeepPaired.kept(context))
        assertFalse(KeepPaired.needsChoice(context))
    }
}
