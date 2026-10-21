package com.jemcik.jemrec.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The keep mark: a bookmark, filled when the recording is kept.
 *
 * Drawn here rather than taken from the icon library because the bookmark is
 * in material-icons-extended, thousands of vectors for the two we would use -
 * the same reason the app depends on -core only (see app/build.gradle.kts).
 * The paths are Material's own Bookmark and BookmarkBorder.
 */
internal object KeepIcons {

    val Kept: ImageVector = bookmark(
        "Kept",
        "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2z",
    )

    val NotKept: ImageVector = bookmark(
        "Keep",
        "M17,3H7c-1.1,0 -1.99,0.9 -1.99,2L5,21l7,-3 7,3V5c0,-1.1 -0.9,-2 -2,-2zM17,18l-5,-2.18L7,18V5h10v13z",
    )

    private fun bookmark(name: String, path: String): ImageVector = pathIcon(name, path)
}

/**
 * A 24dp icon from one of Material's path strings - for the few icons the app
 * draws itself (here, and SettingsIcons) rather than pulling in
 * material-icons-extended.
 */
internal fun pathIcon(name: String, path: String): ImageVector =
    ImageVector.Builder(
        name = name,
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = PathParser().parsePathString(path).toNodes(),
        // Tinted by whoever draws it; black is only the mask.
        fill = SolidColor(Color.Black),
    ).build()
