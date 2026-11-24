package com.jemcik.jemrec.capture

import android.app.KeyguardManager
import android.app.NotificationManager
import androidx.work.ListenableWorker.Result
import androidx.work.testing.TestListenableWorkerBuilder
import com.jemcik.jemrec.recorder.RecorderSwitch
import com.jemcik.jemrec.system.UsbDefault
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
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

/**
 * Set to No data transfer and the Default USB configuration's reading
 * (UsbDefault) against a recorder that answers (FakeRecorder): what each
 * answer leaves - the state table's rows 7 to 11 in tmp/issue-131/PLAN.md -
 * and the USB-state watch queued only where the recorder writes.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class UsbDefaultTapTest {

    private val context = RuntimeEnvironment.getApplication()
    private lateinit var work: HeldWork
    private lateinit var recorder: FakeRecorder
    private val mtp = UsbDefault.FUNCTION_MTP

    @Before fun aPhoneSetUpWithTheRecorderUp() {
        TestPhone.switchesOn(context)
        TestPhone.offWifi(context)
        RecorderSwitch.set(context, true)
        TestPhone.setUp(context)
        work = HeldWork(context)
        recorder = FakeRecorder(context, FakeRecorder.bundledBuild(context))
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(true)
        UsbDefault.forgetAll(context)
    }

    @After fun closeAll() {
        recorder.close()
        work.close()
    }

    private fun asked(): List<Int> = recorder.heard.filter { it.key == "U" }.map { it.payload.single().toInt() }

    private fun watches() = work.unique(UsbStateWatch.NAME)

    /** Row 9: written after the answer - none kept, the data mode it
     *  replaced noted, and the watch queued to bring back a recorder the
     *  write ends. */
    @Test fun aSetIsWatched() {
        UsbDefault.keep(context, mtp)
        recorder.replies["U"] = "USB setting"
        assertEquals(UsbDefault.Outcome.SETTING, runBlocking { UsbDefault.setNone(context) })
        assertEquals(listOf(1), asked())
        assertEquals(0L, UsbDefault.stored(context))
        assertEquals(1, watches().size)
        UsbDefault.keep(context, mtp)
        assertTrue("read as File transfer again, it did not stay", UsbDefault.didNotStay(context, mtp))
    }

    /** Row 11: during a call or a fetch nothing is written, and nothing is
     *  watched. */
    @Test fun aSetDuringACallWritesNothing() {
        UsbDefault.keep(context, mtp)
        recorder.replies["U"] = "USB busy"
        assertEquals(UsbDefault.Outcome.BUSY, runBlocking { UsbDefault.setNone(context) })
        assertEquals(mtp, UsbDefault.stored(context))
        assertTrue(watches().isEmpty())
    }

    /** Row 7: a recorder before 1.7 hangs up on the command - no answer,
     *  nothing changed. */
    @Test fun anOldRecorderIsNoAnswer() {
        UsbDefault.keep(context, mtp)
        assertEquals(UsbDefault.Outcome.NO_ANSWER, runBlocking { UsbDefault.setNone(context) })
        assertEquals(listOf(1), asked())
        assertEquals(mtp, UsbDefault.stored(context))
        assertTrue(watches().isEmpty())
        assertEquals("the last answer, kept", mtp, runBlocking { UsbDefault.read(context) })
    }

    /** Reviews, rounds 2 and 4: the recorder down and its start failing -
     *  here for want of Wi-Fi - said with its cause, as Start recorder says
     *  it, not as a recorder that did not answer: no retry starts it. */
    @Test fun aTapWithTheRecorderDownOffWifiSaysWifi() {
        UsbDefault.keep(context, mtp)
        recorder.close()
        assertEquals(UsbDefault.Outcome.NOT_STARTED, runBlocking { UsbDefault.setNone(context) })
        assertEquals(
            "could not start the recorder: it needs Wi-Fi for a few seconds, or Start without Wi-Fi's port",
            UsbDefault.said(UsbDefault.Outcome.NOT_STARTED, com.jemcik.jemrec.recorder.RecorderHealth.cause(context)),
        )
        assertTrue("nothing asked of a recorder that is not there", asked().isEmpty())
        assertEquals(mtp, UsbDefault.stored(context))
        assertTrue(watches().isEmpty())
    }

    /** None already: nothing written, nothing watched, none kept. */
    @Test fun noneAlreadyIsKept() {
        UsbDefault.keep(context, mtp)
        recorder.replies["U"] = "USB default 0"
        assertEquals(UsbDefault.Outcome.NONE_ALREADY, runBlocking { UsbDefault.setNone(context) })
        assertEquals(0L, UsbDefault.stored(context))
        assertTrue(watches().isEmpty())
    }

    /** Row 13: the recorder could not ask Android - nothing changed. */
    @Test fun aRecorderThatCannotAskChangesNothing() {
        UsbDefault.keep(context, mtp)
        recorder.replies["U"] = "USB unreadable"
        assertEquals(UsbDefault.Outcome.UNREADABLE, runBlocking { UsbDefault.setNone(context) })
        assertEquals(mtp, UsbDefault.stored(context))
        assertTrue(watches().isEmpty())
        assertTrue("the card says the way by hand", UsbDefault.didNotStay(context, mtp))
    }

    /** A reading asks with a 0, and keeps the answer. */
    @Test fun aReadingAsksAndKeeps() {
        recorder.replies["U"] = "USB default 4"
        assertEquals(mtp, runBlocking { UsbDefault.read(context) })
        assertEquals(listOf(0), asked())
        assertArrayEquals(byteArrayOf(0), recorder.heard.single { it.key == "U" }.payload)
        assertEquals(mtp, UsbDefault.stored(context))
    }

    /** A keep-alive run with the recorder up asks it, and says it once in a
     *  notification - where JemRec learns of it while nobody opens it. */
    @Test fun aKeepAliveRunTellsIt() {
        recorder.replies["U"] = "USB default 4"
        val result = runBlocking { TestListenableWorkerBuilder<RecorderKeepAlive>(context).build().doWork() }
        assertEquals(Result.success(), result)
        assertEquals(mtp, UsbDefault.stored(context))
        val told = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
            .count { it.extras.getString("android.title") == UsbDefault.TITLE }
        assertEquals(1, told)
    }

    /** Set by hand in Developer options: the next reading takes the
     *  notification back, as it finds none. */
    @Test fun aReadingThatFindsItSetByHandTakesTheNotificationBack() {
        recorder.replies["U"] = "USB default 4"
        runBlocking { TestListenableWorkerBuilder<RecorderKeepAlive>(context).build().doWork() }
        val told = { shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
            .count { it.extras.getString("android.title") == UsbDefault.TITLE } }
        assertEquals(1, told())
        recorder.replies["U"] = "USB default 0"
        assertEquals(0L, runBlocking { UsbDefault.read(context) })
        assertEquals(0, told())
    }

    /** With a swipe lock, the same run says nothing. */
    @Test fun aKeepAliveRunWithASwipeLockSaysNothing() {
        shadowOf(context.getSystemService(KeyguardManager::class.java)).setIsDeviceSecure(false)
        recorder.replies["U"] = "USB default 4"
        runBlocking { TestListenableWorkerBuilder<RecorderKeepAlive>(context).build().doWork() }
        val told = shadowOf(context.getSystemService(NotificationManager::class.java)).allNotifications
            .count { it.extras.getString("android.title") == UsbDefault.TITLE }
        assertEquals(0, told)
        assertFalse(UsbDefault.notice(context, UsbDefault.stored(context), false))
    }
}
