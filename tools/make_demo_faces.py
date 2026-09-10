"""Draw the two contact portraits the demo list uses, for the README screenshots.

INVENTED PEOPLE NEED INVENTED FACES. The screenshots show a call recorder's
list, and a real contact photo there would be a real person's face on a public
README beside a record of a phone call. A stock photo is someone else's face
with the same problem, and a monogram is not a portrait - the row is supposed to
show what a saved contact WITH a picture looks like, which is the case a plain
initials circle already covers.

So these are drawn: flat head-and-shoulders illustrations, of nobody. Two of
them, in different colours, because one is a coincidence and two show the list
handling more than one.

They live in app/src/debug/res/drawable, so they ship in no release APK.

Run from anywhere:  python3 tools/make_demo_faces.py
"""
import pathlib
from PIL import Image, ImageDraw

# 4x the 256 the app needs, drawn then downsampled: at 36dp the edges of a hair
# curve are the whole impression, and PIL has no antialiased fill.
SIZE = 256
S = 4
N = SIZE * S


class Portrait:
    def __init__(self, name, background, skin, hair, shirt, long_hair):
        self.name = name
        self.background = background
        self.skin = skin
        self.hair = hair
        self.shirt = shirt
        self.long_hair = long_hair


PORTRAITS = [
    # Warm background, dark hair to the shoulders.
    Portrait("demo_face_1.png", "#E8B98A", "#F0C9A4", "#4A3328", "#3D6B8F", True),
    # Cool background, short fair hair.
    Portrait("demo_face_2.png", "#8FB8D8", "#E8B48C", "#8A5A34", "#C4553F", False),
]


def draw(p: Portrait) -> Image.Image:
    image = Image.new("RGB", (N, N), p.background)
    d = ImageDraw.Draw(image)

    def box(x0, y0, x1, y1):
        return (x0 * S, y0 * S, x1 * S, y1 * S)

    # Shoulders: a wide rounded shape rising from the bottom edge, so the circle
    # crop leaves a body under the head rather than a floating oval.
    d.rounded_rectangle(box(40, 186, 216, 300), radius=76 * S, fill=p.shirt)

    if p.long_hair:
        # Behind the head, falling past the jaw on both sides.
        d.rounded_rectangle(box(66, 58, 190, 216), radius=52 * S, fill=p.hair)

    # Neck, then the head over it.
    d.rounded_rectangle(box(110, 158, 146, 200), radius=16 * S, fill=p.skin)
    d.ellipse(box(78, 62, 178, 182), fill=p.skin)

    # Hair on top: an ellipse clipped to its upper half by a rectangle over the
    # forehead line, which is a fringe.
    d.chord(box(74, 52, 182, 158), start=180, end=360, fill=p.hair)
    if not p.long_hair:
        d.rounded_rectangle(box(74, 96, 182, 112), radius=8 * S, fill=p.hair)

    # Eyes. Small, dark, and set wide: at 36dp they are two dots, and two dots
    # are what makes it read as a face rather than a shape.
    for x in (108, 148):
        d.ellipse(box(x - 6, 118, x + 6, 132), fill="#3A2B22")

    # A closed smile, drawn as the lower arc of an ellipse.
    d.arc(box(106, 128, 150, 158), start=20, end=160, fill="#8A5346", width=4 * S)

    return image.resize((SIZE, SIZE), Image.LANCZOS)


if __name__ == "__main__":
    out = (
        pathlib.Path(__file__).resolve().parent.parent
        / "app" / "src" / "debug" / "res" / "drawable"
    )
    out.mkdir(parents=True, exist_ok=True)
    for portrait in PORTRAITS:
        path = out / portrait.name
        draw(portrait).save(path)
        print(f"wrote {path} ({path.stat().st_size} bytes)")
