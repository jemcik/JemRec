"""Cut the setup video from a screen recording of JemRec's first-time setup.

Out comes a 1920x1080 MP4 - the phone at full height, the step and what to do
beside it, a narrator and quiet music if asked for - and an .srt of the words.

WHY A SCRIPT AND NOT A VIDEO EDITOR. Setup changes: a card gets new words, a
step moves, Android renames a switch. Then the video is wrong, and the cheap fix
is a new take on the phone. So the edit lives in TIMELINE below as data in the
recording's own clock - what to keep, what to speed up, what to say, where the
taps land, what to blur - and a re-record means new numbers, not a new edit.

WHY THE USER RECORDS IT, ON THE PHONE, UNPLUGGED. A real first setup happens
with no computer attached: adb only exists once USB debugging is on, and a
tethered Mac would put "Allow USB debugging?" and "USB debugging connected" on
screen, which nobody setting JemRec up ever sees. So the take is the phone's own
screen recorder, with no "show touches"; the rings at each tap are drawn here
(TAP), from the ripple of the button in the footage.

THE NARRATOR AND THE MUSIC. A silent how-to felt wrong to the user, so each
caption can carry a spoken line (`voice=`), read by Kokoro - an open model
(Apache-2.0) that runs on this Mac, so no text leaves it; af_heart is the
user's pick of its voices. A line is said BEFORE the tap it describes: where
the footage does not leave room, the frame just before that tap is held until
the line is done (fit). The music is synthesized by ambient_music.py, sits 21
dB under the voice while she speaks and dips before her first word, not after.

WHAT IS BLURRED, AND WHY BY SOURCE TIME. Only what a blur() cue in TIMELINE
names - nothing is found or covered automatically. Look for anything personal in
a new take: other notifications, the account card in Settings, the Wi-Fi name,
the IP address and port, the phone's name and serial numbers, the keyboard's
suggestions, names in the recordings list. A blur covers every frame of the
RECORDING in its time span, before anything else is drawn, so a speed-up, a
freeze or a zoom shows only the blurred pixels. Check every frame of each
blurred stretch at the video's size, not only the stills: a box that lagged a
scroll by one frame, and a blur too weak to hide "jemcik", were both found that
way on the 2026-10-24 take. The PIN screen is not blurred but cut.

Run from anywhere (raw footage stays out of git - keep it in a scratch folder):

    python3 tools/setup_video.py take.mp4            the video and the .srt
    python3 tools/setup_video.py take.mp4 --voice af_heart --music night \
        --kokoro MODELS --tts-python VENV/bin/python --espeak-data SHORT_DIR
                                                     narrated, with music:
                                                     MODELS holds kokoro-v1.0.onnx
                                                     and voices-v1.0.bin, VENV has
                                                     kokoro-onnx installed
    python3 tools/setup_video.py take.mp4 --stills   one still per caption, tap
                                                     and zoom, and no encode
    python3 tools/setup_video.py take.mp4 --sheet 0 60 1
                                                     raw frames from 0 s to 60 s,
                                                     one a second, labelled - to
                                                     read times off a new take
    python3 tools/setup_video.py take.mp4 --frame 12.5
                                                     one raw frame with a grid in
                                                     screen pixels - to read
                                                     positions off a new take

Output goes beside the recording unless --out says otherwise. Needs ffmpeg,
Pillow and numpy.

--espeak-data: espeak-ng, which Kokoro speaks through, keeps its data folder's
path in a 160-character buffer and cuts a longer one short without a word -
measured on 2026-10-24, when a venv in a deep scratch folder failed to start
with a path ending in "venv/lib//phontab". A copy of espeakng_loader's
espeak-ng-data at a short path fixes it (a symlink does not: it is resolved).
"""
import argparse
import dataclasses
import hashlib
import json
import math
import pathlib
import subprocess
import sys
import time
import wave

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
ICON = ROOT / "fastlane" / "metadata" / "android" / "en-US" / "images" / "icon.png"

FPS = 30
SIZE = (1920, 1080)
# Positions in TIMELINE are in this phone's screen pixels (Honor Magic 8 Pro,
# `wm size` 1256x2808). A take recorded at another size is scaled to match.
SCREEN = (1256, 2808)

# The site's palette (tools/build_site.py), so the video sits on its pages.
LOOKS = {
    "light": dict(bg="#F1F4F8", card="#FAFBFD", line="#D5DCE5", ink="#161A20",
                  mid="#4C555F", accent="#2C5AA0"),
    "dark": dict(bg="#22262B", card="#2C3137", line="#3B4149", ink="#E4E7EB",
                 mid="#B2BAC4", accent="#9ECAFF"),
}


@dataclasses.dataclass
class Cue:
    kind: str
    start: float
    end: float
    text: str = ""
    at: tuple | None = None
    to: tuple | None = None
    speed: int = 1
    seconds: float = 0.0
    voice: str = ""


# The timeline's vocabulary. Times are seconds into the raw recording; KEEP,
# HOLD and CARD are played in the order they are listed, everything else is
# drawn wherever its time falls.

def keep(start, end, speed=1):
    """Play start..end, every `speed`-th frame (a badge says so when > 1)."""
    return Cue("keep", start, end, speed=speed)


def hold(at, seconds):
    """Freeze the frame at `at` for `seconds` - time to read a caption."""
    return Cue("hold", at, at, seconds=seconds)


def card(name, seconds, voice=""):
    """A full-screen card from CARDS, longer if its spoken line needs it."""
    return Cue("card", 0, 0, text=name, seconds=seconds, voice=voice)


def step(start, end, title):
    """The step heading, numbered in order: "Step 2 of 5"."""
    return Cue("step", start, end, text=title)


def say(start, end, text, voice=""):
    """The caption: what to do, or what just happened - and what she says."""
    return Cue("say", start, end, text=text, voice=voice)


def tap(at, x, y):
    """A ring where a finger landed."""
    return Cue("tap", at, at, at=(x, y))


def zoom(start, end, rect):
    """The (x, y, w, h) of the screen, enlarged beside the phone."""
    return Cue("zoom", start, end, at=rect)


def blur(start, end, rect, to=None):
    """Cover (x, y, w, h); with `to`, the box glides there by `end` (a scroll)."""
    return Cue("blur", start, end, at=rect, to=to)


CARDS = {
    "title": ("Setting up JemRec",
              ["A call recorder for Android.",
               "No root and no computer: about three minutes, once."],
              "Filmed on an Honor Magic 8 Pro, MagicOS 11, Android 17, JemRec 0.19.1"),
    "end": ("Ready to record",
            ["Test it: call someone and talk for twenty seconds.",
             "The manual: jemcik.github.io/JemRec"],
            ""),
}

# The take of 2026-10-24 (SVID_20261024_113124_1.mp4, 80.7 s), recorded by the
# user on the Honor's own screen recorder after Start fresh, a reinstall of
# 0.19.1 from GitHub, and Developer options turned off. Times from --sheet; a
# tap's time is where the button's brightness changes, measured at 60 fps;
# positions from --frame. The steps are the manual's (tools/build_site.py).
# The thumbnail: the pairing code beside its step, the moment that says what
# the video is about (and the frame YouTube picked for its own, 2026-10-24).
THUMBNAIL_AT = 46.5
DIGITS_Y = 1899
DIGIT_X = {d: round(57 + ((d - 1) % 10) * 122.9) for d in range(10)}   # 0 is last
TIMELINE: list[Cue] = [
    card("title", 4.0, voice="Here's how to set up JemRec, a call recorder for Android. "
                              "It takes about three minutes, and you only do it once."),

    # 1. Two permissions, then the one question.
    keep(0.0, 2.6),
    keep(2.6, 3.6),
    hold(3.6, 2.2),
    keep(3.6, 4.7),
    hold(4.7, 2.5),
    keep(4.7, 7.3),
    hold(7.3, 2.0),
    keep(7.3, 9.6),
    hold(9.6, 2.0),
    keep(9.6, 10.4),
    keep(10.4, 12.4, speed=2),
    step(0.0, 12.45, "Allow permissions"),
    say(0.0, 2.55, "Open JemRec and tap Allow.",
        voice="Step one. Open JemRec, and tap Allow."),
    tap(2.42, 250, 1125),
    say(2.55, 3.75, "Allow notifications. The pairing code goes into one, later.",
        voice="Allow notifications. You'll type the pairing code into one, later."),
    tap(3.70, 628, 2334),
    say(3.75, 4.88, "Allow audio files, to find recordings made before. Refusing is fine.",
        voice="Allow audio files too, so JemRec can find recordings it made before. "
              "Saying no is fine."),
    tap(4.83, 628, 2334),
    say(4.88, 7.35, "Debugging stays on for as long as JemRec is installed: "
                    "it records through it.",
        voice="JemRec records through Android's debugging tools, so debugging stays on "
              "for as long as it's installed."),
    say(7.35, 12.45, "Android reminds you with a notification. Keep it or hide it: "
                     "either works.",
        voice="Android shows a notification while debugging is on. Keep it, or hide it. "
              "Either works."),
    tap(9.90, 890, 1890),

    # 2. Developer options. The PIN screen records as black (it is secure),
    # and only a little of it is kept.
    keep(12.4, 16.3),
    hold(16.3, 1.0),
    keep(16.3, 21.32),
    keep(21.32, 23.5),
    hold(23.5, 1.5),
    keep(24.5, 25.2),
    hold(25.2, 1.5),
    keep(25.2, 28.05),
    step(12.45, 28.05, "Turn on Developer options"),
    say(12.45, 16.55, "This sounds technical, but nothing about your phone changes. "
                      "Tap About phone.",
        voice="Step two: Developer options. It sounds technical, but nothing about your "
              "phone changes. Tap About phone."),
    tap(16.50, 346, 1736),
    say(16.55, 21.32, "Find Build number and tap it seven times.",
        voice="Find Build number, and tap it seven times."),
    *[tap(t, 430, 1935) for t in (19.72, 19.98, 20.22, 20.45, 20.68, 20.93, 21.23)],
    say(21.32, 24.55, "Type your PIN if asked. Android keeps that screen out of recordings.",
        voice="Type your PIN if you're asked. Android keeps that screen out of recordings."),
    say(24.55, 25.6, "\"You are now a developer!\" means it worked.",
        voice="When it says you are now a developer, it worked."),
    zoom(24.6, 25.6, (315, 2468, 645, 118)),
    say(25.6, 28.05, "Swipe back to JemRec. It notices by itself.",
        voice="Swipe back to JemRec. It notices by itself."),

    # 3. Both debugging switches, in one trip to Settings.
    keep(28.05, 29.3),
    hold(29.3, 1.5),
    keep(29.3, 31.6),
    keep(31.6, 36.0),
    keep(36.0, 39.3, speed=2),
    keep(39.3, 40.2),
    hold(40.2, 2.0),
    keep(40.2, 43.0),
    hold(43.0, 1.0),
    step(28.05, 43.05, "Turn on both debugging switches"),
    say(28.05, 30.0, "Now one trip to Settings: both debugging switches, then the code.",
        voice="Step three: both debugging switches, in one trip to Settings."),
    say(30.0, 31.6, "Tap Open settings.", voice="Tap Open settings."),
    tap(31.55, 356, 2503),
    say(31.6, 36.0, "Find Developer options. On this phone: System & updates.",
        voice="Find Developer options. On this phone, it's under System and updates."),
    tap(34.50, 400, 2373),
    tap(35.82, 400, 1782),
    say(36.0, 39.4, "Scroll down to Debugging.", voice="Scroll down to Debugging."),
    say(39.4, 40.6, "Turn on USB debugging and tap OK. No cable is needed.",
        voice="Turn on USB debugging, and tap OK. No cable needed."),
    tap(39.45, 1100, 1754),
    tap(40.50, 876, 2522),
    say(40.6, 43.05, "Then open Wireless debugging and turn it on.",
        voice="Then open Wireless debugging, and turn it on."),
    tap(41.50, 450, 2197),
    tap(42.68, 1100, 465),

    # 4. Pairing, from the notification shade.
    keep(43.0, 46.5),
    hold(46.5, 1.0),
    keep(46.5, 48.9),
    hold(48.9, 2.0),
    keep(48.9, 49.8),
    hold(49.8, 1.2),
    keep(49.8, 57.5),
    # Nothing to read here in the silent cut, but where her "Paired!" line
    # needs time: on the notification, not on the shade half closed (57.97).
    hold(57.5, 0.0),
    keep(57.5, 58.5),
    hold(58.5, 1.5),
    keep(58.5, 59.1),
    step(43.05, 59.1, "Pair, from your notifications"),
    say(43.05, 45.15, "Tap Pair device with pairing code.",
        voice="Step four: pairing. Tap Pair device with pairing code."),
    tap(45.10, 480, 1195),
    say(45.15, 48.3, "Leave this window open: the code only works while it is.",
        voice="Leave this window open. The code only works while it's on screen."),
    zoom(45.6, 48.3, (100, 1905, 640, 240)),
    say(48.3, 49.15, "Swipe down from the top, over it. Settings stays open underneath.",
        voice="Now swipe down from the top. Settings stays open underneath."),
    say(49.15, 50.5, "Tap the JemRec notification, then Enter code.",
        voice="Tap the JemRec notification, then Enter code."),
    tap(49.23, 520, 945),
    tap(50.22, 628, 1416),
    say(50.5, 54.9, "Type the six digits and send.", voice="Type the six digits, and send them."),
    zoom(50.7, 54.9, (30, 1370, 1196, 120)),
    *[tap(t, DIGIT_X[d], DIGITS_Y) for t, d in
      ((51.53, 4), (52.15, 7), (52.65, 6), (53.07, 6), (53.62, 9), (54.00, 4))],
    tap(54.58, 1130, 1428),
    say(54.9, 58.0, "Paired. JemRec starts its recorder by itself.",
        voice="Paired! JemRec now starts its recorder by itself."),
    zoom(55.4, 57.9, (40, 840, 1180, 180)),
    say(58.0, 59.1, "JemRec is now under Paired devices.",
        voice="And it shows up under Paired devices."),
    zoom(58.2, 59.0, (40, 1360, 1180, 240)),

    # 5. Back to JemRec. 63.6-66.4 is cut: the header said Ready to record,
    # then Starting... until 72.4 - USB debugging switched itself off at
    # 59.3 (seen in the take), and the recorder log shows the first daemon
    # (11:32:23) replaced by a second at 11:32:36.
    keep(59.1, 63.6, speed=2),
    keep(66.4, 72.5, speed=3),
    keep(72.5, 80.6),
    step(59.1, 80.6, "Wait a few seconds"),
    # Ends on the pairing card: a held frame at 63.77 was already the home
    # screen saying Ready, and froze the very flash this cut removes.
    say(59.1, 63.6, "Swipe back to JemRec.", voice="Step five. Swipe back to JemRec."),
    say(66.4, 72.5, "It starts its recorder: a few seconds.",
        voice="It takes a few seconds to start the recorder."),
    say(72.5, 76.5, "Ready to record. Calls are recorded from now on.",
        voice="And it's ready to record. From now on, every call is recorded automatically."),
    say(76.5, 80.6, "Optional: allow Show who called, to see names on recordings.",
        voice="If you like, allow Show who called, to see names on your recordings."),

    card("end", 5.0, voice="That's it. Make a test call, and talk for about twenty seconds. "
                           "The full manual is on the JemRec website."),

    # Blurs. The phone's name on About phone, through the opening animation,
    # the scroll and the swipe back.
    blur(16.55, 17.0, (100, 1000, 1000, 400)),
    blur(17.0, 17.82, (100, 1110, 470, 160)),
    # The scroll, 17.85 to 18.3: the whole left column the name passes
    # through, because a box following it lagged by a frame or two.
    blur(17.82, 18.35, (40, 230, 560, 1100)),
    blur(18.35, 21.33, (50, 262, 470, 100)),
    blur(24.5, 25.55, (50, 262, 470, 100)),
    blur(25.55, 26.0, (0, 150, 1256, 450)),
    # Settings: the Honor ID's photo and name, and the Wi-Fi network's name,
    # from the opening animation until the scroll takes them off screen.
    blur(31.66, 32.42, (40, 760, 1216, 330)),
    blur(31.66, 32.42, (760, 1120, 496, 170)),
    blur(32.42, 32.75, (0, 230, 1256, 1100)),
    # Wireless debugging: the phone's name and its IP address and port, on
    # the page and in the pairing dialog (which grows from the bottom).
    blur(42.7, 48.5, (850, 630, 330, 120)),
    blur(42.7, 48.5, (680, 795, 540, 115)),
    blur(45.25, 45.5, (100, 2250, 700, 558)),
    blur(45.5, 48.5, (110, 2265, 640, 115)),
    blur(57.9, 59.03, (850, 630, 330, 120)),
    blur(57.9, 59.03, (680, 795, 540, 115)),
    # The swipe back slides the page right, name and address with it.
    blur(59.03, 59.25, (560, 600, 696, 420)),
]


# --- the recording ----------------------------------------------------------

def probe(path):
    """Width and height as decoded (a rotation flag swaps them), and length."""
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-select_streams", "v:0", "-show_entries",
         "stream=width,height:stream_side_data=rotation:format=duration",
         "-of", "json", str(path)],
        capture_output=True, text=True, check=True,
    ).stdout
    info = json.loads(out)
    stream = info["streams"][0]
    w, h = stream["width"], stream["height"]
    rotation = 0
    for side in stream.get("side_data_list", []):
        rotation = int(side.get("rotation", 0))
    if abs(rotation) % 180 == 90:
        w, h = h, w
    return w, h, float(info["format"]["duration"])


def start_time(path):
    """When the recording's first frame is stamped, in seconds."""
    out = subprocess.run(
        ["ffprobe", "-v", "error", "-show_entries", "format=start_time", "-of", "json",
         str(path)], capture_output=True, text=True, check=True).stdout
    return float(json.loads(out)["format"].get("start_time") or 0)


def raw_frame(path, t, size):
    """The recording's frame at t seconds, as an RGB image.

    t counts from the first frame, the same clock the render uses (Frames
    restarts the timestamps at zero), so a time read off --sheet is the time
    the timeline means even when a recording's stamps do not start at 0.
    """
    w, h = size
    data = subprocess.run(
        ["ffmpeg", "-v", "error", "-ss", f"{t + start_time(path):.3f}", "-i", str(path),
         "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "rgb24", "-"],
        capture_output=True, check=True,
    ).stdout
    if len(data) < w * h * 3:
        raise SystemExit(f"no frame at {t:.2f} s")
    return Image.frombuffer("RGB", (w, h), data[: w * h * 3])


class Frames:
    """The recording's frames in order, only the ones the edit uses.

    A minute of this phone's screen is 1,800 frames of 10 MB each at 30 fps, so
    ffmpeg's own `select` drops everything the timeline cuts before it reaches
    the pipe. The set it keeps is computed here the same way, so the n-th frame
    out of the pipe is known to be the n-th number in `wanted`.
    """

    def __init__(self, path, size, wanted):
        self.size = size
        self.wanted = sorted(set(wanted))
        self.index = -1
        self.number = -1
        self.image = None
        terms = []
        # One term per run of evenly spaced frames, so a long keep is one
        # term rather than a thousand.
        for first, last, every in runs(self.wanted):
            terms.append(f"between(n,{first},{last})*not(mod(n-{first},{every}))")
        expression = "+".join(terms) or "0"
        self.proc = subprocess.Popen(
            ["ffmpeg", "-v", "error", "-i", str(path), "-an", "-vf",
             f"setpts=PTS-STARTPTS,fps={FPS},select='{expression}',format=rgb24",
             "-fps_mode", "passthrough", "-f", "rawvideo", "-"],
            stdout=subprocess.PIPE,
        )

    def get(self, n):
        w, h = self.size
        while self.number < n:
            data = self.proc.stdout.read(w * h * 3)
            if len(data) < w * h * 3:
                # Past the end of the take: keep showing its last frame.
                if self.image is None:
                    raise SystemExit(f"the take ends before frame {n} ({n / FPS:.2f} s)")
                break
            self.index += 1
            self.number = self.wanted[self.index]
            self.image = Image.frombuffer("RGB", (w, h), data)
        return self.image

    def close(self):
        self.proc.stdout.close()
        self.proc.wait()


def runs(numbers):
    """Sorted frame numbers as (first, last, every) runs of equal spacing."""
    out = []
    i = 0
    while i < len(numbers):
        first = numbers[i]
        if i + 1 == len(numbers):
            out.append((first, first, 1))
            break
        every = numbers[i + 1] - first
        j = i + 1
        while j + 1 < len(numbers) and numbers[j + 1] - numbers[j] == every:
            j += 1
        out.append((first, numbers[j], every))
        i = j + 1
    return out


# --- the plan: which frame goes out when -------------------------------------

@dataclasses.dataclass
class Piece:
    kind: str          # "src" or "card"
    start: float       # output seconds
    frames: list       # source frame numbers, one per output frame ("src")
    name: str = ""     # the card ("card")
    count: int = 0     # output frames
    speed: int = 1     # 1, or how many times faster; a hold is 1


def plan(timeline):
    pieces = []
    t = 0.0
    last = -1
    for cue in timeline:
        if cue.kind == "card":
            count = round(cue.seconds * FPS)
            pieces.append(Piece("card", t, [], name=cue.text, count=count))
        elif cue.kind in ("keep", "hold"):
            if cue.kind == "keep":
                frames = list(range(round(cue.start * FPS), round(cue.end * FPS), cue.speed))
            else:
                frames = [round(cue.start * FPS)] * round(cue.seconds * FPS)
            if frames and frames[0] < last:
                raise SystemExit(f"{cue.kind} at {cue.start} s goes back in time")
            last = frames[-1] if frames else last
            pieces.append(Piece("src", t, frames, count=len(frames),
                                speed=cue.speed if cue.kind == "keep" else 1))
        else:
            continue
        t += pieces[-1].count / FPS
    return pieces


def out_span(pieces, start, end):
    """Where source start..end lands in the output: (first, last) seconds."""
    a = b = None
    lo, hi = round(start * FPS), round(end * FPS)
    for piece in pieces:
        if piece.kind != "src":
            continue
        for k, n in enumerate(piece.frames):
            # End exclusive, so a caption that starts where another ends
            # does not share a frame with it.
            if lo <= n < max(hi, lo + 1):
                t = piece.start + k / FPS
                a = t if a is None else a
                b = t + 1 / FPS
    return (a, b) if a is not None else None


# --- drawing ------------------------------------------------------------------

FONTS = {}


def font(size, weight=400):
    key = (size, weight)
    if key not in FONTS:
        FONTS[key] = load_font(size, weight)
    return FONTS[key]


def load_font(size, weight):
    sf = pathlib.Path("/System/Library/Fonts/SFNS.ttf")
    if sf.exists():
        f = ImageFont.truetype(str(sf), size)
        # Width, optical size, grade, weight - the axes this file has.
        f.set_variation_by_axes([100, max(17, min(96, size)), 400, weight])
        return f
    for path in (
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf" if weight >= 600
        else "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
        "/System/Library/Fonts/Helvetica.ttc",
    ):
        if pathlib.Path(path).exists():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default(size)


def wrap(text, f, width):
    lines = []
    for paragraph in text.split("\n"):
        line = ""
        for word in paragraph.split():
            trial = f"{line} {word}".strip()
            if f.getlength(trial) <= width or not line:
                line = trial
            else:
                lines.append(line)
                line = word
        lines.append(line)
    return lines


def text_block(text, f, width, colour, spacing=1.25):
    """Wrapped text as a transparent image, and its height."""
    lines = wrap(text, f, width)
    step_y = round(f.size * spacing)
    img = Image.new("RGBA", (width, step_y * len(lines) + round(f.size * 0.3)), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    for i, line in enumerate(lines):
        draw.text((0, i * step_y), line, font=f, fill=colour)
    return img


def faded(img, alpha):
    if alpha >= 0.999:
        return img
    out = img.copy()
    out.putalpha(img.getchannel("A").point(lambda v: round(v * alpha)))
    return out


def rounded_mask(size, radius):
    mask = Image.new("L", size, 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius, fill=255)
    return mask


def ramp(t, span, edge=0.25):
    """1 inside span, easing in and out over `edge` seconds at its ends."""
    if span is None:
        return 0.0
    a, b = span
    if t < a or t >= b:
        return 0.0
    return max(0.0, min(1.0, (t - a) / edge, (b - t) / edge))


class Wide:
    """16:9: the phone on the left at full height, the words on the right."""

    def __init__(self, size, timeline, pieces, look):
        self.look = LOOKS[look]
        self.timeline = timeline
        self.pieces = pieces
        w, h = size
        self.k = w / SCREEN[0]                      # screen px -> recording px
        self.ph = SIZE[1] - 80
        self.pw = round(self.ph * w / h)
        self.px, self.py = 200, 40
        self.s = self.ph / h                        # recording px -> video px
        self.panel_x = self.px + self.pw + 120
        self.panel_w = SIZE[0] - self.panel_x - 90
        self.mask = rounded_mask((self.pw, self.ph), 36)
        self.base = self.background()
        self.steps = [c for c in timeline if c.kind == "step"]
        self.spans = {id(c): out_span(pieces, c.start, c.end) for c in timeline
                      if c.kind in ("step", "say", "zoom")}
        # Up to four frames on: inside a sped-up keep the tap's own frame may
        # be one of those skipped, and a one-frame window then lost its ring.
        self.taps = [(out_span(pieces, c.start, c.start + 4 / FPS), c)
                     for c in timeline if c.kind == "tap"]
        self.cache = {}

    def background(self):
        img = Image.new("RGB", SIZE, self.look["bg"])
        shadow = Image.new("L", SIZE, 0)
        ImageDraw.Draw(shadow).rounded_rectangle(
            (self.px, self.py + 10, self.px + self.pw, self.py + self.ph + 10), 40, fill=90)
        shadow = shadow.filter(ImageFilter.GaussianBlur(18))
        img.paste(Image.new("RGB", SIZE, "#000000"), (0, 0), shadow)
        return img

    def to_video(self, x, y):
        """Screen px -> video px on the phone."""
        return self.px + x * self.k * self.s, self.py + y * self.k * self.s

    def text(self, key, text, size, weight, colour, width):
        k = (key, text, size, weight, colour, width)
        if k not in self.cache:
            self.cache[k] = text_block(text, font(size, weight), width, colour)
        return self.cache[k]

    def frame(self, src, n, out_t, speed=1):
        t = n / FPS
        screen = blurred(src, self.timeline, t, self.k)
        canvas = self.base.copy()
        phone = screen.resize((self.pw, self.ph), Image.BILINEAR, reducing_gap=2.0)
        canvas.paste(phone, (self.px, self.py), self.mask)
        draw = ImageDraw.Draw(canvas, "RGBA")
        self.draw_speed(draw, speed)
        y = self.draw_step(canvas, draw, out_t)
        y = self.draw_say(canvas, out_t, y)
        self.draw_zoom(canvas, draw, screen, out_t, y)
        self.draw_taps(canvas, out_t)
        return canvas

    def draw_step(self, canvas, draw, out_t):
        x, y = self.panel_x, 110
        current = None
        for i, cue in enumerate(self.steps):
            if ramp(out_t, self.spans[id(cue)], 0.001) > 0:
                current = i
        if current is None:
            return 300
        cue = self.steps[current]
        alpha = ramp(out_t, self.spans[id(cue)], 0.3)
        # Where this step sits among all of them: a bar per step, the done
        # ones and this one filled.
        gap, total = 14, len(self.steps)
        seg = (self.panel_w - gap * (total - 1)) / total
        for i in range(total):
            fill = self.look["accent"] if i <= current else self.look["line"]
            x0 = x + i * (seg + gap)
            draw.rounded_rectangle((x0, y, x0 + seg, y + 8), 4, fill=fill)
        label = self.text("label", f"STEP {current + 1} OF {total}", 30, 700,
                          self.look["accent"], self.panel_w)
        canvas.paste(faded(label, alpha), (x, y + 36), faded(label, alpha))
        title = self.text("title", cue.text, 64, 700, self.look["ink"], self.panel_w)
        canvas.paste(faded(title, alpha), (x, y + 84), faded(title, alpha))
        return y + 84 + title.height + 30

    def draw_say(self, canvas, out_t, y):
        bottom = y
        for cue in self.timeline:
            if cue.kind != "say":
                continue
            alpha = ramp(out_t, self.spans[id(cue)])
            if alpha <= 0:
                continue
            img = self.text("say", cue.text, 46, 400, self.look["ink"], self.panel_w)
            img = faded(img, alpha)
            canvas.paste(img, (self.panel_x, y), img)
            bottom = max(bottom, y + img.height)
        return bottom + 30

    def draw_zoom(self, canvas, draw, screen, out_t, y):
        for cue in self.timeline:
            if cue.kind != "zoom":
                continue
            alpha = ramp(out_t, self.spans[id(cue)])
            if alpha <= 0:
                continue
            x0, y0, w0, h0 = (v * self.k for v in cue.at)
            region = screen.crop((round(x0), round(y0), round(x0 + w0), round(y0 + h0)))
            room_w = self.panel_w - 24
            room_h = SIZE[1] - 60 - y - 24
            scale = min(room_w / region.width, room_h / region.height)
            big = region.resize((round(region.width * scale), round(region.height * scale)),
                                Image.LANCZOS)
            frame_box = (self.panel_x, y, self.panel_x + big.width + 24, y + big.height + 24)
            a = round(255 * alpha)
            draw.rounded_rectangle(frame_box, 20, fill=self.look["card"] + f"{a:02x}",
                                   outline=self.look["accent"] + f"{a:02x}", width=4)
            mask = rounded_mask(big.size, 12).point(lambda v: round(v * alpha))
            canvas.paste(big, (self.panel_x + 12, y + 12), mask)
            # And where it came from, on the phone.
            ax, ay = self.to_video(*cue.at[:2])
            bx, by = self.to_video(cue.at[0] + cue.at[2], cue.at[1] + cue.at[3])
            draw.rounded_rectangle((ax - 4, ay - 4, bx + 4, by + 4), 10,
                                   outline=self.look["accent"] + f"{a:02x}", width=4)

    def draw_taps(self, canvas, out_t):
        for span, cue in self.taps:
            if span is None:
                continue
            p = (out_t - span[0]) / 0.7
            if not 0 <= p < 1:
                continue
            cx, cy = self.to_video(*cue.at)
            ease = 1 - (1 - p) ** 3
            r = 16 + 34 * ease
            a = round(255 * (1 - p))
            layer = Image.new("RGBA", (120, 120), (0, 0, 0, 0))
            d = ImageDraw.Draw(layer)
            c = 60
            if p < 0.35:
                d.ellipse((c - 14, c - 14, c + 14, c + 14), fill=(255, 255, 255, round(a * 0.8)))
            d.ellipse((c - r - 3, c - r - 3, c + r + 3, c + r + 3),
                      outline=(255, 255, 255, a), width=9)
            d.ellipse((c - r, c - r, c + r, c + r),
                      outline=self.look_rgb("accent") + (a,), width=4)
            canvas.paste(layer, (round(cx) - c, round(cy) - c), layer)

    def draw_speed(self, draw, speed):
        # From the piece, not the source frame: a frame held inside a sped-up
        # stretch is not playing fast, and once said so on a frozen screen.
        if speed <= 1:
            return
        label = f"{speed}×"
        f = font(30, 700)
        w = 60 + f.getlength(label)
        x1, y0 = self.px + self.pw - 18, self.py + 110
        x0 = x1 - w
        draw.rounded_rectangle((x0, y0, x1, y0 + 50), 25, fill=(22, 26, 32, 210))
        # Two triangles: fast forward.
        for i in range(2):
            tx = x0 + 16 + i * 15
            draw.polygon([(tx, y0 + 15), (tx, y0 + 35), (tx + 14, y0 + 25)], fill="#FFFFFF")
        draw.text((x0 + 52, y0 + 7), label, font=f, fill="#FFFFFF")

    def look_rgb(self, name):
        v = self.look[name].lstrip("#")
        return tuple(int(v[i:i + 2], 16) for i in (0, 2, 4))

    def card(self, name, local_t, seconds):
        title, lines, small = CARDS[name]
        key = ("card", name)
        if key not in self.cache:
            img = Image.new("RGB", SIZE, self.look["bg"])
            draw = ImageDraw.Draw(img)
            icon = Image.open(ICON).convert("RGBA").resize((200, 200), Image.LANCZOS)
            y = 230
            img.paste(icon, ((SIZE[0] - 200) // 2, y), icon)
            y += 250
            f = font(92, 700)
            draw.text(((SIZE[0] - f.getlength(title)) / 2, y), title, font=f, fill=self.look["ink"])
            y += 140
            f = font(44, 400)
            for line in lines:
                draw.text(((SIZE[0] - f.getlength(line)) / 2, y), line, font=f, fill=self.look["mid"])
                y += 64
            if small:
                f = font(30, 400)
                draw.text(((SIZE[0] - f.getlength(small)) / 2, SIZE[1] - 110), small,
                          font=f, fill=self.look["mid"])
            self.cache[key] = img
        img = self.cache[key]
        alpha = max(0.0, min(1.0, local_t / 0.4, (seconds - local_t) / 0.4))
        if name == "title":
            alpha = min(1.0, (seconds - local_t) / 0.4)   # opens on it, no fade in
        return Image.blend(self.base, img, alpha) if alpha < 1 else img


def blurred(src, timeline, t, k):
    """The recording's frame with every blur that covers time t applied."""
    boxes = []
    for cue in timeline:
        if cue.kind != "blur" or not cue.start <= t <= cue.end:
            continue
        box = cue.at
        if cue.to is not None and cue.end > cue.start:
            p = (t - cue.start) / (cue.end - cue.start)
            box = tuple(a + (b - a) * p for a, b in zip(cue.at, cue.to))
        boxes.append(box)
    if not boxes:
        return src
    out = src.copy()
    for x, y, w, h in boxes:
        x0, y0 = max(0, round(x * k)), max(0, round(y * k))
        x1, y1 = min(src.width, round((x + w) * k)), min(src.height, round((y + h) * k))
        if x1 <= x0 or y1 <= y0:
            continue
        region = src.crop((x0, y0, x1, y1))
        # Pixelate first, then soften: a blur alone can leave big text
        # readable. Blocks under a line of text's height left the word's
        # shape on the 2026-10-24 take - "jemcik" could still be read at the
        # video's size - so a block is a third of the box, 24 to 48 pixels.
        block = max(24, min(48, min(x1 - x0, y1 - y0) // 3))
        small = region.resize((max(1, (x1 - x0) // block), max(1, (y1 - y0) // block)), Image.BOX)
        region = small.resize(region.size, Image.BILINEAR).filter(ImageFilter.GaussianBlur(block / 3))
        out.paste(region, (x0, y0), rounded_mask(region.size, 18))
    return out


# --- the voice and the music ------------------------------------------------

AUDIO_SR = 48000
LANG, SPEED = "en-us", 1.0
# When a line is said: LEAD after its caption appears, GAP after the line
# before, and done TAIL before the tap it describes (or the caption changes).
LEAD, GAP, TAIL = 0.25, 0.35, 0.3
CARD_LEAD, CARD_TAIL = 0.6, 1.0

# Run by the Python that has kokoro-onnx, which is not this one: the model
# and its dependencies live in their own venv, and this script needs only
# ffmpeg, Pillow and numpy. A job on stdin, a WAV per line out.
TTS = r"""
import json, sys, wave
import numpy as np
from kokoro_onnx import Kokoro, EspeakConfig
job = json.loads(sys.stdin.read())
config = EspeakConfig(data_path=job["espeak"]) if job["espeak"] else None
k = Kokoro(job["model"], job["voices"], espeak_config=config)
for line in job["lines"]:
    x, sr = k.create(line["text"], voice=job["voice"], speed=job["speed"], lang=job["lang"])
    loud = np.flatnonzero(np.abs(x) > 0.01)
    if len(loud):
        x = x[max(0, loud[0] - int(0.04 * sr)): loud[-1] + int(0.1 * sr)]
    with wave.open(line["out"], "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())
"""


def read_wav(path):
    """Mono float samples and the rate."""
    with wave.open(str(path)) as w:
        x = np.frombuffer(w.readframes(w.getnframes()), "<i2").astype(np.float64) / 32768
        if w.getnchannels() > 1:
            x = x.reshape(-1, w.getnchannels()).mean(axis=1)
        return x, w.getframerate()


def to_audio_rate(x, sr):
    """Resample to AUDIO_SR; Kokoro's 24 kHz doubles exactly in the spectrum."""
    if sr == AUDIO_SR:
        return x
    if AUDIO_SR == 2 * sr:
        spectrum = np.zeros(len(x) + 1, complex)
        spectrum[: len(x) // 2 + 1] = np.fft.rfft(x)
        return np.fft.irfft(spectrum, 2 * len(x)) * 2
    t = np.arange(round(len(x) * AUDIO_SR / sr)) * sr / AUDIO_SR
    return np.interp(t, np.arange(len(x)), x)


def synthesize(timeline, args, cache):
    """Every spoken line as samples at AUDIO_SR, synthesized once and kept."""
    cache.mkdir(parents=True, exist_ok=True)
    texts = sorted({c.voice for c in timeline if c.voice})

    def name(text):
        key = f"{args.voice}|{SPEED}|{LANG}|{text}".encode()
        return cache / f"{hashlib.sha1(key).hexdigest()[:16]}.wav"

    missing = [t for t in texts if not name(t).exists()]
    if missing:
        if not (args.kokoro and args.tts_python):
            raise SystemExit("--voice needs --kokoro and --tts-python")
        job = dict(model=str(args.kokoro / "kokoro-v1.0.onnx"),
                   voices=str(args.kokoro / "voices-v1.0.bin"),
                   espeak=str(args.espeak_data) if args.espeak_data else None,
                   voice=args.voice, speed=SPEED, lang=LANG,
                   lines=[dict(text=t, out=str(name(t))) for t in missing])
        print(f"  synthesizing {len(missing)} lines", file=sys.stderr)
        subprocess.run([str(args.tts_python), "-c", TTS], input=json.dumps(job),
                       text=True, check=True)
    return {t: to_audio_rate(*read_wav(name(t))) for t in texts}


def shown_at(pieces):
    """Source frame -> the output times it is shown at, in order.

    fit() asks where frames land once per cue on every stretch; walking every
    output frame for each question made a fit cues x frames x stretches.
    """
    index = {}
    for p in pieces:
        if p.kind == "src":
            for k, n in enumerate(p.frames):
                index.setdefault(n, []).append(p.start + k / FPS)
    return index


def pause_frame(cue, timeline, index):
    """The frame to hold if this caption's line needs more time: just before
    the first tap it describes, or its last held frame, or its last frame."""
    lo, hi = round(cue.start * FPS), round(cue.end * FPS)
    taps = [c.start for c in timeline if c.kind == "tap" and cue.start <= c.start < cue.end]
    holds = [round(c.start * FPS) for c in timeline
             if c.kind == "hold" and lo <= round(c.start * FPS) < hi]
    if taps:
        target = round((min(taps) - 0.12) * FPS)
    elif holds:
        target = max(holds)
    else:
        target = hi - 1
    shown = [n for n in range(lo, hi) if n in index]
    if not shown:
        raise SystemExit(f"the caption at {cue.start} s is cut from the video")
    before = [n for n in shown if n <= target]
    return before[-1] if before else shown[0]


def released(index, frame):
    """The output time at which `frame` stops being shown."""
    return index[frame][-1] + 1 / FPS


def lines_of(timeline, pieces, voice):
    """Each spoken line: (start, end, text, frame to hold or None, deadline).

    In output order, each one after its caption appears and after the one
    before it is finished; the deadline is when the frame it must be done by
    moves on (a card's is the card's end).
    """
    index = shown_at(pieces)
    wants = []
    for cue in timeline:
        if cue.kind == "say" and cue.voice:
            frame = pause_frame(cue, timeline, index)
            first = min(index[n][0] for n in range(round(cue.start * FPS), frame + 1)
                        if n in index)
            wants.append((first + LEAD, cue.voice, frame, released(index, frame) - TAIL))
    cards = iter(p for p in pieces if p.kind == "card")
    for cue in timeline:
        if cue.kind == "card":
            piece = next(cards)
            if cue.voice:
                end = piece.start + piece.count / FPS
                wants.append((piece.start + CARD_LEAD, cue.voice, None, end - CARD_TAIL))
    out = []
    last = -GAP
    for start, text, frame, deadline in sorted(wants):
        start = max(start, last + GAP)
        end = start + len(voice[text]) / AUDIO_SR
        out.append((start, end, text, frame, deadline))
        last = end
    return out


def stretch(timeline, frame, seconds):
    """Hold `frame` for `seconds` more: lengthen its hold, or split its keep."""
    seconds = math.ceil(seconds * FPS) / FPS
    for c in timeline:
        if c.kind == "hold" and round(c.start * FPS) == frame:
            c.seconds += seconds
            return
    for i, c in enumerate(timeline):
        a, b = round(c.start * FPS), round(c.end * FPS)
        if c.kind == "keep" and a <= frame < b and (frame - a) % c.speed == 0:
            t = frame / FPS
            timeline[i:i + 1] = [keep(c.start, t, c.speed), hold(t, seconds),
                                 keep(t, c.end, c.speed)]
            return
    raise SystemExit(f"frame {frame} is in no keep")


def fit(timeline, voice):
    """The timeline with every line given room: cards lengthened, frames held."""
    timeline = [dataclasses.replace(c) for c in timeline]
    for c in timeline:
        # The holds were set for reading the captions of the silent cut; with
        # a voice they left the screen frozen after she had finished, so each
        # starts at nothing and grows only as far as her line needs.
        if c.kind == "hold":
            c.seconds = 0.0
        if c.kind == "card" and c.voice:
            c.seconds = max(c.seconds, CARD_LEAD + len(voice[c.voice]) / AUDIO_SR + CARD_TAIL)
    for _ in range(300):
        pieces = plan(timeline)
        late = next(((frame, end - deadline) for _, end, _, frame, deadline
                     in lines_of(timeline, pieces, voice)
                     if frame is not None and end > deadline + 1e-6), None)
        if late is None:
            return timeline
        stretch(timeline, *late)
    raise SystemExit("the narration did not fit")


def soundtrack(path, seconds, lines, voice, music, level):
    """The narration over the music, as a 48 kHz stereo WAV.

    The voice is set to -20 dBFS RMS over what she says. The music sits
    `level` dB under that while she speaks and 7 dB higher in the gaps; the
    dip begins 0.45 s before a line and lifts 0.6 s after it, and a gap under
    1.5 s stays dipped, so the bed never pumps between sentences.
    """
    n = round(seconds * AUDIO_SR)
    speech = np.zeros(n)
    spoken = np.zeros(n, bool)
    for start, end, text, _, _ in lines:
        x = voice[text]
        i = round(start * AUDIO_SR)
        speech[i:i + len(x)] += x[: max(0, n - i)]
        spoken[i:i + len(x)] = True
    said = np.sqrt(np.mean(speech[spoken] ** 2)) if spoken.any() else 0
    if said > 0:
        speech *= 10 ** (-20 / 20) / said
    mix = np.stack([speech, speech], axis=1)
    if music:
        sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
        import ambient_music
        if music in ambient_music.PRESETS:
            bed = ambient_music.render(music, seconds)
        else:
            data = subprocess.run(
                ["ffmpeg", "-v", "error", "-i", str(music), "-f", "f32le", "-ac", "2",
                 "-ar", str(AUDIO_SR), "-"], capture_output=True, check=True).stdout
            bed = np.frombuffer(data, "<f4").reshape(-1, 2).astype(np.float64)
            bed = np.tile(bed, (n // len(bed) + 1, 1))[:n]
            bed *= ambient_music.envelope(n, 2.0, 4.0)[:, None]
            bed *= 10 ** (-20 / 20) / np.sqrt(np.mean(bed ** 2))
        bed = np.pad(bed, ((0, max(0, n - len(bed))), (0, 0)))[:n]
        spans = []
        for start, end, *_ in lines:
            a, b = start - 0.45, end + 0.6
            if spans and a - spans[-1][1] < 1.5:
                spans[-1][1] = b
            else:
                spans.append([a, b])
        gain = np.full(n, 10 ** ((level + 7) / 20))
        for a, b in spans:
            gain[max(0, round(a * AUDIO_SR)):round(b * AUDIO_SR)] = 10 ** (level / 20)
        w = round(0.35 * AUDIO_SR)
        c = np.cumsum(np.pad(gain, (w // 2, w - w // 2), mode="edge"))
        gain = (c[w:] - c[:-w]) / w
        mix += bed * gain[:n, None]
    peak = np.abs(mix).max()
    if peak > 0.95:
        mix *= 0.95 / peak
    with wave.open(str(path), "wb") as f:
        f.setnchannels(2)
        f.setsampwidth(2)
        f.setframerate(AUDIO_SR)
        f.writeframes((np.clip(mix, -1, 1) * 32767).astype("<i2").tobytes())


def mux(video, audio, out):
    """The video with the soundtrack at -16 LUFS: measured, then set linearly."""
    target = "I=-16:TP=-1.5:LRA=11"
    report = subprocess.run(
        ["ffmpeg", "-hide_banner", "-i", str(audio), "-af",
         f"loudnorm={target}:print_format=json", "-f", "null", "-"],
        capture_output=True, text=True, check=True).stderr
    # The report is the last {...} in what ffmpeg prints, and more follows it.
    m = json.loads(report[report.rindex("{"):report.rindex("}") + 1])
    measured = (f"measured_I={m['input_i']}:measured_TP={m['input_tp']}:"
                f"measured_LRA={m['input_lra']}:measured_thresh={m['input_thresh']}:"
                f"offset={m['target_offset']}:linear=true")
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-i", str(video), "-i", str(audio),
         "-map", "0:v", "-map", "1:a", "-c:v", "copy",
         "-af", f"loudnorm={target}:{measured}", "-ar", str(AUDIO_SR),
         "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", str(out)],
        check=True)


def prepare(args, out_dir):
    """The timeline as it plays, its pieces, and the spoken lines."""
    timeline = TIMELINE
    voice, lines = {}, []
    if args.voice:
        voice = synthesize(timeline, args, out_dir / "voice" / args.voice)
        timeline = fit(timeline, voice)
    pieces = plan(timeline)
    if voice:
        lines = lines_of(timeline, pieces, voice)
        before = sum(p.count for p in plan(TIMELINE)) / FPS
        after = sum(p.count for p in pieces) / FPS
        print(f"  {len(lines)} lines spoken; {before:.1f} s of video became {after:.1f} s",
              file=sys.stderr)
    return timeline, pieces, voice, lines


# --- the output ---------------------------------------------------------------

def render(path, out_dir, args):
    size = probe(path)[:2]
    timeline, pieces, voice, lines = prepare(args, out_dir)
    layout = Wide(size, timeline, pieces, args.look)
    total = sum(p.count for p in pieces)
    still = out_span(pieces, THUMBNAIL_AT, THUMBNAIL_AT + 4 / FPS)
    if still is None:
        raise SystemExit(f"THUMBNAIL_AT ({THUMBNAIL_AT} s) is cut from the video")
    frames = Frames(path, size, [n for p in pieces for n in p.frames])
    video = out_dir / "jemrec-setup.mp4"
    silent = out_dir / "jemrec-setup.video.mp4" if (lines or args.music) else video
    enc = subprocess.Popen(
        ["ffmpeg", "-v", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
         "-s", f"{SIZE[0]}x{SIZE[1]}", "-r", str(FPS), "-i", "-",
         "-vf", "scale=out_color_matrix=bt709:out_range=tv",
         "-c:v", "libx264", "-preset", "slow", "-crf", "18", "-pix_fmt", "yuv420p",
         "-colorspace", "bt709", "-color_primaries", "bt709", "-color_trc", "bt709",
         "-movflags", "+faststart", "-an", str(silent)],
        stdin=subprocess.PIPE,
    )
    began = time.time()
    done = 0
    for piece in pieces:
        for k in range(piece.count):
            out_t = piece.start + k / FPS
            if piece.kind == "card":
                img = layout.card(piece.name, k / FPS, piece.count / FPS)
            else:
                n = piece.frames[k]
                img = layout.frame(frames.get(n), n, out_t, piece.speed)
            enc.stdin.write(img.tobytes())
            done += 1
            if done % 150 == 0:
                rate = done / (time.time() - began)
                print(f"  {done}/{total} frames, {(total - done) / rate:.0f} s to go",
                      file=sys.stderr)
    frames.close()
    enc.stdin.close()
    enc.wait()
    if silent != video:
        audio = out_dir / "jemrec-setup.wav"
        soundtrack(audio, total / FPS, lines, voice, args.music, args.music_level)
        mux(silent, audio, video)
        silent.unlink()
    write_srt(out_dir / "jemrec-setup.srt", timeline, pieces, lines)
    thumbnail(video, out_dir / "jemrec-setup.jpg", still[0])
    print(f"{video} ({total / FPS:.1f} s)")


def thumbnail(video, path, t):
    """A 1280x720 still of the video at output time t - the site shows it in
    place of the player until someone presses play."""
    subprocess.run(
        ["ffmpeg", "-v", "error", "-y", "-ss", f"{t + 0.5:.3f}", "-i", str(video),
         "-frames:v", "1", "-vf", "scale=1280:720:flags=lanczos", "-q:v", "3", str(path)],
        check=True)


def write_srt(path, timeline, pieces, lines):
    """What she says, when she says it; without a voice, the captions."""
    entries = []
    if lines:
        for start, end, text, _, _ in lines:
            entries.append((start, end, "\n".join(srt_lines(text))))
    else:
        for piece in pieces:
            if piece.kind == "card":
                title, card_lines, _ = CARDS[piece.name]
                entries.append((piece.start, piece.start + piece.count / FPS,
                                "\n".join([title] + card_lines)))
        steps = [c for c in timeline if c.kind == "step"]
        named = set()
        for cue in timeline:
            if cue.kind != "say":
                continue
            span = out_span(pieces, cue.start, cue.end)
            if span is None:
                continue
            text = cue.text
            for i, s in enumerate(steps):
                if s.start <= cue.start <= s.end and i not in named:
                    named.add(i)
                    text = f"Step {i + 1}: {s.text}\n{text}"
            entries.append((span[0], span[1], text))
    entries.sort()

    def stamp(s):
        ms = round(s * 1000)
        return f"{ms // 3600000:02d}:{ms // 60000 % 60:02d}:{ms // 1000 % 60:02d},{ms % 1000:03d}"

    path.write_text("".join(
        f"{i}\n{stamp(a)} --> {stamp(b)}\n{text}\n\n"
        for i, (a, b, text) in enumerate(entries, 1)
    ))


def srt_lines(text, width=42):
    """A subtitle broken into lines of at most `width` characters."""
    out, line = [], ""
    for word in text.split():
        if line and len(line) + 1 + len(word) > width:
            out.append(line)
            line = word
        else:
            line = f"{line} {word}".strip()
    return out + [line]


def stills(path, out_dir, args):
    """The composed frame at the middle of every caption and zoom, just after
    every tap, and on every hold - for checking without an encode."""
    size = probe(path)[:2]
    timeline, pieces, _, _ = prepare(args, out_dir.parent)
    layout = Wide(size, timeline, pieces, args.look)
    moments = []
    for cue in timeline:
        if cue.kind in ("say", "zoom"):
            moments.append((cue.kind, (cue.start + cue.end) / 2))
        elif cue.kind == "tap":
            moments.append(("tap", cue.start))
        elif cue.kind == "hold":
            moments.append(("hold", cue.start))
    out_dir.mkdir(parents=True, exist_ok=True)
    for i, (kind, t) in enumerate(sorted(moments, key=lambda m: m[1])):
        n = round(t * FPS)
        out_t = None
        for piece in pieces:
            # A sped-up keep has only every few frames: take the nearest.
            if piece.kind == "src" and piece.count and piece.frames[0] <= n <= piece.frames[-1]:
                k = min(range(piece.count), key=lambda i: abs(piece.frames[i] - n))
                n = piece.frames[k]
                out_t = piece.start + k / FPS + (0.15 if kind == "tap" else 0)
                speed = piece.speed
                break
        if out_t is None:
            print(f"  {kind} at {t:.2f} s is cut from the video", file=sys.stderr)
            continue
        img = layout.frame(raw_frame(path, n / FPS, size), n, out_t, speed)
        img.save(out_dir / f"{i:03d}-{t:07.2f}-{kind}.png")
    for name in {c.text for c in timeline if c.kind == "card"}:
        layout.card(name, 2.0, 4.0).save(out_dir / f"card-{name}.png")
    print(out_dir)


def sheet(path, out_dir, start, end, every, tw=240):
    """Raw frames from start to end, labelled with their time, 8 to a row,
    with a faint line every 200 screen pixels for reading positions off."""
    size = probe(path)[:2]
    th = round(tw * size[1] / size[0])
    per = tw / SCREEN[0]
    times = []
    t = start
    while t <= end + 1e-9:
        times.append(t)
        t += every
    cols = 8
    rows = math.ceil(len(times) / cols)
    img = Image.new("RGB", (cols * (tw + 8) + 8, rows * (th + 40) + 8), "#222222")
    draw = ImageDraw.Draw(img)
    for i, t in enumerate(times):
        frame = raw_frame(path, t, size).resize((tw, th), Image.BILINEAR)
        x, y = 8 + (i % cols) * (tw + 8), 8 + (i // cols) * (th + 40)
        img.paste(frame, (x, y + 32))
        draw.text((x, y), f"{t:.2f} s", font=font(26, 600), fill="#FFFFFF")
        for gx in range(200, SCREEN[0], 200):
            draw.line((x + gx * per, y + 32, x + gx * per, y + 32 + th), fill="#FF0050", width=1)
        for gy in range(200, SCREEN[1], 200):
            draw.line((x, y + 32 + gy * per, x + tw, y + 32 + gy * per), fill="#FF0050", width=1)
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"sheet-{start:07.2f}-{end:07.2f}.png"
    img.save(out)
    print(out)


def grid(path, out_dir, t):
    """One raw frame, a line every 100 screen pixels, labelled."""
    size = probe(path)[:2]
    k = size[0] / SCREEN[0]
    img = raw_frame(path, t, size).convert("RGBA")
    layer = Image.new("RGBA", img.size, (0, 0, 0, 0))
    draw = ImageDraw.Draw(layer)
    f = font(22, 600)
    for x in range(0, SCREEN[0], 100):
        draw.line((x * k, 0, x * k, img.height), fill=(255, 0, 80, 110), width=1)
        draw.text((x * k + 3, 3), str(x), font=f, fill=(255, 0, 80, 230))
    for y in range(0, SCREEN[1], 100):
        draw.line((0, y * k, img.width, y * k), fill=(255, 0, 80, 110), width=1)
        draw.text((3, y * k + 3), str(y), font=f, fill=(255, 0, 80, 230))
    out_dir.mkdir(parents=True, exist_ok=True)
    out = out_dir / f"frame-{t:07.2f}.png"
    Image.alpha_composite(img, layer).convert("RGB").save(out)
    print(out)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("recording", type=pathlib.Path)
    parser.add_argument("--out", type=pathlib.Path)
    parser.add_argument("--look", choices=LOOKS, default="light")
    parser.add_argument("--stills", action="store_true")
    parser.add_argument("--sheet", nargs=3, type=float, metavar=("START", "END", "EVERY"))
    parser.add_argument("--thumb", type=int, default=240, help="--sheet's frame width")
    parser.add_argument("--frame", type=float, metavar="T")
    parser.add_argument("--voice", metavar="NAME", help="a Kokoro voice, e.g. af_heart")
    parser.add_argument("--kokoro", type=pathlib.Path, metavar="DIR",
                        help="where kokoro-v1.0.onnx and voices-v1.0.bin are")
    parser.add_argument("--tts-python", type=pathlib.Path, metavar="PATH",
                        help="a Python with kokoro-onnx installed")
    parser.add_argument("--espeak-data", type=pathlib.Path, metavar="DIR",
                        help="espeak-ng-data at a path under 160 characters")
    parser.add_argument("--music", metavar="PRESET|FILE",
                        help="an ambient_music.py preset, or an audio file to loop")
    parser.add_argument("--music-level", type=float, default=-21,
                        help="dB under the voice while she speaks")
    args = parser.parse_args()
    out = args.out or args.recording.parent
    out.mkdir(parents=True, exist_ok=True)
    if args.sheet:
        sheet(args.recording, out / "sheets", *args.sheet, tw=args.thumb)
    elif args.frame is not None:
        grid(args.recording, out / "frames", args.frame)
    elif args.stills:
        stills(args.recording, out / "stills", args)
    else:
        if not TIMELINE:
            raise SystemExit("TIMELINE is empty - fill it from --sheet and --frame first")
        render(args.recording, out, args)


if __name__ == "__main__":
    main()
