package com.jemcik.jemrec.ui

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.jemcik.jemrec.MainActivity
import com.jemcik.jemrec.capture.RecorderSwitch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The Quick Settings tile: JemRec's on/off switch, without opening JemRec.
 *
 * The same switch as the one in the app's header, through the same path
 * (RecorderSwitch.turn), so the two can never disagree about what "off"
 * means. Each refreshes the other: the tile reads the switch whenever the
 * panel opens, and RecorderSwitch.set asks the tile to look again.
 *
 * Behind the lock screen. Turning a call recorder off, or on, is not
 * something whoever picks up a locked phone should be able to do, so a tap
 * on a locked phone asks for the unlock first.
 *
 * Before setup has finished there is nothing to switch, so the tile opens the
 * app, where setup is.
 */
class RecorderTile : TileService() {

    private companion object {
        /** Outlives the tile: Android unbinds it as soon as the panel closes,
         *  and a switch half-turned when that happened would leave the daemon
         *  recording under a switch that says off. */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override fun onStartListening() {
        show(RecorderSwitch.isOn(this))
    }

    override fun onClick() {
        if (isLocked) unlockAndRun { toggle() } else toggle()
    }

    private fun toggle() {
        if (!Setup.isComplete(this)) {
            openApp()
            return
        }
        val on = !RecorderSwitch.isOn(this)
        // Shown at once; the recorder catches up in the background.
        show(on)
        val app = applicationContext
        scope.launch {
            runCatching { RecorderSwitch.turn(app, on) }
                .onFailure { Log.w("JemRec", "tile: could not turn recording ${if (on) "on" else "off"}", it) }
        }
    }

    private fun show(on: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "JemRec"
        tile.subtitle = if (on) "Recording calls" else "Off"
        tile.contentDescription = if (on) "JemRec, recording calls" else "JemRec, off"
        tile.updateTile()
    }

    // The Intent form is deprecated, and throws for apps targeting Android 14+
    // - but it runs here only below 14, where the PendingIntent form does
    // not exist yet. Lint sees the call, not the version check around it.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }
}
