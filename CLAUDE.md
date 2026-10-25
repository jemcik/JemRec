# JemRec - notes for Claude

A call recorder for unrooted Android: the app pairs with the phone's own
Wireless debugging and starts a recorder that runs as the shell user
(`shellserver/`, an app_process jar). How and why, with the measurements:
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Building, tests, releases:
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). Read those rather than guess.

## Build and test

- JDK 21 exactly - the daemon's bytes depend on it. Point `JEMREC_JAVA_HOME` at
  one if the default java is another.
- `./gradlew assembleDebug`; `./gradlew test` runs the app's and the daemon's
  tests; `./gradlew :daemon-tests:test` the daemon's alone. Gradle builds the
  daemon jar itself (`shellserver/build.sh`).
- The daemon's decisions live in plain Java (`AnswerMachine`, `CallStates`,
  `Checks`, ...) so they can be tested off the phone: keep Android out of them.
- Screen tests compare against `app/src/test/screenshots`. After a meant change:
  `./gradlew testDebugUnitTest --tests '*.ui.screens.*' -PrecordScreens=true`,
  then look at every PNG that changed.
- Before a release: `python3 tools/emulator_checks.py --serial emulator-5554` on
  the Android 12, 13 and 17 AVDs (`jemrec-api31-0.14`, `jemrec-api33`,
  `jemrec-api37.2`; boot with `-no-snapshot-save`). Ask before creating or
  deleting an AVD.
- Pre-push hook (tests and lint): `git config core.hooksPath tools/hooks`.
- `./gradlew lint` is a gate: any warning fails it, and there is no baseline;
  `app/lint.xml` says what is off and why. It also checks the daemon's calls
  against Android 12 (`:daemon-lint`). `./gradlew :daemon-tests:errorProne`
  is Error Prone and NullAway over the daemon, analysis only: what may be
  null there is marked with the daemon's own `@Nullable`. Reports:
  [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md#checks-beyond-the-tests).

## Generated files - edit the source, then regenerate

- `docs/*.html`, `docs/SETUP.md`, `docs/jemrec.css`, `docs/jemrec.js`: from
  `tools/build_site.py`.
- `docs/screenshots/` and the F-Droid screenshots: `tools/shoot.py`, on the
  phone, with the debug build's invented people.

## When a feature lands - every place that describes it

They must all say the same thing; a feature is not done until they do.

- `README.md`: the "What you can do" table, the FAQ, the phones table, the
  privacy and permission lines, the tests badge and the developer section's
  test count.
- `tools/build_site.py`, then run it: the home page's tiles, FAQ and "Will it
  work on your phone?", `HOW_BODY`, the manual (`SETUP` blocks: its own
  section, Settings, the Calls tab's statuses, the notifications table) and
  the privacy policy for any new data kept or network asked.
- `docs/ARCHITECTURE.md`: a section of its own, the contents line, the
  permission table if one changes.
- `docs/DEVELOPMENT.md` and `tools/README.md`: test counts, emulator checks.
- `fastlane/metadata/android/en-US/`: `full_description.txt`,
  `short_description.txt` if the one-liner changes, and the changelog for the
  new versionCode (with the bump in `gradle.properties`).
- Screenshots: screen tests for the new screens; the demo data
  (`app/src/debug/.../DemoRecordings.kt`) so the feature shows; `SCREENS` in
  `build_site.py` and `LISTING_ORDER` in `tools/shoot.py` for a new screen;
  then `tools/shoot.py`.
- An emulator check in `tools/emulator_checks.py` for what can be simulated.

## Limits and rules

- `fastlane/.../full_description.txt` at most 4000 characters (it sits close);
  `changelogs/<versionCode>.txt` at most 500.
- The version lives only in `gradle.properties`; versionCode is
  MAJOR*10000 + MINOR*100 + PATCH. Merging a version bump to main IS the
  release - no tags by hand.
- Never commit captured call audio or recordings of real calls.
- Commit, push or open a PR only when asked.

## The test phone (Honor Magic 8 Pro, MagicOS 11, Android 17)

- Third-party apps' logcat lines are encrypted. The daemon logs to
  `/data/local/tmp/jemrec-capture.out`; read the app's files with `run-as`.
- Broadcasts to the debug `SpikeReceiver` are dropped unless JemRec's App launch
  is set to manage manually.
- Stop the recorder before `adb uninstall`, or it turns debugging off when the
  app disappears - and the USB link goes with it.
- `pkill -f` matches its own command line: bracket a letter of the pattern, and
  keep the name out of the rest of that shell line.
- Real calls need the user. Say what was measured and what was inferred.

## How the work is done here

- Comments and docs explain why, with the measurement behind a claim (device,
  date); plain words.
- Replacing a code path: list what the old one did, and show each still holds.
- Check Material 3 and Android's docs before calling a UI pattern standard.
- No runnable commands for steps that only hold after a merge.
- One line of status before emulator or device work.
