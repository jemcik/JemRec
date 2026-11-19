package com.jemcik.jemrec.system

import android.app.ActivityManager
import android.content.Context
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.capture.RecordingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** JemRec's Battery setting Restricted: what Android is asked, when home
 *  says so, and when it may be put away. */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class BatteryHealthTest {

    private val app: Context = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        Prefs.of(app).edit().clear().commit()
        RecordingMode.set(app, RecordingMode.AUTOMATIC)
    }

    @Test fun androidsOwnWordIsRead() {
        val manager = app.getSystemService(ActivityManager::class.java)
        shadowOf(manager).setBackgroundRestricted(true)
        assertTrue(BatteryHealth.restricted(app))
        shadowOf(manager).setBackgroundRestricted(false)
        assertFalse(BatteryHealth.restricted(app))
    }

    @Test fun nothingReadYetSaysNothing() {
        assertFalse(BatteryHealth.notice(app, null))
    }

    @Test fun restrictedIsSaidUntilPutAway() {
        assertTrue(BatteryHealth.notice(app, true))
        BatteryHealth.dismiss(app)
        assertFalse(BatteryHealth.notice(app, true))
    }

    /** In Ask first a call may go unasked, and so unrecorded: the notice
     *  stays, though it was put away in Automatic. */
    @Test fun inAskFirstItCannotBePutAway() {
        BatteryHealth.dismiss(app)
        RecordingMode.set(app, RecordingMode.ON_DEMAND)
        assertTrue(BatteryHealth.notice(app, true))
        assertFalse(BatteryHealth.dismissable(askFirst = true))
        assertTrue(BatteryHealth.dismissable(askFirst = false))
    }

    /** Lifted, the dismissal is forgotten: restricted again, it is said. */
    @Test fun restrictedAgainIsSaidAgain() {
        BatteryHealth.dismiss(app)
        assertFalse(BatteryHealth.notice(app, false))
        assertTrue(BatteryHealth.notice(app, true))
    }

    private fun told() = shadowOf(app.getSystemService(android.app.NotificationManager::class.java))
        .allNotifications.count { it.extras.getString("android.title") == BatteryHealth.TITLE }

    private fun setUpAndOn() {
        com.jemcik.jemrec.setup.Setup.markComplete(app)
        com.jemcik.jemrec.recorder.RecorderSwitch.set(app, true)
    }

    /** Said once in a notification while Restricted, at a start of the
     *  process - once even across starts, though put away from the shade;
     *  lifted, it goes, and set again, it is said again. */
    /** Its Open App info button names JemRec's own activity and the battery
     *  link: an implicit intent in a notification's PendingIntent could be
     *  answered by another app's activity (CodeQL
     *  java/android/implicit-pendingintents, PR #105). */
    @Test fun itsButtonOpensJemRecsOwnActivity() {
        setUpAndOn()
        shadowOf(app.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(true)
        BatteryHealth.tellIfRestricted(app)
        val posted = shadowOf(app.getSystemService(android.app.NotificationManager::class.java)).allNotifications
            .single { it.extras.getString("android.title") == BatteryHealth.TITLE }
        val intent = shadowOf(posted.actions.single().actionIntent).savedIntent
        assertEquals(app.packageName, intent.component?.packageName)
        assertEquals("BATTERY_SETTINGS", intent.getStringExtra("link"))
    }

    @Test fun restrictedIsToldOnceUntilLifted() {
        setUpAndOn()
        val manager = app.getSystemService(ActivityManager::class.java)
        val notifications = app.getSystemService(android.app.NotificationManager::class.java)
        shadowOf(manager).setBackgroundRestricted(true)
        BatteryHealth.tellIfRestricted(app)
        assertTrue(told() == 1)
        notifications.cancelAll()
        BatteryHealth.tellIfRestricted(app)
        assertTrue("not said again", told() == 0)
        shadowOf(manager).setBackgroundRestricted(false)
        BatteryHealth.tellIfRestricted(app)
        shadowOf(manager).setBackgroundRestricted(true)
        BatteryHealth.tellIfRestricted(app)
        assertTrue(told() == 1)
    }

    /** A reading that finds it lifted takes the notification back at once,
     *  not at the process's next start. */
    @Test fun aReadingThatFindsItLiftedTakesTheNoticeBack() {
        setUpAndOn()
        val manager = app.getSystemService(ActivityManager::class.java)
        shadowOf(manager).setBackgroundRestricted(true)
        BatteryHealth.tellIfRestricted(app)
        assertTrue(told() == 1)
        shadowOf(manager).setBackgroundRestricted(false)
        assertFalse(BatteryHealth.read(app))
        assertTrue(told() == 0)
    }

    /** JemRec switched off, or not set up: nothing it records is held back.
     *  Each on its own - the switch is on by default, so not set up alone
     *  passed a gate on setup alone (review, 2026-11-18). */
    @Test fun offOrNotSetUpSaysNothing() {
        shadowOf(app.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(true)
        BatteryHealth.tellIfRestricted(app)
        assertTrue("not set up", told() == 0)
        com.jemcik.jemrec.setup.Setup.markComplete(app)
        com.jemcik.jemrec.recorder.RecorderSwitch.set(app, false)
        BatteryHealth.tellIfRestricted(app)
        assertTrue("switched off", told() == 0)
        com.jemcik.jemrec.recorder.RecorderSwitch.set(app, true)
        BatteryHealth.tellIfRestricted(app)
        assertTrue("on and set up", told() == 1)
    }

    /** What it says follows the mode and call tracking: with tracking off
     *  Android refuses every call's wake - in Ask first none is asked about,
     *  in Record every call each is saved only once JemRec opens; with it on,
     *  some (a messenger's). The cost said in each, not only the clause all
     *  share: "some" said for tracking off passed (review, 2026-11-18). */
    @Test fun itsWordsFollowTheModeAndTracking() {
        val cost = { askFirst: Boolean, trackingOff: Boolean ->
            BatteryHealth.words(askFirst, trackingOff).substringAfter("missing calls meanwhile, and ").substringBefore(".")
        }
        assertEquals("no call can be asked about, so none is recorded", cost(true, true))
        assertEquals("calls it cannot ask about are not recorded", cost(true, false))
        assertEquals("every call is saved only when you open it", cost(false, true))
        assertEquals("some calls are saved only when you open it", cost(false, false))
        for (askFirst in listOf(true, false)) for (off in listOf(true, false)) {
            assertTrue(BatteryHealth.words(askFirst, off).contains("may stay stopped until you open JemRec"))
        }
    }

    /** A keep-alive run reads the setting: lifted - from the notification's
     *  own button, which does not clear it - the notification goes with it;
     *  still set - a run with JemRec on the screen - it stays (review,
     *  2026-11-18). */
    @Test fun aKeepAliveRunWhileStillRestrictedKeepsTheNotice() {
        setUpAndOn()
        val manager = app.getSystemService(ActivityManager::class.java)
        shadowOf(manager).setBackgroundRestricted(true)
        BatteryHealth.tellIfRestricted(app)
        assertTrue(told() == 1)
        kotlinx.coroutines.runBlocking {
            androidx.work.testing.TestListenableWorkerBuilder<com.jemcik.jemrec.capture.RecorderKeepAlive>(app).build().doWork()
        }
        assertTrue("still said", told() == 1)
    }

    @Test fun aKeepAliveRunAfterItIsLiftedTakesTheNoticeBack() {
        setUpAndOn()
        val manager = app.getSystemService(ActivityManager::class.java)
        shadowOf(manager).setBackgroundRestricted(true)
        BatteryHealth.tellIfRestricted(app)
        assertTrue(told() == 1)
        shadowOf(manager).setBackgroundRestricted(false)
        kotlinx.coroutines.runBlocking {
            androidx.work.testing.TestListenableWorkerBuilder<com.jemcik.jemrec.capture.RecorderKeepAlive>(app).build().doWork()
        }
        assertTrue(told() == 0)
    }

    /** A notification the user put away from the shade stays away when
     *  the mode or tracking changes its words - re-posted, it came back,
     *  with its sound (review round 9). One still up is said again. */
    @Test fun aNotificationPutAwayIsNotPostedAgainByAChangeOfWords() {
        setUpAndOn()
        val manager = app.getSystemService(ActivityManager::class.java)
        val notifications = app.getSystemService(android.app.NotificationManager::class.java)
        shadowOf(manager).setBackgroundRestricted(true)
        BatteryHealth.tellIfRestricted(app)
        assertTrue(told() == 1)
        BatteryHealth.refresh(app)
        assertTrue("still up, said again", told() == 1)
        notifications.cancelAll()
        BatteryHealth.refresh(app)
        assertTrue("put away, left away", told() == 0)
    }

    /** "Not now" on home's card takes the notification down too, still
     *  told: its tap opened home, where the card had been put away. */
    @Test fun notNowTakesTheNotificationDownStillTold() {
        setUpAndOn()
        val manager = app.getSystemService(ActivityManager::class.java)
        shadowOf(manager).setBackgroundRestricted(true)
        BatteryHealth.tellIfRestricted(app)
        assertTrue(told() == 1)
        BatteryHealth.dismiss(app)
        assertTrue(told() == 0)
        BatteryHealth.tellIfRestricted(app)
        assertTrue("not said again at the next start", told() == 0)
    }

    /** Put away, then JemRec switched Off and On: not posted again - the
     *  switch took back what was told and forgot it, the card stayed put
     *  away, and the notification came back with its sound (review round
     *  10). */
    @Test fun notNowHoldsThroughOffAndOn() {
        setUpAndOn()
        shadowOf(app.getSystemService(ActivityManager::class.java)).setBackgroundRestricted(true)
        BatteryHealth.tellIfRestricted(app)
        BatteryHealth.dismiss(app)
        com.jemcik.jemrec.recorder.RecorderSwitch.set(app, false)
        BatteryHealth.tellIfRestricted(app)
        com.jemcik.jemrec.recorder.RecorderSwitch.set(app, true)
        BatteryHealth.tellIfRestricted(app)
        assertTrue("put away, left away", told() == 0)
        assertFalse(BatteryHealth.notice(app, true))
    }
}
