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

    /** The app's activity is created (MainActivity). */
    APP_OPEN,

    /** The home screen's look at where setup stands, at every resume
     *  (MainViewModel.recheckSetup). */
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
