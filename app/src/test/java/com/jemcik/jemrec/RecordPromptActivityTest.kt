package com.jemcik.jemrec

import android.content.Intent
import com.jemcik.jemrec.calls.RecordPrompt
import com.jemcik.jemrec.capture.CallMonitorService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/** Ask first's question as a window over a call (RecordPromptActivity): up
 *  for its own question alone, and gone once that is answered or over. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class RecordPromptActivityTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun window(ticket: String?) = Robolectric.buildActivity(
        RecordPromptActivity::class.java,
        Intent(context, RecordPromptActivity::class.java).putExtra(CallMonitorService.EXTRA_TICKET, ticket),
    ).setup()

    private fun idle() = shadowOf(android.os.Looper.getMainLooper()).idle()

    @Test fun itStaysWhileItsQuestionWaits() {
        RecordPrompt.show(context, incoming = true)
        val activity = window(RecordPrompt.ticket(context)).get()
        idle()
        assertFalse(activity.isFinishing)
    }

    @Test fun itGoesOnceTheCallIsOver() {
        RecordPrompt.show(context, incoming = true)
        val activity = window(RecordPrompt.ticket(context)).get()
        RecordPrompt.dismiss(context)
        idle()
        assertTrue(activity.isFinishing)
    }

    @Test fun itGoesOnceItIsAnsweredElsewhere() {
        RecordPrompt.show(context, incoming = true)
        val ticket = RecordPrompt.ticket(context)
        val activity = window(ticket).get()
        // Not now, from the banner: another ticket is live, for the call's own
        // notification, and none of it is this question's.
        assertTrue(RecordPrompt.claim(context, ticket))
        RecordPrompt.keepRecord(context)
        idle()
        assertTrue(activity.isFinishing)
    }

    @Test fun openedForAQuestionAlreadyOverItShowsNothing() {
        RecordPrompt.show(context, incoming = true)
        val ticket = RecordPrompt.ticket(context)
        RecordPrompt.dismiss(context)
        assertTrue(window(ticket).get().isFinishing)
    }

    @Test fun backClosesItAndAnswersNothing() {
        RecordPrompt.show(context, incoming = true)
        val activity = window(RecordPrompt.ticket(context)).get()
        idle()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertNotNull("drawn as a dialog", dialog)
        (dialog as androidx.activity.ComponentDialog).onBackPressedDispatcher.onBackPressed()
        idle()
        assertTrue(activity.isFinishing)
        assertNull("no answer sent", shadowOf(context).nextStartedService)
        assertNotNull("the question still waits", RecordPrompt.ticket(context))
    }
}
