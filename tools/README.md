# tools

Scripts that are not part of the app. Each one is either a device experiment
kept because its measurements are cited in `docs/`, or something that produces
an artefact checked into this repository.

| | |
| --- | --- |
| `hooks/pre-push` | Tests and lint before a push. Install with `git config core.hooksPath tools/hooks`. |
| `render_icon.py` | Redraws the launcher mark as `docs/icon.png` for the README and the site. |
| `make_demo_faces.py` | Draws the two invented contact portraits the demo list uses. Output goes to the debug source set, so it ships in no release APK. |
| `shoot.py` | Captures `docs/screenshots/` on the phone, in both themes, with the invented calls turned on. |
| `build_site.py` | Generates the site GitHub Pages serves - the landing page, the setup manual and the privacy policy - and `docs/SETUP.md`, which is the same manual rendered as Markdown from the same source. |
| `pair-now.sh` | Pairs the phone with its own Wireless debugging from a USB shell, for testing setup without tapping through it. |
| `capture-call.py` | Records from the daemon in raw PCM and measures each channel separately, which is how to tell "audio arrived" from "both sides of the call arrived". |

The Python ones need Pillow (`python3 -m pip install pillow`); the shell ones
need `adb` and a phone attached.
