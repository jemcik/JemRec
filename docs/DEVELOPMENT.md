# Developing JemRec

Building it, testing it, and releasing it. For what the app does and how it is
put together, see the [README](../README.md) and [How JemRec works](ARCHITECTURE.md).

**Contents:** [Build](#build) · [Tests](#tests) · [Releases](#releases)

## Build

Needs **JDK 21** - that major, not "or newer" - and an Android SDK with:

    platforms;android-37       the app's compileSdk
    platforms;android-36       what the shell daemon links against
    build-tools;36.0.0         d8, for dexing the daemon

Then:

    ./gradlew assembleDebug

The daemon is not a Gradle module. `shellserver/build.sh` compiles it against
android-36's `android.jar` - pinned because the jar's bytes depend on it; one
jar runs on Android 12 and later - and Gradle runs that script as part of every
build and packages the jar as an app asset.

The release build is shrunk with R8 and the debug build is not. Measured on
0.4, the unshrunk APK was 38 MB of which this app's own code was 0.2 MB; the
rest was libraries shipped whole, and a third of it was nothing but their
class, method and field names. Shrunk, it is under 7 MB. Names are kept -
[app/proguard-rules.pro](../app/proguard-rules.pro) says why, and lists what
R8 must not remove and the two references it must not warn about, which is
the whole file.

A third build, `shrunk`, is the debug build through R8 with the same rules,
and it is what the emulator checks install. The script builds it first
(`./gradlew :app:assembleShrunk`, which redoes only what changed), so a run
never checks an APK older than the sources; CI builds it on each push to main
and each pull request.
Until 0.25.3 they installed debug, so nothing ever ran what R8 makes, and a
library rule that let R8 remove a constructor WorkManager finds by reflection
failed every one-time work in every release from 0.5 to 0.25.2 - each kick of
the keep-alive, every USB debugging watch - without a check seeing it
([#69](https://github.com/jemcik/JemRec/issues/69). The constructor is missing
from the published 0.8 APK and from a local build of 0.25.2's code, measured
here, and from the published 0.25.2 APK, as its reporter measured; 0.5 to 0.7
were built with the same AGP, WorkManager and rules). Measured 2026-10-31 on
the Android 12 and 17 emulators: with WorkManager 2.9.1 the shrunk build (then
still debuggable, see below - R8's debug mode removed the constructor too)
fails `usb_debugging` ("the app never switched USB debugging back on", the log
saying `NoSuchMethodException: androidx.work.OverwritingInputMerger.<init>`);
with 2.12.0 the same work ends `Worker result SUCCESS`. It is not debuggable,
because AGP gives a debuggable build R8's debug mode, which optimises far less
(measured: 49,827 items removed against the release's 70,575; not debuggable,
70,635 against 70,578 - the few more presumably the diagnostic code's, not
checked). So the checks read the app's files as root through the image's
`su`, not `run-as`, and the revive log is kept by a resource the debug sources
set rather than by the debuggable flag. It has no unit tests of its own: AGP 9
makes them for debug only, and its sources are debug's.

The JDK major is exact because the daemon's bytes depend on it: measured, JDK 17
and JDK 21 turn the same sources into dex files 264 bytes apart, and an APK that
differs by machine is one F-Droid cannot reproduce. 21 is what F-Droid's build
server runs, so 21 is what CI installs and what `build.sh` insists on -
`JEMREC_JAVA_HOME` points it at one if the default is something else.

**Dependency verification.** Every artifact the build downloads - libraries,
plugins, their metadata - must match the SHA-256 in
`gradle/verification-metadata.xml`, or the build stops (Gradle's "Verifying
dependencies"; from 1.2). A dependency changed, its checksums are written
again with the tasks that resolve everything, and the diff read before it is
committed:

    ./gradlew --refresh-dependencies --write-verification-metadata sha256 assembleRelease assembleShrunk assembleDebug test :daemon-tests:errorProne lint
    ./gradlew --refresh-dependencies --write-verification-metadata sha256 -Pcoverage createDebugUnitTestCoverageReport :daemon-tests:test :daemon-tests:pitest :pure-tests:pitest

(the second for the coverage reports' JaCoCo and the mutation tests' PIT,
which only those tasks fetch). `--refresh-dependencies` makes Gradle fetch
everything as CI does from nothing: written from the local cache, the file
missed metadata a fresh fetch reads - BouncyCastle's BOM `.module` among
five - and CI failed on it before building anything (PR #105, 2026-11-19).

Gradle adds what is new and keeps what the file holds, including what was
written by hand: aapt2's jar for Linux and for Windows, which a Mac build never
downloads - taken from dl.google.com and checked against the `.sha256` files
published beside them - so CI and F-Droid's builder, on Linux, find theirs. A
new AGP brings a new aapt2: its three jars are added the same way. Javadoc
and source jars are trusted as they are, as Gradle advises, for the IDEs that
fetch them. Dependabot's Gradle pull requests fail their build until this is
done on their branch: Dependabot does not write the file (dependabot-core
issue 1996).

The actions in `.github/workflows` are pinned to a commit, each with its tag
beside it, as GitHub's hardening guide advises: a tag can be moved, a commit
cannot. Dependabot proposes their updates, keeping both.

## Tests

2981 JVM tests, no device needed - 2555 for the app and 426 for the daemon:

    ./gradlew test

They cover the parts where a mistake is silent: the list's filtering (shared by
select-all, so a wrong answer is a wrong bulk delete), the daemon's wire format,
the setup state machine and every regression its comments describe, the Opus
header, the call-log match, the home screen's selection logic, the lists' day
headings (just after midnight, "an hour ago" is yesterday), what each Settings
row says a setting is set to, and every file name read back in every format (a
misread name is a recording dated wrong).

**The screens are tested too**, on the JVM, under Robolectric: the real
composables drawn from a state the test sets up - recording, switched off,
unable to record, playing, selecting, empty, a permission missing, a rule
that keeps nothing, a phone too old for messenger calls, the dialogs - in
light and dark, and at twice the text size. Each is compared with a PNG in
`app/src/test/screenshots`; a screen that comes out different fails the
build, and `app/build/outputs/roborazzi` then holds the reference, what was
drawn, and the difference side by side. A few more tests check what the
screens do rather than how they look: Back from each screen of Settings,
Settings keeping its place, what a screen reader is told about the choices
and switches, a disabled row ignoring taps. The people on the screens do not
exist, the clock is fixed, and so are the language and time zone, so the same
state draws the same pixels on any machine on any day.

After a change to a screen that was meant, draw the references again, look at
every PNG that changed before committing it, and commit them with the change:

    ./gradlew testDebugUnitTest --tests '*.ui.screens.*' -PrecordScreens=true

Two of the logic tests hold the parts where 0.14 went wrong:
- **What becomes of a recording** (`SavePlan`): which calls it holds, who
  each was with, what each file is called, which the rules leave out. A call
  can be known two ways - from Telecom (exact call tracking) or from the call
  log - and the tests hold both to the same file name for the same call. In
  0.14 they disagreed, and a number in no contact lost its caller-ID name
  ([#30](https://github.com/jemcik/JemRec/issues/30)).
- **The daemon's call states** (`CallStates`, in `shellserver/tests`): which
  way a call went and when it ended, from telephony's events. A call that
  rang within two seconds of the call before was recorded as outgoing.

And one holds the answering machine, whose mistakes would be heard by
callers: **its states** (`AnswerMachine`, in `shellserver/tests`) - nothing
answered without a go-ahead for the ring in progress, never during another
call, the microphone always given back, and the phone's own unmute at the
end of a call not taken for *Talk to them*.

**What the code does today, pinned.** Before the 1.0 restructuring moves any
code, tests hold what it does now where no test reached before:
`CallMonitorService` driven by the recorder's wakes, the after-call notices
and the ask-first prompt's ticket, the call-log queries against a fake call
log (`FakeCallLog`), the recordings list, Recently deleted and auto-delete
over fake MediaStore and folder providers (`FakeStorage`), the keep-alive's
and the USB watch's runs and what each trigger arms (WorkManager's test
driver), the record switch, a revive while JemRec stays off, and the main
screen's reading and busy rules. Where one pins something that looks wrong,
its comment says so ("Pins today's behaviour, which looks like a bug") and
what: the fix, when it comes, turns the test round.

**Every branch of a save** (`RecordingSaverTest`, from 0.27): the saver takes
the recorder's files, the Ogg writer, where a file goes, the clock and its
thread as seams with today's objects by default, and the tests put fakes in
(`SaverFakes`): what is saved and under what name, what stays on the
recorder for later, what the ledger is told and when, what is announced, the
log lines the emulator checks read, and the week a failing file is retried.
The call service's tests drive it with the same fakes, and with its own
thread and the saver's made the test's, so a save that ends before its launch
returns does so every time; its rule for standing down (`StandDown`) is
tested apart, with jobs whose ends the test decides, so two jobs ending
together do too.

**The recorder's lifecycle** (from 0.28): a recorder for the tests on the
loopback port (`FakeRecorder`), which proves itself with this install's
token and answers each command as the test says, so a revive, a start, the
keep-alive, the USB and cable watches, Turn all debugging off and Diagnostics'
Start recorder run against a recorder that is up, out of date, or
answering anything the protocol allows. Behind the revive's seam
(`ReviveWorld`), a phone of the tests' own (`FakePhone`) runs a revive's
whole sequence - the switches, adbd, the connect, the port, the start - in
virtual time (`ReviveSequenceTest`). What each trigger arms, whether it goes
on, and what follows its revive are tables (`Triggers.kt`, tested in the pure
module below); `SwitchWritersTest` holds that only `RecorderLifecycle`
writes the debugging switches.

**Under the plain Application** (from 2026-11-20,
`app/src/test/resources/robolectric.properties`): Robolectric starts the app
afresh for each test, and the thread `JemRecApp` starts, which nothing waits
for, could outlive its test and hand the next test the ended one's files
folder - main's build failed twice so. A test of what the app does at its
start calls that work itself.

**Robolectric's Android jars** (from 2026-11-20, `app/build.gradle.kts`): the
Android the tests run in - 16's and 13's jars, 383 MB - comes as Gradle
dependencies, checked against `gradle/verification-metadata.xml` and kept on
CI with the rest; Robolectric runs offline and reads them from
`app/build/robolectric-jars`. Left to fetch them itself, it did so on every
CI run, and a 404 failed five test classes (PR #123, 2026-11-19). The first
local run fetches them once into Gradle's cache; copies Robolectric kept in
`~/.m2` before are no longer read. A new Robolectric may ask for other
versions: its tests then fail with `IllegalArgumentException` at
`LocalDependencyResolver`, and the test report names the jar ("Path is not a
file: …") - change the version in `app/build.gradle.kts` and write the
checksums again (Dependency verification, above).

**Each of the three places** (from 0.29): Robolectric has no MediaStore and no
folder provider, so `FakeMediaStore` and `FakeFolder` stand in for them, far
enough to take what JemRec writes as well as what it reads. Over them, what a
save asks of each place and how it falls back (`RecordingStoreTest`); what
each is asked to trash, restore and delete for good, and what comes of each
answer, Android's own yes included (`TrashPlacesTest`); and marks through the
providers, and a place that cannot be asked (`PlacesTest`). These hold what
JemRec asks for. What Android makes of it is the emulator checks' to show.

**The screens' view model** (from 0.30): its parts are tested through it, as
the screens call them. Its busy table, action by action: each action whose
control is greyed while the app is busy is let go when tapped then, and runs
when tapped once the app is free; each answer already given waits its turn,
a picker's answer is taken at once
(`BusyPolicyTest`); what asks for a fresh reading and what does not
(`ReadingTest`); a notification's buttons (`DeepLinksTest`); the player before
its session answers, Share, and the log (`PlaybackAndShareTest`); the four
Allow buttons for the call log, the narrowing line, the search's focus and
the list's next page and its place when the phone is turned
(`ListScreensTest`). Two moments no reading reaches: a waveform fill-in's
run ending, stood in for by a seam (`WaveformFillTest`), and the view
model's own end, through a `ViewModelStore` as an activity ends it
(`GreetingPreviewClosedTest`).

**The recorder's decisions and its protocol** (from 0.31, in
`shellserver/tests`): when a call is recorded, asked about or left out, the
skip window, a wake for a call already over, a message taken over and a
recording's four endings, as tables checked against every combination
(`CallDecisionsTest`); every reply line in `protocol.txt` built by the
recorder's own `Wire`, and the handshake against the same worked one as the
app's (`WireTest`, `HandshakeTest`); the hop to the main looper given up on
before the app's wait and never run late, but for the custodian's, which no
app waits on (`HopTest`); and the messenger
copy's policy held by the captures reading through it (`PolicyUseTest`).

**Names and words something else depends on** (`app/src/test/.../contract`):
the component and worker class names the phone and the tools remember,
the recorder's own class name, and the actions and extras of its wakes
(`FrozenNamesTest`); the preference keys, the enums stored by name and a fresh
install's defaults (`PreferencesInventoryTest`); every log line
`tools/emulator_checks.py` waits for, as it is written or from the parts it is
built of (`LogLinesTest`); and every reply of the loopback protocol, listed in
`shellserver/tests/fixtures/protocol.txt`, as the app reads it and the
recorder writes it (`ProtocolTest`). A rename in any of these compiles and
passes every other test. They search the code whole and without its
comments, so a file moved between packages changes nothing for them, and a
comment that quotes a line does not stand in for the line. Beside them,
`HandshakeTest` runs the app's side of the handshake against the one worked
by hand in `shellserver/tests/fixtures/handshake.txt`, which the recorder's
tests hold their side to too.

Before a release, the same calls go through the real app and daemon on an
emulator, for each Android version it claims (12, 13, 16 and 17 for 0.29 -
16 is what the Galaxy phones of the issues run). The script boots each AVD
itself, from nothing, two at a time, and shuts it down when its checks are
done:

    python3 tools/emulator_checks.py --avds jemrec-api31-0.14,jemrec-api33,jemrec-api36,jemrec-api37.2

Each one is started with `-no-snapshot -memory 4096 -no-boot-anim -camera-back
none -camera-front none -no-window -crash-report-mode never` (`EMULATOR_FLAGS`, with
why: an emulator that crashed before asks at its next start whether to send
the report, in a dialog that holds the boot until answered) - with `--wipe`, its
data wiped too (`-wipe-data`), so a run is not shaped by the runs before it: by
2026-11-17 the Android 12 AVD held 11 empty recordings and hundreds of an old
install's - and is given until its load settles after the boot, up to three
minutes. Then it is set up with
animations off, as Android's testing guide sets a device up for UI tests.
Every line of each run is printed after its AVD's name, and kept in
`app/build/emulator-checks/<avd>.log`. The run ends with one line for each
AVD. `--serial emulator-5554` checks an emulator that is already up instead.

Cold, not `-no-snapshot-save` alone, which still loads the AVD's quick-boot
snapshot at every start and throws away whatever happened since it was taken:
the 17 AVD showed JemRec 0.9 for eight days that way, until 2026-10-28 (every
run installed the current build first, so the results were for it).

Any crash of the app during a check fails that check, whatever else it found,
and so does an "isn't responding" of its own (Android's DropBox keeps both), a
native crash of the app (DropBox's `data_app_native_crash`), and a native crash
of the recorder, read from the emulator's tombstones by its command line:
DropBox keeps the recorder's on Android 12 but not on 17, whose UWB HAL crashes
every few seconds and leaves that tag's entries "contents lost". Native crashes
were not counted until 1.9.1: the app died of SIGSEGV in two full runs on the
12 AVD that passed, found afterwards in DropBox (2026-11-25), and in a run on
the 13 AVD on 2026-11-21, found in its tombstones five days later.

A check that fails is run again, with the others that failed, on the same
emulator shut down and booted cold again by the script itself; it fails only
if it fails both times, and its first failure stays in the output - unless
JemRec crashed or stopped responding during it, which fails the run whatever
the retry does, as a retry is for the emulator's bad moments, not the app's
(the 12 AVD's "isn't responding" in `usb_debugging`, 2026-11-18, passed on its
retry and was found only so); a setup
that fails before any check is tried once more the same way (`--no-retry`
turns both off, for working on a check). Every check's first try and retry is
kept across runs with retries, with the tree it ran on
(`~/.cache/jemrec/emulator-checks.tsv`; a `--no-retry` run is not kept), the
summary names each check that passed only on its retry and in how many of its
last runs it did so, and one that did so in 2 of the last 3 runs on an
Android version fails the run - Android's guide: "keep track of and fix flaky
tests". A check that failed both times failed as it was, and counts as no
flake. Three past "flakes" here were bugs (answer_during_voip on Android 12
failed 4 runs of 5, 0.24). And for the whole run, every few
seconds, an Android "isn't responding" dialog - System UI's, on a loaded
emulator, or JemRec's own, which fails its check as above - is answered Wait, as it would otherwise hold the screen from the
check under way. Android's own `hide_error_dialogs` is left off: with it on,
Android kills the app that stopped answering instead of asking
(`AppErrors.handleShowAnrUi`), and System UI would restart in the middle of a
check. The emulator's own
call network, Wi-Fi and timing give way now and then under load: in a full
run on 2026-11-17 the Android 17 AVD failed `caller_id`, `app_call`,
`mode_switch` and `allow_this_wifi`, and the Android 12 one `ringing_gap` and
`start_without_wifi`, with no crash and nothing changed in what they check;
such failures used to be rerun by hand, on a fresh boot. An emulator that
ends mid-run - ten crash reports between 2026-11-12 and 2026-11-18, all
the emulator's own: four its hang detector ending it with its sound output
on the Mac deadlocked in CoreAudio, three its GPU renderer freeing what a
process that ended had drawn with (gfxstream), three its window on the Mac,
which the AVDs are now booted without (`-no-window`) - stops the pass: the check under way and those left
are run on the fresh boot, kept in the history as LOST rather than as their
failures; the lines where the emulator ended - GONE, or the check's own
failure - name the Mac's crash report and what it says.
Since 1.3 the AVDs boot without sound (`-no-audio`), which avoids that, and
a run is no longer heard on the Mac: a call's own sound - a tone the
emulator's audio HAL makes - played in full whatever the volumes, Android's
master mute, or `QEMU_AUDIO_OUT_DRV=none`, which this emulator ignores
(2026-11-20). Without sound the Android 17 AVD once played too slowly for
`answering`, `background_playback` and `both_ears` (2026-11-18); measured
again, with the audio server started afresh before the playback checks, the
three playback checks passed, 5030 ms played in 5 s (2026-11-20).
The sound has a cost of its own: an emulator's speaker builds a backlog of
audio written and not yet played, which nothing drains until the audio
server restarts - AudioFlinger's "Threadloop write latency" of the speaker's
thread (`dumpsys media.audio_flinger`). On the Android 13 AVD alone it was
1510 ms after the 24 checks before the playback ones, about 0.35 ms a second
from ringing alone; it grows in jumps when the Mac's audio stalls under load
(inferred - the stutter heard from the emulators). A player started behind
it is heard that late, and `background_playback`, `both_ears` and
`raise_to_listen` read that as JemRec's playhead running ahead: they failed
first and passed on their retry in two runs of three on 12 and 13 (2026-11-19,
"chronic"). Restarted, the same checks passed on the same boot, so each of the
three now starts the audio server afresh and says the backlog it cleared. A
phone's audio hardware keeps its own clock: this is the emulator's.
Two more "chronic" ones that day were the emulator's too. `succession` on 17:
the contact's call answered, Telecom put the phone in call mode 2.5 s later
and told the recorder its off-hook as late, and JemRec recorded the moment it
was told (on the Honor the same day, 24 ms after the answer); a call's late
start is now counted from the call's audio, Telecom's own "Mode MODE_IN_CALL"
(`dumpsys telecom`), and Android's part is said beside it. `wifi_back`: in
1.2 it waited five minutes for a retry that did not come, and the job list read
after that said "Ready: true". That was put down to JobScheduler's connectivity
batching, whose flag (`batch_connectivity_jobs_per_network`) is off on these
AVDs and the Honor - with JemRec in the ACTIVE bucket there (EXEMPTED on the
Honor), which JobScheduler's other batching leaves alone. Measured again
(1.3): a retry's delay ends with an alarm that is inexact (up to 75% of the
wait late) and does not wake the device; one retry in six, nothing reading it
and the screen on, ran 299 s into a 60 s wait, which that does not explain -
nor what 1.2 waited for. And a read of JobScheduler's state once a job's delay
is over, before that alarm, marks the job ready without telling the scheduler
and, from Android 14, stops tracking it, so the alarm reports nothing. The "Ready: true"
was that read's own, and 1.2.1's version of the check, reading every 2 s, lost
its retry the same way before it forced it. The check now reads the retry
once, while its delay still runs, waits the delay out by the clock, and runs it
with `cmd jobscheduler run -f`, Android's own tool for testing jobs.

It installs the shrunk build (see above), sets JemRec up from nothing and
places simulated calls: the ringing cut, the same cut with nothing captured
until the answer (as over Wi-Fi Calling on a Pixel, issue #40: the recorder
is stopped while the call rings), an unanswered call not kept, an incoming
call kept to the second, a stranger skipped before recording, two calls in
quick succession, a caller-ID name, a contact dialled without the
country code named by the number with it (the number format, issue #71)
and a call that the call log says was made in Ukraine named with
Ukraine's code, the self-test, and the answering machine: a message
taken and marked with the microphone given back, a caller who hangs
up during the greeting, the recorder killed mid-message and the message's
call hung up by the call's own service, its file saved as a message and the
mute given back by the recorder started again (`message_recorder_lost`), a caller
the rules leave out left to ring, a phone
call ringing over a messenger call left to ring (a stand-in call from
`shellserver/probe`, built by the script if need be), a caller left to ring
in the day after an emergency call (the pause set as the call tracker sets it;
no emergency number is dialled), in Ask first a call taken over: the
message kept and the rest asked about, and a test call, with the machine
itself off, answered with its greeting on the call's voice path - not the
mixer's, whose route is the call-screening one - and its call read in
Telecom's list as not on Wi-Fi (the mark that names a test over Wi-Fi
Calling), and a test call's greeting moved under it - the stand-in call
taking communication mode over it, on Android 14 and later - stopped and
played again on the call-screening route, and again on the call's voice path
once the mode is back. Then another app's call through
Telecom, as Google Meet's are (a stand-in call of the debug build's own): left
alone, a contact's phone call rung in over it recorded on its own and ended
where it was hung up, and the stand-in's row in the call log not counted as a
call gone unrecorded. Then a phone call whose audio mode turns "communication"
in the middle (the stand-in call from `shellserver/probe` takes the mode and
plays a tone): recorded through the mixer from then, the tone in its copy, and
through the call capture again once the mode is back, saved exactly, and
Diagnostics' *Copy a report* saying each way (before Android 14, kept on the
call capture); and the stand-in app call placed over a phone call being
recorded, as a Meet call taken with the phone call on hold, keeping that phone
call on the call capture; and a messenger call - a stand-in app named as
Telegram's telegram.org build (`shellserver/probe/standin`, built by the script
with javac and d8), holding a call as a messenger does - whose recorder is
ended mid-call and brought back by the call's own watch, both parts saved as
one call and told once, and a call ended while its recorder is down ended
from the audio mode (1.5 s out), what the recorder left saved as a part
(`messenger_recorder_lost`, Android 14 and later). Then a call's waveform, kept as it is
saved; the list working out its waveforms with the app stopped 4 s mid-decode,
its decoders' reads and output held from then - on Android 12 and 13 until Android's codec
watchdog has taken a decoder away five times, 40 stops at most - the app living
through each and, opened afresh, working its first waveforms out again
(`decoder_pause`: with 1.9's decoder planted, the app died at the first firing
on the 12 AVD, 2026-11-26); three Quality choices made in Settings, each the rate the recorder
starts the next call at, the file's first audio page holding one packet and
no other page longer than a second, every checksum good and its last
position its length, the file's own rate within a fifth over the choice and
the three in its order, Highest well over the rest, and a recorder started afterwards taking the choice from its
environment; a recording played from its row going on with the
screen off, named only "Call recording" while locked and by its caller again
once unlocked, held back by a ringing call and playing on after it; a
recording playing switched by its setting - the key Settings' switch writes,
which a screen test taps - from both sides in both ears to each
in its own ear and back, opening again each time from where it was, and its
sound stalled 0.8 s by stopping the app, the playhead falling behind the
device's clock by the stall rather than running on ahead of the sound; a
recording raised to
the ear - the proximity sensor set through the emulator's console, the debug
build acting as if the emulator had an earpiece - moving to the earpiece as
voice-call audio with the screen's lock held and the audio mode left normal,
back on the loudspeaker and playing on when lowered, moved to the earpiece
while a call holds it back with JemRec off the screen and the phone raised,
and playing on there after the call, the player's earpiece
button moving it too, and with Earpiece in Settings a recording opening there
- and each move opening the recording again where the sound was, raised,
lowered, or paused, moved and played on: where it aimed, from its own log
line, within 50 ms of the session's place at that moment, and its place
across the move, against the device's clock, no more than 100 ms ahead - a
move done right reads 117 to 160 ms behind, for its gap, so a skip of about
a quarter second is the most this lets through (on the 1.2 branch, before
its release, a move dropped what Android's audio track held, 390 ms on the
Android 17 AVD); a seek's sound beginning where the seek was sent - 20 s of
noise encoded by Android's own Opus encoder and written by the app's Ogg
writer, played by an ExoPlayer built as the app's own with a tee taking what
its sink is given, sent as `OpusPreRoll` says: six seeks while it plays,
three while it is paused and then played, three files opened at a place as a
reopen opens them, each one's sound matched against a straight decode from
20 ms in, between 40 ms before its place and 30 ms after (a build without
`OpusPreRoll` measured 48 to 82 ms after on the Android 12 and 17 AVDs,
2026-11-20, Android's decoder dropping its 80 ms pre-roll from the place;
`seek_preroll`);
JemRec
as one window -
begun by its own way in, as from a notification, the same way in again and
the tile's long press leave one window, and a folder picker opened over it
stays when the launcher's intent comes, with the flags of an icon's tap, and
is finished by JemRec's own way in; a
deleted recording
brought back by Restore all in Recently deleted, its notification gone with it
as it went there, then deleted again and gone
for good by Delete all for good, from Android's own trash and the disk; the
same in a folder picked through Android's own picker, where it waits renamed
in its folder (Android 17 reports that file under its old name, issue #51),
and one there whose 30 days are up removed by the daily pass; one name saved
twice in each, the second under the name Android made, then trashed in both, a
row each on the real screen, Restore all and Delete all for good;
the app started again in Deck (dark), Multitrack and Memo, opening in each;
and a message, kept from its row, a kept message again after the app is
uninstalled and installed again - its marks read back from the file.
Then a USB cable on the emulator's simulated port: pulled out, the daemon
wakes the app and the app leaves a recorder that lived alone; plugged in with
the recorder killed during the watch, as unplugging an Honor from a computer
does, the app switches Wireless debugging off and on and starts it again.
Then USB debugging switched off by the shell with the app in the background,
as an Honor's own Settings app does whenever Developer options is shown: the
app is woken by the change, switches it back on and has the recorder
answering again. Then adbd stopped with Wireless debugging on, as an Honor
on MagicOS 11 leaves it after a restart (stopped here as root, the recorder
killed): the app's revive reads adbd stopped, switches Wireless debugging off
and on, and has the recorder back, running as shell. Then When JemRec is
off: set to Hide Developer options, off
takes Developer options and a Wireless debugging switched on by hand off and
the recorder keeps its process, and on puts them back - and with Wireless
debugging off, as JemRec leaves it, on does not switch it on; set to Turn all
debugging off, the recorder switches Wireless and USB debugging off and goes,
adbd with it, and on - sent by the debug build after a delay, since adb is
gone - puts it all back and starts the recorder, switching Wireless debugging
on for the start and the recorder off after it. Then an Off from the background, JemRec 20 s off the
screen: the app's process lives, where the call service asking for the
foreground, which Android refuses there, took it on all four AVDs
(2026-11-05). Then the header's *Tap to allow this Wi-Fi*: on a Wi-Fi
Android has not allowed (its allowance taken back first), with the recorder
down and port 5555 shut to the app, the header asks; the tap puts Android's
question up, and after Allow the tap's own revive has the recorder back, and
the recorder switches Wireless debugging off after it. Then Wireless
debugging only to start (1.5), with port 5555 shut to the app and the AVD's
Wi-Fi allowed - set up allows it for the whole run, as at each switch-on an
unallowed Wi-Fi asks again: the recorder killed with Wireless debugging off,
the revive switches it on, the recorder is back and switches it off within
15 s, and keeps its process 10 s after; on Android 12 and 13 after 15 s with
nobody browsing mDNS, past NsdService's stop of `mdnsd`. Then switched on
from the shell, as a user's for a computer: a revive uses it and leaves it
on. Then mDNS silent (1.9, #140): a look over Wireless debugging, then mDNS
dropped on the AVD (UDP 5353 going out, IPv4 and IPv6) and Wireless
debugging switched off and on, with port 5555 shut to the app: the connect
setup and Try again make lands on `adbd`'s new port, found by the loopback
probe, and a start - Wireless debugging switched on by the revive, another
new port - brings the recorder back. What phones met by chance, an old port
from Android's cache or no announcement at all, made certain: on the Android
16 AVD the cache gave the old port in one run and the live one in the next
(2026-11-26), and checks that waited for it passed on 1.8.1.
Then a USB mode changed (1.6): JemRec's process ended, `svc usb
setFunctions` to a function the last USB_STATE did not name - Android's own
kill, adbd and the recorder with it - and the recorder back within 40 s by
JemRec's run of the cable's watch, woken by the broadcast, and Wireless
debugging off again after: over Wireless debugging with port 5555 shut on
Android 16 and 17, over port 5555 on 12 and 13, where the emulator's adbd
leaves NsdService unable to find anything after its restart. Then a screen
lock with *File transfer* as the Default USB configuration (1.7, #131): a PIN
set, the default set with `svc usb setScreenUnlockedFunctions`, the lock seen
to end the recorder first; home's card, and its *Set to No data transfer*
setting none and the recorder brought back by the USB-state watch the tap
queues; then two locks and unlocks that keep it, a call while locked and one
locked 3 s in, each saved whole (`exact`); the PIN, the lock screen's switch
and the default put back after, each in an adb call of its own, as a USB
change can end the shell that runs it. Then Ask first's button over the call
(1.8, `ask_button`): its permission allowed by the recorder; over a call's
screen, dragged and kept where it was left, its *Record* recording the call; its
cross a *Not now*, after which *Record* on the call's own notification still
records (the debug op pressaction sends that button's own PendingIntent:
uiautomator does not see the shade's silent part); over a call answered on a
PIN-locked phone, and not while it rings or on the lock screen itself; the
question in the shade and no banner while it is up, unlocked and locked; its
permission taken back, the question a banner again, which the power key makes a
window over the call's screen, and a tap outside its dialog gives the call's
back, unanswered; and switched off, the permission taken back by the recorder.
Then setup's last step on a phone that refuses its grant, as an OPPO, OnePlus
or realme does until *Disable system optimization* is on (issues #61, #64;
the debug build answers with #64's refusal, as an emulator grants): told that
switch, and not set up; with the ADB session dropped, told so rather than
sent to a switch; Try again, without the fake, grants for real, read back
from the package's own record. Then notification access for the app's
listener, taken away, and granted again by the recorder with the app's ADB
session closed, as Settings' Show who called and Hide the debugging
notification ask for it (the debug op grantlistener), and in Android's list;
then again with the recorder killed first, the grant starting it and the new
recorder granting it.
Then Keep JemRec paired (the debug op keeppaired): Android's pairing timeout
turned off by the recorder, with its old value in the recorder's own file,
and put back from it; then a timeout off by hand, neither recorded nor put
back. That the off keeps a pairing was measured by hand on the Android 17
AVD (2026-11-15): with the timeout at two minutes JemRec's key left `dumpsys
adb` a second after JemRec had connected, and at 0 it stayed. The AVDs let
any key in, so there the loss shows only in that list.
Then a pairing code that is not six digits, twenty times (`pairing_card`): the
card ends on what was wrong each time, with the field for the next code, and
its service ends; the service started by root with no code, and no pairing
tried.
Then Telegram's other builds (issue #93): stand-ins named
`org.telegram.messenger.web` and `.beta` - apps with no code, built by the
script with the SDK's aapt2 and signed with the debug key - visible to JemRec
in Android's own list (`dumpsys package queries`) and, from Android 14,
listed in Messenger calls as *Telegram (telegram.org)* and *Telegram Beta*;
removed after. Then Settings' About: the installed version shown as the
package has it, and *Newest version* handing GitHub's newest release to the
browser (Android's record of the start). Then Wi-Fi coming back: with a kick of the keep-alive failed twice off Wi-Fi
and a minute or more from its next try, and no screen up, Wi-Fi's own run has
the recorder back within 45 s, before that try - 10 s on the Android 17 AVD,
where the build before had none, the kick's wait taking Wi-Fi's place
(2026-10-31).
Then Start without Wi-Fi: on Wi-Fi a revive has adbd
open its classic port on JemRec's own number (since 1.11; the property is what
is checked, and the number is set by the check, as nothing on the Mac can ask
the emulator once adbd has left 5555); with Wi-Fi off, Turn all debugging off
and back on has the recorder started over that port; switched off, the port is
closed again on Wi-Fi over Wireless debugging - or, where the revive came over
the port itself, left open, as it must be, and closed by the check for the
checks after. On a number other than 5555 adbd listens on that number alone,
and the Mac's adb loses the emulator, whose own transport is its 5555: the
check follows adbd through a port of the Mac's that the emulator's console
forwards to the number (`follow_port`; measured on the Android 16 AVD,
2026-11-27). While it follows, every adb command goes by whichever way
answers at that moment: once the switch is on, a revive may move adbd at any
time (one did just after the Allow tap on the Android 12 AVD, 2026-11-28).
And first, where the AVD's Wi-Fi has not
been allowed for Wireless debugging - on Android 12 always, the check taking
its allowance back first, as root with system_server stopped - that once the
question is cancelled no revive for the port asks again or opens it; then
the check allows that Wi-Fi for the rest. These checks, the USB-debugging one,
the adbd one and `debugging_off` take a snapshot through the emulator's console
first, and load it if adb never comes back - taken before any change of window,
as saving one stops the emulator for seconds (`usb_debugging` pressed Home and
then saved: JemRec's window took its focus event late, an "isn't responding" on
the Android 12 AVD, 2026-11-18).
After start_without_wifi, a port 5555 someone else opened (1.10): with the switch off,
the host's `adb tcpip 5555`; JemRec's revive leaves it open, the switch's line
says a computer or another app opened it - on the screen left open across
the change, which is reported, and reopened, which is required - and When
JemRec is off says Back on: anywhere; after `adb usb` the plain line is back.
Then whose port it is, told by its number (1.11): JemRec's own opened, then,
away from Wi-Fi with the switch off, the host's `adb usb` and `adb tcpip 5555`
back to back - back on Wi-Fi JemRec leaves 5555 and Settings names it as a
computer's or another app's (1.10 took it for its own and closed it); and a
port someone else opened on a number of its own, set before the switch goes
on - JemRec leaves it, Settings names it, and with Wi-Fi off the recorder
comes back over it. An emulator has no cable to have the recorder looked at
after adbd's restarts, as a phone's cable watch does, so these ask for
revives themselves. And every adb session shown to be the phone's shell before
use (1.11): with the debug build's stand-in on, a revive starts nothing and
its log says the session did not show itself as the phone's shell, and a
connect on the classic port is let go, one answering wrong at once and one
silent once the proof's 10 s are up; with it off, the next revive starts the
recorder. A run cut short inside one of these can leave the switch
on with JemRec's number set: the next cold boot opens that port before the
checks begin, and the emulator's own adb transport is gone. The number is
the AVD's own, the same at every run, so the run follows adbd to it and
set_up closes it once the switch is off; an AVD reached neither way is
booted with `--wipe`.
One cut short in a lock check (ask_button, usb_default_lock) leaves its PIN,
and the user locked after every cold boot: set_up clears it and fails, and
its retry boots unlocked.
And, run before those Wi-Fi checks (they come late - stopped_early, wifi_back,
start_without_wifi: they turn the emulator's Wi-Fi off and on, and only
port_opened_elsewhere, port_number_own, port_other_used, session_proof, battery_restricted,
audio_server_restart and
stall_told, which start the recorder themselves, run after them), the daemon's watch for the phone
stopping JemRec in the background:
that it runs and reads the device's own exit records; and on Android 15 and
later, that the app reads its own start record - a Force stop, then an open,
read as "started stopped" and, being the user's own stop, not warned of. The
phone's stop itself cannot be made on an emulator, which never kills apps the
way a vendor power manager does. And, from 1.2, what Android itself can do to
JemRec, made on the emulator rather than waited for: on battery and with
JemRec's uid idle, a call recorded and saved at once with the Battery set to
Restricted and in the restricted standby bucket, each with exact call tracking
on and off - but for Restricted with tracking off, where Android refuses the
recorder's wake, and the call is saved once JemRec opens, home and a
notification saying why; in Ask first there, no call asked about or recorded
and the header saying so; under Restricted the keep-alive's jobs held, as
JobScheduler says; and in the restricted bucket a kick bringing an ended
recorder back (`battery_restricted`); the audio server ended in the middle of a call and
the recording going on, its sound after the kill as before it - Android's own
AudioRecord restores itself (a gap of 1.1 to 1.5 s - 4.7 s once beside
another AVD under load, held under 6 s - the Android 12 and 17
AVDs, 2026-11-18; held down 2 and 5 s on the 12 AVD, and 20 s on the 12 and
17, it restored itself too, no read failing) (`audio_server_restart`); held
down mid-call, the call's notification saying the recording stopped within
seconds and that it goes on once the server does - the stall watch, no read
failing, the same recorder throughout saying the stop itself, and the saved
call not said to have stopped early (`stall_told`). A read that does fail - the
recorder's to make anew (`Restarting`, `CallPath`) - has not been produced on
an emulator: its decisions are held by the daemon's tests (`RestartLoopTest`,
`RestartPolicyTest`, `CallPathTest`), the captures' use of them only read in
review; notifications taken back after setup, leaving home and
the recordings there with a notice and, in Ask first, the header saying no call
can be recorded, and from Android 14 full-screen notifications taken away (an
app op) said in Ask first - and not while they are allowed - its Turn on on
their own page (`notifications_off`,
Android 13 and later); a picked folder
deleted, the next call saved in the standard folder with a notification and
home's notice saying so (`folder_lost`); and the recorder ended mid-call,
brought back while the call is up by the call's own watch, said in the call's
notification, both parts saved and neither said to have stopped early, the
call's one notification saying it is in two parts -
brought back during an incoming call, the new recorder joining it, both parts
saved incoming and none said to have stopped early - lost unnoticed just before a hang-up and a redial, two calls, the first's file a part, three times: redialled at once with exact call tracking on, a second after the call's mode went normal with it off, and after a recorder started between the calls - and, Wi-Fi off, not brought back, the call's end read from the audio mode at once, its notification gone after the call and
its recording collected and said to have stopped early, the call's notice in the early one's place and no other beside it (`stopped_early`); the
app's process killed (SIGKILL as root, as the low-memory killer sends) while it
saves a call - a second into the transfer, written but not published, and
published with the recorder's copy not yet deleted, the debug build holding
the save there (`SaveHold`), and the process ending itself straight after
the save journal's writes (before the first publish, and after the
recorder's copy went) - in the standard folder and a picked one, the call
saved whole and once at the next open, nothing left pending
(`killed_mid_save`); the
recorder's encoder ended mid-call by a full phone, with exact call tracking
off, the call's notification saying so during the call, its file named with
what it missed and the app saying it stopped early from that alone, in one
notification for the call, and a
message's recording ended the same way hung up on at once, what was kept
saved (`early_end`); the phone put in Doze by Android's own test
command, a call recorded and saved at once and an ended recorder brought back
by a kick (`doze`); and the
app's process ended in the background - `am kill`, which ends an app's
process only once it is safe to, in the background, as Android does for the
memory -
and JemRec back on the screen and the home tab it was left on
(`process_death`). And that
a call's recording is a valid Ogg Opus stream by RFC 7845 - each page's position
where its last packet ends, the last page marked - read from its bytes without
the app's code, and every recording this install owns so after the pass over
ones saved before 1.2 (`opus_valid`).
It turns the emulator's sounds down to about half before it starts - ringtone,
notifications, system, alarms and media, never the call's, which the checks
measure - and it
refuses to run on anything but an emulator, and needs a `google_apis` image.

A pre-push hook runs the tests and lint before anything leaves the machine:

    git config core.hooksPath tools/hooks

## Checks beyond the tests

CI runs the first four on each push to main and each pull request; each runs
locally too.
They exist because reviews kept finding bugs by chance that a tool finds every
time. CI also makes the site again (`python3 tools/build_site.py`) and fails
if `docs/` comes out other than committed, and the tests hold the F-Droid
listing to its lengths (`ListingLimitsTest`).

- **Lint is a gate.** `./gradlew lint`: every warning is an error, and there
  is no baseline - the 121 found when the gate went in were fixed (80 of them
  `UseKtx`, most by lint's own fixes, read through one by one) or are allowed
  with the reason beside them. `app/lint.xml` says what is off by design: the
  view model handed down to the screens, the checks that compare with the
  newest versions online or with Google Play's SDK index of the day (a known
  vulnerability still fails), trust-all managers inside two libraries, the
  app's own `CompositionLocal`s, each named, and the `@ReadOnlyComposable`
  hint, which would put that mark on functions whose cached lambdas need a
  group. A single place allowed says so where it stands (`tools:ignore`,
  `@SuppressLint`).
- **Compose rules.** Slack's [compose-lints](https://slackhq.github.io/compose-lints/)
  run inside lint (`lintChecks` in `app/build.gradle.kts`): modifiers passed
  on and placed right, parameter order, new `CompositionLocal`s.
- **Error Prone on the daemon.** `./gradlew :daemon-tests:errorProne` compiles
  all of `shellserver/src` with [Error Prone](https://errorprone.info) into
  `shellserver/tests/build/errorprone` and throws the classes away. Warnings
  fail too: the checks off by design (style the daemon does not use) are
  listed in `shellserver/tests/build.gradle.kts`, and each other finding was
  fixed or suppressed where the code says why. It does not know Android's API
  levels - a replacement it suggests can be newer than Android 12, which the
  daemon runs on (`Scanner(InputStream, Charset)` is Android 14) - and the
  next check is what catches that. The jar in the APK is still built by
  `shellserver/build.sh` alone.
- **NullAway, in the same run.** What may be null in the daemon's package is
  marked `@Nullable` - the daemon's own (`shellserver/src/.../Nullable.java`),
  source only, so the jar gains that one type and nothing else - and
  [NullAway](https://github.com/uber/NullAway) fails the run on a marked value
  used before it is checked, and on null given where nothing is marked. Two
  planted dereferences both failed it (2026-10-25). What it cannot see: what
  scrcpy's files and Android's methods return is taken as never null -
  `android.jar` carries none of the platform's nullness marks (the SDK keeps
  them apart, in `annotations.zip`, for lint and the IDE) - and it trusts a
  field from its check to its use, so another thread clearing it in between
  goes unseen. The daemon runs several (the call watch, the cable watch, the
  kill watch, the acceptor): a nullable field another thread writes is read
  into a local first, or under the lock its writers hold. The four fields
  read twice in a row were each found safe (2026-10-26): `activeRecording` is
  written only under the call lock, which its reader holds (`Main.class` then,
  `Calls.LOCK` since 0.31); `Voip.policy` only under `Voip.class` (since 0.31
  `PolicyUse` holds it, read and written under its own lock); a capture's
  pumps are set once and never cleared.
- **Android API levels in the daemon.** The daemon runs from Android 12 but
  compiles against 16's `android.jar`, so a call from a newer Android builds
  fine and then crashes an older phone. `shellserver/lint` is an Android module
  that exists only so lint's `NewApi` and `InlinedApi` checks read
  `shellserver/src` at the daemon's floor (minSdk 31); nothing is built from
  it. It runs inside `./gradlew lint`, or alone with
  `./gradlew :daemon-lint:lint`. Proven on two planted calls (Android 13's
  `ByteArrayOutputStream.toString(Charset)`, 14's `Scanner(InputStream,
  Charset)`); the daemon itself had none.
- **R8's constructors.** `tools/check_r8_constructors.py`, on the release APK
  after every CI build: each class Android or a library makes by name - the
  manifest's components, androidx.startup initializers, ServiceLoader
  providers, workers, input mergers, Room databases - must be in the APK with
  the constructor it is made with. A keep rule that keeps a class but not its
  constructor fails only when the class is made, and silently in #69. It
  fails 0.25.2's code and the published 0.8 APK on WorkManager's two input
  mergers, and passes 0.25.3 (2026-10-31). One exception is listed with its
  reason: coroutines' `AndroidDispatcherFactory`, which R8 makes with a direct
  `new`.
- **CodeQL.** `.github/workflows/codeql.yml`: GitHub's code scanning with the
  security-and-quality queries, on pushes to `main`, pull requests and weekly.
  Findings go to the Security tab.
- **The app's pure code, alone.** `./gradlew :pure-tests:test`, part of
  `./gradlew test` and so of CI, compiles the app's files listed in
  `app/pure-tests/build.gradle.kts` - those that use nothing of Android -
  with no `android.jar`, and runs their tests there. A listed file that
  reaches Android, or an app class that does, fails to compile: in the app's
  own tests `android.jar`'s stubs return defaults, so there it would pass.

Reports, not gates - run them when wanted:

- **App coverage:** `./gradlew createDebugUnitTestCoverageReport -Pcoverage`,
  then `app/build/reports/coverage/test/debug/index.html`. 67% of lines on
  2026-11-01: 80% of `ui`, 99% of the themes, 58% of `capture` (55%, 73%
  and 36% at 0.21).
- **Daemon coverage:** `./gradlew :daemon-tests:test -Pcoverage`, then
  `shellserver/tests/build/reports/jacoco/test/html`. 97% of instructions,
  89% of branches (2026-11-14).
- **Mutation testing:** `./gradlew :daemon-tests:pitest`, then
  `shellserver/tests/build/reports/pitest`. [PIT](https://pitest.org) changes
  the daemon's decision classes one small way at a time and runs the tests on
  each. 978 of the 1045 changes its tests reach fail one (94%, 2026-11-14):
  all 3 in `Closing`, which 1.0 added, all 177 in the classes 0.31 added,
  798 of 865 in the five before. At 470
  of 524 (2026-10-26) each change left was read: 41 change only what is
  logged, and 13 change nothing a call can meet or a listener hear: a bound
  the chime's loop never reaches, an equality that cannot occur, a go-ahead
  counted from 0 (which needs a ring in the first three seconds after boot),
  the whole chime's sign or its octave's phase. Some tests read records the
  Honor itself printed (`shellserver/tests/fixtures`,
  `app/src/test/resources/honor`).
- **The app's mutation testing:** `./gradlew :pure-tests:pitest`, then
  `app/pure-tests/build/reports/pitest`: PIT over the pure module's files
  (the gate above). 272 of the 277 changes its tests reach fail one
  (2026-11-14; the one in `Formats`, which 1.0 added, among them; 271 of 276
  on 2026-11-06; 254 of 259 before 0.29 added the trashed names, all 17 of whose
  changes fail one; 218 of 223 before 0.28 added the lifecycle's tables, all 36
  of whose changes fail one; 119 of 123 before 0.27 added the saver's and
  the call service's decisions). The five left change nothing: frame lengths at Opus
  config 12, which SILK and hybrid give alike; two `setTimeZone` calls on
  formatters built in that zone already; and two shortcuts inside the
  standard library, inlined: a bound in `trim` (`AudioStream`) and `none`'s
  "is it a collection" (`StandDown`), which a list always is. 17 more are not
  reached, nearly all in `SystemProperty`, which runs `getprop`. The Kotlin
  compiler's own null checks, `hashCode`, and data classes' `copy` and
  `componentN` are left out (`--avoidCallsTo`, `--excludedMethods`): they
  were 9 of the first run's 26 survivors - six null checks removed, three
  hashes changed that still agreed with `equals` - and no test could tell
  any of them.

None of these checks changes what ships but NullAway, whose `Nullable` - a
type the daemon's sources now use - is one more class in the daemon jar.
Built with the rest in place, the release APK and the daemon jar were
byte-for-byte those of `main` (2026-10-25).

## Releases

To release, bump the version in a pull request and merge it. Nobody types a
tag:

    # gradle.properties: jemrecVersionName=0.9, jemrecVersionCode=900
    # fastlane/metadata/android/en-US/changelogs/900.txt: what changed
    # ...merged to main as a PR, like everything else; main takes no direct push

The version lives in `gradle.properties` and nowhere else, because that is
where F-Droid's update checker reads it at each tag. When a merge to main
changes that file to a version not yet released, the release workflow:

- refuses a `versionCode` that is not `MAJOR*10000 + MINOR*100 + PATCH`;
- refuses a release with no changelog;
- waits, up to an hour, for the build workflow's run on that same commit, and
  refuses unless it passed - every test, Error Prone and lint (`checked`);
  main's runs are never cancelled by a later merge, so that run is there to
  wait for;
- builds a release APK signed with the project key held in repository secrets;
- refuses to publish anything `apksigner` cannot verify, or signed by any
  other key than the one whose SHA-256 the README and the site publish;
- tags that same merge commit and attaches `jemrec-0.9.apk` to the release.

F-Droid takes it from there without being asked: its update checker reads
`gradle.properties` at each new tag, adds a build entry, and publishes once its
own build of that commit matches the APK attached here. Which means a release's
APK has to stay on the releases page until F-Droid has built that version —
delete it sooner and the check has nothing to compare against.

The tag has to point at a commit **on main**, because that commit is what
F-Droid builds and what the APK's own version-control stamp names. Tags used to
be pushed by hand, and that went wrong twice:

- One went up with a push of main that was refused, so it pointed at a commit
  nothing else could see.
- One was pushed before the bump's pull request had merged, so it pointed at
  the previous version.

A tag made from main's own merge can be neither.

A tag pushed by hand still builds, for a re-run or an emergency. It is refused
unless it is on main and `gradle.properties` at its commit says the same
version. A tag pushed too early, whose commit says an older version, does not
block the release: the merge that brings that version moves the tag onto
itself and says so in a warning.

The signing key is the thing that makes an upgrade possible: an APK signed with
a different certificate cannot install over an earlier one, whatever its version
number. It lives outside this repository and always has — see `.gitignore`,
which has refused signing material since before any existed.

**The build is reproducible**, and that is what lets F-Droid carry the same
key: F-Droid builds each tag on its own machine, checks the result against the
APK on the release page byte for byte, and publishes the release APK, signed
here, when they match. Three things used to differ, each found by building a
tag on a second machine and diffing against the release: the daemon jar's
`zip` step stamped in the build's wall-clock time (`d8` now writes the jar,
with the epoch); AGP stripped the native libraries only where an NDK happened
to be installed (they are now packaged as shipped); and `javac` 17 and 21 do
not agree on the daemon's bytecode (the major is now pinned and checked).
