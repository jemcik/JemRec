# How JemRec works

The design, the measurements behind it, and every feature in depth. The
[README](../README.md) is the short version; this is the long one, for anyone
who wants to know why a call recorder for unrooted Android is built the way
this one is - or to check that it does what it says.

**Contents**

- [The fact and the obstacle](#the-fact-and-the-obstacle)
- The recorder: [ADB host and device](#the-phone-is-both-adb-host-and-adb-device) ·
  [bootstrap, not transport](#adb-is-a-bootstrap-not-a-transport) ·
  [the daemon records](#the-daemon-records-the-app-collects) ·
  [the authenticated socket](#the-socket-is-authenticated-in-both-directions) ·
  [staying alive without Wi-Fi](#staying-alive-without-wi-fi) ·
  [starting without Wi-Fi](#starting-without-wi-fi) ·
  [unplugged from a computer](#unplugged-from-a-computer) ·
  [after a restart](#after-a-restart) ·
  [stopped in the background](#stopped-in-the-background)
- The features: [recording and being asked](#recording-and-being-asked) ·
  [exact call tracking](#exact-call-tracking) ·
  [whose calls to keep](#whose-calls-to-keep) · [file names](#file-names) ·
  [messenger calls](#messenger-calls) ·
  [phone calls through the mixer](#phone-calls-that-play-through-the-mixer) ·
  [Google Meet and other apps' calls](#google-meet-and-other-apps-calls-through-telecom) ·
  [the answering machine](#the-answering-machine) ·
  [the Calls tab](#the-calls-tab) · [playing a recording](#playing-a-recording) ·
  [playing in the background](#playing-in-the-background) · [three looks](#three-looks) ·
  [keeping and deleting](#keeping-deleting-and-cleaning-up) ·
  [marks inside the recording](#marks-inside-the-recording) ·
  [notifications](#after-the-call-and-when-something-is-wrong) ·
  [the last call's report](#the-last-calls-report)
- The phone: [permissions](#what-it-does-not-ask-for) ·
  [what stays switched on](#what-stays-switched-on) ·
  [off, and debugging with it](#off-and-debugging-with-it)

## The fact and the obstacle

The whole design follows from one fact and one obstacle.

**The fact.** Android's `voice-call` audio source carries both sides of a call,
and reading it needs `CAPTURE_AUDIO_OUTPUT` and
`CAPTURE_VOICE_COMMUNICATION_OUTPUT` — signature-level permissions no ordinary
app can hold. But `com.android.shell`, the UID an ADB session runs as, holds
them both.

**The obstacle.** Getting shell privilege normally means a computer, or root, or
a second app like Shizuku that you keep alive. JemRec needs none of them,
because the phone can be its own computer.

## The phone is both ADB host and ADB device

The app embeds an ADB client. It pairs, over `127.0.0.1`, with the phone's own
Wireless debugging — the same SPAKE2 pairing a laptop does, with the six-digit
code, except both ends are on the same handset. Nothing leaves the phone, and no
network is involved beyond the loopback interface.

## ADB is a bootstrap, not a transport

The session exists for a few seconds. In it the app runs `pm grant` to give
*itself* `WRITE_SECURE_SETTINGS`, allow-lists a notification listener if you
asked it to hide Android's debugging notification, and launches a small Java
daemon with `app_process` from a jar it carries as an asset. Then it closes the
session. The *session* is short; the debugging switches stay on — see
[What stays switched on](#what-stays-switched-on).

That matters because ADB is not there when you need it. With Wi-Fi off, `adbd`
tears its listener down completely, and the shell UID may not pin a port — so
anything that needed ADB at the moment a call arrives would simply not record.
A process spawned from an ADB shell, on the other hand, **outlives the
connection**. Start it once; talk to it forever over a loopback socket that
needs no network at all.

## The daemon records; the app collects

The daemon watches the call state itself and writes the call to a file. The app
is not on the audio path at all.

That split was forced by measurement. When the app muxed the live audio, this
phone's process manager froze it while the dialer was in front, its socket
reader stalled, and the tail of every call was lost: a 76.7-second call arrived
as 14 to 44 seconds. The daemon runs as shell, outside app management, and is
never frozen. Afterwards the app fetches the finished file at whatever pace the
system allows — a freeze there costs a slower copy, never a lost second.

## The socket is authenticated in both directions

Loopback is shared by every process on the phone, so the daemon's port would
otherwise let any app fetch a recording, delete one, or open a live capture of
the call in progress. Each connection now proves itself with an HMAC exchange
over a token the app generated and handed to the daemon in its environment:

```
app    ->  'A' + client nonce
daemon ->  daemon nonce + HMAC(token, "jemrec-daemon" | nonces)
app    ->  HMAC(token, "jemrec-client" | nonces) + command
```

The daemon proves itself **first**, because anything can bind that port while
the daemon is down and a scheme that sent the token as a password would hand it
over. The token never crosses the socket, and both nonces are fresh, so nothing
can be replayed. A bare ping needs no handshake and answers with the protocol
version and the daemon's build hash — which is how the app spots a daemon left
behind by an older install, and replaces it.

## Staying alive without Wi-Fi

Losing Wi-Fi restarts `adbd`, and the restart kills everything `adbd` spawned —
including the recorder. The counter is one setting: with USB debugging enabled
(the setting, no cable involved) the framework keeps `adbd` alive through the
loss of the wireless transport. The daemon minds that switch itself, every ten
seconds, because this phone turns it off on its own.

Proven on the device: no cable, Wi-Fi off, a real call recorded while `adbd` and
the daemon kept their process IDs across the outage.

**Why it turns off on its own** was found on 2026-10-26, on the Honor (Android
17) with no cable: the phone's own Settings app writes USB debugging off each
time the Developer options screen is shown — 21 to 60 ms after it opens, again
on coming back to it from Wireless debugging, and again on unlocking the phone
onto it. Four times out of four. On this phone that stops `adbd` at once,
Wireless debugging on or not, and the recorder with it, so the daemon's own
ten-second check never gets to answer. So the app asks Android to wake it when
the setting changes (`UsbDebuggingWatch`), and then turns the switch back on and
starts the recorder itself - not by kicking the keep-alive, which a keep-alive
still waiting out a failed attempt's backoff ignores (found on the Android 17
emulator). With a cable in, that took about six seconds.
Without one, Honor holds every background app's scheduled work while the phone
is in use and lets it run for the app in front: the recorder came back when
JemRec was opened, or — with the phone locked straight after — 37 seconds after
the switch went off. Off Wi-Fi nothing can bring it back until there is Wi-Fi -
unless *Start without Wi-Fi* has opened its port (next section) - so on such a
phone Developer options is best left alone away from it.

**Any joined Wi-Fi counts, internet or not.** A Wi-Fi without internet - a
car's, a hotel's before its login page, another phone's hotspot with no data -
stays joined while Android sends everything else over mobile data, and
Wireless debugging, which asks only whether Wi-Fi is joined, works on it. The
app asked whether Android's *default* network was Wi-Fi, and so took such a
Wi-Fi for none: on the Android 12 emulator, 2026-10-28, with the Wi-Fi's
internet blocked, it stayed joined while mobile data became the default, and
the revive stopped at "not on Wi-Fi". It now asks whether any joined network
is Wi-Fi (`NetworkRevive.onWifi`); the same emulator, with port 5555 blocked
for the app so that only Wireless debugging over that Wi-Fi could do it, had
the recorder back.

When the daemon does die — a reboot kills it outright, unplugging the phone
from a computer can (see the next section), and the system reclaims it
eventually — it has to be started again, and starting it is the one operation
that needs ADB and therefore Wi-Fi, or the port of [Starting without
Wi-Fi](#starting-without-wi-fi). A scheduled job and a system-held Wi-Fi
watcher do it without you: the app is woken when a network appears, rebuilds the
recorder in a few seconds, and stands the ADB session back down.

Both of those have to survive a reboot, and both are armed by the boot
broadcast — so on a phone whose ROM declines to start the app at boot, neither
exists and the repair never begins. That is the case in [After a restart](#after-a-restart), and it is why the
manual says to open the app once after a restart rather than trusting the
machinery. **Between the restart and that moment nothing is recorded**, which is
why the header says so plainly rather than looking healthy - and after two
minutes of it, a notification does too.

## Starting without Wi-Fi

**Starting the recorder takes an adb connection, and the only one a phone
offers an app on its own is Wireless debugging - which Android allows only on a
Wi-Fi network the phone has joined.** `AdbDebuggingManager` switches it off
with "Not connected to any wireless network. Not enabling adbwifi." whenever
the phone has no Wi-Fi client connection, and the phone's own hotspot is not
one (read in AOSP's source, and measured on the Honor, 2026-09-09). So after a
reboot, or after *Turn all debugging off*, nothing could start the recorder
until there was Wi-Fi - and someone travelling often has none (issue #37).

`adbd` has a second door, the one `adb tcpip` opens: its `tcpip:5555` service
sets `service.adb.tcp.port` and restarts `adbd`, and from then on every start
of `adbd` listens on port 5555 too - on every interface, loopback included,
Wi-Fi or not - until a reboot clears the property. Only `init`, `adbd` and
`system_server` may set it (AOSP's sepolicy), so the shell cannot, but any adb
client can ask `adbd` to. Settings → *Start without Wi-Fi*
(`StartWithoutWifi`), off until switched on, has JemRec ask, once each boot,
over Wireless debugging; after that the recorder can be started over
`127.0.0.1:5555` with no network at all. Apps may read the property
(`get_prop(appdomain, adbd_config_prop)`), which is how JemRec knows the port
will be there while `adbd` is stopped and nothing can answer on it.

Measured on the Honor (MagicOS 11, Android 17), 2026-10-28, off the cable:

- With the port open and Wi-Fi off, *Turn all debugging off* from the tile
  stopped `adbd` and the recorder; On from the tile, 38 seconds later,
  switched USB debugging back on, `adbd` came back on 5555 with the property
  kept, took JemRec's paired key there without asking, and the recorder was
  up 6.1 seconds after the tap. A call half a minute later, Wi-Fi still off,
  was recorded both sides (16.5 s, -26 dB each).
- Unplugged from a computer with Wi-Fi off, the recorder died with `adbd`
  5 seconds later; the revive switched Wireless debugging off and on, which
  had Android start `adbd` with no Wi-Fi, connected on 5555, and the recorder
  was back 16.6 seconds after the cable came out.
- The switch, on Wi-Fi: on, `adbd` answered "restarting in TCP mode port:
  5555", listened 1.1 seconds later, and the recorder was back within 5
  seconds; off, `usb:` restarted `adbd` before its own answer could arrive
  ("Stream closed."), and the property was cleared all the same.
- The first command after any reconnect failed "Stream closed": the app kept
  its shell from the connection before, a stream of the dead one. A new
  connection now drops it (`AdbTransport.forgetShell`).

**When the port is opened and closed.** Changing it restarts `adbd`, and the
recorder with it, so it is changed only where the recorder can be started
again at once: on Wi-Fi, never in a call - and not while *When JemRec is off*
has debugging switched off, which a revive would have to switch Wireless
debugging on for; the change waits for On. After a reboot the first start opens
it before starting the recorder, so nothing restarts twice. Switched on while
the recorder runs, the next revive - the switch's own, at once on Wi-Fi -
opens it and restarts the recorder; switched off, the next revive on Wi-Fi
closes it (`usb:`), but only a port JemRec opened this boot: one a computer
opened with `adb tcpip` is the computer's - and never over the port itself: on
a Wi-Fi Android has not allowed Wireless debugging on, a revive's connection
falls back on 5555, and closing it would leave nothing to start the recorder
over until another Wi-Fi. On such a Wi-Fi, switching Wireless debugging on
for a change has Android ask "Allow wireless debugging on this network?", as
it does for any revive there. Android 17 leaves the switch on after Cancel and
does not ask again; Android 12 keeps it off while it asks and after Cancel, so
each keep-alive asked again while a change waited. Measured on the Android 12
emulator, 2026-10-28: refused, the switch read 0 with the question up; allowed,
it read 1 - for 25 seconds after *Turn all debugging off* and back on, and
after a revive's own switch-on. So a switch-on that reads off again is taken
for a refusal of that Wi-Fi connection (`NetworkRevive.noteWirelessRefused`,
keyed to the boot and the network): a revive for the port alone waits 20
seconds for Allow, and no revive switches it on again on that connection - as
on Android 17, which leaves the switch on and never asks twice - until it is
found on after all, an Allow tapped later on the question that stayed up,
which ends the refusal. Asking again
is left to "Allow this Wi-Fi", in the header and the alert, and to Start
without Wi-Fi's switch flipped by hand, whose user is there to answer.
Measured on the Android 12 emulator, 2026-10-28, with port 5555 blocked for
the app so that nothing but Wireless debugging could start the recorder:
before, with JemRec open, each revive asked again over it (and, by the code,
the cause could not be "not allowed", which wanted the switch on, so the
header offered no Allow this Wi-Fi); now the revive left the
switch alone, the header said "Tap to allow this Wi-Fi", the tap put the
question up, and Allow had the recorder back 6 seconds later. The alert,
posted by the keep-alive's own retry 237 seconds after the recorder went
down, said "Android has not allowed Wireless debugging on this Wi-Fi" with
its "Allow this Wi-Fi" button; tapped in the notification shade, it put the
question up, Allow had the recorder back 6 seconds later, and the alert went
with it. And the switch
flipped by hand after a refusal noted in the background asked at once, and
Allow had the port open 14 seconds after the flip. An ask after which the property
does not say 5555 is not repeated until the next boot, and the switch says so -
as for a close after which it still does, which leaves the port JemRec's to
show, open until a restart (an ask lost on a connection that had just died);
a slow `adbd` is not taken for that, and where the property cannot be read,
`adbd` answering on the port is. The reconnect after a change switches
Wireless debugging back on where `adbd`'s restart left it off: on the Honor,
2026-10-28, it read 0 after one open of the port and 1 after another. Start
fresh closes it, over the port itself, under the recorder's lock, read back
as a revive's close is. Off Wi-Fi with the port open, every revive - the
keep-alive, a cable from a computer, On after *Turn all debugging off* -
connects on it rather than standing down.

**What it costs.** Port 5555 is open on every network the phone joins - home,
a hotel's Wi-Fi, mobile data - whenever `adbd` runs, which is whenever USB
debugging is on: while JemRec is on, and while it is off unless *Turn all
debugging off* goes with it. *Hide Developer options*, the first choice for a
bank, leaves USB debugging on, so the port stays open while JemRec is off; the
*When JemRec is off* screen says so while the switch is on. `adbd` can be kept
to loopback only through `service.adb.listen_addrs`, which only
`system_server` may set. A computer that reaches the port - or any app on the
phone, at `127.0.0.1` - still needs its key accepted on the phone, but it can
put the question on screen; Wireless debugging, which takes pairing, never asked
about unknown keys. And `adbd` announces the port on every network the phone
joins, over mDNS, as `_adb._tcp` named after the phone's serial number
(`daemon/mdns.cpp`): measured on the Honor, 2026-10-28, a Mac on its network saw
`adb-<serial>` offered on 5555 while the port was open, and no more once it was
closed. Wireless debugging announces that name too, but only on a Wi-Fi it is
allowed on; the port's announcement goes out on a hotel's as well, which makes
the phone easy to find there. With all
debugging off, `adbd` is stopped and nothing listens. Uninstalling leaves the property
until the next reboot, with USB debugging off, so nothing listens then either
unless debugging is switched on again by hand. That is why it is a switch of
its own, off by default, and why its summary says the port is open whichever
way it is set.

**What it does not do.** After a reboot the first start still needs Wi-Fi: the
property is gone, and the one property that would outlive a reboot,
`persist.adb.tcp.port`, takes root (Shizuku-Next's own code says a stock
Samsung refuses it). There is a way around that on some phones, a race: with
the phone's own hotspot up, writing Wireless debugging on every 20 ms or so
until one write lands before the framework takes it back (found by Shizuku
users in March 2026, confirmed on Galaxy S22 Ultra, S23, S23+ and M34). It
depends on a bug, and it fails on the Honor: 1682 offs and ons in 120 seconds,
hotspot up, and `adbd` never opened its port once - the framework checked for
Wi-Fi before telling `adbd` to start, every time (2026-10-27). It is not built.

## Unplugged from a computer

**Pulling the cable out of a computer stops the recorder, and JemRec starts it
again by itself.** Measured on the Honor (Android 17), 2026-10-24: three
seconds after the cable came out - Android's debounce of a USB disconnect -
the phone took USB debugging off the cable's functions and `adbd` was stopped.
The recorder was started through `adbd`, so it is in `adbd`'s cgroup, and
Android kills the whole group: the recorder went at the same instant. Shell
cannot move a process out; every `cgroup.procs` belongs to the system. Then
nothing: `adbd` stayed stopped with both debugging switches still on, there was
nothing to connect to, and the app took that for a Wi-Fi Android had not
allowed. The day it was found, that cost 74 minutes of calls.

So the recorder watches the USB port, once a second, and spends its last three
seconds waking the app, which zygote started in a cgroup of its own and which
lives through it. The app watches for eight seconds. If the recorder goes, it
switches Wireless debugging off and on - the one thing that had Android start
`adbd` again - and starts the recorder. Measured on the same phone with the
screen on: recording again 12 and 16 seconds after the cable came out in the
two runs that were timed, nothing tapped, and back by itself in a third. Off
Wi-Fi, the next revive on Wi-Fi does the same.

**With the screen off it needs JemRec allowed to run on its own** - the
setting [After a restart](#after-a-restart) is about. A cable usually comes out
with the screen off. On *Manage manually*, measured: recording again 13
seconds after the unplug, JemRec started from nothing by the wake and done two
seconds later. On *Manage automatically*, Honor's power manager freezes JemRec,
foreground service or not, and takes its wake lock away; its own log says so.
Measured 2026-10-25: unfrozen for the recorder's wake at 00:10:09.72,
`freeze pid=... reason=PG` with the wake lock released half a second later,
three seconds' thaw when JemRec's notification appeared, then frozen until the
phone was plugged in at 00:14:46 - and nothing an app may do outranks that.
The same happened in two earlier runs, once before the recorder was started
and once after. The watch keeps the phone awake while it works, a minute and a
half at most, because a foreground service keeps its process and not the CPU.

The setting decides the keep-alive too: on *Manage automatically* the
15-minute job waited on a condition of Honor's own, `HN_USER_EXPERIENCE`, that
was not met; switched to *Manage manually*, it was met at once.

A wall charger, plugged in or pulled out, stopped nothing, and the app's watch
ends before Android would show its notification. Plugging into a computer
stopped nothing either, the three times it was measured; the watch covers it anyway.

The recorder polls rather than listens: Android refuses a broadcast receiver to
a process it did not start ("registerReceiverWithFeature: no app for null", on
the Android 17 emulator), as it refuses it a settings observer. The shell may
read the USB port's status, a binder call of about 0.2 ms, and that changes
the moment the cable does.

## After a restart

**A reboot kills the recorder, and building it again takes a few seconds of
Wi-Fi.** Whether that happens without you comes down to one phone setting.

**Allow JemRec to start automatically.** JemRec's Settings has a *Survive a
restart* row that opens the right screen on your phone; on an Honor
that is Settings → Battery → App launch → JemRec → *Manage manually*, with
*Auto-launch* on. Measured on that phone: the app started at boot, and the
recorder was back **ten seconds** after Wi-Fi came on, with the app never
opened.

**Without it, nothing happens by itself.** Same phone, auto-launch off: the
system declined to start the app for the boot broadcast — `don't meet
cpuload`, in its own log — so neither the scheduled job nor the Wi-Fi watcher
was ever armed, and seven minutes after Wi-Fi returned nothing had come back.
**Opening JemRec once** had it recording sixteen seconds later. That is the
fallback, and it always works.

**A restart can leave Wireless debugging switched on and not running.**
Measured on the Honor (MagicOS 11, Android 17), 2026-10-28, with no cable:
after the restart both switches read on and Android believed both on, but
adbd was not running - `AdbDebuggingManager` logged "adbd_auth domain socket
unavailable" once a second. Every revive found nothing advertised; the header
said *Tap to allow this Wi-Fi*, for a Wi-Fi allowed long before; and the
recorder stayed down until that tap switched Wireless debugging off and on.
adbd was up two seconds later, the recorder a minute after that, at the
keep-alive's next try. So a revive now reads whether adbd runs, from init's
own note of it (`init.svc.adbd`, `AdbdState`) - readable by apps, measured on
the Honor and the Android 12, 13, 16 and 17 emulators - and with adbd stopped
switches Wireless debugging off and on itself. The same phone rebooted with
that build: JemRec's first try, as the phone was unlocked, read adbd stopped,
switched it, and had the recorder up **eleven seconds** later, the app never
opened. Where the note cannot be read, a revive that finds nothing switches it
off and on once per Wi-Fi connection: that starts an adbd not running, and on
a Wi-Fi Android has not allowed it asks once (`cycleAfterNotFound`). *Tap to
allow this Wi-Fi* is now said only with adbd running, or after that once
(`RecorderHealth.notAllowed`), and the tap starts the recorder itself, waiting
for a start under way instead of cutting it off.

Wi-Fi is needed to *rebuild* the recorder, never to use it. Once it is up,
recording carries on with Wi-Fi off, on mobile data, anywhere.

## Stopped in the background

The same App launch setting decides more than a reboot. Some phones stop apps
in the background to save power. The recorder runs on as the shell user, but a
stopped JemRec cannot start it again once it goes — after an unplug or a
restart — so calls go unrecorded until the app is opened.

**Measured on an Honor (MagicOS 11, Android 17, 2026-10-25).** With App launch
on *Manage automatically*, turning the screen off had the system **kill**
JemRec eight seconds later — mid-service, `reason=13 (OTHER KILLS BY SYSTEM)`,
`iAwareR[SmartClean]` — and then mark it *stopped*, which cancels its keep-alive
job, so nothing brought the recorder back. On *Manage manually* the same test
left it running and the recorder returned on its own in **fifteen seconds**.

JemRec cannot read or change that setting, so it does three things around it,
each only on a phone that has such a screen (never a Pixel):

- **Asks during setup.** A step offers to open the phone's own screen for it,
  before the first call rather than after the first lost one. It is asked once
  — JemRec has no way to check the setting — and blocks nothing. The card and
  the warnings name that screen and say what to do on it in its maker's words
  ([`AutoStart.instructionFor`](../app/src/main/java/com/jemcik/jemrec/ui/AutoStart.kt)):
  *Manage manually* in an Honor's or Huawei's App launch, a switch in a
  Xiaomi's Autostart, OPPO's startup manager or vivo's, *Never sleeping apps*
  on a Samsung's battery page. Honor's is measured; the others follow
  dontkillmyapp.com and have not been tried on those phones.
- **Warns the moment it happens.** The daemon runs as the shell user in its own
  session, which the power manager does not freeze, so it is left watching when
  the app is killed. It reads `dumpsys activity exit-info`
  ([`Checks.backgroundKillStamp`](../shellserver/src/com/jemcik/jemrec/shell/Checks.java)):
  a newest exit that was a system kill while JemRec was working, or one that
  left it *stopped*, wakes the app to post a notification. Not every system
  kill: reason 13 is also how Android reclaims an idle app, and on the same
  Honor 2,015 of its 2,062 such records were idle apps cleaned up
  (importance 400, "iAwareK", "too many empty"), none left stopped. That costs
  JemRec nothing — the recorder runs on and wakes it for the next call — so
  it is not told. A stopped app loses its scheduled jobs and the boot
  broadcast. The daemon reads that flag directly; an app running again is no
  longer stopped, so the app trusts its wake. The user's own Force stop never
  counts.
- **Warns when JemRec next starts.** At every start of its process, and
  again at every open, the app reads its *own* exit records
  (`getHistoricalProcessExitReasons`, the same decision in
  [`BackgroundKill`](../app/src/main/java/com/jemcik/jemrec/capture/BackgroundKill.kt)),
  as a net for a stop the daemon's wake did not land — the recorder down, say.
  Starting clears the stopped flag, but from Android 15 Android's record of
  the process's own start says whether it began stopped
  (`ApplicationStartInfo.wasForceStopped`). Measured on the same Honor: the
  open at about 14:00, eight minutes after the 13:52 kill and with no exit
  between, was marked so. And after a Force stop, Android started JemRec
  again for its notification listener within a second, with no activity and
  the flag already clear before the daemon could look — which is why the
  check runs when the process starts, not only when the app is opened. Before
  Android 15 there is no such record, so an idle JemRec stopped while the
  recorder was down is not told. The two share one "last warned" time, so a
  stop is reported once.
- **Warns when it is held back.** More often the phone does not kill JemRec
  but freezes it, which leaves no exit record. Tried twice on the same Honor
  (2026-10-25), it froze JemRec both times and killed it in neither; after an
  unplug with the screen off, the recorder stayed down **8.5 minutes**, until
  the screen was touched. The cable watch times its own restart, and one that
  took longer than a restart can by itself — the 90 seconds its wake lock is
  sized for, against 12–16 s measured on *Manage manually* —
  ([`CableWatch.heldBack`](../app/src/main/java/com/jemcik/jemrec/capture/CableWatch.kt))
  posts the same warning. It does not wait for the restart to finish: on the
  Honor the phone froze JemRec again mid-restart, so the recorder came back
  only when the app was opened, seven minutes on. Instead the warning goes up
  the moment JemRec runs at all - two of its looks, half a second apart in
  intent, found minutes apart - and the finished restart then updates it with
  the whole time. Measured on the Honor after that change (2026-10-25): frozen
  three seconds after the unplug, the warning went up 3.5 minutes in, on the
  lock screen, waking it; the recorder was back half a minute later, and the
  notification read "about 4 min". (Before it, the same freeze showed nothing
  until the app was opened seven minutes on.)
  A restart that fails quickly, off Wi-Fi, leaves no gap and no warning, so a
  missing network is never taken for this.

The notification points at App launch; it does not claim JemRec can fix the
setting, because it cannot.

## Recording, and being asked

Automatic mode records every call from the moment it goes off-hook. Ask-first
mode records **nothing** until you tap Record on the start-of-call notification,
so a declined call is never written to disk — the setting means what it says,
and the cost is the few seconds before the tap.

Recordings are Opus in an Ogg container, in the phone's standard
`Recordings/JemRec` folder, where any file manager or music app can open them.
You can point the app somewhere else.

**Two channels.** Every recording is stereo. A messenger call, and a phone call
that plays through the mixer (both below), have you on the left channel and the
other person on the right: JemRec takes the two sides from two sources and puts
them there itself. An ordinary phone call has what the phone's own audio driver
gives the call capture (`VOICE_CALL`, asked for in stereo). On the Honor
(MagicOS 11, Android 17) that is the two sides apart, you on the left: the left
was all zeros while the answering machine had the microphone muted
(2026-10-22); the 7 phone calls among the recordings checked on 2026-10-26 and
27 all had channels too different to be one mix copied twice (an eighth, an
answering-machine message, had its left silent, muted); and played on
the phone's two speakers, its owner's voice came from one and the other
person's from the other (2026-10-27). One call of ten checked on 2026-10-20
had nearly all its sound on one channel, both voices in it; why is not known.
On a Pixel with Android 17 both channels carry the same mix (reported in
[issue #20](https://github.com/jemcik/JemRec/issues/20), 2026-10-27, not
measured here). Splitting such a call would take two captures at once, the
call's uplink and downlink, which other recorders report working only on some
phones; it is not built.

A Quick Settings tile turns recording on and off without opening the app. It
is the same switch as the one in the app's header. A long press on it opens
JemRec, not App info: the main activity takes `ACTION_QS_TILE_PREFERENCES`, as
Android's Quick Settings tiles guide says (asked for in issue #37).

## Exact call tracking

The daemon starts at off-hook, and an outgoing call goes off-hook as it is
dialled - so its recording began with the ringing, and a call nobody answered
was saved as a recording of ringing. Android knows exactly when each call is
answered, but tells only the dialer and apps holding `MANAGE_ONGOING_CALLS`, a
permission no app can ask the user for. The shell can allow it, so the daemon
does, for JemRec alone, and Android then tells JemRec, as it happens, which
way each phone call went, when it was answered, and when it ended. With that:

- **A recording starts at the answer.** An outgoing call's ringing is cut, and
  a call nobody answered is not saved at all.
- **Call waiting gives one recording per call**, each from where it was
  answered; while one is on hold, its stretch goes to the other.
- **Whose calls to keep decides before recording** - see below.
- **The end of a call reaches the app directly**, a second way to collect the
  recording beside the daemon's own.

Call tracking only reads. The permission would let an app answer, hold and end
calls; JemRec's service does none of that and shows no screen. (The
[answering machine](#the-answering-machine), when you switch it on, is the
recorder's doing, under the shell's own permissions: it answers, mutes and hangs
up the calls it takes messages on, and nothing else.) It is on unless you
turn it off in Settings (*Exact call tracking*). Off, the daemon takes the
permission back and JemRec works exactly as it did before 0.14. The answering
machine answers on tracking's word, so it goes off with it: with the machine
on, the switch first asks *Turn off the answering machine too?* - until 0.19
it turned the machine off without a word (the user found it, 2026-10-24) - and
the machine's row in Settings then says it is off for want of tracking. JemRec keeps
what Android said about the last 40 calls - when each began, was answered and
ended, which way it went, how far apart the phone's two clocks were during it
(below), and the number only with the call-log permission.

Measured on an Honor (MagicOS 11, Android 17): an outgoing call answered 3.6
seconds after dialling had 3.6 seconds of ringing at the start of its
recording. Allowing the permission for the app's package was not enough there;
Android bound nothing until it was allowed for the app's user ID, which is how
the daemon allows it. With tracking, on the same phone: an outgoing call that
rang for 45 seconds was saved as its 14 seconds of conversation, matching
Android's answer and end to a twentieth of a second; one nobody answered was
not saved; a number the rules left out was stopped a quarter of a second after
dialling and deleted; and all of it held after a restart. Call waiting has not
been tried on a phone yet.

**Where the call is in the recording.** The cut is made in time, and the
recording's packets were placed in it by counting - one every 20 ms from the
first. Over Wi-Fi Calling on a Pixel 11 Pro XL (Android 17) the capture gave
nothing while an outgoing call rang, so counting from the dialling put the
answer past the last packet: a call that rang for 32 seconds and lasted 20.5
was deleted as having no audio
([issue #40](https://github.com/jemcik/JemRec/issues/40)), and a call longer
than its ringing would have lost its start without a word. Each packet now
goes where its own time - which the recorder writes beside it - says it was
captured, wherever the capture lost more than half a second; Android's times
and the recorder's are on two clocks, which the app reads together during the
call. A recording of one call that the cut would still leave empty is saved
whole, ringing and all, rather than deleted. On the emulator, with the
recorder stopped for 1.4 seconds from its first moment, the call was saved to
within a hundredth of a second of Android's account (2026-10-24). On the
Pixel, with 0.21, the reporter's outgoing Wi-Fi call that rang about 33
seconds was saved whole from its answer, 52 seconds with speech from the first
second, and a second Wi-Fi call kept both voices (2026-10-26).

## Whose calls to keep

Settings can narrow down which calls are kept: your contacts - all, all but
some, only some, or none - and, separately, other numbers: ones not in your
contacts, and hidden ones. Nothing is narrowed until you choose.

With exact call tracking, JemRec knows who a call is with while it still rings
or is being dialled, and a call your choice leaves out is not recorded at all.
With a second call already up, the shared recording is split instead, and only
the calls your choice keeps are saved.

Without it, who a call is with is known only once it has ended: the call-state
callback carries no number, by design. So every call is recorded, and as it
ends JemRec reads the call log: a call your choice leaves out is deleted from
the recorder there and then, before it is ever saved. Either way, if JemRec
cannot tell who it was, the recording is kept - it can be deleted later, and a
lost one cannot be made again. In ask-first mode a call
you tap Record on is always kept. This needs the call-log and contacts
permissions, to tell who a call was with.

## File names

- **Date and time**, the default - `20261020_165705_out.ogg`.
- **With the caller** - `20261020_165705_out_Priya Raman.ogg`.
- **For Honor's Phone app** - `Priya Raman@+447700900123_20261020165705.ogg`,
  in `Sounds/CallRecord`: the name and folder Honor's own recorder uses.
  Honor's Phone app then shows a play button on the call in its history.
  Offered only on a phone that has that app. Tested on an Honor: the time
  may be anywhere inside the call, the number has to be the call's, and the
  part before the `@` is not read at all.

The caller comes from the number Android gave exact call tracking, or else
from the call log as the call is saved - either way, only with the call-log
permission. Without it, or for a call neither knows, a recording is named by
date and time. A name is
given once and never changed. Google's Phone app has no equivalent: it keeps
its own recordings in a database of its own, and nothing in it is open to
another app.

## Messenger calls

WhatsApp, Telegram, Signal and Viber calls can be recorded too, both sides,
each app switched on separately in Settings - and Google Meet's calls and
meetings, though Meet reaches the recorder another way (below). Nothing is
recorded from any of them until you do.

**Android 14 or newer.** Copying another app's call audio takes
`CAPTURE_VOICE_COMMUNICATION_OUTPUT`, and stock Android gives the shell that
permission only from Android 14 (AOSP `packages/Shell/AndroidManifest.xml`: not
in 12, 12L or 13). Without it, Android's audio service refuses the copy
(`AudioService`), and its audio engine leaves call audio out of any copy made
without it (`AudioPolicyMix`). JemRec asks Android whether the shell holds the
permission rather than going by the version, so a build that grants it earlier
gets messenger calls; anywhere else, Settings says why there are none. 0.13
offered the switches on Android 12 and 13, where they recorded nothing.
Measured on emulators, 2026-10-20: on Android 12 and on Android 13, Android
refused the copy ("Audio capture for voice communication requires
CAPTURE_VOICE_COMMUNICATION_OUTPUT system permission"); on Android 17 it was
registered, and on the Honor (Android 17) the calls were recorded.

The daemon asks Android for a copy of every app's voice-call audio - an audio
policy, under the `CAPTURE_VOICE_COMMUNICATION_OUTPUT` permission the shell
already holds - while the call still plays in your ear. Your side comes from
the microphone, opened so that it never takes the microphone from the call app;
if Android ever reports the call app's own capture silenced while JemRec
records, JemRec drops its microphone at once. A call is the phone's audio in
"communication" mode with a switched-on app both playing call audio and
capturing - so a voice message played at the ear is not one. You are on the
left channel and the other person on the right, where a phone call's recording
has them on a phone that splits the two sides
([two channels](#recording-and-being-asked)), so the sides never swap with the
kind of call. Up to 0.24 a messenger call had them the other way
round, and recordings made then stay so. Measured on the Honor, 2026-10-27,
played on its speakers: a Telegram call recorded by 0.24.1 had its owner's
voice from the same speaker as the phone calls, and one recorded by 0.24 from
the other.

- **An app can refuse.** Android lets an app mark its audio as not capturable,
  and honours that whatever the recorder holds. JemRec does not try to get
  round it.
- **Not phone calls.** Android's call log has no messenger calls - Google
  Meet's are in it, and JemRec leaves them out (below) - so their recordings
  are in Recordings, marked with the app's icon, and not in the Calls tab.
  *Whose calls to keep* does not apply to them: the app's switch is the rule.
- **Who called.** Only the app's own call notification says. *Show who
  called* reads it, with notification access, which Android grants to all
  notifications. JemRec keeps the caller's name, the time and whether the call
  rang first - from the call notifications of the apps switched on, for the
  last 30 calls - and nothing else.
- **Speakerphone.** Your side is the plain microphone, without the call app's
  echo cancellation, so on speakerphone the other person is on your side too.

Tested on an Honor (MagicOS 11, Android 17) with Viber, Telegram and WhatsApp,
both directions: both sides recorded, the caller named, and the other person
heard the call throughout; and with Google Meet, calls both ways and a meeting
(below). Signal takes the same path and has not been tested yet.

## Phone calls that play through the mixer

A phone call is normally carried by the modem, and the recorder hears it
through Android's call capture (`VOICE_CALL`). Some phones carry a Wi-Fi
Calling call like a messenger call instead: through Android's own mixer, with
the phone's audio in "communication" mode. The call capture then hears
nothing at all - and such a phone call is recorded the way a messenger call is
(above): the other person from a copy of the call's audio, you from the
microphone, never taking it from the call.

Measured on a Galaxy S23 (One UI, Android 16), 2026-10-24, over Wi-Fi Calling:
the call ran in "communication" mode from the dialling, its far side played by
the call stack as voice-communication audio. `VOICE_CALL` at 48 kHz stereo,
16 and 8 kHz mono, and the downlink and uplink on their own, all delivered
silence at the full rate for the whole call - 0.19.2 saved such calls as
silent recordings. The copy had the other person, the microphone her; the
call's own microphone was never silenced, and both people heard each other.
Recorded this way - the mode read as the call went off-hook, the packets
counted - an outgoing and an incoming call came out with both voices, each on
its own channel, to within 0.03 seconds of Android's account. The switch below,
and placing such a recording's packets by their stamps (exact call tracking,
above), came after those calls; with both, an outgoing Wi-Fi call on the S23
switched to the mixer once the mode answered, 0.37 seconds after off-hook, and
came out at 21.45 seconds for Android's 21.49, her voice on the left and the
other person's on the right.

So a phone call is recorded through the call capture from its first moment,
as always, while the recorder asks the audio mode on a thread of its own: if it
says "communication", on Android 14 or newer (the copy needs the same
permission as messenger calls), the recording switches to the mixer a few
milliseconds in. Any other mode - every phone call measured before, on the
Honor and the emulators - and nothing changes. The mode is never waited for:
while Telecom moves the phone into a call, Android answers it only once the
move is done - 0.87 seconds later for an incoming call on the Android 13
emulator, which a recording that waited lost from its start. The copy is armed
for that call alone, whether or not any messenger is switched on, and disarmed
when it ends. You are on the left channel and the other person on the right,
as the call capture has them; on speakerphone the other person is on your side
too, as with messenger calls. The answering machine works on such calls as
well (above, in its own section).

**The mode is followed to the end of the call** (0.24). Up to 0.23 it was
read that once, and a call whose mode changed afterwards stayed where the first
answer put it: one that turned "communication" only after the off-hook, or was
handed over between mobile and Wi-Fi, lost the other person from then on. A
Galaxy M34 (Exynos, Android 16) recorded a Wi-Fi call with its owner's voice
alone ([issue #37](https://github.com/jemcik/JemRec/issues/37), 2026-10-26) -
not measured there, but it is what this would do on that phone: Samsung's
Exynos audio code turns a call capture outside the in-call mode into an
ordinary microphone capture (the open SLSI `audio_hw.c`, read 2026-10-27). Now
each change of mode that holds for half a second moves the recording: to the
mixer when it says "communication", back to the call capture when it says "in
call", nothing for any other. The new capture is started first and takes over
between two reads.

**Only on top of what 0.23 did.** A call that began on the mixer stays there as
0.23 kept it, and leaves it only for the modem's voice path, where the mixer
has nothing. A call that began on the call capture goes to the mixer only while
no app is in a call of its own through Telecom, and only while Telecom holds a
phone call. In the middle of a call, "communication" can also be another app's
call brought to the front over a phone call on hold - Telecom gives Google
Meet's that mode - and the mixer would record it with the phone call. Telecom
is asked app by app, whatever each plays: on a Galaxy S23, Samsung's Routines
and Viber played voice-call audio all through a Wi-Fi call with no call of their
own (2026-10-24), so what plays cannot tell. A call that went to the mixer this
way is asked again every second. Whatever Android will not answer, the call
stays as 0.23 recorded it.

On the Android 17 emulator (2026-10-27), with a stand-in call taking the mode
from a phone call for five seconds and playing a tone: the recording went to
the mixer 0.6 seconds after the change, the tone at -13 dB in its copy, and
back to the call capture half a second after the mode was - saved as 10.96
seconds for Android's 10.97. The copy there was armed after the tone's player
had started, and still had it: Android re-routes the players already playing
when a policy is registered. And with a stand-in app call of the debug build
placed over a phone call being recorded, as a Meet call taken with the phone
call on hold, the recording stayed on the call capture each time, half a second
after the mode changed - also when the stand-in could not make a sound. Both
held on the Android 15 and 16 emulators too (16 is what the Galaxy M34 runs),
where Telecom answered the shell the same way.

Not known yet: which other phones carry Wi-Fi Calling this way.

Only a phone call goes this way: a call Telecom holds as the phone's own.
Google Meet's calls are in "communication" mode too, and up to 0.21 they were
recorded through here as phone calls (next).

## Google Meet, and other apps' calls through Telecom

Google Meet does not play its calls the messengers' way. It puts them through
Telecom, Android's call service, on a "self-managed" account of its own - so do
Gmail and Slack, and `dumpsys telecom` on the Honor lists all three. Telecom
then reports Meet's calls in the call state the recorder watches: ringing,
off-hook, idle, as for a phone call. Meet even writes them into the phone's
call log, with the other person's number.

So 0.21 took them for phone calls. Measured on the Honor (MagicOS 11,
Android 17), 2026-10-26, with Meet on another phone: an incoming and an
outgoing call and a meeting were each recorded as a phone call - both sides,
through the mixer (above), since Telecom put the phone in "communication"
mode for them - with no switch for it, named like phone calls, nothing saying
they were Meet's. And a Meet call the other person declined, followed at once
by their phone call back, came out as one recording: their call rang before
the call state ever went idle, the recording set up for Meet ran on, and their
side of the phone call was not in it.

Telecom knows which of its calls are the phone's own ("managed"):
`TelecomManager.isInManagedCall`, which the shell may ask. Now a ring or an
off-hook while Telecom holds no phone call is another app's call, and the
recorder leaves it to the messenger watch (above), which records it only if
that app is switched on - Google Meet is on the list, off until you turn it
on; Gmail and Slack are not. The answering machine never counts such a ring
either - nor could it act on one: JemRec's call tracker is not bound for
self-managed calls (`dumpsys telecom`, the same day), so no go-ahead comes.

- **A phone call during an app's call** is a phone call: it rings with
  Telecom holding it, and is recorded on its own - once the Meet call ends, if
  that one is being recorded, as with any messenger call.
- **A phone call hung up while the app's call goes on** gets no idle: the
  call state stays off-hook for the app's call. The audio mode changes, though,
  and a change with no phone call left in Telecom ends the phone call's
  recording, confirmed as any end is. The same holds for a WhatsApp call taken
  over a phone call: its "communication" mode used to count as the phone call
  still being up (found reading the code). The app's call, if switched on, is
  recorded from then.
- **Which way.** Meet's ring reaches the recorder through Telecom, so it
  knows whether a Meet call rang before it began, and writes that into the
  recording's name; the app takes it over Meet's notifications, and for a
  call that rang, the caller's name from the ring. Those notifications name
  one caller two ways - the ring by the person's name, the in-call
  notification by their account (the Honor, 2026-10-26) - which the
  notification rule read as a missed ring and a call placed to someone else:
  every incoming Meet call came out outgoing.
- **The call log.** The Calls tab, the names of phone recordings, the
  missed-recording check and the self-test read only the rows of the phone's
  own account (Telephony's, `com.android.phone`); a Meet call in the call log
  was otherwise a phone call that was "not recorded".
- **Telecom cannot be asked** - an exception, a phone without it: every call
  is a phone call, as before.

Measured on the Honor with 0.22, 2026-10-26, Meet switched on: an incoming
and an outgoing Meet call and a meeting were each recorded as Meet's, both
sides (-23 to -26 dB in speech), each named from Meet's notification, the
incoming one incoming - Telecom's ring - and Meet's microphone never
silenced. A Meet call the other person declined, then their phone call back,
made two recordings, the phone call with both sides (theirs -26 dB). The
Calls tab listed only the phone calls, and no call was reported unrecorded.
A meeting's notification names no person; its recording is called what Meet
calls it, "Your video call".

Measured on the emulators, Android 12, 13 and 17, with a stand-in from the
debug build - a call of its own on a self-managed account, as Meet's are: the
call state went off-hook for it there too, and the recorder left it alone; a
contact's phone call rung in over it was recorded on its own, and ended where
it was hung up - on 17, its log showed, with the stand-in call holding the
audio mode and no phone call left in Telecom; and the
stand-in's row in the call log was not counted as a call that went
unrecorded - a row Android 12 and 13 wrote, and the check wrote on 17, whose
emulator logs no such call (`tools/emulator_checks.py`, app-call).

## The answering machine

**Off until you switch it on.** It answers the phone calls you do not take,
plays your own greeting to the caller with your microphone muted, and keeps
their message as a recording marked as one. Phone calls only: messenger calls
are not in Telecom, where the recorder answers from. Whether they could be is
[a spike of its own](VOIP-ANSWER-SPIKE.md).

**The app decides who; the recorder decides when.** While a call rings, JemRec's
call tracker knows who it is and applies *Whose calls to keep*. Anything it
cannot be sure of means no answer: a caller it cannot identify, another call
already up, roaming, recording switched off. So does a messenger or meeting
call in progress, which the tracker cannot see - Telecom shows it only calls it
manages - and which the mute would silence: the audio mode says one is up, and
the recorder asks again before answering, by the app playing and capturing
call audio, because a phone call ringing over it may take the mode. On Android
12 the shell is not told whose each player and capture is (it lacks
`MODIFY_AUDIO_ROUTING` there; 13 grants it), and up to 0.23 that second look
found nothing: on the Android 12 emulator (2026-10-27) the machine answered a
call ringing over a stand-in messenger call in four runs of five, whenever the
ringtone got the mode first. Where Android hides the owners, call audio playing
and a call's kind of capture (voice communication, the microphone) now count as
a call between them, and the phone rings on. Recording keeps a call when unsure,
because a recording can be deleted later; an answered call cannot be unanswered.
The tracker sends the recorder a go-ahead with the delay - 5 to 30 seconds, about
two rings when Do Not Disturb would silence the caller - and the recorder runs
the timer, because the app can be frozen while the dialer is in front. Without
a go-ahead for the ring in progress, nothing is answered.

**For a day after a call to an emergency number** it answers nothing, so an
emergency service calling back rings until the user can pick up. The callback
cannot be recognised - it comes from an ordinary number, or a withheld one -
so phones pause instead: iPhone turns its call screening off for 24 hours after
an emergency call, and Android turns call blocking off, 2 hours by default
(`KEY_DURATION_BLOCKING_DISABLED_AFTER_EMERGENCY_INT`). Android's own
emergency callback mode, which the recorder asks about too, starts only on
CDMA, or on LTE where the carrier opts in (AOSP, read 2026-10-22): on GSM it
never does. The call tracker notes an emergency call - to a number Android
calls one, or one the network marks - and counts the day from its end;
Telecom's own record of the last one, on a ringing call, counts too, and a call
the network marks as a callback is never answered. The user can end the pause
early, warned. No test call answers anyone in that day either - a test answers
whoever calls in its three minutes - and none ever dials an emergency number:
Telecom does not place one for the shell, but opens the dialer with it (AOSP
`NewOutgoingCallIntentBroadcaster`, read 2026-10-22), and a tap on Call there
would have been taken for the test's call. A call of the user's own while a
test waits ends the test.

**How the greeting reaches the caller.** No app can play sound into a call:
Android lets a call's uplink be written only by a holder of
`MODIFY_PHONE_STATE`, which the shell holds and no app can be granted. The
recorder plays the greeting as a plain media track aimed at the call's uplink
device, which Android's audio policy marks as *in-call music* - the output
Qualcomm mixes into what the caller hears.

Measured on an Honor (Snapdragon, MagicOS 11, Android 17), 2026-10-22, with a
caller on another phone:

- The in-call music route: heard in full. Without a 1.5-second lead-in the
  greeting's first words were lost while the call's audio came up.
- Android's own call-screening API (`getCallUplinkInjectionAudioTrack`): it
  played, at real-time pace and with no error anywhere, to an output Qualcomm's
  audio routes nowhere. The caller heard nothing, though Android's own
  `isPstnCallAudioInterceptable` said yes.
- Call-redirect mode: the phone's microphone and earpiece leave the call and the
  caller's voice can be captured cleanly, but nothing at all reaches the caller.
- `setMicrophoneMute`: the room stays out, the greeting does not. Telecom
  adopts the mute and shows it on the call screen; it held through a switch to
  speaker and back, and the phone cleared it as the call ended.

Measured on a Galaxy S23 (One UI, Android 16), 2026-10-24, over Wi-Fi Calling -
a call that plays through the mixer (below):

- The call never takes Android's in-call mode, and the machine took that for a
  call whose audio never came up: it answered, waited, and hung up on the
  caller. It now counts the call's own mode, as the recorder does.
- In-call music has nothing to mix into: the call's voice does not go through
  the modem. An audio policy that swaps the call's own microphone for the
  greeting (the messenger route) left it on the microphone.
- The call-screening route carried the greeting: the caller heard it, at
  real-time pace. With it, a real unanswered call was answered after ten
  seconds, her microphone muted, the greeting heard, and the message saved
  with the caller's voice from its first second.
- The machine's test filed that call under VoLTE: Android's voice network type
  says LTE for a Wi-Fi call there. Telling them apart needs the IMS
  registration's transport; not done yet.

**A message's length** is the user's: 30 seconds to 2 minutes, then the
recorder hangs up - or no limit, which ends the call after 30 seconds without a
sound from the caller, because a pocket dial or a line left open would
otherwise hold the phone in the call for as long as it stayed open.

**During a message**, a notification - *Taking a message* - offers *Talk to
them* (the greeting stops, the microphone opens, the call carries on) and *Hang
up*. Unmuting on the call screen does the same as *Talk to them*, and so does
answering a second call - a waiting one, or one the user places - which the
call tracker sees become active: left to the machine, the greeting would play
into it, the microphone stay muted, and the message's limit hang it up. A call
taken over is the user's own: an ordinary recording, not a message, from just before
they took it - or the caller's first words, if those came first; kept from the
answer, it began with the user hearing their own greeting in silence. In Ask
first the takeover ends the message there instead: what the caller said is
saved as the message, and the user is asked, as at the start of any call,
whether to record the rest - the caller agreed to leave a message, not to a
recorded conversation, and answering machines have always stopped recording
when picked up (Gigaset, BT, VTech manuals; Pixel's Take a Message).
The recorder
leaves a marker while it holds the mute, and a recorder started after a crash
gives the microphone back - at once between calls, or as the call in progress
ends: muted, the room stays out of a message whose caller is still on. The mute comes as soon as the call's audio is up -
on the Honor that is before the network connects the call (2026-10-22: 14 ms
after the answer, connected at 235 ms) - and the greeting, and the app's
*Taking a message*, wait for the off-hook: a caller gone in that moment left
no call, and nothing to undo but the mute.

**The message** is saved from half a second before the caller's first words,
never later than the beep - the silence of their listening to the greeting is
cut, and words said over it are kept. Words are a run of sound with half a
second of sound in it: on the Honor (2026-10-22) a click as the call connected
started a message there, and the caller's ten silent seconds were kept. The
caller is the one sound in the recording - this phone's side is muted - unless
a phone mixes the greeting into its recording: there the greeting is not taken
for the caller, and words over it count in its pauses. On the Honor
(2026-10-22) this phone's side was all zeros while muted. A caller who hangs
up during the greeting, having said nothing, leaves nothing; the Calls tab says
so. A message is posted on its own notification channel, with a sound, and is
never removed by the short-calls auto-delete rule.

**Your greeting** is recorded by the recorder, not the app, which holds no
microphone permission; the app trims the silence at both ends and evens the
level, because a call's uplink plays at full volume with no control over it.

**Whether it works on a phone** is found out, not assumed. Android's own answer
said yes on the Honor for a route that failed, so the app checks three ways:

1. Without a call: the recorder holds the permissions, and the phone's audio
   setup declares an in-call music output. The emulator declares none (Android
   17, 2026-10-22), nor does the published audio setup of a Huawei Kirin 970
   (read, not tried on a phone); there only Android's call-screening route is
   left to try, which reached nobody on the Honor.
2. A test call: someone calls, or the recorder dials a line that answers by
   itself. The recorder checks the greeting played on the in-call music output,
   at real-time pace (the route that failed was consumed three times too fast),
   and looks for a check tone in the phone's own recording of its side - which
   proves the route when found, and proves nothing when not: on the Honor's 2G
   calls it never was, though the caller heard it.
3. After a test someone made, one question: did they hear it?

Results are kept per call type - 2G, VoLTE and Wi-Fi Calling take different
paths - and *Copy a report* puts the phone and the results on the clipboard for
a report, with nothing about any call. On the Honor, VoLTE could not be tried:
its carrier would not register it for VoLTE.

**Your carrier's voicemail** takes unanswered calls after its own timer, so the
answering machine has to answer first. The recorder can ask the carrier for that
timer (`getCallForwarding`, 6 to 18 seconds on the Honor), and, on your tap,
make it wait the longest it allows, 30 seconds, keeping your carrier voicemail
for calls JemRec does not take. That timer is the carrier's, on its network:
it outlasts JemRec, which does not keep what it was before.

## The Calls tab

Beside Recordings, a Calls tab shows the phone's call log, under the same
Today, Yesterday and Earlier headings as the recordings. A recorded call has
the play mark and plays on a tap, like the Recordings tab. A call without a
recording says why, where JemRec knows:

- **In Recently deleted**, or **Recording deleted** - recorded, then deleted.
  The second is known for a week.
- **Left out by your rules** - a call *Whose calls to keep* left out. Also
  known for a week.
- **Too short** - under five seconds.
- **Recorded, not saved yet** - still on the recorder, collected when it can
  be: after a restart, the next Wi-Fi; after a hang-up whose hand-over did not
  land, the next pass.
- **Recording was off**, or **Ask first, not recorded** - from a record of the
  switch and the mode that JemRec keeps from 0.12 on.
- **Hung up during the greeting**, or **Answering machine test** - a call the
  answering machine answered that left nothing to keep: the caller said
  nothing before hanging up, or it was a test, whose recording is not the
  user's. A message itself is a recorded call, marked **Message**.
- **Greeting could not play**, in red - the answering machine answered and hung
  up without taking a message: the greeting would not play, the microphone
  would not mute, or the call's audio never came. The user is told then, by
  whichever collects the call: the hang-up's save, or a later pass.
- **Not recorded**, in red - recording was on and automatic, and still nothing
  was saved. This is the one to worry about. Said only for the last week:
  past that, a recording deleted or a call left out looks exactly the same.
- **No recording** - from before that record began, with no guess at why.

Missed, declined and blocked calls are marked as such. Calls Google Meet puts
in the call log are not listed: they are not phone calls (above). The tab needs
call-log access, the same optional permission that names callers in the list.

**Finding a call.** Both tabs can be searched - by name or number, and on
Recordings by the time the row shows - and narrowed by chips: favourites,
kept, messages, and on Recordings the phone and each messenger, on Calls the
calls that connected and were not recorded. Chips of one kind widen (WhatsApp
and Telegram), chips of different kinds narrow (and favourites). A chip is
drawn only where it would leave some of its list, not all of it and not none:
one that would change nothing is noise, and one that would empty the list is
a trap. One that is on stays, so it can be turned off. Whether a chip is
drawn and what it leaves are one rule in the code, so a chip offered never
leaves something other than it promised.

A narrowed list says so. A line under the chips names what narrows it - each
lit chip, what is typed - with how many of how many are shown, and *Show all*
turns every chip off and folds the search away; and a chip lit past the
row's edge is scrolled into view as the screen opens. On the Honor
(2026-10-23) Messages was lit off the screen's edge and the list showed 2 of
52 with nothing saying why: recordings that seemed gone. The row's chips
cannot all be in view on a phone with three messengers; the line can.

## Playing a recording

A recording plays inside the app, never handed to another: the first version
let Android choose, which on the Honor meant YouTube Music's preview bar, and
gave a third-party app the audio of a private call at every tap. Sharing is a
separate, deliberate action.

The player under the row is the recording's **waveform**: a bar for each slice
of it, as loud as the loudest twentieth of a second in that slice, so words
stand out of the pauses and a long silence reads as one. A tap or a drag on it
seeks; to a screen reader it is a slider, read as a time. The loudness is
worked out from the file itself, decoded by Android's own decoder - the one
the silence check after each call already uses - so there is no library, no
native code, and nothing new for the reproducible build to match. It is
worked out as a call is saved, an older recording's while the list is on
screen (one at a time, stopping with the screen), or one played before then
- with a plain slider until it is there - and kept in the app's cache: 800
levels, a byte each, under a hash of the file name, which can hold a
caller's, until the recording is deleted. A recording still being written is
left until it is finished, and one decode runs at a time a recording. Android's `Visualizer`, which draws whatever is playing, was not an
option: it needs the microphone permission this app does not hold.

**Fast, for a long call.** Android's decoder runs in a process of its own,
and every packet handed to it is a trip there and back. So the packets go six
to one - Opus lets a packet hold up to 120 ms of frames, which decode to the
same samples - and up to four decoders work at once, each on a stretch of the
call, lined up against each other where the stretches overlap. Joined, the
samples are the same CRC for CRC (two phone calls' middles and a messenger
call's whole file, on the Honor), but for the encoder's first 6.5 ms, which
the decoder trims from a single frame and keeps from a joined one - less
than a bar. Lined up, because after a jump
into a file, Android's times for the app's own recordings were a 20 ms packet
early. Measured on the Honor (2026-10-23), a 30-minute call took 24.5 s a
packet at a time with one decoder, 6.9 s joined, and 2.5 s joined four at
once; a 58-second call, 1.0 s and then 0.14 s. Drawn, the bars match the one
decoder's within 10%.

## Playing in the background

A recording plays on with the screen off and the app out of sight, under the
phone's own media controls - the notification, Quick Settings, the lock
screen, a headset's buttons - and, on the Honor, in MagicOS's pill at the top
of the screen. It is Android's documented route: a Media3 media session in a
foreground service of the media-playback type (`PlaybackService`), with
nothing Honor-specific. Measured with a probe, 2026-10-23: on the Honor
(MagicOS 11) the pill showed it from the standard session alone, and it
played 59.2 s in 60.3 s with the screen off; on the Android 17 emulator it
played on as well. An emulator check plays a recording from its row and
holds all of this below on each release AVD.

- **No caller on the lock screen.** The controls name the caller once the
  phone is unlocked, and say only *Call recording* while it is locked: a lock
  screen is read by whoever holds the phone, and with Media3's defaults the
  Honor's showed the name. One session feeds every surface, so the session
  itself is renamed as the screen goes off and as the phone is unlocked; a
  Bluetooth device or a car sees the same. The unlock is SystemUI's
  `USER_PRESENT`, and SystemUI is an app, so its receiver is exported -
  registered not exported, it never heard the unlock on the Honor and the
  name did not come back; the broadcast is protected, so no other app can
  send it. Measured on the Honor, 2026-10-23: *Call recording* on the lock
  screen, the name again within two seconds of unlocking.
- **The same player.** Recordings still play with `MediaPlayer` - its exact
  seeking, measured, kept - wrapped for the session (`SessionPlayer`). The
  session alone added 0.56 MB to the release APK; ExoPlayer would have added
  1.54 MB (9.19 MB before, measured). So what ExoPlayer does by itself is
  written out (`PlaybackRules`), from Android's audio-focus guide: a ringing
  call holds it back and it plays on after; another app taking the audio
  pauses it until you play it again; asked to duck, speech pauses instead,
  so no word is missed; headphones coming out pause it, so a call never
  moves to the loudspeaker by itself. During a call it does not start -
  Android refuses it the audio - and the app says so.
- **Who may use it.** Android lets any app ask to connect to a session. JemRec
  alone chooses what plays; the system's controls, and apps the user lets read
  notifications - which see the media notification anyway - may play, pause
  and seek; any other app is turned away. Playback resumption, the system's
  offer to start the last recording again after a reboot, is off: it would
  name a caller.
- **It no longer pauses when you leave.** Until 0.19 a recording paused when
  the app went out of sight, and on another tab or in Settings: the row's
  player was its only control, and one left playing in a pocket could not
  have been stopped. With the system's controls it plays on. The end still
  rewinds to the start, paused; swiping JemRec away in Recents leaves a
  playing recording playing, as music apps do; the notification's pause, or
  the row's chevron, stops it. Reopened while it plays, the app shows it
  where it is, waveform and all.

## Three looks

The app comes in three looks, picked in Settings → Theme: **Memo**, the
default and the app as it was, so an update changes nothing unasked; **Deck**,
a pocket recorder, with a REC key and an LCD; **Multitrack**, a sound editor,
each caller's calls in a colour of their own. Each is light, dark, or as the
phone - Android's words and order for an in-app choice ("Implement dark
theme"), told to Android as the app starts (`setApplicationNightMode`, from
API 31, the minimum here), so the launch screen is drawn in it too.

They are one app drawn three ways, not three apps: the same screens from the
same state, and only the colours, the type and the home screen's own pieces -
the header, the rows, the player - and Settings' bar and groups differ. What
each look shows is the same: the header's one strip (the record control, the
state, a line of marks or of what to do, the gear, the tabs) is one height in
every state and at every text size, and each setting the header marks -
AUTO or ASK, ANS, MSG, DEL - carries the same mark in Settings, from one
function. A look draws nothing that is not data: no meter moves between calls,
no LED is lit for show.

The Theme screen previews the picked look with the home screen's own parts,
drawn over five invented calls: a preview of the user's calls would put them
on a settings screen, and the hand-drawn pictures it replaced had already
fallen behind the headers they showed. It is a picture: nothing in it can be
tapped or heard by a screen reader - both held by tests - or focused.

The looks' type is four fonts in the APK, unmodified from
[Google Fonts](https://github.com/google/fonts) under the SIL Open Font
Licence ([NOTICE.md](../NOTICE.md)): Doto for Deck's LCD word and Share Tech
Mono for everything else in Deck, its names and keys included; Space Grotesk
for Multitrack's names and titles and JetBrains Mono for the rest of it. They
grew the APK from 8.83 to 9.13 MB. Each look's theme carries its type for
every screen drawn in Material's text styles - Settings' screens, Recently
deleted, the dialogs and sheets - not only for the look's own pieces: until
0.19 those were in the phone's font in every look, and Deck's names and its
Recordings and Calls keys with them (the user found it, 2026-10-23).

Multitrack gives each caller a colour of their own, and keeps it. By the
name's hash alone, two callers shared one of its six colours one time in six
- on the Honor the user's two most frequent callers were both blue. Now a
caller seen for the first time gets the colour fewest of the callers in the
lists have (most frequent first, when many are new at once), so the first six
are six colours, and the colour stays theirs. What is kept is a one-way key -
the first 12 hex digits of the SHA-256 of the name - and the colour's number,
in private storage that no backup takes. Colours are given only while
Multitrack is the look, and kept after a change of look, so a caller's colour
is still theirs on the way back; uninstalling deletes them.

None of Share Tech Mono, Space Grotesk or Doto has Cyrillic (0 of the 64
letters each, read from their character maps), so Android drew a Cyrillic
name in the phone's font. Deck's face is now a chain Android builds
(`Typeface.CustomFallbackBuilder`): Share Tech Mono, then JetBrains Mono at
weight 400 for Cyrillic - every Ukrainian letter and the apostrophe U+02BC,
and Share Tech Mono's weight (a capital's stem 0.123 of its cap height, to
0.125) - then the phone's monospace. Of eight candidates drawn on the Honor
the user picked it; Anonymous Pro, as wide as Share Tech Mono, drew Cyrillic
visibly lighter (0.102). Multitrack's Cyrillic names stay in the phone's
sans, close to Space Grotesk's shapes - the user's choice.

On a phone on its side - a window under 480 dp tall, Android's compact
height, and wider than it is tall - home's header becomes a side panel at the
left, at its portrait width, its ground the full height: the state at the
top, the list's chips and search in the middle, named there (the row has room
only for icons), and the tabs at the foot, under the left thumb. The list
beside it takes the full height, with no chip row of its own.
On top, the header and the chips took half a landscape screen and left about
one row in view (800 x 360 dp, every look). Android's window size classes put
"99.78% of phones in landscape" in compact height, and show the top app bar
only from medium height up in their example; the width is where the room is.

## Keeping, deleting and cleaning up

- **Keep** - the bookmark on each row marks a recording to keep. A Kept chip
  shows only those, and auto-delete never touches them.
- **Delete goes to Recently deleted** - from the list or from the notification
  after a call. A deleted recording waits 30 days, can be restored until
  then, and is then removed for good. In the standard Recordings folder that
  is Android's own trash, so other apps stop seeing the file at once. In a
  folder you chose, the file is renamed the way Android's trash names it
  (`.trashed-<expiry>-<name>`, hidden from most file managers, with any `@`
  written `%40` so Honor's Phone app stops offering it), and JemRec removes
  it when the 30 days are up. Recently deleted restores one at a time, or
  all at once (*Restore all*), and *Delete all for good* empties it now,
  after a question saying it cannot be undone - wherever each waits, with
  Android's own dialog for a file it does not count as JemRec's. Until 0.19
  only time emptied it, by choice; the user asked for both (2026-10-23). An
  emulator check taps both in the real screen and finds the file gone from
  Android's trash and the disk; Robolectric has no MediaStore to try it on.
  Android 17 added a trash to folders, and its folder provider reads the
  renamed file as trashed: it reports `.trashed-<expiry>-<name>` as `<name>`,
  marked restorable (the Android 17 emulator and the Honor, 2026-10-26), where
  the Android 12 and 13 emulators report the name as given. Up to 0.21, JemRec
  read only that name, so on Android 17 a recording deleted in a chosen folder
  stayed in the list, never reached Recently deleted and was never removed
  (issue #51). It now reads the name from the end of the document's ID, which
  still holds it, and takes the restorable mark alone as "in the trash" where
  the ID does not say. Android 17's own folder trash was tried and set aside:
  `trashDocument` moves the file out of the folder, to
  `/sdcard/.trash-storage`, and restoring it from there was refused to
  JemRec's grant on the folder. A second emulator check repeats the first in a
  folder picked through Android's picker, and backdates one there for the
  daily pass to remove; on the code before the fix it fails on Android 17.
- **Auto-delete** - off until you choose. It removes recordings older than 30
  days, 90 days, 6 months or a year, and calls shorter than 5, 10 or 30
  seconds - wrong numbers, hang-ups. A short call is caught as it is saved,
  so it never gets a notification. What auto-delete removes goes to Recently
  deleted like anything else.
- **Recordings Android does not count as JemRec's** - typically ones made
  before a reinstall. Android asks before JemRec may move them, in its own
  dialog. Deleting them from the list, or changing an auto-delete rule, shows
  that dialog. The daily auto-delete pass has nobody to ask, so it leaves them
  alone.

## Marks inside the recording

What a row shows beyond its file name - that it is a message, that it is
kept, who a messenger call was with, and for Honor's names which way the
call went - used to live only in the app's preferences, by file name. An
uninstall deletes those and leaves the recordings, so after a reinstall on
the Honor (2026-10-24) the Messages chip found none and every messenger call
was "Telegram call". The file is the one thing that outlives the app, and
the one thing the three places a recording can live have in common, so each
recording now carries its own marks.

- **Where.** An Ogg Opus file's second page is its tags (OpusTags, RFC 7845
  section 5.2): a vendor string, then `NAME=value` fields, then optional
  zeros. MediaMuxer writes it with "libopus" and no fields, and cannot be
  told to write any (read from a recording's first bytes on the Honor). So
  the muxer writes each recording into a work file in the app's private
  storage (not its cache, which Android empties when storage runs low), and
  the file is copied into place with that one page swapped for JemRec's:
  `JEMREC=1`, and `JEMREC_MESSAGE`, `JEMREC_DIRECTION`, `JEMREC_CALLER`,
  `JEMREC_KEEP` as they apply. Every other page is the muxer's, byte for
  byte, and the tests hold the checksum to the two the Honor's own file
  carries. A copy that fails is a recording not saved: the recorder's copy
  is kept for a retry, as when the muxer cannot finish. A work copy and then
  the copy into place need the recording's size free twice, so a recording
  whose save has failed once is saved the old way at the retry - straight
  into place, untagged, its marks in the app alone - rather than risk a
  week of failures and the recorder giving it up.
- **Measured on the Honor** (2026-10-24, a 20 s tone, not a call): Android's
  media scanner read a copy tagged this way as 20 s, as it did the untagged
  one; with a standard `ARTIST` field added it showed that artist, so it
  reads the page, zeros and all; the `JEMREC_*` fields show in no music app.
  A message JemRec saved and tagged on an emulator, put in
  `Sounds/CallRecord` under the Honor name of a real call, got that call its
  Play button in Honor's Phone app, which played it to the end in Honor's
  Recorder. And the 0.19.2 debug build, installed fresh beside 50 older
  recordings and two tagged ones, read all 52 once its audio access was
  allowed in setup, and noted the tagged message as a kept message and the
  Telegram call's caller by name.
- **Keep, after the save.** The tags are written at a fixed size (512 bytes,
  more for a long name) with room for the keep mark, so marking a recording
  kept, or not, rewrites that one page in place at the same length - a few
  hundred bytes, not the file. A recording from before this has no room,
  and after a reinstall Android counts the old install's files as nobody's
  and will not let JemRec write them without asking (a
  `RecoverableSecurityException`, on the Android 17 emulator, 2026-10-24);
  for those the mark is kept in the app only, as every mark was before - so
  a mark changed on such a file goes back to what the file says at the next
  reinstall.
- **Reading back.** The list reads the first two pages of any recording it
  has not read before - every one, after a reinstall; afterwards, only one
  copied in from elsewhere - and notes what they say where the marks have
  always been read, before auto-delete can ask. Each file is read once. An
  emulator check saves a message, keeps it from its row, uninstalls and
  installs again: on Android 17 and 13 the row is a kept message again
  (2026-10-24), and Android's index still reads the file's full length.
- **A row's time is the call's.** Writing the keep mark moves the file's
  date (by 4 s on the Android 17 emulator), and a row with no caller showed
  that date: it would have jumped to the moment of the tap. It now shows the
  call's start, from the name - as the day it is listed under, and a named
  row's time, already did. Only a file whose name is not JemRec's still
  shows its own date.
- **Seeing them at all.** After a reinstall Android counts the old
  install's files as nobody's, and an app sees those only with a permission:
  `READ_MEDIA_AUDIO` from Android 13. Android 12 has only
  `READ_EXTERNAL_STORAGE`, which reads every photo, video and audio file;
  JemRec did not ask for it, and on 12 listed no recording from before a
  reinstall, tags or none (the emulator check, 2026-10-24). The user chose to
  ask for it there too, on the same card, capped at 12 in the manifest.
- **What it does not cover.** A phone call's caller is still the
  call log's, as it always was. A folder you chose must be chosen again: its
  address was in the preferences too, and until then its recordings are not
  listed. Recordings in the app's own storage - only ever a last resort -
  are deleted with the app. Multitrack's caller colours belong to callers,
  not recordings, and are given out again.

## After the call, and when something is wrong

A recorder that stops recording without saying so is worse than none: you find
out on the day you need the call. So JemRec says so, in four notifications.

- **After each call** - who it was with and how long, with Play, Share and
  Delete. An icon in the status bar but no sound, one at a time, and it can be
  switched off in Settings. Delete asks first, in the notification itself.
- **The recording came out silent** - every recording is decoded and measured
  once it is saved. A muted capture still produces a file of the right length;
  this is what catches it. A recording with only one side is NOT reported,
  because it cannot be told reliably: on the phone this was built on, a call
  with both voices audible had one channel all but silent - the other carried
  the whole call.
- **The recorder is down** - after two minutes, with the reason: it needs a few
  seconds of Wi-Fi, or Android has not allowed Wireless debugging on this Wi-Fi
  (with *Allow this Wi-Fi*), or it cannot connect and the pairing is probably
  gone (with *Pair again*), or it could not start. It goes away when the
  recorder is back. The home screen's header says the same at once, each cause
  with its own line, and only off Wi-Fi does it say to join one. A pairing
  turned away twice in a row sends home to setup's pairing step instead, which
  says why: through 0.19 the header told the user to join a Wi-Fi they were on,
  their pairing deleted (the Honor, 2026-10-24).
- **A call was not recorded** - in automatic mode, with call-log access, the
  call log is held against what was recorded, and an answered call with nothing
  to show for it is reported once. Recordings you delete do not count as missed:
  the app notes each call as it saves it.

None of it needs a permission the app did not already have.

## The last call's report

A call that comes out wrong on a phone nobody here has held can only be
understood from that phone. A Galaxy M34's Wi-Fi call came out with one side
([issue #37](https://github.com/jemcik/JemRec/issues/37)), and nothing on the
phone could say why: the recorder's log says which way each call was recorded,
but it is out of reach without a computer, and replaced at the recorder's next
start.

So the recorder keeps an account of the last call it recorded, and
*Settings → Diagnostics → Copy a report* puts it on the clipboard, after this
phone's make, model and Android build and the settings that decide how a call
is recorded (messenger calls, exact call tracking, the answering machine):

- which way the call was recorded - the call capture, the mixer, or a
  messenger's - from when, and how loud each channel came out each way, with
  digital silence (every sample zero) said as such;
- the audio mode as it changed, and what the recorder did about it;
- what was playing and capturing three seconds in, and three seconds after
  each change of way: which app or system uid, for what (voice call, media...),
  whether it lets the system capture it, by what kind of player, to or from
  which device.

Nothing about the call itself: no number, no name, no sound. The account is
kept in `/data/local/tmp/jemrec-lastcall.txt` until the next call replaces
it, so a recorder started again since still has it, and it leaves the phone
only if you copy it and paste it somewhere. Android 13 and later confirm a copy
themselves; on 12 the app says *Report copied*, as Android's guidance asks
("Copy and paste", developer.android.com) - which the answering machine's
*Copy a report* had not said until then.

## What it does not ask for

No `RECORD_AUDIO`. This app never records — the shell-side process
does the capturing, including your greeting for the answering machine, and only
while you are recording it — and a call recorder that asks for the microphone
anyway is asking for something it does not use.

This is the whole list, in the order F-Droid's listing page shows it, because a
permission a call recorder does not explain is one its user has to guess at:

| Permission | Why |
| --- | --- |
| `INTERNET` | Not about the internet: Android requires it for any TCP socket, including one to `127.0.0.1`. |
| `ACCESS_NETWORK_STATE` | To tell whether there is Wi-Fi to rebuild the recorder over, instead of trying and failing. |
| `WRITE_SECURE_SETTINGS` | Granted by the app to itself over its own ADB session. Nothing but a shell can grant it, so no user can be talked into it. It lets JemRec switch debugging back on when the phone turns it off, and off with JemRec when you ask it to. |
| `POST_NOTIFICATIONS` | The recording notice, the ask-first prompt, the pairing code box. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | To stay alive while a call is being recorded and while the finished file is collected; while the recorder is restarted after a computer's cable comes out; and for the seconds it takes to warn that the phone stopped JemRec in the background. `SPECIAL_USE` is the honest type: the microphone type would need a microphone permission this app does not hold. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | To play a recording on with the screen off, in `PlaybackService` ([playing in the background](#playing-in-the-background)). It plays; it never records. |
| `RECEIVE_BOOT_COMPLETED` | A reboot kills the recorder; this is what arms the job that rebuilds it. |
| `READ_MEDIA_AUDIO` | To list recordings made before a reinstall. Refusing it only hides those. |
| `READ_EXTERNAL_STORAGE` | Android 12 only, which has no `READ_MEDIA_AUDIO`: the same, to list recordings made before a reinstall. It covers photos and videos too; JemRec reads only its own folder. Refusing it only hides those. |
| `READ_CALL_LOG`, `READ_CONTACTS` | Optional. The only way to know who a call was with: to label a recording, to name a file after the caller, to keep or leave out a call by who it was with, and, for the call log, to notice a call that went unrecorded. |
| `MANAGE_ONGOING_CALLS` | For exact call tracking. Not one an app can ask for: the daemon allows it, as the shell, while the switch is on. Android then tells JemRec when each call is answered and ends. Tracking never acts on a call; the answering machine's answering is the recorder's, as the shell. |
| `USE_FULL_SCREEN_INTENT` | Lets the ask-first prompt last a minute instead of five seconds. Not auto-granted, and nothing is gated on it. |
| `WAKE_LOCK` | So the keep-alive job can finish its few seconds of work, and so a phone with its screen off stays awake while it brings the recorder back after a USB cable, a minute and a half at most. WorkManager declares it too. |

Two more (`READ_PHONE_STATE` and `WRITE_EXTERNAL_STORAGE`) are actively
**removed** from the manifest, because the ADB library's own manifest would
otherwise pull them in; the third it pulls in, `READ_EXTERNAL_STORAGE`, is
declared instead, capped at Android 12, for the row above. One more that cannot be removed and means nothing:
`com.jemcik.jemrec.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which AndroidX
defines for this app alone when it registers a receiver as not-exported.

## What stays switched on

**USB debugging and Wireless debugging stay on while JemRec is on** - and
while it is off too, unless you choose otherwise (see [Off, and debugging with
it](#off-and-debugging-with-it)). USB debugging is what keeps `adbd` alive
when Wi-Fi drops or Wireless debugging goes off; with both off, `adbd` stops and
the recorder with it. (Measured on the Honor, 2026-10-26: Wireless debugging switched off with USB
debugging on left `adbd` and the recorder running, the same process IDs two and
a half minutes later.) So the daemon puts USB debugging back on within ten
seconds if it goes off while `adbd` lives, the app does when the switch-off took
`adbd` with it (see [Staying alive without Wi-Fi](#staying-alive-without-wi-fi)),
and the app turns Wireless debugging back on the next time it rebuilds the
recorder.

What that means for the phone: while debugging is on, a computer that has been
paired with it can open an ADB shell on it from the same Wi-Fi, and one you have
authorised before can do the same over a cable. An unpaired computer cannot.
Check *Wireless debugging → Paired devices* now and then and remove anything you
do not recognise. With *Start without Wi-Fi* on, port 5555 is open too, on
every network the phone joins; a computer that reaches it, or an app on the
phone, can ask to debug the phone, and only a yes on the phone lets it (see [Starting without
Wi-Fi](#starting-without-wi-fi)).

Android shows a notification for as long as debugging is on. Setup says all of
the above before you switch anything on, then asks whether JemRec should hide
that notification. Only on a yes does it get notification access, and it then
snoozes nothing but Android's own debugging notifications, matched by their
system ID, or by the word "debugging" on a ROM that numbers them differently.
Settings can change the answer at any time. The same access serves one other,
separate choice: *Show who called* for messenger calls (see Messenger calls),
which notes the caller's name from the switched-on messengers' call
notifications and reads nothing else.

To switch debugging off for good, uninstall JemRec first. If the recorder is
running, it notices the uninstall within a few minutes, turns both switches off
and exits; either way, once JemRec is gone nothing turns them back on.


## Off, and debugging with it

**Some banking apps will not open while Developer options or debugging is on**
(issue #37). Settings → *When JemRec is off* chooses what goes off with JemRec,
by the record button or the Quick Settings tile (`DebugSwitchesOff`):

| Choice | Switches off | Back on |
| --- | --- | --- |
| Keep debugging on (the default) | nothing else | instantly, anywhere |
| Hide Developer options | Developer options, Wireless debugging | instantly, anywhere |
| Turn all debugging off | Developer options, Wireless debugging, USB debugging | needs Wi-Fi - or *Start without Wi-Fi* - about 15 s; no answering machine until then |

The three are what each switch costs, measured on the Honor (Android 17),
2026-10-26:

- **Developer options' own switch** (`development_settings_enabled`) is only
  the menu. Written off, `adbd` and the recorder went on as before, and the menu
  was gone from Settings - which, on this phone, also means nobody opens it and
  has its Settings switch USB debugging off (see [Staying alive without
  Wi-Fi](#staying-alive-without-wi-fi)).
- **Wireless debugging** is needed only to start the recorder. Written off with
  USB debugging on and no cable, the recorder kept its process from 20:51 to
  21:01 through Wi-Fi off and on, almost six minutes locked and a real call,
  recorded both sides (-32 and -30 dB). Nothing switched it back on, not
  Android when Wi-Fi returned and not JemRec. With the cable in, two heartbeats
  2.5 minutes apart said the same.
- **USB debugging** is what keeps `adbd` up, and the recorder lives in `adbd`'s
  cgroup. Off, both go - on the Honor even with Wireless debugging on - and
  starting the recorder again needs Wireless debugging, so Wi-Fi.

So the first two keep the recorder and are instant both ways; the third takes
the recorder with it, which is the one an off in the field used to cost before
the switch was made soft. On then took 6 seconds to have it recording again,
twice, on the Honor (2026-10-26); the app's "about 15 s" is the slow end, a
revive that has to switch Wireless debugging off and on having taken 12 to 16
seconds. That is why the third says what it costs on its own line, and why,
away from Wi-Fi, turning off with it asks first - the only off that does
(`TurnOffAwayFromWifiDialog`, the same dialog in the app and, through a
see-through activity, from the tile). With *Start without Wi-Fi*'s port open
it asks nothing, and its line says *anywhere*: On comes back over the port
(see [Starting without Wi-Fi](#starting-without-wi-fi)).

**What goes off, and what comes back.** The app writes Developer options off
itself (it holds `WRITE_SECURE_SETTINGS`). For the third choice the recorder
writes Wireless and then USB debugging off, after a new command
(`COMMAND_DEBUGGING_OFF`): the recorder is never frozen, so it is the one that
waits out a call or a fetch in progress, gives the microphone back if a message
left it muted, and stops its own shield from putting USB debugging back. The
second write stops `adbd`, and the recorder goes with it. JemRec writes down
what it switched off - only what was on, so Wireless debugging already off away
from Wi-Fi is not switched on for nothing later - and On puts back exactly
that, USB debugging before Wireless debugging, then starts the recorder. A new
choice made while JemRec is off applies from the next off.

**Nothing brings debugging back behind the user's back.** Every way the
recorder is revived - the keep-alive job, the Wi-Fi and cable watches, boot,
opening the app, and the USB-debugging watch - goes through one function, and
it stands down while the third choice is in force. While JemRec is off with
Developer options hidden, a revive (after a reboot, or a cable from a computer)
turns Wireless debugging on to start the recorder and off again once it is up.
A recorder that dies while it waits out a call - a computer's cable pulled -
leaves its off unfinished; the next revive or keep-alive run that finds no
recorder finishes it, once. USB debugging the user switches on themselves while
JemRec is off stays on. Start fresh puts Developer options back, since setup
sends the user there.

Not measured: a banking app that refuses. Ours open with debugging on, so what
any one bank looks at is not known here; the order to try is the order above.
The emulator checks `hide_developer_options` and `debugging_off` run both
choices end to end on Android 12, 13, 16 and 17, and `start_without_wifi` the
third with Wi-Fi off, back on over the port.

---

Everything else that would have gone in a document is in the code, beside the
thing it explains: why the timestamps are counted rather than read, why an idle
call state is not believed the first time, why the daemon polls a setting
instead of observing it. Each of those comments records the measurement it came
from, because the reason is the part that stops someone undoing it.
