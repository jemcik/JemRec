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
 * The player's earpiece button, drawn as the keep mark is: it shows where a
 * recording plays - Material's VolumeUp, outlined, on the loudspeaker, and
 * its Hearing, an ear, at the earpiece. The user's pick of three drawn
 * (2026-11-15): a phone handset first, which on the Honor was tapped for
 * Play, and in a call recorder can read as "call back".
 */
internal object EarpieceIcons {
    val Loudspeaker: ImageVector = pathIcon(
        "Loudspeaker",
        "M3,9v6h4l5,5V4L7,9H3zM10,8.83v6.34L7.83,13H5v-2h2.83L10,8.83zM16.5,12c0,-1.77 -1.02,-3.29 -2.5,-4.03v8.05" +
            "c1.48,-0.73 2.5,-2.25 2.5,-4.02zM14,3.23v2.06c2.89,0.86 5,3.54 5,6.71s-2.11,5.85 -5,6.71v2.06" +
            "c4.01,-0.91 7,-4.49 7,-8.77 0,-4.28 -2.99,-7.86 -7,-8.77z",
    )

    val Ear: ImageVector = pathIcon(
        "Earpiece",
        "M17,20c-0.29,0 -0.56,-0.06 -0.76,-0.15 -0.71,-0.37 -1.21,-0.88 -1.71,-2.38 -0.51,-1.56 -1.47,-2.29 -2.39,-3 " +
            "-0.79,-0.61 -1.61,-1.24 -2.32,-2.53C9.29,10.98 9,9.93 9,9c0,-2.8 2.2,-5 5,-5s5,2.2 5,5h2c0,-3.93 -3.07,-7 " +
            "-7,-7S7,5.07 7,9c0,1.26 0.38,2.65 1.07,3.9 0.91,1.65 1.98,2.48 2.85,3.15 0.81,0.62 1.39,1.07 1.71,2.05 " +
            "0.6,1.82 1.37,2.84 2.73,3.55 0.51,0.23 1.07,0.35 1.64,0.35 2.21,0 4,-1.79 4,-4h-2c0,1.1 -0.9,2 -2,2z" +
            "M7.64,2.64L6.22,1.22C4.23,3.21 3,5.96 3,9s1.23,5.79 3.22,7.78l1.41,-1.41C6.01,13.74 5,11.49 5,9" +
            "s1.01,-4.74 2.64,-6.36zM11.5,9c0,1.38 1.12,2.5 2.5,2.5s2.5,-1.12 2.5,-2.5 -1.12,-2.5 -2.5,-2.5 " +
            "-2.5,1.12 -2.5,2.5z",
    )
}

/** The Calls tab's marks, drawn as the keep mark is: Material's Block, for a
 *  call that connected and was not recorded. */
internal object CallIcons {
    val NotRecorded: ImageVector = pathIcon(
        "Not recorded",
        "M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM4,12c0,-4.42 3.58,-8 8,-8 " +
            "1.85,0 3.55,0.63 4.9,1.69L5.69,16.9C4.63,15.55 4,13.85 4,12zM12,20c-1.85,0 -3.55,-0.63 " +
            "-4.9,-1.69L18.31,7.1C19.37,8.45 20,10.15 20,12c0,4.42 -3.58,8 -8,8z",
    )
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
