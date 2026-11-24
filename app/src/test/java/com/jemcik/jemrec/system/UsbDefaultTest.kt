package com.jemcik.jemrec.system

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import com.jemcik.jemrec.Prefs
import com.jemcik.jemrec.recorder.DaemonClient
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

/**
 * The Default USB configuration that stops the recorder at every lock
 * (#131): when home and the notification say so, what Not now puts away,
 * and what a set leaves behind - the state table's rows in
 * tmp/issue-131/PLAN.md, by number.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class UsbDefaultTest {

    private val app: Context = RuntimeEnvironment.getApplication()
    private val mtp = UsbDefault.FUNCTION_MTP
    private val ptp = UsbDefault.FUNCTION_PTP

    @Before fun setUp() {
        Prefs.of(app).edit().clear().commit()
        secure(true)
    }

    private fun secure(on: Boolean) =
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(on)

    private fun told() = shadowOf(app.getSystemService(NotificationManager::class.java))
        .allNotifications.count { it.extras.getString("android.title") == UsbDefault.TITLE }

    private fun setUpAndOn() {
        com.jemcik.jemrec.setup.Setup.markComplete(app)
        com.jemcik.jemrec.recorder.RecorderSwitch.set(app, true)
    }

    /** Debugging is no data; any other function is. */
    @Test fun dataModesAreAnythingButDebugging() {
        assertFalse(UsbDefault.dataMode(0L))
        assertFalse(UsbDefault.dataMode(UsbDefault.FUNCTION_ADB))
        assertTrue(UsbDefault.dataMode(mtp))
        assertTrue(UsbDefault.dataMode(mtp or UsbDefault.FUNCTION_ADB))
        assertTrue(UsbDefault.dataMode(UsbDefault.FUNCTION_NCM))
    }

    /** Named as Developer options lists them. */
    @Test fun eachModeIsNamedAsSettingsNamesIt() {
        assertEquals("File transfer", UsbDefault.name(mtp))
        assertEquals("PTP", UsbDefault.name(ptp))
        assertEquals("USB tethering", UsbDefault.name(UsbDefault.FUNCTION_RNDIS))
        assertEquals("USB tethering", UsbDefault.name(UsbDefault.FUNCTION_NCM))
        assertEquals("MIDI", UsbDefault.name(UsbDefault.FUNCTION_MIDI))
        assertEquals("Webcam", UsbDefault.name(UsbDefault.FUNCTION_UVC))
        assertEquals("a data mode", UsbDefault.name(1L shl 6))
        assertEquals("No data transfer", UsbDefault.name(0L))
    }

    /** Rows 1-3: said only for a data mode with a secure lock. Nothing read
     *  yet says nothing. */
    @Test fun saidOnlyForADataModeWithASecureLock() {
        assertFalse("row 1", UsbDefault.notice(app, 0L, true))
        assertFalse("row 2", UsbDefault.notice(app, mtp, false))
        assertTrue("row 3", UsbDefault.notice(app, mtp, true))
        assertFalse("nothing read", UsbDefault.notice(app, null, true))
        assertFalse("lock not read", UsbDefault.notice(app, mtp, null))
    }

    /** Android's own word on the lock is what is read. */
    @Test fun theLockIsAndroidsWord() {
        secure(true)
        assertTrue(UsbDefault.secureLock(app))
        secure(false)
        assertFalse(UsbDefault.secureLock(app))
    }

    /** Row 6: Not now is for the functions it named; another data mode is
     *  said again, and so is the same one once it was none in between. */
    @Test fun notNowIsForTheFunctionsItNamed() {
        UsbDefault.dismiss(app, mtp)
        assertFalse(UsbDefault.notice(app, mtp, true))
        assertTrue("another data mode", UsbDefault.notice(app, ptp, true))
        UsbDefault.dismiss(app, ptp)
        assertFalse(UsbDefault.notice(app, 0L, true))
        assertTrue("the same again after none", UsbDefault.notice(app, ptp, true))
    }

    /** Nothing read yet - a view model Android has just made - decides
     *  nothing about Not now: it stays put away (review round 2). */
    @Test fun anUnreadValueKeepsNotNow() {
        UsbDefault.dismiss(app, mtp)
        assertFalse(UsbDefault.notice(app, null, null))
        assertFalse(UsbDefault.notice(app, mtp, null))
        assertFalse(UsbDefault.notice(app, null, true))
        assertFalse("still put away", UsbDefault.notice(app, mtp, true))
    }

    /** Row 13: a tap the recorder could not carry out leaves the card up,
     *  with the way by hand on it - not only in a snackbar. */
    @Test fun aTapThatCouldNotSetItSaysTheWayByHand() {
        UsbDefault.keep(app, mtp)
        UsbDefault.noteTried(app)
        assertEquals(mtp, UsbDefault.stored(app))
        assertTrue(UsbDefault.didNotStay(app, mtp))
        assertTrue(UsbDefault.notice(app, mtp, true))
    }

    /** Row 8: the recorder's last answer is kept for while it is down. */
    @Test fun theLastAnswerIsKept() {
        assertNull(UsbDefault.stored(app))
        UsbDefault.keep(app, mtp)
        assertEquals(mtp, UsbDefault.stored(app))
    }

    /** Rows 9 and 14: a set leaves none as the answer; read as a data mode
     *  again, it did not stay, and the words say the way by hand; read as
     *  none, that is forgotten. */
    @Test fun aSetThatDoesNotStayIsSaidSo() {
        UsbDefault.keep(app, mtp)
        UsbDefault.noteSet(app)
        assertEquals(0L, UsbDefault.stored(app))
        assertFalse(UsbDefault.didNotStay(app, 0L))
        UsbDefault.keep(app, mtp)
        assertTrue(UsbDefault.didNotStay(app, mtp))
        assertTrue(UsbDefault.words(mtp, didNotStay = true).endsWith(
            "To set it by hand: Developer options, Default USB configuration, No data transfer."))
        assertFalse(UsbDefault.words(mtp, didNotStay = false).contains("by hand"))
        UsbDefault.keep(app, 0L)
        UsbDefault.keep(app, mtp)
        assertFalse("forgotten once none was read", UsbDefault.didNotStay(app, mtp))
    }

    /** Rows 9-13: each answer to a set, as what the screen does with it -
     *  a none answered to a set is no set, a data mode answered is not one
     *  done. */
    @Test fun eachAnswerToASetIsAnOutcome() {
        assertEquals(UsbDefault.Outcome.SETTING, UsbDefault.outcomeOf(DaemonClient.UsbDefaultReply.Setting))
        assertEquals(UsbDefault.Outcome.BUSY, UsbDefault.outcomeOf(DaemonClient.UsbDefaultReply.Busy))
        assertEquals(UsbDefault.Outcome.UNREADABLE, UsbDefault.outcomeOf(DaemonClient.UsbDefaultReply.Unreadable))
        assertEquals(UsbDefault.Outcome.NONE_ALREADY, UsbDefault.outcomeOf(DaemonClient.UsbDefaultReply.Default(0L)))
        assertEquals(UsbDefault.Outcome.UNREADABLE, UsbDefault.outcomeOf(DaemonClient.UsbDefaultReply.Default(mtp)))
        assertEquals(UsbDefault.Outcome.NO_ANSWER, UsbDefault.outcomeOf(null))
        assertEquals("Not during a call, or while a recording is saved: try again in a moment", UsbDefault.said(UsbDefault.Outcome.BUSY, null))
    }

    /** The words name the mode and what it costs. */
    @Test fun itsWordsNameTheModeAndTheCost() {
        val words = UsbDefault.words(mtp, didNotStay = false)
        assertTrue(words.startsWith("Developer options' Default USB configuration is File transfer."))
        assertTrue(words.contains("Calls while the phone is locked can go unrecorded"))
        assertTrue(words.contains("until you choose File transfer in the USB notification"))
    }

    /** Said once in a notification while it holds; taken back once it does
     *  not, and said again the next time. */
    @Test fun toldOnceWhileItHolds() {
        setUpAndOn()
        UsbDefault.keep(app, mtp)
        UsbDefault.tellIfAtRisk(app)
        assertEquals(1, told())
        app.getSystemService(NotificationManager::class.java).cancelAll()
        UsbDefault.tellIfAtRisk(app)
        assertEquals("not said again", 0, told())
        UsbDefault.keep(app, 0L)
        UsbDefault.tellIfAtRisk(app)
        UsbDefault.keep(app, mtp)
        UsbDefault.tellIfAtRisk(app)
        assertEquals("said again after none", 1, told())
    }

    private fun told(name: String) = shadowOf(app.getSystemService(NotificationManager::class.java))
        .allNotifications.filter { it.extras.getString("android.title") == UsbDefault.TITLE }
        .map { it.extras.getCharSequence("android.text").toString() to (it.flags and android.app.Notification.FLAG_ONLY_ALERT_ONCE != 0) }
        .also { assertTrue("$name: one notification at most", it.size <= 1) }

    /** Review round 1: told for File transfer, the default now PTP - said
     *  again in place, quietly, while it is up. */
    @Test fun newWordsAreSaidAgainInPlaceQuietly() {
        setUpAndOn()
        UsbDefault.keep(app, mtp)
        UsbDefault.tellIfAtRisk(app)
        val (first, firstQuiet) = told("first").single()
        assertTrue(first.contains("File transfer"))
        assertFalse("the first time, with its sound", firstQuiet)
        UsbDefault.keep(app, ptp)
        UsbDefault.tellIfAtRisk(app)
        val (text, quiet) = told("again").single()
        assertTrue(text, text.contains("PTP"))
        assertTrue("quietly", quiet)
    }

    /** One the user swiped away stays away, new words or not. */
    @Test fun aNotificationPutAwayIsNotPostedAgainForNewWords() {
        setUpAndOn()
        UsbDefault.keep(app, mtp)
        UsbDefault.tellIfAtRisk(app)
        app.getSystemService(NotificationManager::class.java).cancelAll()
        UsbDefault.keep(app, ptp)
        UsbDefault.tellIfAtRisk(app)
        assertEquals(0, told())
    }

    /** A reading says new words, but never posts a notification anew - as
     *  BatteryHealth's reading does not. */
    @Test fun aReadingNeverPostsANewNotification() {
        setUpAndOn()
        UsbDefault.keep(app, mtp)
        kotlinx.coroutines.runBlocking { UsbDefault.read(app) }
        assertEquals("not from a reading", 0, told())
        UsbDefault.tellIfAtRisk(app)
        UsbDefault.keep(app, ptp)
        kotlinx.coroutines.runBlocking { UsbDefault.read(app) }
        val (text, quiet) = told("reading").single()
        assertTrue(text, text.contains("PTP"))
        assertTrue("said again quietly", quiet)
    }

    private fun aBuilt() = com.jemcik.jemrec.calls.AfterCall.problem(
        app, UsbDefault.TITLE, "built before the lock",
        android.app.PendingIntent.getActivity(app, 0, android.content.Intent(), android.app.PendingIntent.FLAG_IMMUTABLE),
    ).build()

    /** Review round 4: a telling past its look - built, then the lock -
     *  posts nothing where, since, JemRec was switched off, a tap set it,
     *  or its words changed: each preference it decided on is read again
     *  under the lock. With nothing changed, it posts. */
    @Test fun aTellingPastItsLookReadsItsPreferencesAgain() {
        setUpAndOn()
        UsbDefault.keep(app, mtp)
        val words = UsbDefault.words(mtp, didNotStay = false)
        com.jemcik.jemrec.recorder.RecorderSwitch.set(app, false)
        UsbDefault.postLocked(app, mtp, words, null, aBuilt())
        assertEquals("switched off since", 0, told())
        com.jemcik.jemrec.recorder.RecorderSwitch.set(app, true)
        UsbDefault.noteSet(app)
        UsbDefault.postLocked(app, mtp, words, null, aBuilt())
        assertEquals("set by a tap since", 0, told())
        // Read back as File transfer: it did not stay, and the words now add
        // the way by hand.
        UsbDefault.keep(app, mtp)
        UsbDefault.postLocked(app, mtp, words, null, aBuilt())
        assertEquals("other words since", 0, told())
        UsbDefault.postLocked(app, mtp, UsbDefault.words(mtp, didNotStay = true), null, aBuilt())
        assertEquals("nothing changed since: said", 1, told())
    }

    /** Review round 5: told already by another thread since the look, or put
     *  away with Not now, a telling posts nothing - each read again under the
     *  lock. */
    @Test fun aTellingToldOrPutAwaySinceItsLookPostsNothing() {
        setUpAndOn()
        UsbDefault.keep(app, mtp)
        val words = UsbDefault.words(mtp, didNotStay = false)
        val notifications = app.getSystemService(NotificationManager::class.java)
        UsbDefault.postLocked(app, mtp, words, null, aBuilt())
        assertEquals(1, told())
        notifications.cancelAll()
        UsbDefault.postLocked(app, mtp, words, null, aBuilt())
        assertEquals("told since", 0, told())
        Prefs.of(app).edit().remove("usb_default_told_words").commit()
        UsbDefault.dismiss(app, mtp)
        UsbDefault.postLocked(app, mtp, words, null, aBuilt())
        assertEquals("put away", 0, told())
    }

    /** Review round 4: a look that finds it no longer at risk forgets Not
     *  now, as home's reading does - File transfer, Not now, then none,
     *  then File transfer again, with only keep-alive runs looking: said. */
    @Test fun aLookThatFindsItSafeForgetsNotNow() {
        setUpAndOn()
        UsbDefault.keep(app, mtp)
        UsbDefault.tellIfAtRisk(app)
        UsbDefault.dismiss(app, mtp)
        assertEquals(0, told())
        UsbDefault.keep(app, 0L)
        UsbDefault.tellIfAtRisk(app)
        UsbDefault.keep(app, mtp)
        UsbDefault.tellIfAtRisk(app)
        assertEquals("said again", 1, told())
    }

    /** Reviews, rounds 2 and 4: a tap whose recorder would not start says
     *  why in Start recorder's words, for every cause - no "try again in a
     *  moment" that no retry answers. */
    @Test fun aFailedStartIsSaidAsStartRecorderSaysIt() {
        (com.jemcik.jemrec.recorder.RecorderHealth.Problem.entries + null).forEach { cause ->
            assertEquals(com.jemcik.jemrec.ui.couldNotStart(cause), UsbDefault.said(UsbDefault.Outcome.NOT_STARTED, cause))
        }
        assertEquals(
            "could not start the recorder: Android has not allowed Wireless debugging on this Wi-Fi",
            UsbDefault.said(UsbDefault.Outcome.NOT_STARTED, com.jemcik.jemrec.recorder.RecorderHealth.Problem.NETWORK_NOT_ALLOWED),
        )
    }

    /** A swipe lock costs nothing: nothing said, and what was said goes. */
    @Test fun aLockThatIsNotSecureTakesItBack() {
        setUpAndOn()
        UsbDefault.keep(app, mtp)
        UsbDefault.tellIfAtRisk(app)
        assertEquals(1, told())
        secure(false)
        UsbDefault.tellIfAtRisk(app)
        assertEquals(0, told())
    }

    /** A reading that finds the lock no longer secure takes the
     *  notification back at once - not at the next keep-alive run. */
    @Test fun aReadingWithASwipeLockTakesTheNotificationBack() {
        setUpAndOn()
        UsbDefault.keep(app, mtp)
        UsbDefault.tellIfAtRisk(app)
        assertEquals(1, told())
        secure(false)
        assertEquals("the last answer, no recorder here", mtp, kotlinx.coroutines.runBlocking { UsbDefault.read(app) })
        assertEquals(0, told())
    }

    /** Rows 15 and 16: switched off, or not set up, nothing is said. */
    @Test fun offOrNotSetUpSaysNothing() {
        UsbDefault.keep(app, mtp)
        UsbDefault.tellIfAtRisk(app)
        assertEquals("not set up", 0, told())
        com.jemcik.jemrec.setup.Setup.markComplete(app)
        com.jemcik.jemrec.recorder.RecorderSwitch.set(app, false)
        UsbDefault.tellIfAtRisk(app)
        assertEquals("switched off", 0, told())
        com.jemcik.jemrec.recorder.RecorderSwitch.set(app, true)
        UsbDefault.tellIfAtRisk(app)
        assertEquals("on and set up", 1, told())
    }

    /** Put away with Not now for these functions, it is not posted. */
    @Test fun notNowKeepsTheNotificationAway() {
        setUpAndOn()
        UsbDefault.keep(app, mtp)
        UsbDefault.dismiss(app, mtp)
        UsbDefault.tellIfAtRisk(app)
        assertEquals(0, told())
    }

    /** Its button names JemRec's own activity and the set's link - no
     *  implicit intent another app could answer (CodeQL, PR #105). */
    @Test fun itsButtonOpensJemRecsOwnActivity() {
        setUpAndOn()
        UsbDefault.keep(app, mtp)
        UsbDefault.tellIfAtRisk(app)
        val posted = shadowOf(app.getSystemService(NotificationManager::class.java)).allNotifications
            .single { it.extras.getString("android.title") == UsbDefault.TITLE }
        val action = posted.actions.single()
        assertEquals(UsbDefault.ACTION, action.title.toString())
        val intent = shadowOf(action.actionIntent).savedIntent
        assertEquals(app.packageName, intent.component?.packageName)
        assertEquals("USB_DEFAULT_NONE", intent.getStringExtra("link"))
    }

    /** A set takes back what was said. */
    @Test fun aSetTakesTheNotificationBack() {
        setUpAndOn()
        UsbDefault.keep(app, mtp)
        UsbDefault.tellIfAtRisk(app)
        assertEquals(1, told())
        UsbDefault.noteSet(app)
        assertEquals(0, told())
    }

    /** Row 17: Start fresh forgets the answer, Not now and what was said. */
    @Test fun startFreshForgetsItAll() {
        setUpAndOn()
        UsbDefault.keep(app, mtp)
        UsbDefault.tellIfAtRisk(app)
        UsbDefault.dismiss(app, mtp)
        UsbDefault.forgetAll(app)
        assertNull(UsbDefault.stored(app))
        assertTrue(UsbDefault.notice(app, mtp, true))
    }
}
