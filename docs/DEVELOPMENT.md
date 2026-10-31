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

The daemon is not a Gradle module. `shellserver/build.sh` compiles it against the
platform the *device* runs, with its `@hide` internals intact, and Gradle runs
that script as part of every build and packages the jar as an app asset.

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
never checks an APK older than the sources; CI builds it on every push.
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

## Tests

914 JVM tests, no device needed - 718 for the app and 196 for the daemon:

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

Before a release, the same calls go through the real app and daemon on an
emulator, for each Android version it claims (12, 13, 16 and 17 for 0.25.3 -
16 is what the Galaxy phones of the issues run), each booted cold
(`emulator -avd <name> -no-snapshot`):

    python3 tools/emulator_checks.py --serial emulator-5554

Cold, not `-no-snapshot-save` alone, which still loads the AVD's quick-boot
snapshot at every start and throws away whatever happened since it was taken:
the 17 AVD showed JemRec 0.9 for eight days that way, until 2026-10-28 (every
run installed the current build first, so the results were for it).

It installs the shrunk build (see above), sets JemRec up from nothing and
places simulated calls: the ringing cut, the same cut with nothing captured
until the answer (as over Wi-Fi Calling on a Pixel, issue #40: the recorder
is stopped while the call rings), an unanswered call not kept, an incoming
call kept to the second, a stranger skipped before recording, two calls in
quick succession, a caller-ID name, the self-test, and the answering machine: a
message taken and marked with the microphone given back, a caller who hangs
up during the greeting, a caller the rules leave out left to ring, a phone
call ringing over a messenger call left to ring (a stand-in call from
`shellserver/probe`, built by the script if need be), a caller left to ring
in the day after an emergency call (the pause set as the call tracker sets it;
no emergency number is dialled), and, in Ask first, a call taken over: the
message kept and the rest asked about. Then another app's call through
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
call on the call capture. Then a call's waveform, kept as it is
saved; a recording played from its row going on with the screen off, named
only "Call recording" while locked and by its caller again once unlocked,
held back by a ringing call and playing on after it; a deleted recording
brought back by Restore all in Recently deleted, then deleted again and gone
for good by Delete all for good, from Android's own trash and the disk; the
same in a folder picked through Android's own picker, where it waits renamed
in its folder (Android 17 reports that file under its old name, issue #51),
and one there whose 30 days are up removed by the daily pass;
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
takes Developer options and Wireless debugging off and the recorder keeps its
process, and on puts them back; set to Turn all debugging off, the recorder
switches Wireless and USB debugging off and goes, adbd with it, and on - sent
by the debug build after a delay, since adb is gone - puts it all back and
starts the recorder. Then the header's *Tap to allow this Wi-Fi*: on a Wi-Fi
Android has not allowed (its allowance taken back first), with the recorder
down and port 5555 shut to the app, the header asks; the tap puts Android's
question up, and after Allow the tap's own revive has the recorder back.
Then setup's last step on a phone that refuses its grant, as an OPPO, OnePlus
or realme does until *Disable system optimization* is on (issues #61, #64;
the debug build answers with #64's refusal, as an emulator grants): told that
switch, and not set up; with the ADB session dropped, told so rather than
sent to a switch; Try again, without the fake, grants for real, read back
from the package's own record.
Then Wi-Fi coming back: with a kick of the keep-alive failed twice off Wi-Fi
and a minute or more from its next try, and no screen up, Wi-Fi's own run has
the recorder back within 45 s, before that try - 10 s on the Android 17 AVD,
where the build before had none, the kick's wait taking Wi-Fi's place
(2026-10-31).
Then Start without Wi-Fi: on Wi-Fi a revive has adbd
open its classic port, 5555 (the property is what is checked: an emulator's
adbd listens there anyway); with Wi-Fi off, Turn all debugging off and back
on has the recorder started over that port; switched off, the port is closed
again on Wi-Fi over Wireless debugging - or, where the revive came over 5555
itself, left open, as it must be. And first, where the AVD's Wi-Fi has not
been allowed for Wireless debugging - on Android 12 always, the check taking
its allowance back first, as root with system_server stopped - that once the
question is cancelled no revive for the port asks again or opens it; then
the check allows that Wi-Fi for the rest. These checks, the USB-debugging one
and the adbd one take a snapshot through the emulator's console first, and
load it if adb never comes back.
And, run before those Wi-Fi checks (they go last: they turn the emulator's
Wi-Fi off and on), the daemon's watch for the phone
stopping JemRec in the background:
that it runs and reads the device's own exit records; and on Android 15 and
later, that the app reads its own start record - a Force stop, then an open,
read as "started stopped" and, being the user's own stop, not warned of. The
phone's stop itself cannot be made on an emulator, which never kills apps the
way a vendor power manager does.
It turns the emulator's sounds down to about half before it starts - ringtone,
notifications, system, alarms and media, never the call's, which the checks
measure - and it
refuses to run on anything but an emulator, and needs a `google_apis` image.

A pre-push hook runs the tests and lint before anything leaves the machine:

    git config core.hooksPath tools/hooks

## Checks beyond the tests

CI runs the first four on every push and pull request; each runs locally too.
They exist because reviews kept finding bugs by chance that a tool finds every
time.

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
  written only under `Main.class`, which its reader holds; `Voip.policy` only
  under `Voip.class`; a capture's pumps are set once and never cleared.
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

Reports, not gates - run them when wanted:

- **App coverage:** `./gradlew createDebugUnitTestCoverageReport -Pcoverage`,
  then `app/build/reports/coverage/test/debug/index.html`. 55% of lines at
  0.21: 73% of `ui`, 99% of the themes, 36% of `capture`.
- **Daemon coverage:** `./gradlew :daemon-tests:test -Pcoverage`, then
  `shellserver/tests/build/reports/jacoco/test/html`. 99% of instructions,
  93% of branches (2026-10-26).
- **Mutation testing:** `./gradlew :daemon-tests:pitest`, then
  `shellserver/tests/build/reports/pitest`. [PIT](https://pitest.org) changes
  the daemon's decision classes one small way at a time and runs the tests on
  each. 470 of its 524 changes now fail a test (90%, up from 79% when it
  went in). Of the 54 left, 41 change only what is logged, and 13 change
  nothing a call can meet or a listener hear: a bound the chime's loop never
  reaches, an equality that cannot occur, a go-ahead counted from 0 (which
  needs a ring in the first three seconds after boot), the whole chime's
  sign or its octave's phase - each one read in the report (2026-10-26). Some tests read records the Honor itself
  printed (`shellserver/tests/fixtures`, `app/src/test/resources/honor`).

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
- builds a release APK signed with the project key held in repository secrets;
- refuses to publish anything `apksigner` cannot verify;
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
