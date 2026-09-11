"""Render the launcher mark as a raster, for the README and the website.

The launcher icon is a vector drawable and GitHub needs a raster. Screenshotting
the phone would bake the launcher's own wallpaper and shadow into the corners,
so this redraws the same geometry from the same numbers the drawables use.

THE NUMBERS ARE DUPLICATED, not parsed. They come from
app/src/main/res/drawable/ic_launcher_foreground.xml and
app/src/main/res/values/colors.xml. If either changes, change them here too and
re-run; the check is that the output matches the icon on the phone's launcher.

An adaptive icon is a 108dp canvas of which a launcher shows the central 72dp,
so this crops to that before masking - otherwise the mark sits small in a field
of background, which is not what anyone sees on their home screen. The corners
are made TRANSPARENT with a squircle mask, because the README is read on a white
page or a near-black one and an opaque square shows its own edge against both.

Two outputs, one drawing: docs/icon.png at 112px for the README and the site,
and the F-Droid listing's icon at the 512px fastlane asks for. Rendered from the
same numbers so the store icon is the launcher icon and not a second design.

Run from anywhere:  python3 tools/render_icon.py
"""
import pathlib
from PIL import Image, ImageDraw

# Supersample, then downscale: the capsule ends are round and a 112px draw of
# them is visibly stepped.
S = 8
VIEWPORT = 108
SAFE = 72          # what a launcher actually shows of the 108dp canvas
OUT_SIZE = 112     # what the README asks for
STORE_SIZE = 512   # what fastlane asks for, and F-Droid reads

BACKGROUND = "#0B1B2E"
WAVE = "#8AB4F8"
RECORD = "#E8564B"

# x0, y0, x1, y1 in viewport units, from the pathData. Each is a capsule: the
# arcs at both ends have the same radius as half the width, so a rounded
# rectangle with radius = width/2 is the same shape.
BARS = [
    (24, 45, 32, 63, WAVE),
    (36, 36, 44, 72, WAVE),
    (64, 36, 72, 72, WAVE),
    (76, 45, 84, 63, WAVE),
    (49, 27, 59, 81, RECORD),
]


def squircle(size: int, n: float = 4.0) -> Image.Image:
    """A superellipse mask, roughly the shape Android masks an icon to."""
    mask = Image.new("L", (size, size), 0)
    pixels = mask.load()
    half = size / 2
    for y in range(size):
        v = abs((y + 0.5 - half) / half)
        for x in range(size):
            u = abs((x + 0.5 - half) / half)
            if u ** n + v ** n <= 1.0:
                pixels[x, y] = 255
    return mask


def render(size: int = OUT_SIZE, mask: bool = True) -> Image.Image:
    canvas = Image.new("RGBA", (VIEWPORT * S, VIEWPORT * S), BACKGROUND)
    draw = ImageDraw.Draw(canvas)
    for x0, y0, x1, y1, colour in BARS:
        draw.rounded_rectangle(
            (x0 * S, y0 * S, x1 * S, y1 * S),
            radius=(x1 - x0) * S / 2,
            fill=colour,
        )

    inset = (VIEWPORT - SAFE) // 2
    canvas = canvas.crop(
        (inset * S, inset * S, (inset + SAFE) * S, (inset + SAFE) * S)
    ).resize((size, size), Image.LANCZOS)

    if mask:
        canvas.putalpha(squircle(size))
    return canvas


if __name__ == "__main__":
    root = pathlib.Path(__file__).resolve().parent.parent
    for out, size in (
        (root / "docs" / "icon.png", OUT_SIZE),
        (root / "fastlane" / "metadata" / "android" / "en-US" / "images" / "icon.png", STORE_SIZE),
    ):
        out.parent.mkdir(parents=True, exist_ok=True)
        render(size).save(out)
        print(f"wrote {out.relative_to(root)} ({size}px, {out.stat().st_size} bytes)")
