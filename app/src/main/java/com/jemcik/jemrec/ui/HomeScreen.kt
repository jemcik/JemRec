package com.jemcik.jemrec.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.jemcik.jemrec.calls.NotificationHealth
import com.jemcik.jemrec.recordings.FileNames
import com.jemcik.jemrec.capture.AudioAccess
import com.jemcik.jemrec.recordings.Recording
import com.jemcik.jemrec.recordings.RecordingStore
import com.jemcik.jemrec.recordings.Trash
import com.jemcik.jemrec.ui.theme.JemRecColors
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The recordings, and nothing else.
 *
 * It used to open with a status card and a mode switch, so the list people came
 * to read started below the fold behind two things they had already read. State
 * and the on/off switch moved into the header, which does not scroll - whether
 * the app is recording your calls should not be something you scroll to find -
 * and the mode went to Settings, where a preference belongs.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: UiState,
    vm: MainViewModel,
    askForCallLog: () -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
    pickFolder: (android.net.Uri?) -> Unit = {},
    narrowingAside: Boolean = false,
) {
    val haptics = LocalHapticFeedback.current
    val focusManager = LocalFocusManager.current
    val narrowing = state.recordingsNarrowing
    val narrowed = narrowingOf(state, vm, calls = false)
    val searchFocus = rememberSearchFocus(narrowed.searchOpen, narrowingAside)
    // Entering selection blurs the field and drops the keyboard. The field
    // stays on screen as context - the query you searched by - but you are
    // picking rows now, not typing, so the keyboard should not be eating the
    // list, and the text cursor should not be left dangling under the field.
    LaunchedEffect(state.selecting) {
        if (state.selecting) focusManager.clearFocus()
    }
    if (state.confirmingDeleteSelected) {
        val count = state.selected.size
        AlertDialog(
            onDismissRequest = vm::cancelDeleteSelected,
            title = {
                Text(if (count == 1) "Delete this recording?" else "Delete $count recordings?")
            },
            text = {
                // The count again, in the sentence. A number in a title is
                // glanced at; a number in the sentence you are agreeing to is
                // read - and this is the last point at which it can be read.
                Text(
                    if (count == 1) {
                        "It goes to Recently deleted, in Settings, where it can be " +
                            "restored for ${Trash.DAYS} days."
                    } else {
                        "All $count of them go to Recently deleted, in Settings, where " +
                            "they can be restored for ${Trash.DAYS} days."
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { haptics.confirm(); vm.confirmDeleteSelected() },
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = vm::cancelDeleteSelected) { Text("Cancel") }
            },
        )
    }

    val audioPermission = rememberPermissionRequest { vm.onAudioAccessResult() }
    val turnOnNotifications = LocalTurnOnNotifications.current

    // Filtered, cut and grouped once per change of what they depend on - the
    // grouping also per day, which the headings read - not on every
    // recomposition: the player's position ticks recompose this screen three
    // times a second.
    val now = LocalNow.current
    val today = dayStamp(now())
    val filtered = remember(state.recordings, narrowing) { filterRecordings(state.recordings, narrowing) }
    val shown = remember(filtered, state.visibleCount) { filtered.take(state.visibleCount) }
    val days = remember(shown, today) { byDay(shown, now()) { it.startedAtMillis } }
    // A day's count is all of its recordings, not the ones the endless
    // scroll has drawn so far: counted from `shown`, Earlier read 15 beside a
    // tab saying 50, and grew as the list scrolled.
    val dayCounts = remember(filtered, today) { filtered.groupingBy { dayOf(it.startedAtMillis, now()) }.eachCount() }

    // Endless scroll instead of a "Show more" button. Everything is already in
    // memory - the list, the durations, the caller lookups - so a button gating
    // it was friction in front of data that was right there. As the last rows
    // come into view the window quietly widens; with nothing to fetch there is
    // no spinner, and it stops on its own once the whole filtered list is shown.
    // Not before the list is measured: with nothing laid out yet, "the last
    // row in view" read as row 0 of 0 - near the end - so every time the tab
    // was shown anew a page came, and select-all then took rows nobody had
    // scrolled to.
    // Keyed on the list's state: until the list is read it is a throwaway
    // one (MainScreen), and a reading kept from it said "near the end" of
    // the empty list for ever after - a page more at every turn, till the
    // whole list was drawn and select-all took it all (review, 2026-11-18).
    val nearEnd by remember(listState) {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: return@derivedStateOf false
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, filtered.size, state.visibleCount) {
        if (nearEnd && filtered.size > state.visibleCount) vm.showMore()
    }
    val scrolled by remember(listState) { derivedStateOf { listState.canScrollBackward } }

    // A notification's Play or Share: its row brought into view, below the
    // pinned header - from a list left scrolled down, the recording played
    // with its player off the screen - unless it is in view already. Keyed on
    // the link alone, and let go however it ends - a touch taking the scroll
    // over, the row not drawn - or it waited, and a later change to the list
    // jumped to it while the user was elsewhere.
    val offer = state.offerCallLog && state.recordings.isNotEmpty()
    // Not over a list not read yet: it showed over nothing, at every cold
    // open, before "No recordings yet." (review, 2026-11-18).
    val tip = !state.selectHintSeen && !state.selecting && !state.listUnread
    // Only while JemRec is on, as its notification (BatteryHealth.
    // tellIfRestricted): off, nothing is held back that it records, and in
    // Ask first the card - which cannot be put away there - told an Off
    // JemRec it was missing calls (review, 2026-11-18).
    val battery = state.batteryNotice && state.enabled
    // The same for the Default USB configuration that stops the recorder at
    // every lock: off, nothing it records is lost (UsbDefault.tellIfAtRisk).
    val usbDefault = state.usbDefaultFunctions.takeIf { state.usbDefaultNotice && state.enabled }
    // The same for notifications off: its words say what calls it costs, and
    // under Off it told a JemRec that records nothing "JemRec still records
    // your calls" - or, in Ask first, that no call is recorded, not to be put
    // away (review, 2026-11-18).
    val notificationsOff = state.notificationsOff.takeIf { state.enabled }
    val cards = listOf(notificationsOff != null, battery, usbDefault != null, state.folderLost, offer).count { it }
    val placeOf by rememberUpdatedState { name: String -> placeInList(name, cards, tip, days) }
    val reveal = state.reveal
    LaunchedEffect(reveal) {
        val name = reveal ?: return@LaunchedEffect
        try {
            // Drawn - its window was widened with the link - and laid out as
            // drawn, for the header's height and where the row is now.
            val at = withTimeoutOrNull(2_000) {
                snapshotFlow { placeOf(name) }.filterNotNull().first()
            } ?: return@LaunchedEffect
            withTimeoutOrNull(2_000) { snapshotFlow { listState.layoutInfo.totalItemsCount }.first { it == at.items } }
            val laid = listState.layoutInfo
            val pinned = laid.visibleItemsInfo.firstOrNull { it.index == at.header }?.size ?: 0
            val row = laid.visibleItemsInfo.firstOrNull { it.key == name }
            if (row == null || row.offset < pinned || row.offset + row.size > laid.viewportEndOffset) {
                listState.scrollToItem(at.target, -pinned)
            }
            // Its player opens once the session knows the recording's length,
            // after this: a row at the screen's foot grew past the edge, its
            // controls below it. Followed as it opens, for a few seconds, and
            // left to the user the moment they scroll.
            withTimeoutOrNull(FOLLOW_MS) {
                snapshotFlow { listState.isScrollInProgress to listState.layoutInfo }.first { (scrolling, info) ->
                    if (scrolling) return@first true
                    val grown = info.visibleItemsInfo.firstOrNull { it.key == name } ?: return@first false
                    val over = grown.offset + grown.size - info.viewportEndOffset
                    val room = grown.offset - pinned
                    if (over > 0 && room > 0) listState.scrollBy(minOf(over, room).toFloat())
                    false
                }
            }
        } finally {
            vm.onRevealed(name)
        }
    }

    // THE LIST IS ITS OWN SCROLL, so its header can PIN. It used to be one item
    // in the page-wide scroll, which meant "Recordings / Select" scrolled away
    // with the rows - wrong for a header, which has to stay reachable while you
    // move through a long list. A LazyColumn with a sticky header keeps it put,
    // the way a calls or messages list does, and is why the home screen is no
    // longer wrapped in the page scroll.
    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        // No TOP content padding: it would sit above the sticky header and let
        // it travel that far before pinning. The header carries its own top
        // padding instead, which pins with it. Bottom padding is fine - it is
        // below everything and never pins.
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        // The notices, then the call-log offer: each scrolls away with the
        // list (not pinned), as none is part of its chrome. What
        // notifications are off, and what that costs: above everything, as
        // it can mean calls going unrecorded (NotificationHealth).
        val off = notificationsOff
        if (off != null) {
            item {
                NotificationsOffCard(
                    off,
                    askFirst = !state.automatic,
                    button = callButtonWorks(state),
                    onTurnOn = turnOnNotifications,
                    onNotNow = { vm.dismissNotificationsOff(off) },
                )
            }
        }
        // The Battery setting Restricted: what it holds back, and where to
        // lift it (BatteryHealth).
        if (battery) {
            item {
                BatteryRestrictedCard(
                    askFirst = !state.automatic,
                    trackingOff = !state.callTracking,
                    onOpen = vm::openBatterySettings,
                    onNotNow = vm::dismissBatteryNotice,
                )
            }
        }
        // The Default USB configuration that stops the recorder at every
        // lock: what it costs, and one tap to No data transfer (UsbDefault).
        if (usbDefault != null) {
            item {
                UsbDefaultCard(
                    functions = usbDefault,
                    didNotStay = state.usbDefaultDidNotStay,
                    onSet = vm::setUsbDefaultNone,
                    onNotNow = vm::dismissUsbDefaultNotice,
                )
            }
        }
        // The folder chosen lost: where calls go now, and the way back.
        if (state.folderLost) {
            item {
                FolderLostCard(state, choose = { pickFolder(vm.folderPickerStart()) }, useStandard = vm::useDefaultFolder)
            }
        }
        // Offered once, above the thing it would improve: a one-time
        // suggestion.
        if (offer) {
            item {
                NoticeCard(
                    title = "Show who called",
                    text = "JemRec can label each recording with the contact's name " +
                        "and photo, or the number if they are not in your " +
                        "contacts. That means permission to read your call log, " +
                        "which holds the name, and your contacts, which hold " +
                        "the photo. The call log also lets JemRec tell you if a " +
                        "call ever goes by unrecorded.\n\nNothing else changes, " +
                        "and you can say no to either.",
                    action = "Allow",
                    onAction = askForCallLog,
                    onNotNow = vm::dismissCallLogOffer,
                )
            }
        }

        // Pinned, and on the list's own ground. It was a grey band, which
        // put a white header, the rule, grey chips and a white list in a stack
        // - the tabs above it looked as if they belonged to nothing. The tint
        // now belongs to the app bar and tabs (see MainScreen); the filters
        // are part of the list they filter. Opaque, so rows scroll UNDER it
        // without showing through.
        stickyHeader {
            // Presets, and a search that stays folded away until asked for.
            // A full-width search bar sat here permanently for something used
            // once in a while; now the chips are all that is pinned, and the
            // last chip unfolds the field below them on demand. Hidden while
            // selecting, where the header is a selection toolbar instead. The
            // search field is deliberately NOT gated on selecting: it stays
            // open through selection, so the query you searched by is still on
            // screen while you tick rows and delete - and the list does not
            // jump as it vanishes. Beside the list instead, on a phone on its
            // side (NarrowingPanel), chips and search both.
            NarrowingHeader(
                narrowed,
                shown = filtered.size,
                of = state.recordings.size,
                onShowAll = vm::showAllRecordings,
                aside = narrowingAside,
                focus = searchFocus,
                listed = state.recordings.isNotEmpty(),
                chipsShown = !state.selecting,
                scrolled = scrolled,
            ) {
                // NO "Recordings" TITLE, NO "Select" BUTTON. The screen is the
                // recordings, so a heading only restated the obvious and cost a
                // whole pinned row. Selection is entered by tapping a row's photo
                // (Gmail/Photos), so the only thing pinned here between calls is
                // what actually filters the list - search and presets. During
                // selection this row becomes the toolbar instead.
                // The chip row and the selection toolbar just need the header's
                // height and inset; the section's surfaceContainerHigh background
                // is carried by the Column now, so both rows are transparent on it
                // and the field below shares the same ground. HeaderRowHeight so
                // swapping rows never moves the list.
                val headerBand = Modifier
                    .fillMaxWidth()
                    .height(HeaderRowHeight)
                    .padding(horizontal = 16.dp)
                if (state.selecting) {
                    Row(
                        modifier = headerBand,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = { haptics.tap(); vm.clearSelection() },
                            modifier = Modifier.offset(x = (-12).dp),
                        ) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Cancel selection",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        Text(
                            if (state.selected.isEmpty()) "Select recordings"
                            else "${state.selected.size} selected",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            // One line in the bar's fixed height, as an app
                            // bar's title is: at 200% text it wrapped and
                            // was cut off (Android 14's guide asks for 200%).
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // Tri-state rather than a guessed "select all" glyph:
                        // it says what tapping will do and reports what is
                        // already picked, which an icon cannot.
                        val picked = picked(state.selected, shown.map { it.name })
                        TriStateCheckbox(
                            state = when (picked) {
                                Picked.NONE -> ToggleableState.Off
                                Picked.ALL -> ToggleableState.On
                                Picked.SOME -> ToggleableState.Indeterminate
                            },
                            onClick = {
                                val selectingAll = picked != Picked.ALL
                                haptics.toggle(selectingAll)
                                if (selectingAll) vm.selectAllVisible() else vm.deselectAll()
                            },
                            // Not before the list is read: tapped over the empty
                            // list, it let a selection brought back after the
                            // process ended go (review, 2026-11-18).
                            enabled = !state.listUnread,
                            // Its name, which a checkbox alone does not say.
                            modifier = Modifier.semantics { contentDescription = "Select all" },
                        )
                        // Hidden with nothing picked. A delete button that
                        // deletes nothing is a question mark on a toolbar whose
                        // whole job is to be unambiguous.
                        if (state.selected.isNotEmpty() && !state.listUnread) {
                            // Keep marks them all, or - when every one is
                            // kept already - takes the mark off them all. The
                            // icon says which: the one tapping will leave.
                            val allKept = state.recordings
                                .filter { it.name in state.selected }
                                .all { it.kept }
                            IconButton(
                                onClick = { haptics.tap(); vm.keepSelected() },
                                modifier = Modifier.offset(x = 12.dp),
                            ) {
                                Icon(
                                    if (allKept) KeepIcons.NotKept else KeepIcons.Kept,
                                    contentDescription = if (allKept) "Stop keeping selected" else "Keep selected",
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                            IconButton(
                                onClick = { haptics.tap(); vm.askDeleteSelected() },
                                modifier = Modifier.offset(x = 12.dp),
                            ) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete selected",
                                    tint = JemRecColors.delete,
                                )
                            }
                        }
                    }
                }
            }
        }

        if (state.recordings.isEmpty() && !state.listUnread) {
            item {
                // AN EMPTY LIST SAYS IT IS EMPTY. IT DOES NOT EXPLAIN THE APP.
                //
                // This read "Nothing yet. Recording starts by itself when a
                // call does" - a sentence about how the recorder works rather
                // than about the list, in the one place a person is looking for
                // their recordings. It was also not true: with "Record every
                // call" off nothing starts by itself, the app asks first. The
                // header already says which mode is on, so this says the one
                // thing the header cannot: there is nothing here.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Phone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "No recordings yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    // The exception to saying nothing else, because here the
                    // list is empty and WRONG: reinstalling leaves the old
                    // recordings on the phone but unreadable, and a person
                    // looking at "no recordings" beside a folder full of them
                    // is owed the reason rather than left to conclude the app
                    // threw their calls away.
                    if (!state.canReadAudio) {
                        // Conditional: on a first install there is nothing from
                        // before, and saying there is would be false.
                        Text(
                            "If JemRec was installed on this phone before, the calls " +
                                "it recorded then are still here. " + AudioAccess.askFor,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        FilledTonalButton(
                            onClick = {
                                haptics.tap()
                                audioPermission.launch(AudioAccess.PERMISSIONS)
                            },
                        ) { Text("Allow") }
                    }
                }
            }
        } else if (shown.isEmpty() && !state.listUnread) {
            item {
                NoMatches(
                    if (narrowing.messages) {
                        "No messages match."
                    } else if (narrowing.kept) {
                        "No kept recordings match."
                    } else if (narrowing.favorites) {
                        "No starred recordings match."
                    } else {
                        "No recordings match your search or filter."
                    },
                )
            }
        } else {
            // A one-time nudge toward the way in that has no button: selection
            // starts by tapping a photo or pressing and holding a row, and
            // nothing on screen says so until you try. It goes the first time
            // selection is used, or at its x.
            //
            // A TIP, NOT A CAPTION. It was a line of grey text above the list,
            // which read as a label a developer forgot - and half of it said
            // what every row's play mark already shows. Now it says only the
            // part that cannot be seen, in a card that is plainly a tip and
            // plainly dismissable. The Calls tab has none: its rows show the
            // play mark too, and selection does not exist there.
            if (tip) {
                item(key = "tip", contentType = "tip") {
                    SelectTip(onDismiss = { haptics.tap(); vm.dismissSelectHint() })
                }
            }
            // Under Today, Yesterday and Earlier, as a phone's call history
            // reads, with no rules between rows: the headings do the grouping.
            days.forEachIndexed { run, (day, rows) ->
                item(key = "day-$run", contentType = "day") { DayHeading(day, dayCounts[day] ?: rows.size) }
                items(rows, key = { it.name }, contentType = { "recording" }) { recording ->
                    RecordingRow(recording, day, state, vm)
                }
            }

            // No "Show more" footer: the list extends itself as you scroll (see
            // the endless-scroll effect above), so the window grows to the whole
            // filtered list on its own and there is nothing to tap.
        }
    }
}

/** The tip over the recordings: how selection starts, with an x to put it away. */
@Composable
private fun SelectTip(onDismiss: () -> Unit) {
    TipCard(
        "Tap a photo, or press and hold a call, to select recordings",
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp),
        onDismiss = onDismiss,
    )
}

/** How long a link's row is followed as its player opens (HomeScreen). */
private const val FOLLOW_MS = 3_000L

/** A row's place in HomeScreen's list: the item to scroll to, how many
 *  items the list has, and where its header is. */
private class Place(val target: Int, val items: Int, val header: Int)

/**
 * Where [name]'s row is in HomeScreen's list - or, the first of its day, its
 * day's heading, so both show - counted as the LazyColumn adds its items:
 * the [cards] above (the notices, the call-log offer), the
 * header, the [tip], then each day's heading and
 * rows. Null if the row is not drawn.
 */
private fun placeInList(name: String, cards: Int, tip: Boolean, days: List<Pair<Day, List<Recording>>>): Place? {
    val header = cards
    var at = header + 1 + (if (tip) 1 else 0)
    var place: Int? = null
    for ((_, rows) in days) {
        val row = rows.indexOfFirst { it.name == name }
        if (row >= 0 && place == null) place = if (row == 0) at else at + 1 + row
        at += 1 + rows.size
    }
    return place?.let { Place(it, at, header) }
}

/** What is off, what that costs, and the way to turn it back on - or put the
 *  notice off, where that leaves no call unrecorded (NotificationHealth). */
@Composable
private fun NotificationsOffCard(
    off: NotificationHealth.Off,
    askFirst: Boolean,
    button: Boolean,
    onTurnOn: () -> Unit,
    onNotNow: () -> Unit,
) {
    val (title, text) = when (off) {
        NotificationHealth.Off.ALL -> "Notifications are off" to when {
            askFirst && !button ->
                "Ask first asks whether to record each call in a notification, so while " +
                    "JemRec's notifications are off, no call is recorded. Nor can it tell " +
                    "you when something stops it recording."
            askFirst ->
                "JemRec still asks with its button over calls, but cannot tell you " +
                    "anything: not that a call was recorded, and not when something stops " +
                    "it recording."
            else ->
                "JemRec still records your calls, but cannot tell you anything: not that a " +
                    "call was recorded, and not when something stops it recording."
        }
        NotificationHealth.Off.ASK_FIRST -> "Ask first cannot ask" to
            "Its question, \"Record this call?\", comes as a notification, and that " +
            "notification is off in Android's settings - so no call is being recorded."
        NotificationHealth.Off.ASK_FIRST_QUIET -> "Ask first's question will not pop up" to
            "\"Record this call?\" is set not to pop up in Android's settings, so during " +
            "a call it waits in the notification shade, easy to miss - and a call it " +
            "is not answered for is not recorded."
        NotificationHealth.Off.ASK_FIRST_BRIEF -> "Ask first's question will not stay up" to
            "JemRec's full-screen notifications are off in Android's settings, so " +
            "during a call \"Record this call?\" stays on screen for a minute rather " +
            "than until you answer it, then waits in the notification shade, easy " +
            "to miss."
        NotificationHealth.Off.PROBLEMS -> "Problem alerts are off" to
            "If a call goes unrecorded, or something stops JemRec recording, it " +
            "cannot tell you: its \"Recording problems\" notifications are off in " +
            "Android's settings."
    }
    NoticeCard(
        title = title,
        text = text,
        action = "Turn on",
        onAction = onTurnOn,
        onNotNow = if (NotificationHealth.dismissable(off, askFirst && !button)) onNotNow else null,
    )
}

/** JemRec's Battery setting is Restricted (BatteryHealth): what Android
 *  holds back for it, and the way to App info, where it is changed - or put
 *  the notice off, but not in Ask first, where calls go unasked and so
 *  unrecorded (BatteryHealth.dismissable). Shown only while JemRec is on. */
@Composable
private fun BatteryRestrictedCard(askFirst: Boolean, trackingOff: Boolean, onOpen: () -> Unit, onNotNow: () -> Unit) {
    NoticeCard(
        title = com.jemcik.jemrec.system.BatteryHealth.TITLE,
        // App info names its page "App battery usage" (Android 17 AVD, whose
        // page has "Allow background usage"), "Battery" before.
        text = com.jemcik.jemrec.system.BatteryHealth.words(askFirst, trackingOff),
        action = "Open App info",
        onAction = onOpen,
        onNotNow = if (com.jemcik.jemrec.system.BatteryHealth.dismissable(askFirst)) onNotNow else null,
    )
}

/** The Default USB configuration is a data mode and the lock is secure
 *  (UsbDefault): what that costs, Set to No data transfer, or Not now -
 *  put away until the setting or the lock changes. Shown only while JemRec is on. */
@Composable
internal fun UsbDefaultCard(functions: Long, didNotStay: Boolean, onSet: () -> Unit, onNotNow: () -> Unit) {
    NoticeCard(
        title = com.jemcik.jemrec.system.UsbDefault.TITLE,
        text = com.jemcik.jemrec.system.UsbDefault.words(functions, didNotStay),
        action = com.jemcik.jemrec.system.UsbDefault.ACTION,
        onAction = onSet,
        onNotNow = onNotNow,
    )
}

/** The folder chosen can no longer be reached (FolderAccess): where new
 *  calls go, what is missing from the list, and the two ways on. */
@Composable
private fun FolderLostCard(state: UiState, choose: () -> Unit, useStandard: () -> Unit) {
    val honor = if (state.fileNames == FileNames.Format.HONOR) {
        " Honor's Phone app plays only recordings in Sounds/CallRecord."
    } else {
        ""
    }
    NoticeCard(
        title = "Your recordings folder cannot be reached",
        text = "JemRec can no longer reach ${RecordingStore.chosenWords(state.folder)} - " +
            "${com.jemcik.jemrec.recordings.FolderAccess.WHY_LOST}. New recordings go to the standard " +
            "Recordings folder until you choose it again, and the ones in it do not show here.$honor",
        action = "Choose again",
        onAction = choose,
        onNotNow = useStandard,
        notNow = "Use the standard folder",
        // As the Settings folder dialog's Use default: dropped while the screen
        // is busy, so greyed. Choose again stays live: the folder picked is
        // taken at once (FOLDER_CHOSEN bypasses the busy screen).
        notNowEnabled = !state.busy,
    )
}
