package com.jemcik.jemrec.ui.theme

import android.app.UiModeManager
import android.content.Context
import androidx.core.content.edit
import com.jemcik.jemrec.Prefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The app's three looks (Settings > Theme). They draw the same screens from
 * the same state: only the colours, the type and the home screen's own pieces
 * - its header, its rows, its player - differ.
 *
 * Memo is the default, and what anyone updating keeps: it is the closest to
 * the app as it was.
 */
enum class Look { MEMO, DECK, MULTITRACK }

/**
 * Light or dark: the phone's choice, or the user's own for this app.
 *
 * These three are what Android's guide to dark theme recommends for an in-app
 * choice, the phone's own being the default (developer.android.com, "Implement
 * dark theme", Change themes in-app, read 2026-10-23).
 */
enum class LightDark { PHONE, LIGHT, DARK }

data class LookChoice(val look: Look = Look.MEMO, val lightDark: LightDark = LightDark.PHONE)

/**
 * Where the choice is kept, and how a change reaches the screens.
 *
 * A StateFlow rather than a read at start: both activities draw from it, and
 * a theme picked in Settings repaints the app at once rather than at the next
 * launch.
 */
object Looks {
    private const val KEY_LOOK = "look"
    private const val KEY_LIGHT_DARK = "light_dark"

    @Volatile private var flow: MutableStateFlow<LookChoice>? = null

    /** Read once a process - and Android told its light or dark then too:
     *  told only on a change, its own copy could differ from the setting
     *  (the setting written by hand, as tools/shoot.py does, or brought back
     *  by a restore), and the app opened in Android's. */
    private fun state(context: Context): MutableStateFlow<LookChoice> =
        flow ?: synchronized(this) {
            flow ?: MutableStateFlow(read(context)).also {
                flow = it
                tellAndroid(context, it.value.lightDark)
            }
        }

    fun choice(context: Context): LookChoice = state(context).value

    fun changes(context: Context): StateFlow<LookChoice> = state(context).asStateFlow()

    fun set(context: Context, choice: LookChoice) {
        val before = state(context).value
        Prefs.of(context).edit {
            putString(KEY_LOOK, choice.look.name)
            putString(KEY_LIGHT_DARK, choice.lightDark.name)
        }
        state(context).value = choice
        if (choice.lightDark != before.lightDark) tellAndroid(context, choice.lightDark)
    }

    /**
     * Android 12 and later keep a light or dark of each app's own, and draw
     * its launch screen in it - without this the app would open in the
     * phone's mode for a moment and then turn (the same guide, "Change themes
     * in-app": setApplicationNightMode from API 31). minSdk is 31, so it is
     * always there. Android keeps it across restarts itself.
     */
    private fun tellAndroid(context: Context, lightDark: LightDark) {
        val mode = when (lightDark) {
            LightDark.PHONE -> UiModeManager.MODE_NIGHT_AUTO
            LightDark.LIGHT -> UiModeManager.MODE_NIGHT_NO
            LightDark.DARK -> UiModeManager.MODE_NIGHT_YES
        }
        runCatching { context.getSystemService(UiModeManager::class.java)?.setApplicationNightMode(mode) }
    }

    private fun read(context: Context): LookChoice {
        val prefs = Prefs.of(context)
        return LookChoice(
            look = runCatching { Look.valueOf(prefs.getString(KEY_LOOK, null)!!) }.getOrDefault(Look.MEMO),
            lightDark = runCatching { LightDark.valueOf(prefs.getString(KEY_LIGHT_DARK, null)!!) }
                .getOrDefault(LightDark.PHONE),
        )
    }
}
