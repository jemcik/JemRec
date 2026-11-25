package com.jemcik.jemrec.calls

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.capture.RecordingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** The notifications JemRec needs, off: which notice home shows, and
 *  which may be put away. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class NotificationHealthTest {

    private val app: Context = RuntimeEnvironment.getApplication()
    private val manager = app.getSystemService(NotificationManager::class.java)

    private val fullScreen = NotificationHealth.fullScreenAllowed

    @Before fun setUp() {
        Prefs.of(app).edit().clear().commit()
        AfterCall.ensureChannels(app)
        RecordPrompt.ensureChannel(app)
        // As a phone answers an app that holds the permission, as JemRec
        // does from its manifest; Robolectric's service says no to all.
        NotificationHealth.fullScreenAllowed = { true }
    }

    @org.junit.After fun tearDown() {
        NotificationHealth.fullScreenAllowed = fullScreen
    }

    /** Ask first's question turned down - below Urgent, so no pop-up - is
     *  said in Ask first, and may be put away: it still asks, in the shade. */
    @Test fun askFirstsQuestionTurnedDownIsSaid() {
        RecordingMode.set(app, RecordingMode.ON_DEMAND)
        listOf(NotificationManager.IMPORTANCE_DEFAULT, NotificationManager.IMPORTANCE_LOW, NotificationManager.IMPORTANCE_MIN)
            .forEach { importance ->
                manager.getNotificationChannel(RecordPrompt.CHANNEL_ID).importance = importance
                assertEquals("importance $importance", NotificationHealth.Off.ASK_FIRST_QUIET, notice())
            }
        assertTrue(NotificationHealth.dismissable(NotificationHealth.Off.ASK_FIRST_QUIET, askFirst = true))
        manager.getNotificationChannel(RecordPrompt.CHANNEL_ID).importance = NotificationManager.IMPORTANCE_HIGH
        assertNull(notice())
        // Off is off, not turned down.
        manager.getNotificationChannel(RecordPrompt.CHANNEL_ID).importance = NotificationManager.IMPORTANCE_NONE
        assertEquals(NotificationHealth.Off.ASK_FIRST, notice())
        // Recording every call, there is no question to miss.
        manager.getNotificationChannel(RecordPrompt.CHANNEL_ID).importance = NotificationManager.IMPORTANCE_LOW
        RecordingMode.set(app, RecordingMode.AUTOMATIC)
        assertNull(notice())
    }

    /** Full-screen intents taken away (Android 14 and later): the question
     *  shows briefly; said in Ask first, after a question turned down. */
    @Test fun fullScreenTakenAwayIsSaidInAskFirst() {
        NotificationHealth.fullScreenAllowed = { false }
        assertNull(notice())
        RecordingMode.set(app, RecordingMode.ON_DEMAND)
        assertEquals(NotificationHealth.Off.ASK_FIRST_BRIEF, notice())
        assertTrue(NotificationHealth.dismissable(NotificationHealth.Off.ASK_FIRST_BRIEF, askFirst = true))
        manager.getNotificationChannel(RecordPrompt.CHANNEL_ID).importance = NotificationManager.IMPORTANCE_DEFAULT
        assertEquals(NotificationHealth.Off.ASK_FIRST_QUIET, notice())
    }

    @Test @Config(sdk = [33]) fun fullScreenIsNotAskedBeforeAndroid14() {
        NotificationHealth.fullScreenAllowed = { error("not on Android 13") }
        RecordingMode.set(app, RecordingMode.ON_DEMAND)
        assertNull(notice())
    }

    /** Each case's own page: the question's channel; full-screen intents'
     *  ("the settings page where users can grant the permission"). */
    @Test fun eachOpensItsOwnSettingsPage() {
        val shadow = shadowOf(RuntimeEnvironment.getApplication())
        NotificationHealth.openSettings(app, NotificationHealth.Off.ASK_FIRST_QUIET)
        shadow.nextStartedActivity.let {
            assertEquals(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, it.action)
            assertEquals(RecordPrompt.CHANNEL_ID, it.getStringExtra(android.provider.Settings.EXTRA_CHANNEL_ID))
        }
        NotificationHealth.openSettings(app, NotificationHealth.Off.ASK_FIRST_BRIEF)
        shadow.nextStartedActivity.let {
            assertEquals(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, it.action)
            assertEquals("package:" + app.packageName, it.dataString)
        }
    }

    /** One put away stays away while it is off, and the next one down is
     *  shown: it hid every one below it (review round 9). */
    @Test fun aNoticePutAwayLetsTheNextOneDownShow() {
        RecordingMode.set(app, RecordingMode.ON_DEMAND)
        manager.getNotificationChannel(RecordPrompt.CHANNEL_ID).importance = NotificationManager.IMPORTANCE_LOW
        turnOff(AfterCall.PROBLEM_CHANNEL_ID)
        assertEquals(NotificationHealth.Off.ASK_FIRST_QUIET, notice())
        NotificationHealth.dismiss(app, NotificationHealth.Off.ASK_FIRST_QUIET)
        assertEquals(NotificationHealth.Off.PROBLEMS, notice())
        // Full-screen off under a question turned down says nothing more:
        // below Urgent a full-screen intent does nothing (review round 10).
        NotificationHealth.fullScreenAllowed = { false }
        assertEquals(NotificationHealth.Off.PROBLEMS, notice())
        NotificationHealth.dismiss(app, NotificationHealth.Off.PROBLEMS)
        assertNull(notice())
        // The question back up: full-screen off is said now, and the
        // question's dismissal is forgotten, so turned down again it is
        // said again.
        manager.getNotificationChannel(RecordPrompt.CHANNEL_ID).importance = NotificationManager.IMPORTANCE_HIGH
        assertEquals(NotificationHealth.Off.ASK_FIRST_BRIEF, notice())
        NotificationHealth.dismiss(app, NotificationHealth.Off.ASK_FIRST_BRIEF)
        assertNull(notice())
        manager.getNotificationChannel(RecordPrompt.CHANNEL_ID).importance = NotificationManager.IMPORTANCE_LOW
        assertEquals(NotificationHealth.Off.ASK_FIRST_QUIET, notice())
        // Back to Urgent: full-screen off, put away and off all along under
        // the cover, stays away (review round 11).
        manager.getNotificationChannel(RecordPrompt.CHANNEL_ID).importance = NotificationManager.IMPORTANCE_HIGH
        assertNull(notice())
    }

    /** All off, put away in Record every call: nothing below it comes up
     *  in its place - the problems channel off with it is the same cost
     *  (review round 10). */
    @Test fun allOffPutAwayBringsNothingBelowIt() {
        turnOff(AfterCall.PROBLEM_CHANNEL_ID)
        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals(NotificationHealth.Off.ALL, notice())
        NotificationHealth.dismiss(app, NotificationHealth.Off.ALL)
        assertNull(notice())
    }

    /** What home would say, Android asked now. */
    private fun notice() = NotificationHealth.notice(app, NotificationHealth.switches(app), overlayAllowed = false)

    private fun turnOff(channel: String) {
        manager.createNotificationChannel(NotificationChannel(channel, channel, NotificationManager.IMPORTANCE_NONE))
        // An app cannot lower a channel itself; the user does, in Android's
        // settings. The shadow's channel is replaced as theirs would be.
        manager.getNotificationChannel(channel).importance = NotificationManager.IMPORTANCE_NONE
    }

    @Test fun allIsWellSaysNothing() {
        assertNull(notice())
    }

    @Test fun allOffComesFirst() {
        shadowOf(manager).setNotificationsEnabled(false)
        turnOff(AfterCall.PROBLEM_CHANNEL_ID)
        assertEquals(NotificationHealth.Off.ALL, notice())
    }

    @Test fun askFirstsQuestionOffMattersInAskFirstAlone() {
        turnOff(RecordPrompt.CHANNEL_ID)
        RecordingMode.set(app, RecordingMode.AUTOMATIC)
        assertNull(notice())
        RecordingMode.set(app, RecordingMode.ON_DEMAND)
        assertEquals(NotificationHealth.Off.ASK_FIRST, notice())
    }

    @Test fun whatLeavesCallsUnrecordedCannotBePutAway() {
        assertFalse(NotificationHealth.dismissable(NotificationHealth.Off.ASK_FIRST, askFirst = true))
        assertFalse(NotificationHealth.dismissable(NotificationHealth.Off.ALL, askFirst = true))
        assertTrue(NotificationHealth.dismissable(NotificationHealth.Off.ALL, askFirst = false))
        assertTrue(NotificationHealth.dismissable(NotificationHealth.Off.PROBLEMS, askFirst = true))
    }

    @Test fun aNoticePutAwayStaysAwayUntilAllIsWellAgain() {
        turnOff(AfterCall.PROBLEM_CHANNEL_ID)
        // Shown first, as the card is before it can be put away.
        assertEquals(NotificationHealth.Off.PROBLEMS, notice())
        NotificationHealth.dismiss(app, NotificationHealth.Off.PROBLEMS)
        assertNull(notice())
        // Something else off: said.
        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals(NotificationHealth.Off.ALL, notice())
        // All back on, the problems channel still off: still put away - it
        // never stopped being off under the cover (review round 11).
        shadowOf(manager).setNotificationsEnabled(true)
        assertNull(notice())
        // All well again, the dismissal is forgotten: off once more, said.
        manager.getNotificationChannel(AfterCall.PROBLEM_CHANNEL_ID).importance = NotificationManager.IMPORTANCE_DEFAULT
        assertNull(notice())
        manager.getNotificationChannel(AfterCall.PROBLEM_CHANNEL_ID).importance = NotificationManager.IMPORTANCE_NONE
        assertEquals(NotificationHealth.Off.PROBLEMS, notice())
    }

    @Test fun aChannelNotMadeYetIsNotOff() {
        manager.deleteNotificationChannel(RecordPrompt.CHANNEL_ID)
        assertEquals(false, NotificationHealth.switches(app).askFirst)
        RecordingMode.set(app, RecordingMode.ON_DEMAND)
        assertNull(notice())
    }

    /** What the notice says is decided from what Android said and the mode
     *  then - preferences only, so as a reading lands, on the main thread. */
    @Test fun theNoticeIsDecidedFromWhatAndroidSaidAndTheMode() {
        val askOff = NotificationHealth.Switches(all = false, askFirst = true, problems = false)
        RecordingMode.set(app, RecordingMode.ON_DEMAND)
        assertEquals(NotificationHealth.Off.ASK_FIRST, NotificationHealth.notice(app, askOff, overlayAllowed = false))
        RecordingMode.set(app, RecordingMode.AUTOMATIC)
        assertNull(NotificationHealth.notice(app, askOff, overlayAllowed = false))
        assertNull(NotificationHealth.notice(app, null, overlayAllowed = false))
    }

    /** With Ask first's button over calls working, the question's
     *  notification off, quiet or brief leaves no call unasked: not said, and
     *  all of them off can be put away as in Record every call. */
    @Test fun withItsButtonOverCallsAskFirstNeedsNoNotification() {
        RecordingMode.set(app, RecordingMode.ON_DEMAND)
        CallButton.setEnabled(app, true)
        val off = NotificationHealth.Switches(all = false, askFirst = true, problems = false)
        val quiet = NotificationHealth.Switches(all = false, askFirst = false, problems = false, askFirstQuiet = true)
        val brief = NotificationHealth.Switches(all = false, askFirst = false, problems = false, askFirstBrief = true)
        listOf(off, quiet, brief).forEach { assertNull("$it", NotificationHealth.notice(app, it, overlayAllowed = true)) }
        val all = NotificationHealth.Switches(all = true, askFirst = false, problems = false)
        assertEquals(NotificationHealth.Off.ALL, NotificationHealth.notice(app, all, overlayAllowed = true))
        NotificationHealth.dismiss(app, NotificationHealth.Off.ALL)
        assertNull(NotificationHealth.notice(app, all, overlayAllowed = true))
        // The permission taken back, or the button off: no button to ask with.
        assertEquals(NotificationHealth.Off.ASK_FIRST, NotificationHealth.notice(app, off, overlayAllowed = false))
        CallButton.setEnabled(app, false)
        assertEquals(NotificationHealth.Off.ASK_FIRST, NotificationHealth.notice(app, off, overlayAllowed = true))
    }

    /** Put away while all were off; then only the problems' off; then all
     *  again: said again - what was put away changed in between. */
    @Test fun aNoticePutAwayIsSaidAgainOnceWhatIsOffChanged() {
        RecordingMode.set(app, RecordingMode.AUTOMATIC)
        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals(NotificationHealth.Off.ALL, notice())
        NotificationHealth.dismiss(app, NotificationHealth.Off.ALL)
        assertNull(notice())
        shadowOf(manager).setNotificationsEnabled(true)
        turnOff(AfterCall.PROBLEM_CHANNEL_ID)
        assertEquals(NotificationHealth.Off.PROBLEMS, notice())
        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals(NotificationHealth.Off.ALL, notice())
    }

    /** Switched to Ask first and back, nothing changed in Android: the
     *  problems' notice put away stays away. Kept by the notice, Ask first's
     *  showing in between forgot it (review, 2026-11-18). */
    @Test fun aModeFlipKeepsADismissal() {
        RecordingMode.set(app, RecordingMode.AUTOMATIC)
        turnOff(AfterCall.PROBLEM_CHANNEL_ID)
        turnOff(RecordPrompt.CHANNEL_ID)
        assertEquals(NotificationHealth.Off.PROBLEMS, notice())
        NotificationHealth.dismiss(app, NotificationHealth.Off.PROBLEMS)
        assertNull(notice())
        RecordingMode.set(app, RecordingMode.ON_DEMAND)
        assertEquals(NotificationHealth.Off.ASK_FIRST, notice())
        RecordingMode.set(app, RecordingMode.AUTOMATIC)
        assertNull(notice())
    }
}
