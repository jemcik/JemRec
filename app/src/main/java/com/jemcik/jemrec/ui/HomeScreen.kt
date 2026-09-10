package com.jemcik.jemrec.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.Card
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.OutlinedTextFieldDefaults
import com.jemcik.jemrec.capture.AudioAccess
import com.jemcik.jemrec.capture.CallLogLookup
import com.jemcik.jemrec.capture.RecordingFilter
import com.jemcik.jemrec.capture.filterRecordings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.jemcik.jemrec.ui.theme.JemRecColors

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
fun HomeScreen(state: UiState, vm: MainViewModel, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    // Focus jumps to the search field the moment it opens, so the keyboard is
    // up and ready - opening search only to have to tap the field as well would
    // undo the point of hiding it behind one tap.
    val searchFocus = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    LaunchedEffect(state.searchOpen) {
        if (state.searchOpen) runCatching { searchFocus.requestFocus() }
    }
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
                    (if (count == 1) "It will be deleted permanently"
                    else "All $count of them will be deleted permanently") +
                        " and cannot be recovered."
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

    // Share asks HOW first: the original Opus is instant and fine for Android
    // and desktop; the .m4a plays on Apple's stock apps too but has to be
    // converted. A bottom sheet, not a dialog - it is a short menu of ways to
    // do one thing, the M3 pattern for exactly that, and roomy enough to say
    // what each costs.
    state.shareChoice?.let { recording ->
        ModalBottomSheet(onDismissRequest = { vm.dismissShareChoice() }) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    "Share as",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
                )
                ListItem(
                    headlineContent = { Text("Any device (.m4a)") },
                    supportingContent = {
                        Text("Converts first - plays on iPhone, Mac, Windows, anything")
                    },
                    leadingContent = {
                        Icon(Icons.Filled.Share, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        haptics.tap(); vm.shareUniversal(recording)
                    },
                )
                ListItem(
                    headlineContent = { Text("Original (.ogg)") },
                    supportingContent = {
                        Text("No conversion, smaller - best for Android and desktop")
                    },
                    leadingContent = {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        haptics.tap(); vm.shareOriginal(recording)
                    },
                )
            }
        }
    }

    // While the .m4a is being made. Indeterminate - the wait is the length of
    // the call, not a known number of steps - and not dismissible: cancelling
    // half a transcode just leaves nothing to share. A short blocking wait, so
    // a spinner with a word, not a whole screen.
    if (state.converting) {
        AlertDialog(
            // Back or a tap outside cancels too - the same as the button, so the
            // dialog is never a trap while it works.
            onDismissRequest = { vm.cancelConversion() },
            confirmButton = {
                TextButton(onClick = { haptics.tap(); vm.cancelConversion() }) {
                    Text("Cancel")
                }
            },
            text = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    CircularProgressIndicator()
                    Text("Converting to .m4a…")
                }
            },
        )
    }

    val audioPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.onAudioAccessResult() }

    val callLogPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { vm.onCallLogResult() }

    val filtered = filterRecordings(state.recordings, state.query, state.filter, state.favoritesOnly)
    val shown = filtered.take(state.visibleCount)

    // Endless scroll instead of a "Show more" button. Everything is already in
    // memory - the list, the durations, the caller lookups - so a button gating
    // it was friction in front of data that was right there. As the last rows
    // come into view the window quietly widens; with nothing to fetch there is
    // no spinner, and it stops on its own once the whole filtered list is shown.
    val listState = rememberLazyListState()
    val nearEnd by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= listState.layoutInfo.totalItemsCount - 3
        }
    }
    LaunchedEffect(nearEnd, filtered.size, state.visibleCount) {
        if (nearEnd && filtered.size > state.visibleCount) vm.showMore()
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
        // Offered once, above the thing it would improve. It scrolls away (not
        // pinned): it is a one-time suggestion, not part of the list's chrome.
        if (state.offerCallLog && state.recordings.isNotEmpty()) {
            item {
                Card(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp, bottom = 16.dp)) {
                    Column(
                        Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Show who called", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "JemRec can label each recording with the contact's name " +
                                "and photo, or the number if they are not in your " +
                                "contacts. That means permission to read your call log, " +
                                "which holds the name, and your contacts, which hold " +
                                "the photo.\n\nNothing else changes, and you can say no " +
                                "to either.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilledTonalButton(
                                onClick = {
                                    haptics.tap()
                                    callLogPermission.launch(CallLogLookup.PERMISSIONS)
                                },
                            ) { Text("Allow") }
                            TextButton(
                                onClick = { haptics.tap(); vm.dismissCallLogOffer() },
                            ) { Text("Not now") }
                        }
                    }
                }
            }
        }

        // Pinned. The WHOLE header section is the tonal band now, not just the
        // chip/toolbar row: the search field sits on it too, so the field's
        // section reads as part of the filters bar rather than a white block
        // hanging below it. Opaque, so rows scroll UNDER it without showing
        // through.
        stickyHeader {
            Column(Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
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
                            modifier = Modifier.weight(1f),
                        )
                        // Tri-state rather than a guessed "select all" glyph:
                        // it says what tapping will do and reports what is
                        // already picked, which an icon cannot.
                        TriStateCheckbox(
                            state = when {
                                state.selected.isEmpty() -> ToggleableState.Off
                                state.selected.size >= shown.size -> ToggleableState.On
                                else -> ToggleableState.Indeterminate
                            },
                            onClick = {
                                val selectingAll = state.selected.size < shown.size
                                haptics.toggle(selectingAll)
                                if (selectingAll) vm.selectAllVisible() else vm.deselectAll()
                            },
                        )
                        // Hidden with nothing picked. A delete button that
                        // deletes nothing is a question mark on a toolbar whose
                        // whole job is to be unambiguous.
                        if (state.selected.isNotEmpty()) {
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
                // Presets, and a search that stays folded away until asked for.
                // A full-width search bar sat here permanently for something used
                // once in a while; now the chips are all that is pinned, and the
                // last chip unfolds the field below them on demand. Hidden while
                // selecting, where the header is a selection toolbar instead.
                if (!state.selecting && state.recordings.isNotEmpty()) {
                    Row(
                        // The same band the selection toolbar wears, so the header
                        // is one strip in both modes and switching moves nothing.
                        modifier = headerBand,
                        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // No "All" chip: all IS the resting state, the one where
                        // none of these is picked. A chip for it would be the
                        // only one always selectable and never meaningfully off,
                        // and tapping the active chip again already falls back to
                        // all - so the empty row says "everything" on its own.
                        PresetChip(state.filter == RecordingFilter.TODAY, "Today") {
                            haptics.tap(); vm.setFilter(RecordingFilter.TODAY)
                        }
                        PresetChip(state.filter == RecordingFilter.THIS_WEEK, "This week") {
                            haptics.tap(); vm.setFilter(RecordingFilter.THIS_WEEK)
                        }
                        // A toggle on its own axis, not one of the mutually
                        // exclusive time presets: it narrows to starred contacts
                        // on top of whatever time range is chosen, and leaves the
                        // time chips alone.
                        FilterChip(
                            selected = state.favoritesOnly,
                            onClick = { haptics.tap(); vm.toggleFavorites() },
                            shape = CircleShape,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondary,
                            ),
                            label = {
                                Icon(
                                    Icons.Filled.Star,
                                    contentDescription = "Only calls with favourite contacts",
                                    // Gold on the band; a DEEPER gold once
                                    // selected, where the pill turns pale in dark
                                    // theme and the bright gold washed into it.
                                    tint = if (state.favoritesOnly) {
                                        JemRecColors.favoriteSelected
                                    } else {
                                        JemRecColors.favorite
                                    },
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                        // The fold-out. A filter chip in looks, a disclosure in
                        // job: selected while the field is open, so the icon
                        // reads as "search is showing" rather than a filter that
                        // is on.
                        FilterChip(
                            selected = state.searchOpen,
                            onClick = { haptics.tap(); vm.toggleSearch() },
                            shape = CircleShape,
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.secondary,
                            ),
                            label = {
                                Icon(
                                    Icons.Filled.Search,
                                    contentDescription = "Search recordings",
                                    // An icon-only chip label does not inherit a
                                    // visible content colour the way a text label
                                    // does - untinted it came out invisible - so
                                    // the tint is set for each state: onSecondary
                                    // on the bright selected pill, onSurfaceVariant
                                    // on the band.
                                    tint = if (state.searchOpen) {
                                        MaterialTheme.colorScheme.onSecondary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                        )
                    }
                }
                // The search field is deliberately NOT gated on !selecting: it
                // stays open through selection, so the query you searched by is
                // still on screen while you tick rows and delete - and the list
                // does not jump as it vanishes. Only the chip row above becomes
                // the selection toolbar; the field holds its place under both.
                if (state.recordings.isNotEmpty()) {
                    // Below the header row, and only when asked for. Expands and
                    // fades in together so it reads as unfolding from the chip,
                    // not jumping into place.
                    AnimatedVisibility(
                        visible = state.searchOpen,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        // A hand-built compact field, not the stock
                        // OutlinedTextField. That one reserves 56dp - the M3
                        // search-bar height - and forcing it shorter clips the
                        // text. Driving the decoration box directly lets the
                        // reserved padding shrink so a single line sits in 48dp,
                        // the M3 minimum touch target and the shortest a field may
                        // honestly be. The pill shape and colours are the stock
                        // ones, only the height is ours.
                        val searchInteraction = remember { MutableInteractionSource() }
                        // The stock field paints a bold primary-blue border while
                        // focused - and this one is focused whenever it is open,
                        // so it wore a blue ring the grey-outlined chips never did.
                        // Border pinned to the chips' own outline colour, one
                        // thickness focused or not, so the field reads as part of
                        // the same set. Cursor stays primary; that is the caret,
                        // not the outline.
                        val searchColors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                        )
                        BasicTextField(
                            value = state.query,
                            onValueChange = vm::setQuery,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 12.dp)
                                // No forced height: a fixed height taller than the
                                // content parks the content at the top and leaves
                                // the slack below, so the text sat high in the pill.
                                // The content padding sizes it instead (see below),
                                // which keeps the one line centred.
                                .focusRequester(searchFocus),
                            textStyle = MaterialTheme.typography.bodyLarge.copy(
                                color = MaterialTheme.colorScheme.onSurface,
                            ),
                            singleLine = true,
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            interactionSource = searchInteraction,
                            decorationBox = { innerTextField ->
                                OutlinedTextFieldDefaults.DecorationBox(
                                    value = state.query,
                                    innerTextField = innerTextField,
                                    enabled = true,
                                    singleLine = true,
                                    visualTransformation = VisualTransformation.None,
                                    interactionSource = searchInteraction,
                                    colors = searchColors,
                                    placeholder = { Text("Search by name or number") },
                                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                                    trailingIcon = {
                                        if (state.query.isNotEmpty()) {
                                            IconButton(onClick = { vm.setQuery("") }) {
                                                Icon(Icons.Filled.Close, contentDescription = "Clear search")
                                            }
                                        }
                                    },
                                    // The whole reason this is hand-built: with no
                                    // forced height, this padding IS the height -
                                    // one 24dp line plus 12dp each side is a 48dp
                                    // pill, the M3 minimum touch target - and being
                                    // equal top and bottom it centres the line.
                                    contentPadding = OutlinedTextFieldDefaults.contentPadding(
                                        top = 12.dp,
                                        bottom = 12.dp,
                                    ),
                                    container = {
                                        OutlinedTextFieldDefaults.Container(
                                            enabled = true,
                                            isError = false,
                                            interactionSource = searchInteraction,
                                            colors = searchColors,
                                            shape = RoundedCornerShape(28.dp),
                                            focusedBorderThickness = 1.dp,
                                            unfocusedBorderThickness = 1.dp,
                                        )
                                    },
                                )
                            },
                        )
                    }
                }
                // The rule caps the header and pins with it, so the title sits
                // above the list instead of reading as its first row.
                if (state.recordings.isNotEmpty()) HorizontalDivider()
            }
        }

        if (state.recordings.isEmpty()) {
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
                        Text(
                            "Calls recorded before JemRec was reinstalled are still " +
                                "on this phone. It needs permission to read audio to " +
                                "show them.",
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
        } else if (shown.isEmpty()) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp, vertical = 56.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "No matches",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        if (state.favoritesOnly) {
                            "No starred recordings match."
                        } else {
                            "No recordings match your search or filter."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            // A one-time nudge toward the way in that has no button any more.
            // The title and its "Select" are gone; selection now starts by
            // tapping a photo, and nothing on screen says so until you try. It
            // clears itself the first time selection is entered (by photo or by
            // long-press), so a returning user never sees it.
            if (!state.selectHintSeen && !state.selecting) {
                item {
                    Text(
                        "Tap a photo to select",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(top = 12.dp, bottom = 4.dp),
                    )
                }
            }
            itemsIndexed(shown, key = { _, recording -> recording.name }) { index, recording ->
                Column {
                    // Between items, not after each: nothing dangles under the
                    // last row.
                    if (index > 0) HorizontalDivider()
                    val isCurrent = state.playingName == recording.name
                    val isSelected = recording.name in state.selected
                    Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                // A fixed minimum height, so a row is the same
                                // height whether or not it is selecting. Normal
                                // rows are as tall as the 48dp share button;
                                // selection rows drop it and use a checkbox that
                                // - being row-driven, not self-clickable -
                                // reserves no interactive size, so without this
                                // the rows visibly shrank on entering selection.
                                .heightIn(min = 64.dp)
                                // The whole row is the target, not just the
                                // text. What the tap MEANS depends on the mode:
                                // normally it plays, and while selecting it
                                // picks - which is the Android convention and
                                // the reason long-press is reserved for
                                // starting selection rather than used for a
                                // menu.
                                .combinedClickable(
                                    onClick = {
                                        if (state.selecting) {
                                            // In selection mode a tap anywhere on
                                            // the row picks it - the Android
                                            // convention, and the only thing that
                                            // makes sense once selecting has
                                            // stopped playback and hidden the
                                            // scrubber: a tap that played here
                                            // played invisibly.
                                            haptics.toggle(recording.name !in state.selected)
                                            vm.toggleSelected(recording)
                                        } else {
                                            // Otherwise the row plays. Selecting
                                            // starts from the photo or a
                                            // long-press - the Gmail split: the
                                            // avatar selects, the row opens.
                                            haptics.tap()
                                            vm.play(recording)
                                        }
                                    },
                                    onLongClick = {
                                        if (state.selecting) {
                                            haptics.toggle(recording.name !in state.selected)
                                            vm.toggleSelected(recording)
                                        } else {
                                            // The one everyone expects to feel:
                                            // a long-press that starts selecting.
                                            haptics.longPress()
                                            vm.startSelecting(recording)
                                        }
                                    },
                                )
                                // Padding goes INSIDE the clickable, so the
                                // ripple fills the whole row edge to edge while
                                // the content still lines up with the 16dp the
                                // header sits at.
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // The leading slot keeps the play glyph in selection
                            // mode too, so rows keep their shape. Selection shows
                            // on the photo itself - it flips to a tick - so
                            // nothing here has to change to a checkbox and then
                            // explain why the play arrow left.
                            if (isCurrent && state.playing) {
                                PauseGlyph(
                                    tint = JemRecColors.play,
                                    modifier = Modifier.offset(x = GLYPH_INSET),
                                )
                            } else {
                                // Nudged out by the amount of transparent margin
                                // Material draws inside the play triangle. The
                                // icon BOX was already flush with the header, but
                                // a box is not what anyone sees - the triangle
                                // sat visibly right of "Recordings" while being
                                // technically aligned with it.
                                Icon(
                                    Icons.Filled.PlayArrow,
                                    contentDescription = "Play",
                                    tint = JemRecColors.play,
                                    modifier = Modifier.offset(x = GLYPH_INSET),
                                )
                            }
                            // Tapping the photo picks the row - the Gmail /
                            // Photos gesture. The photo FLIPS to a tick rather
                            // than a checkbox blinking in beside it, so the
                            // feedback is the object turning over, not a new
                            // control appearing. A firm long-press haptic on the
                            // way in, the lighter toggle buzz after. Tapping the
                            // row body plays; long-press on the row also selects.
                            SelectableAvatar(
                                caller = recording.caller,
                                photo = recording.callerPhoto,
                                selected = isSelected,
                                onClick = {
                                    if (state.selecting) {
                                        haptics.toggle(!isSelected)
                                        vm.toggleSelected(recording)
                                    } else {
                                        haptics.longPress()
                                        vm.startSelecting(recording)
                                    }
                                },
                            )
                            Column(Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        // The contact's name, or the number if
                                        // they are not saved. Nothing else on this
                                        // line - a name IS the identity.
                                        recording.caller ?: recording.whenLabel,
                                        style = MaterialTheme.typography.bodyLarge,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false),
                                    )
                                    // Read-only: the address book's favourite
                                    // star, not something to toggle here.
                                    if (recording.callerFavorite) {
                                        Icon(
                                            Icons.Filled.Star,
                                            contentDescription = "Favourite contact",
                                            tint = JemRecColors.favorite,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    }
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    DirectionArrow(recording.incoming)
                                    Text(
                                        // When, and how long. With no caller the
                                        // headline already is the date, so just
                                        // the length here.
                                        if (recording.caller != null) {
                                            "${recording.whenLabel} · ${recording.durationLabel}"
                                        } else {
                                            recording.durationLabel
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            // Share stays on the row because sharing one
                            // recording is a single-item action. Delete does
                            // NOT: it sat a thumb-width from Play on a row
                            // whose entire purpose is being tapped, and what it
                            // destroyed could not be recovered. It lives in the
                            // selection toolbar now, where reaching it takes a
                            // deliberate step.
                            if (!state.selecting) {
                                IconButton(
                                    onClick = { haptics.tap(); vm.share(recording) },
                                    modifier = Modifier.offset(x = 12.dp),
                                ) {
                                    Icon(
                                        Icons.Filled.Share,
                                        contentDescription = "Share",
                                        // Match the avatar circle rather than
                                        // defaulting to near-black: the share
                                        // control belongs with the row's other
                                        // furniture, not shouting over it.
                                        tint = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                            }
                        }

                        // The scrubber belongs to whichever recording is
                        // loaded, and only appears there - a row of sliders
                        // would be noise.
                        //
                        // WITH A WAY OUT. Once a recording was loaded the
                        // scrubber stayed under its row until the recording was
                        // deleted; there was no control that folded it back. The
                        // scrubber is the row EXPANDED, so the control is
                        // Material's expand/collapse chevron on the trailing
                        // edge, under the row's share icon so the trailing
                        // controls line up. NOT a close (X): in a list of
                        // recordings, next to a recording, an X reads as "remove
                        // this one" - it was tried, and it did. The chevron
                        // stops playback and folds the row back.
                        if (!state.selecting && isCurrent && state.durationMs > 0) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 16.dp, end = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Slider(
                                    value = state.positionMs.toFloat()
                                        .coerceIn(0f, state.durationMs.toFloat()),
                                    onValueChange = { vm.seekTo(it.toInt()) },
                                    valueRange = 0f..state.durationMs.toFloat(),
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { haptics.tap(); vm.stopPlaying() }) {
                                    Icon(
                                        Icons.Filled.KeyboardArrowUp,
                                        contentDescription = "Collapse player",
                                        tint = MaterialTheme.colorScheme.secondary,
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    // The end label sits under the slider's end,
                                    // not under the chevron.
                                    .padding(start = 16.dp, end = 52.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    clock(state.positionMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    clock(state.durationMs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }

                    }
                }
            }

            // No "Show more" footer: the list extends itself as you scroll (see
            // the endless-scroll effect above), so the window grows to the whole
            // filtered list on its own and there is nothing to tap.
        }
    }

/**
 * Who the call was with, as a circle.
 *
 * Initials when there is a name, a handset when there is only a number or
 * nothing at all - so every row has the same shape and the list does not go
 * ragged down its left edge where some calls happen to be in the contacts and
 * others are not.
 *
 * Drawn rather than fetched. The call log carries a photo URI, but it points
 * into the contacts provider and opening it needs READ_CONTACTS - a second
 * dangerous permission, for a picture. Initials cost nothing, identify a
 * caller just as well at this size, and keep the app to the one permission it
 * actually asked for.
 */
/** One preset filter chip.
 *
 *  Selected chips wear `secondary`, not the default `secondaryContainer`: on the
 *  neutral header band the container tone sat too close to the band to read as
 *  on. `secondary` is a light tone in dark theme, so a picked chip becomes a
 *  bright pill that pops the way the ticked photos and checkbox do. */
@Composable
private fun PresetChip(selected: Boolean, label: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        // Fully rounded, not the M3 default 8dp: the search field is a pill and
        // the avatars are circles, so a squarish chip was the one hard-cornered
        // thing in a rounded header. CircleShape on a wider-than-tall chip is a
        // stadium - a pill - which unifies the row's shape language.
        shape = CircleShape,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondary,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondary,
        ),
    )
}

/**
 * The caller photo, which turns over to a tick when the row is selected.
 *
 * The flip is the whole point. A checkbox that blinks in beside an unchanged
 * photo reads as a separate control arriving; the same circle rotating on its
 * vertical axis to a ticked face reads as THIS item being picked - the object
 * you touched responding, which is why Gmail and Photos do it this way. The
 * ~280ms turn is the "it happened" the instant hard-swap never gave.
 *
 * Drawn as two faces of one coin: below 90 degrees the photo shows, past it the
 * tick, and the tick is turned a further 180 so it is not left mirrored by the
 * parent's rotation.
 */
@Composable
private fun SelectableAvatar(
    caller: String?,
    photo: Uri?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (selected) 180f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "avatarFlip",
    )
    Box(
        modifier = Modifier
            .size(36.dp)
            .graphicsLayer {
                rotationY = rotation
                // Without a finite camera distance the turn looks flat and
                // stretched; this gives it the shallow perspective a real card
                // flip has.
                cameraDistance = 12f * density
            }
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (rotation <= 90f) {
            CallerAvatar(caller, photo)
        } else {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { rotationY = 180f }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "Selected",
                    tint = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun CallerAvatar(caller: String?, photo: Uri?) {
    val initials = initialsOf(caller)
    val bitmap = rememberContactPhoto(photo)
    // A SOLID circle, not a tonal container. secondaryContainer is a pale blue
    // and the cards are a pale grey, so on light theme the circle barely
    // separated from the card - a real contrast failure, since a container tone
    // is pale by design and cannot stand off a pale surface. The solid
    // secondary role does, in both themes, and its on-colour gives the glyph or
    // initials full contrast.
    Box(
        modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondary),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (initials != null) {
            Text(
                initials,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSecondary,
            )
        } else {
            Icon(
                Icons.Filled.Phone,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * The contact's picture, decoded off the main thread, or null.
 *
 * Null covers every ordinary case as well as failure: no contact, no photo, or
 * permission not given. The caller falls back to initials either way, so
 * nothing has to know which.
 *
 * No image library for this. One small bitmap per row, decoded once and kept by
 * the composition, is not worth a dependency - and a call recorder is a poor
 * place to add one that fetches over a network.
 */
@Composable
private fun rememberContactPhoto(uri: Uri?): ImageBitmap? {
    val context = LocalContext.current
    return produceState<ImageBitmap?>(initialValue = null, uri) {
        value = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    BitmapFactory.decodeStream(stream)?.asImageBitmap()
                }
            }.getOrNull()
        }
    }.value
}

/**
 * Up to two initials, or null when there is no name to take them from.
 *
 * A phone number has no initials worth showing - "+3" tells nobody anything -
 * so anything without a letter in it falls back to the handset.
 */
private fun initialsOf(caller: String?): String? {
    if (caller == null || caller.none { it.isLetter() }) return null
    return caller.split(' ', '-')
        .mapNotNull { part -> part.firstOrNull { it.isLetter() }?.uppercaseChar() }
        .take(2)
        .joinToString("")
        .ifEmpty { null }
}

/**
 * Which way the call went, as the arrow every phone app uses.
 *
 * Material has call_made and call_received for exactly this and neither is in
 * material-icons-core, which ships about fifty icons rather than several
 * thousand. Both shapes are a plain arrow turned: out is up-and-right, in is
 * down-and-left. Rotating one is honest and free, where pulling in
 * material-icons-extended for two glyphs would cost thousands of vectors.
 *
 * The words it replaces were "Incoming" and "Outgoing", which is a lot of a
 * short line spent on something an arrow says at a glance - and the room is
 * wanted for who the call was with.
 */
@Composable
private fun DirectionArrow(incoming: Boolean) {
    Icon(
        Icons.Filled.ArrowForward,
        contentDescription = if (incoming) "Incoming" else "Outgoing",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .size(14.dp)
            .rotate(if (incoming) 135f else -45f),
    )
}

/**
 * A pause glyph, drawn rather than imported.
 *
 * material-icons-core has PlayArrow but not Pause, and pulling in
 * material-icons-extended - thousands of vectors - for two rectangles would be
 * a poor trade.
 */
@Composable
private fun PauseGlyph(tint: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.size(24.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(2) {
            Box(
                Modifier
                    .width(6.dp)
                    .height(18.dp)
                    .background(tint, RoundedCornerShape(2.dp))
            )
        }
    }
}

/**
 * How far the play triangle is drawn inside its own icon box.
 *
 * Material icons are 24dp boxes with the artwork inset, and the inset is not
 * the same on every glyph - which is why aligning the boxes leaves the shapes
 * looking ragged. Measured against this list rather than derived, because the
 * only thing that matters is whether it looks right next to "Recordings".
 */
private val GLYPH_INSET = (-5).dp

/**
 * One height for the pinned header's top row, whichever it is.
 *
 * The chip row and the selection toolbar swap in the same slot, and a person
 * entering selection should see a toolbar appear, not the whole list lurch. The
 * two do not naturally agree - 32dp chips against 48dp icon buttons - so both
 * are pinned to this, tall enough for the buttons and centred either way. The
 * list stays exactly where it was.
 */
private val HeaderRowHeight = 56.dp

/** mm:ss, for the scrubber's two ends. */
private fun clock(ms: Int): String {
    val total = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(total / 60, total % 60)
}
