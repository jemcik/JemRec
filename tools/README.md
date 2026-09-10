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
| `build_site.py` | Generates `docs/index.html` and `docs/privacy-policy.html`, which GitHub Pages serves. |
| `pair-now.sh` | Pairs the phone with its own Wireless debugging from a USB shell, for testing setup without tapping through it. |
| `milestone1.sh` | The milestone-1 transport checks: shell uid over the embedded client, in each network state. |
| `milestone3.sh` | The milestone-3 capture check: records a clip and reports per-channel energy. |
| `capture-call.py` | Records a real call over the loopback socket and writes the measurements `docs/evidence/` cites. |

The Python ones need Pillow (`python3 -m pip install pillow`); the shell ones
need `adb` and a phone attached.
