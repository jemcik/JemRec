package com.jemcik.jemrec.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
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
 * not wait on. A miss is kept too, so an uninstalled app is not asked about
 * again on every row.
 */
@Composable
internal fun rememberAppIcon(packages: List<String>, size: Dp): ImageBitmap? {
    val context = LocalContext.current
    val px = (size.value * context.resources.displayMetrics.density).toInt()
    val key = "${packages.joinToString(",")}@$px"
    return produceState(initialValue = icons[key]?.bitmap, key) {
        if (!icons.containsKey(key)) {
            icons[key] = Cached(withContext(Dispatchers.IO) {
                packages.firstNotNullOfOrNull { iconOf(context, it, px) }
            })
        }
        value = icons[key]?.bitmap
    }.value
}

/** The packages a file name's messenger tag can mean, in VoipApps' order -
 *  "whatsapp" is WhatsApp or WhatsApp Business, whichever is installed. */
internal fun packagesForTag(tag: String): List<String> = VoipApps.KNOWN.filter { it.tag == tag }.map { it.pkg }

/** A looked-up icon, or a looked-up absence of one. */
private class Cached(val bitmap: ImageBitmap?)

private val icons = ConcurrentHashMap<String, Cached>()

private fun iconOf(context: Context, pkg: String, px: Int): ImageBitmap? = runCatching {
    val drawable = context.packageManager.getApplicationIcon(pkg)
    Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888).also {
        drawable.setBounds(0, 0, px, px)
        drawable.draw(Canvas(it))
    }.asImageBitmap()
}.getOrNull()
