package com.jemcik.jemrec.ui.screens

import android.content.pm.PackageManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.jemcik.jemrec.ui.LocalNow
import com.jemcik.jemrec.ui.theme.JemRecTheme
import com.jemcik.jemrec.ui.theme.LookChoice
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.hasScrollToIndexAction
import com.jemcik.jemrec.calls.CallLogLookup
import com.jemcik.jemrec.recordings.Recording
import com.jemcik.jemrec.recordings.Recordings
import com.jemcik.jemrec.ui.AppScaffold
import com.jemcik.jemrec.ui.CallsNarrowing
import com.jemcik.jemrec.ui.HomeTab
import com.jemcik.jemrec.ui.MainViewModel
import com.jemcik.jemrec.ui.RecordingsNarrowing
import com.jemcik.jemrec.ui.Screen
import com.jemcik.jemrec.ui.UiState
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * The lists' and Settings' parts that only a drawn screen shows: the four
 * Allow buttons for the call log, the narrowing line over an empty list,
 * where the search field's focus goes, and the endless list's next page.
 * Pinned before the composables are reworked (the 1.0 restructuring).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [36], qualifiers = ScreenTest.PHONE)
class ListScreensTest : ScreenTest() {

    private val app get() = RuntimeEnvironment.getApplication()

    @After fun noStandIns() {
        Recordings.override = null
    }

    /**
     * [state]'s Allow, tapped: Android is asked for the call log and the
     * contacts; then refused, and the refusal counts as the answer - the
     * offer is not made again.
     */
    private fun allowAsksForTheCallLog(state: UiState, scrollTo: Boolean = false) {
        Recordings.override = { emptyList() }
        val vm = viewModel(state)
        show { AppScaffold(state, vm) }
        val allow = compose.onNodeWithText("Allow")
        if (scrollTo) allow.performScrollTo()
        allow.performClick()
        compose.waitForIdle()
        val asked = shadowOf(compose.activity).lastRequestedPermission
        assertArrayEquals(CallLogLookup.PERMISSIONS, asked.requestedPermissions)
        compose.activity.onRequestPermissionsResult(
            asked.requestCode,
            asked.requestedPermissions,
            IntArray(asked.requestedPermissions.size) { PackageManager.PERMISSION_DENIED },
        )
        compose.waitForIdle()
        assertFalse(vm.state.value.offerCallLog)
        assertTrue(CallLogLookup.bannerDismissed(app))
        settle(vm)
    }

    @Test fun theRecordingsOfferAsksForTheCallLog() =
        allowAsksForTheCallLog(Fixtures.ready().copy(offerCallLog = true, canReadCallLog = false))

    @Test fun theCallsTabAsksForTheCallLog() =
        allowAsksForTheCallLog(Fixtures.ready().copy(homeTab = HomeTab.CALLS, canReadCallLog = false))

    @Test fun settingsAsksForTheCallLog() =
        allowAsksForTheCallLog(Fixtures.ready().copy(screen = Screen.SETTINGS, canReadCallLog = false))

    @Test fun whoseCallsAsksForTheCallLog() =
        allowAsksForTheCallLog(Fixtures.ready().copy(screen = Screen.WHOSE_CALLS, canReadCallLog = false), scrollTo = true)

    /** With no recordings at all, a chip still on is not said: "No
     *  recordings yet" says it all, as the Calls tab's empty log does. It
     *  said "Kept · 0 of 0", with Show all, until the author's word
     *  (2026-11-08). */
    @Test fun overNoRecordingsNoNarrowingIsSaid() {
        val recordings = Fixtures.ready().copy(recordings = emptyList(), recordingsNarrowing = RecordingsNarrowing(kept = true))
        show { AppScaffold(recordings, viewModel(recordings)) }
        compose.onNodeWithText("No recordings yet").assertExists()
        compose.onNodeWithText("Kept · 0 of 0").assertDoesNotExist()
        compose.onNodeWithText("Show all").assertDoesNotExist()
    }

    /** The Calls tab, its log empty, has no narrowing line either. */
    @Test fun overAnEmptyCallLogNoNarrowingIsSaid() {
        val calls = Fixtures.ready().copy(homeTab = HomeTab.CALLS, canReadCallLog = true, calls = emptyList(), callsNarrowing = CallsNarrowing(kept = true))
        show { AppScaffold(calls, viewModel(calls)) }
        compose.onNodeWithText("No calls in the call log yet.").assertExists()
        compose.onNodeWithText("Show all").assertDoesNotExist()
    }

    /** Search opened: the field takes the focus, keyboard and all. Then
     *  selection takes it away again. */
    @Test fun openingSearchFocusesTheFieldAndSelectingDropsIt() {
        val vm = viewModel(Fixtures.ready())
        showApp(vm)
        compose.onNodeWithContentDescription("Search recordings").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        vm.startSelecting()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    /** On a phone on its side the field sits in the panel, which takes the
     *  focus as it opens, not the list's own. */
    @Test
    @Config(qualifiers = "w800dp-h360dp-hdpi")
    fun inThePanelTheFieldIsFocusedAsItOpens() {
        val vm = viewModel(Fixtures.ready())
        showApp(vm)
        compose.onNodeWithText("Search").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    /**
     * Near the end of what is drawn, twenty more come - and only there. The
     * first frame, before the list is measured, read as near its end: every
     * time the tab was shown anew another page came, and select-all then
     * picked rows nobody had scrolled to.
     */
    @Test fun theListGrowsByAPageNearItsEndOnly() {
        val many = List(65) { i -> Fixtures.recordings()[0].copy(name = "20261021_${"%06d".format(100000 + i)}_in.ogg") }
        Recordings.override = { many }
        val vm = viewModel(Fixtures.ready().copy(recordings = many))
        showApp(vm)
        assertEquals("the first page only", 20, vm.state.value.visibleCount)
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(21)
        compose.waitForIdle()
        assertEquals(40, vm.state.value.visibleCount)

        // Away to the Calls tab and back: the list starts at its top again,
        // and adds nothing.
        vm.showTab(HomeTab.CALLS)
        settle(vm)
        compose.waitForIdle()
        vm.showTab(HomeTab.RECORDINGS)
        settle(vm)
        compose.waitForIdle()
        assertEquals(40, vm.state.value.visibleCount)
    }

    /** Opened, then read: the list draws its first page and no more. Laid
     *  out first with the throwaway place it has until it is read
     *  (MainScreen), "near its end" was read from that empty list and kept,
     *  and a page more came at every turn till the whole list was drawn -
     *  select-all then took it all (review, 2026-11-18). */
    @Test fun aListReadAfterTheWindowOpensDrawsItsFirstPageOnly() {
        val many = List(65) { i -> Fixtures.recordings()[0].copy(name = "20261021_${"%06d".format(100000 + i)}_in.ogg") }
        Recordings.override = { many }
        val vm = viewModel(Fixtures.ready().copy(recordings = emptyList(), listUnread = true))
        showApp(vm)
        compose.waitForIdle()
        vm.refresh()
        settle(vm)
        compose.waitForIdle()
        assertEquals(false, vm.state.value.listUnread)
        assertEquals(65, vm.state.value.recordings.size)
        assertEquals("the first page only", 20, vm.state.value.visibleCount)
    }

    /**
     * Turned so the header goes beside the list, or back, the list stays
     * where it was scrolled to - turning the phone remakes the screen, its
     * places restored from what it saved. The list was drawn from two
     * branches, upright and on its side, and each kept a place of its own:
     * turned, it came back at its top.
     */
    @Test fun turningThePhoneKeepsTheListWhereItWas() {
        val many = List(40) { i -> Fixtures.recordings()[0].copy(name = "20261021_${"%06d".format(100000 + i)}_in.ogg") }
        val state = Fixtures.ready().copy(recordings = many, visibleCount = 40)
        val vm = viewModel(state)
        // The phone's window, upright: 360 x 800 dp at 1.5x.
        var window by mutableStateOf(IntSize(540, 1200))
        val restoring = StateRestorationTester(compose)
        restoring.setContent {
            val real = LocalWindowInfo.current
            val turned = remember {
                object : WindowInfo {
                    override val isWindowFocused get() = real.isWindowFocused
                    override val containerSize get() = window
                }
            }
            CompositionLocalProvider(LocalNow provides { Fixtures.NOW }, LocalWindowInfo provides turned) {
                JemRecTheme(LookChoice()) {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                        AppScaffold(state, vm)
                    }
                }
            }
        }
        fun scrolled() = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(20)
        compose.waitForIdle()
        assertTrue("scrolled", scrolled() > 0f)

        // On its side: the screen remade, the header beside the list.
        window = IntSize(1200, 540)
        restoring.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertTrue("the panel is there", compose.onAllNodesWithText("Search").fetchSemanticsNodes().isNotEmpty())
        assertTrue("still scrolled on its side", scrolled() > 0f)

        // And upright again.
        window = IntSize(540, 1200)
        restoring.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        assertTrue("still scrolled upright", scrolled() > 0f)
    }

    /**
     * After the process ended in the background: the screen remade from what
     * it saved, its list not read for a moment - nothing in it - and then
     * read. The list is where it was scrolled to. Laid out over the empty
     * list first, its place was cut to that list's end, and it came back at
     * its top (review, 2026-11-18).
     */
    @Test fun theListComesBackWhereItWasAfterTheProcessEnded() {
        val many = List(40) { i -> Fixtures.recordings()[0].copy(name = "20261021_${"%06d".format(100000 + i)}_in.ogg") }
        val read = Fixtures.ready().copy(recordings = many, visibleCount = 40)
        var state by mutableStateOf(read)
        val vm = viewModel(read)
        val restoring = StateRestorationTester(compose)
        restoring.setContent {
            CompositionLocalProvider(LocalNow provides { Fixtures.NOW }) {
                JemRecTheme(LookChoice()) {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                        AppScaffold(state, vm)
                    }
                }
            }
        }
        fun scrolled() = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(20)
        compose.waitForIdle()
        assertTrue("scrolled", scrolled() > 0f)

        // The process ended: a view model made new, its list not read yet.
        state = read.copy(recordings = emptyList(), listUnread = true)
        restoring.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        // Its first reading lands.
        state = read
        compose.waitForIdle()
        assertTrue("back where it was", scrolled() > 0f)
    }

    /** Settings brought back after the process ended is where it was
     *  scrolled to, though setup's checking card came first: laid out over
     *  that card, Settings' place was cut to its height (review, 2026-11-18). */
    @Test fun settingsComesBackWhereItWasThoughSetupCameFirst() {
        Recordings.override = { emptyList() }
        val read = Fixtures.ready().copy(screen = Screen.SETTINGS)
        var state by mutableStateOf(read)
        val vm = viewModel(read)
        val restoring = StateRestorationTester(compose)
        restoring.setContent {
            CompositionLocalProvider(LocalNow provides { Fixtures.NOW }) {
                JemRecTheme(LookChoice()) {
                    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                        AppScaffold(state, vm)
                    }
                }
            }
        }
        val scrollable = androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        fun scrolled() = compose.onAllNodes(scrollable).fetchSemanticsNodes().maxOf { it.config[SemanticsProperties.VerticalScrollAxisRange].value() }
        compose.onNodeWithText("About").performScrollTo()
        compose.waitForIdle()
        assertTrue("scrolled", scrolled() > 0f)

        // The process ended: setup checks first, then Settings is back.
        state = read.copy(step = com.jemcik.jemrec.setup.SetupStep.CHECKING)
        restoring.emulateSavedInstanceStateRestore()
        compose.waitForIdle()
        state = read
        compose.waitForIdle()
        assertTrue("back where it was", scrolled() > 0f)
    }

    /** Seventy recordings a minute apart from 17:59, forty on the day the
     *  screens show and thirty the day before, each with a caller of its own. */
    private fun twoDays(): List<Recording> = List(70) { i ->
        val day = if (i < 40) "20261021" else "20261020"
        val at = 17 * 3600 + 59 * 60 - (i % 40) * 60
        val stamp = "%02d%02d%02d".format(at / 3600, at / 60 % 60, at % 60)
        Fixtures.recordings()[1].copy(name = "${day}_${stamp}_out.ogg", caller = "Caller $i")
    }

    /** [many]'s row [at] played from its notification, and the list's
     *  frames turned - the main looper alone does not draw them - until the
     *  link is let go. */
    private fun playFromItsNotification(vm: MainViewModel, many: List<Recording>, at: Int) {
        vm.open(com.jemcik.jemrec.DeepLink.Link(com.jemcik.jemrec.DeepLink.Kind.PLAY, many[at].name))
        awaitMain("playing $at") { vm.state.value.playingName == many[at].name }
        val until = System.currentTimeMillis() + 10_000
        while (vm.state.value.reveal != null) {
            // The list's clock too: the row is followed for a few seconds
            // as its player opens.
            compose.mainClock.advanceTimeBy(250)
            compose.waitForIdle()
            check(System.currentTimeMillis() < until) { "row $at never let go" }
        }
        compose.waitForIdle()
    }

    /** Where a node ends, whatever is cut off: its bounds in the root are
     *  clipped to what shows, and a node past the list's edge ended there. */
    private fun androidx.compose.ui.semantics.SemanticsNode.unclippedBottom() = positionInRoot.y + size.height

    private fun listScrolled() = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode()
        .config[SemanticsProperties.VerticalScrollAxisRange].value()

    /**
     * A notification's Play, with the list left scrolled down: its row is
     * brought into view, below the pinned header - the recording played with
     * its player off the screen - with its day's heading when it is the
     * first of its day; one past the list's window too, which had no row
     * drawn at all. A row already in view is left where it is.
     */
    @Test fun aNotificationsPlayBringsItsRowIntoView() {
        val many = twoDays()
        Recordings.override = { many }
        com.jemcik.jemrec.recordings.Waveforms.override = { name -> Fixtures.waveform(name.hashCode().toLong()) }
        // No call-log offer over the list, which a reading would bring.
        com.jemcik.jemrec.calls.CallLogLookup.dismissBanner(app)
        try {
            val vm = viewModel(Fixtures.ready().copy(recordings = many, offerCallLog = false, selectHintSeen = true))
            showApp(vm)
            // The header's bottom: where the first day's heading starts, at
            // the top - its node starts inside Memo's 16 dp above it.
            val todayTop = compose.onAllNodesWithText("Today")[0].fetchSemanticsNode().boundsInRoot.top
            val headerBottom = todayTop - with(compose.density) { 16.dp.toPx() }
            // In view already: no scroll.
            playFromItsNotification(vm, many, 1)
            assertEquals(0f, listScrolled())

            fun shows(at: Int) {
                val row = compose.onAllNodesWithText("Caller $at")[0].assertIsDisplayed().fetchSemanticsNode().boundsInRoot
                assertTrue("row $at at ${row.top}, under the header to $headerBottom", row.top >= headerBottom - 0.5f)
            }
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(15)
            compose.waitForIdle()
            assertTrue(compose.onAllNodesWithText("Caller 0").fetchSemanticsNodes().isEmpty())
            playFromItsNotification(vm, many, 0)
            shows(0)
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(15)
            compose.waitForIdle()
            playFromItsNotification(vm, many, 12)
            shows(12)
            // The first of the day before, under its heading - past the rows
            // drawn, forty once the list was scrolled.
            assertTrue(vm.state.value.visibleCount <= 40)
            playFromItsNotification(vm, many, 40)
            shows(40)
            val heading = compose.onAllNodesWithText("Yesterday")[0].fetchSemanticsNode().boundsInRoot
            assertTrue("its heading shown, at ${heading.top}, below the header as Today's at the top", heading.top >= todayTop - 0.5f)
            playFromItsNotification(vm, many, 65)
            shows(65)
        } finally {
            com.jemcik.jemrec.recordings.Waveforms.override = null
        }
    }

    /** On a phone on its side, the call-log offer and the tip above it: the
     *  newest recording's Play brings its row into view. Taken to the list's
     *  top, the row was under the offer, the tip and its heading, below the
     *  screen's edge. */
    @Test
    @Config(qualifiers = "w800dp-h360dp-hdpi")
    fun theNewestsPlayBringsItsRowIntoViewUnderTheOfferAndTheTip() {
        val many = twoDays()
        Recordings.override = { many }
        com.jemcik.jemrec.recordings.Waveforms.override = { name -> Fixtures.waveform(name.hashCode().toLong()) }
        try {
            val vm = viewModel(Fixtures.ready().copy(recordings = many, offerCallLog = true, selectHintSeen = false))
            showApp(vm)
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(15)
            compose.waitForIdle()
            playFromItsNotification(vm, many, 0)
            val list = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
            val row = compose.onAllNodesWithText("Caller 0")[0].assertIsDisplayed().fetchSemanticsNode().unclippedBottom()
            assertTrue("row 0 to $row, the list to ${list.bottom}", row <= list.bottom + 0.5f)
        } finally {
            com.jemcik.jemrec.recordings.Waveforms.override = null
        }
    }

    /** A link to a row that is not drawn - gone before the list drew it - is
     *  let go: kept, any later change to the list jumped to it. */
    @Test fun aLinkToARowNotDrawnIsLetGo() {
        val many = twoDays()
        Recordings.override = { many }
        val vm = viewModel(Fixtures.ready().copy(recordings = many, reveal = "20261019_101010_in.ogg"))
        showApp(vm)
        val until = System.currentTimeMillis() + 10_000
        while (vm.state.value.reveal != null) {
            compose.mainClock.advanceTimeBy(500)
            compose.waitForIdle()
            check(System.currentTimeMillis() < until) { "never let go" }
        }
        assertEquals(0f, listScrolled())
    }

    /** A row at the screen's foot, played from its notification: as its
     *  player opens - once the session knows the recording's length - the
     *  row is kept in view. Measured before, it grew past the edge, its
     *  controls below it. */
    @Test fun aRowWhosePlayerOpensAtTheFootIsKeptInView() {
        val many = twoDays()
        Recordings.override = { many }
        com.jemcik.jemrec.recordings.Waveforms.override = { name -> Fixtures.waveform(name.hashCode().toLong()) }
        com.jemcik.jemrec.calls.CallLogLookup.dismissBanner(app)
        // A session that plays what it is given, a minute long.
        val session = org.mockito.Mockito.mock(androidx.media3.session.MediaController::class.java)
        val given = java.util.concurrent.atomic.AtomicReference<androidx.media3.common.MediaItem?>()
        org.mockito.Mockito.doAnswer { given.set(it.getArgument(0)); null }
            .`when`(session).setMediaItem(org.mockito.Mockito.any())
        org.mockito.Mockito.`when`(session.currentMediaItem).thenAnswer { given.get() }
        org.mockito.Mockito.`when`(session.duration).thenReturn(60_000L)
        org.mockito.Mockito.`when`(session.isPlaying).thenReturn(true)
        // As a session gives them: never null, the speaker's (SessionPlayer).
        org.mockito.Mockito.`when`(session.audioAttributes).thenReturn(androidx.media3.common.AudioAttributes.DEFAULT)
        // Reached once the row has been measured, as a session is, a moment on.
        val connecting = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()
        try {
            val vm = viewModel(
                Fixtures.ready().copy(recordings = many, offerCallLog = false, selectHintSeen = true),
                connectSession = { connecting },
            )
            showApp(vm)
            val list = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
            // The last row reaching into view - cut at the edge, or whole.
            val last = (0 until 20).last { i ->
                compose.onAllNodesWithText("Caller $i").fetchSemanticsNodes().firstOrNull()
                    ?.boundsInRoot?.let { it.bottom <= list.bottom } == true
            }
            // The one above it: whole, and in view as it plays, but not
            // with its player.
            val foot = last - 1
            vm.open(com.jemcik.jemrec.DeepLink.Link(com.jemcik.jemrec.DeepLink.Kind.PLAY, many[foot].name))
            awaitMain("playing") { vm.state.value.playingName == many[foot].name }
            compose.waitForIdle()
            assertEquals("in view, not moved", 0f, listScrolled())
            connecting.set(session)
            val until = System.currentTimeMillis() + 10_000
            while (vm.state.value.reveal != null) {
                compose.mainClock.advanceTimeBy(250)
                compose.waitForIdle()
                check(System.currentTimeMillis() < until) { "never let go" }
            }
            compose.waitForIdle()
            assertTrue("its player open", vm.state.value.durationMs > 0)
            // The player's foot: its position in the recording, under the row.
            val seeker = compose.onAllNodesWithContentDescription("Position in the recording")[0].fetchSemanticsNode().unclippedBottom()
            assertTrue("its player to $seeker, the list to ${list.bottom}", seeker <= list.bottom + 0.5f)
        } finally {
            com.jemcik.jemrec.recordings.Waveforms.override = null
        }
    }

    /** A link's row asked into view while setup shows instead of the list
     *  is let go: kept, the list jumped to it whenever it next showed. */
    @Test fun aLinksRowIsLetGoWhileSetupShows() {
        Recordings.override = { emptyList() }
        val vm = viewModel(Fixtures.ready().copy(step = com.jemcik.jemrec.setup.SetupStep.NEEDS_PAIRING, reveal = "20261021_160200_in.ogg"))
        showApp(vm)
        compose.waitForIdle()
        assertEquals(null, vm.state.value.reveal)
    }

    /** The user's own scroll ends a link's follow: from then the row is
     *  theirs to place. Followed on, the list scrolled back against them as
     *  the player opened. */
    @Test fun aScrollEndsTheFollow() {
        val many = twoDays()
        Recordings.override = { many }
        com.jemcik.jemrec.recordings.Waveforms.override = { name -> Fixtures.waveform(name.hashCode().toLong()) }
        com.jemcik.jemrec.calls.CallLogLookup.dismissBanner(app)
        val session = org.mockito.Mockito.mock(androidx.media3.session.MediaController::class.java)
        val given = java.util.concurrent.atomic.AtomicReference<androidx.media3.common.MediaItem?>()
        org.mockito.Mockito.doAnswer { given.set(it.getArgument(0)); null }
            .`when`(session).setMediaItem(org.mockito.Mockito.any())
        org.mockito.Mockito.`when`(session.currentMediaItem).thenAnswer { given.get() }
        org.mockito.Mockito.`when`(session.duration).thenReturn(60_000L)
        org.mockito.Mockito.`when`(session.isPlaying).thenReturn(true)
        // As a session gives them: never null, the speaker's (SessionPlayer).
        org.mockito.Mockito.`when`(session.audioAttributes).thenReturn(androidx.media3.common.AudioAttributes.DEFAULT)
        val connecting = com.google.common.util.concurrent.SettableFuture.create<androidx.media3.session.MediaController>()
        try {
            val vm = viewModel(
                Fixtures.ready().copy(recordings = many, offerCallLog = false, selectHintSeen = true),
                connectSession = { connecting },
            )
            showApp(vm)
            compose.onNode(hasScrollToIndexAction()).performScrollToIndex(3)
            compose.waitForIdle()
            val list = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode().boundsInRoot
            val last = (0 until 30).last { i ->
                compose.onAllNodesWithText("Caller $i").fetchSemanticsNodes().firstOrNull()
                    ?.boundsInRoot?.let { it.bottom <= list.bottom } == true
            }
            val foot = last - 1
            vm.open(com.jemcik.jemrec.DeepLink.Link(com.jemcik.jemrec.DeepLink.Kind.PLAY, many[foot].name))
            awaitMain("playing") { vm.state.value.playingName == many[foot].name }
            compose.waitForIdle()
            val before = listScrolled()
            // The user drags the list a little toward its start: the row lower.
            compose.onNode(hasScrollToIndexAction()).performTouchInput {
                swipeDown(startY = centerY - 30f, endY = centerY + 30f, durationMillis = 1_000)
            }
            compose.waitForIdle()
            val theirs = listScrolled()
            assertTrue("dragged from $before to $theirs", theirs < before)
            connecting.set(session)
            val until = System.currentTimeMillis() + 10_000
            while (vm.state.value.reveal != null || System.currentTimeMillis() < until - 8_000) {
                compose.mainClock.advanceTimeBy(250)
                compose.waitForIdle()
                check(System.currentTimeMillis() < until) { "never let go" }
            }
            compose.mainClock.advanceTimeBy(3_000)
            compose.waitForIdle()
            assertTrue("its player open", vm.state.value.durationMs > 0)
            assertEquals("left where the user put it", theirs, listScrolled())
        } finally {
            com.jemcik.jemrec.recordings.Waveforms.override = null
        }
    }

    /** On the Calls tab too, the search takes the focus as it opens. */
    @Test fun theCallsSearchIsFocusedAsItOpens() {
        val vm = viewModel(Fixtures.ready().copy(homeTab = HomeTab.CALLS, calls = Fixtures.calls()))
        showApp(vm)
        compose.onNodeWithContentDescription("Search calls").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsFocused()
    }

    /** Selecting, a screen reader hears which rows are picked, what a tap
     *  does to each, and the select-all box's name - not "Select" on every
     *  row, picked or not. */
    @Test fun aScreenReaderHearsWhichRowsArePicked() {
        val recordings = Fixtures.recordings()
        Recordings.override = { recordings }
        val first = recordings.first().name
        val state = Fixtures.ready().copy(recordings = recordings, selecting = true, selected = setOf(first))
        val vm = viewModel(state)
        show { AppScaffold(state, vm) }
        compose.onAllNodes(androidx.compose.ui.test.SemanticsMatcher.expectValue(
            androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Selected")).assertCountEquals(1)
        // That row picked as Android means it (the tab picked is the other).
        compose.onNode(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.StateDescription, "Selected") and
                androidx.compose.ui.test.SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.Selected, true),
        ).assertExists()
        compose.onNodeWithContentDescription("Select all").assertExists()
        // The photos select, as checkboxes with a state: the picked row's
        // checked, each other row's not - the select-all box, a checkbox too,
        // is neither while some are picked. Until the review of 2026-11-18
        // the photos had the role and no state, and this counted the
        // select-all box alone.
        fun photos(state: androidx.compose.ui.state.ToggleableState) = compose.onAllNodes(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.Role, androidx.compose.ui.semantics.Role.Checkbox,
            ) and androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.ToggleableState, state,
            ) and androidx.compose.ui.test.hasContentDescription("Select all").not(),
        ).fetchSemanticsNodes().size
        assertEquals(1, photos(androidx.compose.ui.state.ToggleableState.On))
        assertTrue("the other photos, unchecked", photos(androidx.compose.ui.state.ToggleableState.Off) >= 2)
        // Each named, as a checkbox must be: the picked row's by its caller.
        val picked = recordings.first()
        compose.onNode(
            androidx.compose.ui.test.SemanticsMatcher.expectValue(
                androidx.compose.ui.semantics.SemanticsProperties.ToggleableState, androidx.compose.ui.state.ToggleableState.On,
            ) and androidx.compose.ui.test.hasContentDescription(picked.caller ?: "Unknown caller"),
        ).assertExists()
    }

    /** Opened, its list not read yet - at every start, and after the
     *  process ended with rows picked: home does not say the list is empty,
     *  nor offer to delete what it has not read (review, 2026-11-18). */
    @Test fun aListNotReadYetIsNotSaidToBeEmpty() {
        Recordings.override = { emptyList() }
        val unread = Fixtures.ready().copy(recordings = emptyList(), listUnread = true, selecting = true, selected = setOf("a.ogg"))
        var state by mutableStateOf(unread)
        val vm = viewModel(unread)
        show { AppScaffold(state, vm) }
        compose.onAllNodesWithText("No recordings yet").assertCountEquals(0)
        compose.onAllNodesWithText("match", substring = true).assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Delete selected").assertCountEquals(0)
        // Nor select-all: tapped over the empty list, it let the selection
        // brought back go.
        compose.onNodeWithContentDescription("Select all").assertIsNotEnabled()
        // Read, and empty: said so.
        state = unread.copy(listUnread = false, selecting = false, selected = emptySet())
        compose.waitForIdle()
        compose.onNodeWithText("No recordings yet").assertExists()
    }

    /** The header's own tap does as the notice's Turn on does: Android's
     *  question first, where the permission is what is missing - one rule
     *  for one thing (review, 2026-11-18). */
    @Test fun theHeadersTapAsksForTheNotificationPermissionToo() {
        Recordings.override = { emptyList() }
        val state = Fixtures.ready().copy(automatic = false, notificationsOff = com.jemcik.jemrec.calls.NotificationHealth.Off.ALL)
        val vm = viewModel(state)
        show { AppScaffold(state, vm) }
        compose.onNodeWithText("Tap to turn on notifications").performClick()
        compose.waitForIdle()
        val asked = shadowOf(compose.activity).lastRequestedPermission
        assertTrue(android.Manifest.permission.POST_NOTIFICATIONS in asked.requestedPermissions)
    }

    /** The header's Battery Restricted line and the card's Open App info
     *  both open App info, where the setting is - not another screen
     *  (review, 2026-11-18). */
    @Test fun theBatteryWaysOutOpenAppInfo() {
        Recordings.override = { emptyList() }
        val state = Fixtures.ready().copy(automatic = false, batteryRestricted = true, callTracking = false, batteryNotice = true)
        val vm = viewModel(state)
        show { AppScaffold(state, vm) }
        val started = org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication())
        // JemRec's own App info, by its package (review, 2026-11-18).
        val own = "package:" + org.robolectric.RuntimeEnvironment.getApplication().packageName
        compose.onNodeWithText("Battery Restricted · tap to allow").performClick()
        compose.waitForIdle()
        started.nextStartedActivity.let {
            assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, it.action)
            assertEquals(own, it.dataString)
        }
        compose.onNodeWithText("Open App info").performClick()
        compose.waitForIdle()
        started.nextStartedActivity.let {
            assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, it.action)
            assertEquals(own, it.dataString)
        }
    }

    /** Nothing read yet, the other screens wait blank rather than draw
     *  defaults they have not read: Settings, Recently deleted, the Calls
     *  tab - and the Calls tab says nothing of an empty call log until it
     *  has read it (review, 2026-11-18). */
    @Test fun screensNotReadYetSayNothing() {
        Recordings.override = { emptyList() }
        val unread = Fixtures.ready().copy(screen = Screen.SETTINGS, listUnread = true)
        var state by mutableStateOf(unread)
        val vm = viewModel(unread)
        show { AppScaffold(state, vm) }
        compose.onAllNodesWithText("Quality").assertCountEquals(0)
        state = unread.copy(screen = Screen.TRASH)
        compose.waitForIdle()
        compose.onAllNodesWithText("Nothing here", substring = true).assertCountEquals(0)
        // The calls not read: nothing of them. Whether the log may be read
        // is known from the view model's making: its card, where it may not.
        state = unread.copy(screen = Screen.HOME, homeTab = HomeTab.CALLS, canReadCallLog = true)
        compose.waitForIdle()
        compose.onAllNodesWithText("See every call here").assertCountEquals(0)
        compose.onAllNodesWithText("No calls in the call log yet.").assertCountEquals(0)
        state = unread.copy(screen = Screen.HOME, homeTab = HomeTab.CALLS, canReadCallLog = false)
        compose.waitForIdle()
        compose.onNodeWithText("See every call here").assertExists()
        // Read, but not the call log yet: no word of it being empty.
        state = unread.copy(screen = Screen.HOME, homeTab = HomeTab.CALLS, canReadCallLog = true, listUnread = false, callsUnread = true, calls = emptyList())
        compose.waitForIdle()
        compose.onAllNodesWithText("No calls in the call log yet.").assertCountEquals(0)
        state = state.copy(callsUnread = false)
        compose.waitForIdle()
        compose.onNodeWithText("No calls in the call log yet.").assertExists()
        state = unread.copy(listUnread = false)
        compose.waitForIdle()
        compose.onNodeWithText("Quality").performScrollTo().assertExists()
    }

    @Test fun turnOnAsksForTheNotificationPermission() {
        Recordings.override = { emptyList() }
        val state = Fixtures.ready().copy(notificationsOff = com.jemcik.jemrec.calls.NotificationHealth.Off.ALL)
        val vm = viewModel(state)
        show { AppScaffold(state, vm) }
        compose.onNodeWithText("Turn on").performClick()
        compose.waitForIdle()
        val asked = shadowOf(compose.activity).lastRequestedPermission
        assertTrue(android.Manifest.permission.POST_NOTIFICATIONS in asked.requestedPermissions)
    }

    /** Refused at once, Android no longer asking: the app's notification
     *  settings, where the switch is - not App info, a level above them
     *  (review, 2026-11-18). */
    @Test fun turnOnRefusedOpensTheNotificationSettings() {
        Recordings.override = { emptyList() }
        val state = Fixtures.ready().copy(notificationsOff = com.jemcik.jemrec.calls.NotificationHealth.Off.ALL)
        val vm = viewModel(state)
        show { AppScaffold(state, vm) }
        compose.onNodeWithText("Turn on").performClick()
        compose.waitForIdle()
        val asked = shadowOf(compose.activity).lastRequestedPermission
        compose.activity.onRequestPermissionsResult(
            asked.requestCode, arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED),
        )
        compose.waitForIdle()
        val started = org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        assertEquals(app.packageName, started.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
    }

    /** Ask first's question off, the permission granted: that channel's own
     *  settings, the question's switch. */
    @Test fun turnOnForAskFirstsQuestionOpensItsChannel() {
        Recordings.override = { emptyList() }
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val state = Fixtures.ready().copy(automatic = false, notificationsOff = com.jemcik.jemrec.calls.NotificationHealth.Off.ASK_FIRST)
        val vm = viewModel(state)
        show { AppScaffold(state, vm) }
        compose.onNodeWithText("Turn on").performClick()
        compose.waitForIdle()
        val started = org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, started.action)
        assertEquals(com.jemcik.jemrec.calls.RecordPrompt.CHANNEL_ID, started.getStringExtra(android.provider.Settings.EXTRA_CHANNEL_ID))
    }

    /** The question turned down, or shown briefly: said, with Not now -
     *  it still asks, in the shade - and Turn on opens that page. */
    @Test fun askFirstsQuestionTurnedDownOrBriefSaysSoAndOpensItsPage() {
        Recordings.override = { emptyList() }
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val quiet = Fixtures.ready().copy(automatic = false, notificationsOff = com.jemcik.jemrec.calls.NotificationHealth.Off.ASK_FIRST_QUIET)
        var state by mutableStateOf(quiet)
        val vm = viewModel(quiet)
        show { AppScaffold(state, vm) }
        compose.onNodeWithText("Ask first's question will not pop up").assertIsDisplayed()
        compose.onNodeWithText("Not now").assertIsDisplayed()
        // Asking still works: the header does not say it cannot record.
        compose.onAllNodesWithText("Tap to turn on notifications").assertCountEquals(0)
        val shadow = org.robolectric.Shadows.shadowOf(org.robolectric.RuntimeEnvironment.getApplication())
        compose.onNodeWithText("Turn on").performClick()
        compose.waitForIdle()
        assertEquals(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, shadow.nextStartedActivity.action)
        state = quiet.copy(notificationsOff = com.jemcik.jemrec.calls.NotificationHealth.Off.ASK_FIRST_BRIEF)
        compose.onNodeWithText("Ask first's question will not stay up").assertIsDisplayed()
        compose.onNodeWithText("Turn on").performClick()
        compose.waitForIdle()
        assertEquals(android.provider.Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, shadow.nextStartedActivity.action)
    }

    /** JemRec switched off: the Battery card is not shown, as its
     *  notification is not - nothing it records is held back (review,
     *  2026-11-18). */
    @Test fun theBatteryCardWaitsWhileJemRecIsOff() {
        Recordings.override = { emptyList() }
        val state = Fixtures.ready().copy(enabled = false, batteryRestricted = true, batteryNotice = true)
        val vm = viewModel(state)
        show { AppScaffold(state, vm) }
        compose.onAllNodesWithText(com.jemcik.jemrec.system.BatteryHealth.TITLE).assertCountEquals(0)
    }

    /** Android's call-log question closed under the user - a link opening
     *  JemRec's window finishes what is above it - is no answer: the offer
     *  stays. Taken for one, it went for good, the user never asked. */
    @Test fun aCallLogQuestionClosedUnderTheUserIsNoAnswer() {
        Recordings.override = { emptyList() }
        val state = Fixtures.ready().copy(offerCallLog = true)
        val vm = viewModel(state)
        show { AppScaffold(state, vm) }
        compose.onNodeWithText("Allow").performClick()
        compose.waitForIdle()
        val asked = shadowOf(compose.activity).lastRequestedPermission
        // Interrupted: nothing back, as Android hands it.
        compose.activity.onRequestPermissionsResult(asked.requestCode, emptyArray(), IntArray(0))
        compose.waitForIdle()
        assertTrue(vm.state.value.offerCallLog)
        assertFalse(com.jemcik.jemrec.calls.CallLogLookup.bannerDismissed(app))
    }

    /** Search open, its keyboard put away, and the call-log offer answered:
     *  the field is not focused again. Known by its place, the header was
     *  made anew as the offer went, and the keyboard came back. */
    @Test fun answeringTheOfferLeavesTheSearchAlone() {
        Recordings.override = { Fixtures.recordings() }
        val vm = viewModel(Fixtures.ready().copy(offerCallLog = true))
        lateinit var focus: androidx.compose.ui.focus.FocusManager
        show {
            focus = androidx.compose.ui.platform.LocalFocusManager.current
            val state by vm.state.collectAsState()
            AppScaffold(state, vm)
        }
        compose.onNodeWithContentDescription("Search recordings").performClick()
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertIsFocused()
        compose.runOnIdle { focus.clearFocus() }
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
        compose.onNodeWithText("Not now").performScrollTo().performClick()
        compose.waitForIdle()
        assertFalse(vm.state.value.offerCallLog)
        compose.onNode(hasSetTextAction()).assertIsNotFocused()
    }

    /** ...while another tab, and back, starts the list at its top, as it did. */
    @Test fun anotherTabAndBackStartsTheListAtItsTop() {
        val many = List(40) { i -> Fixtures.recordings()[0].copy(name = "20261021_${"%06d".format(100000 + i)}_in.ogg") }
        Recordings.override = { many }
        val vm = viewModel(Fixtures.ready().copy(recordings = many, visibleCount = 40))
        showApp(vm)
        fun scrolled() = compose.onNode(hasScrollToIndexAction()).fetchSemanticsNode()
            .config[SemanticsProperties.VerticalScrollAxisRange].value()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(20)
        compose.waitForIdle()
        assertTrue(scrolled() > 0f)
        vm.showTab(HomeTab.CALLS)
        settle(vm)
        compose.waitForIdle()
        vm.showTab(HomeTab.RECORDINGS)
        settle(vm)
        compose.waitForIdle()
        assertEquals(0f, scrolled())
    }
}
