package com.jemcik.jemrec.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.jemcik.jemrec.calls.VoipApps
import com.jemcik.jemrec.ui.theme.JemRecColors
import com.jemcik.jemrec.ui.theme.LocalLook
import com.jemcik.jemrec.ui.theme.Look
import com.jemcik.jemrec.ui.theme.LookFonts

/**
 * A list's chips, as one band: the Recordings tab's and the Calls tab's, drawn
 * from [chips] (see chipsFor - a chip only where it narrows its list), [on]
 * the ones picked. [calls] words them for the Calls tab.
 *
 * The same band the selection toolbar wears, so the header is one strip in
 * both modes and switching moves nothing. Scrolled sideways when the chips
 * outgrow the screen, and still centred whenever they fit: at least as wide
 * as the band. No "All" chip: all IS the resting state, the one where none of
 * these is picked. A chip for it would be the only one always selectable and
 * never meaningfully off, and tapping the active chip again already falls
 * back to all - so the empty row says "everything" on its own.
 */
@Composable
internal fun ListChipRow(chips: List<ListChip>, on: Set<ListChip>, calls: Boolean, onTap: (ListChip) -> Unit) {
    // A lit chip in view whenever the row is drawn anew - the app reopened,
    // the tab switched to - with it on: past the screen's edge it said
    // nothing (on the Honor, Messages, 2026-10-23). B of the fixes sketched,
    // with NarrowingLine. Not on a tap: the chip tapped is where the finger
    // is. The pinned header's keepsItsPlace stops the request at the row.
    val firstLit = remember { chips.firstOrNull { it in on && it.kind != ChipKind.SEARCH } }
    val lit = remember { BringIntoViewRequester() }
    LaunchedEffect(Unit) {
        if (firstLit != null) {
            withFrameNanos { }
            runCatching { lit.bringIntoView() }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().height(HeaderRowHeight)) {
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .widthIn(min = maxWidth)
                .fillMaxHeight()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            for (chip in chips) {
                Box(if (chip == firstLit) Modifier.bringIntoViewRequester(lit) else Modifier) {
                    ListChip(chip, chip in on, calls, words = false, onTap)
                }
            }
        }
    }
}

/**
 * One chip of the list's narrowing, as the chip row draws it - an icon, its
 * meaning in words for a screen reader - or, with [words], as the side panel
 * does: the icon, then its name, where there is room for one.
 */
@Composable
private fun ListChip(chip: ListChip, selected: Boolean, calls: Boolean, words: Boolean, onTap: (ListChip) -> Unit) {
    val tap = { onTap(chip) }
    if (words) {
        WordChip(chip, selected, tap)
        return
    }
    when (chip.kind) {
        // Starred contacts, on top of the other chips.
        ChipKind.FAVORITES -> FilterChip(
            selected = selected,
            onClick = tap,
            shape = chipShape(),
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.secondary,
            ),
            label = {
                Icon(
                    chipVector(chip.kind),
                    contentDescription = "Only calls with favourite contacts",
                    tint = favouriteTint(selected),
                    modifier = Modifier.size(18.dp),
                )
            },
        )
        ChipKind.SOURCE -> SourceChip(chip.source.orEmpty(), selected, tap)
        ChipKind.KEPT -> IconFilterChip(
            selected,
            chipVector(chip.kind),
            if (calls) "Only calls whose recording is kept" else "Only recordings marked to keep",
            tap,
        )
        // The calls the answering machine took.
        ChipKind.MESSAGES -> IconFilterChip(selected, chipVector(chip.kind), "Only messages", tap)
        ChipKind.NOT_RECORDED -> IconFilterChip(selected, chipVector(chip.kind), "Only calls not recorded", tap)
        // The fold-out. A filter chip in looks, a disclosure in
        // job: selected while the field is open, so the icon
        // reads as "search is showing" rather than a filter that
        // is on.
        ChipKind.SEARCH -> FilterChip(
            selected = selected,
            onClick = tap,
            shape = chipShape(),
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.secondary,
            ),
            label = {
                Icon(
                    chipVector(chip.kind),
                    contentDescription = if (calls) "Search calls" else "Search recordings",
                    // An icon-only chip label does not inherit a
                    // visible content colour the way a text label
                    // does - untinted it came out invisible - so
                    // the tint is set for each state: onSecondary
                    // on the bright selected pill, onSurfaceVariant
                    // on the band.
                    tint = if (selected) {
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

/** A chip's icon, the row's and the side panel's alike; SOURCE's is the
 *  phone's - a messenger's chip wears its app's icon instead. */
private fun chipVector(kind: ChipKind): ImageVector = when (kind) {
    ChipKind.SEARCH -> Icons.Filled.Search
    ChipKind.FAVORITES -> Icons.Filled.Star
    ChipKind.SOURCE -> Icons.Filled.Call
    ChipKind.KEPT -> KeepIcons.Kept
    // The calls the answering machine took.
    ChipKind.MESSAGES -> messageIcon
    ChipKind.NOT_RECORDED -> CallIcons.NotRecorded
}

/** The favourites' star: gold on the band; a DEEPER gold once selected,
 *  where the pill turns pale in dark theme and the bright gold washed into
 *  it. */
@Composable
@ReadOnlyComposable
private fun favouriteTint(selected: Boolean) = if (selected) JemRecColors.favoriteSelected else JemRecColors.favorite

/** A chip with its name (chipName), for the side panel: the row's own icon
 *  first. */
@Composable
private fun WordChip(chip: ListChip, selected: Boolean, onClick: () -> Unit) {
    val name = chipName(chip)
    val source = chip.source.orEmpty()
    val icon: @Composable () -> Unit = if (chip.kind == ChipKind.SOURCE && source != PHONE_SOURCE) {
        val app = rememberAppIcon(packagesForTag(source), 18.dp)
        ({ if (app != null) Image(app, contentDescription = null, modifier = Modifier.size(18.dp)) })
    } else {
        {
            Icon(
                chipVector(chip.kind),
                contentDescription = null,
                tint = if (chip.kind == ChipKind.FAVORITES) favouriteTint(selected) else LocalContentColor.current,
                modifier = Modifier.size(18.dp),
            )
        }
    }
    FilterChip(
        selected = selected,
        onClick = onClick,
        shape = chipShape(),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondary,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondary,
            selectedLeadingIconColor = MaterialTheme.colorScheme.onSecondary,
        ),
        leadingIcon = icon,
        label = { ChipLabel(name) },
    )
}

/**
 * The line that says a list is narrowed - what narrows it, how many of how
 * many are shown - and Show all, which turns every chip off and folds the
 * search away. Pinned under the chips, in both tabs, upright or on its side.
 * A lit chip can be scrolled out of the row's sight, and was: on the Honor
 * (2026-10-23) Messages was lit past the screen's edge and the list showed 2
 * of 52 with nothing saying why - the user's worry, recordings that seem to
 * be gone. A of the fixes sketched, the user's pick. Heard as it changes.
 */
@Composable
internal fun NarrowingLine(by: List<String>, shown: Int, of: Int, onShowAll: () -> Unit) {
    if (by.isEmpty()) return
    val haptics = LocalHapticFeedback.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 40.dp)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "${by.joinToString(" · ")} · $shown of $of",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite },
        )
        TextButton(onClick = { haptics.tap(); onShowAll() }) { Text("Show all") }
    }
}

/**
 * One list's narrowing, as the header over it and the side panel beside it
 * draw it: the chips that would narrow it (chipsFor), the ones lit, the
 * search, and what a tap or a keystroke does. The Recordings tab's and the
 * Calls tab's, from [narrowingOf].
 */
internal class ListNarrowing(
    /** The Calls tab's, worded for it. */
    val calls: Boolean,
    val chips: List<ListChip>,
    val on: Set<ListChip>,
    val searchOpen: Boolean,
    val query: String,
    val tap: (ListChip) -> Unit,
    val search: (String) -> Unit,
)

/** [state]'s narrowing of the Calls tab, or with [calls] false, of the
 *  Recordings tab. The chips are worked out again only as the list or the
 *  lit chips change. */
@Composable
internal fun narrowingOf(state: UiState, vm: MainViewModel, calls: Boolean): ListNarrowing {
    val on = if (calls) state.callsNarrowing.chipsOn() else state.recordingsNarrowing.chipsOn()
    val chips = remember(if (calls) state.calls else state.recordings, on) {
        if (calls) chipsFor(state.calls.map { it.chipFacts() }, on) else chipsFor(state.recordings.map { it.chipFacts() }, on)
    }
    return ListNarrowing(
        calls = calls,
        chips = chips,
        on = on,
        searchOpen = if (calls) state.callsNarrowing.searchOpen else state.recordingsNarrowing.searchOpen,
        query = if (calls) state.callsNarrowing.query else state.recordingsNarrowing.query,
        tap = if (calls) vm::tapCallsChip else vm::tapRecordingsChip,
        search = if (calls) vm::setCallsQuery else vm::setQuery,
    )
}

/**
 * A list's pinned header, both tabs': [toolbar] on top (the Recordings
 * tab's selection), the chips, the search under them, the line that says
 * the list is narrowed, and the rule once rows pass under it. On a phone on
 * its side the chips and search are in the side panel instead
 * (NarrowingPanel), and only the line is here. Over a list with nothing in
 * it at all ([listed] false), none of it: the list's own words - "No
 * recordings yet" - say it all, on both tabs; a list narrowed to nothing
 * keeps its line. While selecting ([chipsShown] false), no chips - the
 * toolbar is in their place - but the search stays.
 *
 * The search takes the focus as it opens, so the keyboard is up and ready -
 * opening search only to have to tap the field as well would undo the point
 * of hiding it behind one tap.
 */
@Composable
internal fun NarrowingHeader(
    narrowing: ListNarrowing,
    shown: Int,
    of: Int,
    onShowAll: () -> Unit,
    aside: Boolean,
    listed: Boolean,
    chipsShown: Boolean,
    scrolled: Boolean,
    toolbar: @Composable () -> Unit = {},
) {
    val haptics = LocalHapticFeedback.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(narrowing.searchOpen, aside) {
        if (narrowing.searchOpen && !aside) runCatching { focus.requestFocus() }
    }
    Column(Modifier.keepsItsPlace().background(MaterialTheme.colorScheme.surface)) {
        toolbar()
        if (listed && !aside) {
            if (chipsShown) {
                ListChipRow(narrowing.chips, narrowing.on, narrowing.calls) { chip -> haptics.tap(); narrowing.tap(chip) }
            }
            // Below the header row, and only when asked for. Expands and
            // fades in together so it reads as unfolding from the chip, not
            // jumping into place.
            AnimatedVisibility(
                visible = narrowing.searchOpen,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                ListSearchField(narrowing.query, narrowing.search, focus, "Search by name or number")
            }
        }
        if (listed) NarrowingLine(narrowedBy(narrowing.on, narrowing.query), shown = shown, of = of, onShowAll = onShowAll)
        // The rule caps the header once rows are passing under it -
        // Material's app bars separate from content on scroll the same way.
        // At the top there is nothing to separate it from.
        if (listed && scrolled) HorizontalDivider()
    }
}

/**
 * The list's chips and search beside the list, on a phone on its side - the
 * side panel's middle (option B, the user's pick, 2026-10-23). The same chips
 * by the same rule as the row (chipsFor) and the same composable (ListChip),
 * named and wrapping, as the row has no room to and scrolls instead; the
 * search field under them. As in the row, the Recordings chips give way while
 * selecting, and the field stays.
 */
@Composable
internal fun NarrowingPanel(state: UiState, vm: MainViewModel) {
    val calls = state.homeTab == HomeTab.CALLS
    if (if (calls) state.calls.isEmpty() else state.recordings.isEmpty()) return
    NarrowingPanel(narrowingOf(state, vm, calls), chipsShown = calls || !state.selecting)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NarrowingPanel(narrowing: ListNarrowing, chipsShown: Boolean) {
    val haptics = LocalHapticFeedback.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(narrowing.searchOpen) {
        if (narrowing.searchOpen) runCatching { focus.requestFocus() }
    }
    Column(Modifier.padding(top = 6.dp)) {
        if (chipsShown) {
            FlowRow(
                Modifier.padding(end = 10.dp, bottom = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (chip in narrowing.chips) {
                    ListChip(chip, chip in narrowing.on, narrowing.calls, words = true) { tapped ->
                        haptics.tap()
                        narrowing.tap(tapped)
                    }
                }
            }
        }
        AnimatedVisibility(visible = narrowing.searchOpen, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            ListSearchField(narrowing.query, narrowing.search, focus, "Search by name or number")
        }
    }
}

/**
 * How the call came, as a chip: a phone for phone calls, a messenger's own
 * icon for its calls - or its name, where the icon cannot be had (the app
 * uninstalled since).
 */
@Composable
private fun SourceChip(source: String, selected: Boolean, onClick: () -> Unit) {
    if (source == PHONE_SOURCE) {
        IconFilterChip(selected, chipVector(ChipKind.SOURCE), "Only phone calls", onClick)
        return
    }
    val name = VoipApps.labelForTag(source) ?: source
    val icon = rememberAppIcon(packagesForTag(source), 18.dp)
    FilterChip(
        selected = selected,
        onClick = onClick,
        shape = chipShape(),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondary,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondary,
        ),
        label = {
            if (icon != null) {
                Image(icon, contentDescription = "Only $name calls", modifier = Modifier.size(18.dp))
            } else {
                ChipLabel(name)
            }
        },
    )
}

/**
 * A pinned header keeps its place: a request from inside it to be brought
 * into view - the search field's, as it takes focus when search opens -
 * stops here. Passed on, it reached the list, which scrolled to where the
 * header's item really is, the top: both lists jumped to their start as
 * search opened (found on the Honor, 2026-10-23; BehaviourTest
 * openingSearchLeaves...). Pinned, the header is on screen already, so there
 * is nothing to scroll to.
 */
internal fun Modifier.keepsItsPlace(): Modifier = this then KeepsItsPlace

private data object KeepsItsPlace : ModifierNodeElement<KeepsItsPlaceNode>() {
    override fun create() = KeepsItsPlaceNode()
    override fun update(node: KeepsItsPlaceNode) = Unit
    override fun InspectorInfo.inspectableProperties() {
        name = "keepsItsPlace"
    }
}

private class KeepsItsPlaceNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) = Unit
}

/** The list's search field, folded out under the chips: [placeholder] says
 *  what it searches. */
@Composable
internal fun ListSearchField(query: String, onQuery: (String) -> Unit, focus: FocusRequester, placeholder: String) {
    // A hand-built compact field, not the stock
    // OutlinedTextField. That one reserves 56dp - the M3
    // search-bar height - and forcing it shorter clips the
    // text. Driving the decoration box directly lets the
    // reserved padding shrink so a single line sits in 48dp,
    // the M3 minimum touch target and the shortest a field may
    // honestly be. The colours are the stock ones; the height
    // is ours, and the shape the look's chips' (chipShape): the
    // stock pill sat under Deck's keys and Multitrack's tags.
    // In Memo it is the same pill - 28dp corners on a 48dp
    // field were drawn 24dp, half its height, as a circle's.
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
        value = query,
        onValueChange = onQuery,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 12.dp)
            // No forced height: a fixed height taller than the
            // content parks the content at the top and leaves
            // the slack below, so the text sat high in the pill.
            // The content padding sizes it instead (see below),
            // which keeps the one line centred.
            .focusRequester(focus),
        textStyle = MaterialTheme.typography.bodyLarge.copy(
            color = MaterialTheme.colorScheme.onSurface,
        ),
        singleLine = true,
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        interactionSource = searchInteraction,
        decorationBox = { innerTextField ->
            OutlinedTextFieldDefaults.DecorationBox(
                value = query,
                innerTextField = innerTextField,
                enabled = true,
                singleLine = true,
                visualTransformation = VisualTransformation.None,
                interactionSource = searchInteraction,
                colors = searchColors,
                placeholder = { Text(placeholder) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        IconButton(onClick = { onQuery("") }) {
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
                        shape = chipShape(),
                        focusedBorderThickness = 1.dp,
                        unfocusedBorderThickness = 1.dp,
                    )
                },
            )
        },
    )
}

/** A filter chip's shape and words in the look: Memo's pill, Deck's key and
 *  Multitrack's tag, the last two in capitals of their mono. */
@Composable
@ReadOnlyComposable
private fun chipShape(): Shape = when (LocalLook.current) {
    Look.MEMO -> CircleShape
    Look.DECK -> RoundedCornerShape(8.dp)
    Look.MULTITRACK -> RoundedCornerShape(6.dp)
}

@Composable
private fun ChipLabel(label: String) {
    when (LocalLook.current) {
        Look.MEMO -> Text(label)
        Look.DECK -> Text(label.uppercase(), fontFamily = LookFonts.engraved, letterSpacing = 0.08.em)
        Look.MULTITRACK -> Text(label.uppercase(), fontFamily = LookFonts.mono, fontSize = 12.sp, letterSpacing = 0.08.em)
    }
}

/**
 * One height for the pinned header's top row, whichever it is.
 *
 * The chip row and the selection toolbar swap in the same slot, and a person
 * entering selection should see a toolbar appear, not the whole list lurch. The
 * two do not naturally agree - 32dp chips against 48dp icon buttons - so both
 * are pinned to this, tall enough for the buttons and centred either way. The
 * list stays exactly where it was.
 */
internal val HeaderRowHeight = 56.dp

/** A filter that is an icon alone - Kept, Messages - filled in the secondary
 *  colour while it is on: not the default `secondaryContainer`, which on the
 *  neutral header band sat too close to the band to read as on. `secondary`
 *  is a light tone in dark theme, so a picked chip becomes a bright pill that
 *  pops the way the ticked photos and checkbox do. */
@Composable
private fun IconFilterChip(selected: Boolean, icon: ImageVector, description: String, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        shape = chipShape(),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.secondary,
        ),
        label = {
            Icon(
                icon,
                contentDescription = description,
                tint = if (selected) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        },
    )
}
