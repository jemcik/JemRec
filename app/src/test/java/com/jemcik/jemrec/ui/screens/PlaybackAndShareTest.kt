package com.jemcik.jemrec.ui.screens

import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Looper
import com.google.common.util.concurrent.Futures
import com.jemcik.jemrec.recordings.Recording
import com.jemcik.jemrec.recordings.Recordings
import com.jemcik.jemrec.recordings.Waveforms
import com.jemcik.jemrec.ui.MainViewModel
import com.jemcik.jemrec.ui.UiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import java.util.Collections

/**
 * The player, Share and the log, as the view model runs them - pinned
 * before they move into holders of their own (the 1.0 restructuring). The
 * media session is never reached here (ScreenTest's never connects), so
 * these hold what the screen does before it answers, and when it cannot.
 */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36])
class PlaybackAndShareTest : ScreenTest() {

    private val app get() = RuntimeEnvironment.getApplication()

    private fun recording(stamp: String, caller: String) = Recording(
        name = "${stamp}_in.ogg",
        uri = Uri.parse("jemrec-test://$stamp"),
        whenLabel = "",
        incoming = true,
        durationLabel = "1:00",
        sizeLabel = "1.0 MB",
        caller = caller,
    )

    private val olivia = recording("20261021_160200", "Olivia Bennett")
    private val marcus = recording("20261021_152400", "Marcus Chen")

    private val heard: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private var listening: Job? = null

    /** Every snackbar [vm] asks for, from now on. */
    private fun listen(vm: MainViewModel) {
        listening = CoroutineScope(Dispatchers.Unconfined).launch { vm.notices.collect { heard += it } }
    }

    @Before fun inventedWaveforms() {
        Waveforms.override = { name -> Fixtures.waveform(name.hashCode().toLong()) }
    }

    @After fun noStandIns() {
        listening?.cancel()
        Recordings.override = null
        Waveforms.override = null
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_NORMAL
    }

    /** Android gives a player no sound during a call: Play says why
     *  instead of doing nothing, in a snackbar and the log. */
    @Test fun aRecordingDoesNotPlayDuringACall() {
        val vm = viewModel(UiState(recordings = listOf(olivia)))
        listen(vm)
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_CALL
        vm.play(olivia)
        assertNull(vm.state.value.playingName)
        assertEquals(listOf("A recording does not play during a call"), heard)
        assertTrue(vm.state.value.log.contains("] a recording does not play during a call"))
    }

    /** A pause is never refused: a voice app can hold the call's audio
     *  mode with a recording still playing. */
    @Test fun aPauseIsNotRefusedDuringACall() {
        val vm = viewModel(UiState(recordings = listOf(olivia), playingName = olivia.name, playing = true, durationMs = 60_000))
        listen(vm)
        app.getSystemService(AudioManager::class.java).mode = AudioManager.MODE_IN_COMMUNICATION
        vm.play(olivia)
        assertEquals(emptyList<String>(), heard)
        assertEquals(olivia.name, vm.state.value.playingName)
    }

    /** Another recording starts at its beginning, its length not known
     *  yet, with its own waveform once worked out. */
    @Test fun anotherRecordingStartsAtItsBeginningWithItsWaveform() {
        val vm = viewModel(
            UiState(
                recordings = listOf(olivia, marcus), playingName = marcus.name, playing = true,
                positionMs = 30_000, durationMs = 60_000, waveform = Fixtures.waveform(1),
            ),
        )
        vm.play(olivia)
        val s = vm.state.value
        assertEquals(olivia.name, s.playingName)
        assertFalse(s.playing)
        assertEquals(0, s.positionMs)
        assertEquals(0, s.durationMs)
        assertNull("the old waveform gone at once", s.waveform)
        settle(vm)
        assertEquals(Fixtures.waveform(olivia.name.hashCode().toLong()), vm.state.value.waveform)
    }

    /** A session that cannot be reached: said, and the row folds back. */
    @Test fun aSessionThatCannotBeReachedSaysSoAndStops() {
        val vm = viewModel(
            UiState(recordings = listOf(olivia)),
            connectSession = { Futures.immediateFailedFuture(IllegalStateException("no session")) },
        )
        listen(vm)
        vm.play(olivia)
        shadowOf(Looper.getMainLooper()).idle()
        settle(vm)
        assertNull(vm.state.value.playingName)
        assertEquals(listOf("Could not play that recording"), heard)
    }

    /** A seek moves the playhead at once, before the session says so; the
     *  chevron stops and folds the row back. */
    @Test fun aSeekMovesThePlayheadAndTheChevronStops() {
        val vm = viewModel(
            UiState(recordings = listOf(olivia), playingName = olivia.name, playing = true, positionMs = 1_000, durationMs = 60_000, waveform = Fixtures.waveform(2)),
        )
        vm.seekTo(42_000)
        assertEquals(42_000, vm.state.value.positionMs)
        vm.stopPlaying()
        val s = vm.state.value
        assertNull(s.playingName)
        assertFalse(s.playing)
        assertEquals(0, s.positionMs)
        assertEquals(0, s.durationMs)
        assertNull(s.waveform)
    }

    /** Share's Original: at once, the file as it is, to Android's chooser,
     *  readable by whoever it is shared with. */
    @Test fun theOriginalIsSharedAtOnceAsItIs() {
        val vm = viewModel(UiState(recordings = listOf(olivia)))
        vm.share(olivia)
        vm.shareOriginal(olivia)
        assertNull(vm.state.value.shareChoice)
        assertFalse(vm.state.value.converting)
        val chooser: Intent = shadowOf(app).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        assertTrue(chooser.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        @Suppress("DEPRECATION")
        val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
        assertEquals(Intent.ACTION_SEND, send.action)
        assertEquals("audio/ogg", send.type)
        @Suppress("DEPRECATION")
        assertEquals(olivia.uri, send.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        assertTrue(send.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    /** The .m4a, cancelled at once: no sheet, nothing said. */
    @Test fun aConversionCancelledLeavesNothing() {
        val vm = viewModel(UiState(recordings = listOf(olivia)))
        listen(vm)
        vm.share(olivia)
        vm.shareUniversal(olivia)
        assertNull(vm.state.value.shareChoice)
        assertTrue(vm.state.value.converting)
        vm.cancelConversion()
        assertFalse(vm.state.value.converting)
        settle(vm)
        assertNull(shadowOf(app).nextStartedActivity)
        assertEquals(emptyList<String>(), heard)
        assertEquals("", vm.state.value.log)
    }

    /** The log: newest first, each line stamped with its time in Latin
     *  digits, cut to its last 8,000 characters. */
    @Test fun theLogIsNewestFirstStampedAndCapped() {
        Recordings.override = { emptyList() }
        val vm = viewModel()
        vm.onDeleteConsentResult(false)
        settle(vm)
        vm.startPairingNotification()
        settle(vm)
        val log = vm.state.value.log
        val stamp = Regex("""^\[\d\d:\d\d:\d\d] check your notifications - you can type the code there\n\n\[\d\d:\d\d:\d\d] deletion cancelled\n\n$""")
        assertTrue(log, stamp.matches(log))

        repeat(150) {
            vm.startPairingNotification()
            settle(vm)
        }
        assertEquals(8_000, vm.state.value.log.length)
        assertTrue(vm.state.value.log.startsWith("["))
    }

    /** A failure of something done just now goes in a snackbar too, its
     *  first letter a capital; the log keeps it as said. */
    /** The answering machine's words are on the screen too, not only in
     *  Diagnostics' log: "could not arm", "no test call came" and "a call
     *  in the next three minutes may be answered with your greeting" were
     *  in the log alone. */
    @Test fun anAnsweringMachineMessageIsASnackbarToo() {
        Recordings.override = { emptyList() }
        val vm = viewModel()
        listen(vm)
        vm.answer.startTest(incoming = true)
        awaitMain("a word") { heard.isNotEmpty() }
        assertEquals(listOf("The recorder could not arm a test call"), heard.toList())
        assertTrue(vm.state.value.log, vm.state.value.log.contains("] the recorder could not arm a test call"))
    }

    @Test fun aFailureJustNowIsASnackbarToo() {
        Recordings.override = { emptyList() }
        val vm = viewModel()
        listen(vm)
        vm.addRuleContact(Uri.parse("content://com.android.contacts/contacts/404"))
        settle(vm)
        assertEquals(listOf("Could not add that contact"), heard)
        assertTrue(vm.state.value.log, vm.state.value.log.contains("] could not add that contact"))
    }
}
