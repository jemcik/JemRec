package com.jemcik.jemrec.capture

import android.media.AudioManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** How the app reads a daemon's answer to quit, and what it takes for a call
 *  to be in progress when a BUSY is weighed (DaemonLauncher.retire). */
class QuitTest {

    @Test fun byeAndBusyAreReadAsTheDaemonWritesThem() {
        assertEquals(CaptureDaemon.QuitReply.BYE, CaptureDaemon.parseQuitReply("BYE"))
        assertEquals(CaptureDaemon.QuitReply.BUSY, CaptureDaemon.parseQuitReply("BUSY"))
    }

    @Test fun anythingElseIsNoAnswer() {
        assertEquals(CaptureDaemon.QuitReply.NONE, CaptureDaemon.parseQuitReply(null))
        assertEquals(CaptureDaemon.QuitReply.NONE, CaptureDaemon.parseQuitReply(""))
        assertEquals(CaptureDaemon.QuitReply.NONE, CaptureDaemon.parseQuitReply("BU"))
        assertEquals(CaptureDaemon.QuitReply.NONE, CaptureDaemon.parseQuitReply("NOPE"))
    }

    @Test fun onlyTheNormalAudioModeMeansNoCall() {
        assertFalse(CaptureDaemon.inCall(AudioManager.MODE_NORMAL))
        listOf(
            AudioManager.MODE_RINGTONE,
            AudioManager.MODE_IN_CALL,
            AudioManager.MODE_IN_COMMUNICATION,
            AudioManager.MODE_CALL_SCREENING,
            AudioManager.MODE_CALL_REDIRECT,
            AudioManager.MODE_COMMUNICATION_REDIRECT,
        ).forEach { mode -> assertTrue("mode $mode", CaptureDaemon.inCall(mode)) }
    }

    /** What waits out a call takes only a call's own modes - a ringtone's
     *  or screening's, which an app can leave set, held all of it for ever
     *  (review round 12). */
    @Test fun aCallIsUpOnlyInACallsMode() {
        assertTrue(com.jemcik.jemrec.recorder.DaemonLauncher.callUp(AudioManager.MODE_IN_CALL))
        assertTrue(com.jemcik.jemrec.recorder.DaemonLauncher.callUp(AudioManager.MODE_IN_COMMUNICATION))
        listOf(AudioManager.MODE_NORMAL, AudioManager.MODE_RINGTONE, AudioManager.MODE_CALL_SCREENING)
            .forEach { mode -> assertFalse("mode $mode", com.jemcik.jemrec.recorder.DaemonLauncher.callUp(mode)) }
    }
}
