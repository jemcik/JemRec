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
[app/proguard-rules.pro](../app/proguard-rules.pro) says why, and lists the two
things R8 must not remove and the two references it must not warn about,
which is the whole file.

The JDK major is exact because the daemon's bytes depend on it: measured, JDK 17
and JDK 21 turn the same sources into dex files 264 bytes apart, and an APK that
differs by machine is one F-Droid cannot reproduce. 21 is what F-Droid's build
server runs, so 21 is what CI installs and what `build.sh` insists on -
`JEMREC_JAVA_HOME` points it at one if the default is something else.

## Tests

792 JVM tests, no device needed - 627 for the app and 165 for the daemon:

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
emulator, for each Android version it claims (12, 13 and 17 for 0.21.1):

    python3 tools/emulator_checks.py --serial emulator-5554

It installs the debug build, sets JemRec up from nothing and places simulated
calls: the ringing cut, the same cut with nothing captured until the answer
(as over Wi-Fi Calling on a Pixel, issue #40: the recorder is stopped while
the call rings), an unanswered call not kept, an incoming call kept to
the second, a stranger skipped before recording, two calls in quick
succession, a caller-ID name, the self-test, and the answering machine: a
message taken and marked with the microphone given back, a caller who hangs
up during the greeting, a caller the rules leave out left to ring, a phone
call ringing over a messenger call left to ring (a stand-in call from
`shellserver/probe`, built by the script if need be), a caller left to ring
in the day after an emergency call (the pause set as the call tracker sets it;
no emergency number is dialled), and, in Ask first, a call taken over: the
message kept and the rest asked about. Then a call's waveform, kept as it is
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
Last, the daemon's watch for the phone stopping JemRec in the background:
that it runs and reads the device's own exit records; and on Android 15 and
later, that the app reads its own start record - a Force stop, then an open,
read as "started stopped" and, being the user's own stop, not warned of. The
phone's stop itself cannot be made on an emulator, which never kills apps the
way a vendor power manager does.
It refuses to run on anything but an emulator, and needs a `google_apis`
image.

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
- **CodeQL.** `.github/workflows/codeql.yml`: GitHub's code scanning with the
  security-and-quality queries, on pushes to `main`, pull requests and weekly.
  Findings go to the Security tab.

Reports, not gates - run them when wanted:

- **App coverage:** `./gradlew createDebugUnitTestCoverageReport -Pcoverage`,
  then `app/build/reports/coverage/test/debug/index.html`. 55% of lines at
  0.21: 73% of `ui`, 99% of the themes, 36% of `capture`.
- **Daemon coverage:** `./gradlew :daemon-tests:test -Pcoverage`, then
  `shellserver/tests/build/reports/jacoco/test/html`. 98% of instructions,
  90% of branches.
- **Mutation testing:** `./gradlew :daemon-tests:pitest`, then
  `shellserver/tests/build/reports/pitest`. [PIT](https://pitest.org) changes
  the daemon's decision classes one small way at a time and runs the tests on
  each. 427 of its 478 changes now fail a test (89%, up from 79% when it
  went in). Of the 51 left, 38 change only what is logged, and 13 change
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
