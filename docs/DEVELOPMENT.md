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

419 JVM tests, no device needed - 317 for the app and 102 for the daemon:

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
emulator, for each Android version it claims (12, 13 and 17 for 0.17):

    python3 tools/emulator_checks.py --serial emulator-5554

It installs the debug build, sets JemRec up from nothing and places simulated
calls: the ringing cut, an unanswered call not kept, an incoming call kept to
the second, a stranger skipped before recording, two calls in quick
succession, a caller-ID name, the self-test, and the answering machine: a
message taken and marked with the microphone given back, a caller who hangs
up during the greeting, a caller the rules leave out left to ring, a phone
call ringing over a messenger call left to ring (a stand-in call from
`shellserver/probe`, built by the script if need be), a caller left to ring
in the day after an emergency call (the pause set as the call tracker sets it;
no emergency number is dialled), and, in Ask first, a call taken over: the
message kept and the rest asked about. It refuses to run on anything but an
emulator, and needs a `google_apis` image.

A pre-push hook runs the tests and lint before anything leaves the machine:

    git config core.hooksPath tools/hooks

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
