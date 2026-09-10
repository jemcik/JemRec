"""Capture JemRec's screenshots on the phone, in both themes, with invented calls.

WHY A DEBUG BUILD AND FAKE DATA. A screenshot of a call recorder is a screenshot
of somebody's calls. The debug source set can fill the list with people who do
not exist (see DemoRecordings), and that is what the README shows. Everything
ABOVE the list is real: the header says "Ready to record" because the daemon on
this phone genuinely answers, and the self-test passes because it genuinely runs.

WHY THE FLAG IS SET WITH run-as AND NOT A BROADCAST. The debug receiver takes
diagnostic ops over `am broadcast`, and on this Honor the delivery is
unreliable: the log shows "Enqueued broadcast ... : 0" and the receiver never
runs, which is the same iAware behaviour that stops the app being woken for a
call. Writing the preference file directly through `run-as` always works, needs
no token, and the provider installs the override at the next process start -
which the script forces anyway, between themes.

Taps are located by TEXT from the view hierarchy rather than by coordinates, so
this survives a layout change.

Run from anywhere:  python3 tools/shoot.py
"""
import os
import pathlib
import re
import subprocess
import sys
import time

# The phone these were shot on. Anyone else: JEMREC_DEVICE=<serial>, or leave it
# and let adb pick when only one device is attached.
DEV = os.environ.get("JEMREC_DEVICE", "AUNN025C15000871")
PKG = "com.jemcik.jemrec"
ROOT = pathlib.Path(__file__).resolve().parent.parent
OUT = ROOT / "docs" / "screenshots"

THEMES = {"light": "no", "dark": "yes"}

# What the README shows, at the width it shows it. Full-resolution shots of a
# 1256px-wide phone are 300 kB each and the page scales them to a fifth of that.
README_WIDTH = 420


def adb(*args: str, binary: bool = False):
    result = subprocess.run(
        ["adb", "-s", DEV, *args], capture_output=True, text=not binary
    )
    return result.stdout


def shell(command: str) -> str:
    return adb("shell", command)


def demo(on: bool) -> None:
    """Set the debug flag that swaps the real list for invented calls."""
    shell(f"am force-stop {PKG}")
    time.sleep(1)
    # The token is whatever is already there; only the flag changes, and the
    # file is rewritten whole because there is no way to edit XML in place from
    # a shell one-liner.
    current = shell(f"run-as {PKG} cat shared_prefs/jemrec_diag.xml")
    token = re.search(r'name="token">([0-9a-f]+)<', current)
    lines = [
        "<?xml version='1.0' encoding='utf-8' standalone='yes' ?>",
        "<map>",
    ]
    if token:
        lines.append(f'    <string name="token">{token.group(1)}</string>')
    lines.append(f'    <boolean name="demo_recordings" value="{str(on).lower()}" />')
    lines.append("</map>")
    subprocess.run(
        ["adb", "-s", DEV, "shell",
         f"run-as {PKG} sh -c 'cat > shared_prefs/jemrec_diag.xml'"],
        input="\n".join(lines) + "\n", text=True, check=True,
    )
    print(f"  invented recordings: {'on' if on else 'off'}")


def status_bar(clean: bool) -> None:
    """A fixed clock and full bars, so the shots do not date themselves.

    Best effort: some ROMs ignore demo mode entirely, and a real status bar is a
    cosmetic problem, not a reason to stop.
    """
    if clean:
        shell("settings put global sysui_demo_allowed 1")
        for command in (
            "command enter",
            "command clock -e hhmm -e 0900",
            "command battery -e level -e 100 -e plugged -e false",
            "command network -e wifi -e show -e level -e 4",
            "command network -e mobile -e show -e datatype -e false -e level -e 4",
            "command notifications -e visible -e false",
        ):
            shell(f"am broadcast -a com.android.systemui.demo -e {command}")
    else:
        shell("am broadcast -a com.android.systemui.demo -e command exit")


def grab(path: pathlib.Path) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    png = subprocess.run(
        ["adb", "-s", DEV, "exec-out", "screencap", "-p"], capture_output=True
    ).stdout
    path.write_bytes(png)
    print(f"    {path.relative_to(ROOT)}  {len(png) // 1024} kB")


def centre(label: str):
    """Where the node carrying this text or description is, or None."""
    shell("uiautomator dump /sdcard/ui.xml")
    xml = shell("cat /sdcard/ui.xml")
    # Node by node: one regex over the whole dump lets a node's empty text=""
    # swallow the next node's content-desc, which is the only thing the gear
    # carries.
    for node in xml.split("<node")[1:]:
        box = re.search(r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', node)
        if not box:
            continue
        labels = [l for l in re.findall(r'(?:text|content-desc)="([^"]*)"', node) if l]
        if any(label.lower() in l.lower() for l in labels):
            x1, y1, x2, y2 = map(int, box.groups())
            return (x1 + x2) // 2, (y1 + y2) // 2
    return None


def tap(label: str, what: str) -> bool:
    point = centre(label)
    if not point:
        print(f"    !! could not find {what} ({label!r})")
        return False
    shell(f"input tap {point[0]} {point[1]}")
    time.sleep(2)
    return True


def shoot(theme: str) -> None:
    print(f"  {theme}")
    shell(f"cmd uimode night {THEMES[theme]}")
    time.sleep(1)
    shell(f"am force-stop {PKG}")
    shell(f"am start -n {PKG}/.MainActivity")
    time.sleep(4)

    into = OUT / theme
    grab(into / "home.png")

    if not tap("Settings", "the settings gear"):
        return
    grab(into / "settings.png")

    # Diagnostics sits below the settings cards on a tall phone, and the report
    # panel appears under the buttons - so scroll to the bottom first, run the
    # test, and let the screen's own auto-scroll bring the answer into view.
    shell("input swipe 628 2000 628 900 400")
    time.sleep(1)
    if tap("Self-test", "the self-test button"):
        time.sleep(6)
        grab(into / "selftest.png")
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1)


def thumbnails() -> None:
    """The copies the README points at: one flat set, scaled to page width."""
    from PIL import Image

    for theme in THEMES:
        for name in ("home", "settings", "selftest"):
            source = OUT / theme / f"{name}.png"
            if not source.exists():
                continue
            suffix = "" if theme == "light" else "-dark"
            target = OUT / f"{name}{suffix}.png"
            image = Image.open(source)
            height = round(image.height * README_WIDTH / image.width)
            image.resize((README_WIDTH, height), Image.LANCZOS).save(target)
            print(f"    {target.relative_to(ROOT)}  {target.stat().st_size // 1024} kB")


if __name__ == "__main__":
    only = sys.argv[1:] or list(THEMES)
    demo(True)
    status_bar(True)
    try:
        for theme in only:
            shoot(theme)
    finally:
        status_bar(False)
        demo(False)
        # Leave the phone as its owner keeps it.
        shell("cmd uimode night yes")
        shell(f"am force-stop {PKG}")
    thumbnails()
    print("done")
