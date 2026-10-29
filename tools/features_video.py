"""Make the features video: JemRec's own screens on a pocket recorder, to music.

Out comes a 1920x1080 MP4 of 1:51 - seven chapters, a narrator, a synthwave
track - with its .srt, a 1280x720 thumbnail and the YouTube title, description
and chapters. Published 2026-10-29 as https://youtu.be/v5JV6KUUj_I.

WHY A SCRIPT. As with setup_video.py: the screens change, and the cheap fix
for a video that shows an old screen is to run this again. Nothing is filmed.
The phone screens are docs/screenshots/<look>/dark (tools/shoot.py, the debug
build's invented people), so a new set of screenshots is a new video.

THE LOOK. The app's Deck look as hardware, the user's pick of four: brushed
metal, the phone in a slot like a tape, an LCD in Doto with the chapter, two VU
meters and a row of transport keys. FF goes down through every cut, a phone
drops into the slot at each chapter, and EJECT lifts it out for the end card.
The last chapter, "Three looks", turns the slot into a changer: Memo, Deck and
Multitrack drop in as she names them. That chapter is there because new users
open JemRec in Memo, not in the Deck screens the video shows.

THE MUSIC. "Futuristic" by JMHBM, from the Free Music Archive, licensed CC BY
4.0: the credit is on the end card and in the description this writes. Fetch
it from https://freemusicarchive.org/track/futuristic-1/stream/ (the page's
download link returns a web page, not the file); it is not in this repository.
The track is 125.00 BPM with its first kick at 0.062 s. A first measurement on
256-sample hops said 0.023 s, and every cut landed 39 ms ahead of the kick; on
64-sample hops (2.9 ms) --beats reads 0.060 s, the same within a hop
(2026-10-29).

EVERY CUT ON A BAR. Each section takes whole bars of the track and grows by
one when its words need it: she starts LEAD after a cut and is done TAIL
before the next, so the music comes up through every cut. A phrase that swaps
the phone or marks a row starts just after the half bar the change lands on:
a whole bar left up to 1.6 s of silence before a name. Each chapter is at
least 6 bars (11.5 s) because YouTube shows chapters only when every one is
at least 10 s; the first starts at 0:00 and takes the title card with it.

THE NARRATOR AND THE MIX. Kokoro's af_heart, the setup video's voice, through
setup_video.py's own Kokoro job. Everything she says is a claim the README
makes, in its words where they fit. The mix is the setup video's: her voice at
-20 dBFS RMS over what she says, the music 21 dB under that while she speaks,
dipping 0.45 s before a line and lifting 0.6 s after it, the whole at -16 LUFS.
Two differences: between lines the music comes up 9 dB, not 7, and a gap
under 1.5 s stays dipped only if no cut falls in it.

THE METERS READ THE SOUNDTRACK. MUSIC is the track as heard, after the dip, and
VOICE is her: RMS per frame with about 300 ms ballistics. One pair of meters on
the mix sat at rest whenever the music played alone, since it is always 9 to
21 dB under her. Each meter has its own trim, set from stills (2026-10-29):
VOICE reads 0 VU at -15 dBFS, where her louder words peak (0 VU at her -20
average pinned the needle); MUSIC reads 0 VU at its RMS between lines, where
it swings about -5 to +3 with the beat, and falls about 16 dB when she speaks.

    python3 tools/features_video.py TRACK.mp3 OUTDIR \\
        --kokoro MODELS --tts-python VENV/bin/python --espeak-data SHORT_DIR
                                                     the video and the rest:
                                                     MODELS, VENV and SHORT_DIR as
                                                     for setup_video.py --voice
    ... --plan-only                                  every section's bars and
                                                     where each line falls
    ... --stills                                     a still at every change and
                                                     in every section, no encode
    python3 tools/features_video.py --beats TRACK.mp3
                                                     the tempo and first kick of
                                                     another track

Needs ffmpeg, Pillow and numpy. Copy the thumbnail over docs/video/features.jpg
after a new cut; the site shows it in place of the player.
"""
import argparse
import json
import math
import pathlib
import subprocess
import sys
import wave

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = pathlib.Path(__file__).resolve().parent.parent
SHOTS = ROOT / "docs" / "screenshots"
FONTS = ROOT / "docs" / "fonts"
sys.path.insert(0, str(ROOT / "tools"))
import setup_video as sv  # noqa: E402  (the voice and the loudness pass)

W, H, FPS = 1920, 1080, 30
SR = sv.AUDIO_SR
BPM, FIRST_KICK = 125.0, 0.062
BEAT = 60 / BPM
BAR = 4 * BEAT
LEAD, TAIL = 0.7, 1.0            # her words after a cut / before the next
END_TAIL = 1.5 * BEAT + 0.4      # the end card fades over its last beat and a half
LEVEL, GAP_UP = -21.0, 9.0       # the music under her, and between her lines
VOICE = "af_heart"
VIDEO_URL = "https://youtu.be/v5JV6KUUj_I"
TRACK_URL = "https://freemusicarchive.org/music/beat-mekanik/single/futuristic-1"


def bar(n):
    return FIRST_KICK + n * BAR


# A phrase: (key, words, change). A change is ("look", name) - another phone
# drops into the slot - or ("mark", box) - a box lands on that row, in the
# screenshot's own pixels (1256 x 2808).
SECTIONS = [
    dict(key="title", min=3, phrases=[
        ("title", "This is JemRec, a call recorder for Android.", None)]),
    dict(key="record", min=6, tape="home", lines=["BOTH SIDES", "OF EVERY CALL"],
         chapter="Both sides of every call",
         caption="Every call, or only the ones you tap Record on - and each plays "
                 "as its waveform.",
         phrases=[
             ("r1", "It records both sides of every call, with no root and no computer.",
              ("mark", (50, 165, 1030, 400))),
             ("r2", "Every call, or only the ones you tap Record on.", None),
             ("r3", "Each one plays as its waveform, so you can see where people talk.",
              ("mark", (25, 1290, 1231, 1975)))]),
    dict(key="answer", min=6, tape="answering", lines=["ANSWERING", "MACHINE"],
         chapter="Answering machine",
         caption="Answers the calls you don't take, plays your greeting and keeps "
                 "the message.",
         phrases=[
             ("a1", "Switch on the answering machine, and the calls you don't take are "
                    "answered: it plays your greeting, and keeps the message.", None),
             ("a2", "You can still take the call from its notification while they speak.",
              None)]),
    dict(key="calls", min=6, tape="calls", lines=["SEE EVERY", "CALL"],
         chapter="Every call, in the Calls tab",
         caption="The Calls tab is your call log: each call with its recording, "
                 "or why there is none.",
         phrases=[
             ("c1", "The Calls tab is your call log: each call with its recording, or, "
                    "if there is none, the reason why.", None),
             ("c2", "Search either list, or narrow it with a tap.", None)]),
    dict(key="rules", min=6, tape="settings", lines=["YOUR", "RULES"],
         chapter="Whose calls, messengers, exact tracking",
         caption="Whose calls to keep, messenger calls, and recordings that start "
                 "at the answer.",
         phrases=[
             ("u1", "Keep everyone's calls, or only some people's.",
              ("mark", (60, 885, 1196, 1110))),
             ("u2", "WhatsApp, Telegram, Viber and Google Meet calls are recorded too, "
                    "on Android 14 and newer.", ("mark", (60, 1385, 1196, 1640))),
             ("u3", "And exact call tracking starts at the answer, and skips the calls "
                    "nobody answered.", ("mark", (60, 1670, 1196, 2070)))]),
    dict(key="alerts", min=6, tape="selftest", lines=["WHEN THINGS", "GO WRONG"],
         chapter="When something goes wrong",
         caption="A notification after each call, and one when something goes wrong.",
         phrases=[
             ("w1", "A notification after every call, and another if a recording is "
                    "silent, or a call went by unrecorded.", None),
             ("w2", "Copy a report in Diagnostics says how a call was recorded, for an "
                    "issue on GitHub.", ("mark", (55, 1056, 1196, 1450)))]),
    dict(key="banking", min=6, tape="whenoff", lines=["BANKING", "APPS"],
         chapter="Banking apps",
         caption="Turning JemRec off can hide Developer options or switch debugging "
                 "off - and on again.",
         phrases=[
             ("b1", "Some banking apps won't open while debugging is on.", None),
             ("b2", "When you turn JemRec off, it can hide Developer options, or switch "
                    "all debugging off.", ("mark", (55, 750, 1196, 1430))),
             ("b3", "Turn it back on, and it puts back what it switched off.", None)]),
    dict(key="looks", min=6, tape="theme", lines=["THREE", "LOOKS"],
         chapter="Three looks",
         caption="Memo, Deck or Multitrack - each light, dark or as the phone, "
                 "in Settings > Theme.",
         phrases=[
             ("l0", "And it comes in three looks.", None),
             ("l1", "Memo, calm and simple.", ("look", "memo")),
             ("l2", "Deck, a pocket recorder, like this one.", ("look", "deck")),
             ("l3", "And Multitrack, a sound editor, with a colour for each caller.",
              ("look", "multitrack")),
             ("l4", "Each one light or dark, in Settings.", None)]),
    dict(key="end", min=3, phrases=[
        ("end", "Free and open source, on F-Droid and GitHub.", None)]),
]
CHAPTERS = SECTIONS[1:-1]
LINES = [(k, words) for s in SECTIONS for k, words, _ in s["phrases"]]

SLOT_X, SLOT_Y = 400, 100        # the phone's centre x and top, seated
PHONE_H, BEZEL = 880, 12
SCALE = PHONE_H / 2808           # screenshot pixels -> pixels on the phone
LCD, INK, OFF, EDGE = "#0E140B", "#B6F27A", "#18210f", "#565D66"   # Deck dark


# ------------------------------------------------------------------ drawing

def font(path, size, index=0, weight=None):
    f = ImageFont.truetype(str(path), size, index=index)
    if weight is not None:
        f.set_variation_by_axes([weight if a["name"] == b"Weight" else a["default"]
                                 for a in f.get_variation_axes()])
    return f


def futura(size):
    """The meters' print: Futura Bold on a Mac, DejaVu Sans Bold elsewhere."""
    mac = pathlib.Path("/System/Library/Fonts/Supplemental/Futura.ttc")
    if mac.exists():
        return font(mac, size, 2)
    return font("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", size)


def ramp(t, a, b):
    return min(max((t - a) / (b - a), 0.0), 1.0)


def ease(x):
    x = min(max(x, 0.0), 1.0)
    return x * x * (3 - 2 * x)


def rgb(h):
    h = h.lstrip("#")
    return np.array([int(h[i:i + 2], 16) for i in (0, 2, 4)], np.float32)


def vgrad(n, stops):
    pos = np.array([p for p, _ in stops])
    cols = np.array([rgb(c) for _, c in stops])
    x = np.linspace(0, 1, n)
    return np.stack([np.interp(x, pos, cols[:, i]) for i in range(3)], axis=1)


def draw_text(d, xy, text, f, fill, tracking=0):
    x, y = xy
    if not tracking:
        d.text((x, y), text, font=f, fill=fill)
        return
    for ch in text:
        d.text((x, y), ch, font=f, fill=fill)
        x += f.getlength(ch) + tracking


def wrap(text, f, maxw):
    lines, cur = [], ""
    for w in text.split():
        nxt = (cur + " " + w).strip()
        if f.getlength(nxt) > maxw and cur:
            lines.append(cur)
            cur = w
        else:
            cur = nxt
    return lines + [cur]


def rgba(color, mask, alpha=1.0):
    """Float RGBA in one colour, shaped by an L mask."""
    a = np.asarray(mask, np.float32)[..., None] / 255.0 * alpha
    c = np.broadcast_to(rgb(color), a.shape[:2] + (3,))
    return np.concatenate([c, a * 255], axis=2).astype(np.float32)


def glow(arr, r):
    """A premultiplied blur of a float RGBA layer, padded 3r so it fades out
    before its own edges, and that pad: (layer, pad)."""
    pad = int(r * 3)
    pm = np.zeros((arr.shape[0] + 2 * pad, arr.shape[1] + 2 * pad, 4), np.float32)
    pm[pad:pad + arr.shape[0], pad:pad + arr.shape[1]] = arr
    pm[..., :3] *= pm[..., 3:4] / 255.0
    out = [np.asarray(Image.fromarray(np.clip(pm[..., i], 0, 255).astype(np.uint8))
                      .filter(ImageFilter.GaussianBlur(r)), np.float32) for i in range(4)]
    return np.stack(out, axis=2), pad


def over(fr, layer, x, y, alpha=1.0):
    """Lay a float RGBA layer on the frame at (x, y)."""
    x, y = int(round(x)), int(round(y))
    lh, lw = layer.shape[:2]
    x0, y0, x1, y1 = max(x, 0), max(y, 0), min(x + lw, W), min(y + lh, H)
    if x1 <= x0 or y1 <= y0 or alpha <= 0:
        return
    sub = layer[y0 - y:y1 - y, x0 - x:x1 - x]
    a = sub[..., 3:4] / 255.0 * alpha
    fr[y0:y1, x0:x1] = fr[y0:y1, x0:x1] * (1 - a) + sub[..., :3] * a


def add(fr, layer, x, y, gain=1.0):
    """Add light: a premultiplied layer, as glow() makes it, at (x, y)."""
    x, y = int(round(x)), int(round(y))
    lh, lw = layer.shape[:2]
    x0, y0, x1, y1 = max(x, 0), max(y, 0), min(x + lw, W), min(y + lh, H)
    if x1 <= x0 or y1 <= y0 or gain <= 0:
        return
    fr[y0:y1, x0:x1] += layer[y0 - y:y1 - y, x0 - x:x1 - x, :3] * gain


def rrect_mask(w, h, r, ss=3):
    m = Image.new("L", (w * ss, h * ss))
    ImageDraw.Draw(m).rounded_rectangle([0, 0, w * ss - 1, h * ss - 1], r * ss, fill=255)
    return m.resize((w, h), Image.LANCZOS)


def phone(look, screen, radius=58):
    """A dark screenshot in a phone's black bezel, as a float RGBA layer."""
    im = Image.open(SHOTS / look / "dark" / f"{screen}.png").convert("RGB")
    w = round(im.width * PHONE_H / im.height)
    im = im.resize((w, PHONE_H), Image.LANCZOS)
    size = (w + 2 * BEZEL, PHONE_H + 2 * BEZEL)
    body = Image.new("RGBA", size, (6, 6, 8, 255))
    body.putalpha(rrect_mask(*size, radius + BEZEL))
    body.paste(im, (BEZEL, BEZEL), rrect_mask(w, PHONE_H, radius))
    return np.asarray(body, np.float32)


# ------------------------------------------------------------- the timeline

def plan(voice):
    """Each section's (start, end), each phrase's start, and the changes:
    (when it lands, chapter index, "look" or "mark", what)."""
    spans, starts, changes, at = [], {}, [], 0
    half = BAR / 2
    for si, s in enumerate(SECTIONS):
        t0 = bar(at)
        end = None
        for key, _, change in s["phrases"]:
            if end is None:
                start = land = t0 + LEAD
            elif change:
                land = FIRST_KICK + math.ceil((end + 0.25 - FIRST_KICK) / half) * half
                start = land + 0.15
            else:
                start = end + 0.35
            if change:
                changes.append((land, si - 1, *change))
            starts[key] = start
            end = start + len(voice[key]) / SR
        tail = END_TAIL if s["key"] == "end" else TAIL
        n = max(s["min"], math.ceil((end + tail - t0) / BAR - 1e-9))
        spans.append((t0, bar(at + n)))
        at += n
    return spans, starts, changes


# ---------------------------------------------------------------- the sound

def speak(args, cache):
    """Every line as samples at SR, through setup_video.py's Kokoro job."""
    cache.mkdir(parents=True, exist_ok=True)
    job = dict(model=str(args.kokoro / "kokoro-v1.0.onnx"),
               voices=str(args.kokoro / "voices-v1.0.bin"),
               espeak=str(args.espeak_data), voice=VOICE, speed=sv.SPEED, lang=sv.LANG,
               lines=[dict(text=t, out=str(cache / f"{k}.wav")) for k, t in LINES])
    subprocess.run([str(args.tts_python), "-c", sv.TTS], input=json.dumps(job),
                   text=True, check=True)
    return {k: sv.to_audio_rate(*sv.read_wav(cache / f"{k}.wav")) for k, _ in LINES}


def mix(track, voice, starts, cuts, dur, path):
    """Her lines over the track, as a 48 kHz stereo WAV, and the two stems the
    meters read: (speech, music), each as it is in the mix."""
    n = round(dur * SR)
    speech = np.zeros(n)
    spoken = np.zeros(n, bool)
    lines = []
    for k, _ in LINES:
        x = voice[k]
        i = round(starts[k] * SR)
        speech[i:i + len(x)] += x[:max(0, n - i)]
        spoken[i:i + len(x)] = True
        lines.append((starts[k], starts[k] + len(x) / SR))
    speech *= 10 ** (-20 / 20) / np.sqrt(np.mean(speech[spoken] ** 2))
    raw = subprocess.run(["ffmpeg", "-v", "error", "-i", str(track), "-t", f"{dur:.4f}",
                          "-f", "f32le", "-ac", "2", "-ar", str(SR), "-"],
                         capture_output=True, check=True).stdout
    bed = np.frombuffer(raw, "<f4").reshape(-1, 2).astype(np.float64)
    bed = np.pad(bed, ((0, max(0, n - len(bed))), (0, 0)))[:n]
    bed *= np.clip((dur - np.arange(n) / SR) / BAR, 0, 1)[:, None]   # out over a bar
    bed *= 10 ** (-20 / 20) / np.sqrt(np.mean(bed[: round((dur - BAR) * SR)] ** 2))
    spans = []
    for a, b in lines:
        a, b = a - 0.45, b + 0.6
        if spans and a - spans[-1][1] < 1.5 and not any(spans[-1][1] <= c <= a for c in cuts):
            spans[-1][1] = b
        else:
            spans.append([a, b])
    gain = np.full(n, 10 ** ((LEVEL + GAP_UP) / 20))
    for a, b in spans:
        gain[max(0, round(a * SR)):round(b * SR)] = 10 ** (LEVEL / 20)
    w = round(0.35 * SR)
    c = np.cumsum(np.pad(gain, (w // 2, w - w // 2), mode="edge"))
    gain = (c[w:] - c[:-w]) / w
    music = bed * gain[:n, None]
    out = np.stack([speech, speech], 1) + music
    scale = min(1.0, 0.95 / np.abs(out).max())
    out *= scale
    with wave.open(str(path), "wb") as f:
        f.setnchannels(2)
        f.setsampwidth(2)
        f.setframerate(SR)
        f.writeframes((np.clip(out, -1, 1) * 32767).astype("<i2").tobytes())
    return speech * scale, music.mean(1) * scale


def vu_levels(stems, n_frames):
    """Needle positions (0..1 on the scale) per video frame, one per stem."""
    hop = SR // FPS
    out = []
    for x, ref in stems:
        v, pos = 0.0, []
        for i in range(n_frames):
            seg = x[i * hop:(i + 1) * hop]
            rms = np.sqrt(np.mean(seg ** 2)) if len(seg) else 0.0
            v += (rms - v) * (1 - math.exp(-1 / FPS / 0.1))   # ~300 ms to 99 %
            vu = 20 * math.log10(v + 1e-9) - ref
            pos.append(np.interp(vu, [-20, -7, -3, 0, 3], [0, 0.33, 0.55, 0.72, 1.0],
                                 left=-0.04, right=1.03))
        out.append(np.array(pos))
    return out


def measure_beats(track):
    """(BPM, first kick in s) of a track, from the rise of its low end on
    64-sample hops (2.9 ms)."""
    sr = 22050
    x = np.frombuffer(subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(track), "-ac", "1", "-ar", str(sr),
         "-f", "f32le", "-"], capture_output=True, check=True).stdout, np.float32)
    hop = 64
    lp = np.convolve(x, np.ones(32) / 32, mode="same")
    e = np.sqrt((lp[:len(lp) // hop * hop].reshape(-1, hop) ** 2).mean(1))
    rise = np.maximum(np.diff(e, prepend=e[0]), 0)
    fps = sr / hop
    r = rise - rise.mean()
    ac = np.fft.irfft(np.abs(np.fft.rfft(r, 2 * len(r))) ** 2)[:len(r)]
    lo, hi = int(fps * 60 / 160), int(fps * 60 / 100)
    period = (lo + np.argmax(ac[lo:hi])) / fps
    # Refine the tempo: the period whose grid lands on the most rise.
    best = (-1, period, 0.0)
    for p in np.arange(period * 0.99, period * 1.01, period * 0.0001):
        for ph in np.arange(0, p, 1 / fps):
            idx = ((ph + p * np.arange(int((len(rise) / fps - ph) / p))) * fps).astype(int)
            s = rise[idx].sum()
            if s > best[0]:
                best = (s, p, ph)
    _, period, phase = best
    # The kick itself: the median of where each beat's rise peaks, near the grid.
    offs = []
    for k in range(8, int((len(rise) / fps - phase) / period) - 8):
        c = phase + k * period
        a, b = int((c - 0.12) * fps), int((c + 0.12) * fps)
        offs.append((a + np.argmax(rise[a:b])) / fps - c)
    return 60 / period, (phase + np.median(offs)) % period


# ---------------------------------------------------------------- the deck

class Deck:
    """The app's Deck look as hardware, playing the video's own timeline."""

    def __init__(self, spans, changes, levels):
        self.spans, self.levels = spans, levels
        self.dur = spans[-1][1]
        self.cuts = [a for a, _ in spans[1:]]        # where each section begins
        self.swaps = [(t, v) for t, _, kind, v in changes if kind == "look"]
        self.marks = [(t, ch, box) for t, ch, kind, box in changes if kind == "mark"]
        rng = np.random.default_rng(7)
        n = rng.normal(0, 1, (H, W + 200)).astype(np.float32)
        c = np.cumsum(n, axis=1)
        brushed = (c[:, 200:] - c[:, :-200]) / 200 ** 0.5
        base = vgrad(H, [(0, "#3a3e44"), (0.5, "#2b2e33"), (1, "#212428")])
        self.bg = base[:, None, :] + brushed[..., None] * 3.0 + n[:, :W, None] * 1.5
        d = Image.new("RGBA", (W, H))
        dd = ImageDraw.Draw(d)
        for x, y in [(40, 40), (W - 40, 40), (40, H - 40), (W - 40, H - 40)]:
            dd.ellipse([x - 16, y - 16, x + 16, y + 16], fill=(120, 126, 134, 255),
                       outline=(20, 22, 24, 255), width=2)
            dd.line([(x - 10, y - 4), (x + 10, y + 4)], fill=(40, 42, 46, 255), width=4)
        eng = font(FONTS / "share_tech_mono.ttf", 26)
        for dy, col in [(-1, (14, 16, 18, 255)), (1, (90, 96, 104, 255)),
                        (0, (178, 184, 192, 255))]:
            draw_text(dd, (760, 62 + dy), "JEMREC  ·  CALL RECORDER  ·  2-CH", eng, col, 6)
            draw_text(dd, (150, 1020 + dy), "PHONE", eng, col, 6)
        dd.rounded_rectangle([150, 70, 650, 1006], 26, fill=(15, 16, 18, 255))
        dd.rounded_rectangle([150, 70, 650, 1006], 26, outline=(70, 75, 82, 255), width=2)
        self.chrome = np.asarray(d, np.float32)
        # The LCD and its unlit dots.
        self.lcd_box = (760, 110, 1800, 420)
        x0, y0, x1, y1 = self.lcd_box
        lcd = Image.new("RGBA", (x1 - x0, y1 - y0))
        ld = ImageDraw.Draw(lcd)
        ld.rounded_rectangle([0, 0, x1 - x0 - 1, y1 - y0 - 1], 22,
                             fill=tuple(rgb(EDGE).astype(int)) + (255,))
        ld.rounded_rectangle([8, 8, x1 - x0 - 9, y1 - y0 - 9], 16,
                             fill=tuple(rgb(LCD).astype(int)) + (255,))
        for yy in range(26, y1 - y0 - 20, 11):
            for xx in range(24, x1 - x0 - 20, 11):
                ld.ellipse([xx, yy, xx + 4, yy + 4], fill=tuple(rgb(OFF).astype(int)) + (255,))
        self.lcd = np.asarray(lcd, np.float32)
        self.f_lcd = font(FONTS / "doto.ttf", 112, weight=900)
        self.f_lcd_s = font(FONTS / "doto.ttf", 50, weight=900)
        self.f_leg = font(FONTS / "share_tech_mono.ttf", 30)
        self.f_cap = font(FONTS / "share_tech_mono.ttf", 38)
        self.f_small = font(FONTS / "share_tech_mono.ttf", 28)
        self.f_key = font(FONTS / "share_tech_mono.ttf", 28)
        self.big = []                 # each chapter's LCD words, sized to fit
        for words in [s["lines"] for s in CHAPTERS] + [["FREE AND", "OPEN SOURCE"]]:
            size = 112
            fl = font(FONTS / "doto.ttf", size, weight=900)
            while max(fl.getlength(w) for w in words) > 940:
                size -= 4
                fl = font(FONTS / "doto.ttf", size, weight=900)
            self.big.append(fl)
        # The meters, each saying what it reads.
        self.vu_boxes = [(760, 455, 1262, 735), (1298, 455, 1800, 735)]
        self.vu_face = [self.meter(c_ - a, d_ - b, name) for (a, b, c_, d_), name
                        in zip(self.vu_boxes, ["MUSIC", "VOICE"])]
        # The tapes: (in, out, phone, label) - each chapter's own, then the changer's.
        self.tapes = []
        for (a, b), s in zip(spans[1:-1], CHAPTERS):
            outs = [t for t, _ in self.swaps if a < t < b] + [b]
            self.tapes.append((a, outs[0], phone("deck", s["tape"]), None))
            for (t, look), nxt in zip([w for w in self.swaps if a < w[0] < b], outs[1:]):
                self.tapes.append((t, nxt, phone(look, "home"), look.upper()))

    @staticmethod
    def meter(w, h, name):
        f = Image.new("RGBA", (w, h))
        ImageDraw.Draw(f).rounded_rectangle([0, 0, w - 1, h - 1], 14, fill=(18, 19, 21, 255))
        fa = np.asarray(f, np.float32).copy()
        fa[12:h - 12, 12:w - 12, :3] = vgrad(h - 24, [(0, "#f3e9c8"), (1, "#d9c99a")])[:, None, :]
        f = Image.fromarray(fa.astype(np.uint8))
        fd = ImageDraw.Draw(f)
        px, py = w / 2, h + 90
        for k in range(0, 23):
            v = k / 22
            ang = math.radians(-42 + 84 * v)
            r1, r2 = 300, 318 if k % 2 else 332
            col = (196, 40, 32) if v > 0.72 else (40, 36, 30)
            fd.line([(px + math.sin(ang) * r1, py - math.cos(ang) * r1),
                     (px + math.sin(ang) * r2, py - math.cos(ang) * r2)], fill=col, width=3)
        fd.arc([px - 300, py - 300, px + 300, py + 300], -132, -48, fill=(40, 36, 30), width=3)
        fd.arc([px - 300, py - 300, px + 300, py + 300], -71, -48, fill=(196, 40, 32), width=7)
        lab = futura(24)
        for v, s in [(0.0, "-20"), (0.33, "-7"), (0.55, "-3"), (0.72, "0"), (1.0, "+3")]:
            ang = math.radians(-36 + 72 * v)
            fd.text((px + math.sin(ang) * 346, py - math.cos(ang) * 346), s, font=lab,
                    fill=(196, 40, 32) if v >= 0.72 else (40, 36, 30), anchor="mm")
        fd.text((w / 2, h - 70), "VU", font=futura(44), fill=(40, 36, 30), anchor="mm")
        fd.text((40, h - 52), name, font=futura(28), fill=(40, 36, 30))
        return np.asarray(f, np.float32)

    def keys(self, fr, pressed):
        layer = Image.new("RGBA", (1060, 175))
        d = ImageDraw.Draw(layer)
        kw = 166
        for i, nm in enumerate(["REC", "REW", "PLAY", "FF", "STOP", "EJECT"]):
            x = i * (kw + 12)
            down = nm in pressed
            dy = 8 if down else 0
            red = nm == "REC"
            face = (192, 57, 47) if red else ((168, 173, 180) if down else (205, 209, 214))
            edge = (110, 30, 24) if red else (104, 108, 116)
            d.rounded_rectangle([x, 8, x + kw, 128], 10, fill=(14, 15, 17, 255))
            d.rounded_rectangle([x + 4, 12 + dy, x + kw - 4, 120], 8, fill=edge + (255,))
            d.rounded_rectangle([x + 4, 12 + dy, x + kw - 4, 108 - (0 if down else 6)], 8,
                                fill=face + (255,))
            cx, cy = x + kw / 2, 60 + dy
            ink = (255, 255, 255, 255) if red else (36, 38, 42, 255)
            if nm == "REC":
                d.ellipse([cx - 13, cy - 13, cx + 13, cy + 13], fill=ink)
            elif nm == "PLAY":
                d.polygon([(cx - 12, cy - 16), (cx - 12, cy + 16), (cx + 16, cy)], fill=ink)
            elif nm in ("FF", "REW"):
                s = 1 if nm == "FF" else -1
                for o in (-14, 8):
                    d.polygon([(cx + s * o, cy - 14), (cx + s * o, cy + 14),
                               (cx + s * (o + 20), cy)], fill=ink)
            elif nm == "STOP":
                d.rectangle([cx - 13, cy - 13, cx + 13, cy + 13], fill=ink)
            else:
                d.polygon([(cx - 16, cy + 2), (cx + 16, cy + 2), (cx, cy - 18)], fill=ink)
                d.rectangle([cx - 16, cy + 8, cx + 16, cy + 14], fill=ink)
            draw_text(d, (cx - self.f_key.getlength(nm) / 2, 130), nm, self.f_key,
                      (190, 196, 204, 255))
        over(fr, np.asarray(layer, np.float32), 760, 846)

    def lcd_text(self, fr, lines, flicker=1.0):
        """Lit segments: ((x, y), text, font, boxed) in the LCD's own pixels."""
        x0, y0 = self.lcd_box[:2]
        layer = Image.new("L", (self.lcd.shape[1], self.lcd.shape[0]))
        d = ImageDraw.Draw(layer)
        for (x, y), text, f, box in lines:
            if box:
                w = f.getlength(text)
                d.rectangle([x - 10, y - 2, x + w + 10, y + f.size + 8], outline=255, width=3)
            d.text((x, y), text, font=f, fill=255)
        lit = rgba(INK, layer, flicker)
        g, pad = glow(rgba(INK, layer), 10)
        add(fr, g, x0 - pad, y0 - pad, 0.55 * flicker)
        over(fr, lit, x0, y0)

    def section(self, t):
        """-1 for the title, 0.. for a chapter, len(CHAPTERS) for the end card."""
        return sum(t >= c for c in self.cuts) - 1

    def tape_at(self, t):
        """The phone in or near the slot: (layer, top, label), or (None, 0, None)."""
        for a, b, ph, label in self.tapes:
            if a - 0.05 <= t < b:
                k = ease(ramp(t, a, a + 0.45))                  # drops in
                out = ease(ramp(t, b - 0.4, b - 0.02))          # lifts out
                return ph, SLOT_Y - (1 - k) * 1000 - out * 1000, label
        return None, 0, None

    def mark(self, fr, t, sec, px, py):
        """The box on the row she is talking about, drawn on the phone where it
        is, so it leaves with it."""
        live = [(a, box) for a, ch, box in self.marks if ch == sec and a <= t + 0.001]
        if not live:
            return
        a, (x0, y0, x1, y1) = live[-1]
        k = ease(ramp(t, a, a + 0.25))
        x0, x1 = px + BEZEL + x0 * SCALE, px + BEZEL + x1 * SCALE
        y0, y1 = py + BEZEL + y0 * SCALE, py + BEZEL + y1 * SCALE
        pad = 30
        w, h = int(x1 - x0) + 2 * pad, int(y1 - y0) + 2 * pad
        m = Image.new("L", (w * 3, h * 3))
        ImageDraw.Draw(m).rounded_rectangle([pad * 3, pad * 3, (w - pad) * 3, (h - pad) * 3],
                                            14 * 3, outline=255, width=4 * 3)
        m = m.resize((w, h), Image.LANCZOS)
        line = np.asarray(m, np.float32)[..., None] / 255
        halo = np.asarray(m.filter(ImageFilter.GaussianBlur(9)), np.float32)[..., None] / 255
        ox, oy = int(round(x0 - pad)), int(round(y0 - pad))
        # A phone lifting out takes its mark above the frame: a negative end
        # would count from the bottom, so clip, and draw nothing once it's gone.
        ya, yb, xa, xb = max(oy, 0), min(oy + h, H), max(ox, 0), min(ox + w, W)
        if yb <= ya or xb <= xa:
            return
        ink = rgb(INK)
        la = line[ya - oy:yb - oy, xa - ox:xb - ox] * k
        ha = halo[ya - oy:yb - oy, xa - ox:xb - ox] * k
        fr[ya:yb, xa:xb] = fr[ya:yb, xa:xb] * (1 - la) + ink * la + ink * ha * 1.4

    def frame(self, t, still=False):
        """The frame at t s. `still` is the thumbnail's: the title's marquee
        held on its first words and the clock at 0:00."""
        fr = self.bg.copy()
        over(fr, self.chrome, 0, 0)
        ph, y, label = self.tape_at(t)
        if ph is not None:
            over(fr, ph, SLOT_X - ph.shape[1] / 2, y)
        over(fr, self.lcd, *self.lcd_box[:2])
        sec = self.section(t)
        last = len(CHAPTERS)
        near = [c for c in self.cuts[:-1] if c - 0.4 <= t < c + 0.2]
        ff = bool(near)
        eject = (any(w - 0.4 <= t < w + 0.15 for w, _ in self.swaps)
                 or self.cuts[-1] - 0.4 <= t < self.cuts[-1] + 0.4)
        clock = "0:00" if still else f"{int(t) // 60}:{int(t) % 60:02d}"
        if ff:
            n = self.cuts.index(near[0]) + 1
            flick = 0.55 + 0.45 * (math.sin(t * 90) > -0.2)
            self.lcd_text(fr, [((40, 34), "FF", self.f_leg, True),
                               ((40, 120), f">> CH 0{n}", self.f_lcd, False)], flick)
        elif sec < 0:
            marquee = "EVERYTHING IT DOES  ·  A CALL RECORDER FOR ANDROID  ·  "
            off = int(t * 9) % len(marquee)
            shown = "EVERYTHING IT DOES" if still else (marquee * 2)[off:off + 30]
            self.lcd_text(fr, [((40, 34), "PLAY", self.f_leg, True),
                               ((160, 34), clock, self.f_leg, False),
                               ((40, 82), "JEMREC", self.f_lcd, False),
                               ((40, 212), shown, self.f_lcd_s, False)])
        else:
            q = t - self.cuts[sec]
            words = CHAPTERS[sec]["lines"] if sec < last else ["FREE AND", "OPEN SOURCE"]
            fl = self.big[sec]
            state = "EJECT" if eject else ("PLAY" if sec < last else "STOP")
            if sec < last:
                legends = [((40, 34), f"CH 0{sec + 1}", self.f_leg, True),
                           ((200, 34), state, self.f_leg, True),
                           ((360, 34), clock, self.f_leg, False)]
                if label and not eject:
                    legends.append(((480, 34), label, self.f_leg, True))
            else:
                legends = [((40, 34), state, self.f_leg, True),
                           ((200, 34), clock, self.f_leg, False)]
            self.lcd_text(fr, legends + [
                ((40, 84), words[0][:int(ramp(q, 0.1, 0.6) * len(words[0]))], fl, False),
                ((40, 84 + fl.size * 0.84), words[1][:int(ramp(q, 0.5, 0.95) * len(words[1]))],
                 fl, False)])
        # The needles.
        i = min(int(round(t * FPS)), len(self.levels[0]) - 1)
        for ch, ((a, b, c_, d_), face) in enumerate(zip(self.vu_boxes, self.vu_face)):
            over(fr, face, a, b)
            ang = math.radians(-42 + 84 * (self.levels[ch][i] if not still else (0.62, 0.5)[ch]))
            w, h = c_ - a, d_ - b
            nl = Image.new("RGBA", (w, h))
            ImageDraw.Draw(nl).line(
                [(w / 2 + math.sin(ang) * 120, h + 90 - math.cos(ang) * 120),
                 (w / 2 + math.sin(ang) * 330, h + 90 - math.cos(ang) * 330)],
                fill=(20, 20, 22, 255), width=4)
            over(fr, np.asarray(nl, np.float32), a, b)
            fr[d_ - 22:d_ - 12, a + 12:c_ - 12] = rgb("#1a1b1e")
        # The caption under the meters.
        layer = Image.new("RGBA", (1060, 100))
        d = ImageDraw.Draw(layer)
        if sec < 0:
            k = 1 - ease(ramp(t, self.cuts[0] - 0.3, self.cuts[0]))
            d.text((0, 0), "A call recorder for Android.", font=self.f_cap,
                   fill=(222, 226, 231, 255))
        elif sec < last:
            q = t - self.cuts[sec]
            k = ease(ramp(q, 0.8, 1.2)) * (1 - ease(ramp(t, self.cuts[sec + 1] - 0.3,
                                                          self.cuts[sec + 1])))
            for j, ln in enumerate(wrap(CHAPTERS[sec]["caption"], self.f_cap, 1040)[:2]):
                d.text((0, j * 48), ln, font=self.f_cap, fill=(222, 226, 231, 255))
        else:
            k = ease(ramp(t - self.cuts[-1], 0.6, 1.0))
            d.text((0, 0), "On F-Droid and GitHub.", font=self.f_cap, fill=(222, 226, 231, 255))
            d.text((0, 52), "Music: “Futuristic” by JMHBM · CC BY 4.0",
                   font=self.f_small, fill=(170, 176, 184, 255))
        over(fr, np.asarray(layer, np.float32), 760, 752, k)
        self.keys(fr, {"FF"} if ff else {"EJECT"} if eject else
                  {"STOP"} if sec == last else {"PLAY"} if t > 0.3 else set())
        fade = 1 - ease(ramp(t, self.dur - 1.5 * BEAT, self.dur))
        fr *= fade
        # The mark goes on last, at the fade's level (marks end before it starts).
        if 0 <= sec < last and ph is not None:
            before = fr.copy()
            self.mark(fr, t, sec, SLOT_X - ph.shape[1] / 2, y)
            fr = before + (fr - before) * fade
        return fr


def to_img(fr):
    return Image.fromarray(np.clip(fr, 0, 255).astype(np.uint8))


# ------------------------------------------------------------------ outputs

def write_srt(path, lines):
    def ts(s):
        ms = int(round(s * 1000))
        return (f"{ms // 3600000:02d}:{ms // 60000 % 60:02d}:"
                f"{ms // 1000 % 60:02d},{ms % 1000:03d}")
    measure = font(FONTS / "share_tech_mono.ttf", 20)
    out = []
    for i, (a, b, text) in enumerate(lines, 1):
        rows = wrap(text, measure, 42 * measure.getlength("n"))
        out.append(f"{i}\n{ts(a)} --> {ts(b)}\n" + "\n".join(rows) + "\n")
    path.write_text("\n".join(out))


def write_description(path, chapters):
    stamps = "".join(f"{int(a) // 60}:{int(a) % 60:02d} {name}\n" for a, name in chapters)
    path.write_text(f"""Suggested title:
JemRec: a call recorder for Android, no root - everything it does in 2 minutes

Description:

JemRec records both sides of your calls on Android: phone calls, and WhatsApp, Telegram, Viber and Google Meet calls on Android 14 or newer. No root, no computer, no second app - the phone sets itself up. Nothing leaves your phone: no account, no cloud, no analytics. Free and open source (Apache-2.0).

{stamps}
Get JemRec
F-Droid: https://f-droid.org/packages/com.jemcik.jemrec/
GitHub: https://github.com/jemcik/JemRec
Website and manual: https://jemcik.github.io/JemRec/
How to set it up: https://www.youtube.com/watch?v=rt25pZd8R9M

The screens show the app's demo data; the people in them are invented.

Music: "Futuristic" by JMHBM (Beat Mekanik), from the Free Music Archive - {TRACK_URL} - licensed under CC BY 4.0: https://creativecommons.org/licenses/by/4.0/
Narration: Kokoro text-to-speech, voice {VOICE}.
""")


def main():
    p = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    p.add_argument("track", type=pathlib.Path, help='"Futuristic", as an MP3')
    p.add_argument("outdir", type=pathlib.Path, nargs="?")
    p.add_argument("--kokoro", type=pathlib.Path,
                   help="where kokoro-v1.0.onnx and voices-v1.0.bin are")
    p.add_argument("--tts-python", type=pathlib.Path, help="a Python with kokoro-onnx")
    p.add_argument("--espeak-data", type=pathlib.Path,
                   help="espeak-ng-data at a short path (see setup_video.py)")
    p.add_argument("--plan-only", action="store_true")
    p.add_argument("--stills", action="store_true")
    p.add_argument("--beats", action="store_true", help="measure the track and stop")
    args = p.parse_args()
    if args.beats:
        bpm, kick = measure_beats(args.track)
        print(f"{bpm:.2f} BPM, first kick at {kick:.3f} s")
        return
    if not (args.outdir and args.kokoro and args.tts_python and args.espeak_data):
        p.error("OUTDIR, --kokoro, --tts-python and --espeak-data are needed")
    out = args.outdir
    out.mkdir(parents=True, exist_ok=True)
    voice = speak(args, out / "voice")
    spans, starts, changes = plan(voice)
    dur = spans[-1][1]
    for s, (a, b) in zip(SECTIONS, spans):
        print(f"{s['key']:8s} {a:6.2f}-{b:6.2f} s  {round((b - a) / BAR)} bars")
        for k, _, ch in s["phrases"]:
            said = starts[k] + len(voice[k]) / SR
            print(f"           {k:5s} {starts[k]:6.2f}-{said:6.2f} s" + (f"  {ch[0]}" if ch else ""))
    print(f"length {dur:.2f} s")
    if args.plan_only:
        return
    # YouTube's chapters: the first must start at 0:00, so it takes the title;
    # the last runs on through the end card.
    write_description(out / "jemrec-features.txt",
                      [(0.0, CHAPTERS[0]["chapter"])] +
                      [(a, s["chapter"]) for (a, _), s in zip(spans[2:-1], CHAPTERS[1:])])
    write_srt(out / "jemrec-features.srt",
              [(starts[k], starts[k] + len(voice[k]) / SR, words) for k, words in LINES])
    cuts = [a for a, _ in spans[1:]]
    wav = out / "jemrec-features.wav"
    speech, music = mix(args.track, voice, starts, cuts, dur, wav)
    # MUSIC's trim: the music alone, from the title's line lifting off it to the
    # first cut (4.4-5.6 s on this timeline).
    said = starts["title"] + len(voice["title"]) / SR
    a, b = round(said + 0.9, 1), round(cuts[0] - 0.2, 1)
    alone = 20 * np.log10(np.sqrt(np.mean(music[round(a * SR):round(b * SR)] ** 2)))
    n = int(round(dur * FPS))
    deck = Deck(spans, changes, vu_levels([(music, alone), (speech, -15.0)], n))
    if args.stills:
        d = out / "stills"
        d.mkdir(exist_ok=True)
        for t in sorted([w + 0.6 for w, *_ in changes] + [a + 3.0 for a, _ in spans]):
            to_img(deck.frame(t)).convert("RGB").save(d / f"t{t:06.2f}.jpg", quality=85)
        print("stills in", d)
        return
    # The thumbnail: the title's LCD, with the first chapter's phone in the slot.
    th = deck.frame(spans[0][1] - 1.0, still=True)
    ph = deck.tapes[0][2]
    over(th, ph, SLOT_X - ph.shape[1] / 2, SLOT_Y)
    to_img(th).convert("RGB").resize((1280, 720), Image.LANCZOS).save(
        out / "jemrec-features.jpg", quality=92)
    video = out / "jemrec-features.video.mp4"
    enc = subprocess.Popen(
        ["ffmpeg", "-loglevel", "error", "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
         "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-", "-c:v", "libx264", "-preset", "slow",
         "-crf", "17", "-profile:v", "high", "-g", "15", "-bf", "2", "-pix_fmt", "yuv420p",
         str(video)], stdin=subprocess.PIPE)
    for i in range(n):
        enc.stdin.write(np.clip(deck.frame(i / FPS), 0, 255).astype(np.uint8).tobytes())
        if i % 300 == 0:
            print(f"  frame {i}/{n}", file=sys.stderr, flush=True)
    enc.stdin.close()
    if enc.wait():
        raise SystemExit("ffmpeg failed")
    sv.mux(video, wav, out / "jemrec-features.mp4", tp=-2.5, bitrate="320k")
    video.unlink()
    wav.unlink()
    print("wrote", out / "jemrec-features.mp4")


if __name__ == "__main__":
    main()
