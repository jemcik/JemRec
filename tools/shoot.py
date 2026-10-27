"""Capture JemRec's screenshots on the phone, in all three looks and both
themes, with invented calls.

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

WHY THE SELECT TIP IS BROUGHT BACK. The Recordings tab opens with a tip until
it is dismissed, and it is the first thing a new user sees - so it belongs in
the shots. The phone they are shot on has usually dismissed it long ago, so
the flag is cleared for the shots and put back as it was afterwards.

Taps are located by TEXT from the view hierarchy rather than by coordinates, so
this survives a layout change.

Run from anywhere:  python3 tools/shoot.py
                    python3 tools/shoot.py deck dark calls  (only those looks,
                                          themes and screens; any of each)
                    python3 tools/shoot.py --listing   (no phone: re-derive the
                                          README and F-Droid copies from disk)
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
# docs/screenshots/<look>/<theme>/<screen>.png at full size, and beside each
# look's folder the README's and the site's copies (see thumbnails).
OUT = ROOT / "docs" / "screenshots"

# The three looks of Settings > Theme, as Looks keeps them. Every screen is
# shot in each: the site has a switch for the look as well as for light or
# dark (the user's choice, 2026-10-23).
LOOKS = {"memo": "MEMO", "deck": "DECK", "multitrack": "MULTITRACK"}
THEMES = {"light": "no", "dark": "yes"}
SCREENS = ("home", "calls", "settings", "answering", "theme", "whenoff", "selftest")

# The F-Droid listing. fastlane orders screenshots by file name, so these are
# numbered: the three looks first, then the rest in Memo, the default (the
# user's choice, 2026-10-23); full resolution.
LISTING = ROOT / "fastlane" / "metadata" / "android" / "en-US" / "images" / "phoneScreenshots"
LISTING_ORDER = (
    ("memo", "light", "home"),
    ("deck", "light", "home"),
    ("multitrack", "light", "home"),
    ("memo", "light", "calls"),
    ("memo", "light", "settings"),
    ("memo", "light", "answering"),
    ("memo", "light", "whenoff"),
    ("memo", "light", "selftest"),
    ("memo", "light", "theme"),
)

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


TIP = "select_hint_seen"


def tip_dismissed() -> bool:
    prefs = shell(f"run-as {PKG} cat shared_prefs/jemrec.xml")
    return f'name="{TIP}" value="true"' in prefs


def dismiss_tip(on: bool) -> None:
    """Flip the select tip's flag in place, with the app stopped.

    In place, not rewritten like the diag file: this one holds every setting
    the phone's owner has made. A flag that was never written needs nothing -
    absent means the tip shows.
    """
    old, new = ("false", "true") if on else ("true", "false")
    shell(f"am force-stop {PKG}")
    shell(
        f"run-as {PKG} sed -i "
        f"'s/name=\"{TIP}\" value=\"{old}\"/name=\"{TIP}\" value=\"{new}\"/' "
        "shared_prefs/jemrec.xml"
    )
    print(f"  select tip: {'dismissed' if on else 'showing'}")


# The look and its light or dark, as Settings > Theme keeps them (Looks):
# each shot is of one look following the phone, whatever the phone's owner
# picked - with Deck and Dark picked, every light shot came out Deck and
# dark. The app tells Android its light or dark as it starts, so the phone's
# night mode decides again.
LOOK_KEYS = ("look", "light_dark")


def set_look(look: str, light_dark: str = "PHONE") -> None:
    """Write the look's two settings, with the app stopped: replaced where
    the owner has them, added where they have never been written - Deck
    has to be written, where Memo, the default, could be left absent. The
    file holds every setting the owner has made, so it is read whole and
    written back with only these two changed."""
    shell(f"am force-stop {PKG}")
    prefs = shell(f"run-as {PKG} cat shared_prefs/jemrec.xml")
    if "</map>" not in prefs:
        print("    !! no settings file to write the look into")
        return
    for key, value in (("look", look), ("light_dark", light_dark)):
        line = f'<string name="{key}">{value}</string>'
        if re.search(rf'<string name="{key}">[A-Z_]*</string>', prefs):
            prefs = re.sub(rf'<string name="{key}">[A-Z_]*</string>', line, prefs)
        else:
            prefs = prefs.replace("</map>", f"    {line}\n</map>")
    subprocess.run(
        ["adb", "-s", DEV, "shell",
         f"run-as {PKG} sh -c 'cat > shared_prefs/jemrec.xml'"],
        input=prefs, text=True, check=True,
    )


def look_is(look: str) -> bool:
    """Whether the settings file says [look] now - checked with the app
    stopped, just before it starts: a process started between set_look's
    read and write (the keep-alive job, a call) could write its own copy of
    the file back over it."""
    prefs = shell(f"run-as {PKG} cat shared_prefs/jemrec.xml")
    return f'<string name="look">{look}</string>' in prefs


def night_mode() -> str:
    """The phone's own night mode, as `cmd uimode night` says it, to put
    back: the shoot sets it for each theme, and ended by setting it to yes
    whatever it had been."""
    said = re.search(r"Night mode: (\w+)", shell("cmd uimode night"))
    return said.group(1) if said else "unknown"


def owners_look() -> dict:
    """The look and light or dark the phone's owner keeps, to put back."""
    prefs = shell(f"run-as {PKG} cat shared_prefs/jemrec.xml")
    was = {}
    for key in LOOK_KEYS:
        found = re.search(rf'name="{key}">([A-Z_]+)<', prefs)
        if found:
            was[key] = found.group(1)
    print(f"  the owner's look: {was or 'the default'}")
    return was


def selected(label: str) -> bool:
    """Whether the node carrying exactly this text or description is
    selected, or the node round it - a tab. Round it: the looks' tabs are
    rows holding their name, and the name's own node is never the selected
    one - asked of it, the Calls tab never opened, in every look."""
    import xml.etree.ElementTree as ET

    shell("uiautomator dump /sdcard/ui.xml")
    try:
        root = ET.fromstring(shell("cat /sdcard/ui.xml"))
    except ET.ParseError:
        return False
    parent = {child: node for node in root.iter() for child in node}
    for node in root.iter("node"):
        if any((node.get(a) or "").lower() == label.lower() for a in ("text", "content-desc")):
            while node is not None:
                if node.get("selected") == "true":
                    return True
                node = parent.get(node)
            return False
    return False


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


def centre(label: str, exact: bool = False):
    """Where the node carrying this text or description is, or None.

    By substring unless `exact`: the Calls tab has to be asked for exactly,
    or the favourites chip - "Only calls with favourite contacts" - answers.
    """
    box = bounds(label, exact)
    return ((box[0] + box[2]) // 2, (box[1] + box[3]) // 2) if box else None


def bounds(label: str, exact: bool = False):
    """The box (left, top, right, bottom) of the node carrying this text or
    description, or None - as centre() finds it."""
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
        if any(label.lower() == l.lower() if exact else label.lower() in l.lower() for l in labels):
            return tuple(map(int, box.groups()))
    return None


# The invented call the list shows playing: the first, whose stand-in is a
# silent file that really plays (DemoRecordings), so its player opens with its
# waveform - one waveform, in the list only.
PLAYING = "Olivia Bennett"


def play_first() -> None:
    """The first call opened in its player, paused two fifths of the way in."""
    if not tap(PLAYING, "the first recording"):
        return
    box = bounds("Position in the recording")
    if not box:
        print("    !! no waveform under the first recording; home.png shows none")
        return
    x = box[0] + (box[2] - box[0]) * 2 // 5
    shell(f"input tap {x} {(box[1] + box[3]) // 2}")
    time.sleep(1)
    # Paused, so the time under it is the time tapped.
    tap(PLAYING, "the first recording, to pause it")


def tap(label: str, what: str, exact: bool = False) -> bool:
    point = centre(label, exact)
    if not point:
        print(f"    !! could not find {what} ({label!r})")
        return False
    shell(f"input tap {point[0]} {point[1]}")
    time.sleep(2)
    return True


def scroll_to(label: str) -> bool:
    """Swipe the screen up until a node with exactly this text is on it -
    not a fixed distance: one swipe stopped being enough the day Settings
    gained a card."""
    for _ in range(6):
        if centre(label, exact=True):
            return True
        shell("input swipe 628 2000 628 900 400")
        time.sleep(1)
    return bool(centre(label, exact=True))


def shoot(look: str, theme: str, wanted: set) -> None:
    print(f"  {look}, {theme}")
    set_look(LOOKS[look])
    shell(f"cmd uimode night {THEMES[theme]}")
    time.sleep(1)
    shell(f"am force-stop {PKG}")
    if not look_is(LOOKS[look]):
        print(f"    the look was written over; writing {look} again")
        set_look(LOOKS[look])
    shell(f"am start -n {PKG}/.MainActivity")
    time.sleep(4)

    into = OUT / look / theme
    if "home" in wanted:
        play_first()
        grab(into / "home.png")
        # Folded away again: the Calls tab would show the same player under
        # the same call.
        tap("Collapse player", "the player's chevron")

    # The Calls tab, with the invented call log behind the invented list.
    # Checked, not assumed: once the tap landed while the header was still
    # settling, missed the tab, and calls.png was the Recordings tab again.
    # Asked of the tab itself: the check was a time chip only the Recordings
    # list had, and those chips went - it passed whichever tab was open.
    for _ in range(3 if "calls" in wanted else 0):
        if not tap("Calls", "the calls tab", exact=True):
            break
        time.sleep(1)
        if selected("Calls"):
            grab(into / "calls.png")
            tap("Recordings", "the recordings tab", exact=True)
            break
    else:
        if "calls" in wanted:
            print("    !! the calls tab never opened; calls.png is unchanged")

    if not wanted & {"settings", "answering", "theme", "whenoff", "selftest"}:
        return
    if not tap("Settings", "the settings gear"):
        return
    if "settings" in wanted:
        grab(into / "settings.png")

    # The answering machine's own screen: real, as the phone has it - the
    # greeting's length and the test results are this phone's.
    if "answering" in wanted and tap("Answering machine", "the answering machine row", exact=True):
        grab(into / "answering.png")
        shell("input keyevent KEYCODE_BACK")
        time.sleep(1)

    # Settings > Theme: the look's own preview over the looks to choose.
    if "theme" in wanted and scroll_to("Theme") and tap("Theme", "the theme row", exact=True):
        grab(into / "theme.png")
        shell("input keyevent KEYCODE_BACK")
        time.sleep(1)

    # Settings > When JemRec is off, below Theme: the choice as this phone has
    # it, as the answering machine's screen is.
    if "whenoff" in wanted and scroll_to("When JemRec is off") \
            and tap("When JemRec is off", "the when-off row", exact=True):
        grab(into / "whenoff.png")
        shell("input keyevent KEYCODE_BACK")
        time.sleep(1)

    # Diagnostics is its own screen, the last row of Settings: open it, run
    # the test, and let the screen's own auto-scroll bring the answer into
    # view.
    if "selftest" in wanted and scroll_to("Diagnostics") and tap("Diagnostics", "the diagnostics row", exact=True) \
            and tap("Self-test", "the self-test button"):
        time.sleep(6)
        grab(into / "selftest.png")
        shell("input keyevent KEYCODE_BACK")
        time.sleep(1)
    shell("input keyevent KEYCODE_BACK")
    time.sleep(1)


def thumbnails() -> None:
    """The copies the README and the site point at, scaled to page width:
    docs/screenshots/<look>/<screen>.png, and <screen>-dark.png."""
    from PIL import Image

    for look in LOOKS:
        for theme in THEMES:
            for name in SCREENS:
                source = OUT / look / theme / f"{name}.png"
                if not source.exists():
                    continue
                suffix = "" if theme == "light" else "-dark"
                target = OUT / look / f"{name}{suffix}.png"
                image = Image.open(source)
                height = round(image.height * README_WIDTH / image.width)
                image.resize((README_WIDTH, height), Image.LANCZOS).save(target)
                print(f"    {target.relative_to(ROOT)}  {target.stat().st_size // 1024} kB")


def listing() -> None:
    """The F-Droid copies: full resolution, numbered, from the shots above."""
    import shutil

    LISTING.mkdir(parents=True, exist_ok=True)
    for old in LISTING.glob("*.png"):
        old.unlink()
    for number, (look, theme, name) in enumerate(LISTING_ORDER, start=1):
        source = OUT / look / theme / f"{name}.png"
        if not source.exists():
            continue
        suffix = "" if theme == "light" else "-dark"
        target = LISTING / f"{number}-{name}-{look}{suffix}.png"
        shutil.copyfile(source, target)
        print(f"    {target.relative_to(ROOT)}  {target.stat().st_size // 1024} kB")


if __name__ == "__main__":
    # `--listing` rebuilds the README and F-Droid copies from the full-size
    # shots already on disk, with no phone attached.
    if sys.argv[1:] == ["--listing"]:
        thumbnails()
        listing()
        sys.exit()

    asked = sys.argv[1:]
    looks = [a for a in asked if a in LOOKS] or list(LOOKS)
    themes = [a for a in asked if a in THEMES] or list(THEMES)
    wanted = {a for a in asked if a in SCREENS} or set(SCREENS)
    looked = owners_look()
    night = night_mode()
    print(f"  the phone's night mode: {night}")
    demo(True)
    dismissed = tip_dismissed()
    if dismissed:
        dismiss_tip(False)
    status_bar(True)
    try:
        for look in looks:
            for theme in themes:
                shoot(look, theme, wanted)
    finally:
        status_bar(False)
        demo(False)
        # Leave the phone as its owner keeps it.
        if night in ("yes", "no", "auto"):
            shell(f"cmd uimode night {night}")
        else:
            print(f"  !! the phone's night mode was {night}: set it back by hand")
        shell(f"am force-stop {PKG}")
        if dismissed:
            dismiss_tip(True)
        set_look(looked.get("look", "MEMO"), looked.get("light_dark", "PHONE"))
    thumbnails()
    listing()
    print("done")
