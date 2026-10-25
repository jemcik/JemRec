package com.jemcik.jemrec.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.core.graphics.createBitmap
import com.jemcik.jemrec.capture.VoipApps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * A messenger's own icon, as installed on this phone, or null if none of the
 * given packages is installed or its icon cannot be read.
 *
 * Read off the main thread and kept for the life of the process: a list row
 * is composed again each time it scrolls back into view, and asking the
 * package manager and drawing the icon every time is work a scroll should
 * not wait on. A miss is not kept: an app installed later shows its icon the
 * next time the row is drawn.
 */
@Composable
internal fun rememberAppIcon(packages: List<String>, size: Dp): ImageBitmap? {
    val context = LocalContext.current
    // LocalDensity, not context.resources: it follows a configuration change.
    val px = (size.value * LocalDensity.current.density).toInt()
    val key = "${packages.joinToString(",")}@$px"
    return produceState(initialValue = icons[key], key) {
        value = icons[key] ?: withContext(Dispatchers.IO) {
            packages.firstNotNullOfOrNull { iconOf(context, it, px) }
        }?.also { icons[key] = it }
    }.value
}

/** The packages a file name's messenger tag can mean, in VoipApps' order -
 *  "whatsapp" is WhatsApp or WhatsApp Business, whichever is installed. */
internal fun packagesForTag(tag: String): List<String> = VoipApps.KNOWN.filter { it.tag == tag }.map { it.pkg }

private val icons = ConcurrentHashMap<String, ImageBitmap>()

/** Empties the cache - for the screen tests, which share one process and
 *  would otherwise see an app's icon after it was "uninstalled". */
internal fun forgetAppIcons() = icons.clear()

/**
 * Locked on the drawable because the same one can be handed out twice: the
 * home screen asks for an icon at two sizes at once (its chip, 18 dp, and the
 * row, 16 dp), each on an IO thread, and one's setBounds landed between the
 * other's setBounds and draw - a circle cut off at the right and foot, seen in
 * a screen test on 2026-10-25, whose fake package manager returns one
 * instance. A phone's normally gives a fresh drawable per call; the lock costs
 * nothing then.
 */
private fun iconOf(context: Context, pkg: String, px: Int): ImageBitmap? = runCatching {
    val drawable = context.packageManager.getApplicationIcon(pkg)
    createBitmap(px, px).also {
        synchronized(drawable) {
            drawable.setBounds(0, 0, px, px)
            drawable.draw(Canvas(it))
        }
    }.asImageBitmap()
}.getOrNull()
