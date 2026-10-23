# tools

Scripts that are not part of the app. Each one is either a device experiment
kept because its measurements are cited in `docs/`, or something that produces
an artefact checked into this repository.

| | |
| --- | --- |
| `hooks/pre-push` | Tests and lint before a push. Install with `git config core.hooksPath tools/hooks`. |
| `render_icon.py` | Redraws the launcher mark as `docs/icon.png` for the README and the site, and at 512px as the F-Droid listing's icon under `fastlane/`. |
| `make_demo_faces.py` | Draws the two invented contact portraits the demo list uses. Output goes to the debug source set, so it ships in no release APK. |
| `shoot.py` | Captures `docs/screenshots/` on the phone - every screen in each of the three looks, light and dark, into `docs/screenshots/<look>/` - with the invented calls turned on, and copies the F-Droid set under `fastlane/`. Looks, themes and screens can be named to shoot only those (`shoot.py deck dark calls`); `--listing` redoes the copies from disk with no phone. The phone's own look and night mode are put back after. |
| `build_site.py` | Generates the site GitHub Pages serves - the landing page, the setup manual and the privacy policy - and `docs/SETUP.md`, which is the same manual rendered as Markdown from the same source. |
| `pair-now.sh` | Pairs the phone with its own Wireless debugging from a USB shell, for testing setup without tapping through it. |
| `check_16k_pages.py` | Fails when a native library in the APK would make Android run JemRec in 16 KB page-size compat mode. CI runs it on every build. Standard library only. |
| `emulator_checks.py` | Release checks on an emulator: installs the debug build, sets JemRec up from nothing, and places simulated calls through the real app and daemon - the ringing cut, an unanswered call, an incoming call, a stranger skipped by the rules, two calls in quick succession, a caller-ID name, the self-test, and the answering machine: a message taken, a hang-up during the greeting, a caller the rules leave out, a phone call over a messenger call (a stand-in call from `shellserver/probe`, built by the script), a caller in the day after an emergency call, and a call taken over in Ask first; then a call's waveform kept as it is saved, a recording played from its row going on with the screen off - named only "Call recording" while locked, held back by a ringing call - a deleted recording brought back by Restore all in Recently deleted and then removed by Delete all for good, file and all, and the app started again in each look. PASS or FAIL for each. Refuses to touch anything but an emulator; needs a `google_apis` image. Standard library only. |
| `capture-call.py` | Records from the daemon in raw PCM and measures each channel separately, which is how to tell "audio arrived" from "both sides of the call arrived". |

The Python ones need Pillow (`python3 -m pip install pillow`); the shell ones
need `adb` and a phone attached.
