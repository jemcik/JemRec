package com.jemcik.jemrec.capture

import com.jemcik.jemrec.capture.CallNotice.Notice
import com.jemcik.jemrec.capture.CallNotice.State
import org.junit.Assert.assertEquals
import org.junit.Test

/** What the call service's notification says, in its order of precedence. */
class CallNoticeTest {

    private val idle = State(
        callUp = false, recording = false, notRecording = false, messageTicket = null,
        testing = false, checkingRecorder = false, warningOnly = false,
    )

    private val call = Notice("Call in progress", "JemRec is handling this call", false, null)
    private val recording = Notice("Recording call", "Saving this call", false, null)
    private val notRecording = Notice("Not recording this call", "Tap to see why", false, null)
    private val message = Notice("Taking a message", "Your greeting is playing; your microphone is muted", false, "t")
    private val test = Notice("Testing the answering machine", "Your greeting is playing to the caller", false, "t")
    private val cable = Notice("Checking the recorder", "A USB cable went in or came out", true, null)
    private val warning = Notice("Checking JemRec", "Making sure it keeps running", true, null)

    @Test fun eachOnItsOwn() {
        assertEquals(call, CallNotice.of(idle))
        assertEquals(call, CallNotice.of(idle.copy(callUp = true)))
        assertEquals(recording, CallNotice.of(idle.copy(recording = true)))
        assertEquals(notRecording, CallNotice.of(idle.copy(notRecording = true)))
        assertEquals(message, CallNotice.of(idle.copy(messageTicket = "t")))
        assertEquals(test, CallNotice.of(idle.copy(messageTicket = "t", testing = true)))
        assertEquals(cable, CallNotice.of(idle.copy(checkingRecorder = true)))
        assertEquals(warning, CallNotice.of(idle.copy(warningOnly = true)))
        // Testing without a message being taken says nothing of it.
        assertEquals(call, CallNotice.of(idle.copy(testing = true)))
    }

    @Test fun aNoticeReadsOutAsItIs() {
        val notice = CallNotice.of(idle.copy(messageTicket = "t"))
        assertEquals("Taking a message", notice.title)
        assertEquals("Your greeting is playing; your microphone is muted", notice.text)
        assertEquals(false, notice.deferred)
        assertEquals("t", notice.buttons)
        assertEquals(true, CallNotice.of(idle.copy(checkingRecorder = true)).deferred)
    }

    @Test fun aMessageOutranksARecordingWhichOutranksNotRecording() {
        assertEquals(message, CallNotice.of(idle.copy(messageTicket = "t", recording = true, notRecording = true)))
        assertEquals(recording, CallNotice.of(idle.copy(recording = true, notRecording = true)))
    }

    @Test fun theChecksAreTheirOwnOnlyWithNothingElseUp() {
        val ups = listOf(idle.copy(callUp = true), idle.copy(recording = true), idle.copy(messageTicket = "t"))
        ups.forEach { up ->
            assertEquals(CallNotice.of(up), CallNotice.of(up.copy(checkingRecorder = true)))
            assertEquals(CallNotice.of(up), CallNotice.of(up.copy(warningOnly = true)))
        }
        // A cable watch's notice comes before the warning's.
        assertEquals(cable, CallNotice.of(idle.copy(checkingRecorder = true, warningOnly = true)))
        // And both before a failed save's "Not recording this call".
        assertEquals(warning, CallNotice.of(idle.copy(warningOnly = true, notRecording = true)))
        assertEquals(cable, CallNotice.of(idle.copy(checkingRecorder = true, notRecording = true)))
    }
}
