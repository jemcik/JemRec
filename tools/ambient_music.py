"""Original ambient music for JemRec's videos, synthesized from nothing.

WHY SYNTHESIZED. A bought or downloaded track comes with a licence to read and
a Content ID claim to dispute; this has neither, because every sample of it is
computed here - sine waves, envelopes and a reverb made of noise. It is also
exactly as long as the video needs, and the same seed gives the same track.

WHAT IT AIMS AT. Music under a narrator, so it must stay out of her way. The
guidance found on 2026-10-24 agrees on the shape: no drums and no vocals, a
level 18 to 24 dB under the speech (WCAG 1.4.7 asks for at least 20), and the
dip under each line starting before the first word, not after it. The style is
the user's pick, Moby's ambient work: slow, pretty chords with no drums,
reverbed piano over drifting string pads, a faint noisy texture, broad loops
with little variation. None of his music is used or imitated note for note -
the chords are the plainest in the language and the melody is drawn from a
seeded random walk over them.

WHERE THE VOICE GOES. A female narrator's fundamental sits around 160-260 Hz
and what makes words intelligible around 1-4 kHz. The pads stop by 2.4 kHz
and the finished mix is dipped a few dB from 1 to 4 kHz, so the music fills the
room around her rather than the frequencies she speaks in.

    python3 tools/ambient_music.py --preset porch --seconds 48 --out porch.wav
    python3 tools/ambient_music.py --samples DIR      one of each preset

Needs numpy.
"""
import argparse
import pathlib
import wave

import numpy as np

SR = 48000

NAMES = {"C": 0, "C#": 1, "Db": 1, "D": 2, "D#": 3, "Eb": 3, "E": 4, "F": 5,
         "F#": 6, "Gb": 6, "G": 7, "G#": 8, "Ab": 8, "A": 9, "A#": 10, "Bb": 10,
         "B": 11}


def hz(name):
    """'D3' -> 146.8: equal temperament from A4 = 440."""
    midi = 12 * (int(name[-1]) + 1) + NAMES[name[:-1]]
    return 440.0 * 2 ** ((midi - 69) / 12)


# A chord: the pad's voicing and the bass note under it. The keys play over
# the pad's tones, moved up into their own octaves.
PRESETS = {
    # Warm and plain: piano over strings, in D major. The closest to the brief.
    "porch": dict(
        title="Porch light", bpm=66, keys="piano", pad=0.8, air=1.0, room=5.0,
        density=0.55, delay=False,
        chords=[
            ("D3 A3 C#4 E4 F#4", "D2"),     # Dmaj9
            ("B2 F#3 A3 D4 E4", "B1"),      # Bm11
            ("G2 D3 F#3 A3 B3", "G1"),      # Gmaj9
            ("A2 E3 B3 C#4 E4", "A1"),      # A add9
        ]),
    # Darker and slower, around A minor: a felt piano heard from another room,
    # over a low drone. The user's pick on 2026-10-24, after an FM electric
    # piano sounded sharp and a soft lead tone, even unstruck, took too much
    # attention - so the piano is rare, low, dark above 1.4 kHz and mostly
    # reverb, and the narration owns the foreground.
    "night": dict(
        title="Night drive", bpm=58, keys="piano", pad=0.6, air=1.8, room=6.0,
        density=0.28, delay=False, keys_level=0.25, keys_room=0.9,
        keys_dark=1400, keys_range=(180, 620),
        chords=[
            ("A2 E3 G3 B3 C4", "A1"),       # Am9
            ("F2 C3 E3 A3 B3", "F1"),       # Fmaj7#11
            ("C3 G3 B3 D4 E4", "C2"),       # Cmaj9
            ("G2 D3 E3 A3 B3", "G1"),       # G6add9
        ]),
    # Brighter, with more motion: soft glass bells and an echo, in F.
    "glass": dict(
        title="Glass", bpm=72, keys="bell", pad=1.0, air=0.7, room=4.5,
        density=0.75, delay=True,
        chords=[
            ("F2 C3 E3 A3 C4", "F1"),       # Fmaj7
            ("D3 A3 C4 E4 F4", "D2"),       # Dm9
            ("Bb2 F3 A3 C4 D4", "Bb1"),     # Bbmaj9
            ("C3 G3 D4 E4", "C2"),          # C add9
        ]),
}


def envelope(n, attack, release):
    """Raised-cosine attack and release, in seconds, over n samples."""
    e = np.ones(n)
    a, r = min(n, int(attack * SR)), min(n, int(release * SR))
    if a:
        e[:a] = 0.5 - 0.5 * np.cos(np.linspace(0, np.pi, a))
    if r:
        e[n - r:] *= 0.5 + 0.5 * np.cos(np.linspace(0, np.pi, r))
    return e


def pan(mono, position):
    """Equal-power pan: 0 is left, 1 is right."""
    return np.stack([mono * np.cos(position * np.pi / 2),
                     mono * np.sin(position * np.pi / 2)], axis=1)


def pad_note(freq, seconds, rng, bright):
    """A string-like note: three detuned voices, warm - partials falling off
    faster than a saw's and stopping by 2.4 kHz, because a full saw buzzed."""
    n = int(seconds * SR)
    t = np.arange(n) / SR
    out = np.zeros((n, 2))
    partials = max(1, int(2400 * bright // freq))
    for v, cents in enumerate((-7, 0, 7)):
        f = freq * 2 ** (cents / 1200)
        # A slow drift of a few cents keeps the voices from locking.
        drift = 0.0012 * np.sin(2 * np.pi * (0.11 + 0.04 * v) * t + rng.uniform(0, 6.3))
        phase = 2 * np.pi * f * np.cumsum(1 + drift) / SR
        sig = np.zeros(n)
        for k in range(1, partials + 1):
            sig += np.sin(k * phase + rng.uniform(0, 6.3)) / k ** 1.9
        out += pan(sig, 0.5 + (v - 1) * 0.35)
    return out / 3 * envelope(n, 1.8, 2.2)[:, None]


def piano_note(freq, vel, rng):
    """Felt piano: slightly stretched partials, two strings, upper ones dying first."""
    n = int(6.5 * SR)
    t = np.arange(n) / SR
    sig = np.zeros(n)
    ring = 4.0 * (220 / freq) ** 0.5
    for k in range(1, 10):
        fk = k * freq * np.sqrt(1 + 0.0003 * k * k)
        if fk > 7000:
            break
        amp = vel ** (1 + 0.3 * k) / k ** 1.3
        decay = np.exp(-t / (ring / (1 + 0.7 * (k - 1))))
        sig += amp * decay * (np.sin(2 * np.pi * fk * t)
                              + 0.6 * np.sin(2 * np.pi * fk * 1.0006 * t + rng.uniform(0, 6.3)))
    return sig * np.minimum(1, t / 0.005) * envelope(n, 0, 0.5)


def bell_note(freq, vel, rng):
    """A soft glass bell by FM, inharmonic ratio, quick to fade."""
    n = int(4.5 * SR)
    t = np.arange(n) / SR
    index = 1.6 * vel * np.exp(-t / 0.7)
    sig = np.sin(2 * np.pi * freq * t + index * np.sin(2 * np.pi * freq * 3.5 * t))
    sig *= np.exp(-t / 1.4) * vel
    return sig * np.minimum(1, t / 0.003) * envelope(n, 0, 0.4)


KEYS = {"piano": piano_note, "bell": bell_note}


def melody(chords, beats, rng, density, low=260, high=1050):
    """(beat, frequency, velocity) events: a slow walk over each chord's tones.

    Moby's ambient lines move slowly and step rather than leap, so the next
    note is a neighbour of the last one among the chord's tones lifted an
    octave or two, and most beats are left empty.
    """
    events = []
    prev = None
    for ci, (voicing, _) in enumerate(chords):
        tones = sorted({f * m for f in map(hz, voicing.split()) for m in (2, 4)
                        if low <= f * m <= high})
        b = rng.choice([0.0, 1.0, 2.0])
        while b < beats:
            if rng.random() < density:
                if prev is None or prev not in tones:
                    i = int(rng.integers(len(tones) // 3, 2 * len(tones) // 3 + 1))
                else:
                    i = tones.index(prev) + int(rng.choice([-2, -1, -1, 1, 1, 2, 0]))
                i = max(0, min(len(tones) - 1, i))
                prev = tones[i]
                events.append((ci * beats + b, prev, float(rng.uniform(0.45, 0.8))))
            b += float(rng.choice([1.0, 1.5, 2.0, 3.0], p=[0.25, 0.3, 0.3, 0.15]))
    return events


def spectrum_gain(x, gain_db_at):
    """Filter by multiplying the spectrum with gain_db_at(freqs)."""
    n = len(x)
    X = np.fft.rfft(x, axis=0)
    f = np.fft.rfftfreq(n, 1 / SR)
    X *= (10 ** (gain_db_at(f) / 20))[:, None]
    return np.fft.irfft(X, n=n, axis=0)


def convolve(x, ir):
    """Each channel of x with the same channel of ir, by FFT."""
    n = len(x) + len(ir) - 1
    size = 1 << (n - 1).bit_length()
    X = np.fft.rfft(x, size, axis=0)
    H = np.fft.rfft(ir, size, axis=0)
    return np.fft.irfft(X * H, size, axis=0)[: len(x)]


def room(x, seconds, rng):
    """A hall made of noise: decorrelated left and right, darker as it decays."""
    n = int(seconds * SR)
    t = np.arange(n) / SR
    ir = rng.normal(size=(n, 2)) * np.exp(-6.91 * t / seconds)[:, None]
    ir = spectrum_gain(ir, lambda f: -12 * np.clip(np.log2(np.maximum(f, 1) / 4000), 0, None))
    ir = np.concatenate([np.zeros((int(0.03 * SR), 2)), ir])
    ir /= np.sqrt((ir ** 2).sum(axis=0))
    return convolve(x, ir)


def echo(x, every, feedback=0.38, taps=5):
    """Ping-pong: each repeat on the other side, quieter."""
    out = x.copy()
    shift = int(every * SR)
    for k in range(1, taps + 1):
        s = shift * k
        if s >= len(x):
            break
        side = (k % 2)
        out[s:, side] += x[:-s, 0] * feedback ** k * 0.7
        out[s:, 1 - side] += x[:-s, 1] * feedback ** k * 0.3
    return out


def render(preset, seconds, seed=7, **changes):
    """The finished stereo track, float in [-1, 1], at about -20 dBFS RMS.

    `changes` override the preset's settings - keys=None for no melody at
    all; keys_level and keys_room for how loud it is dry and in the hall;
    keys_dark to roll it off above that many Hz; keys_range for its register.
    """
    p = {"keys_level": 0.9, "keys_room": 1.0, "keys_dark": None,
         "keys_range": (260, 1050), **PRESETS[preset], **changes}
    rng = np.random.default_rng(seed)
    n = int(seconds * SR)
    beat = 60 / p["bpm"]
    beats = 8                                    # two bars a chord
    span = beats * beat
    count = int(np.ceil(seconds / span)) + 1
    chords = [p["chords"][i % len(p["chords"])] for i in range(count)]
    pads = np.zeros((n + int(10 * SR), 2))
    bass = np.zeros(len(pads))
    for i, (voicing, root) in enumerate(chords):
        start = int(i * span * SR)
        # Each chord overlaps the next by its release, so they cross-fade.
        for name in voicing.split():
            note = pad_note(hz(name), span + 2.2, rng, p["pad"])
            pads[start:start + len(note)] += note[: len(pads) - start]
        t = np.arange(int((span + 2.5) * SR)) / SR
        f = hz(root)
        b = (np.sin(2 * np.pi * f * t) + 0.12 * np.sin(4 * np.pi * f * t)) * envelope(len(t), 1.5, 2.5)
        bass[start:start + len(b)] += b[: len(bass) - start]
    keys = np.zeros_like(pads)
    if p["keys"]:
        play = KEYS[p["keys"]]
        for b, freq, vel in melody(chords, beats, rng, p["density"], *p["keys_range"]):
            # The keys come in on the third chord - by playing every note two
            # chords later than the chord it was drawn from, so each lands on
            # the chord after next. Not what melody() means, and kept anyway:
            # it is what the published setup video's music is (2026-10-24),
            # and every tone of these presets is in one key, so nothing
            # clashes. A new preset should skip b < 2 * beats instead.
            start = int((b * beat + 2 * span) * SR)
            if start >= n:
                continue
            note = pan(play(freq, vel, rng), 0.35 + 0.3 * float(rng.random()))
            keys[start:start + len(note)] += note[: len(keys) - start]
        if p["delay"]:
            keys = echo(keys, beat * 0.75)
        if p["keys_dark"]:
            cut = p["keys_dark"]
            keys = spectrum_gain(keys, lambda f: -18 * np.clip(np.log2(np.maximum(f, 1) / cut), 0, None))
    air = rng.normal(size=(len(pads), 2))
    air = spectrum_gain(air, lambda f: np.where((f > 4500) & (f < 11000), 0.0, -60.0))
    swell = 0.6 + 0.4 * np.sin(2 * np.pi * 0.03 * np.arange(len(pads)) / SR)
    air *= swell[:, None]

    def rms(x):
        return np.sqrt(np.mean(x ** 2)) + 1e-12

    # Levels relative to the pads; the keys sit just above, the air far below.
    pads /= rms(pads)
    keys /= rms(keys) if keys.any() else 1
    bass_st = np.stack([bass, bass], axis=1) / rms(bass)
    dry = pads * 1.0 + keys * p["keys_level"] + bass_st * 0.45 + air / rms(air) * 0.05 * p["air"]
    wet = room(pads * 0.6 + keys * p["keys_room"], p["room"], rng)
    mix = (dry * 0.75 + wet / rms(wet) * rms(dry) * 0.6)[:n]
    # Out of the voice's way: a dip from 1 to 4 kHz, nothing under 35 Hz.
    mix = spectrum_gain(mix, lambda f: -4.5 * np.exp(-0.5 * (np.log2(np.maximum(f, 1) / 2000) / 0.6) ** 2)
                        - 24 * (f < 35))
    mix *= envelope(n, 2.5, 4.0)[:, None]
    mix *= 10 ** (-20 / 20) / rms(mix)
    peak = np.abs(mix).max()
    if peak > 0.89:                               # soft ceiling, rarely needed
        mix = np.tanh(mix / 0.89) * 0.89
    return mix


def write_wav(path, x):
    pcm = (np.clip(x, -1, 1) * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(2)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    parser.add_argument("--preset", choices=PRESETS, default="porch")
    parser.add_argument("--seconds", type=float, default=48)
    parser.add_argument("--seed", type=int, default=7)
    parser.add_argument("--out", type=pathlib.Path)
    parser.add_argument("--samples", type=pathlib.Path, metavar="DIR")
    args = parser.parse_args()
    if args.samples:
        args.samples.mkdir(parents=True, exist_ok=True)
        for name in PRESETS:
            write_wav(args.samples / f"music-{name}.wav", render(name, args.seconds, args.seed))
            print(args.samples / f"music-{name}.wav")
    else:
        write_wav(args.out, render(args.preset, args.seconds, args.seed))
        print(args.out)


if __name__ == "__main__":
    main()
