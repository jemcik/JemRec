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
  [after a restart](#after-a-restart)
- The features: [recording and being asked](#recording-and-being-asked) ·
  [exact call tracking](#exact-call-tracking) ·
  [whose calls to keep](#whose-calls-to-keep) · [file names](#file-names) ·
  [messenger calls](#messenger-calls) · [the answering machine](#the-answering-machine) ·
  [the Calls tab](#the-calls-tab) · [playing a recording](#playing-a-recording) ·
  [playing in the background](#playing-in-the-background) · [three looks](#three-looks) ·
  [keeping and deleting](#keeping-deleting-and-cleaning-up) ·
  [notifications](#after-the-call-and-when-something-is-wrong)
- The phone: [permissions](#what-it-does-not-ask-for) ·
  [what stays switched on](#what-stays-switched-on)

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

When the daemon does die — a reboot kills it outright, and the system reclaims
it eventually — it has to be started again, and starting it is the one operation
that needs ADB and therefore Wi-Fi. A scheduled job and a system-held Wi-Fi
watcher do it without you: the app is woken when a network appears, rebuilds the
recorder in a few seconds, and stands the ADB session back down.

Both of those have to survive a reboot, and both are armed by the boot
broadcast — so on a phone whose ROM declines to start the app at boot, neither
exists and the repair never begins. That is the case in [After a restart](#after-a-restart), and it is why the
manual says to open the app once after a restart rather than trusting the
machinery. **Between the restart and that moment nothing is recorded**, which is
why the header says so plainly rather than looking healthy - and after two
minutes of it, a notification does too.

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

Wi-Fi is needed to *rebuild* the recorder, never to use it. Once it is up,
recording carries on with Wi-Fi off, on mobile data, anywhere.

## Recording, and being asked

Automatic mode records every call from the moment it goes off-hook. Ask-first
mode records **nothing** until you tap Record on the start-of-call notification,
so a declined call is never written to disk — the setting means what it says,
and the cost is the few seconds before the tap.

Recordings are Opus in an Ogg container, in the phone's standard
`Recordings/JemRec` folder, where any file manager or music app can open them.
You can point the app somewhere else.

A Quick Settings tile turns recording on and off without opening the app. It
is the same switch as the one in the app's header.

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
ended, which way it went, and the number only with the call-log permission.

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
each app switched on separately in Settings. Nothing is recorded from any of
them until you do.

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
capturing - so a voice message played at the ear is not one. The other person
is on the left channel, you on the right.

- **An app can refuse.** Android lets an app mark its audio as not capturable,
  and honours that whatever the recorder holds. JemRec does not try to get
  round it.
- **Not in the call log.** Android's call log has no messenger calls, so their
  recordings are in Recordings, marked with the app's icon, and not in the Calls tab.
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
heard the call throughout. Signal takes the same path and has not been tested
yet.

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
call audio, because a phone call ringing over it may take the mode. Recording keeps a call when unsure,
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

Missed, declined and blocked calls are marked as such. The tab needs call-log
access, the same optional permission that names callers in the list.

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
  recorder is back.
- **A call was not recorded** - in automatic mode, with call-log access, the
  call log is held against what was recorded, and an answered call with nothing
  to show for it is reported once. Recordings you delete do not count as missed:
  the app notes each call as it saves it.

None of it needs a permission the app did not already have.

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
| `WRITE_SECURE_SETTINGS` | Granted by the app to itself over its own ADB session. Nothing but a shell can grant it, so no user can be talked into it. |
| `POST_NOTIFICATIONS` | The recording notice, the ask-first prompt, the pairing code box. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE` | To stay alive while a call is being recorded and while the finished file is collected. `SPECIAL_USE` is the honest type: the microphone type would need a microphone permission this app does not hold. |
| `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | To play a recording on with the screen off, in `PlaybackService` ([playing in the background](#playing-in-the-background)). It plays; it never records. |
| `RECEIVE_BOOT_COMPLETED` | A reboot kills the recorder; this is what arms the job that rebuilds it. |
| `READ_MEDIA_AUDIO` | To list recordings made before a reinstall. Refusing it only hides those. |
| `READ_CALL_LOG`, `READ_CONTACTS` | Optional. The only way to know who a call was with: to label a recording, to name a file after the caller, to keep or leave out a call by who it was with, and, for the call log, to notice a call that went unrecorded. |
| `MANAGE_ONGOING_CALLS` | For exact call tracking. Not one an app can ask for: the daemon allows it, as the shell, while the switch is on. Android then tells JemRec when each call is answered and ends. Tracking never acts on a call; the answering machine's answering is the recorder's, as the shell. |
| `USE_FULL_SCREEN_INTENT` | Lets the ask-first prompt last a minute instead of five seconds. Not auto-granted, and nothing is gated on it. |
| `WAKE_LOCK` | Not declared here: WorkManager brings it, so the keep-alive job can finish its few seconds of work. |

Three more (`READ_PHONE_STATE` and two storage permissions) are actively
**removed** from the manifest, because the ADB library's own manifest would
otherwise pull them in. One more that cannot be removed and means nothing:
`com.jemcik.jemrec.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which AndroidX
defines for this app alone when it registers a receiver as not-exported.

## What stays switched on

**USB debugging and Wireless debugging stay on for as long as JemRec is
installed.** Turning Wireless debugging off restarts `adbd`, which kills the
recorder, and USB debugging is what keeps `adbd` alive when Wi-Fi drops. So the
daemon puts USB debugging back on within ten seconds if it goes off, and the app
turns Wireless debugging back on the next time it rebuilds the recorder.

What that means for the phone: while debugging is on, a computer that has been
paired with it can open an ADB shell on it from the same Wi-Fi, and one you have
authorised before can do the same over a cable. An unpaired computer cannot.
Check *Wireless debugging → Paired devices* now and then and remove anything you
do not recognise.

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

---

Everything else that would have gone in a document is in the code, beside the
thing it explains: why the timestamps are counted rather than read, why an idle
call state is not believed the first time, why the daemon polls a setting
instead of observing it. Each of those comments records the measurement it came
from, because the reason is the part that stops someone undoing it.
