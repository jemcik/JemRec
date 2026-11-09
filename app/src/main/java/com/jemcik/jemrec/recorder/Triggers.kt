package com.jemcik.jemrec.recorder

/**
 * What wakes the recorder's lifecycle, and what each wake arms to keep the
 * recorder alive between calls: one table, where boot, the app and the
 * watches each armed their own few by hand. The arming is
 * RecorderLifecycle.arm; this is pure, and tested as a table.
 */
internal enum class Trigger {
    /** The phone has booted (BootReceiver). */
    BOOT,

    /** Wi-Fi is back: the system-held watch fired (NetworkReviveReceiver). */
    WIFI_BACK,

    /** The app's activity is created, or brought forward by an opening - a
     *  plain notification, the tile, the launcher with JemRec on top
     *  (MainActivity.cameIn). */
    APP_OPEN,

    /** The home screen's look at where setup stands, at every resume
     *  (RecorderAndSetup.recheckSetup). */
    SETUP_CHECK,

    /** JemRec turned on, from the app or the tile (RecorderSwitch.turn). */
    TURN_ON,

    /** JemRec turned off, from the app or the tile (RecorderSwitch.turn). */
    TURN_OFF,

    /** A keep-alive run: the heartbeat, a kick, the Wi-Fi run
     *  (RecorderKeepAlive). */
    KEEP_ALIVE,

    /** USB debugging changed (UsbDebuggingWatch): it arms the next watch
     *  itself, behind its own run (APPEND_OR_REPLACE), first thing. */
    USB_CHANGED,

    /** A cable went in or came out (CableWatch, woken by the recorder). */
    CABLE,

    /** The self-test found a recorder too old to say whether it hears
     *  calls (SelfTest): a revive replaces it. */
    SELF_TEST,

    /** A revive, whoever asked for it (RecorderLifecycle.revive): the gate
     *  every revive passes, after its caller's own. */
    REVIVE,
}

/** What keeps the recorder alive between calls. */
internal enum class Keeper {
    /** The watch on USB debugging (UsbDebuggingWatch). */
    USB_WATCH,

    /** The keep-alive's run every fifteen minutes. */
    HEARTBEAT,

    /** A keep-alive run now, unless one is waiting or running (KEEP). */
    KICK,

    /** The keep-alive's run for Wi-Fi coming back, under a name of its own. */
    WIFI_RUN,

    /** The system-held watch for Wi-Fi coming back (NetworkRevive). */
    WIFI_WATCH,
}

/**
 * What [trigger] arms, in the order it arms them; for On, after its revive.
 * Boot and the home screen's check arm everything but the Wi-Fi run, the
 * Wi-Fi watch included - on Wi-Fi it fires at once and is dropped, so a
 * Wi-Fi run follows (NetworkRevive); opening the app arms the heartbeat, the
 * USB watch and a kick, not the Wi-Fi watch, which the screen's check arms;
 * On arms all but a kick, as it has just revived; a keep-alive run puts back
 * the USB watch, which a force-stop clears; Wi-Fi coming back starts its own
 * run; the self-test, a kick. Off arms nothing and disarms nothing: what
 * keeps the recorder alive stays armed (RecorderSwitch.turn). The watches arm
 * nothing up front - what follows their revive is [afterFailedRevive] - and
 * nor does a revive itself.
 */
internal fun armedBy(trigger: Trigger): List<Keeper> = when (trigger) {
    Trigger.BOOT, Trigger.SETUP_CHECK -> listOf(Keeper.USB_WATCH, Keeper.HEARTBEAT, Keeper.KICK, Keeper.WIFI_WATCH)
    Trigger.APP_OPEN -> listOf(Keeper.USB_WATCH, Keeper.HEARTBEAT, Keeper.KICK)
    Trigger.TURN_ON -> listOf(Keeper.USB_WATCH, Keeper.HEARTBEAT, Keeper.WIFI_WATCH)
    Trigger.KEEP_ALIVE -> listOf(Keeper.USB_WATCH)
    Trigger.WIFI_BACK -> listOf(Keeper.WIFI_RUN)
    Trigger.SELF_TEST -> listOf(Keeper.KICK)
    Trigger.TURN_OFF, Trigger.USB_CHANGED, Trigger.CABLE, Trigger.REVIVE -> emptyList()
}

/**
 * After a watch's revive that left the recorder down - the USB watch's, or
 * the cable's last try: the Wi-Fi watch where there is no Wi-Fi, then a
 * kick. The keep-alive owns the retrying, and off Wi-Fi the Wi-Fi watch
 * brings it back the moment there is some.
 */
internal fun afterFailedRevive(onWifi: Boolean): List<Keeper> =
    listOfNotNull(Keeper.WIFI_WATCH.takeUnless { onWifi }, Keeper.KICK)

/**
 * What a trigger finds when it comes, read anew each time from the
 * preferences: the app sleeps between triggers, and nothing it held in
 * memory is still true when the next one wakes it.
 */
internal data class Gates(
    /** Setup finished, with an identity to revive with (RecorderLifecycle.isSetUp). */
    val setUp: Boolean,
    /** JemRec on (RecorderSwitch). */
    val switchOn: Boolean,
    /** The last Off switched something off that On has not put back
     *  (DebugSwitchesOff.switchedOff). */
    val recorded: Boolean,
    /** ...and the recorder with it: USB debugging among them. */
    val recorderGone: Boolean,
)

/** JemRec off with the recorder gone by choice: nothing may start it again
 *  until On. With JemRec on, a record left is an On cut short instead. */
internal fun standingDown(recorderGone: Boolean, switchOn: Boolean): Boolean = recorderGone && !switchOn

/** What a trigger may do, by its [Gates] ([admit]). */
internal sealed interface Admit {
    /** Nothing: not set up, or JemRec off where the switch is asked. */
    data object Skip : Admit

    /** Standing down ([standingDown]): no revive, no arming; an off the
     *  recorder left undone is finished (DebugSwitchesOff.finishOffLeftUndone). */
    data object StandDown : Admit

    /** On with it - having first put back what the last Off switched off,
     *  where [putBack] says so. */
    data class Go(val putBack: Boolean = false) : Admit
}

/**
 * Whether [trigger] goes on, by [gates]. The rules differ, and each has its
 * reason: boot and Wi-Fi coming back ask setup and not the switch - the
 * recorder is kept for an On anywhere, with no Wi-Fi to start it then; the
 * app's own (opening, the screen's check) ask the switch and not setup;
 * the keep-alive and the USB watch ask setup, then whether JemRec is
 * standing down; every revive asks that first, whoever asked for it, and
 * with JemRec on puts back what an On cut short left switched off; a turn
 * goes only if the switch still says what it was turned to; the cable's
 * watch, woken with the token, always.
 */
internal fun admit(trigger: Trigger, gates: Gates): Admit {
    val standingDown = standingDown(gates.recorderGone, gates.switchOn)
    return when (trigger) {
        Trigger.BOOT, Trigger.WIFI_BACK -> if (gates.setUp) Admit.Go() else Admit.Skip
        Trigger.APP_OPEN, Trigger.SETUP_CHECK -> if (gates.switchOn) Admit.Go() else Admit.Skip
        Trigger.KEEP_ALIVE, Trigger.USB_CHANGED -> when {
            !gates.setUp -> Admit.Skip
            standingDown -> Admit.StandDown
            else -> Admit.Go()
        }
        Trigger.REVIVE -> when {
            standingDown -> Admit.StandDown
            else -> Admit.Go(putBack = gates.switchOn && gates.recorded)
        }
        Trigger.TURN_ON -> if (gates.switchOn) Admit.Go(putBack = gates.recorded) else Admit.Skip
        Trigger.TURN_OFF -> if (!gates.switchOn) Admit.Go() else Admit.Skip
        Trigger.CABLE, Trigger.SELF_TEST -> Admit.Go()
    }
}

/** What a trigger does after its revive ([followUp]). */
internal data class FollowUp(
    /** What to arm, in order (RecorderLifecycle.arm). */
    val arm: List<Keeper> = emptyList(),
    /** Collect what the recorder still holds, then look for missed calls. */
    val collect: Boolean = false,
    /** Run again, with backoff. */
    val retry: Boolean = false,
)

/**
 * What [trigger] does once its revive has found the recorder [alive] or not,
 * [onWifi] or not. The keep-alive arms the Wi-Fi watch off Wi-Fi whatever it
 * found - off Wi-Fi is when the watch has something to wait for, and on
 * Wi-Fi it fires at once and is dropped (NetworkRevive) - collects only from
 * a recorder that is up, and tries again by [triesAgain]. The watches hand
 * over only a recorder still down ([afterFailedRevive]) and never try again
 * themselves. The rest do nothing after.
 */
internal fun followUp(trigger: Trigger, alive: Boolean, onWifi: Boolean, forWifi: Boolean = false): FollowUp =
    when (trigger) {
        Trigger.KEEP_ALIVE -> FollowUp(
            arm = listOfNotNull(Keeper.WIFI_WATCH.takeUnless { onWifi }),
            collect = alive,
            retry = triesAgain(alive, forWifi, onWifi),
        )
        Trigger.USB_CHANGED, Trigger.CABLE -> FollowUp(arm = if (alive) emptyList() else afterFailedRevive(onWifi))
        else -> FollowUp()
    }

/** Whether a keep-alive run that ended with the recorder down tries again
 *  with backoff. A kick or the heartbeat does, Wi-Fi or not: the retry is
 *  what brings the recorder back if nothing else does. The Wi-Fi run does
 *  only while it is on Wi-Fi: off it, the watch is armed again and will
 *  wake a new one, which a retry still waiting would be taken for, as the
 *  kick's was (RecorderKeepAlive.kickForWifi). */
internal fun triesAgain(alive: Boolean, forWifi: Boolean, onWifi: Boolean): Boolean =
    !alive && (!forWifi || onWifi)
