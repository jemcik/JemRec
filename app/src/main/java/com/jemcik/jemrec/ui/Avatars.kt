package com.jemcik.jemrec.ui

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
internal fun SelectableAvatar(
    caller: String?,
    photo: Uri?,
    selected: Boolean,
    size: Dp,
    container: Color,
    content: Color,
    edge: Color?,
    onClick: () -> Unit,
) {
    val rotation by animateFloatAsState(
        targetValue = if (selected) 180f else 0f,
        animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
        label = "avatarFlip",
    )
    Box(
        modifier = Modifier
            .size(size)
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
            CallerAvatar(caller, photo, size, container, content, edge)
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

/**
 * Who the call was with, as a circle: the contact's photo where there is one
 * (rememberContactPhoto), else initials when there is a name, else a person's
 * outline - so every row has the same shape and the list does not go ragged
 * down its left edge where some calls happen to be in the contacts and others
 * are not.
 */
@Composable
internal fun CallerAvatar(
    caller: String?,
    photo: Uri?,
    size: Dp = 36.dp,
    container: Color = MaterialTheme.colorScheme.secondary,
    content: Color = MaterialTheme.colorScheme.onSecondary,
    edge: Color? = null,
) {
    val initials = initialsOf(caller)
    val bitmap = rememberContactPhoto(photo)
    // A SOLID circle, not a tonal container. secondaryContainer is a pale blue
    // and the cards are a pale grey, so on light theme the circle barely
    // separated from the card - a real contrast failure, since a container tone
    // is pale by design and cannot stand off a pale surface. The solid
    // secondary role does, in both themes, and its on-colour gives the glyph or
    // initials full contrast.
    // Multitrack's is its caller's colour, a tint with an edge.
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(container)
            .then(if (edge != null) Modifier.border(1.dp, edge, CircleShape) else Modifier),
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
            // Part of the picture, sized to the circle - not body text that
            // grows with the phone's font size. At twice the size "OB" no
            // longer fit a 36 dp circle and spilled out of it (the screen
            // tests' large-text screenshots showed it). The size labelLarge
            // has at the default font size, held there.
            val letters = with(LocalDensity.current) { 14.dp.toSp() }
            Text(
                initials,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = letters, lineHeight = letters),
                color = content,
                maxLines = 1,
                softWrap = false,
            )
        } else {
            // A person, not a phone: in the line under the name a phone now
            // says the call was a phone call (2026-10-23).
            Icon(
                Icons.Filled.Person,
                contentDescription = null,
                tint = content,
                modifier = Modifier.size(22.dp),
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
