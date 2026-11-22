# JemRec - notes for Claude

A call recorder for unrooted Android: the app pairs with the phone's own
Wireless debugging and starts a recorder that runs as the shell user
(`shellserver/`, an app_process jar). How and why, with the measurements:
[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Building, tests, releases:
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md). Read those rather than guess.

## Build and test

- JDK 21 exactly - the daemon's bytes depend on it. Point `JEMREC_JAVA_HOME` at
  one if the default java is another.
- The daemon's decisions live in plain Java (`AnswerMachine`, `CallStates`,
  `Checks`, ...) so they can be tested off the phone: keep Android out of them.
- Screen tests compare against `app/src/test/screenshots`. After a meant change:
  `./gradlew testDebugUnitTest --tests '*.ui.screens.*' -PrecordScreens=true`,
  then look at every PNG that changed.
- Before a release: `python3 tools/emulator_checks.py --avds
  jemrec-api31-0.14,jemrec-api33,jemrec-api36,jemrec-api37.2` - the Android
  12, 13, 16 and 17 AVDs, each booted cold by the script, two at a time (more
  at once makes calls flaky), and shut down after. It builds and installs the
  shrunk build: debug through R8, since debug is not shrunk and R8 broke the
  release unseen in #69. Ask before creating or deleting an AVD.
  Never `-no-snapshot-save` alone: it loads the quick-boot snapshot at every
  start, which kept the 17 AVD on JemRec 0.9 for eight days (2026-10-28).
- `./gradlew lint` is a gate: any warning fails it, and there is no baseline;
  `app/lint.xml` says what is off and why. It also checks the daemon's calls
  against Android 12 (`:daemon-lint`). `./gradlew :daemon-tests:errorProne`
  is Error Prone and NullAway over the daemon, analysis only: what may be
  null there is marked with the daemon's own `@Nullable`. CI also runs
  `tools/check_r8_constructors.py` on the release APK: a class made by name
  that R8 left without its constructor fails the build (#69). Reports:
  [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md#checks-beyond-the-tests).
- Order before a release: code review rounds, then the emulator checks on the
  shrunk build of that final tree, then the phone. A code change after the
  checks means running them again.
- A pass says only what its check read. After a run, read the logs too:
  `FATAL`, and the app's and recorder's own W and E lines. A FATAL sat behind
  34 passes of 34 on 2026-11-05.
- Name the build every "verified" ran on: debug, shrunk or release. Anything
  on reflection, keep rules or library internals is checked on the shrunk
  build. In #69 every check and the phone ran debug from 0.5 to 0.25.2.
- Never rerun a check on the emulator it failed on, and never repair one by
  hand (toggling Wi-Fi, resetting settings): boot it cold again. On
  2026-10-27 an AVD that had been up for hours lost its Wi-Fi, and only a cold
  boot brought it back.
- When a run's results are read, shut it all down: each AVD (`adb -s
  emulator-<port> emu kill`, then `pgrep -f qemu-system` comes back empty),
  the logcat streams, the preview servers. They load the Mac.
- To prove a test catches a break, plant it with a copy: `cp file keep`, edit,
  run the test, `cp keep file`, `cmp keep file`. Never `git checkout --` a file
  that may hold uncommitted work.
- The shell is zsh, so an unquoted `$var` is not split into words: write
  commands out, or use arrays. BSD tools differ from GNU; prefer Python for
  edits. Every wait has a cap, and after about 15 s check that what it waits
  for exists at all.

## Generated files - edit the source, then regenerate

- `docs/*.html`, `docs/SETUP.md`, `docs/jemrec.css`, `docs/jemrec.js`, `docs/fonts/`: from
  `tools/build_site.py`.
- `docs/screenshots/` and the F-Droid screenshots: `tools/shoot.py`, on the
  phone, with the debug build's invented people.

## When a feature lands - every place that describes it

They must all say the same thing; a feature is not done until they do.

- `README.md`: the "What you can do" table, the FAQ, the phones table, the
  privacy and permission lines, the tests badge and the developer section's
  test count.
- `tools/build_site.py`, then run it: the home page's feature rows (`FEATURES`,
  `FEATURE_ROWS`, with a picture for a new row), FAQ and "Will it
  work on your phone?", `HOW_BODY`, the manual (`SETUP` blocks: its own
  section, Settings, the Calls tab's statuses, the notifications table) and
  the privacy policy (`PRIVACY_DATA`: its words, and the chips that sum them
  up) for any new data kept or network asked.
- `docs/ARCHITECTURE.md`: a section of its own, the contents line, the
  permission table if one changes.
- `docs/DEVELOPMENT.md` and `tools/README.md`: test counts, emulator checks.
- `fastlane/metadata/android/en-US/`: `full_description.txt`,
  `short_description.txt` if the one-liner changes, and the changelog for the
  new versionCode (with the bump in `gradle.properties`).
- Screenshots: screen tests for the new screens; the demo data
  (`app/src/debug/.../DemoRecordings.kt`) so the feature shows; `SCREENS` in
  `build_site.py` and `LISTING_ORDER` in `tools/shoot.py` for a new screen
  (F-Droid keeps every file name it is given: never drop or rename one, a new
  shot gets a name that sorts last); then `tools/shoot.py`.
- An emulator check in `tools/emulator_checks.py` for what can be simulated.

## Limits and rules

- `fastlane/.../full_description.txt` at most 4000 characters (it sits close);
  `changelogs/<versionCode>.txt` at most 500.
- Merging a version bump to main IS the release - no tags by hand.
- Never commit captured call audio or recordings of real calls.
- Commit, push or open a PR only when asked.
- One commit per PR. Checkpoint commits on the branch are squashed into one
  before the PR opens. A fix made after it opens (a review, the emulator
  checks, the phone) is folded into that commit (`git commit --amend`, `git
  push --force-with-lease`), not added as a new one. GitHub's squash puts
  every commit's message into the commit on main: 0.27 to 1.0 put 350 to 1100
  lines each there.
- Commit messages and PR descriptions are as brief as they can be. The
  commit: "<version>: <what the user gets>", then a line per user-visible
  change, five at most. The PR: the changes in a few bullets the user would
  recognise, and one line on how it was tested. No class or file names, test
  counts, measurements or review history: those belong in ARCHITECTURE,
  DEVELOPMENT and the code's comments.
- Never post to users: no issue or PR comments, no Discussions posts or
  replies, no closing someone's PR. Write the draft, ready to paste, and say
  where it goes; the user posts it. Never offer to.
- One branch per release: what comes up while working on it stays on that
  branch, uncommitted until the user asks. Its limit is under "How the work is
  done here".
- After a merge, run `git fetch --prune`. A local branch whose upstream is
  gone is deleted once `git diff --stat <branch> <its squash commit>` is
  empty, with its tip's hash said. A branch with work not on main is a
  question, not a deletion.
- Start servers and scripts yourself rather than handing the user a command.
  The site goes through `preview_start` with a "site" entry in
  `.claude/launch.json` (git ignores `.claude/`, so a new clone makes it):
  `python3 -I -m http.server 8765 --bind 127.0.0.1 --directory docs`, port
  8765. A server the user is looking at stays up until they are done.
- Steps the user must follow, and drafts to post, go in the closing message
  after `wrap_up`: text written before it can reach the user cut to a summary.
- Anything commits or a PR need lives in `tmp/<task>/`, not the scratchpad: a
  Mac restart empties the scratchpad.

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
- It stays on the debug build of the current code, also after a release: the
  device tests need the diag receiver and the demo recordings. Never offer the
  release APK there. After a probe on another build, put the debug build back
  in the same turn.
- Its Quick Settings tile shows only "JemRec", never the subtitle. Test steps
  say lit or dim, or read the app's header.
- Before a merge, run a set of tests then and there, each covering a path the
  branch changed. Never "a few days on the build": days often bring no calls.
  Give one step per message, ending with "tell me done". Nothing runs on the
  phone until then, and any baseline is taken before handing it over.
- The user does what needs a person: calls, listening, earbuds, unlocking.
  Screens (open, scroll, tap, read) are driven over adb, with one line saying
  so before the phone is touched while the user holds it.

## How the work is done here

- Comments and docs explain why, with the measurement behind a claim (device,
  date); plain words.
- Replacing a code path: list what the old one did, and show each still holds,
  best through one shared, tested function. A difference between the two is a
  finding to raise, never "minor" (#30).
- Check Material 3 and Android's docs before calling a UI pattern standard,
  and cite the page. m3.material.io renders only in a real browser.
- No runnable commands for steps that only hold after a merge.
- One line of status before emulator or device work.
- A finding about one writer, caller or path: find every other one of its kind
  and fix them all in the same change. In 0.25 one flag had four writers,
  fixed over three rounds.
- Make the better fix now, not "for later": upgrade the library whose newer
  version fixes the bug, and close the gap that let it through (#69).
- A real finding gets fixed even when the fix is outside the diff. Skip one
  only if it is wrong, or if the fix would change meant behaviour, and say
  which behaviour.
- Fixing a list of findings: make the list once, fix each in place, run the
  tests after each batch, and report progress against the list. No
  refactors; a deeper fix is a decision for the user.
- Before docs or other prose go to review, list every factual claim in them
  and check each against its source (the code, ARCHITECTURE, the live page).
  Check each fix's new words the same way.
- What a feature does is what README and `tools/build_site.py` on main say,
  and the newer measurement wins. "Tested on X" is not "only works on X".
- Anything shown in two places is drawn by one composable, and the two are
  looked at side by side, in every look.
- No decorative UI: every meter, LED or mark shows real data, or is not drawn.
  One mark per setting, never the same fact twice.
- Memo's tap lines and its 40 dp player buttons sit under 48 dp on purpose:
  the user's tradeoff (2026-11-18), not a finding.

From the practice audit (2026-11-17), whose findings had all
passed tests and reviews:

- Spec, not lenient players: a file format or protocol gets tests of the
  spec's own rules (RFC 7845 for the recordings), and a change to it is
  measured on Android's own decoder before it ships.
- Platform failure modes, produced: before building, list what Android can do
  to the feature (a permission taken back after setup, a folder's access lost,
  a process killed mid-transfer, Battery Restricted, the restricted standby
  bucket, Doze) and make each happen on the emulators. Fakes in unit tests
  fail the way the platform does.
- Stock Android first: read developer.android.com and AOSP for the mechanism,
  then measure the vendor's quirks on top.
- Reasons expire: when a cause goes (MediaPlayer for ExoPlayer, a permission
  dropped), every comment and decision that cites it is re-checked in the same
  change.
- Every review round has an "against established practice" angle, with a
  source for each claim.
- Inherited code (scrcpy, AOSP) is audited like our own when touched.

Agreed 2026-11-19, after 1.2 grew by patching each finding as it came
instead of planning it:

- Plan before code - for a feature, anything on the call path or in a file
  format, or a fix that touches more than one piece of logic: write
  `tmp/<task>/PLAN.md` and show it to the user before any code. It covers the
  problem, the goal the user sees, and what is out of scope. It says how it
  works today: our code and AOSP, measured where it matters; and how others
  solved it (prior art, below), before its options. Its state table
  lists every situation the feature meets (call types, the recorder up, lost
  or revived, modes, settings, tracking on or off, messages, the failure
  modes above) and what must happen in each. It weighs two options or more,
  the simplest among them, with the pick and why. It splits the work into
  small pieces, each with its tests and its "done", and gives the test plan
  and an estimate. A bug gets its root cause before any fix: reproduced or
  traced, named, and everything with the same cause listed.
- One scope per release, written in a line with its "done". Audit findings
  and features found on the way become releases of their own.
- Two review rounds at most. A third only for a high or medium bug in app
  code from one realistic fault. Lows and nits go on a list for the user. A
  finding the state table does not cover means re-planning that piece, not a
  patch. A fix that adds a flag, a state or a rule says why the design cannot
  take it more simply.
- When a piece is done and verified, ask whether to commit it or ship it as
  its own small release.
- Every task has an estimate; at twice it, stop and ask. No unattended
  overnight loops without a budget the user set.
- "Fix the whole class" and "no known gap" apply at or above that bar. One
  branch per release covers fixes to the release's own code, not new
  features or audits.

Agreed 2026-11-22, after the USB mode plan was approved before anyone looked
at how Shizuku copes:

- Prior art first: before a feature or a fix of any size, look up how
  similar software solved it - Shizuku and its forks, other tools started
  over adb, other call recorders: their issues, their code (shallow clones,
  only read), AOSP. A plan gets a section on it with sources (repo and date,
  issue numbers): what they do, what it costs them, what fails for them.
  Their way is evidence, weighed against our own rules, not a recipe.
