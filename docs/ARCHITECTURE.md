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
  [inside the recorder](#inside-the-recorder) ·
  [staying alive without Wi-Fi](#staying-alive-without-wi-fi) ·
  [starting without Wi-Fi](#starting-without-wi-fi) ·
  [unplugged from a computer](#unplugged-from-a-computer) ·
  [after a restart](#after-a-restart) ·
  [coming back without you](#coming-back-without-you) ·
  [Wireless debugging, only to start](#wireless-debugging-only-to-start) ·
  [stopped in the background](#stopped-in-the-background)
- The features: [recording and being asked](#recording-and-being-asked) ·
  [quality and size](#quality-and-size) ·
  [exact call tracking](#exact-call-tracking) ·
  [whose calls to keep](#whose-calls-to-keep) · [file names](#file-names) ·
  [messenger calls](#messenger-calls) ·
  [phone calls through the mixer](#phone-calls-that-play-through-the-mixer) ·
  [Google Meet and other apps' calls](#google-meet-and-other-apps-calls-through-telecom) ·
  [the answering machine](#the-answering-machine) ·
  [the Calls tab](#the-calls-tab) · [playing a recording](#playing-a-recording) ·
  [playing in the background](#playing-in-the-background) ·
  [playing at your ear](#playing-at-your-ear) ·
  [both sides in both ears](#both-sides-in-both-ears) · [three looks](#three-looks) ·
  [a tap while it checks](#a-tap-while-the-app-is-checking) · [one window](#one-window) ·
  [keeping and deleting](#keeping-deleting-and-cleaning-up) ·
  [marks inside the recording](#marks-inside-the-recording) ·
  [notifications](#after-the-call-and-when-something-is-wrong) ·
  [the last call's report](#the-last-calls-report)
- The phone: [permissions](#what-it-does-not-ask-for) ·
  [when the phone refuses the grant](#when-the-phone-refuses-the-grant) ·
  [what stays switched on](#what-stays-switched-on) ·
  [keeping the pairing](#keeping-the-pairing) ·
  [off, and debugging with it](#off-and-debugging-with-it)
- The code: [errors and cancellation](#errors-and-cancellation) ·
  [where coroutines run](#where-coroutines-run)

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
session. The *session* is short, and so is Wireless debugging: since 1.5 it is
on only for a start, and USB debugging stays on — see [Wireless debugging,
only to start](#wireless-debugging-only-to-start) and [What stays switched
on](#what-stays-switched-on).

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

**Two collect it, by one set of rules.** The call service collects a call's
recording as the call ends. Whatever a wake that did not land leaves on the
recorder is collected later: by the keep-alive, and, with exact call tracking,
a few seconds after the last call. Both go through one saver, and what each
does after a save is one table, so where the two differ it is written down:
only the call service tells you at once of a recording that came out empty or
could not be saved; only a later collection keeps trying a file that fails,
and gives it up after a week, deleting the recorder's copy. The missed-call check runs after both: after a
call the call service saved, and after each of the keep-alive's collections.

- A recording is noted as saved before the recorder's copy is deleted, so a
  copy that outlives its save — a delete that did not get through — is only
  deleted next time. A messenger call's too, since 0.27; before, it was saved
  a second time.
- A save cut short - the app's process killed in the middle of it, as Android
  kills one it needs the memory of - is finished or undone by the next save
  of that file (1.2, `SaveJournal`). Before each step the save writes down
  what it is about to do, at once (`commit`: `apply` "starts an asynchronous
  commit to disk", and a kill meanwhile loses it). Killed while writing, the
  places it opened are deleted - a MediaStore row still pending, a picked
  folder's file already under its own name - and the recorder's copy is
  saved anew under the same names; killed while publishing, every part
  written whole, the next save reads what each place holds before it changes
  anything - still pending, there, gone - publishes what is pending, notes
  each as the save would have, says it stopped early if it was to be (decided
  before the publish, and written down with it), and deletes the recorder's
  copy. A place whose provider will not say what it holds leaves it all for a
  later collection: taken for published, an unanswered question had the
  recorder's only copy deleted (review round 9). After a week of that silence,
  timed apart from a failing save's week, the recorder's copy is saved anew
  where saving goes now and nothing is deleted - if the place answers again,
  the call may then have two copies (round 11). A part lost - abandoned when
  it would not publish, or its pending row expired - is saved again from the
  recorder's copy, only parts no one has seen deleted first; each part is
  written down as published as it is, so one the user deleted since is told
  from one lost. With the recorder's copy already gone, nothing is undone.
  Produced on the Android 17 and 12 emulators (`killed_mid_save`, 2026-11-18),
  the debug build holding the save where the check kills it (`SaveHold`), or
  ending the process itself straight after a write of the journal: before, a
  kill right after publishing saved the call twice, the second as "(1)" -
  the ledger's note and the recorder's delete come after the publish, and
  `NonCancellable` keeps out a cancellation, not a kill - and a kill while
  writing left its MediaStore row pending, hidden, for Android's seven days;
  in a picked folder the file under the recording's own name showed. With
  the journal, the six kills first produced - mid-transfer, before and after
  publishing, in the standard folder and a picked one - were each saved
  whole and once, nothing left.
- A recording of two calls (call waiting) is saved as one file each, and noted
  only once every file is saved: a part that would not save is saved at the
  next try, the others again with it, rather than lost.
- A fetch says how long the file is before it sends it (1.2; the recorder's
  `fetch-length`, said in its answer to a ping), and one that brings fewer
  bytes is a transfer cut short - the recorder ended in the middle of it, as
  an Honor's unplug or Android's memory reaper does - and the recorder's copy
  is kept for the next try. Before, the bytes that came were taken for the
  whole recording and saved, and at the next collection the recorder's whole
  copy was dropped as one saved already. A recorder from before 1.2 says no
  length, and is read as it always was: an app talks to one for days after an
  update, until a revive replaces it.
- The recorder's list of its files ends with how many it sent (1.2; its
  `list-end`, said in its answer to a ping), and a list without that end is
  no answer: one cut short - the recorder ended mid-reply - read before as
  "these are all", let a pass take a file missing from it for gone, and a
  save it then finished let the next collection delete the recorder's only
  copy of a part that was lost (review round 10). A recorder from before 1.2
  says no `list-end`, and its list is read to its end as before - but not
  trusted for "gone": until round 11 of the reviews every list of one was no
  answer, and nothing it held was collected nor any missed call told until a
  revive replaced it.
- A recording is dropped when recording is switched off at its call's end;
  switched off and on again before the end, it is kept whole (the Honor,
  2026-11-02). Both collectors judge that moment: the call service reads the
  switch as the call's end reaches it; a later collection goes by the end
  Telecom gave, or else by the recording's own end, which is when the
  recorder's wake to the call service goes.

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

## Inside the recorder

The recorder is the package `com.jemcik.jemrec.shell`, beside the part of
scrcpy it keeps (`shellserver/PATCHES.md` lists both). Until 0.31 most of it
was one file of 3,559 lines; each part has a class of its own now:

- **The start** (`Main`): the main looper prepared before anything else
  touches Android, the environment read, the threads started in their order.
- **A capture that fails** (`Restarting`, `RestartLoop`, `RestartPolicy`): a read of nothing
  is read again for a while, and a failed one - Android's `AudioRecord` says
  `ERROR_DEAD_OBJECT` "needs to be recreated" - makes the capture anew, up to
  three times within a minute, each time noted in the call's report; past
  that the recording is said to have stopped - the call's notification says
  so during the call, and the answering machine hangs up on a message it can
  no longer keep - and a capture is made once every 10 s for the rest of the
  call, the recording going on, a gap in it, once one reads (said too). Every
  recording ended at its first failed read before 1.2 (scrcpy's encoder, as it
  came), and until 1.2's reviews at the fourth within a minute, the rest of
  the call unrecorded for a failure that may pass. Within a minute, not a recording: three
  a recording, as 1.2 first had, never came back, and an hour's call whose
  audio server went down four times, far apart, would have ended at the
  fourth - an Erlang/OTP supervisor, the practice this follows, gives up only
  on more than its intensity of restarts within a period; here, as the call is
  not the recorder's to end and a capture costs little once in ten seconds,
  that slows the restarts down rather than ending them. The call's end
  interrupts the slow wait (the encoder interrupts its input thread before
  the capture is stopped), so a hang-up's save does not wait it out; the
  wait is cut into one-second slices besides. A stall in which no read
  fails - a read that only blocks: the audio server held down, AudioRecord
  came back by itself after 22.5 and 23.7 s (below) - is said as any stop
  since round 10 of 1.2's reviews: five seconds with nothing delivered is
  told stopped, and back once reads have flowed for five seconds, as the
  restart policy's own back (`StallWatch`), where the call's notice went on
  saying it recorded. A stall still not back at the recording's end - one
  read or two since, or the restart loop's own stop since - is a loss from
  the last read to the end, and the file is named as ended early; reads
  still flowing at the end are back, no loss (review rounds 12 and 13). Each restart waits
  first, longer each time - 250, 500 and 750 ms - for an audio server still
  coming back, and a new capture that will not start uses a restart without
  the failed one being read again: without either, the three would have gone
  in milliseconds. The failed capture is stopped before the new one is made,
  and the new one is given it to take up where the call stood: left running
  through the wait, a phone call's mode thread could swap captures under the
  one being made (all three read in review, 2026-11-18; not measured). Not
  every failure of the audio server reaches it: killed in the middle of a
  call on the Android 12 and 17 emulators (2026-11-18), the server came back
  and Android's own `AudioRecord` restored itself, no read failing, a gap of
  1.1 to 1.5 s in the recording and its sound after it as before (the release
  check `audio_server_restart`). A read that fails all the same is what this
  is for; none has been produced on an emulator - held down by init for 2 and
  then 5 s mid-call on the Android 12 emulator, and for 20 s on the 12 and 17
  (2026-11-18), the server came back and AudioRecord restored itself each
  time, gaps of 3.2, 5.7, 22.5 and 23.7 s and no read failing; and a phone
  call on the mixer (the 17 emulator, the stand-in call's "communication"
  mode), the server killed or held down 5 s, had the copy's sound back with
  the server, no read failing either. So its decisions are plain Java, held by the daemon's
  tests (`RestartLoopTest`, `RestartPolicyTest`, `CallPathTest`); how
  `PhoneCapture` and `Voip` put them to use is read in review, not produced.
  A messenger call's capture, and a phone call's through the mixer, fail the
  same way when the other side's record does (`Voip.Capture.read`) - until
  1.2's reviews it went on as silence, unsaid; the microphone's failing lets
  the microphone go and opens it again once, said in the call's report, and
  the other side goes on - unlike the guard's giving it back, which is the
  call's for the rest of it. While a messenger capture is made anew, its
  audio policy is held: stopped first, with the messenger list emptied during
  the call, it would have let the policy go, and the call with it. A capture
  made anew takes up where the call stood, from the one read last
  ([`CallPath`](../shellserver/src/com/jemcik/jemrec/shell/CallPath.java),
  one value replaced whole): on the mixer or the call capture, as the
  call's mode had decided, with the checks for another app's call kept, and
  without the microphone where the guard had given it back to the call. A
  mixer that would not come - at the call's first look or later - is tried
  again each second, five times; refused at the first look, it was never
  asked for again, and the call lost its other side, which VOICE_CALL does
  not hear in such a call. Made as at the call's start, a capture made anew
  had put a call in "communication" mode straight on the mixer, past the
  check that keeps a Meet call over a held phone call out of its recording
  (read in review, 2026-11-18; not produced). A recording whose encoder ends
  before its call - the codec or the file failing, or a stop it never came
  back from (a capture that kept failing, tried slowly, or a stall) - is
  named so (`_early` and how much it missed,
  `Checks.earlyRecfile`), and said at once (the recorder's `RECORDING_STOPPED`
  wake), for the app to say it [stopped early](#after-the-call-and-when-something-is-wrong)
  of any call, a messenger's or one recorded with call tracking off too.
  Produced end to end on the Android 17 emulator (2026-11-18): the phone's
  storage filled for 6 s mid-call, tracking off, the encoder's write failed,
  the file was named `_early16085`, and the app said so (the release check
  `early_end`).
- **The port** (`LoopbackServer`, `Handshake`): bound to `127.0.0.1`, each
  connection on a thread of its own, who connects checked, then the proof
  above. **The commands** (`Commands`, `Wire`): what each does, and its bytes.
- **The call** (`CallWatch`, `Calls`, `CallDecisions`, `RecordingSession`,
  `Wakes`): the phone's call state as the recorder hears it; the call's state
  and what is done as it starts and ends; the rules for when a call is
  recorded, asked about or left out; one call's recording; the wakes to the
  app, in order.
- **The answering machine** (`AnswerMachine`, `Answering`, `AnswerEffects`,
  `AnswerTest`), and **the background jobs**: the uninstall watch
  (`Custodian`), the off-Wi-Fi shield (`Shield`), the cable (`CableWatch`),
  a background kill (`KillWatch`), exact call tracking (`Tracking`),
  debugging off (`DebuggingOff`); and the app's notification listener
  allowed, as only the shell can (`Listener`).
- **What is reached by reflection** (`Platform`): the telephony, audio and
  telecom services rebound to the shell's context, the USB service, system
  properties - each a place one phone can differ from another.

**What is tested without a phone.** The decisions are plain Java, compiled by
`shellserver/tests` without Android: the call's rules (`CallDecisions`, as
tables of every combination), the call states, the answering machine, the
protocol's lines and frames (`Wire`), the handshake (`Handshake`), the hop
to the main looper (`Hop`), the messenger copy's policy (`PolicyUse`), and
the call's file closed again if its recording fails to start before the
encoder has it (`Closing`; until 1.0 its descriptor was left to the
garbage collector). The protocol is written twice, in the app and here: every reply line is in
`shellserver/tests/fixtures/protocol.txt`, which the app's parsers read and
`Wire` builds, and one handshake worked by hand,
`shellserver/tests/fixtures/handshake.txt`, holds both sides' proofs.

**One lock, for the call.** A call's start and end, a recording begun and
stopped, a message split off, the Record yes, a skip, the list of
recordings, a quit's and debugging off's look at whether a call is up: one
lock (`Calls.LOCK`). The refusals' log, which held it too, across a read of
`/proc/net/tcp`, has its own since 0.31.

**The main looper is waited for one second.** The answering machine lives on
the recorder's main looper, where telephony delivers its events; a command
from the app - the go-ahead for a ringing call, Talk to them, Hang up, the
settings, a test call's arming - is handed to it and waited for. The app
waits a second and a half for the first three; the recorder waited two
seconds, and what it gave up on still ran when the looper came to it, after
the app had said it was not taken. Since 0.31 it waits one second, and what
has not started by then never runs (`Hop`) - with what it guards: the
settings are taken with the machine's own, a test call's "already one under
way" is asked with its arming. Settings that come before the machine is
made, as the recorder starts, are kept for it; a machine made meanwhile is
configured again from what is kept when its looper comes to that, which is
not waited for, so it ends with the last settings kept. The custodian's
hang-up of a message after an uninstall, which no app waits on, is still
left to run when the looper comes to it.

**Every change to the recorder replaces it.** The app compares the recorder's
build - the first eight hex digits of its jar's SHA-256 - with the jar it
carries, and replaces a recorder that differs at its next revive with an ADB
session: a quit, waited out during a call. An app talks to an older recorder
until then, for days off Wi-Fi, so a reply changes only on both sides at once,
and an addition goes where an older one reads past it (the ring's time after a
go-ahead since 0.17, a test call's id since 0.31).

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
again at once: on Wi-Fi, never in a call, and only while the switch still asks
for it, both asked again once the revive has connected, up to half a minute
on - and not while *When JemRec is off* has debugging switched off, which a
revive would have to switch Wireless debugging on for; the change waits for
On. After a reboot the first start opens it before starting the recorder, so
nothing restarts twice. Every flip of the switch has a revive of its own, and
what is due is asked there, under the recorder's lock: asked before it, a flip
that came while a revive was changing the port found nothing to change, and on
the Honor, 2026-11-05, on and straight off left 5555 open under a switch that
says off for nine minutes, until the next keep-alive closed it. A close still due
after that revive is tried once more ten seconds on: `adbd`'s restart takes
Wireless debugging's advertisement with it, for over 9 seconds on the same phone,
and a revive that can connect only over the port does not close it. Switched on
while the recorder runs, the next revive - the switch's own, at once on Wi-Fi -
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
After a refusal, mDNS can still hand out the port of a connection made before
it, and a connect there is turned away at once: nothing listens. Measured on
the Android 16 emulator, 2026-11-03: three revives in a row were given the
port of a connection 45 seconds earlier, each turned away within 2 ms, and as
two refusals in a row mean a lost pairing, the header said Pair again - whose
tap forgets a pairing that works. A refusal with the switch read off is now
taken for nothing advertised (`RecorderHealth.meant`), and the header asks to
allow the Wi-Fi.
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

**On some phones, pulling the cable out of a computer stops the recorder, and
JemRec starts it again by itself.** Measured on the Honor (Android 17),
2026-10-24: three seconds after the cable came out - Android's debounce of a USB disconnect -
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
two runs that were timed, nothing tapped, and back by itself in a third.
With Wireless debugging off between starts (1.5), about the same: recording
16 seconds after the recorder's wake, 2026-11-22. Off
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
ends before Android would show its notification. Plugging the Honor into a
computer stopped nothing either, the three times it was measured.

**Other phones do it differently, and the same watch covers them.** On a Galaxy
S20 (One UI 5.1, Android 13), 2026-11-04, it was the other way round. Pulling
the cable out stopped nothing. Plugging into a computer restarted `adbd`, all
three times, by its process's age, and took the recorder with it. The first
time's log shows why: about four seconds in, `android.process.media` had the
USB functions set again, and the USB configuration went to none and back. The
two times a recorder was running, the app's watch saw it go and had it back
15.5 and 19.7 seconds after the plug-in. On a OnePlus 13 (LineageOS 23.2,
Android 16), the same day, neither stopped it.

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

**Since 1.5 Wireless debugging is off before a restart**, unless the user had
it on, and switching it on is what starts `adbd`. Measured on the same phone,
2026-11-21, on the 1.4 debug build with the switch written off before the
restart and the cable out before the boot, nothing tapped: the run Wi-Fi
started at 19:39:28.5 read `adbd` stopped and the switch off, switched it on,
which alone started `adbd`, and had the recorder up at 19:39:38.9 - 10.4 s
after that run began, about 13 s after JemRec's first try, about 38 s after
the boot. The off and on above stays, for a Wireless
debugging the user had on.

**Android decides when the boot reaches JemRec.** Nothing of JemRec's runs
until Android hands it `BOOT_COMPLETED`, which it gives apps one after another,
and the queue hardly moves while the phone sleeps. Measured on a Galaxy S20
(One UI 5.1, Android 13), 2026-11-04, the phone locked again straight after the
first unlock: the broadcast was queued at 23:46:17, the phone slept from
23:46:59 to 23:50:40 but for a few wakes of a second or two, JemRec's turn came
at 23:51:03, and the recorder was up at 23:51:18, the app never opened. That is
five minutes after the unlock, about 70 seconds of them awake. How long it
takes with nobody waking the phone was not measured. Opening JemRec does not
wait for it.

**Android can cut the first start.** On a OnePlus 13 (LineageOS 23.2, Android
16), 2026-11-04, three seconds after JemRec connected at boot Android logged
`Detected wifi network change. Disabling adbwifi` and the session went, half way
through starting the recorder. The keep-alive's retry had it up 82 seconds
after the first try, the app never opened.

Wi-Fi is needed to *rebuild* the recorder, never to use it. Once it is up,
recording carries on with Wi-Fi off, on mobile data, anywhere.

## Coming back without you

Five things bring a stopped recorder back with nobody touching the phone: a
heartbeat every 15 minutes (`RecorderKeepAlive`; since 1.2 it never asks to be
retried, as WorkManager runs periodic work that does by its backoff - 30 s
doubling up to five hours - not its period, and a day off Wi-Fi had put the
next heartbeat hours away), and four that cut it short -
a one-time run at a restart, another when Wi-Fi returns (`NetworkRevive`), the
watch on USB debugging (`UsbDebuggingWatch`, a one-time run itself), and after
a cable is pulled the app's own revive (`CableWatch`), which falls back to a
one-time run. Opening JemRec starts it too, on the app's own. Each of these
jobs asks for a network, and the one-time runs but the USB watch are
expedited - its content trigger keeps it a regular job ("Expedited jobs may
only set network, storage-not-low, and persistence constraints",
`JobInfo.Builder.setExpedited`), so in the restricted bucket it waits for
the day's session: the revive
reaches adbd and the recorder over 127.0.0.1, and Android takes the network,
the loopback with it, from an app idle in the rare standby bucket or below
on battery - giving it back to a job only if it asks for one ("bypass Doze,
app standby, and battery saver network restrictions (if the job has a
setRequiredNetwork(NetworkRequest))", `JobInfo.Builder.setExpedited`). In the
restricted bucket, JemRec idle on battery (2026-11-18): without it, on the
Android 17 emulator, a kick's run started and its revive timed out at every
step, 15 to 18 s each - so too with the emulator's charger in, though whether
Android took that for charging, which lifts the standby firewall (AOSP's
AppStandbyController), was not read; with it, on the Android 12 and 17
emulators, the recorder was back 4 s after the kick.
Any network, not validated internet: WorkManager's "connected" becomes
JobScheduler's "any network", which asks for validated internet (the Android
12 emulator's dump), and on a Wi-Fi without internet with no mobile data -
where Wireless debugging works, as above - no job would have run; with a
request of its own, on the Android 17 emulator (2026-11-18) with its Wi-Fi
not validated and mobile data off, the jobs had their network and a kick
had an ended recorder back in 4 s. With no network at all - no Wi-Fi joined
and mobile data off, as a traveller often has it, calls still coming - a
revive over Wireless debugging could not succeed anyway, and the runs wait
for one, the collecting of a stranded recording, the missed-call check,
auto-delete and the recorder-down alert a revive posts with them - all but a
kick made while JemRec is in front - an activity on the screen, or a
foreground service such as the call's (its end, its watch's end and revive,
said so outright as the service may stand down before the kick is read;
review round 13) - which asks for none when there is none: the loopback is
never taken from an app in front
("visible or in foreground state: Network: No restrictions"), and a kick that
waited kept every kick after it (review round 9). A kick waiting on a network
ask that no longer fits is replaced. Not with Start without Wi-Fi on: its port needs no
network, so the heartbeat and the USB watch ask for none
(`RecorderKeepAlive.constraints`) - a run that waited for one never came
there (reviews, 2026-11-18) - and a one-time run asks for one unless the port
is open and the phone has no network (`runConstraints`): after a restart the
port is closed, a revive needs Wi-Fi to open it again, and a run that asks
keeps the network a job is given in the buckets and in Doze. The trade, for
that switch: in the rare or restricted bucket on battery a run that asks for
none finds no loopback, as every run did before 1.2.
The user's Battery setting *Restricted* runs none of them while JemRec is in
the background ([Stopped in the background](#stopped-in-the-background)).

**What each does is one table** (`Triggers.kt`): what each of these, boot,
opening the app, its check of setup, On and Off arms to keep the recorder
alive (`armedBy`); whether it goes on, by what it finds (`admit`) - boot and
Wi-Fi coming back ask whether the phone is set up, not the switch, as the
recorder is kept for an On anywhere; the app's own ask the switch; the
keep-alive and the USB watch ask setup, then whether JemRec is standing down;
and every revive asks that first; and what follows a revive (`followUp`) -
the keep-alive's Wi-Fi watch off Wi-Fi and its retry, the watches' hand-over
of a recorder still down. `RecorderLifecycle` reads what the table needs,
holds the recorder's lock, and is the one place in the app that writes the
debugging switches. A revive that has the lock runs to its end, as Start
fresh does: its changes come in pairs - Wireless debugging off and on, adbd
on the port and the port noted as JemRec's, an old recorder retired and the
new one started, Wireless debugging on and, once the recorder is up, off
again - and one cut between
the two, by a worker stopped or a screen closed from Recents, left the first
half alone (shown in the unit tests on a stand-in phone; what that left behind
is read from the code). Closing from Recents is what ends the screen: Back
only moves it to the background, since Android 12. Measured on the Honor,
2026-11-04: Start without Wi-Fi switched off and JemRec closed from Recents
6 seconds into the revive, with adbd's restart having taken the recorder: the
screen went, the process stayed, the revive ran on, and the recorder was back
3.7 seconds later.
Start fresh ends the recorder under the same lock, from asking it to quit to
forgetting the pairing key: a revive under way finishes first, and one queued
behind finds no key and starts nothing. Asked to quit before the lock, a
recorder a revive was starting could come up after the quit and run on - read
from the code, not seen on a phone; the unit tests show the order.

**In the release build, from 0.5 to 0.25.2, no one-time run ran**
([#69](https://github.com/jemcik/JemRec/issues/69)): not the restart's, not
Wi-Fi's, not the USB debugging watch, not the cable's fallback. The cable's
own revive, the heartbeat and an open of the app were left. Before each
one-time run WorkManager builds, by reflection, the small object that merges
the run's input (`OverwritingInputMerger`), and its own keep rule let R8
remove that object's constructor: each run ended failed before it began. The
debug build is not shrunk, so neither the emulator checks nor the Honor, which
run it, ever showed it. Measured on the Honor (MagicOS 11, Android 17),
2026-10-31, with shrunk builds - the debug build's tools through R8 with the
release's rules: Wi-Fi back with the app not on screen, the old code had no
recorder after 120 s and WorkManager's own record of the run read FAILED, 0
attempts (that build still debuggable, so R8 in its debug mode, which removed
the constructor as the release's pass did); 0.25.3's first build, not
debuggable, had it back in 21 s, and after a restart 7 s after the app
started, never opened. WorkManager 2.12 keeps the constructor itself, and the
checks now install the shrunk build ([DEVELOPMENT.md](DEVELOPMENT.md#build)).

**Wi-Fi's run is a run of its own.** A one-time run that finds no Wi-Fi tries
again with backoff - 30 s, then 60, 120 and on - and Wi-Fi's run used to be the
same work as the rest, kept rather than started while one waited. On the Honor
test above the recorder came back on the waiting run's next try, not on
Wi-Fi's. The waits double, so from the sixth a waiting try can be further off
than the heartbeat (WorkManager's backoff, not measured). Now Wi-Fi's run has
its own name, and tries again only while it is on Wi-Fi: off it, the watch is
armed again and starts a new one. Measured on the Android 17 emulator,
2026-10-31, with a run two minutes from its next try: 0.25.3's first build had
no recorder 45 s after Wi-Fi; with this, it was back in 10 s (`wifi_back`). On
the Honor the same day, the kick 58 s from its next try: Wi-Fi's run began 3 s
after Wi-Fi, and the recorder answered at 9 s.

## Wireless debugging, only to start

**Since 1.5, Wireless debugging is on only while JemRec starts its recorder.**
A start switches it on where it is off, gives `adbd` four seconds to
advertise, connects, starts the recorder and closes the session; then the
recorder switches Wireless debugging off again (`Revive.askWirelessOff`). Until
1.5 it stayed on from setup on, though nothing needed it between starts - and
while it is on, on that Wi-Fi, any computer ever paired with the phone can open
a shell on it with no question on the phone (a paired key is let in without
asking, AOSP `AdbDebuggingManager`; with *Keep JemRec paired* no pairing ever
expires), the phone announces itself over mDNS as `_adb-tls-connect._tcp`,
named after its serial number (`adb-<serial>-<six random characters>`, adbd's
`daemon/mdns.cpp`, read 2026-11-21), and `adbd`'s TLS port is open to the
network. Raised in discussion #124, which asked for Shizuku.

**Why off keeps the recorder.** The recorder lives in `adbd`'s cgroup, and
USB debugging is what keeps `adbd` up: AOSP's `AdbService.stopAdbd` stops it
only once neither transport is enabled, and every start switches USB
debugging on first (see [Staying alive without
Wi-Fi](#staying-alive-without-wi-fi)). Measured on the Honor (MagicOS 11,
Android 17): on 2026-10-26, Wireless debugging written off with USB debugging
on and no cable, the recorder kept its process from 20:51 to 21:01 through
Wi-Fi off and on, almost six minutes locked and a real call, recorded both
sides; on 2026-11-21, with a computer's cable in, `adbd` and the recorder
had the same process IDs 8 s after the off, and `adbd` kept its ID through six
starts after. On a Galaxy S23 (One UI 9, Android 17), 2026-11-21, with no
cable, five locks of about a minute with Wireless debugging on killed
nothing, and neither did the locks after with it off: the recorder kept its
process from 20:05:46 to 20:43:00. LineageOS switches it
off by itself a second after a start (the OnePlus 13, 2026-11-12, [What stays
switched on](#what-stays-switched-on)), and two Shizuku forks, which start
their service the same way, ship the off: thedjchi's after every start,
Stellar's after a start it has confirmed (their code, read 2026-11-21).

**The recorder switches it off, not the app** (`COMMAND_WIRELESS_OFF`,
`WirelessOff`). It is the shell, so it reads both switches' true values:
Android 17 hides USB debugging from apps on Pixels, and Android 17 QPR3 may
hide Wireless debugging too (Shizuku-Next #224: a test app read 0 where the
shell read 1). It writes the switch off only while USB debugging reads on -
off with USB debugging off would stop `adbd`, and itself - and answers what it
found: off, off already, USB debugging off (nothing written), or refused (an
OPPO, OnePlus or realme phone with *Disable system optimization* off refuses
the shell's writes). The app asks once the recorder is up and current and the
session that started it is closed - a session left open, by a missed ping, is
closed first; only for a Wireless debugging JemRec switched on; not during a
call - it is not switched off mid-call, as the port is not changed mid-call,
and the call's end has the keep-alive look again; and only in a process that
can switch it on again for the next start. Setup's own process cannot: the
app's `WRITE_SECURE_SETTINGS` is live only from the next one, so a start
there - the recorder dying, *Start without Wi-Fi* flipped right after setup -
would find nothing to connect to, where 1.4 found it on. So setup's end notes
it as JemRec's, and the first look in a later process switches it off; the
same keeps it on where the permission was taken back.

**A guard after the off.** A phone where the off stops `adbd` though USB
debugging is on has not been seen - not on the Honor, the S23 or the four
emulators, not in AOSP's `AdbService` 12 to 17, which stops `adbd` only once
neither transport is enabled, and not by the titles of thedjchi's Shizuku
fork's issues, which ships the off - but on one, every start would end with the recorder gone
and nothing would say so. So after an answer of off, or none (a recorder
taken with `adbd` can go before it answers), the app waits 2 s and asks the
recorder the confirmed way. Gone, it notes for good that Wireless debugging
keeps `adbd` up on this phone, goes round once more - switched on, recorder
started - and never asks for the off there again, until Start fresh
(`NetworkRevive.noteWirelessKeepsAdbd`). The user's choice, 2026-11-21: the
plan had dropped any check, as nothing showed the case.

**Whose it is.** The app notes that JemRec switched Wireless debugging on
(`NetworkRevive.noteJemRecs`): a start's switch-on from off, a switch off and
on from off (after a cable, or Allow this Wi-Fi's), and setup's end, where the user switched it on to
pair. One found on is the user's - for a computer, or another app - and stays
on, a start's off and on included. The note goes when the recorder answers
off or off already, and at Start fresh. Where it did not go off - USB
debugging read off, a refusal, no answer, a call - the next look that finds
the recorder up asks again: the heartbeat, opening JemRec, Wi-Fi coming back,
a call's end. One note, not one per Wi-Fi connection or boot: Android 17
keeps the switch on across Wi-Fi changes and restarts - its
`AdbDebuggingManager` writes it off on a Wi-Fi change only without the
`allow_adb_wifi_reconnect` flag (android17-release), whose
`AdbWifiNetworkMonitor` the S23 logged ("Received the same Wi-Fi SSID.
Ignoring."), and the 17 emulator kept it on across a restart - and a note per
connection, as first built, went void while JemRec's switch stayed on. What
one note leaves: an off by someone else - Android's, at a Wi-Fi change before
17 - and the user switching it on before JemRec's next look, after an off of
JemRec's was missed; that switch then goes off once. Found on or off is the
app's own reading of the switch at the start, the only one there is with the
recorder down; where Android hides the switch from apps - Android 17 QPR3, by
Shizuku-Next #224, not seen on a phone here - a user's would read off, be
switched on as JemRec's, and go off after the start. The off itself is decided
by the recorder's reading, which Android does not hide. Installs from before
1.5 had it on with no note: the first start after the update takes it over,
once (the user's choice, 2026-11-21), and with it any When JemRec is off
record of it, which On would otherwise put back as the user's for good; an
open of JemRec before that start does not use the once up. With JemRec off and Developer options
hidden, a Wireless debugging JemRec switched on is not written down for On to
put back ([Off, and debugging with it](#off-and-debugging-with-it)).

**What it costs: four seconds a start.** From a revive's start to the
recorder up, measured 2026-11-21 (the AVDs on the shrunk build; the Honor on
the debug build, with a computer's cable in and JemRec brought to the front):
with Wireless debugging on, 5.94-6.03 s on the Android 16 emulator, 5.88-5.99
s on 17, 6.27-6.31 s on the Honor; switched on by the
revive, 9.95-10.03, 9.92-10.01 and 10.28-10.33 s. The four seconds a revive
gives `adbd` after a switch-on (`ADBD_SETTLE_MS`) stay: without them mDNS
hands out the port of the last switch-on - a new one each time - and on the
Android 16 emulator two of five connects got nowhere in 25 s; after them,
all fifteen switch-ons on 16 and 17 connected at once. Nothing else changed
that was measured. No notification is posted for Wireless debugging being on:
on the Honor, with a computer's cable in (off the cable not read), "Wireless
debugging connected" only while a start's session is open, as in AOSP (`showAdbConnectedNotification`: "Show when at least one
device is connected"). With USB debugging on, switching Wireless debugging on
or off never writes Android's list of keys ([Keeping the
pairing](#keeping-the-pairing)). After a restart it depends on the phone: the
Android 16 emulator switched it off across a restart even when it was on, 17
kept it; the Honor with it off is in [After a restart](#after-a-restart).

**Android 12 and 13: found again.** There `adbd` announces itself through
the native `mdnsd`, and NsdService stops `mdnsd` 10 s after the last app's
request ends (`CLEANUP_DELAY_MS`, AOSP android12-release and
android13-release); the announcement goes with it, and is not made again
until the next switch-on. Measured on the Android 12 emulator, 2026-11-21:
found right after setup; after 15 and 30 s with nobody browsing, `mdnsd`
stopped and nothing found; switched off and on, found in 0.3 s. The emulator
checks do it on the 12 and 13 emulators (`wireless_between_starts`,
2026-11-22): the recorder killed 15 s after the last look, the start switched
Wireless debugging on and found `adbd`. Before 1.5 a
revive there that found Wireless debugging on and `adbd` running - a recorder
dead alone, or an older one an update was to replace, which was then kept -
found nothing, and the header
said *Tap to allow this Wi-Fi* for a Wi-Fi allowed long before; the emulator
checks never saw it, their revives falling back on port 5555. Every start
from off now switches it on afresh; one that finds it on - the user's, or
JemRec's whose off was missed - and nothing advertised switches it off and on
once per Wi-Fi connection, which announces it afresh, as a revive does where
`adbd` cannot be read (`cycleAfterNotFound`). And on 12 and 13 the app holds
an mDNS request of its own open from before the switch-on to the connect
(`AdbTransport.holdMdns`): a request active cancels a stop pending from an
earlier look (`storeRequestMap`), which could otherwise land just after the
new announcement - one NOT_FOUND after a switch-on on the 12 emulator, read
as that race, not proven. The request resolves nothing: NsdService takes one
resolve at a time from an app and turns a second away
(`FAILURE_ALREADY_ACTIVE`), so one that resolved could take the connect's. Android 14 moved
NsdService to an mDNS stack of its own, and the 16 and 17 emulators kept
finding `adbd` with nobody browsing.

**A Wi-Fi allowed once asks at every start.** Android keeps a Wi-Fi's
allowance only for *Always allow* (`MSG_ADBWIFI_ALLOW`); allowed once, it
asks again at the next switch-on, which since 1.5 is every start. An *Always
allow*-ed access point never asks.

Not measured: a phone where Wireless debugging off with USB debugging on stops
`adbd` - none seen; phones that lose the recorder at a screen lock with only
USB debugging on, as a Lenovo tablet and an Oppo phone did for a Shizuku fork
(thedjchi #108), where Wireless debugging on kept it - the S23's locks killed
nothing either way, and its USB mode change killed the recorder with Wireless
debugging on too; and whether a banking app that looks at Wireless debugging
alone now opens.

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
each only on a phone that has such a screen (none on a Pixel):

- **Asks during setup.** A step offers to open the phone's own screen for it,
  before the first call rather than after the first lost one. It is asked once
  — JemRec has no way to check the setting — and blocks nothing. The card and
  the warnings name that screen and say what to do on it in its maker's words
  ([`AutoStart.instructionFor`](../app/src/main/java/com/jemcik/jemrec/system/AutoStart.kt)):
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
  [`BackgroundKill`](../app/src/main/java/com/jemcik/jemrec/recorder/BackgroundKill.kt)),
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
  ([`CableWatch.heldBack`](../app/src/main/java/com/jemcik/jemrec/recorder/CableWatch.kt))
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

**Stock Android's own rules hold any phone's app back too**, and JemRec says
so. Measured on the Android 12 and 17 emulators (2026-11-18), on battery and
with JemRec's uid made idle - an app counts as active for a minute after it
leaves the screen (ActivityManagerConstants' background settle time), and
Android holds it back only once it is not. (Not the charger: the user's
setting and the restricted bucket both hold while charging - the Javadoc
below, and Android's App Standby Buckets page. A first measurement,
2026-11-17, seconds after JemRec left the screen, found nothing: review,
2026-11-18.)

- **The restricted standby bucket**, which an app left unused for days
  falls into: a call was recorded and saved at once, exact call tracking on
  or off: the call's service runs in the foreground, which has the network
  and is not rationed. The keep-alive is: a regular job runs "once per day
  for up to 10 minutes", and an app there in the background has no network,
  127.0.0.1 included. So its one-time runs are expedited and ask for a
  network ([Coming back without you](#coming-back-without-you)): a kick, the
  recorder ended, brought it back in 4 s (the release check), where without
  the network asked every step of its revive timed out. The heartbeat and the
  USB watch are still regular jobs there, once a day. With Start without
  Wi-Fi on, its runs ask for no network - its port needs none - and there
  they time out as before. A restart in that bucket brought the
  recorder back 51 and 164 s after the boot on the Android 12 and 17
  emulators - but Android had JemRec in the active bucket again by then, so
  what the bucket does at a real restart is not measured. An app falls into
  it after days unused (8 from Android 13, Android's App Standby Buckets
  page).
- **The user's Battery setting *Restricted*.** Android's word: "jobs and
  alarms will not execute and foreground services cannot be started unless
  an app activity is in the foreground", charging or not
  (`ActivityManager.isBackgroundRestricted`), and from Android 13 the boot
  broadcast waits until the app is started for another reason (Background
  optimization). Measured: every one of the keep-alive's jobs waiting on
  `BACKGROUND_NOT_RESTRICTED` in JobScheduler's own dump (Android 17), so a
  recorder that stops may stay down until something starts JemRec. At a
  restart it is not measured: the shell's Restricted does not outlive one on
  the AVDs - the app op written out (`cmd appops write-settings`) or the
  restriction level set (`am set-bg-restriction-level`), it read "allow"
  after the boot (Android 17 emulator, 2026-11-18) - and a recorder back
  114 s after one, measured earlier and said here, was the setting already
  gone. After a restart any start would do: its jobs are not kept over one, and
  WorkManager, finding them gone, hands its work to its own scheduler in the
  app's process (GreedyScheduler), whatever JobScheduler holds back - with a
  network asked, only where the app's network is not blocked (read from
  WorkManager 2.12.0's sources; not measured). Not at a plain start of the
  process, where that scheduler is handed only work enqueued then. Auto-delete and Recently deleted's 30
  days wait with the keep-alive, a day or more late. (A first probe also ended the recorder and turned Wi-Fi off and on,
  and nothing came back - nor would it have on an app held back by nothing:
  the Wi-Fi watch is armed only by a run off Wi-Fi, which a few seconds off
  it never had. Not evidence, and not said.) With exact call tracking off, Android refused both of a
  call's wakes ("Error: Not found; no service started"): the call recorded by
  the recorder, but saved, and told of, only once JemRec was opened. With
  tracking on, a phone call was saved at once: Telecom binds JemRec's call
  service for the call first, which brings it out of the background; a
  messenger call has no such bind (inferred, not measured). In Ask first, a
  call whose question cannot be put is not recorded: with tracking off that
  is every call, and the header says *Cannot record*, as it does for Ask
  first's notifications off; with it on, a messenger call most likely
  (inferred), and the header says Ready, which a phone call is.
  So JemRec says so: on home while it is set, and once in a notification at
  a start of its process, with the way to App info, where it is lifted
  ([`BatteryHealth`](../app/src/main/java/com/jemcik/jemrec/system/BatteryHealth.kt);
  *Not now* puts the card away, but not in Ask first). JemRec does not go
  round the setting - the shell could let each wake past it - as it is the
  user's.

The release check `battery_restricted` keeps each of these so.

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
phones; it is not built. Played in JemRec, the two sides are heard in both
ears unless you choose otherwise, and the file keeps them apart either way:
[both sides in both ears](#both-sides-in-both-ears).

A Quick Settings tile turns recording on and off without opening the app. It
is the same switch as the one in the app's header. A long press on it opens
JemRec, not App info: the main activity takes `ACTION_QS_TILE_PREFERENCES`, as
Android's Quick Settings tiles guide says (asked for in issue #37).

## Quality and size

A recording takes about 12 MB an hour since 1.2, a fifth of what it did.
Settings → Saving → **Quality** has four choices, each with its size an hour:
Smallest, 24 kbps, about 9 MB - "the two sides of a call may blend into
one"; Standard, 32 kbps, about 12 MB, the default, for updates too; High,
64 kbps, about 29 MB; Highest, 128 kbps, about 58 MB, what every recording
was before - less the pages: with Android's muxer's page to every packet, the
same rate came to about 63 MB an hour (below). A choice is for calls recorded from then on: the recorder reads
the rate once, as a call's recording starts. Asked for in discussion #84.

- **Why 128 was too much.** It was scrcpy's default. The 101 recordings on
  the Honor (MagicOS 11, Android 17) averaged 139.9 kbps, 1.05 MB a minute,
  about 63 MB an hour (MediaStore's sizes and lengths, 2026-11-16). A call
  carries far less: on the phone network AMR-WB, 50 to 7000 Hz in at most
  23.85 kbps (GSMA IR.36), or EVS (GSMA's HD Voice+ asks at least 13.2 kbps
  for super-wideband, 24.4 for fullband); in a messenger, Opus at a few tens of
  kbps (WebRTC's default is 32 for fullband). RFC 6716 puts Opus's own sweet
  spots at 16-20 kbps for wideband speech and 28-40 for fullband.
- **A blind test.** On the Honor, 2026-11-16: about 45 s of a phone call, 45 s
  of a messenger call and 26 s of an answering-machine message, each encoded on
  the phone with its own Opus encoder at 24, 32, 48, 64 and 128 kbps, the
  letters shuffled and the key read only after the answers. Its owner heard no
  difference through the phone's speakers, nor through earbuds, but for one:
  the messenger call at 24 kbps "sounds in mono". That fits what libopus's
  source says its speech mode does - narrow the stereo when the share of bits
  for what both sides have in common falls below its minimum
  (`silk/stereo_LR_to_MS.c`, "reduce stereo width", read in 1.3.1) - though
  the encoder was not asked; below about 19 kbps it codes stereo as mono
  outright (`opus_encoder.c`). So 32 is the default, and 24 is offered saying
  what it costs.
- **What each choice came to** in that test, kbps for the phone call, the
  messenger call and the message - Opus gives quiet stretches fewer bits, so
  a recording is about this, not exactly: 24 → 18.2, 23.1, 17.0; 32 → 23.4,
  30.0, 21.6; 64 → 65.1, 65.1, 63.3; 128 → 129.4, 129.5, 125.2.
- **Fewer page headers.** Android's Ogg muxer (AOSP's `OggWriter`, behind
  `MediaMuxer`) ends a page after every packet: 20 ms of sound to a page, 50
  pages a second, each with a 27-byte header and its lacing - about 11 kbps,
  whatever the bitrate (read in its source; on the Honor, the pages read of a
  recording each 960 samples on from the last and about 350 bytes, 29 of them
  header). At 24 kbps that
  would be a third of the file. The copy that puts the
  [marks](#marks-inside-the-recording) into a recording now joins its audio
  pages about a second to a page (`OggPages`; RFC 7845 allows 255 packets to
  a page and sets no limit on how long one plays), every packet as it was.
  Every page is also given the position Ogg Opus means (RFC 7845, 4): where
  its last packet *ends*, in 48 kHz samples from the start, worked out from
  each packet's own first byte - and the last page is marked the stream's
  end. The muxer writes where that packet *starts*, and marks no page last
  (measured on the Android 12 and 17 emulators, 2026-11-17). RFC 7845 calls
  such a stream invalid - its first audio page at 0 with 960 samples on it -
  and libopusfile refuses to open it (`op_find_initial_pcm_offset`, read in
  its source, not run), so players built on it would have too; CoreAudio,
  AVFoundation, ffmpeg, Chromium and
  Android, all that had been tried, played it. And Android heard it wrong:
  its extractor stamped each page a packet early, and its decode **cut 312
  samples a channel, 6.5 ms, at the start of the second packet, 13.5 ms in**
  - through MediaExtractor and MediaCodec on the Android 17 emulator, a 34 s
  test call's sound jumped from 15456 to -334 there; with the positions
  written so, it decoded without the cut, 1701 × 960 - 312 samples a channel
  as RFC 7845 counts them, its start as libopus (ffmpeg) decoded it, and
  MediaPlayer gave it 20 ms more, its last packet. The same on the Android 12
  emulator, six calls in all (the debug build's `repage`). Through 1.1 this
  note read those 312 samples the other way - that joining the first page
  made Android play the pre-skip as sound; it was this cut, gone.
  The first audio page is still not joined: MediaPlayer and ExoPlayer landed
  on the same millisecond at five seeks either way, MediaStore gave the same
  length, and the file went from 9,490,960 to 8,773,057 bytes (the Honor,
  2026-11-16). In the test above the joined pages saved 10.5 to 10.6 kbps at
  every rate.
  A save retried without a work file - storage for one copy, not two - is
  written straight into place and keeps the muxer's pages, as it keeps no
  marks; their positions and last mark are written where they lie
  (`OggPages.mend`), each page its own length, and the file synced before
  the recorder's copy may go.
  **Recordings from before 1.2** get the same where they lie: each
  keep-alive run, every quarter hour or so, goes on through JemRec's own a
  minute at a time until it has been through every one once - once for each
  folder chosen in turn, and only with that folder there - passing over
  any already marked after a look at its last
  page (`OldRecordingPages`). Only Opus streams, only names JemRec gives,
  only files this install may write - after a reinstall, Android counts the
  old install's as nobody's, and those play as they did. Stopped part way, a
  file's positions still only go on.
- **Not offered.** Recording in mono: with Opus a bitrate sets the size, not
  the channels, so mono would make a file no smaller at the same rate, and the
  two sides, once mixed, never come apart again - the after-call check that
  finds a dead side needs them. Other formats: Android guarantees no MP3
  encoder,
  uncompressed stereo at 48 kHz is 11.5 MB a minute for a call that was lossy
  already, and Share's `.m4a` already reaches Apple's and Windows' players.
- **To the recorder.** The rate goes in the recorder's environment at its
  start (`JEMREC_QUALITY`) and as command `Z` on a change, the way the
  messenger apps and exact call tracking go; a value that is not a rate keeps
  the one there was. A recorder from before 1.2 hangs up on it and records at
  128 kbps until the app replaces it, at its next revive. *Copy a report* says
  the rate a call was recorded at.
- **Checked** by the emulator check `quality`: each of three rates chosen in
  Settings is the rate the recorder starts the next call at, a recorder
  started afterwards takes it from its environment, and the file has its
  first audio page alone, no other page more than a second of audio, each
  page's checksum good and its last position its length, to the second.

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

Call tracking reads. The permission would let an app answer, hold and end
calls; JemRec's service answers and holds none, shows no screen, and ends one
call only: the answering machine's, when its recorder is lost mid-message
(1.2, `MessageHangUp`, below). (The
[answering machine](#the-answering-machine), when you switch it on, is the
recorder's doing, under the shell's own permissions: it answers, mutes and hangs
up the calls it takes messages on, and nothing else - but for that one hang-up,
its recorder gone.) It is on unless you
turn it off in Settings (*Exact call tracking*). Off, the daemon takes the
permission back and JemRec works exactly as it did before 0.14. The answering
machine answers on tracking's word, so it goes off with it: with the machine
on, the switch first asks *Turn off the answering machine too?* - until 0.19
it turned the machine off without a word (the user found it, 2026-10-24), and
until 0.30 it still did in the moment after the app opened, before the screen
had read the machine's setting - and the machine's row in Settings then says
it is off for want of tracking. JemRec keeps what Android said about the last 40 calls - when each began, was answered and
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

One recording can hold two calls - call waiting - and the rules may leave
out one and keep the other. The call left out is noted in the ledger (the
missed-call check reads it) only as the kept part is safely saved, with the
part's own entry, or as an empty recording is dropped: the recording's own
call is noted at the file's start, which tells the next collection the file
was saved, so noted before the write, a save that failed - the recorder out
of reach, the app's process killed, a full phone - had its retry delete the
recorder's only copy and the kept call was lost. A save cut short after
publishing notes it as it finishes (the save journal carries it; 1.2's
capped review, reproduced in RecordingSaverTest).

## File names

- **Date and time**, the default - `20261020_165705_out.ogg`.
- **With the caller** - `20261020_165705_out_Priya Raman.ogg`.
- **With the phone number** - `20261020_165705_out_+447700900123.ogg`: the
  number even for a contact, for a program that sorts the files by who the
  call was with ([#71](https://github.com/jemcik/JemRec/issues/71)). That
  place in the name holds a number or nothing, never a name. It is written
  with its country code, read in the country the call log gives each call
  (`UA` on all 178 calls on the Honor, 2026-11-01) - else the one the phone's
  network or SIM is in - so `050 123 45 67` dialled and `+380501234567`
  ringing in are one number in the names; a short code such as `477` keeps its
  digits. A hidden number leaves the date, time and direction, and so does a
  code such as `*86`; `#31#` or `*31#` in front of a number, to hide or show
  caller ID for that call, is left out, and so is an extension dialled after a
  pause. A messenger call has no number: its name is the date, direction and
  app, and its caller is kept as for the date format.
- **For Honor's Phone app** - `Priya Raman@+447700900123_20261020165705.ogg`,
  or `+447700900123_20261020165705.ogg` for a caller with no name, in
  `Sounds/CallRecord`: the name and folder Honor's own recorder uses.
  Honor's Phone app then shows a play button on the call in its history.
  Offered only on a phone that has that app. Tested on an Honor: the time
  may be anywhere inside the call, the number has to be the call's, and the
  part before the `@` is not matched - but the Phone app reads it as a
  regular expression, to take the `_` out of it. Until 1.2 a caller with no
  name got the number there too, `+447700900123@...`: a pattern with a
  dangling `+`, which the Phone app fails on, reading no number, so those
  calls had no play button (the Honor, 2026-11-17: a real call and two
  copies had none, the same recording named with the number alone got one;
  the cause read in the Phone app's code, HnContacts 19.0.8.606). Now a
  name loses what a pattern gives a meaning to, and a caller with no name
  gets the number alone (`FileNames.honorWho`, `HonorNames`); a test reads
  every name JemRec makes the way the Phone app's code does. Recordings
  already in `Sounds/CallRecord` under a name the Phone app reads no number
  in - by that same reading of its code (`HonorPhoneApp`) - are renamed to
  the one 1.2 gives the same call, as the folder is read and while it is the
  folder recordings go to (`HonorRenames`); one it reads keeps its name,
  odd characters and all - in review, "Dr. Who" and "Mom (work)" were found
  renamed for nothing (2026-11-18). What JemRec remembers of each by name -
  kept, a message, which way the call went - goes with it, a name already
  taken is left as it is, and one restored from Recently deleted is put
  right at the next read.
  Honor's own Recorder app does not list them, though it plays one the Phone
  app hands it: its lists scan `Sounds` and `Sounds/CallRecord` and keep only
  names ending `.amr`, `.m4a` or `.wav` (`FileUtils.AUDIO_EXTENSIONS`, read in
  Recorder 17.0.2.705), so an `.ogg` is dropped before anything else is
  looked at - its log on the Honor, 2026-11-17, found 0 files in a folder
  holding 2; the `.nomedia`, Honor's recording databases and the names play
  no part, and the Phone app's play button goes straight to the file. Only an
  `.m4a` would be listed, and that is a second format, which 1.2 does not
  have (the user's decision): AAC holds speech worse than Opus at these
  rates, so the same sound would take about twice the size (the codecs'
  known behaviour, not measured here); everything that reads a recording -
  its tags, pages, waveform, the check of its two sides - reads Ogg Opus;
  and the Recorder deletes and renames what it lists, which JemRec would see
  only as recordings gone or renamed.

The caller and the number come from what Android gave exact call tracking,
or else from the call log as the call is saved - either way, only with the
call-log permission. Without it, or for a call neither knows, a recording is
named by date and time. A name is given once and never changed. If a file of
that name is already there, Android makes another, ` (1)` before the extension,
in the Recordings folder and in a folder you chose alike (the Android 12 and 17
emulators, 2026-11-06), and JemRec does the same in app storage. That is the
recording's name from then on: JemRec reads it back as it saves, and goes by it
for everything a name is kept for. Up to 0.28 it went on with the name it asked
for, so the marks landed on the other file. Such a name reads as the one asked
for: the same time and direction, and any caller in it; after a caller the
` (1)` stays, as it may be the caller's own. The one exception to "never
changed" is a recording restored onto a name taken meanwhile: in app storage it
gets a ` (1)`, what Android's providers do then was not measured, and a mark
kept only in the app stays with the old name.
Google's Phone app has no equivalent: it keeps its own recordings in a
database of its own, and nothing in it is open to another app.

## Messenger calls

WhatsApp, Telegram, Signal and Viber calls can be recorded too, both sides,
each app switched on separately in Settings - and Google Meet's calls and
meetings, though Meet reaches the recorder another way (below). Nothing is
recorded from any of them until you do.

Each build of an app is an app of its own to Android, and has to be named in
the manifest's `<queries>` or Android 11 and later hide it from JemRec.
Telegram from telegram.org is `org.telegram.messenger.web`, and Settings called
it not installed until 1.0 ([#93](https://github.com/jemcik/JemRec/issues/93));
Telegram's source builds a beta as `org.telegram.messenger.beta` too
(`applicationIdSuffix` ".web" in `TMessagesProj_AppStandalone`, ".beta" in
`TMessagesProj_AppHockeyApp`, read 2026-11-14). Settings lists them as
*Telegram (telegram.org)* and *Telegram Beta*, beside the Play Store's
*Telegram*; everywhere else each is Telegram - the ask-first prompt, the
file's `_telegram` tag, and the reading of a caller's name, which drops a
notification text naming the app. A test (`PackageQueriesTest`) holds every
package JemRec looks for to the manifest's list. The recorder takes any
package it is sent.

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
  last 60 call notifications (a repeat within two minutes not noted again),
  each with which app it came from - deleted when Show who called is turned
  off - and, once a recording is saved, its caller by the recording's file
  name (`name_callers`, with its other marks: the newest 2000).
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
when it ends - once no capture reads through it: each claims it as it starts
(since 0.31; a call flipping to the mixer, back and to it again could find it
disarmed under its new capture, and record that stretch silent on Wi-Fi
Calling - read from the code). You are on the left channel and the other person on the right,
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
Qualcomm mixes into what the caller hears. A call that goes through the mixer
instead, as Wi-Fi Calling does on a Galaxy S23, has no such output to mix into:
it gets Android's call-screening route (below).

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
  says LTE for a Wi-Fi call there. Since 0.31 the recorder reads Telecom's own
  mark instead - the call's `wifi` property, the one the call log's Wi-Fi flag
  is taken from - in `dumpsys telecom`'s list of the calls there are now, three
  seconds into each greeting a test plays, together with the route and the
  voice type, so a result is named by the path its route was checked on. On the
  same S23, then on Android 17 (2026-11-12), every Wi-Fi call carried the mark;
  the one test made with it read - cut off by the move below, read as the
  machine hung up on it - was filed as Wi-Fi Calling.

Measured on that S23 on Android 17, 2026-11-12: a call answered on LTE can be
handed over to Wi-Fi while the greeting plays. Two of the five calls of that
session began on LTE. One, a test call on the call-screening route, was cut off
2.2 s after the greeting's track began: Android releases a call-screening track
the moment the audio mode it applies leaves the family the track was made in
(`CallInjectionModeChangedListener`, in AOSP's `AudioManager`) - in call, call
screening and call redirect for a phone call's, communication and its redirect
for one through the mixer - so the track died with the move, as it does at
every call's end, and the machine, taking it for a greeting that could not
play, hung up on the caller. The other, on the in-call music route, played on
through its move. Since 0.31 a greeting whose track is released under it is
played again from the start once the call's audio is back - at most twice a
call - the microphone kept muted, put back if the move dropped it, and held so
through the 1.5 s the greeting begins with. Back is the mode Telecom asked for,
once Android has applied it too (below) - where Android does not say, the mode
asked for alone, as AOSP's `AudioService.getMode()` gives it, which Android
applied 0.37 s after the cut there. While telephony says the call is over, the
cut is the end's: nothing plays, and it hangs up only if the mute will not go
back on. Seen on that S23 the next day, on a build that played again on the
mode asked for: a test call answered on LTE, its Wi-Fi turned on as it was
answered, was handed over 12.7 s into its greeting; the greeting played again
from its start 0.05 s after the cut, on Wi-Fi, to the beep, and the caller
heard it, the microphone muted throughout. Android applied communication 0.55 s
after that cut, which a greeting played again now waits for.

Measured on that S23, 2026-11-13, over VoLTE, its Wi-Fi off: the call-screening
route's greeting played in full - its 11.6 s in 13.0 s - with no error, and the
caller heard nothing, as on the Honor; the in-call music route's was heard,
from its start to the beep. Over Wi-Fi Calling it is the other way round:
the call-screening route was heard (above), and in-call music was not, on a
call that moved to Wi-Fi 1.5 s into its greeting (2026-11-12). Until 0.31 one route served every call on a phone, so the S23 could
greet callers on one of the two; and after a VoLTE test the caller did not
hear, the app kept the call-screening route, as it had passed over Wi-Fi
Calling. Since 0.31 the route follows the call: one through the mixer gets the
call-screening route, the one that reached it on the S23, and one on its voice
path the route the phone's tests found - on the S23, in-call music. A test
through the mixer stands for neither route the app chooses between: its pass
keeps nothing, its failure moves nothing. On the update to 0.31, a phone a
failed test had moved to the call-screening route, though its checks found
in-call music, is put back on in-call music once, its test results cleared -
they could not tell a call through the mixer from one on its voice path - so
its settings say it is not tested - and, on a phone that comes to 1.0 from
0.30 or earlier, why (a phone that ran 0.31's step was told nothing then, and
is not now). On the update to 1.0,
every phone's VoLTE result goes, once, with its mixer mark and a question
about it still to be asked: until 0.31 a test over Wi-Fi Calling was filed
under VoLTE where the voice network read LTE through it, as the S23's did,
and a stored result does not say which version filed it. The two steps run
as one, under one lock, at the first read of the route, the results or the
pending test; the Test call row says an update cleared them, and why, and
Settings' Answering machine row, while the machine is on, to test again -
until a test is filed. A call that moves while its greeting plays
has the greeting stopped by the recorder as Android applies the new mode, and
played again from the start on the route for its new path, counted with the
cuts above: in-call music's track is not released by a move - on that S23 one
played on after its call went to Wi-Fi, all 11.6 s of it in 12.3 s, its output
left with no device (Android's log: it refused the speaker). Every greeting,
the first too, waits for Android to apply the mode Telecom asked for, which
came 0.3 to 1.7 s later on the S23 as a call moved: a call-screening
track made before a mode in between is applied goes with that mode (AOSP's
AudioManager), and an in-call music track made before in call is applied may
get no in-call music output, which is chosen as the track is made and only
while in call is applied (AOSP's AudioPolicyManager). Past 2 s without
Android's word, the mode asked for goes, as before 0.31, and a greeting on the
call's voice path plays again once Android says it applied it; one through the
mixer, whose injected track takes no output from the mode applied, has its mute
put back and held instead. Communication mode is a move only while no other app
is in a call of its own and Telecom holds the phone call, or cannot say - the
recording's questions, though where Telecom cannot be asked whether an app is
in a call of its own, that counts as no app's call here, and as one for the
recording. A mode that leaves the call's way and comes back to it - in call,
normal, in call - is not taken for a move: not seen on a phone, and a greeting
played again for each would count towards the hang-up.

Measured on that S23 on Android 17 with 0.31's recorder, 2026-11-13, four test
calls, each greeting begun only after Android's word. Over VoLTE, Android
applied in call 0.55 s after Telecom asked for it, and the greeting began on
in-call music 0.14 s later; over Wi-Fi Calling it applied communication 0.44 s
after, and the greeting went on call-screening. Both were heard. A call
answered on LTE, its Wi-Fi turned on 1.6 s into the greeting, went in call,
normal for 0.28 s, then communication: its in-call
music greeting played on through normal, was stopped as communication was
applied, 10.8 s in, and played again on call-screening 0.26 s after Android
applied it - the greeting heard twice, the beep only after the second, the
microphone muted throughout the move. A call answered on Wi-Fi, its Wi-Fi turned off 1.7 s into the
greeting, went to normal 1.65 s later, which released the call-screening track
before the greeting's words began; in call was applied 0.84 s after that cut,
and the greeting played again on in-call music 0.12 s later and was heard once,
in full. Its test was filed under VoLTE, the path its greeting was heard on.

**A message's length** is the user's: 30 seconds to 2 minutes, then the
recorder hangs up - or no limit, which ends the call after 30 seconds without a
sound from the caller, because a pocket dial or a line left open would
otherwise hold the phone in the call for as long as it stayed open. Whatever
the length, a greeting or a message whose recording stops - its capture
failed too often, or its file could not be written - is hung up on at once
(1.2's reviews): nothing more of it can be kept, and the silence watch, which
hears the caller through that recording, would take a caller still talking for
30 seconds of silence (read in review; not produced). Stopped as the call is
answered, before the greeting, it is remembered and no greeting is played into
it; nor is a call the user is taking over hung up. Produced on the Android 17
and 12 emulators, the phone's storage filled mid-message (`early_end`): hung up
1 to 3 s after the recording ended, what was kept saved. Too short to be said
to have stopped early, which a recording is from 5 s missed.

**During a message**, a notification - *Taking a message* - offers *Talk to
them* (the greeting stops, the microphone opens, the call carries on) and *Hang
up*. Unmuting on the call screen does the same as *Talk to them*, and so does
answering a second call - a waiting one, or one the user places - which the
call tracker sees become active: left to the machine, the greeting would play
into it, the microphone stay muted, and the message's limit hang it up. A call
taken over is the user's own: an ordinary recording, not a message, from just
before they took it - or the caller's first words, if those came first; kept
from the answer, it began with the user hearing their own greeting in silence.
In Ask first the takeover ends the message there instead: what the caller said
is saved as the message, and the user is asked, as at the start of any call,
whether to record the rest - the caller agreed to leave a message, not to a
recorded conversation, and answering machines have always stopped recording
when picked up (Gigaset, BT, VTech manuals; Pixel's Take a Message). Another
app's call taken instead - a messenger or meeting call - is not seen as a
takeover: the microphone stays muted, unmuted only by Telecom for a call
answered through it or by a tap, and the message's limit hangs up the call in
front, which may be the app's, as before 0.31; the recorder cannot yet tell
such a call ringing from one under way (AOSP's isInSelfManagedCall counts
both). The recorder leaves a marker while it holds the mute, and a recorder
started after a crash gives the microphone back - at once between calls, or as
the call in progress ends: muted, the room stays out of a message whose caller
is still on. A recorder ended mid-message takes the machine with it - its
greeting, its limit, its hang-up when the recording stops - and from 0.17 the
caller heard silence until they hung up: a recorder started again cannot tell
that call from one of the user's. The call's own service can, with exact call
tracking on (1.2, review round 13, `MessageHangUp`): Telecom binds
`CallTracker` into every call, and ending a call through its own
`android.telecom.Call` ends that call and no other (AOSP Telecom's
`InCallAdapter.disconnectCall` takes the call by its id, for any in-call
service bound; read, not produced). So its watch, finding the recorder gone
while a message is taken, hangs up the message's call - the one call up and
active as the message started - while it is still that call, still active,
and the microphone still muted (given back, someone took the call); where
Telecom has not told the tracker which call it is, it leaves the call up, as
before. The message's file, which the
recorder names as one only as its call ends, is saved as a message - whole,
the greeting at its start - by when its greeting began (`LostMessages`), and
a test call's is not kept (review round 14). Telecom gives the microphone
back by itself as the call ends (AOSP `CallAudioRouteStateMachine`, its
reinitialize), and the recorder started again clears the mute it left. The
release check `message_recorder_lost` kills the recorder mid-message. The mute comes as soon as the call's audio is up - on the Honor
that is before the network connects the call (2026-10-22: 14 ms after the
answer, connected at 235 ms) - and the greeting, and the app's *Taking a
message*, wait for the off-hook: a caller gone in that moment left no call, and
nothing to undo but the mute.

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
From Record until the recorder answers Stop, the greeting takes no tap -
over the loudspeaker it would go into the take - and Record and Delete are
greyed until the take is a greeting (until 0.30 Record then did nothing,
with no sign, and a Delete was undone when the new greeting was saved). A
preview of the old greeting ends as the new one is saved, and a greeting
that cannot be saved - a full disk - is said on its row, as a take the
recorder did not send is (it was "Nothing was heard"), and one with nothing
in it ("Nothing was recorded"). A take stopped is made a greeting of past its
screen - closed from Recents while the take was fetched, the greeting was
never saved - and meanwhile no window starts a take, which would overwrite
the file being read, or deletes the greeting, which the saving would undo; a
screen opened meanwhile shows the new greeting once it is saved - or, when
the take is no greeting, why. A
Stop the recorder does not answer holds the
greeting until its own thirty seconds are out - let go by that take only.
Leaving the screen - Back, a link - stops a take still recording, and one
asked to start once the recorder has started it (a stop sent first is lost
to the start), and does not keep it: it went on unseen. A notification's
Play plays once the recorder has answered that stop, waited for up to five
seconds (in 0.29 it played into the take, which was kept). While a take may
have the microphone, as far as the app knows, nothing JemRec plays starts -
from any of its screens or the media notification - and no other screen
starts a take, previews the greeting or deletes it - Record, Delete and the
preview are greyed there too - and a preview already playing in another
window stops as the take begins; one preview plays at a time. Left in the
background - Home, another app - a take records on to its thirty seconds and
is kept, and a recording played over the loudspeaker then would have become
the greeting (read from the code). The hold is the take's own, let go by that
take only, and timed on a clock that setting the phone's time does not move.

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
   calls it never was, though the caller heard it. An armed test is kept until
   it is read (until 0.30 it went with the screen that armed it, and one
   closed while arming went unread): the screen opened again, or back at the
   front, asks the recorder how it went, and the recorder keeps that until
   the next test. One restarted since keeps none, and the screen says so; a
   recorder that cannot be asked, or a call still under way, leaves the test
   for the next opening, and it is read before another is armed. One test is
   armed at a time, an arming that outlived its screen included, and a
   screen opened while it is under way shows the test once it is noted, or,
   if it armed nothing, why. Each arming carries an id, the test's window's
   end, and the recorder names its results with it (since 0.31): an arming it
   did not answer is read back - armed, and watched, or not - and a result
   that is another test's is never read as this one's. The recorder answers
   an arming before an outgoing test's call goes out, and gives the check that
   the number is no emergency one two seconds, counting one not answered by
   then as one. Until 0.31 it checked with no time limit and placed the call
   first, and said nothing of whose its results were, so an arming it did not
   answer was told as "may have armed", and not watched; a recorder before
   0.31 still is. Each result is acted on once, and the question below is
   asked until it is answered, put aside, or a new test is armed. What answers a tap on
   the answering machine - an arming, the carrier's answer, a take's upload,
   a delete the recorder missed - and, while its screen is shown, a test
   call's ending and a setting that did not reach the recorder, is a
   snackbar as well as a line in Diagnostics' log; the rest, such as the
   checks run on opening, is the log's alone, as all of it was until 0.30.
   The screen that arms
   or watches a test greys the greeting's Delete meanwhile: with no greeting
   the recorder answers no incoming call, a test's included, and an outgoing
   test plays nothing (until 0.30 the screen then waited out an incoming
   test and said no call came, or put the route down as not working on an
   outgoing one).
3. After a test someone made, one question: did they hear it?

Results are kept per call type - 2G, VoLTE and Wi-Fi Calling take different
paths; a call Telecom marks as on Wi-Fi is Wi-Fi Calling (above), any other is
named by the voice network type, as before - each with whether its test went
through the mixer, where the route is the call-screening one whatever the
phone's (since 0.31), and *Copy a report* puts the phone and the results on the
clipboard for a report, with nothing about any call. On
the Honor, VoLTE could not be tried: its carrier would not register it for
VoLTE.

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
turns every chip off and folds the search away - over a list with nothing
in it, on either tab, there is nothing to narrow and no line; and a chip
lit past the row's edge is scrolled into view as the screen opens. On the Honor
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
screen (one at a time, stopping with the screen), or one played before
then - with a plain slider until it is there - and kept in the app's cache: 800
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

**A seek lands where it is sent** (1.4). Opus is decoded from at least 80 ms
before a seek's place, and what comes before the place dropped (RFC 7845,
4.6). Android's Opus decoder drops those 80 ms itself after every flush - at
every seek once it has decoded (AOSP `C2SoftOpusDec`) - but Media3's Ogg
reader feeds it from the packet that holds the place, none before (Media3
1.11.1 `StreamReader`): the 80 ms dropped were the place's own. Measured by
the sound itself - what the player's sink was given after each of 20 seeks,
matched against a straight decode - it began 53.5 to 73.5 ms past the place
on the Android 12 emulator and 73.5 to 92.5 ms past it on 17 (2026-11-20);
the player's own position, which an earlier probe read, did not show it.
So once its decoder is up the player is sent 80 ms early (`OpusPreRoll`), and
the decoder's own drop ends at the place: measured from 32 ms before it to 2
ms after it, a little repeated, never lost. The playhead shows the place meanwhile,
playing or paused: the player's own word reads 80 ms short until the sound
is under way (`PlayClock.sentTo`). A seek before the decoder is up starts a
fresh one, which drops only the stream's pre-skip, and is sent where asked.
A file opened again where it was - a move to the earpiece, Both sides in
both ears - is opened there, not opened and then sent: on the 17 emulator,
one reopen of three in a run lost 74 ms so, its decoder up before the seek came;
opened there, its sound began 8 to 28 ms past the place on 12 and 17.
The first 80 ms of a file have nothing before them to send the player to: a
replay from the start begins 73.5 ms in - the decoder's own rule for a seek to
zero, dropping only the pre-skip, is keyed on a timestamp of 0, and ExoPlayer
stamps every sample past its renderer offset of 1e12 us (Media3 1.11.1
`MediaPeriodQueue`). The release check `seek_preroll`
holds it on Android's own decoder.

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
- **The player.** Recordings play with ExoPlayer since 1.2, cut down to
  what a recording needs, for [both sides in both ears](#both-sides-in-both-ears);
  until then with `MediaPlayer`, chosen for its size - the session alone
  added 0.56 MB to the release APK, and ExoPlayer with its default factories
  would have added 1.54 MB (9.19 MB before, measured 2026-10-23). Either way it
  is wrapped for the session (`SessionPlayer`), and ExoPlayer's own audio
  focus and becoming-noisy handling stay off: what a player should do is
  written out (`PlaybackRules`), from Android's audio-focus guide: a ringing
  call holds it back and it plays on after; another app taking the audio
  pauses it until you play it again; asked to duck, speech pauses instead,
  so no word is missed; headphones coming out pause it, so a call never
  moves to the loudspeaker by itself. During a call it does not start -
  Android refuses it the audio - and the app says so; nor while a greeting
  is being recorded, as over the loudspeaker it would go into the greeting:
  the app's Play says why, and the notification's Play does nothing.
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

## Playing at your ear

Held to the ear while it plays, a recording moves to the earpiece and the
screen goes dark, as in a call; lowered, it goes back to the loudspeaker and
plays on. Asked for in discussion #83. The parts are the ones Signal plays
voice notes with (`VoiceNoteProximityWakeLockManager` and
`VoiceNotePlayerCallback` in its source, read 2026-11-14):

- **The earpiece is voice-call audio.** A recording plays as `USAGE_MEDIA`,
  which goes to the loudspeakers or the headphones; at the ear it plays as
  `USAGE_VOICE_COMMUNICATION`, which Android sends to the earpiece as it does
  a call's. The audio mode is left alone: the answering machine and the
  recorder read "in communication" as another app's call (`CallTracker`,
  `Voip`), and nothing here needs it. Measured with a probe on the Honor,
  2026-11-15: voice-call audio on the earpiece with the mode normal, heard
  there "as in a call", and the recorder, watching four messengers, logging
  no messenger call - it takes one only from an app that plays call audio and
  records at once. A move opens the file again where it was, with the new
  attributes - as `MediaPlayer`, which takes them only before `prepare()`,
  had to: 69-83 ms on the Honor with it, heard as smooth; with ExoPlayer,
  raised and lowered on the Honor it moved as before (2026-11-16). Not
  changed on the player as it plays, which ExoPlayer allows: its audio sink
  lets its AudioTrack go with them, with what the track held and had not
  played - up to its 500 ms buffer (Media3 1.11.1's `DefaultAudioSink.flush`)
  - and a seek back is done for nothing when it is to the player's own
  position, to the millisecond (`ExoPlayerImplInternal.seekToInternal`): on
  the Android 17 emulator (2026-11-18) a raise so skipped 390 ms with no seek,
  400 to 460 ms with that seek. Stopping the player and preparing it again
  first lost nothing, and cost what opening it again does - a new decoder,
  the file read again - so a move opens it again, as Both sides in both ears
  does (`SessionPlayer.reopen`): one way. Opened at its place, not opened and
  then sent there: a seek meeting a decoder already up lost 74 ms (1.4; see
  [Playing a recording](#playing-a-recording)). The release check
  `raise_to_listen` holds it: each move opens the file again within 50 ms of
  where the playhead's clock had the sound (`SessionPlayer.reopen` logs
  where and when it aims), and its sound runs on no more than about a
  quarter second ahead across the move.
  The volume keys follow it: at
  the ear they moved the call volume, on the Honor's 1.1 build (Media3 hands
  the session's attributes to Android's media session).
- **The screen goes dark** by Android's `PROXIMITY_SCREEN_OFF_WAKE_LOCK`, the
  lock a phone app holds in a call. On the Honor: dark at the ear with JemRec
  still in front - not paused or stopped by it - a cheek pressed on it
  changing nothing, and the power key at the ear lit the screen rather than
  locking the phone. It is let go only once the phone is away from the ear
  (`RELEASE_FLAG_WAIT_FOR_NO_PROXIMITY`), so a recording that ends at the ear
  does not light the screen against the cheek. Not every phone darkens it,
  though: the Honor on its side ignores the sensor ("Not response proximity
  sensor for rotation" in its log, 2026-11-15), and the screen stayed on at
  the ear while a cheek pressed the player's buttons. So JemRec covers its own
  screen as well while a recording plays at the earpiece at the ear - black,
  at the window's lowest brightness, taking every touch, nothing under it for
  TalkBack (`EarCover`) - and the sensor is watched for that at a chosen
  earpiece too. The cover stays until the phone leaves the ear: a recording
  that ends or is paused there does not take it off at the cheek, nor is a
  turn of the phone, which remakes the screen, counted as leaving it. On the
  Honor, 2026-11-16, turned to landscape at the ear, JemRec's screen was made
  again in 37 ms while the recording played on in the earpiece and the
  screen stayed dark, until the phone came down. The system's bars are hidden
  under it, shown again by a swipe from their edge: a cheek's swipe up had
  gone home through it, twice, on the Honor.
- **The proximity sensor** is read while a recording plays with JemRec in
  front and, once the phone is at the ear, until it comes down - the
  recording ended or JemRec's screen gone meanwhile included - and while a
  call the user is on holds a recording back to play on after it (below).
  Otherwise nothing starts away from JemRec's screen: in a pocket, under a
  call that only rings or that the answering machine takes, or with
  headphones or Bluetooth in, no raise begins;
  at an earpiece already chosen the sensor only covers the screen. A reading is near as Signal reads one: closer than 5 cm and not
  the sensor's far value. Signal ignores readings for half a second after
  Play; here one in the half second after Play, or after a move, waits and
  counts only if it still holds then - so a finger near the sensor from the
  tap on Play moves nothing, and a raise that wobbles stays at the ear.

**Lowered, the loudspeaker.** Taken down from the ear, a recording goes back to
the loudspeaker it was started on and plays on - the user's choice
(2026-11-15), over pausing as Signal does. Pausing kept a private call from
going out loud to whoever is near once the phone came down; but it had been
started on the loudspeaker, and a hand passing over the sensor paused it: on
the Honor, a hand held there 0.64 s on purpose. Now nothing the sensor reads
pauses a recording; a hand only dips the sound for a moment. Left at the ear
by a cheek's swipe home, it stays at the earpiece until the phone comes down,
whatever screen is up. If JemRec's screen is closed for good at the ear,
nothing is left to see the phone come down: the recording is paused and put
back on the loudspeaker.

**A call.** A call holds a recording back, as it always has, and it plays on
after the call - where the phone is then. The sensor follows the phone while a
call the user is on holds the recording back: a phone call made or answered -
not one only ringing, which has an audio mode of its own - or a messenger call
in its call mode, as Android's audio mode tells (the session says only that it
is held back, which a phone call's ringing does too). Not a call JemRec's
answering machine takes (call screening), nor one whose audio goes to another
device (the redirect modes): the phone may be in a pocket then. Lowered to answer and raised for the call, the
recording is moved to the earpiece while it waits, plays on there, and goes to
the loudspeaker once the phone comes down. Before, nothing watched under the
call's screen: on the Honor, 2026-11-16, lowered to answer and raised for the
call, it played on 1.8 s from the loudspeaker at the ear when the other side
hung up - heard so, on the second run - until JemRec's screen came back and
moved it. Android had given it the audio back 0.26 s after the call's mode
ended, with the call's screen still up for 1.3 s more. With the watch, the
same test on the Honor the same day: lowered to answer, it waited on the
loudspeaker; raised for the call, it was moved to the earpiece 0.53 s after
the call's mode began, while held back; and when the other side hung up it
played on at the earpiece 0.29 s after the mode was normal again - its
loudspeaker player never started, and it was heard at the earpiece. A call only ringing
moves nothing: a phone left ringing in a pocket would have moved it to the
earpiece there. On the Honor, a call ringing unanswered at the ear held it
back, with JemRec still in front and the phone still at the ear, and it
played on in the earpiece once the ringing stopped. The watch is the
screen's (`EarWatch`, in the view model): with JemRec's screen closed for good -
from Recents, the recording playing on under the system's controls - nothing
follows the phone through a call, and it plays on where it was, as before 1.1.
A test runs 2,000 random runs of
everything that can happen: an earpiece the button or the setting chose is
never left by the sensor, and the earpiece of a raise lasts only while the
sensor is watched (`AtTheEarTest.aChosenEarpieceStaysAndARaiseLastsOnlyWhileWatched`).

**Settings → Playing → Play recordings** chooses where recordings play:
*Loudspeaker*, where holding the phone to the ear changes nothing - for a
sensor a hand keeps setting off (people report it with Signal's); *Loudspeaker,
earpiece at your ear*, the default, as above; or *Earpiece*, where each
recording opens at the earpiece and is never moved to the loudspeaker by
itself (asked for by the user, 2026-11-15). Headphones or Bluetooth, when in,
come first in each - but not Bluetooth for calls alone (SCO without A2DP, a
car kit, say), which by the Bluetooth profiles carries no media: the
recording plays on the loudspeaker there, and the earpiece is offered. That
is inferred, not tried; only buds, listing SCO beside A2DP, were (the Honor,
2026-11-15). With Earpiece set, a recording's attributes are given
before it is opened, so it is never a moment on the loudspeaker first. The
setting shows only on a phone with an earpiece, and its middle choice only
with a proximity sensor. A tablet has neither, and nor does an emulator: the
Android 17 AVD lists a speaker and Telephony Tx (2026-11-15). The emulator
check `raise_to_listen` sets the sensor through the emulator's console and has
the debug build act as if there were an earpiece, so it holds all of this but
the sound.

**The button.** The button at the top right of the playing card shows where a
recording plays - a loudspeaker, or an ear in the look's colour for on - and a
tap moves the recording playing to the other, until the next one. The user
picked it from three drawn (2026-11-15); the first, a phone handset, was
tapped for Play on the Honor, and in a call recorder can read as "call back".
In the player's bar beside the time it cut Multitrack's timecode off at
360 dp, so it sits beside the name. It is the screen's alone: the system's
controls - a lock screen, a car - may play, pause and seek, never move it
(`SessionPlayer.TRANSPORT`). JemRec reopened while one plays at an earpiece
the button chose finds it there.

Where it lives: `AtTheEar` decides, in plain Kotlin; `EarWatch` reads the
sensor, holds the lock and watches for headphones; `PlayThroughSetting` keeps
the choice; `SessionPlayer` opens the file again for the output that Media3's
`setAudioAttributes` asks for.

## Both sides in both ears

A recording plays with both sides of the call in each ear: one earbud hears
the whole call, and an answering-machine message, its left side silent, is
not in one ear only. Settings → Playing → **Both sides in both ears**, on
unless switched off, for updates too; off, each side plays in its own ear,
as it was recorded. The file keeps the sides apart either way, and Share's
converted `.m4a` follows the switch. Asked for in discussion #92: "I am heard
in one earpiece and the interlocutor in the other".

- **Why ExoPlayer.** `MediaPlayer` cannot put one channel into the other:
  `setVolume(left, right)` only scales each, and nothing in
  `android.media.audiofx` mixes. Media3's `ChannelMixingAudioProcessor` does,
  in ExoPlayer, and Android's `MediaPlayer` page recommends Media3 for new
  playback. Cut down to the Ogg extractor and one audio renderer it added
  511,036 bytes to the release APK (10,240,823 to 10,751,859, measured
  2026-11-16); with its default factories it had come to about a megabyte. What
  `MediaPlayer` did, and how each still holds, is listed in `RecordingPlayer`.
- **The mix.** Each ear gets both sides at 1/√2 - the mix that keeps the
  loudness - chosen by ear on the Honor over 0.5 (every voice 6 dB down) and
  1.0 (a plain sum). Over the 101 recordings there, 7.3 hours decoded on the
  phone: a plain sum would have gone past full scale 113,936 times in 27 of
  them, 21 of those loud messenger calls; this one 922 times in 6, about 19
  ms in all, and none was heard. What goes past is held at full scale, as Media3
  holds its 16-bit output, and Share's copy is mixed by the same formula
  (`BothEars`), in the encoder's buffer - the decoder's is read-only. A mono
  file plays as it is.
- **Switched while it plays,** the recording opens again with the sides
  mixed or apart - the mix is part of the player as it is built - and goes on
  from where the sound was, by the playhead's clock - the place a move to
  the earpiece goes back to: `PlaybackService` watches the setting.
- **A smooth playhead.** ExoPlayer runs its playback loop as rarely as it can
  (dynamic scheduling, on by default), and the position it gave the app stood
  still and then jumped about 256 ms, four times a second, where
  `MediaPlayer`'s was worked out when asked. The media session handed that on,
  and every three seconds the screen's reading was put right by 110 to 210 ms,
  back about one time in three, where the playhead - which never goes back -
  stood still; its user saw it slow down (the Honor, 2026-11-16). Turning the
  scheduling off smoothed it, at half again the app's CPU while playing (7.1%
  of a core to 10.8%, measured with the probe running), and the switch is one
  Media3 marks experimental, to be renamed or removed. So
  the position comes from the clock once the sound is under way - Media3 says
  when playout started - with ExoPlayer's own word while it is not, or when
  the clock runs behind it, or more than a second ahead (`PlayClock`), or
  after the audio track says it ran dry (Media3's `onAudioUnderrun`), until
  the word moves on: a shorter stall left the clock ahead of the sound for
  good, and a move then skipped as much (reviews, 2026-11-18; the release
  check `both_ears` stops the app 0.8 s to make one); and
  the sound getting under way reaches the controllers at once, as a jump in
  position, not at the session's next word. After: over 40 s, the screen's
  reading ran at exactly the clock's pace, put right once, by 104 ms, a
  quarter of a second after Play. Its user: "Smooth now". The screen's
  playhead reads the sound's place every frame since (`SoundPosition`,
  `PlayheadGlide`).
- **Paused where the sound stopped.** Paused, ExoPlayer's own word is its
  last step, and the session showed it until the next play: at four pauses on
  the Honor, 2026-11-17, the player went on 112 to 231 ms past the position
  shown, and the screen's playhead went back to it at each pause (read from
  its rules, not seen). A pause now
  keeps where the clock had the sound (`PlayClock.pausedAt`): at the fourth,
  worked out from the log's times, 10 ms short of where the player went on.
- **The playhead from the player itself.** With an earbud (Bluetooth) the
  playhead slowed again after Play and after each tap: the screen's
  controller ran on from Play while the sound started 0.67 s later, Media3
  said the sound was under way 2.1 s after Play, and the controller was put
  back 0.5 s then (the Honor, 2026-11-17). The player itself said 0:00 until
  the sound started and moved with it from there. So while it plays the
  screen reads the position from the player, in this process, at every frame
  (`SoundPosition`), not the controller's working-out: the playhead waits at
  the start for the sound and runs with the clock. Paused, it shows the
  controller's word, which is where the player paused (the clock's place) and
  has a seek in it before the player does. The controller is kept for
  playing, pausing and seeking; the system's controls still follow the
  session's word.
- **What a frame log showed then** (a debug probe, every frame's drawn
  position, the same day): on the loudspeaker the playhead waited 75 ms for
  the sound after Play and 25 to 60 ms after a tap, then ran at the sound's
  pace within about a pixel. On the earbud two things were left. ExoPlayer's
  word stood still and stepped on about 257 ms at a time, and the clock ran
  up to 359 ms ahead of it in one run and past 400 in another, which the
  clock took for a stall: the playhead went back 389 ms and on again. So a
  stall is now more than a second (`PlayClock.MOST_AHEAD_MS`). And until
  Media3 said when the sound started, ExoPlayer's word ran 104 to 132 ms
  behind it and then jumped: the playhead now takes such a correction at a
  tenth faster or slower than the sound, over about a second, and a word
  that moves in steps in small ones (`PlayheadGlide`).
- **Tried on the Honor** on the 1.2 build, 2026-11-16, with earbuds: both
  sides in both ears and in one earbud, the switch off and on while playing,
  seeking, pausing, the end, raised to the ear and lowered, the screen off
  with the lock screen's controls, and a shared `.m4a` played in another
  app, mixed. The emulator check `both_ears` holds the wiring - the recording
  opening mixed, apart when switched off while it plays and on from where it
  was, and back; what the ears hear, an emulator cannot say.

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
function. To a screen reader each header is one sentence, the same in all
three: the state, then what is on (Memo's read its marks' own names until
0.30). And only it: Compose hands accessibility a merged node's children as
well, all but those under a node that clears them, and Memo's title, merged
under the sentence but not cleared, was said after it - *Ready to record.
Records every call. Messenger calls is on. Ready to record.* (TalkBack 17.1
on the Honor, 2026-11-20) - so Memo's words sit in a cleared box, as Deck's
and Multitrack's do (1.4), and one setting on takes its own verb. A look
draws nothing that is not data: no meter moves between calls,
no LED is lit for show. Opened, until its first look at the recorder lands,
the header says *Checking* and draws it still, no mark lit and none spoken:
nothing is starting, and nothing it marks has been read. Until 1.2 it showed
the defaults for that moment - *Cannot record*, seconds with the recorder
down - and in 1.2's first rework *Starting*, its animation running and the
answering machine's and messengers' marks growing as the reading landed
(reviews, 2026-11-18).

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
name's hash alone, two callers shared one of its six colours one time in
six - on the Honor the user's two most frequent callers were both blue. Now a
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
beside it takes the full height, with no chip row of its own. Turned either
way, the list stays where it was scrolled to: until 0.30 it went back to its
top.
On top, the header and the chips took half a landscape screen and left about
one row in view (800 x 360 dp, every look). Android's window size classes put
"99.78% of phones in landscape" in compact height, and show the top app bar
only from medium height up in their example; the width is where the room is.

## A tap while the app is checking

Each time JemRec comes to the front it checks where setup stands and reads
again everything it shows - the recordings, the recorder, every setting.
What a tap does meanwhile is one table, an action a row with its reason (`Action`, in
`ui/Actions.kt`):

- **Not taken, the control greyed meanwhile:** Diagnostics' Self-test and
  Start recorder, Recently deleted's Restore and Restore all, auto-delete's
  choices, Messenger calls' caller-names switch, the folder dialog's Use
  default and home's lost-folder notice's Use the standard folder, setup's
  buttons that only move it on. The check itself is one of
  these: one at a time is enough, and the next return asks again.
- **Waits its turn, then runs:** an answer already given in a dialog that has
  closed - Delete, Delete all for good, Start fresh - and a control that is
  never greyed: the record control, setup's Open settings, Settings' switch
  for Android's debugging notification. Dropped, each did nothing, without a
  sign. A delete takes what its question counted, not what is selected when
  its turn comes: until 0.30 a row picked in the wait went too. Said yes to
  twice in the wait - nothing seen to happen - the second takes only what the
  first did not: each counted the same rows, and the second moved them again,
  "could not move" after a delete that worked. A row gone by its turn - from
  its notification, or auto-delete - is left out, as is one gone from
  Recently deleted, where Delete all for good takes every copy of a name - one
  for each place it is trashed in; where Android must be asked about two
  deletes, it is asked about each in turn. Delete, Delete all for good, Start
  fresh and the debugging notification's switch wait their turn past the
  screen, as the record control's own turn does: closed from Recents in the
  wait, each runs all the same, where it went with the screen, without a
  word. A delete, or Delete all for good, has any list open - a screen
  opened meanwhile - read again.
- **Taken at once:** what a picker brings back - a contact for the rules, a
  folder - which arrives as the app comes back to the front, while it
  checks (dropped, the contact was picked and nothing added: measured on an
  emulator while 0.12 was made, 2026-10-20); a chosen contact's removal, one
  preference written, as File names' and Whose calls' choices are; the call
  log's Allow, Android's own question, wherever it is offered; and
  Diagnostics' Copy a report, which changes nothing; and what the recorder is
  told as a setting changes, as the next call can come seconds later. Each of
  these stays live while the app checks: until 1.2 Settings greyed some of
  them there, and home did not (review, 2026-11-18).
- **Allow this Wi-Fi** waits for the check, then asks Android, with
  *Starting* in the header meanwhile rather than the screen held still.

## One window

JemRec's own ways in to its screen - the Quick Settings tile, the media
notification, every notification that opens it - start it with
`MainActivity.open`'s flags: NEW_TASK, CLEAR_TOP and SINGLE_TOP, as deep links
did already. A plain tap - a notification without a button, the tile, the
media notification - is a link of its own (`DeepLink.home`) to the recordings
list and its header, nothing narrowing it and nothing selected, as a Play
link opens it; until 0.30 each of these opened a window of its own on that
list. A notification's button opens what it names; Play and Share also bring
the recording's row into view unless it is in view already, below the pinned
header and with its day's heading when it is the first of its day, the
list's window widened to hold it - from a list left scrolled down, the
recording played with its player off the screen - and followed for a few
seconds as its player opens, which grew a row at the screen's foot past the
edge. While setup shows in the list's place - the pairing lost - a link's
row is let go, not kept to jump to later. Any link lets a question
left open go - "Start fresh?", "Delete all for good?": left, it came back
unasked on the next visit. The window there is comes forward, its intent
through `onNewIntent`, and what is on top of it in its task, such as a picker
opened from JemRec, is finished - Android's permission question too, which
is then no answer: the call-log offer stays, where it went for good. For an
opening - a plain tap, or the
launcher with JemRec on top - what a new window did, the keep-alive armed,
the notification listener rebound, a background kill looked for, the window
brought forward does too; a button's link never opened a window, and does
not. The activity is `singleTop`, so the tile's long press, which Android
starts, reaches it when it is on top. The launcher brings JemRec's task
forward with a picker left open still there. Over a task JemRec began
another way - by a notification, the tile - the launcher makes a new window
over the picker (measured with `am start` on the Android 12 and 17 AVDs,
2026-11-08), which
goes at once and leaves the task as it was (on a fresh start only: remade,
as when the phone is turned, a window stays); started into another app's
task, it opens there, as ever.
(`singleTask`, tried in review, finished the picker on a tap of the launcher
too, and a permission's question, and "delete this recording?".)
Notifications posted by 0.29 keep their old intents until they are posted
again. Until 0.30 the tile, the media notification and a
notification's tap each could open a second window over the first - read from
the code, not measured: their intents had no CLEAR_TOP, and only the media
notification's had SINGLE_TOP - and two answering machine screens could then
arm a test, or play the greeting into a take, unseen by each other. The
record prompt's full-screen window (opened while the phone sleeps or its lock
screen is in front, and over a locked call only where no heads-up can show:
*Notifications off*, in
[after the call](#after-the-call-and-when-something-is-wrong), has the
measurement) has a task of its own: in
JemRec's, its NEW_TASK and CLEAR_TASK cleared that task, and the window in
it (read from the code; 0.29 did the same).

The window comes back where it was left after Android ends JemRec's process
in the background, as Android does whenever it needs the memory: the screen,
the home tab, each list's search and chips, the recordings picked. A
ViewModel does not outlive its process, so it keeps them in its
`SavedStateHandle`, saved with the window's state - "Save UI states" on
developer.android.com, for state a view model holds - and one made new takes
them back from there (`SavedPlace`); one that lived through - the phone
turned - has them already. Until 1.2 it came
back on home's first tab, the search and the selection gone (the practice
audit, 2026-11-17). An emulator check ends the process with `am kill` -
which ends only a process it is safe to, one in the background - from
Settings over the Calls tab, and finds both again; a Robolectric test makes
the window again from its saved state with a view model made new
(`ProcessDeathTest`).

## Keeping, deleting and cleaning up

- **Keep** - the bookmark on each row marks a recording to keep. A Kept chip
  shows only those, and auto-delete never touches them.
- **Delete goes to Recently deleted** - from the list or from the notification
  after a call. A deleted recording waits 30 days, can be restored until
  then, and is then removed for good. In the standard Recordings folder that
  is Android's own trash, so other apps stop seeing the file at once. In a
  folder you chose, the file is renamed the way Android's trash names it
  (`.trashed-<expiry>-<name>`, hidden from most file managers), and JemRec
  removes it when the 30 days are up. In a name Honor's Phone app could match
  (`HonorNames`) the `@` is written `%40` and the `_` `%5F`, as it plays any file in its folder that
  holds the number and the time: with the `@` gone alone, a deleted recording kept
  its play button and played; with the `_` gone too, the button went (the Honor,
  2026-11-06). Recently deleted restores one at a time, or
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
- **Every copy is a row.** In Recently deleted a file is a file with a time
  left, so two of one name - the same call in two places, or one saved twice -
  are two rows, each restored or deleted on its own. Up to 0.28 it showed one
  per name: the other could be neither restored nor deleted, *Delete all for
  good* left it there, and it went unseen when its time was up. The list keeps
  one row per name, as a row there stands for a call. One file is one row, even
  with `Recordings/JemRec` itself chosen as the folder, where it is seen through
  both Android's trash and the folder, by one name and one expiry. In app
  storage a restore, the rename into the trash and a save each take a free name
  if that one is taken: a rename replaces the file that has it, and until 0.29 a
  restore there did, and a retried save truncated it.
  An emulator check saves one name twice in each of the two places Android
  runs, trashes the same name in both, and taps Restore all and Delete all for
  good on the four rows; a unit test holds app storage, which it does not reach.
- **Auto-delete** - off until you choose. It removes recordings older than 30
  days, 90 days, 6 months or a year, and calls shorter than 5, 10 or 30
  seconds - wrong numbers, hang-ups. A short call is caught as it is saved,
  so it never gets a notification. What auto-delete removes goes to Recently
  deleted like anything else. The pass, and Recently deleted's 30 days, run
  once a day at most in a keep-alive run, so they wait with it: for a
  network, which its runs ask for ([Coming back without
  you](#coming-back-without-you)), and under the Battery setting
  *Restricted* for JemRec to be opened - a day or more late then, and
  nothing lost (review, 2026-11-18).
- **Share's `.m4a` is a copy of the call** - made in the app's own folder on
  the phone (`Android/data/…/files/shares`), handed out by the FileProvider
  one file at a time. It goes once it is six hours old - a share in flight is
  minutes old - swept at every share, every keep-alive run and every start of
  the app's process, and when its recording leaves the list, the way its
  notification does (`Trash.left`), unless it was made in the last ten
  minutes: shared and deleted at once, an app that reads its share later - a
  queued upload - would lose it. Until 1.2 copies were swept only at
  the next share: a phone that never shared again kept every one, and a call
  deleted for good went on as its copy (the practice audit, 2026-11-17).
- **A recording not finished is not listed.** In the Recordings folder Android
  keeps it out of sight until it is finished. A folder you chose and app storage
  have nothing of the kind, and the file is there under its name while it
  fills: a save creates it at the first packet and fills it at the end, so a
  save under way, or one killed half way, was a row of 0 B. The list now leaves
  out what this process is writing, and an empty file wherever it is. A folder
  that does not say how big its files are keeps them listed, and a file said to
  be 0 bytes is looked at first: unknown is not empty. A file left empty by a
  save that was killed stays in the folder, unlisted, as nothing can tell it from
  somebody else's.
- **Recordings Android does not count as JemRec's** - typically ones made
  before a reinstall. Android asks before JemRec may move them, in its own
  dialog. Deleting them from the list, or changing an auto-delete rule, shows
  that dialog. The daily auto-delete pass has nobody to ask, so it leaves them
  alone.
- **One rule for the three places.** A recording reaches the rest of the app
  as a uri and, for app storage, a path. Which place that means is worked out
  once (`RecordingLocation`), and each place keeps its own ways of writing,
  listing, trashing, restoring and deleting (`MediaStoreRecordings`,
  `FolderRecordings`, `AppRecordings`). The screen reads the recordings and
  Recently deleted together (`RecordingLibrary`), from one listing of a chosen
  folder, as auto-delete's pass does for its purge and its rules.

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
  `JEMREC_KEEP`, and from 1.2 `JEMREC_START` (when it began, in UTC),
  `JEMREC_PART` (one of a call's parts) and `JEMREC_CALL` (a part joined
  mid-call: its call's start), as they apply. The OpusHead page and the first audio page
  are the muxer's, byte for byte, and the tests hold the checksum to the two
  the Honor's own file carries; the other audio pages are joined in the same
  copy, every packet as it was ([quality and size](#quality-and-size)). A copy that fails is a recording not saved: the recorder's copy
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
- **Still read after it has left the list.** A file leaves the list for Recently
  deleted, for a query that failed, for a permission taken back or another
  folder chosen, and comes back the same file. Up to 0.28 its name was forgotten
  as it left, so the file was read again on its return, and a keep mark taken
  off a file JemRec may not write came back from the file. A name that is not
  listed now stays read, the newest 2000 of them, so the record does not grow
  with every recording ever deleted; past that a name goes, and its file is read
  again if it ever returns.
- **What holds to 2000, and what does not.** A message, a part, a direction,
  a start and a messenger's caller outlive their recordings in the app's
  preferences, so those five keep the newest 2000 names. A name the list has read stays past that, or
  is reading, as it does all of them after a reinstall: an old message used to
  lose its mark with its file still there, and with it the short-call rule's
  exception, so auto-delete could take it for a wrong number. Keep marks have no
  bound.
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
out on the day you need the call. So JemRec says so, in notifications - and
on its home screen, where a notification cannot.

- **After each call** - who it was with and how long, with Play, Share and
  Delete. An icon in the status bar but no sound, one at a time, and it can be
  switched off in Settings. Delete asks first, in the notification itself,
  and only with the phone unlocked (1.2, `setAuthenticationRequired`: from a
  lock screen, a recording of a call was one tap from the trash). It
  goes when its recording goes to the trash or for good, from the list, by
  auto-delete or on Android's own yes: a message's has no timeout, and one
  deleted from the list stayed, with Play, Share and Delete on nothing (the
  Honor, 2026-11-06).
  One a file manager deleted goes at the Play or Share that finds it gone.
  One notice for a call, whatever it is in (1.4). A call a lost recorder left
  in parts says so - *Recorded in 2 parts, 1:49; about 13 s between them not
  recorded* - with Play on its first part, as the Calls tab plays it, and
  Delete on every part, asked about as *both parts*; no Share, one part being
  half the call. Until 1.4 it was told by its part with the most audio alone,
  with that part's length and Play, Share and Delete on that part: *Recorded,
  1:13* and *Recorded, 1:41* for calls of about 1:50 in two parts (the Honor,
  2026-11-19). A call whose recording stopped early has its notice in the
  early notice's place (below), and a message cut short says so in its own.
- **The recording came out silent** - every recording is decoded and measured
  once it is saved. A muted capture still produces a file of the right length;
  this is what catches it. A recording with only one side is NOT reported,
  because it cannot be told reliably: on the phone this was built on, a call
  with both voices audible had one channel all but silent - the other carried
  the whole call. Nor one under ten seconds (1.4): the first seconds after an
  answer often hold no voice yet, and a 5.5 s part a lost recorder left of a
  WhatsApp call - 4.5 s of live line, -62 to -73 dB, then about a second of
  voice - was told
  silent (the Honor, 2026-11-19), the floor being half a second of sound. A
  capture muted on every call is said at the next call of ten seconds or
  more; one muted only on some - Wi-Fi calling, on some phones - goes unsaid
  on a call under ten. A call in parts is silent only if every part long
  enough to judge is.
- **The recorder is down** - after two minutes, with the reason: it needs a few
  seconds of Wi-Fi, or Android has not allowed Wireless debugging on this Wi-Fi
  (with *Allow this Wi-Fi*), or it cannot connect and the pairing is probably
  gone (with *Pair again*), or it could not start. It goes away when the
  recorder is back. The home screen's header says the same at once, each cause
  with its own line, and only off Wi-Fi does it say to join one. A pairing
  turned away twice in a row sends home to setup's pairing step instead, which
  says why: through 0.19 the header told the user to join a Wi-Fi they were on,
  their pairing deleted (the Honor, 2026-10-24). Since 1.1 it also says, with
  Android's pairing timeout on, that Android deletes a pairing 7 days after it
  is made (see [Keeping the pairing](#keeping-the-pairing)).
- **A call was not recorded** - in automatic mode, with call-log access, the
  call log is held against what was recorded, and an answered call with nothing
  to show for it is reported once. Recordings you delete do not count as missed:
  the app notes each call as it saves it.
- **Recording stopped**, during the call (1.2's reviews) - the call's own
  silent notification says *Recording stopped* the moment the recorder says
  so: *Trying again - this part of the call is not recorded* while its capture
  is made anew every 10 s, or nothing has reached the recording for five
  seconds (a stall no read fails in), back to *Recording call* once reads
  have flowed for five seconds, or *The
  rest of this call is not recorded* when the encoder or its file ended
  (`CallNotice`; the recorder's `RECORDING_STOPPED` and `RECORDING_BACK`
  wakes, dropped once the call is over). Silent, as the call's notification
  always is: a sound mid-call is what it must not make. Produced on the
  Android 17 emulator, the phone's storage filled mid-call (`early_end`). A
  recorder ended mid-call cannot say it, so the call's service watches it
  (`RecorderWatch`, 1.2's reviews): every 5 s it asks
  whether the recorder answers - one ping, then the five a destructive step
  asks - and gone, the notification says *Recording stopped* and the
  recorder is revived, again 30 s after one that would not start (timed,
  not counted in looks, which take ~2.4 s of pings each with the recorder
  down; the looks go on meanwhile, so a call that ends then is read as
  over). Started again while the call is up it records the rest of the
  call, a recording of its own, the time between lost - measured on the
  Android 17 emulator first by hand (4.2 s and 10.8 s saved, 8.8 s between
  lost) - neither said to have stopped early. A recorder started mid-call
  sees the call's off-hook as the first state Android tells it, no ring
  before it - or a ring over the call already up (call waiting: the next
  off-hook is the joined call's, unless an idle or the mode leaving the call
  says it ended first; only a ring that sounds, or, with a Bluetooth
  headset connected, one Do Not Disturb lets through, changes the mode - or
  another app's call ringing through Telecom (a self-managed call: Meet's)
  - Telecom plays no ring for it but takes the ring's mode, unless the work
  profile is quiet - AOSP's Ringer.startRinging), or,
  the call
  on the SIM that is not the default, an idle with Telecom holding a phone
  call and the audio in a call (read in AOSP's TelephonyRegistry, not
  produced) - so it cannot tell which way the call went: it names the file
  `_joined` and says so in the call's start, and the saver takes the call
  that was up at the recording's start, whichever way - Telecom's call made
  active last before it, or the call log's entry whose span holds it, waited
  for before one held only by the slack for a ringing - so its caller, its
  rules and its way are that call's.
  Until round 10 of 1.2's reviews such a part was matched to no call: kept
  as an unknown outgoing call whatever the rules (read in review, not
  produced). In Ask first a phone call's start asks nothing again: a call answered
  Record is recorded on, one answered Not now is left - the answer
  forgotten once the audio mode goes back to normal, or to a ring's (the
  call up hung up while a waiting one rings - not a ring over a call on
  hold, which goes on, its Record with it - not a Not now; rounds 26, 27), so a call redialled quickly is
  asked about - except a joined start that came through a ring, which may be
  the waiting call itself, the call up having hung up while it rang
  silently: asked again, never given the other call's answer (round 13).
  A messenger call's revived start says no "joined" - the recorder names no
  messenger file so - and is asked about again: one call swapped for the next
  in the app, no mode change between, cannot be told from it, and an answer
  carried over would record a call nobody was asked about (review round 18).
  Its first part is kept with it: only the revived recorder's own first start
  of the same app's call, after the loss, is taken for it (round 17 had the
  first part dropped; round 18 any start until the call's end kept). A
  joined start continues a phone call only: one over a messenger call whose
  recorder was lost is a phone call rung in during it, asked about and
  saved on its own (round 19). And with the recorder lost and not yet back
  in the call, the call's mode left ends the call there and then: the
  revive, tries of seconds each, can land in the next call, whose start was
  taken for this one's (round 19). Left: normal, or for a phone call a
  ring's mode - a waiting call ringing once the call up hung up never
  passes normal (AOSP CallAudioModeStateMachine; round 20) - but not a ring
  over a call on hold (below; rounds 25, 26). A phone call at
  once: Telecom sets those only with no call active (UnfocusedState,
  RingingFocusState), so a waiting call answered at once - End and answer -
  is the next call whatever the mode says by then (round 21). A messenger
  call after 1.5 s out without a break, as an app switching its route flips
  the mode and the recorder's own watch waits as long (Voip; rounds 20,
  21). Not when the recorder was back as the mode left - the watch's revive
  says so, or one ping at once for one back some other way - its start
  perhaps still on its way (a wake: up to three tries of 10 s, about 34 s at
  most), nor once a revived recorder
  has said the call: their own end ends it, the parts saved together (round
  20). One back later is in no call of this one's: a ping and five more,
  2.4 s, let a revive landing in them keep the call, and the next call's
  start was taken for it (round 22). "Back" is the watch's revive since
  this loss: a revived recorder lost again before the watch's next look,
  or one whose revive began before a phone call rang over a messenger call
  that then ends under the ring (the recorder finds a messenger call only in
  communication mode, Voip; the watch starts none under a ring it takes for
  the call, round 26), leaves the end to the watch's two looks - each
  a second fault within seconds (round 23); so does a recorder revived
  before the mode left whose call ended before it found it - a messenger
  call from the same app within those looks is then taken for this one's,
  as a redial was in round 18 (round 24). A phone call's mode left - normal,
  or a ring's - ends it for the next start, of a file begun after or of
  none, even with the loss found only after the hang-up: the redial is the
  next call (round 24) - when the mode leaves the call; a redial within
  the end-of-call tone (about 1.6 s after Telecom's end on the 12 AVD,
  2026-11-19) never passes normal - AOSP OtherFocusState keeps the call's
  mode through the tone - and with exact call tracking on, Telecom's own
  list emptying ends the call there instead (round 29; off, such a redial
  is taken with the call before): Telecom sends the call's DISCONNECTED to
  the in-call services (InCallController.onCallStateChanged, updateCall),
  and android.telecom.Call takes it out of the list on that update
  (internalUpdate, fireCallDestroyed, Phone.internalRemoveCall,
  onCallRemoved), while CallAudioManager starts the end-of-call tone on the
  same DISCONNECTED (playToneForDisconnectedCall) - read in AOSP main, and
  produced by `stopped_early`'s "telecom" redial pass on the 12 and 17
  AVDs (2026-11-19): no normal between the calls, the redial told apart;
  with the list's line planted out, the two were one call (12). Only the last call gone counts: one of two hanging up -
  a held call's other - leaves a call up (CallTrackerTest, round 30); and
  not a ring's over a call on hold, which is
  still up (AOSP OtherFocusState: a new ringing call goes to
  RingingFocusState unless the phone is at the ear; read in AOSP, not
  produced), told by Telecom's own call list with exact call tracking on -
  for the mode's end, the watch's looks (which take that ring for the call,
  and try no revive under it: one started then sees the ring first and its
  start is no joined one - the loss said all the same, round 27) and a
  Record kept - not a Not now: the recorder itself asks at the call answered
  over the held one (round 27); with it off a held
  call can be split in two there (rounds 25, 26). A waiting call that rings
  silently sets no mode at all: only a ring that sounds, or, with a
  Bluetooth headset connected, one Do Not Disturb lets through, does - or
  another app's call ringing through Telecom (a self-managed call: Meet's)
  - Telecom plays no ring for it but takes the ring's mode, unless the work
  profile is quiet (AOSP Ringer.startRinging, shouldAcquireAudioFocus;
  RingingFocusState) - so its answer is not seen and it is taken with the
  call before, as the recorder's own call waiting is (Calls.onCallStart,
  RECORDING_ALREADY; round 21). A phone call ringing over a messenger call
  whose recorder is lost is the messenger call still, for the watch too -
  four minutes at most, timed, as with a recorder (round 21: it ended the
  messenger call after two looks; round 22: counted in looks, which take
  their pings with no recorder, it was six). What the lost recorder left of a call -
  ended so, by a new call's start, at a message's start, or at an end with
  no file of its own - is a part of it, one file alone too: no short call;
  and the loss is that call's, not the next one's (round 21). Lost unseen
  too: killed in the call's last seconds and found down only at a look
  after its mode left (round 29), or started again by another path between
  the watch's looks - on the 12 and 17 AVDs (2026-11-19) the Wi-Fi revive,
  its line a quarter to half a second after the hang-up, started a recorder 5-9 s
  later that joined the redial, no look having found the loss (on the AVDs
  that revive fires at most dials and hang-ups; a phone on steady Wi-Fi
  should not - inferred). A joined start (a recorder whose watch began with
  a call up), or a recorder started since the newest file of the call
  began (DaemonLauncher.launchedAtMs: one is started only when none
  answers, once an out-of-date one has quit, which it does only with no
  call, recording or fetch under way, or once a wedged one was killed) - timed by the file's own start, not
  by when its wake came - says the recorder writing it is gone; what it
  left is a part (round 30: saved as a whole call). Gone perhaps only after
  the call's end, its end's wake not yet in: the part is then a whole call,
  which errs safe - a part only escapes the short-call rule (round 31). An
  end that names no file marks the files held only on such a sign: the end
  of a call whose start never came - left out by the rules, or its
  recording would not start - names none either (round 31). A message under
  way so is still a message (LostMessages), noted before its file's hold
  goes, and its call hung up as at a loss the watch finds (round 31; the
  hang-up with exact call tracking on, as every message hang-up) - its
  window ending before what the recorder after it began, which is not the
  message (round 33). That file, begun in the message's own call while
  nobody has it (the microphone still muted), is the caller's: a part by
  its joined name, and a test call's noted with the lost message and
  dropped by whichever save meets it, no second test call counted (rounds
  32, 33); not asked about in Ask first. Its joined start ahead of the
  message's own wake - each up to about 34 s - loses the message the same
  way, nobody taking it up, by the joined file's own start (rounds 32, 33;
  with exact call tracking on: the message's call is Telecom's). A
  messenger call's recorder lost unseen and started again mid-call by
  another path is saved as two calls - the first's file a part, the rest a
  call of its own, each told - where a loss the watch saw keeps one call:
  the revived recorder's first start is taken for the same call only after
  a seen loss (round 17), and telling such a start from a redial in the
  same app would need the messenger's confirmed time out of its mode,
  which the service keeps only with the loss seen (round 31; read from the
  code, not produced). The release
  check `messenger_recorder_lost` ends the stand-in messenger's call during
  the revive: ended from the mode, its file saved as a part (Android 16 and
  17 AVDs, 2026-11-18).
  Call waiting at the revive is followed: the recording's own call is the
  one made active last before it, the other call up in the file too. The
  part before is saved at the call's end by the call's own service, with the
  part after, every file claimed before the first is saved, and the call is
  told of once, by the part with the most audio - its notice, which since 1.4
  speaks for every part saved (above), or what went
  wrong if none saved; the others have no notice of their own, so an empty
  end no longer says "Call not recorded" of a call with minutes saved
  (rounds 12, 13), and a part that could not be saved says *Part of a call
  not saved* (round 14); either failure's alert goes once every file it was
  about is saved after all (rounds 15, 16). Where the call's end does not save them - the watch
  ends the call with no recorder to say it, a process killed mid-save - the
  service has noted them as one call's (`CallParts`), and a reconcile saves
  them together and tells the call once, leaving a part alone while its
  call's service is still saving the other. Such parts are no short calls to
  the short-call rule (marked by name, and in the file's tags where known
  before it is written: after a reinstall, a file known as stopped early
  only from Telecom's end is judged by its length again). The service
  keeps the call's file names from its starts, and a reconcile does nothing
  while a call is up: its call is not in the log yet, and whatever it saved
  would post a notice. One so left is owed, process-wide, and the next
  call's end kicks for it: a kick made for what a lost recorder left, its
  run landing in a redial, left those files to the heartbeat - the service
  that owed it stood down in between (review round 25). A call's end kicks
  under a work name of its own, so a run begun before the end - its
  reconcile left for the call - never takes that kick for itself (KEEP)
  and ends with the files uncollected (rounds 26, 27) - and one of its own
  name still running has it queued behind it, not dropped (round 28). A
  reconcile leaves alone any file the call's service holds - from the
  call's start until its end's save has claimed it - so it never saves a
  call's own file in the seconds before the end's wake, with one read of
  a call log not written yet (round 28) - nor a message's, which the
  recorder names only at its end: while one is under way, any file begun
  from a minute before it started (round 29). Its end holds the file by
  name before it lets go of the message, and the reconcile reads the two
  the other way round, so the file is one or the other at every moment
  (round 30). The missed-call check waits out a ring too. A part joined mid-call - the call's only
  recording, its recorder revived minutes in - counts as that call's in the
  missed-call check, the Calls tab and the list's labels by the call's own
  start, which the saver found and the file keeps (its tags, and the app's
  note of its start), the ledger too for the missed-call check; by any start in
  a call's logged span, as round 11 had it, a redial's recording was taken for
  the call before (review round 12). Each ledger entry accounts for one call,
  the closest of its way, as each recording plays for one: by every call of
  its way within three minutes, a redial's recording gave the call before
  its status and silenced its missed-call alarm (review round 12). While
  such a part waits on the recorder it counts for the call it began in,
  either way. Every match of a recording to a call is by when it began in
  UTC, noted as it is saved - by name, and in the file's tags for a
  reinstall: its name holds only the wall clock where it was recorded, read
  in the zone the phone is in now, and after travel every older recording
  was matched hours off its call (round 13, read in the code); a recording
  saved before 1.2 has only its name. And
  "JemRec is not recording calls", due two minutes into a recorder down, is
  posted silently while the audio mode is anything but normal: Android mutes
  every notification while telephony is not idle, a ring included (AOSP
  NotificationAttentionHelper.disableNotificationEffects), and in an app's
  call outside Telecom plays it as a tone in the earpiece
  (playInCallNotification); held to the call's end instead, it went untold
  when no service saw the call (review rounds 10, 11). It is posted again
  with its sound once the mode is normal - at the call's end by its service,
  else at the next revive (rounds 12, 13: marked said, it was never heard) -
  unless the user swiped it away or opened JemRec (round 14). The reconcile and the call's watch with no
  recorder count a call up only in a call's mode - a phone call's or an
  app's - not a ringtone's, which another app can leave set: they waited on
  it for ever (read from AOSP, not produced) - but a messenger call's watch
  counts a phone call ringing over it as the call (rounds 21, 22), and a
  held phone call's watch a ring over it (round 26); a watch
  whose recorder has said the call waits for normal, as the recorder
  records on through a waiting call's ring (round 13) - a ring's mode four
  minutes at most, timed, as a call can ring three minutes or more (3GPP TS
  24.008 T301; rounds 14, 15, 22). The watch holds a wake lock while it runs: with the recorder gone
  nothing else keeps the CPU up on a call with the screen off by the power
  key (inferred, for the Honor session). The service is there
  for every call the recorder announced, call tracking on or off. And when
  it cannot come back - no Wi-Fi to revive over - the call's end is read
  from the audio mode: once the loss is said, at once for a phone call and
  after 1.5 s out for a messenger call (the revive rule, above; review
  rounds 19 to 21),
  and otherwise - a loss found after the call ended - two looks with no
  recorder (six with one, which says
  the end itself seconds after - two with one the watch brought back that
  has not said the call's start: it never saw the call) - what it left a
  part if the recorder was down at any of those looks, or one was started
  since the newest file began - gone, perhaps only after the end; a part
  errs safe (rounds 29-31): until 1.2's reviews only the recorder's end
  took the service down, and its notification stayed up after such a call,
  until the next call's end. The release check `stopped_early` makes six
  passes: brought back mid-call, brought back during an incoming call (the
  joined part saved incoming), lost unnoticed just before a hang-up and a
  redial - two calls, each told, the first's file a part - three times,
  each with one rule the one that decides (round 30: with tracking on, a
  redial a second after the mode went normal was told apart by the mode
  and by Telecom's list both, so the pass would pass with either planted
  out - read from the code, not run; and on the 17 AVD the first's file was
  saved whole, the revive's recorder having joined the redial unseen):
  redialled the instant
  the phone reads idle with exact call tracking on (Telecom's list), a
  second after the mode went normal with it off (the mode; the check reads
  `dumpsys telecom` to see Telecom bound no call service of JemRec's then,
  and did with tracking on), and after a recorder started between the calls
  (a recorder started since, which marks the part; in the other two a
  joined start and a recorder started since both held, so the joined rule
  alone is unit-tested only - joined means started since, but for a clock
  set back, round 32) - PASS on 12 and 17 with no try again (2026-11-19),
  and each rule planted out FAILs its own pass on 12 (round 30's tree):
  Telecom's list and the mode at "each call's after-call notice - two
  calls", the launcher's mark at "the call before's [file] to be marked a
  part" - the last run again on round 32's and 33's trees, the same
  (2026-11-19); and
  with Wi-Fi off not brought back - the call's end read from the audio mode
  within 2 s of its normal (round 25; timed from the mode since round 33:
  on a loaded 17 AVD the mode went normal 6.2 s after Telecom's end, the
  call ended 4 ms after it, and a 5 s wait from the hang-up failed) - in which
  pass, until round 10, the watch slept through its 30 s wait after a failed
  revive and kept the notification up to 40 s after the hang-up (both AVDs,
  2026-11-18). The by-hand measurement before the watch (4.2 s and 10.8 s
  saved, 8.8 s between) was the Android 17 emulator's, 2026-11-18.
- **Recording stopped early** (1.2) - a recording that ended more than 5 s
  before its call, known two ways. A phone call's last packet against the end
  Telecom gave the call: its capture failed or stalled and never came back
  (tried again every 10 s since round 10, so one that comes back is no early
  end), the encoder ended, or the recorder was ended mid-call. Normally the
  recorder runs a second or two past a call's end. And the recorder's own
  mark in the file's name (`_early`), where the recording ended or stopped
  for good before the call (`RecordingLoss`): what tells a messenger call, a
  phone call with call tracking off, a message, and a recording holding two
  calls (call waiting), which Telecom's one end does not judge - these were
  never told until 1.2's reviews. A recorder ended mid-call leaves no mark,
  so for those it goes unsaid. The mark runs a second or two over the loss (the recorder
  stops after the call); where Telecom has an end, that is what is said.
  Telecom's end is not used for a message, which a takeover in Ask first
  ends on purpose while the call goes on; the mark is, as a takeover stops
  the recording before its encoder can end early, so a marked message is one
  whose capture was lost under the caller. Nor where another recording of the
  call carries it on - a recorder
  brought back while the call was up starts one (`SavePlan.carriedOn`). Until
  1.2 such a recording was found out by listening.
  Said the moment its save finds it, kept in the save's journal until said -
  so a process killed before the call's own notice still leaves it (review
  round 9) - and then replaced in place by that notice, which says it: one
  notice for the call, on *Recording problems*, kept until dismissed, its
  sound made once (1.4). Until then the two came together: after a recorder
  lost with Wi-Fi off came back, *Recording stopped early* and *Last call
  recorded, but silent* at once (the Honor, 2026-11-19). A message cut short
  says so in the message's own notice, and the early one goes.
- **Recording saved elsewhere** (1.2) - once, at the first call saved away from
  the folder chosen in Settings because JemRec can no longer reach it: moved,
  deleted, its drive taken out, or its access taken back (Android lets a folder's grant go when the
  folder moves). Home says the same while it lasts, with *Choose again*, and
  Settings' *Saving to* with *Change* - the causes in one wording
  (`FolderAccess.WHY_LOST`), as two of the three had lost the drive. Choosing a folder Android would not let JemRec keep is
  refused, and said, rather than taken and fallen back from at every call; a
  folder chosen before lets its grant go. Saved in the folder again - a drive
  put back - what was said goes, so its next loss is said again.
- **Battery use is Restricted** (1.2) - once, at a start of JemRec's process
  while the user's Battery setting for it is *Restricted* and JemRec is on
  and set up, and on home while it lasts, with *Open App info*
  (`BatteryHealth`). Lifted, it goes at the next reading of the window or the
  keep-alive's next run, which reads it. What it costs is under
  [Stopped in the background](#stopped-in-the-background).

**A tap, and a button.** A problem's tap opens JemRec where the problem is
shown - home's card or header, the Calls tab narrowed to the calls not
recorded, Settings with its row for the phone's background settings brought
into view for a phone stopping JemRec in the background - and its one button does the
fix: *Open App info*, *Choose again* (Android's folder picker at once),
*Self-test*, the phone maker's screen, *Allow this Wi-Fi* or *Pair again*.
Each tap and button names JemRec's own activity - a deep link, which then
opens Android's screen - never an implicit intent: one in a notification's
PendingIntent can be answered by another app's activity claiming the same
action. *Open App info* was the one that did not, until CodeQL's
java/android/implicit-pendingintents found it (PR #105, 2026-11-19).
Android's guide: "Don't include text actions that duplicate the behavior of
tapping on the notification body", and a tap "must display UI that relates
directly to that notification" (developer.android.com, Notifications). Until
1.2's reviews every button went where its tap did (`AfterCall.problem`, held
by `ProblemNotificationTest` and, for its callers, `ProblemButtonsTest`).

**Notifications off.** Home says what is off and what it costs: all of
JemRec's, *Record this call?* in Ask first - then no call is recorded, and the
header says *Cannot record* with *Tap to turn on notifications* - or *Recording
problems*. And in Ask first, its question turned down - below *Urgent*, the
only importance that "appears as a heads-up notification" (Android's
Notification channels), so it waits in the shade, missed mid-call - or, from
Android 14, its full-screen notifications turned off - given at install, an
app op the user can turn off, and Google Play takes it from apps that are not
calling or alarm apps (`canUseFullScreenIntent`, Android 14's behaviour
changes; AOSP's Full-screen intent limits) - so it stays a minute
(`sticky_heads_up_notification_time`), not until it is answered: measured on
the Android 17 emulator in a call (2026-11-18), allowed it was up all 100 s
watched, turned off it went between 57 and 60 s. Its full-screen window opens
while the phone sleeps or its lock screen is in front - not over a call's own
screen in the calls measured that day, locked and unlocked, where it came as
a heads-up - and, from SystemUI's FullScreenIntentDecisionProvider, over a
locked call's screen only where no heads-up can show (Do Not Disturb set not
to pop notifications; read, not measured), so its length is all it costs. Its *Turn on* asks Android's question again, or opens
the setting - the question's channel, or full-screen notifications' own page.
The two that leave calls unrecorded cannot be put away with *Not now*; the
question turned down or brief still asks, in the shade, and can. One put away
stays away while it is off, and the next one down is shown - one hid every one
below it until round 9 of the reviews - and once it is no longer off it is
forgotten, so the next time it is, it is said. The battery card's *Not now*
takes its notification down too, whose tap would open home with the card put
away; and a change of the mode or tracking re-words that notification only
while it is up - re-posted after the user put it away, it came back with its
sound (`setOnlyAlertOnce` holds only for one "already showing"). Until
1.2 notifications refused after setup - in Android's settings, or taken back
from an app left unused - put JemRec back on setup's first screen, recordings
and Off out of reach, while the recorder went on recording. The channels for
after each call, recording problems, messages and *Record this call?* are
made at the app's start, so each can be found in Android's settings before
its first use - and the two this checks, checked; the call's own, pairing's
and playback's are made as they are first used.

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
| `ACCESS_NETWORK_STATE` | To tell whether there is Wi-Fi to rebuild the recorder over, instead of trying and failing - and for the background checks to ask Android for a network to run with (from Android 14 a job that asks for one needs it). |
| `WRITE_SECURE_SETTINGS` | Granted by the app to itself over its own ADB session. Nothing but a shell can grant it, so no user can be talked into it. It lets JemRec switch Wireless debugging on for each start of the recorder, USB debugging back on when the phone turns it off, and debugging off with JemRec when you ask it to. |
| `POST_NOTIFICATIONS` | The recording notice, the ask-first prompt, the pairing code box. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | To stay alive while a call is being recorded and while the finished file is collected; while the recorder is restarted after a computer's cable comes out; and for the seconds it takes to warn that the phone stopped JemRec in the background. `SPECIAL_USE` is the honest type: the microphone type would need a microphone permission this app does not hold. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | To play a recording on with the screen off, in `PlaybackService` ([playing in the background](#playing-in-the-background)). It plays; it never records. |
| `RECEIVE_BOOT_COMPLETED` | A reboot kills the recorder; this is what arms the job that rebuilds it. |
| `READ_MEDIA_AUDIO` | To list recordings made before a reinstall. Refusing it only hides those. |
| `READ_EXTERNAL_STORAGE` | Android 12 only, which has no `READ_MEDIA_AUDIO`: the same, to list recordings made before a reinstall. It covers photos and videos too; JemRec reads only its own folder. Refusing it only hides those. |
| `READ_CALL_LOG`, `READ_CONTACTS` | Optional. The only way to know who a call was with: to label a recording, to name a file after the caller or their number, to keep or leave out a call by who it was with, and, for the call log, to notice a call that went unrecorded. |
| `MANAGE_ONGOING_CALLS` | For exact call tracking. Not one an app can ask for: the daemon allows it, as the shell, while the switch is on. Android then tells JemRec when each call is answered and ends. Tracking acts on a call in one case only: it hangs up the answering machine's call if the recorder is lost mid-message. The machine's answering is the recorder's, as the shell. |
| `USE_FULL_SCREEN_INTENT` | Keeps Ask first's question on screen during a call until you answer it; without it, a minute. Given at install; if you turn it off in Android's settings, the question still waits in the shade, and in Ask first home says what it costs. |
| `WAKE_LOCK` | So the keep-alive job can finish its few seconds of work, and so a phone with its screen off stays awake while it brings the recorder back after a USB cable, a minute and a half at most. WorkManager declares it too. And for the lock a phone app holds in a call, which darkens the screen at the ear ([playing at your ear](#playing-at-your-ear)); the proximity sensor it goes by needs no permission. |

No request to any server either. *Newest version*, in Settings' About,
hands GitHub's address for the newest release to the browser, which makes
the request; JemRec has no updater, which would need both the internet and
the right to install apps. The version shown beside it is the one a copied
report gives (`appVersion`).

Two more (`READ_PHONE_STATE` and `WRITE_EXTERNAL_STORAGE`) are actively
**removed** from the manifest, because the ADB library's own manifest would
otherwise pull them in; the third it pulls in, `READ_EXTERNAL_STORAGE`, is
declared instead, capped at Android 12, for the row above. One more that cannot be removed and means nothing:
`com.jemcik.jemrec.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which AndroidX
defines for this app alone when it registers a receiver as not-exported.

## When the phone refuses the grant

Setup ends by granting JemRec `WRITE_SECURE_SETTINGS` over its own ADB session.
OPPO, OnePlus and realme phones refuse it out of the box: their system takes a
list of permissions away from the shell while `persist.sys.permission.enable`
is `true`, which is how they ship, and `pm grant` answers *Neither user 2000 nor
current process has android.permission.GRANT_RUNTIME_PERMISSIONS*. Two reports:
a Nord CE5 on OxygenOS 16 (issue #64, which quoted that line) and a OnePlus 10R
on Android 15 (#61), which 0.25.1 told to look for a Xiaomi switch.

The way past it is the user's: the last switch in Developer options' Apps
section, **Disable system optimization** on ColorOS and OxygenOS 15 and 16,
**Disable permission monitoring** before; some label it just *Permission
monitoring*, on by default, to be turned off (Shizuku's setup guide; Octoclip's
notes, ColorOS in Chinese). Some builds show it only in English.
Nothing else gets past it: while it is off, the shell's own `settings put` and
`appops set` are refused too. None of that was measured here, with no such
phone. CallVault measured it on a OnePlus 9 (OxygenOS 14) and a OnePlus 12
(OxygenOS 16) on 2026-09-16: the property reads `false` once the switch is on,
and a grant made then outlives the switch going off again, and a restart.

So, since 0.25.2:

- On an OPPO, OnePlus or realme phone the pairing card names the switch, while
  the user is in Developer options anyway - unless the app can read the
  property and it says the switch is on.
- A refused grant is told from one that never ran by what `pm grant` printed,
  and the card names the switch: that one where the property reads `true` or
  the phone is OPPO-family, *USB debugging (Security settings)* on a Xiaomi,
  both anywhere else. **Open settings** sits beside **Try again**.
- The grant is read back from the package's own record (`dumpsys package`),
  not taken from `pm grant` saying nothing: a Xiaomi on HyperOS 3 was reported
  to CallVault granting nothing, silently. The app cannot ask for itself, as a
  grant reaches it only once it restarts. Where dumpsys does not list the
  package, the grant's exit status counts, as before.
- The recorder writes settings as the shell too, and those writes are read
  back and logged as refused where they were: USB debugging kept on (the
  app's own write, holding the permission, puts it back - UsbDebuggingWatch),
  and both switches off for *Turn all debugging off*, which the app then
  finishes itself once the recorder has gone (inferred: an emulator does not
  refuse the write). The one nothing can finish is
  the recorder's off when JemRec is uninstalled: the app is gone. So the
  cards and docs say to leave it that way.
- The recorder logs what Android said when it could not allow exact call
  tracking, and on these phones the self-test names the switch.

Measured here: the read-back on the Android 12 and 17 emulators (2026-10-29),
granted and revoked, and the `refused-grant` emulator check, where the debug
build answers with #64's refusal and Try again then grants for real.

## What stays switched on

**USB debugging stays on while JemRec is on; Wireless debugging only while it
starts its recorder** - and while JemRec is off too, unless you choose
otherwise (see [Off, and debugging with it](#off-and-debugging-with-it)). USB
debugging is what keeps `adbd` alive when Wi-Fi drops or Wireless debugging
goes off; with both off, `adbd` stops and the recorder with it. (Measured on
the Honor, 2026-10-26: Wireless debugging switched off with USB debugging on
and no cable, the recorder kept its process for ten minutes through Wi-Fi off
and on, a lock and a call; with a computer's cable in, the same process IDs
two and a half minutes later.) So the daemon puts USB debugging back on within ten seconds if
it goes off while `adbd` lives, the app does when the switch-off took `adbd`
with it (see [Staying alive without Wi-Fi](#staying-alive-without-wi-fi)), and
the app switches Wireless debugging on for each start, the recorder off again
once it is up ([Wireless debugging, only to start](#wireless-debugging-only-to-start)).
One you switch on yourself stays on.

What that means for the phone: while USB debugging is on, a computer you have
authorised before can open an ADB shell on it over a cable; while Wireless
debugging is on - a start's seconds, or while you have it on - a computer that
has been paired with it can do the same from the same Wi-Fi. An unpaired
computer cannot. Check *Wireless debugging → Paired devices* now and then and
remove anything you do not recognise. With *Start without Wi-Fi* on, port 5555
is open too, on every network the phone joins; a computer that reaches it, or
an app on the phone, can ask to debug the phone, and only a yes on the phone
lets it (see [Starting without Wi-Fi](#starting-without-wi-fi)).

Android shows a notification while something is connected to debugging:
"Wireless debugging connected" for the seconds a start's session is open, and
its USB twin while a computer's cable is in - on the Honor, 2026-11-21, with
the cable in (off the cable not read), the first posted for each start and
taken down as its session closed, nothing for Wireless debugging merely being
on, as AOSP has it
(`showAdbConnectedNotification`). Setup says all of the above before you
switch anything on, then asks whether JemRec should hide that notification.
Only on a yes does it get notification access, and it then snoozes nothing
but Android's own debugging notifications, matched by their system ID, or by
the word "debugging" on a ROM that numbers them differently. Settings can
change the answer at any time. The same access serves one other, separate
choice: *Show who called* for messenger calls (see Messenger calls), which
notes the caller's name from the switched-on messengers' call notifications
and reads nothing else.

Setup grants that access over its own ADB session. From Settings, since 0.31,
the recorder grants it: it is the shell, as a session is, and allows only
the app's own listener, which it names itself, then reads Android's list
back for up to two seconds - Android writes the list a quarter of a second
after the grant (read in AOSP) - and the app's own read of the access has
the last word. Settings opened a session for it before, which needs
Wireless debugging on - and LineageOS switches that off by itself a second
after a revive's session has started the recorder, so on the OnePlus, on
Wi-Fi, Show who called said "it needs Wi-Fi" and could not be turned on
(2026-11-12; Hide the debugging notification goes the same way, read from
the code). Where no recorder answers - none up, one from before 0.31, one
slow to answer - a session was still the way until 1.5; since then JemRec
starts the recorder, as *Tap to start again* does, and asks the one then up:
the start replaces an older one and finds a slow one up. Where none can be
started - no Wi-Fi and no port, JemRec off with all debugging - it is no
answer, as no session could be had either.

Either grant runs to its end once begun, and only then saves its switch on.
Since 1.0 Settings follows both switches as saved, as it follows the record
switch: JemRec closed from Recents during a grant and opened again showed the
switch off until the screen was next read, though the grant then saved it on.

To switch debugging off for good, uninstall JemRec first. If the recorder is
running, it notices the uninstall within a few minutes, turns both switches off
and exits - on an OPPO, OnePlus or realme phone only while its *Disable system
optimization* switch is set as setup asked ([when the phone refuses the grant](#when-the-phone-refuses-the-grant));
either way, once JemRec is gone nothing turns them back on. Its off-Wi-Fi
shield stands down first, as for debugging off: until 0.31 its ten-second
check could put USB debugging back on in the moment before the recorder
exited (read from the code).


## Keeping the pairing

Android forgets a debugging pairing a week after it is made. Each paired key
has a time, set when it pairs and by each connection signed in adb's older way,
over USB or to port 5555 (adbd tells Android of those, and Android notes the
time) - never by a connection over Wireless debugging - and a key older than
`adb_allowed_connection_time` (7 days where nobody set it) is deleted the next
time Android writes its list of keys: when a computer's cable goes in or out,
at its daily check, or as debugging starts - the phone's start, or a switch-on
with the other debugging switch off, which a revive's Wireless debugging off
and on can be (`AdbDebuggingManager`, read in AOSP, android11-release and main,
2026-11-15; `adbd_notify_framework_connected_key` in adbd's `adb.cpp`). With
USB debugging on, as JemRec keeps it, switching Wireless debugging on or off -
each start's, since 1.5 - writes nothing: the thread that keeps the list
counts its users, and writes the list only as the first starts
(`startAdbDebuggingThread`, read 2026-11-21). USB debugging switched back on
with Wireless debugging off - after the Honor's Developer options, say - is
debugging starting, which does; the daily check would within the day anyway.
JemRec connects over Wireless debugging - to port 5555 only with Start
without Wi-Fi, whose revives would renew it (read, not measured) - so its
pairing ends a week after setup, however often it connects. The recorder runs
on - once up it needs no ADB - but the next time it has to be started, after a
restart or a cable out on the Honor, the phone turns JemRec away, and home goes
to the pairing step.

Measured on the Honor, 2026-11-15: at 19:44:46 the cable to a computer came
out, `adbd` stopped as it does there ([Unplugged from a
computer](#unplugged-from-a-computer)), Wireless debugging was switched off and
on, and each of five connections in the next two minutes was refused; `dumpsys
adb` then listed the computer's key and not JemRec's. The same unplug at 01:13
that day had brought the recorder back in 16.6 s. On the Android 17 emulator
the same day, with the time set to two minutes: JemRec paired at 20:47:49,
connected anew at 20:51:01, and its key was gone at 20:51:05, three seconds
after the change - the connection already open kept working. Set to 0, the key
stayed past the same two minutes, through a new connection and the same check.
(The emulator lets any key in, so the refusal itself shows only on a phone.)

0 is what Developer options' *Disable adb authorization timeout* writes, on
phones that show it - the Honor's Settings has no such switch, only *Revoke USB
debugging authorizations* (its resources, read 2026-11-15) - and it keeps every
pairing - a computer allowed over a cable too - so
setup asks first: *Keep JemRec paired*, right after the debugging question,
and once of installs set up before 1.1. Settings → Keep JemRec paired changes
the answer. The switch shows Android's setting as it is: turned back on by hand,
it shows off, and nothing turns it off again.

The recorder writes it, as the shell, and first keeps what it was in a file of
its own in `/data/local/tmp`. Turned off in Settings, at Start fresh, and when
JemRec is uninstalled (the recorder notices within five minutes, as for the
debugging switches above), it puts that back - while the setting still reads
off: a value changed since is left as it is. Only a running recorder can: at
Start fresh with none up, the put back waits for the one the next setup
starts, and JemRec uninstalled while it is off with all debugging off leaves
the timeout off. A timeout off before JemRec asked
is the user's: nothing is kept, nothing put back. A choice made with no
recorder up - setup asks before pairing - waits, and goes to the next recorder
the app finds up, with the settings it sends each.

## Off, and debugging with it

**Some banking apps will not open while Developer options or debugging is on**
(issue #37). Settings → *When JemRec is off* chooses what goes off with JemRec,
by the record button or the Quick Settings tile (`DebugSwitchesOff`):

| Choice | Switches off | Back on |
| --- | --- | --- |
| Keep debugging on (the default) | nothing else | instantly, anywhere |
| Hide Developer options | Developer options | instantly, anywhere |
| Turn all debugging off | Developer options, USB debugging | needs Wi-Fi - or *Start without Wi-Fi* - about 15 s; no answering machine until then |

Wireless debugging is in neither since 1.5: it is off already while JemRec
runs ([Wireless debugging, only to start](#wireless-debugging-only-to-start)),
and the screen says so under the choices. One you switched on yourself goes
off with the last two, and On puts it back.

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
twice, on the Honor (2026-10-26), with Wireless debugging put back by On.
Since 1.5 the start switches it on itself, 4 seconds more: 10 seconds from On
to recording on the Honor (2026-11-22). The app's "about 15 s" is the slow end, a
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
from Wi-Fi is not switched on for nothing later, and not one JemRec switched
on itself, still on in a start's seconds or after an off that could not be
made: the next start switches that on, and off again - and On puts back
exactly that, USB debugging before Wireless debugging, then starts the
recorder. A new
choice made while JemRec is off applies from the next off.

**Nothing brings debugging back behind the user's back.** Every way the
recorder is revived - the keep-alive job, the Wi-Fi and cable watches, boot,
opening the app, and the USB-debugging watch - goes through one function, and
it stands down while the third choice is in force. It asks under the
recorder's lock, and Off writes the switches under the same lock: a revive
under way when Off comes finishes before Off writes, and one queued behind it
stands down. Off and On ask the switch again once they have the lock, and a
keep-alive or watch about to stand down asks again too: an Off taken back
while it waited writes nothing. Until 0.28 a revive asked before the lock and not again, and one
queued as Off came had USB debugging back on - reproduced in the unit tests,
two revives queued and an Off between them. While JemRec is off with
Developer options hidden, a revive (after a reboot, or a cable from a computer)
turns Wireless debugging on to start the recorder and off again once it is up
- since 1.5 by the recorder, as after any start, and no longer written down:
until then On switched it on again for nothing. One found on that JemRec did
not switch on goes off and is written down, as before.
A recorder that dies while it waits out a call - a computer's cable pulled -
leaves its off unfinished; the next revive or keep-alive run that finds no
recorder finishes it, once; one still up whose Off was cut short before it
answered - the app killed while it asked - is asked again by the next look,
and goes. USB debugging the user switches on themselves while JemRec is off
stays on. Switched off, JemRec takes down the recorder-down alert at once,
and again whenever it stands down: a recorder down is no problem then. Start fresh puts Developer options back,
since setup sends the user there.

Not measured: a banking app that refuses. Ours open with debugging on, so what
any one bank looks at is not known here; the order to try is the order above.
The emulator checks `hide_developer_options` and `debugging_off` run both
choices end to end on Android 12, 13, 16 and 17, and `start_without_wifi` the
third with Wi-Fi off, back on over the port.

## Errors and cancellation

**At the edges - the recorder, ADB, Android's settings - null or false
means "could not ask".** The recorder's questions answer null when the
recorder could not be reached (`DaemonClient`: the pending files, a test's
result, the listener's grant), and a write of a setting false when it did
not go through (`GlobalSettings.set`). A caller never takes "could not ask"
for "no": `VoipApps.supported` counts a failure to ask as yes, and asks
again, rather than switch messenger calls off on a phone that records them.

**A `Result` where the failure itself is wanted:** its words, for setup's
card or the log (`Setup.finish` and `reset`; ADB's `pair`, `connect`,
`autoConnect`, `exec` and `restartAdbd`; the recorder's start,
`ensureRunning`), or to tell a file that could not be read from one that has
no tags (`RecordingTags.read`, read again later).

**A cancellation is thrown on, never caught as a failure.** A coroutine
cancelled - its screen closed, its service stopped, a time limit run out -
is being stopped, and is not "could not". Where a failure is caught around a
suspend call it is let go (`rethrowingCancellation`, or a
`catch (e: CancellationException)` first), and work that must finish once
begun runs under `NonCancellable` (the notification-access grant, Start
fresh, the recorder's start in a revive, the watchdog on an ADB command).
Since 1.0 the places that broke the rule in a way a user could meet keep it:
the pairing notification's "timed out" was replaced - by "Something went
wrong: Job was cancelled", or, in setup's last step (`Setup.finish`), by
"Paired, but the recorder did not start" - when Android stopped its service
at the three-minute cap, and its own words now say how far the pairing had
got - still pairing, the connect, or setup's last step - where one "Paired!
... could not connect" was false in the first and the last; the self-test,
on the IO dispatcher already, caught its own 6 s limit and said "Timed out
waiting for 6000 ms" instead of its words for a slow recorder; and a socket
the recorder's client opened as its caller went was left open.

Left as they are, read from the code on 2026-11-14, as none changes what a
user sees:
- The filter row's scroll to its first lit chip (`Narrowing`) catches the
  cancellation of the effect it is the last line of.
- Four catch one and could not be cancelled today: `RecorderSwitch.turnLater`
  and `AnswerController`'s stop of a take run in process scopes never
  cancelled, and the recorder's start (`DaemonLauncher.ensureRunningLocked`)
  and Start fresh's stop of the recorder only under `NonCancellable`.
- ADB's `autoConnect` and `exec` catch one inside a `withContext`, which
  throws it again as it ends.

## Where coroutines run

Besides the coroutines Compose runs for a screen's own drawing
(`LaunchedEffect`, `produceState`), WorkManager's workers
(`RecorderKeepAlive`, `UsbDebuggingWatch`, and the debug build's
`TurnOnLater` and `ReviveLater`), and the watchdog on an ADB command, a
coroutine of its own under `NonCancellable` (`watched`), every coroutine
the app starts runs in one of four kinds of scope, chosen by who is still
there to take its answer:

- **The screen's.** `viewModelScope`, handed to the view model's parts
  (`Actions`, `Playback`, `Sharing`, `WaveformFill`, `Reading`,
  `RecordingsList`, `RecorderAndSetup`, `AnswerController`), and the view
  model's own watch of which screen shows, ended with it. They end when the
  view model is cleared - JemRec closed from Recents; Back only moves it to
  the background since Android 12 - and with them anything only the screen
  would have shown.
- **A component's own.** `CallMonitorService`, `PairingService` and the
  Quick Settings tile (`RecorderTile`) each make a scope as they start and
  cancel it in `onDestroy`.
- **The process's, never cancelled.** For what has to finish whoever asked:
  `RecorderSwitch`'s, where the record switch's turn runs - a tile is
  unbound and a question's activity finishes as soon as they close;
  `CallTracker`'s, with its two ordered channels, as Telecom unbinds the
  tracker the moment the last call goes and the collection after it must
  still run; `Actions.OUTLIVING`, on the main thread, where an answer
  already given waits its turn and runs once the screen has gone (a
  dialog's yes, Hide the debugging notification's grant); and
  `AnswerController`'s, for what must reach the recorder after the
  answering machine's screen has gone, a take stopped.
- **A broadcast's.** `goAsync()` and a scope of its own on the IO
  dispatcher, finished in `finally`: the after-call notification's delete
  (`AfterCallReceiver`), and the debug build's receiver.

Plain threads are left for three things: the check of the phone's own
records of JemRec stopped in the background, at the process's start
(`JemRecApp`) and at each open (`MainActivity`) - binder calls the first
frame should not wait for; the waveform's decoder's workers; and the debug
build's messenger-answering spike (`VoipAnswerSpike`).

What runs in a scope that is never cancelled is waited for by its tests,
which share the process: a screen test that left an outliving answer
running had it run on into the tests after it (2026-11-14).

---

Everything else that would have gone in a document is in the code, beside the
thing it explains: why the timestamps are counted rather than read, why an idle
call state is not believed the first time, why the daemon polls a setting
instead of observing it. Each of those comments records the measurement it came
from, because the reason is the part that stops someone undoing it.
