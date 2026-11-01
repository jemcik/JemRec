"""Generate the project site under docs/, which GitHub Pages serves from main.

A home page, how it works, the setup manual and a privacy policy, from the
templates below. Written by a script rather than by hand so the pages cannot
drift apart, and so the colours stay
the ones the app actually uses - they are taken from
app/src/main/java/com/jemcik/jemrec/ui/theme/Theme.kt and then lifted slightly,
for the reason written above the palette.

Run from anywhere:  python3 tools/build_site.py
"""
import functools
import html
import pathlib
import re
import shutil
import textwrap

ROOT = pathlib.Path(__file__).resolve().parent.parent
DOCS = ROOT / "docs"

REPO = "https://github.com/jemcik/JemRec"
FDROID = "https://f-droid.org/packages/com.jemcik.jemrec/"
# The one way to support the project: a Ukrainian service, because the ones
# better known abroad (PayPal, Buy Me a Coffee) do not pay out to Ukraine.
DONATE = "https://donatello.to/jemcik"
TAGLINE = "A call recorder for unrooted Android — phone and messenger calls."
# The setup video, made by tools/setup_video.py from a take on the Honor and
# published on the user's channel (2026-10-24). docs/video/setup.jpg is that
# script's thumbnail.
VIDEO = "rt25pZd8R9M"
VIDEO_URL = f"https://www.youtube.com/watch?v={VIDEO}"
VIDEO_LENGTH = "2:22"       # 141.5 s; YouTube's player shows 2:22
# The features video, made by tools/features_video.py from this site's own
# Deck screenshots and published on the user's channel (2026-10-29).
# docs/video/features.jpg is that script's thumbnail.
FEATURES_VIDEO = "v5JV6KUUj_I"
FEATURES_VIDEO_LENGTH = "1:51"   # 111.5 s; YouTube's player shows 1:51

# THE THEME HAS THREE STATES, NOT TWO, and each palette is written twice
# because of it:
#
#   bare :root                          light, and the default
#   @media (prefers-color-scheme: dark) the reader's system says dark
#   :root[data-theme=...]               the reader pressed the button
#
# The media query is guarded with :not([data-theme="light"]) so that choosing
# light on a system set to dark actually wins, and the stamped rule comes last
# so it beats both.
#
# AND THE SITE HAS THE APP'S THREE LOOKS. The Look button in the header picks
# Memo, Deck or Multitrack, and the whole site takes it - colours, type and
# shapes - not only its screenshots. A site that looked like every other
# product page was the user's complaint (2026-10-27); the app's looks are
# what JemRec looks like. Each look's colours are the app's own
# (app/src/main/java/com/jemcik/jemrec/ui/theme/LookColors.kt and Theme.kt),
# moved a step in from black and white for the reason above the stylesheet's
# first rule. Memo is the page the site always had.
#
# Six palettes, so they live here as one table and the stylesheet's blocks are
# written from it: every look has every name Memo's has, and its light and dark
# the same names, Deck's own LCD and keys included (palettes_css checks).
PALETTES = {
    "memo": {
        "light": {
            "surface": "#F1F4F8", "raise": "#FAFBFD", "line": "#D5DCE5",
            "ink": "#161A20", "ink-mid": "#4C555F", "accent": "#2C5AA0",
            "record": "#C0392F", "play": "#14713C", "code-bg": "#E7ECF3",
            "head": "#F1F4F8", "band": "#FAFBFD", "marker": "#2C5AA0",
            # MemoLight: the header's state words, and its button's disc.
            "st-ready": "#1E6B34", "st-alarm": "#BE3A2D", "st-idle": "#43474E",
            "disc": "#FFFFFF", "disc-edge": "#DCE1E9", "rec": "#E8564B",
        },
        "dark": {
            "surface": "#22262B", "raise": "#2C3137", "line": "#3B4149",
            "ink": "#E4E7EB", "ink-mid": "#B2BAC4", "accent": "#9ECAFF",
            "record": "#FF7A6B", "play": "#6FDF9B", "code-bg": "#1B1F23",
            "head": "#22262B", "band": "#2C3137", "marker": "#9ECAFF",
            "st-ready": "#6FDF9B", "st-alarm": "#FF7A6B", "st-idle": "#C3C7CF",
            "disc": "#2A2E34", "disc-edge": "#3A3F46", "rec": "#E8564B",
        },
    },
    # Paper under a graphite-and-aluminium recorder: the header and the bands
    # are the recorder's face, the cards its panels, the links the LCD's olive.
    "deck": {
        "light": {
            "surface": "#EFEEEA", "raise": "#E6E8EB", "line": "#CCD0D5",
            "ink": "#1A1C1E", "ink-mid": "#50555C", "accent": "#4E6B26",
            "record": "#BE3A2D", "play": "#276E35", "code-bg": "#E4E6E9",
            "head": "#E4E6E9", "band": "#E4E6E9", "marker": "#50555C",
            "st-ready": "#276E35", "st-alarm": "#BE3A2D", "st-idle": "#50555C",
            "disc": "#C4C9CF", "disc-edge": "#AEB4BA", "rec": "#E8564B",
            "lcd": "#C9D6A3", "lcd-ink": "#1E2A12", "lcd-edge": "#6E747B",
            "lcd-off": "#A7B092", "lcd-ink-off": "#39422C", "lcd-glow": "transparent",
            "key": "#FFFFFF", "key-edge": "#AEB4BA",
            "key-idle": "#F4F5F6", "key-idle-mid": "#C9CDD2", "key-idle-rim": "#9DA3AA",
        },
        # The LCD negative, glowing segments on black, as the app's at night.
        "dark": {
            "surface": "#15181B", "raise": "#1F2327", "line": "#343A41",
            "ink": "#E3E5E8", "ink-mid": "#A3A9B0", "accent": "#B6F27A",
            "record": "#FF7A6B", "play": "#7CF29A", "code-bg": "#111315",
            "head": "#1C1F23", "band": "#1C1F23", "marker": "#9AA3AD",
            "st-ready": "#7CF29A", "st-alarm": "#FF7A6B", "st-idle": "#A3A9B0",
            "disc": "#0E1013", "disc-edge": "#3A4047", "rec": "#E8564B",
            "lcd": "#0E140B", "lcd-ink": "#B6F27A", "lcd-edge": "#565D66",
            "lcd-off": "#0B0F09", "lcd-ink-off": "#6E8E50",
            "lcd-glow": "rgba(182, 242, 122, .45)",
            "key": "#343A41", "key-edge": "#0E1013",
            "key-idle": "#7A8189", "key-idle-mid": "#4A5057", "key-idle-rim": "#2A2E33",
        },
    },
    # A sound editor: white panels on grey, LED green for what works, the
    # day markers' amber for labels - and for "no link", as the app has it.
    "multitrack": {
        "light": {
            "surface": "#EEF0F3", "raise": "#FFFFFF", "line": "#DCE0E5",
            "ink": "#1B1F24", "ink-mid": "#555D68", "accent": "#2460C4",
            "record": "#BE3A2D", "play": "#12803F", "code-bg": "#E7EAEE",
            "head": "#FFFFFF", "band": "#FFFFFF", "marker": "#8A6400",
            "st-ready": "#128A45", "st-alarm": "#8A6400", "st-idle": "#555D68",
            "disc": "#F3F4F6", "disc-edge": "#C9CED6", "rec": "#E8463B",
        },
        "dark": {
            "surface": "#12151A", "raise": "#191D23", "line": "#262C35",
            "ink": "#E6E9EE", "ink-mid": "#A3ABB6", "accent": "#7DB2FF",
            "record": "#FF7A6B", "play": "#3DDC84", "code-bg": "#0E1014",
            "head": "#161A20", "band": "#161A20", "marker": "#F5C542",
            "st-ready": "#7CF29A", "st-alarm": "#F5C542", "st-idle": "#A3ABB6",
            "disc": "#1B1F26", "disc-edge": "#3A404A", "rec": "#FF4D42",
        },
    },
}

# Type and shape, by look, the same in light and dark: the app's typography
# (Theme.kt, typographyFor). Memo the system's; Deck engraved throughout, as
# its keys and screens are, with Doto for the LCD alone; Multitrack Space
# Grotesk for names and titles and JetBrains Mono for the rest.
LOOK_TYPE = {
    "memo": {
        "display": "var(--sans)", "body": "var(--sans)", "label": "var(--sans)",
        "mono": "ui-monospace, SFMono-Regular, Menlo, monospace",
        "radius": "18px", "radius-sm": "999px",
        # A step's number - Get started, the list beside the diagram, the
        # diagram's own - and the same number lit. The shared rules read
        # these, so a look sets them once and the lit rule always wins.
        "num-bg": "var(--accent)", "num-ink": "var(--surface)", "num-edge": "transparent",
        "num-font": "var(--label)", "num-weight": "700", "num-glow": "transparent",
        "num-lit-bg": "var(--record)", "num-lit-edge": "transparent",
    },
    "deck": {
        "display": "var(--engraved)", "body": "var(--engraved)", "label": "var(--engraved)",
        "mono": "var(--engraved)", "radius": "12px", "radius-sm": "8px",
        # Read off an LCD, as the player's time is; lit, its frame goes red.
        "num-bg": "var(--lcd)", "num-ink": "var(--lcd-ink)", "num-edge": "var(--lcd-edge)",
        "num-font": "var(--lcdface)", "num-weight": "800", "num-glow": "var(--lcd-glow)",
        "num-lit-bg": "var(--lcd)", "num-lit-edge": "var(--record)",
    },
    "multitrack": {
        "display": "var(--grotesk)", "body": "var(--grotesk)", "label": "var(--jbmono)",
        "mono": "var(--jbmono)", "radius": "6px", "radius-sm": "4px",
        "num-bg": "var(--ink)", "num-ink": "var(--surface)", "num-edge": "transparent",
        "num-font": "var(--label)", "num-weight": "700", "num-glow": "transparent",
        "num-lit-bg": "var(--record)", "num-lit-edge": "transparent",
    },
}

# The looks' typefaces, the app's own files (app/src/main/res/font), copied
# into docs/fonts unmodified with their licences beside them - the OFL asks
# for that, and Share Tech Mono's Reserved Font Name rules out cutting it
# down. Served from the site itself: this site fetches nothing from anywhere
# else (the privacy policy says so). A browser fetches a face only when text
# on screen is set in it, so Memo, the default, loads none of them.
# (family, file, licence, weight range, characters it is used for)
FONTS = (
    # All but the full stop: Doto draws it as a cross of five dots, and on the
    # Honor "EVERY CALL." read as "EVERY CALL+" (2026-10-27). The LCD's stack
    # falls back to Share Tech Mono, whose stop is one square dot.
    ("Doto", "doto.ttf", "OFL-Doto.txt", "100 900", "U+0000-002D, U+002F-10FFFF"),
    ("Share Tech Mono", "share_tech_mono.ttf", "OFL-ShareTechMono.txt", "400", ""),
    ("JetBrains Mono", "jetbrains_mono.ttf", "OFL-JetBrainsMono.txt", "100 800", ""),
    ("Space Grotesk", "space_grotesk.ttf", "OFL-SpaceGrotesk.txt", "300 700", ""),
)


def _vars(values: dict, indent: str) -> str:
    return "\n".join(f"{indent}--{k}: {v};" for k, v in values.items())


def palettes_css() -> str:
    """Every look's palette in the three states, and its fonts."""
    looks = {look for look, _ in LOOKS}
    assert set(PALETTES) == looks == set(LOOK_TYPE), "LOOKS, PALETTES and LOOK_TYPE name different looks"
    names = set(PALETTES["memo"]["light"])
    for look, themes in PALETTES.items():
        light, dark = set(themes["light"]), set(themes["dark"])
        assert light == dark, f"{look}: light and dark differ in {sorted(light ^ dark)}"
        assert names <= light, f"{look}: no {sorted(names - light)}"
    blocks = [
        "\n".join(
            f'@font-face {{ font-family: "{family}"; src: url("fonts/{file}") format("truetype"); '
            f"font-weight: {weights}; font-display: swap;{f' unicode-range: {chars};' if chars else ''} }}"
            for family, file, _, weights, chars in FONTS
        )
    ]
    for look, themes in PALETTES.items():
        sel = ":root" if look == "memo" else f':root[data-look="{look}"]'
        light = dict(LOOK_TYPE[look], **themes["light"])
        blocks.append(f"{sel} {{\n{_vars(light, '  ')}\n}}")
        blocks.append(
            "@media (prefers-color-scheme: dark) {\n"
            f'  {sel}:not([data-theme="light"]) {{\n{_vars(themes["dark"], "    ")}\n  }}\n}}'
        )
        blocks.append(f'{sel}[data-theme="dark"] {{\n{_vars(themes["dark"], "  ")}\n}}')
    # Paper is white whatever the screen was. Browsers drop backgrounds when
    # printing, so a dark palette printed its light text on white, too faint
    # to read - the privacy policy opens its rows to be printed. Each look
    # prints in its own light palette; the selectors match the dark ones'
    # weight and come after them.
    prints = []
    for look, themes in PALETTES.items():
        sel = ":root" if look == "memo" else f':root[data-look="{look}"]'
        prints.append(f'  {sel}, {sel}[data-theme], {sel}:not([data-theme="light"]) {{\n'
                      f'{_vars(themes["light"], "    ")}\n  }}')
    blocks.append("@media print {\n" + "\n".join(prints) + "\n}")
    return "\n\n".join(blocks)

CSS = """/* Generated by tools/build_site.py - edit there, not here. */

/* NEITHER END IS TAKEN TO ITS EXTREME. The app's own dark surface is #1A1C1E,
   which is right on a phone held at night and reads as a black page in a browser
   window - and it was darker than the phone screenshots lying on it, so every
   image glowed like a light box. The light end started at #FBFCFE, near enough
   to paper that the cards on it had nothing to lift off. So the page sits a step
   in from both: a soft grey-blue in light, a lifted charcoal in dark, with the
   cards and the code blocks moving the other way in each. The looks' own
   palettes follow the same rule (tools/build_site.py, PALETTES). */
:root {
  --wrap:     44rem;
  --wide:     70rem;
  --sans: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  /* Deck's chain, as the app's: Share Tech Mono, then JetBrains Mono for
     what it has no glyph for. */
  --engraved: "Share Tech Mono", "JetBrains Mono", ui-monospace, Menlo, monospace;
  --grotesk: "Space Grotesk", -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
  --jbmono: "JetBrains Mono", ui-monospace, SFMono-Regular, Menlo, monospace;
  --lcdface: "Doto", "Share Tech Mono", ui-monospace, monospace;
}

__PALETTES__

* { box-sizing: border-box; }
/* scroll-padding: the header is sticky, so a jump to a heading - a feature's
   More into the manual, say - would otherwise put the heading under it. */
html { font-size: 17px; scroll-behavior: smooth; scroll-padding-top: 5rem; }
body {
  margin: 0;
  font-family: var(--body);
  line-height: 1.62;
  color: var(--ink);
  background: var(--surface);
  -webkit-font-smoothing: antialiased;
}

/* The RULE spans the window; the CONTENT does not. A sticky bar whose border
   stops short of the edges reads as a floating card rather than a header, so
   the bar stays full width and everything inside it sits in a column.
   The column is the home page's width on every page, not the prose's: the
   home page is laid out wide, and a wordmark that jumped sideways between
   pages would look like a different site. The manual's prose keeps its own
   narrower measure underneath. */
.bar {
  padding: .9rem 0;
  border-bottom: 1px solid var(--line);
  position: sticky; top: 0; background: var(--head); z-index: 5;
}
.column { max-width: var(--wide); margin: 0 auto; padding: 0 1.4rem; }
.bar .column {
  display: flex; align-items: center; justify-content: space-between; gap: 1rem;
}
.brand { display: flex; align-items: center; gap: .6rem; font-weight: 700;
         color: var(--ink); text-decoration: none; font-size: 1.05rem; }
.brand img { border-radius: 22%; }
.bar-end { display: flex; align-items: center; gap: .9rem; }
.bar nav a { color: var(--ink-mid); text-decoration: none; margin-left: 1.1rem;
             font-size: .92rem; font-family: var(--label); }
.bar nav a:first-child { margin-left: 0; }
.bar nav a:hover { color: var(--accent); }

.theme, .look {
  display: inline-flex; align-items: center; gap: .35rem;
  font: inherit; font-family: var(--label); font-size: .92rem; color: var(--ink-mid);
  background: var(--raise); border: 1px solid var(--line); border-radius: var(--radius-sm);
  padding: .2rem .66rem; cursor: pointer;
}
.theme:hover, .look:hover { color: var(--accent); border-color: var(--accent); }
/* In the bar, so it is on every page and stays in view - a link at the foot of
   the page was found by nobody. The theme button's shape, in the record red,
   which on this site means the one thing to notice. */
.support-btn {
  display: inline-flex; align-items: center; gap: .35rem;
  font-size: .92rem; font-weight: 600; text-decoration: none; color: var(--record);
  background: color-mix(in srgb, var(--record) 10%, transparent);
  border: 1px solid color-mix(in srgb, var(--record) 35%, transparent);
  border-radius: var(--radius-sm); padding: .2rem .75rem; font-family: var(--label);
  transition: background-color .15s;
}
.support-btn:hover { background: color-mix(in srgb, var(--record) 18%, transparent); }
/* One height for the pair: once their words are hidden, the theme button holds
   a 14px icon and the heart a line of text, and they came out 23px and 33px. */
.theme, .look, .support-btn { min-height: 2rem; justify-content: center; }
.theme svg, .look svg { width: 14px; height: 14px; }
/* The words go before the buttons do, then the links before the words would
   wrap: a masthead on two lines looks broken rather than responsive. First the
   two pills lose their labels, then the bar keeps only Setup (below). */
@media (max-width: 46rem) {
  .theme .theme-name, .look .look-name, .support-btn .support-name { display: none; }
  .support-btn { padding: .2rem .62rem; }
}
@media (max-width: 36rem) {
  .bar nav a { margin-left: .85rem; }
  .bar-end { gap: .6rem; }
  /* Then every link but Setup, the one a phone's reader came for: the footer
     carries them all, and the wordmark goes home. With the Support heart in
     the bar there was no room for more without it wrapping to two lines. */
  .bar nav a.roomy { display: none; }
}

main { max-width: var(--wrap); margin: 0 auto; padding: 2.6rem 1.4rem 5rem; }

h1, h2, h3 { font-family: var(--display); }
h1 { font-size: 2.1rem; line-height: 1.18; letter-spacing: -.02em; margin: 0 0 .8rem; }
h2 { font-size: 1.28rem; margin: 2.6rem 0 .7rem; letter-spacing: -.01em; }
h3 { font-size: 1.02rem; margin: 1.6rem 0 .4rem; }
p  { margin: .7rem 0; }
a  { color: var(--accent); }
.lead { font-size: 1.1rem; color: var(--ink-mid); }
.muted { color: var(--ink-mid); font-size: .93rem; }

.cta { display: flex; flex-wrap: wrap; gap: .7rem; margin: 1.6rem 0 .6rem; }

pre {
  background: var(--code-bg); border: 1px solid var(--line); border-radius: 10px;
  padding: .8rem 1rem; overflow-x: auto; font-size: .86rem; line-height: 1.5;
}
code { font-family: var(--mono); font-size: .88em; }
p code, li code, td code { background: var(--code-bg); padding: .1rem .32rem; border-radius: 5px; }
/* The manual quotes a 64-character signing digest and a whole Honor file name
   inline; unbroken, either one made the page wider than a phone. */
p code, li code { overflow-wrap: anywhere; }
/* FOREGROUND_SERVICE_SPECIAL_USE is wider than a phone's table column. Only
   there: "anywhere" also lets the column shrink, and on a wide screen it did,
   breaking every name in two. */
@media (max-width: 38rem) { td code { overflow-wrap: anywhere; } }

table { border-collapse: collapse; width: 100%; margin: 1rem 0; font-size: .94rem; }
th, td { text-align: left; padding: .5rem .7rem; border-bottom: 1px solid var(--line);
         vertical-align: top; }
th { color: var(--ink-mid); font-weight: 600; }

.note {
  border-left: 3px solid var(--record); background: var(--raise);
  padding: .8rem 1rem; border-radius: 0 10px 10px 0; margin: 1.4rem 0;
}
/* Two callouts on one page, and they are not the same kind of thing: one is a
   warning about the law, the other is a fact about how the app behaves. The
   accent keeps the second from reading as a second alarm. */
.note.info { border-left-color: var(--accent); }
.note strong { color: var(--ink); }

/* WHAT STAYS ON THE PHONE, a row each: its answers as chips to scan, and
   the policy's own words a tap away (PRIVACY_DATA). It was a table of
   paragraphs, thirteen of them, and nothing to find your way by. */
.datamap { margin: 1.2rem 0 2rem; border-top: 1px solid var(--line); }
.datamap-head, .datum summary {
  display: grid; grid-template-columns: 9.5rem repeat(3, minmax(0, 1fr));
  gap: .5rem .7rem; padding-right: 2rem;
}
.datamap-head {
  padding-top: .6rem; padding-bottom: .5rem; border-bottom: 1px solid var(--line);
  font: 600 .7rem var(--label); text-transform: uppercase; letter-spacing: .1em; color: var(--ink-mid);
}
.datum { border-bottom: 1px solid var(--line); }
.datum summary {
  list-style: none; cursor: pointer; position: relative; align-items: start;
  padding-top: .8rem; padding-bottom: .8rem;
}
.datum summary::-webkit-details-marker { display: none; }
.datum summary::after {
  content: ""; position: absolute; right: .5rem; top: 1.15rem; width: .5rem; height: .5rem;
  border-right: 2px solid var(--ink-mid); border-bottom: 2px solid var(--ink-mid);
  transform: rotate(45deg); transition: transform .2s;
}
.datum[open] summary::after { transform: translateY(.2rem) rotate(-135deg); }
/* Hover only where a pointer hovers: on a touchscreen it sticks after a tap,
   and an opened row kept its accent title (the Honor, 2026-10-27). */
@media (hover: hover) { .datum summary:hover .what { color: var(--accent); } }
.datum .what { font-weight: 700; line-height: 1.35; }
.chip {
  justify-self: start; padding: .12rem .6rem; font-size: .8rem; line-height: 1.4;
  border-radius: min(var(--radius-sm), 10px); background: var(--code-bg); color: var(--ink);
}
/* The column heads say what each chip is in a wide window; a screen reader,
   which skips them, hears the chip's own word instead. */
.chip b {
  position: absolute; width: 1px; height: 1px; overflow: hidden;
  clip-path: inset(50%); white-space: nowrap;
}
.chip.long { background: color-mix(in srgb, var(--accent) 13%, transparent); color: var(--accent); }
.chip.when.if { background: color-mix(in srgb, var(--play) 14%, transparent); color: var(--play); }
.datum p {
  margin: 0 0 1rem; padding: .9rem 1rem; border-radius: var(--radius);
  background: var(--raise); color: var(--ink-mid); font-size: .94rem;
}
@media (max-width: 40rem) {
  .datamap-head { display: none; }
  .datum summary { grid-template-columns: minmax(0, 1fr); gap: .35rem; }
  .chip b { position: static; width: auto; height: auto; overflow: visible; clip-path: none; font-weight: 600; margin-right: .3rem; }
}

/* THE MANUAL, FINDABLE. Thirty sections, some 13,000px of text, and no
   contents at all. In a wide window the contents stay in view beside the
   text and mark the section being read; in a narrow one they sit folded
   under the title (the script opens them only where there is room, so
   nothing on a phone jumps when it runs). The title comes first in the
   page, for a phone and for a screen reader alike. */
main.manual {
  max-width: calc(var(--wrap) + 17.4rem); display: grid; align-items: start;
  grid-template-columns: 14rem minmax(0, 1fr); gap: 0 3.4rem;
}
main.manual > :not(.toc) { grid-column: 2; }
main.manual > .toc { grid-column: 1; grid-row: 1 / span 3; }
.toc {
  position: sticky; top: 4.6rem; max-height: calc(100vh - 5.6rem); overflow-y: auto;
  overscroll-behavior: contain; font-size: .86rem; padding: 1.2rem 0 1rem;
}
.toc summary { cursor: pointer; font-weight: 600; font-family: var(--label); }
.toc p {
  margin: 1.2rem 0 .35rem; font: 600 .7rem var(--label); color: var(--ink-mid);
  text-transform: uppercase; letter-spacing: .12em;
}
.toc p:first-of-type { margin-top: .4rem; }
.toc ol { list-style: none; margin: 0; padding: 0; }
.toc a {
  display: block; padding: .24rem .65rem; line-height: 1.35; text-decoration: none;
  color: var(--ink-mid); border-left: 2px solid var(--line);
}
.toc a:hover { color: var(--ink); }
.toc a[aria-current="true"] {
  color: var(--ink); font-weight: 700; border-left-color: var(--record);
  background: color-mix(in srgb, var(--record) 8%, transparent);
}
.toc .n { font: 700 .8rem var(--label); color: var(--accent); margin-right: .15rem; }
@media (min-width: 70rem) { .toc details[open] > summary { display: none; } }
@media (max-width: 69.99rem) {
  main.manual { display: block; max-width: var(--wrap); }
  .toc {
    position: static; max-height: none; overflow: visible; margin: 0 0 2rem;
    padding: .8rem 1rem; background: var(--raise); border: 1px solid var(--line);
    border-radius: var(--radius);
  }
}

/* On paper, the page alone: not the header and its buttons, nor the
   manual's contents column, which narrowed every printed page's text. */
@media print {
  .bar, .toc, .viewer { display: none; }
  main.manual { display: block; max-width: var(--wrap); }
}

/* A step's place in the setup, over its heading: done, this one, to come. */
.step-rail { display: flex; align-items: center; gap: 4px; max-width: 17rem; margin: 3rem 0 -1.7rem; }
/* A jump from the contents lands on the heading; this much more keeps the
   bar above it out from under the header. */
.step-rail + h2 { scroll-margin-top: 2.2rem; }
.step-rail i { flex: 1; height: 4px; border-radius: 2px; background: var(--line); }
.step-rail i.d { background: var(--play); }
.step-rail i.c { background: var(--record); }
.step-rail span { margin-left: .6rem; font: 600 .74rem var(--label); color: var(--ink-mid); white-space: nowrap; }

/* WHAT THE HEADER SAYS, AS IT SAYS IT - in the page's look, from the app's
   own words (header_words), each look's header drawn small: Memo's disc,
   Deck's REC key and LCD, Multitrack's ring and spaced capitals. */
.status-list { margin: 1.2rem 0 .6rem; border-top: 1px solid var(--line); }
.status {
  display: grid; grid-template-columns: minmax(0, 17rem) minmax(0, 1fr);
  gap: .6rem 1.5rem; align-items: center; padding: .95rem 0; border-bottom: 1px solid var(--line);
}
.status p { margin: 0; color: var(--ink-mid); font-size: .95rem; }
@media (max-width: 38rem) { .status { grid-template-columns: minmax(0, 1fr); } }
.hd { display: inline-flex; align-items: center; gap: .75rem; min-width: 0; }
.hd b { display: block; font-weight: 600; line-height: 1.2; }
.hd small { display: block; margin-top: .15rem; font-size: .78rem; line-height: 1.3; color: var(--st-idle); }
.hd .ready { color: var(--st-ready); }
.hd .alarm { color: var(--st-alarm); }
.hd .idle { color: var(--st-idle); }
.hd-disc, .hd-ring, .hd-key { flex: none; width: 2.4rem; height: 2.4rem; border-radius: 50%; }
.hd-disc, .hd-ring { display: grid; place-items: center; background: var(--disc); }
.hd-disc { border: 1px solid var(--disc-edge); box-shadow: 0 1px 3px rgba(0, 0, 0, .15); }
.hd-ring { border: 2px solid var(--disc-edge); }
.hd-disc i, .hd-ring i { width: 1.1rem; height: 1.1rem; border-radius: 50%; background: var(--rec); }
.hd-disc.off i { background: none; border: 2px solid var(--disc-edge); }
.hd-ring.off i { background: color-mix(in srgb, var(--rec) 28%, var(--disc)); }
.hd-memo b { font-size: 1.12rem; font-family: var(--sans); }
.hd-memo small { font-family: var(--sans); }
/* As Memo draws them: a line you can tap in its primary colour, and while
   it starts, a ring round the button (the app's spins; this one holds). */
.hd-memo small.tap { color: var(--accent); }
.hd-disc.busy { outline: 3px solid var(--accent); outline-offset: 2px; }
/* The REC key as the app domes it (HeaderParts.kt, domed): lit from up-left,
   light, mid, dark at 0, 46% and the rim, over 0.78 of its width - LitRed
   when on, the theme's keyIdle greys when off. */
.hd-key {
  background: radial-gradient(78% 78% at 38% 32%, #FF9184 0, #E8564B 46%, #A8322A 100%);
  box-shadow: 0 0 0 3px var(--disc), 0 0 0 4px var(--disc-edge);
}
.hd-key.off {
  background: radial-gradient(78% 78% at 38% 32%, var(--key-idle) 0, var(--key-idle-mid) 46%, var(--key-idle-rim) 100%);
}
.hd-lcd {
  min-width: 12.5rem; padding: .3rem .65rem .35rem; border-radius: 8px;
  background: var(--lcd); color: var(--lcd-ink); border: 2px solid var(--lcd-edge);
  text-shadow: 0 0 8px var(--lcd-glow);
}
.hd-lcd b, .hd-lcd b.ready, .hd-lcd b.alarm, .hd-lcd b.idle { font: 800 1.2rem var(--lcdface); letter-spacing: .04em; color: inherit; }
.hd-lcd small { color: inherit; font: .64rem var(--engraved); letter-spacing: .03em; }
.hd-lcd.off { background: var(--lcd-off); color: var(--lcd-ink-off); }
.hd-multitrack b { font: 600 1.05rem var(--jbmono); letter-spacing: .14em; }
.hd-multitrack small { font: .64rem var(--jbmono); letter-spacing: .06em; }
.hd-notif {
  padding: .5rem .85rem; border-radius: 14px; background: var(--raise);
  border: 1px solid var(--line); font-family: var(--sans);
}
.hd-notif img { width: 18px; height: 18px; border-radius: 50%; }

footer {
  border-top: 1px solid var(--line); margin-top: 3rem; padding: 1.6rem 0 3rem;
  color: var(--ink-mid); font-size: .9rem; text-align: center;
}
footer a { color: var(--ink-mid); }
footer nav { display: flex; flex-wrap: wrap; justify-content: center; gap: .3rem 1.4rem; }
footer .credit { font-size: .8rem; margin-top: -.3rem; }

/* ── the home page ── */

/* LOOKED AT, NOT READ. The manual and the policy are prose, and keep a 44rem
   measure because longer lines are hard to read. The home page is a phone
   beside a headline and rows of cards, and in 44rem every row became a column,
   so it is a run of full-width sections, each with its content in the wide
   column - which also lets a section's background reach the window's edges. */
main.home { max-width: none; padding: 0 0 2rem; }
/* A section's id is on the section, and its heading is a padding below that:
   the negative margin takes most of the padding back, so a jump lands with the
   heading just under the header rather than a screen-fifth below it. */
.home section { padding-top: 5.5rem; scroll-margin-top: -4rem; }
@media (max-width: 38rem) { .home section { padding-top: 4rem; } }
/* Balanced, so a headline never ends on one word of its own - "Both sides of
   every" over a lone "call." looked like a mistake. */
.home h1, .home h2 { text-wrap: balance; }
.home h2 {
  font-size: clamp(1.6rem, 3.2vw, 2.15rem); line-height: 1.18;
  letter-spacing: -.025em; margin: 0 0 .6rem;
}
.home h3 { font-size: 1.05rem; line-height: 1.35; margin: 0 0 .35rem; }
.intro { color: var(--ink-mid); font-size: 1.08rem; max-width: 40rem; margin: 0 0 2.2rem; }
.home .note { max-width: 46rem; margin-top: 2rem; }
.more { font-weight: 600; text-decoration: none; }
.more:hover { text-decoration: underline; }

.hero {
  display: grid; grid-template-columns: minmax(0, 1.05fr) minmax(0, .95fr);
  gap: 2.5rem 3rem; align-items: center; padding-top: 3.4rem;
}
.eyebrow {
  display: inline-block; font-size: .86rem; font-weight: 600; color: var(--accent);
  background: color-mix(in srgb, var(--accent) 12%, transparent);
  padding: .28rem .8rem; border-radius: var(--radius-sm); font-family: var(--label);
}
.hero h1 {
  font-size: clamp(2.35rem, 5.4vw, 3.75rem); line-height: 1.04;
  letter-spacing: -.035em; margin: 1.1rem 0 1.2rem;
}
.hero h1 span { display: block; color: var(--accent); }
.hero .lead { font-size: 1.17rem; max-width: 33rem; margin: 0; }
.hero .cta { align-items: center; margin: 2rem 0 0; }
.facts {
  list-style: none; padding: 0; margin: 1.5rem 0 0;
  display: flex; flex-wrap: wrap; gap: .35rem 1.3rem;
  color: var(--ink-mid); font-size: .92rem;
}
.facts li::before { content: "\\2713"; color: var(--play); font-weight: 700; margin-right: .45rem; }

/* Store-badge shaped. The logo is a copy in docs/, not a link to F-Droid's
   site: this page fetches nothing from anywhere else (the privacy policy says
   so). F-Droid's blue is F-Droid's own (#1976D2, from the logo) and stays
   that blue in both themes, as a brand colour does; the GitHub one takes the
   page's colours, so it reads as the second choice in either. */
.store {
  display: inline-flex; align-items: center; gap: .7rem;
  padding: .55rem 1.25rem .55rem .85rem; border-radius: 14px;
  border: 1px solid transparent; text-decoration: none;
  transition: background-color .15s, border-color .15s;
}
.store img, .store svg { width: 32px; height: 32px; flex: none; }
.store small {
  display: block; font-size: .68rem; letter-spacing: .08em;
  text-transform: uppercase; opacity: .85; line-height: 1.2;
}
.store strong { display: block; font-size: 1.2rem; line-height: 1.15; }
.store.fdroid {
  background: #1976D2; color: #fff;
  box-shadow: 0 10px 22px -12px rgba(25, 118, 210, .9);
}
.store.fdroid:hover { background: #1565C0; }
/* On a white tile: the logo's robot has a body in F-Droid's own blue, and on a
   button of that same blue only its green head showed. */
.store.fdroid img { width: 38px; height: 38px; padding: 4px; border-radius: 10px; background: #fff; }
.store.github { background: var(--raise); color: var(--ink); border-color: var(--line); }
.store.github svg { width: 28px; height: 28px; margin: 2px; }
.store.github:hover { border-color: var(--ink-mid); }
/* Side by side on a phone too: two buttons stacked read as two unrelated
   things, and at full size the pair is a few pixels too wide for 375px. */
@media (max-width: 26rem) {
  .store { gap: .5rem; padding: .5rem .85rem .5rem .6rem; }
  .store.fdroid img { width: 34px; height: 34px; padding: 3px; }
  .store.github svg { width: 24px; height: 24px; }
  .store strong { font-size: 1.08rem; }
}

/* Two phones, the second behind and to the side, on a soft glow of the accent.
   The frame is drawn, not photographed: a dark bezel and a shadow are enough
   to read as a phone, and they look right on both themes. */
/* A group of fixed width, centred, whatever the column: spread across the
   one-column layout of a mid-width window, the back phone drifted to the far
   edge, away from the front one, and its tilt was cut off by that edge. */
.phones {
  position: relative; display: flex; justify-content: center; isolation: isolate;
  width: 100%; max-width: 28rem; margin: 0 auto; padding: 1rem 0 1.5rem;
}
.phones::before {
  content: ""; position: absolute; z-index: -2; inset: 4% 0;
  background: radial-gradient(closest-side, color-mix(in srgb, var(--accent) 28%, transparent), transparent);
}
.phone {
  width: min(272px, 64%); padding: 8px; border-radius: 40px; background: #0D0F12;
  box-shadow: 0 0 0 1px var(--line), 0 34px 60px -26px rgba(10, 20, 40, .6);
}
/* The page's colour behind a screen still loading, not the bezel's black slab. */
.phone img { display: block; width: 100%; height: auto; border-radius: 32px; background: var(--surface); }
.phones .front { margin-right: 24%; transform: rotate(-2deg); }
/* Tilted 5 degrees, a phone this tall sticks out ~20px past its own box at the
   side and ~10px at the top, and on a phone the group clips at its edges - so
   6% in from the right rather than 2%, and not pinned to the top. */
.phones .back {
  position: absolute; z-index: -1; top: 1rem; right: 6%;
  width: min(232px, 54%); transform: rotate(5deg);
}
@media (max-width: 54rem) {
  .hero { grid-template-columns: minmax(0, 1fr); padding-top: 1.6rem; gap: 1.5rem; }
  /* Tighter on a phone, so the app shows on the first screen and is not
     below a screen of words. */
  .hero h1 { margin: .8rem 0 .9rem; }
  .hero .lead { font-size: 1.05rem; }
  .hero .cta { margin-top: 1.4rem; gap: .6rem; }
  .facts { margin-top: 1.1rem; font-size: .88rem; gap: .25rem 1rem; }
  /* Under the buttons on a phone, so the app is on the first screen - but
     only its top: a whole phone is taller than the window, and the fade says
     there is more without a screen of scrolling past it. */
  .phones {
    max-height: 26rem; overflow: hidden; padding-bottom: 0;
    -webkit-mask-image: linear-gradient(#000 65%, transparent);
            mask-image: linear-gradient(#000 65%, transparent);
  }
}

/* REASONS, NOT CARDS. Four sections of the home page were the same box - an
   icon in a rounded square, a bold title, grey words - and that box is what
   made the site read as a template (the user, 2026-10-27). A list of reasons
   is a list: a rule over each, the icon beside its title. The icons are
   Material's, the set the app draws from (ICONS). */
.reasons { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 2.4rem 2.6rem; }
.reason { border-top: 2px solid var(--ink); padding-top: 1.05rem; }
.reason h3 { display: flex; align-items: flex-start; gap: .6rem; margin: 0 0 .45rem; }
.reason h3 svg { width: 22px; height: 22px; flex: none; color: var(--accent); margin-top: .05rem; }
.reason p { margin: 0; color: var(--ink-mid); font-size: .96rem; }
@media (max-width: 62rem) { .reasons { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 38rem) { .reasons { grid-template-columns: minmax(0, 1fr); gap: 1.8rem; } }

/* Three steps on one line, because they are one road: numbered, joined, no
   boxes. On a phone the line runs down the left. */
.steps {
  list-style: none; padding: 0; margin: 0; counter-reset: step;
  display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 1.6rem 2.6rem;
}
.steps li { position: relative; padding-top: 3.6rem; display: flex; flex-direction: column; }
.steps li::before {
  counter-increment: step; content: counter(step);
  position: absolute; left: 0; top: 0; width: 2.5rem; height: 2.5rem;
  border-radius: var(--radius-sm); display: grid; place-items: center;
  font: var(--num-weight) 1.05rem var(--num-font); background: var(--num-bg); color: var(--num-ink);
  border: 2px solid var(--num-edge); text-shadow: 0 0 6px var(--num-glow);
}
.steps li::after {
  content: ""; position: absolute; top: 1.2rem; left: 3.3rem; right: -1.9rem;
  height: 2px; background: var(--line);
}
.steps li:last-child::after { display: none; }
.steps p { margin: 0 0 .9rem; color: var(--ink-mid); font-size: .96rem; flex: 1; }
.after-steps { margin: 1.4rem 0 0; color: var(--ink-mid); max-width: 46rem; }
@media (max-width: 54rem) {
  .steps { grid-template-columns: minmax(0, 1fr); gap: 1.8rem; }
  .steps li { padding: 0 0 0 3.6rem; }
  .steps li::after { left: 1.2rem; top: 3.1rem; right: auto; bottom: -1.3rem; width: 2px; height: auto; }
}

/* A LOOK AROUND: one screen large enough to read, and the rest as a strip
   that picks it. Seven phones in a row were about 120px wide each - a
   picture of a screen, not one you could read (2026-10-27). Without script
   the first screen shows and the strip does nothing. */
.tour {
  display: grid; grid-template-columns: minmax(0, 19rem) minmax(0, 1fr);
  gap: 2rem 4rem; align-items: center;
}
/* The strip and its words come first in the page, as a phone shows them
   and a keyboard reaches them; a wide window puts the phone on the left. */
.tour-phone { width: 100%; max-width: 19rem; margin: 0 auto; grid-column: 1; grid-row: 1; }
.tour-side { grid-column: 2; grid-row: 1; }
.tour-side h3 { font-size: 1.7rem; line-height: 1.2; letter-spacing: -.02em; margin: 0 0 .5rem; }
.tour-text { color: var(--ink-mid); margin: 0 0 1.6rem; min-height: 3.3em; max-width: 30rem; }
.thumbs { display: flex; flex-wrap: wrap; gap: .7rem; }
.thumbs button {
  width: 5.4rem; padding: 3px; border: 2px solid transparent; border-radius: 14px;
  background: #0D0F12; cursor: pointer; transition: transform .15s;
}
/* Under the phone's picture, the seven share one row: fixed thumbnails
   left one or two alone on a second row between about 650 and 920px. */
@media (max-width: 54rem) {
  .thumbs { display: grid; grid-template-columns: repeat(7, minmax(0, 1fr)); max-width: 44rem; margin: 0 auto; }
  .thumbs button { width: auto; }
}
/* Four to a row on a phone, sharing the width: fixed 4.4rem thumbnails came
   out three, three and one on the Honor (359px wide, 2026-10-27). */
@media (max-width: 38rem) {
  .thumbs { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: .5rem; }
  .thumbs button { width: auto; }
}
.thumbs img {
  display: block; width: 100%; height: auto; aspect-ratio: 420 / 520;
  object-fit: cover; object-position: top; border-radius: 10px;
}
@media (hover: hover) { .thumbs button:hover { transform: translateY(-2px); } }
.thumbs button[aria-pressed="true"] { border-color: var(--record); }
.thumbs button:focus-visible { outline: 2px solid var(--accent); outline-offset: 2px; }
@media (max-width: 54rem) {
  .tour { grid-template-columns: minmax(0, 1fr); }
  /* The strip above the phone: below it, a screen picked on a phone changed
     a picture already scrolled out of view (the Honor, 2026-10-27). */
  .tour-phone, .tour-side { grid-column: auto; grid-row: auto; }
  .tour-phone { max-width: 16rem; }
  .tour-side { text-align: center; }
  .tour-text { margin-left: auto; margin-right: auto; }
}
.shot {
  display: block; width: 100%; padding: 6px; border: 0; border-radius: 30px;
  background: #0D0F12; cursor: zoom-in;
  box-shadow: 0 0 0 1px var(--line), 0 22px 40px -26px rgba(10, 20, 40, .55);
  transition: transform .2s;
}
.shot img { display: block; width: 100%; height: auto; border-radius: 24px; }
@media (hover: hover) { .shot:hover { transform: translateY(-3px); } }
.shot:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; }
.gallery-note { color: var(--ink-mid); font-size: .92rem; margin: 2.2rem 0 0; max-width: 46rem; }

/* The videos: a still with a play button until someone presses play (see
   the script). 16:9 whether still or player, so nothing below it jumps when
   the one replaces the other. */
.video { margin: 2rem 0 0; max-width: 46rem; }
.video + .rows { margin-top: 3.5rem; }
.video-frame {
  position: relative; aspect-ratio: 16 / 9; border-radius: var(--radius); overflow: hidden;
  background: #0D0F12;
  box-shadow: 0 0 0 1px var(--line), 0 22px 40px -26px rgba(10, 20, 40, .55);
}
.video-frame a, .video-frame img, .video-frame iframe {
  position: absolute; inset: 0; width: 100%; height: 100%; border: 0; display: block;
}
.video-frame img { object-fit: cover; }
/* Dead centre, whatever the still shows: off to one side under the code
   panel it read as misplaced, not as the video's button (the user,
   2026-10-24). */
.video-frame .play {
  position: absolute; left: 50%; top: 50%; width: clamp(40px, 9%, 72px);
  aspect-ratio: 1; border-radius: 50%; background: rgba(13, 15, 18, .72);
  transform: translate(-50%, -50%);
  box-shadow: 0 6px 20px rgba(0, 0, 0, .35); transition: transform .2s, background .2s;
}
.video-frame .play::after {
  content: ""; position: absolute; left: 54%; top: 50%; transform: translate(-50%, -50%);
  border-style: solid; border-width: .45em 0 .45em .72em; font-size: clamp(22px, 2.6vw, 34px);
  border-color: transparent transparent transparent #fff;
}
.video-frame a:hover .play, .video-frame a:focus-visible .play {
  background: var(--record); transform: translate(-50%, -50%) scale(1.06);
}
.video-frame a:focus-visible { outline: 3px solid var(--accent); outline-offset: -3px; }
.video figcaption { margin-top: .7rem; color: var(--ink-mid); font-size: .92rem; }
.video figcaption strong { color: var(--ink); }

/* WHAT YOU CAN DO, BESIDE WHAT IT LOOKS LIKE. Eleven tiles of words were
   eleven more boxes; each row now puts a few features beside the screen,
   notification or measurement they are about (FEATURE_ROWS). */
.rows { display: flex; flex-direction: column; gap: 4.5rem; }
.row {
  display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 2rem 4.5rem; align-items: center;
}
.row:nth-child(even) .row-pic { order: -1; }
.row-text { display: flex; flex-direction: column; gap: 1.6rem; }
.feature h3 { font-size: 1.15rem; margin: 0 0 .35rem; }
.feature p { margin: 0 0 .35rem; color: var(--ink-mid); }
.row-pic { min-width: 0; display: flex; justify-content: center; }
.tiles-note { color: var(--ink-mid); margin: 3.5rem 0 0; max-width: 46rem; }
@media (max-width: 54rem) {
  .rows { gap: 3.5rem; }
  .row { grid-template-columns: minmax(0, 1fr); }
  .row:nth-child(even) .row-pic { order: 0; }
}

/* A part of a screenshot: the image, moved up inside a window cut to the
   part's height. Every look and theme has its own window (CROPS), because
   the looks lay the same screen out differently. */
.crop {
  width: 100%; max-width: 24rem; overflow: hidden; border-radius: var(--radius);
  border: 1px solid var(--line); background: var(--surface);
  box-shadow: 0 22px 40px -28px rgba(10, 20, 40, .55);
}
.crop img { display: block; width: 100%; height: auto; }

/* The three looks, side by side in the page's light or dark - each a button
   that puts the site in that look. */
.trio-box { width: 100%; }
.trio { display: flex; gap: .9rem; justify-content: center; width: 100%; }
.trio-note { margin: .9rem 0 0; text-align: center; font-size: .86rem; color: var(--ink-mid); }
.trio button {
  flex: 1 1 0; max-width: 9.5rem; padding: 0; border: 0; background: none;
  color: var(--ink-mid); cursor: pointer; font: .84rem var(--label);
}
.trio .mini {
  display: block; padding: 4px; border-radius: 20px; background: #0D0F12;
  border: 2px solid transparent; margin-bottom: .55rem; transition: transform .15s;
}
.trio img { display: block; width: 100%; height: auto; border-radius: 15px; }
@media (hover: hover) { .trio button:hover .mini { transform: translateY(-3px); } }
.trio button[aria-pressed="true"] { color: var(--ink); font-weight: 700; }
.trio button[aria-pressed="true"] .mini { border-color: var(--record); }
.trio button:focus-visible { outline: 2px solid var(--accent); outline-offset: 3px; border-radius: 6px; }

/* Notifications are Android's, not the app's: drawn in the phone's own
   type in every look. Their words are the app's (NOTIFICATIONS). */
.shade {
  width: 100%; max-width: 25rem; padding: .75rem; border-radius: 24px;
  background: var(--code-bg); display: flex; flex-direction: column; gap: .5rem;
  font-family: var(--sans);
}
.notif {
  background: var(--raise); border-radius: 18px; padding: .75rem 1rem .7rem;
  box-shadow: 0 1px 2px rgba(0, 0, 0, .1);
}
.notif .app { display: flex; align-items: center; gap: .4rem; font-size: .74rem; color: var(--ink-mid); }
.notif .app img { width: 16px; height: 16px; border-radius: 50%; }
.notif strong { display: block; font-size: .93rem; margin-top: .3rem; }
.notif p { margin: .1rem 0 0; font-size: .85rem; line-height: 1.42; color: var(--ink-mid); }
.notif .acts { display: flex; flex-wrap: wrap; gap: .3rem 1.3rem; margin-top: .55rem; font-size: .82rem; font-weight: 600; color: var(--accent); }

/* MEASURED, DRAWN TO SCALE. Each chart is a measurement the docs already
   give with its phone and date (MEASURES), every bar placed by its own
   numbers. HTML rather than an SVG, so its words stay readable on a phone. */
.chart { width: 100%; max-width: 31rem; margin: 0; }
.chart-title { font: 700 1rem/1.35 var(--display); margin: 0 0 1rem; }
.tl-row + .tl-row { margin-top: .85rem; }
.tl-label { display: block; font: 600 .82rem var(--label); margin-bottom: .3rem; }
.tl-track { position: relative; height: 2.1rem; }
.seg {
  position: absolute; top: 0; bottom: 0; display: flex; align-items: center;
  justify-content: center; overflow: hidden; white-space: nowrap; padding: 0 .3rem;
  font: 600 .78rem var(--label); border-radius: min(var(--radius-sm), 6px);
}
.seg.ring { background: var(--line); color: var(--ink-mid); }
.seg.talk, .seg.down { background: var(--record); color: var(--surface); }
.seg.ok { background: var(--play); color: var(--surface); }
.seg.range { background: color-mix(in srgb, var(--record) 30%, transparent); color: var(--ink); }
.seg.lost, .seg.none { background: none; border: 1.5px dashed var(--ink-mid); color: var(--ink-mid); }
.seg.none { border-color: var(--line); }
.tl-axis {
  position: relative; height: 1.5rem; margin-top: .55rem; border-top: 1px solid var(--line);
  font: .74rem var(--label); color: var(--ink-mid);
}
.tl-axis span { position: absolute; top: .3rem; transform: translateX(-50%); white-space: nowrap; }
.tl-axis span:first-child { transform: none; }
.tl-axis span:last-child { transform: translateX(-100%); }
.chart figcaption { margin-top: .9rem; font-size: .9rem; color: var(--ink-mid); }
.chart .src { display: block; margin-top: .35rem; font: .74rem/1.4 var(--label); opacity: .85; }
main:not(.home) .chart { margin: 1.6rem 0; max-width: none; }

.how { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 2rem 3.5rem; align-items: center; }
@media (max-width: 54rem) { .how { grid-template-columns: minmax(0, 1fr); } }
.numbered { list-style: none; padding: 0; margin: 0; counter-reset: n; }
.numbered li { position: relative; padding-left: 2.7rem; margin: 0 0 1.1rem; }
.numbered li::before {
  counter-increment: n; content: counter(n);
  position: absolute; left: 0; top: .1rem; width: 1.8rem; height: 1.8rem;
  border-radius: 50%; display: grid; place-items: center;
  font: var(--num-weight) .88rem var(--num-font); background: var(--num-bg); color: var(--num-ink);
  border: 2px solid var(--num-edge); text-shadow: 0 0 6px var(--num-glow);
}

/* The diagram's colours are the page's, so it changes theme with it. */
.diagram { display: block; width: 100%; max-width: 540px; height: auto; margin: 1.6rem auto; }
.dg-phone { fill: var(--raise); stroke: var(--line); stroke-width: 2; }
.dg-node { fill: var(--surface); stroke: var(--line); stroke-width: 1.5; }
.dg-node.key { stroke: var(--accent); stroke-width: 2; }
.dg-node.file { stroke: var(--play); stroke-width: 2; }
.dg-title { fill: var(--ink); font: 600 18px var(--body); text-anchor: middle; }
.dg-sub { fill: var(--ink-mid); font: 15px var(--body); text-anchor: middle; }
.dg-label { fill: var(--ink-mid); font: 15px var(--body); }
.dg-line { stroke: var(--ink-mid); stroke-width: 2; fill: none; }
.dg-head { fill: var(--ink-mid); }
.dg-num { fill: var(--num-bg); stroke: var(--num-edge); stroke-width: 2; }
.dg-num-text { fill: var(--num-ink); font: var(--num-weight) 15px var(--num-font); text-anchor: middle; dominant-baseline: central; }
/* The step being read, lit, and the others stepped back: pointing at a step
   in the list lights its part, and the first time the diagram comes into
   view it walks through the four once (the script). The movement is the
   order the recorder is built in, nothing else. */
.dg-head.lit { fill: var(--record); }
.dg-step, .numbered li { transition: opacity .25s, color .25s; }
.how-box.walking .dg-step:not(.lit) { opacity: .32; }
.dg-step.lit .dg-node { stroke: var(--record); stroke-width: 3; }
.dg-step.lit .dg-line { stroke: var(--record); marker-end: url(#dg-arrow-lit); }
.dg-step.lit .dg-num { fill: var(--num-lit-bg); stroke: var(--num-lit-edge); }
.how-box.walking .numbered li:not(.lit) { color: var(--ink-mid); }
.numbered li.lit::before { background: var(--num-lit-bg); border-color: var(--num-lit-edge); }
.how-box.solo .diagram { margin-bottom: 2rem; }

/* TWO CARDS THAT LINE UP. The headings are a row of their own, sitting on its
   bottom edge, so a heading that wraps does not push its card below the other;
   the cards are the next row and stretch to one height, and each holds three
   equal rows, so the table's rules and the phones' rules meet across the gap.
   It was a table beside a heading and a stack of cards, and neither the tops
   nor the bottoms agreed. On a phone each heading goes back above its card. */
.works {
  display: grid; grid-template-columns: minmax(0, 1.3fr) minmax(0, 1fr);
  gap: .9rem 2.5rem;
}
.works h3 { align-self: end; margin: 0; }
/* One row height for both cards, so the rules meet; the table gets the wider
   column, because at half the width its words wrapped. */
.works table { height: 100%; }
.works th, .works td { height: 4.75rem; vertical-align: middle; padding-left: .9rem; padding-right: .9rem; }
.works .devices li { min-height: 4.75rem; }
.works-note { margin: 1rem 0 0; }
/* Stacked well before a phone: below this the table, at its share of the
   width, wraps every label onto two lines. */
@media (max-width: 68rem) {
  .works { grid-template-columns: minmax(0, 1fr); }
  h3.versions { order: 1; }
  .table-card.versions { order: 2; }
  h3.tested { order: 3; margin-top: 1.4rem; }
  .devices.tested { order: 4; }
}
.table-card {
  background: var(--raise); border: 1px solid var(--line); border-radius: var(--radius);
  overflow: hidden;
}
.table-card table { margin: 0; }
.table-card th, .table-card td { padding: .95rem 1.1rem; }
.table-card tr:last-child td { border-bottom: 0; }
@media (max-width: 38rem) {
  .table-card table { font-size: .88rem; }
  .table-card th, .table-card td { padding: .8rem .65rem; }
}
/* Android's versions as a strip, a band for each answer: what was a table of
   "12 and 13" and "14 and newer" shows where the line falls. */
.strip { table-layout: fixed; }
.strip th:first-child { width: 31%; }
.strip th { text-align: center; font-family: var(--label); }
.strip th:first-child, .strip th[scope="row"] { text-align: left; color: var(--ink); }
.strip td { text-align: center; font-weight: 600; font-size: .9rem; line-height: 1.35; }
.strip td.y { background: color-mix(in srgb, var(--play) 14%, transparent); color: var(--play); }
.strip td.n { background: color-mix(in srgb, var(--ink-mid) 9%, transparent); color: var(--ink-mid); font-weight: 400; }
/* On a phone the labels take less and the cells pad less: two versions'
   width was too narrow for "Android does not allow it", which broke over five
   lines on the Honor (2026-10-27). */
@media (max-width: 38rem) {
  .strip th { font-size: .8rem; }
  .strip td { font-size: .8rem; }
  .strip th:first-child { width: 28%; }
  .strip th, .strip td { padding-left: .35rem; padding-right: .35rem; }
}
.devices { list-style: none; padding: 0; margin: 0; display: grid; grid-template-rows: repeat(3, 1fr); }
.devices li {
  padding: .75rem 1.1rem; font-weight: 600; border-bottom: 1px solid var(--line);
  display: flex; flex-direction: column; justify-content: center;
}
.devices li:last-child { border-bottom: 0; }
.devices li span { font-weight: 400; color: var(--ink-mid); font-size: .9rem; }

.band {
  margin-top: 5.5rem; padding-bottom: 4rem;
  background: var(--band); border-block: 1px solid var(--line);
}
.band .links { display: flex; flex-wrap: wrap; gap: .5rem 1.6rem; margin: 1.8rem 0 0; }

.faq { display: grid; grid-template-columns: minmax(0, .75fr) minmax(0, 1.6fr); gap: 1rem 3.5rem; align-items: start; }
@media (max-width: 54rem) { .faq { grid-template-columns: minmax(0, 1fr); } }
.faq details { border-bottom: 1px solid var(--line); }
.faq details:first-child { border-top: 1px solid var(--line); }
.faq summary {
  list-style: none; cursor: pointer; position: relative;
  padding: 1.05rem 2.4rem 1.05rem 0; font-weight: 600;
}
.faq summary::-webkit-details-marker { display: none; }
.faq summary::after {
  content: ""; position: absolute; right: .4rem; top: 50%;
  width: .55rem; height: .55rem;
  border-right: 2px solid var(--ink-mid); border-bottom: 2px solid var(--ink-mid);
  transform: translateY(-70%) rotate(45deg); transition: transform .2s;
}
.faq details[open] summary::after { transform: translateY(-25%) rotate(-135deg); }
.faq summary:hover { color: var(--accent); }
.faq details > div { padding: 0 0 1.1rem; color: var(--ink-mid); }
.faq details > div p { margin: 0 0 .6rem; }

.closing { text-align: center; }
/* The app icon only - the child combinator keeps this off the F-Droid logo
   inside the button below it, which it once turned into a block of its own. */
.closing > img { display: block; margin: 0 auto 1.1rem; border-radius: 22%; }
.closing .intro { margin-left: auto; margin-right: auto; }
.closing .cta { justify-content: center; align-items: center; }
/* Under the install buttons and smaller than them: getting the app comes
   first, and asking for money should never compete with it. */
.support { margin: 1.8rem auto 0; max-width: 34rem; color: var(--ink-mid); font-size: .95rem; }
.support span { color: var(--record); margin-right: .2rem; }

@media (prefers-reduced-motion: reduce) {
  html { scroll-behavior: auto; }
  .shot, .thumbs button, .trio .mini, .faq summary::after, .datum summary::after,
  .dg-step, .numbered li { transition: none; }
  .shot:hover, .thumbs button:hover, .trio button:hover .mini { transform: none; }
}

/* ── Deck: a pocket recorder ──
   The app's Deck, on the page (Theme.kt, LookColors.kt): engraved type
   throughout, the screen titles on an LCD in Doto - lit green in light, a
   negative that glows at night - keys that stand up off the face, and
   section labels in capitals with a rule to the edge. */
:root[data-look="deck"] h1, :root[data-look="deck"] h2 {
  text-transform: uppercase; letter-spacing: .03em; font-weight: 400;
}
/* The rule runs on from the heading's last line to the edge: an inline line
   as wide as the heading, cut off by it. Not a flex row - that would pull a
   heading's <code> or <em> apart into items of their own. */
:root[data-look="deck"] h2 { overflow: hidden; }
:root[data-look="deck"] h2::after {
  content: ""; display: inline-block; vertical-align: middle; width: 100%; height: 1px;
  margin: 0 -100% 0 1rem; background: var(--line);
}
:root[data-look="deck"] .faq h2::after, :root[data-look="deck"] .closing h2::after { display: none; }
:root[data-look="deck"] .hero .lcd, :root[data-look="deck"] main:not(.home) h1 {
  background: var(--lcd); color: var(--lcd-ink); border: 3px solid var(--lcd-edge);
  border-radius: 12px; box-shadow: inset 0 2px 10px rgba(0, 0, 0, .22);
  text-shadow: 0 0 9px var(--lcd-glow);
}
:root[data-look="deck"] main:not(.home) h1 {
  font-family: var(--lcdface); font-weight: 800; letter-spacing: .02em;
  padding: .55rem 1rem .45rem; line-height: 1.15;
}
:root[data-look="deck"] .hero .lcd { padding: 1.15rem 1.35rem 1.3rem; margin-bottom: 1.5rem; }
:root[data-look="deck"] .hero h1 {
  font-family: var(--lcdface); font-weight: 800; letter-spacing: .01em;
  margin: .8rem 0 0; font-size: clamp(2.1rem, 4.8vw, 3.3rem); line-height: 1.08;
}
:root[data-look="deck"] .hero h1 span { color: inherit; opacity: .62; }
/* The eyebrow as the LCD's small print: a boxed legend, as AUTO ANS MSG are. */
:root[data-look="deck"] .hero .eyebrow {
  background: none; color: var(--lcd-ink); border: 1.5px solid currentColor;
  border-radius: 3px; padding: .05rem .4rem; font-weight: 400; font-size: .76rem;
  text-transform: uppercase; letter-spacing: .04em;
}
:root[data-look="deck"] .theme, :root[data-look="deck"] .look {
  background: var(--key); border-color: var(--key-edge); box-shadow: 0 2px 0 var(--key-edge);
}
:root[data-look="deck"] .store { border-radius: 10px; }
:root[data-look="deck"] .store.github { background: var(--key); border-color: var(--key-edge); box-shadow: 0 3px 0 var(--key-edge); }
:root[data-look="deck"] .store.fdroid { box-shadow: 0 3px 0 #0D47A1; }
:root[data-look="deck"] .store strong { font-weight: 400; letter-spacing: .02em; }
:root[data-look="deck"] th { text-transform: uppercase; letter-spacing: .05em; font-weight: 400; font-size: .84rem; }
:root[data-look="deck"] .reason h3 svg { color: var(--ink); }
:root[data-look="deck"] .dg-node.key { stroke: var(--lcd-edge); }

/* ── Multitrack: a sound editor ──
   Space Grotesk for names and titles, JetBrains Mono for the rest; screen
   titles and labels in spaced capitals, the day markers' amber for table
   heads, a red tab line under what you point at, square-cornered lanes. */
:root[data-look="multitrack"] main:not(.home) h1 {
  font-family: var(--label); text-transform: uppercase; letter-spacing: .14em;
  font-weight: 600; font-size: clamp(1.35rem, 3.4vw, 1.75rem);
}
:root[data-look="multitrack"] .hero h1 span { color: var(--play); }
/* What is said about a thing in the editor's mono, as the app's smaller
   text is; the manual's running text stays in Space Grotesk. */
:root[data-look="multitrack"] :is(.intro, .reason p, .feature p, .steps p, .tour-text,
  .gallery-note, .tiles-note, .muted, .lead, figcaption, .faq details > div, .status p) {
  font-family: var(--label); font-size: .9rem; line-height: 1.6;
}
:root[data-look="multitrack"] .bar nav a, :root[data-look="multitrack"] .theme,
:root[data-look="multitrack"] .look, :root[data-look="multitrack"] .support-btn {
  text-transform: uppercase; letter-spacing: .1em; font-size: .74rem;
}
:root[data-look="multitrack"] .bar nav a { padding-bottom: .2rem; }
:root[data-look="multitrack"] .bar nav a:hover { color: var(--ink); box-shadow: 0 2px 0 var(--record); }
:root[data-look="multitrack"] .eyebrow {
  text-transform: uppercase; letter-spacing: .12em; font-size: .72rem; font-weight: 600;
  color: var(--play); background: color-mix(in srgb, var(--play) 13%, transparent);
}
:root[data-look="multitrack"] th {
  font-family: var(--label); text-transform: uppercase; letter-spacing: .1em;
  font-size: .72rem; color: var(--marker);
}
:root[data-look="multitrack"] .strip th[scope="row"], :root[data-look="multitrack"] .strip th:first-child { color: var(--marker); }
:root[data-look="multitrack"] .reason { border-top-width: 1px; border-top-color: var(--line); }
:root[data-look="multitrack"] .reason h3 svg { color: var(--play); }
:root[data-look="multitrack"] .store { border-radius: 6px; }
:root[data-look="multitrack"] .store small, :root[data-look="multitrack"] .faq summary,
:root[data-look="multitrack"] .tour-side h3 { letter-spacing: .02em; }
:root[data-look="multitrack"] .more { font-family: var(--label); font-size: .86rem; letter-spacing: .02em; }

/* EACH SCREEN IS ON THE PAGE TWICE, once per theme, and the page's theme
   decides which shows - the three states again, as with the palette. Both are
   loading="lazy", which is what keeps the hidden one from being fetched. The
   :root makes these outrank the rules that set an image's display. */
:root .dark-only { display: none; }
@media (prefers-color-scheme: dark) {
  :root:not([data-theme="light"]) .light-only { display: none; }
  :root:not([data-theme="light"]) .dark-only { display: block; }
}
:root[data-theme="dark"] .light-only { display: none; }
:root[data-theme="dark"] .dark-only { display: block; }

/* AND ONCE PER LOOK - Memo, Deck, Multitrack - the look's button hiding all
   but the one picked. Only ever hiding: the theme's rules above decide what
   shows, these take the other looks away, so they outrank them (:root twice).
   Memo, the app's default, is the page's; data-look is set for the others. */
:root:root:not([data-look="deck"]) .look-deck,
:root:root:not([data-look="multitrack"]) .look-multitrack,
:root:root[data-look] .look-memo { display: none; }

/* ── the screenshot viewer ── */

.viewer {
  position: fixed; inset: 0; z-index: 50;
  background: rgba(0, 0, 0, .82);
  display: flex; align-items: center; justify-content: center;
  padding: 2rem 1rem; cursor: zoom-out;
}
.viewer[hidden] { display: none; }
.viewer img {
  max-width: min(92vw, 420px); max-height: 92vh;
  border-radius: 14px; box-shadow: 0 24px 60px rgba(0, 0, 0, .5);
}
/* FITTING THE HEIGHT STOPS BEING AN ENLARGEMENT ON A SHORT WINDOW. These are
   phone screenshots at roughly 1:2.24 and the thumbnail is already ~230px wide,
   so in a docked browser pane 92vh can be SMALLER than the picture that was
   clicked. Below that point, size by width and let the overlay scroll -
   flex-start rather than centre, or the overflowing top cannot be reached. */
@media (max-height: 780px) {
  .viewer { align-items: flex-start; overflow: auto; }
}
.vnav {
  position: fixed; top: 50%; transform: translateY(-50%);
  width: 46px; height: 46px; border-radius: 50%;
  border: none; background: rgba(255, 255, 255, .14); color: #fff;
  font-size: 1.6rem; line-height: 1; cursor: pointer;
}
.vnav:hover { background: rgba(255, 255, 255, .26); }
/* Beside the picture where there is room for them, at the window's edge where
   there is not: the image is capped at 420px, so past a ~1000px window the
   arrows can sit outside it, and below that they overlap it. */
.vnav.prev { left: max(.6rem, calc(50vw - min(46vw, 210px) - 62px)); }
.vnav.next { right: max(.6rem, calc(50vw - min(46vw, 210px) - 62px)); }
.viewer.solo .vnav { display: none; }
.vcount {
  position: fixed; bottom: 1.2rem; left: 0; right: 0; text-align: center;
  color: rgba(255, 255, 255, .75); font-size: .85rem;
}
.viewer.solo .vcount { display: none; }
"""

JS = """// Generated by tools/build_site.py - edit there, not here.
(function () {
  var KEY = 'jemrec-theme', ORDER = ['system', 'light', 'dark'];

  function read() {
    try { return localStorage.getItem(KEY) || 'system'; } catch (e) { return 'system'; }
  }
  function apply(v) {
    var r = document.documentElement;
    if (v === 'system') r.removeAttribute('data-theme');
    else r.setAttribute('data-theme', v);
  }
  function label(btn, v) {
    var name = v.charAt(0).toUpperCase() + v.slice(1);
    btn.setAttribute('aria-label', 'Theme: ' + name + '. Click to change.');
    btn.querySelector('.theme-name').textContent = name;
  }

  // The look, as the theme: Memo, Deck, Multitrack, round - the whole site's,
  // screenshots included. Memo, the app's default, is no attribute at all.
  var LOOK_KEY = 'jemrec-look', LOOKS = ['memo', 'deck', 'multitrack'];
  function readLook() {
    try {
      var v = localStorage.getItem(LOOK_KEY);
      return LOOKS.indexOf(v) >= 0 ? v : 'memo';
    } catch (e) { return 'memo'; }
  }
  function applyLook(v) {
    var r = document.documentElement;
    if (v === 'memo') r.removeAttribute('data-look');
    else r.setAttribute('data-look', v);
  }
  function labelLook(btn, v) {
    var name = v.charAt(0).toUpperCase() + v.slice(1);
    btn.setAttribute('aria-label', 'Look: ' + name + '. Click to change.');
    btn.querySelector('.look-name').textContent = name;
  }

  document.addEventListener('DOMContentLoaded', function () {
    // The header's button and the three phones under Three looks set the
    // same thing, and each says which look is on.
    var lookBtn = document.querySelector('.look');
    var lookPicks = [].slice.call(document.querySelectorAll('[data-set-look]'));
    var curLook = readLook();
    function setLook(v, keep) {
      curLook = v;
      if (keep) { try { localStorage.setItem(LOOK_KEY, v); } catch (e) {} }
      applyLook(v);
      if (lookBtn) labelLook(lookBtn, v);
      lookPicks.forEach(function (b) { b.setAttribute('aria-pressed', String(b.dataset.setLook === v)); });
    }
    setLook(curLook, false);
    if (lookBtn) {
      lookBtn.addEventListener('click', function () {
        setLook(LOOKS[(LOOKS.indexOf(curLook) + 1) % LOOKS.length], true);
      });
    }
    lookPicks.forEach(function (b) {
      b.addEventListener('click', function () { setLook(b.dataset.setLook, true); });
    });

    var btn = document.querySelector('.theme');
    if (btn) {
      var cur = read();
      label(btn, cur);
      btn.addEventListener('click', function () {
        cur = ORDER[(ORDER.indexOf(cur) + 1) % ORDER.length];
        try { localStorage.setItem(KEY, cur); } catch (e) {}
        apply(cur);
        label(btn, cur);
      });
    }

    // Each video is a picture of it until someone presses play. A YouTube
    // embed loads Google's player, scripts and cookies with the page, for every
    // visitor, watching or not, and this site fetches nothing from anywhere else
    // - the privacy policy says so. So the player is fetched on the click that
    // asks for it, from YouTube's privacy-enhanced youtube-nocookie.com. Without
    // script the link simply opens the video on YouTube.
    document.querySelectorAll('.video-frame a[data-youtube]').forEach(function (a) {
      a.addEventListener('click', function (e) {
        e.preventDefault();
        var f = document.createElement('iframe');
        f.src = 'https://www.youtube-nocookie.com/embed/' + a.dataset.youtube +
                '?autoplay=1&rel=0';
        f.title = a.dataset.title || 'JemRec video';
        f.allow = 'autoplay; encrypted-media; picture-in-picture; fullscreen';
        f.allowFullscreen = true;
        f.referrerPolicy = 'strict-origin-when-cross-origin';
        a.replaceWith(f);
        f.focus();
      });
    });

    // A look around: the strip picks the screen in the phone.
    var tour = document.querySelector('.tour');
    function pick(name) {
      if (!tour) return;
      tour.querySelectorAll('.tour-screen').forEach(function (s) { s.hidden = s.dataset.screen !== name; });
      tour.querySelectorAll('.thumbs button').forEach(function (b) {
        var on = b.dataset.screen === name;
        b.setAttribute('aria-pressed', String(on));
        if (on) {
          tour.querySelector('.tour-title').textContent = b.dataset.title;
          tour.querySelector('.tour-text').textContent = b.dataset.text;
        }
      });
    }
    if (tour) {
      tour.querySelectorAll('.thumbs button').forEach(function (b) {
        b.addEventListener('click', function () { pick(b.dataset.screen); });
      });
    }

    // How it works: pointing at a step lights its part of the diagram, and the
    // first time the diagram is in view it walks through the four once, in the
    // order the recorder is built - unless the reader asked for less motion.
    document.querySelectorAll('.how-box').forEach(function (box) {
      var parts = box.querySelectorAll('.dg-step');
      var items = box.querySelectorAll('.numbered li');
      var timer = null;
      // The pointer that pressed, for the click after it: Safari's clicks
      // carry no pointerType of their own.
      var pressed = '';
      function light(n) {
        n = String(n || '');
        box.classList.toggle('walking', n !== '');
        parts.forEach(function (g) { g.classList.toggle('lit', g.getAttribute('data-step') === n); });
        items.forEach(function (li) { li.classList.toggle('lit', li.dataset.step === n); });
      }
      function stop() { if (timer) { clearTimeout(timer); timer = null; } }
      items.forEach(function (li) {
        li.addEventListener('pointerdown', function (e) { pressed = e.pointerType; });
        li.addEventListener('pointerenter', function (e) {
          if (e.pointerType === 'mouse') { stop(); light(li.dataset.step); }
        });
        li.addEventListener('pointerleave', function (e) { if (e.pointerType === 'mouse') light(''); });
        // A tap, where there is no pointing: lights it, or puts it out again.
        li.addEventListener('click', function () {
          if (pressed === 'mouse') return;
          stop();
          light(li.classList.contains('lit') ? '' : li.dataset.step);
        });
      });
      if (!('IntersectionObserver' in window) ||
          window.matchMedia('(prefers-reduced-motion: reduce)').matches) return;
      var seen = new IntersectionObserver(function (es) {
        if (!es[0].isIntersecting) return;
        seen.disconnect();
        var n = 0;
        (function next() {
          n += 1;
          if (n > 4) { timer = null; light(''); return; }
          light(n);
          timer = setTimeout(next, 1600);
        })();
      }, { threshold: 0.6 });
      seen.observe(box.querySelector('.diagram'));
    });

    // The manual's contents: open beside the text in a wide window, folded
    // above it in a narrow one, and the section being read marked.
    var toc = document.querySelector('.toc details');
    if (toc) {
      var wide = window.matchMedia('(min-width: 70rem)');
      var fit = function () { toc.open = wide.matches; };
      fit();
      if (wide.addEventListener) wide.addEventListener('change', fit);
      toc.addEventListener('click', function (e) {
        if (e.target.closest('a') && !wide.matches) toc.open = false;
      });
      var links = {}, current = null, list = toc.parentNode;
      toc.querySelectorAll('a').forEach(function (a) { links[a.getAttribute('href').slice(1)] = a; });
      var mark = function (id) {
        var link = links[id] || null;
        if (link === current) return;
        if (current) current.removeAttribute('aria-current');
        current = link;
        if (!current) return;
        current.setAttribute('aria-current', 'true');
        // Keep it in view inside the contents, without moving the page.
        if (current.offsetTop < list.scrollTop + 40 ||
            current.offsetTop > list.scrollTop + list.clientHeight - 60) {
          list.scrollTop = current.offsetTop - list.clientHeight / 3;
        }
      };
      // The section being read is the one whose heading is the last above a
      // line under the header. An observer of headings crossing a band missed
      // the way back: scrolled up into an earlier section, with its heading
      // off the top, the later one stayed marked.
      var heads = [].slice.call(document.querySelectorAll('main h2[id]'))
        .filter(function (h) { return links[h.id]; });
      // Each heading counts as reached a little below where a jump to it
      // lands - the page's scroll padding and the heading's own margin, which
      // a setup step's is larger by, for its progress bar. A fixed 120px
      // missed a step's 122px, and a jump to Step 4 marked Step 3.
      var pad = parseFloat(getComputedStyle(document.documentElement).scrollPaddingTop) || 0;
      var lines = heads.map(function (h) {
        return pad + (parseFloat(getComputedStyle(h).scrollMarginTop) || 0) + 40;
      });
      var queued = false;
      var spy = function () {
        queued = false;
        // Above the first heading - the title, the lead, the video - nothing.
        var at = null;
        for (var i = 0; i < heads.length && heads[i].getBoundingClientRect().top <= lines[i]; i++) at = heads[i];
        // At the very bottom the last sections cannot scroll up to that line.
        var d = document.documentElement;
        if (window.innerHeight + window.scrollY >= d.scrollHeight - 2) at = heads[heads.length - 1];
        mark(at ? at.id : '');
      };
      window.addEventListener('scroll', function () {
        if (!queued) { queued = true; requestAnimationFrame(spy); }
      }, { passive: true });
      spy();
    }

    // The privacy policy's rows fold, and a printer leaves a folded row's
    // words out - the words that are the policy. So they open to print, and
    // fold back as they were.
    var folded = [];
    window.addEventListener('beforeprint', function () {
      folded = [].slice.call(document.querySelectorAll('details.datum:not([open])'));
      folded.forEach(function (d) { d.open = true; });
    });
    window.addEventListener('afterprint', function () {
      folded.forEach(function (d) { d.open = false; });
      folded = [];
    });

    // Click a screenshot to see it properly. The thumbnail on the page is a
    // 420px copy; the viewer loads the full capture, which is the only place it
    // is worth a quarter of a megabyte. Escape closes, and so does clicking
    // anywhere - the whole overlay is the target, because hunting for a small
    // cross is the thing people complain about.
    //
    // It is a GALLERY: once one is open the arrows and the left and right keys
    // move through the set without closing it. Closing and reopening to see the
    // next of four is what makes a lightbox feel like a document viewer.
    var viewer = document.querySelector('.viewer');
    var all = [].slice.call(document.querySelectorAll('.shot'));
    if (!viewer || !all.length) return;
    // Each screen is on the page once per look and theme, and the strip shows
    // one screen at a time, so the set is every screen the stylesheet shows
    // in the page's look and theme - its own display, which a screen the
    // strip has not picked keeps - taken when the viewer opens, as either may
    // have changed since.
    var shots = [];

    var img = viewer.querySelector('img');
    var count = viewer.querySelector('.vcount');
    var at = -1;

    // Neighbours are fetched ahead: these are 250kB captures, and without it
    // every arrow press blanks the overlay while the next one loads.
    function warm(i) {
      [i - 1, i + 1].forEach(function (n) {
        var b = shots[(n + shots.length) % shots.length];
        if (b) { new Image().src = b.dataset.full; }
      });
    }
    function show(i) {
      at = (i + shots.length) % shots.length;
      var b = shots[at];
      img.src = b.dataset.full;
      img.alt = b.getAttribute('aria-label') || '';
      count.textContent = (at + 1) + ' / ' + shots.length;
      warm(at);
    }
    function step(d) { if (shots.length > 1) show(at + d); }

    // Hold the page still underneath. The overlay covers everything, so a wheel
    // over it scrolls a page nobody can see, and closing then lands somewhere
    // other than the screenshots. The padding replaces the scrollbar's own
    // width, or the whole page shifts sideways as the viewer opens.
    function lock(on) {
      var d = document.documentElement;
      if (on) {
        var gap = window.innerWidth - d.clientWidth;
        d.style.overflow = 'hidden';
        if (gap > 0) d.style.paddingRight = gap + 'px';
      } else {
        d.style.overflow = '';
        d.style.paddingRight = '';
      }
    }

    function close() {
      viewer.hidden = true;
      lock(false);
      img.removeAttribute('src');
      // Back to the thumbnail SHOWING, not the one clicked: after three arrow
      // presses the picture on screen is not the one that opened the viewer,
      // and landing the keyboard back on that one is disorienting.
      // The phone follows: it shows the screen the viewer was on.
      var b = shots[at];
      at = -1;
      if (b) {
        var screen = b.closest('.tour-screen');
        if (screen && screen.hidden) pick(screen.dataset.screen);
        b.focus();
      }
    }

    all.forEach(function (b) {
      b.addEventListener('click', function () {
        shots = all.filter(function (s) { return getComputedStyle(s).display !== 'none'; });
        viewer.classList.toggle('solo', shots.length < 2);
        viewer.hidden = false;
        lock(true);
        show(shots.indexOf(b));
        // tabindex="-1" is what makes this line do anything: a plain div is not
        // focusable, so focus would stay on <body> and the dialog would open
        // without announcing itself.
        viewer.focus();
      });
    });
    viewer.addEventListener('click', close);
    viewer.querySelectorAll('.vnav').forEach(function (b) {
      b.addEventListener('click', function (e) {
        e.stopPropagation();  // the overlay closes on any click, and these are inside it
        step(b.classList.contains('next') ? 1 : -1);
      });
    });
    document.addEventListener('keydown', function (e) {
      if (viewer.hidden) return;
      if (e.key === 'Escape') close();
      else if (e.key === 'ArrowRight') { e.preventDefault(); step(1); }
      else if (e.key === 'ArrowLeft') { e.preventDefault(); step(-1); }
    });
  });
})();
"""

# Before the stylesheet, and not deferred: a stored choice applied on
# DOMContentLoaded lets the system's colours paint first and then swaps them,
# which is a flash of the wrong theme on every page load.
HEAD_SCRIPT = (
    "<script>(function(){try{var v=localStorage.getItem('jemrec-theme');"
    "if(v&&v!=='system')document.documentElement.setAttribute('data-theme',v);"
    "var l=localStorage.getItem('jemrec-look');"
    "if(l==='deck'||l==='multitrack')document.documentElement.setAttribute('data-look',l);}"
    "catch(e){}})();</script>"
)

THEME_BUTTON = """<button class="theme" type="button" aria-label="Theme">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" aria-hidden="true">
          <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z"/>
        </svg><span class="theme-name">System</span>
      </button>"""

# The look - the site's and its screenshots' - on every page, beside the theme.
LOOK_BUTTON = """<button class="look" type="button" aria-label="Look">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
             stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
          <path d="M12 3 2 8l10 5 10-5-10-5Z"/><path d="m2 12 10 5 10-5"/><path d="m2 16 10 5 10-5"/>
        </svg><span class="look-name">Memo</span>
      </button>"""

# The app's three looks, as Settings > Theme names them; the screenshots are
# in a folder each (tools/shoot.py).
LOOKS = (("memo", "Memo"), ("deck", "Deck"), ("multitrack", "Multitrack"))

VIEWER = """<div class="viewer" hidden tabindex="-1" role="dialog" aria-modal="true"
     aria-label="Screenshot">
  <button class="vnav prev" type="button" aria-label="Previous">&lsaquo;</button>
  <img alt="">
  <button class="vnav next" type="button" aria-label="Next">&rsaquo;</button>
  <span class="vcount"></span>
</div>"""


# ---------------------------------------------------------------------------
# THE SETUP MANUAL, WRITTEN ONCE.
#
# It is rendered twice: as docs/setup.html for the site and as docs/SETUP.md for
# people reading the repository. The two used to be one hand-written file with
# the site not carrying it at all, and by the time the site wanted it the file
# had drifted badly from the app - it still asked for a Phone permission that
# had been removed, never mentioned USB debugging, and described a permanent
# notification that no longer exists. A manual that lies is worse than no
# manual, and two copies of one is how it happens, so there is one copy and two
# renderers.
#
# Blocks are ("kind", payload). Inline markup is the small Markdown subset the
# text actually uses: **bold** and `code`.
# ---------------------------------------------------------------------------

SETUP_TITLE = "Setting up JemRec"
SETUP_LEAD = (
    "Everything happens on the phone. You will not need a computer, a cable, or "
    "a second app. It takes about three minutes, and you do it once."
)

SETUP = [
    ("video", None),
    ("p", "Every step below was walked through on an Honor Magic 8 Pro running "
          "MagicOS 10, and the screen names are the ones that actually appear "
          "there. Other phones differ in wording; the shape of the process does "
          "not, and JemRec names the right path for your phone as you go."),

    ("h2", "Installing"),
    ("p", "**The easy way is F-Droid**: install the F-Droid client, search for "
          "JemRec, and updates arrive like any other app's. The listing is at "
          "`f-droid.org/packages/com.jemcik.jemrec`. None of the rest of this "
          "section applies — F-Droid is an installer the phone already trusts "
          "once you have allowed it."),
    ("p", "**Or download the APK** from the project's **Releases** page and open "
          "it. Android asks whether to allow installing from whatever you opened "
          "it with — a browser, or a file manager — and that permission is "
          "granted per app, not once for the phone."),
    ("p", "Either way you get the same file. F-Droid builds each release on "
          "their own machine, checks the result against the APK on the Releases "
          "page byte for byte, and publishes this project's signed APK rather "
          "than one of their own — so whichever way you installed it, the other "
          "can update it in place, with no uninstall and no pairing again."),
    ("p", "**Play Protect may object, and what it takes differs by phone.** It "
          "warns about apps it has not seen before, which is every app that is "
          "not on Google Play, so this is not a judgement about JemRec. Three "
          "phones, three outcomes:"),
    ("table", (["Phone", "What happened"], [
        ["Honor Magic 8 Pro", "Installed with no complaint."],
        ["Samsung Galaxy S20",
         "Refused. Turning Play Protect off long enough to install, then back "
         "on, worked — it is in the Play Store under your profile picture → "
         "Play Protect → Settings."],
        ["OnePlus on LineageOS",
         "Refused when the download was opened from the browser. Opening the "
         "same file from the **Files** app installed it."],
    ])),
    ("p", "If yours refuses, those are the two things to try, in that order: "
          "open the file from a file manager rather than the browser, and only "
          "then consider turning Play Protect off for the minute it takes — and "
          "back on afterwards."),
    ("p", "Every release is signed with the same key, so each one installs over "
          "the one before it. If you would rather check what you downloaded than "
          "trust it, `apksigner verify --print-certs` on the file should report "
          "SHA-256 `50aeab3630f0198ea3845db51413b1ad29582f78240fa2df6fe55ba8537afdf8`, "
          "the same digest the release workflow prints in its log on every run."),

    ("h2", "Step 1 — Open JemRec and allow notifications"),
    ("ol", [
        "Open **JemRec**.",
        "It asks for **notifications**. Allow them.",
        "It also offers to read audio files, which lets it list recordings made "
        "before a reinstall. Refusing that is fine; only the old ones are hidden. "
        "(On Android 12 the recordings list asks instead, for photos and media: "
        "Android 12 has no choice for audio alone.)",
    ]),
    ("p", "Notifications are not decoration here. They are how the app tells you "
          "a call is being recorded, how it asks whether to record one, and "
          "where you will type the pairing code in step 4."),
    ("p", "JemRec does **not** ask for microphone access and does not have it. "
          "The recording is done by a separate, more privileged helper that the "
          "app starts later; the app itself only collects the finished file."),
    ("p", "**Then one question.** Before you switch anything on, JemRec says what "
          "stays on: USB debugging and Wireless debugging, for as long as it is "
          "installed, turned back on if they go off. Android shows a notification "
          "for as long as debugging is on, and JemRec asks whether to hide it. "
          "Either answer works. Hidden, nothing on screen reminds you that "
          "debugging is on; you can change it later in Settings."),

    ("h2", "Step 2 — Turn on Developer options"),
    ("p", "Skip this if Developer options are already on."),
    ("ol", [
        "Open **Settings**.",
        "Search for `build number`. It usually lives under **About phone**, but "
        "the path differs between phones, which is why searching beats hunting.",
        "Tap **Build number** seven times.",
        "Enter your PIN or pattern if asked.",
    ]),
    ("p", "You should see *You are now a developer*."),
    ("p", "JemRec moves on by itself once it sees them on. Some phones on "
          "Android 17, Pixels from the September 2026 update among them, no "
          "longer tell apps whether Developer options are on, so JemRec cannot "
          "see it. There the card has a **They are on already** button: tap it "
          "once they are."),

    ("h2", "Step 3 — Turn on both debugging switches"),
    ("p", "**Be on Wi-Fi before you start this.** Wireless debugging only stays "
          "on while the phone has a Wi-Fi connection."),
    ("ol", [
        "In JemRec, tap **Open settings**, then search Settings for **Developer "
        "options**. The app also names the menu it usually lives in on your "
        "phone — though on a custom ROM such as LineageOS it is under "
        "**System**, whoever made the handset.",
        "Turn on **USB debugging** and **Wireless debugging**. Either order. No "
        "cable is needed and none should be plugged in.",
        "If Android asks whether to allow wireless debugging on this network, "
        "tick **Always allow** and tap **Allow**.",
        "Look at both switches once more. Some phones switch USB debugging off "
        "each time Developer options opens, so if it is off again when you come "
        "back to that screen, turn it on again. Once JemRec is set up, it turns "
        "it back on by itself.",
    ]),
    ("p", "**Why both.** Wireless debugging is the channel JemRec uses to start "
          "its recorder. USB debugging — the setting, with no cable involved — is "
          "what keeps Android's debugging service alive, and without it Wireless "
          "debugging switches itself off again before the app can connect."),
    ("note", "**On an OPPO, OnePlus or realme phone, one more switch:** "
             "**Disable system optimization**, the last one in Developer "
             "options' **Apps** section (**Disable permission monitoring** on "
             "older ones). Turn it on, and leave it on - or, where it says "
             "just **Permission monitoring**, turn that off and leave it off. "
             "Without it the phone refuses the setting JemRec grants itself in "
             "step 5, and the "
             "recorder could not turn debugging off when JemRec is "
             "uninstalled. Some phones show it only "
             "in English: if it is not there, set the phone's language to "
             "English, turn it on, and set the language back."),
    ("note", "If you see an old **JemRec** under *Paired devices*, tap the gear "
             "beside it and forget it. It is left over from a previous install "
             "and cannot be used: a reinstalled app has a new key and must pair "
             "again."),

    ("h2", "Step 4 — Pair, from your notifications"),
    ("p", "**Read this step before starting it.** The obvious approach does not "
          "work, and knowing why saves repeating it."),
    ("p", "The pairing dialog exists only while Settings is on screen. Leave "
          "Settings — by switching to JemRec, or by pressing Back — and Android "
          "tears the dialog down instantly, taking the code with it."),
    ("p", "The notification shade is different. It belongs to the system rather "
          "than to another app, so pulling it down leaves Settings running and "
          "the dialog intact. That is where you type the code, and JemRec posts "
          "the field before you go anywhere so it is already waiting."),
    ("ol", [
        "In JemRec, tap **Open settings**.",
        "Open **Wireless debugging**, then tap **Pair device with pairing "
        "code**. Six digits appear.",
        "**Swipe down** from the top of the screen, over that dialog.",
        "Find **JemRec — pairing** and tap **Enter code**.",
        "Type the six digits and send them.",
    ]),
    ("p", "You do not need the port number. JemRec finds that itself."),
    ("p", "Then watch that same notification. It says what is happening, and if "
          "a debugging switch turned itself off it says which one — turn it back "
          "on and the app connects by itself. There is no need to pair again or "
          "to fetch a new code."),
    ("note", "The code changes every time the dialog is reopened, so always use "
             "the one on screen. If you swiped the notification away, tap **Show "
             "the code field again** in JemRec to bring it back."),

    ("h2", "Step 5 — Wait a few seconds"),
    ("p", "JemRec does the rest by itself: it grants itself the one privileged "
          "setting it needs, starts the recorder, and begins watching for calls. "
          "When it is done the header says **Ready to record**."),
    ("p", "**If it stops at *Android would not let JemRec change debugging "
          "settings***, the phone refuses that setting until a switch of its "
          "own is set, and the card names it: on OPPO, OnePlus and realme "
          "**Disable system optimization**, set as step 3 says; on Xiaomi "
          "**USB debugging (Security settings)**, on. Then tap **Try again**; "
          "**Open settings** beside it opens Settings."),
    ("p", "There is no permanent notification. The app is dormant between calls "
          "— the recorder is a separate process that does the watching — so a "
          "notification appears for the length of a call, and one more when it "
          "ends, with the recording on it."),

    ("h2", "Step 6 — Test it"),
    ("p", "Call someone and talk for twenty seconds, with both of you speaking."),
    ("ul", [
        "During the call a notification says **Recording call**.",
        "When you hang up, a notification shows who the call was with and how "
        "long it was, with **Play**, **Share** and **Delete**. The recording is "
        "also at the top of JemRec's list.",
    ]),
    ("p", "Files are named like `20260907_150909_out.ogg` — date, time, and `in` "
          "or `out` for the direction. They are ordinary Ogg Opus files that any "
          "player can open."),
    ("p", "If your recording has only your own voice on it, that is the one thing "
          "no app can fix: your phone's audio hardware does not hand the other "
          "side to apps at all. **Settings → Diagnostics → Self-test** answers "
          "that in about a second, without ringing anybody."),

    ("h2", "Where recordings go"),
    ("p", "The phone's standard **Recordings/JemRec** folder, which any file "
          "manager or music app can open, and which survives uninstalling the "
          "app. To use a different folder, open Settings and tap **Change** under "
          "*Saving to*."),

    ("h2", "Choosing when to record"),
    ("p", "**Record every call**, in Settings, decides this."),
    ("ul", [
        "**On**, the default: every call is recorded and you are not asked.",
        "**Off**: when a call starts a notification appears with **Record** and "
        "**Not now**. Nothing is captured until you tap Record, so the opening "
        "seconds of that call are not saved. That is the honest cost of being "
        "asked, and it is why the switch is on by default.",
    ]),
    ("p", "The prompt stays up for about a minute rather than the few seconds an "
          "ordinary notification gets, and if you miss it, it waits in the shade "
          "for the rest of the call. On a locked screen you get the same question "
          "full screen."),

    ("h2", "Exact call tracking"),
    ("p", "**Exact call tracking**, in Settings, is on unless you turn it off. "
          "With it, Android tells JemRec when each phone call is answered and "
          "when it ends, and so:"),
    ("ul", [
        "A recording starts when the call is answered. An outgoing call's "
        "ringing is not in it, and a call nobody answered is not saved at all.",
        "With call waiting, each call gets its own recording.",
        "Whose calls to keep decides before anything is recorded.",
    ]),
    ("p", "Tracking only reads this: it never answers, holds or ends a call, and "
          "shows no screen of its own during one. The one thing that ever "
          "answers a call is the answering machine, below, when you switch it "
          "on. Turned off, JemRec records from the moment the phone goes "
          "off-hook, as it did before 0.14, and the answering machine, which "
          "needs it, answers nothing."),

    ("h2", "Whose calls to keep"),
    ("p", "**Whose calls to keep**, in Settings, narrows down which calls are "
          "kept. **Your contacts**: all of them, all but some, only some, or "
          "none — for the last three you pick the contacts. **Other numbers**, a "
          "switch of its own: numbers not in your contacts, and hidden ones. "
          "Until you change either, every call is kept."),
    ("p", "With exact call tracking, JemRec knows who a call is with while it "
          "still rings, and a call your choice leaves out is not recorded at "
          "all. Without it, who a call was with is only known once it has ended, "
          "from the call log: every call is recorded, and one your choice leaves "
          "out is deleted the moment it ends, before it is saved anywhere. "
          "Either way, if JemRec cannot tell who it was, the recording is kept. In ask-first "
          "mode, a call you tap Record on is always kept. The Calls tab marks a "
          "call that was left out."),
    ("p", "It needs permission to read the call log and your contacts, to tell "
          "who a call was with; its screen asks for it if you have not given it."),

    ("h2", "File names"),
    ("p", "**File names**, in Settings, decides what a new recording is called. "
          "Recordings you already have keep their names."),
    ("ul", [
        "**Date and time**, the default: `20261020_165705_out.ogg` — when the "
        "call began, and which way it went.",
        "**With the caller**: `20261020_165705_out_Priya Raman.ogg` — easier to "
        "find in a file manager or on a computer.",
        "**With the phone number**: `20261020_165705_out_+447700900123.ogg` — the "
        "number with its country code, even a contact's, for a program that sorts "
        "recordings by number. A hidden number leaves the date and time; a "
        "messenger call has no number, so its name ends with the app.",
        "**For Honor's Phone app**, on a phone that has it: "
        "`Priya Raman@+447700900123_20261020165705.ogg`, the name Honor's own "
        "recorder uses. Saved in `Sounds/CallRecord` — the File names dialog has "
        "a button that opens the folder picker right there; tap **Use this folder** and "
        "**Allow** — Honor's Phone app then shows a play button on the call in "
        "its history.",
    ]),
    ("p", "The caller and the number come from exact call tracking or the call "
          "log, with permission to read the call log; without it, a recording is named by "
          "date and time. Google's Phone app has nothing "
          "like Honor's: it keeps its own recordings to itself, and no other app "
          "can add one to its call history."),

    ("h2", "Messenger calls"),
    ("p", "**Messenger calls**, in Settings, records WhatsApp, Telegram, Signal "
          "and Viber calls too, and Google Meet calls and meetings — both sides, "
          "you on the left channel and the other person on the right (up to "
          "JemRec 0.24, the other way round). Each app "
          "has its own switch, and all are off until you turn one on. The ones "
          "installed on your phone are listed."),
    ("p", "It needs **Android 14 or newer**. Before it, Android does not let the "
          "recorder copy another app's call audio, and Settings says so instead "
          "of offering switches that would record nothing."),
    ("p", "An app can refuse to let its calls be recorded, and Android honours "
          "that; JemRec does not try to get round it. Your side comes from the "
          "microphone without the call app's echo cancellation, so on "
          "speakerphone the other person is heard on your side as well. JemRec "
          "never takes the microphone from the call: if Android ever silences "
          "the app's own, JemRec lets go of its own at once."),
    ("p", "These are not phone calls, so their recordings are in Recordings, "
          "marked with the app's icon, and not in the Calls tab. Whose calls to "
          "keep does not apply to them — the app's switch is the rule."),
    ("p", "**Google Meet** puts its calls in the phone's call log, through "
          "Android's own call service, and up to JemRec 0.21 they were taken for "
          "phone calls: every Meet call and meeting was recorded, with no switch "
          "for it. Now Meet is recorded only if you switch it on here, and its "
          "calls stay out of the Calls tab. A phone call during a Meet call is "
          "a phone call of its own, recorded on its own - from when the Meet call "
          "ends, if that one is being recorded. Calls other apps put through "
          "that service — Gmail's, Slack's — are not recorded."),
    ("p", "**Show who called** reads the caller's name, and whether the call rang "
          "first, from the app's own call notification — the only place it is "
          "said. That needs notification access, which Android grants to all "
          "notifications; JemRec keeps only that from the call notifications of "
          "the apps you switched on. It is granted over JemRec's own ADB "
          "session, so turning it on needs Wi-Fi, once."),
    ("p", "Tested on an Honor with Viber, Telegram and WhatsApp, both directions, "
          "and with Google Meet: calls both ways and a meeting. Signal takes the "
          "same path and has not been tested yet."),

    ("h2", "The answering machine"),
    ("p", "**Answering machine**, in Settings, answers the phone calls you do not "
          "take, plays a greeting you recorded, and keeps what the caller says as "
          "a recording. Phone calls only: not WhatsApp, Telegram, Viber or Google Meet calls. It is off until you turn it on, and it needs two things "
          "first: your greeting, and the checks under *Test on this phone*."),
    ("p", "**Your greeting.** Tap **Record**, say it, and tap **Stop** — 30 "
          "seconds at most. JemRec trims the silence at both ends and evens the "
          "level, because the caller hears it at full volume. Tap the row to "
          "hear it; **Record again** replaces it. Callers hear what you said and "
          "then a beep, nothing else."),
    ("p", "**When it answers**"),
    ("ul", [
        "**Answer after** — 5 to 30 seconds of ringing. Your carrier's own "
        "voicemail, if you have it, takes calls after its own timer, so this "
        "has to be shorter; once you have asked your carrier (below), the row "
        "says if its voicemail would answer first.",
        "**With Do Not Disturb on** — after two rings, about 5 seconds, for "
        "callers Do Not Disturb would silence. Callers it lets through ring as "
        "usual.",
        "**Answer on** — with two SIMs, which one: your default for calls, or "
        "both.",
        "**Longest message** — how long a caller can speak after the beep: 30 "
        "seconds, 1 minute or 2 minutes. Then JemRec hangs up, and the message "
        "is saved. Or **No limit**, which asks first: the call then lasts until "
        "the caller hangs up or has been silent for 30 seconds, and your phone "
        "is in it until then - a caller somewhere noisy may never count as "
        "silent.",
    ]),
    ("p", "**Who it answers** is not a setting of its own: it answers only the "
          "callers JemRec records, so **Whose calls to keep** applies to both. "
          "It never answers while you are on another call - a phone call, a "
          "messenger call or a meeting - while roaming, or while recording is "
          "off, and a caller JemRec cannot identify is left to ring. It needs Exact call tracking: "
          "turning tracking off with the machine on asks first, then turns both off."),
    ("p", "**After you call an emergency number** it stands down for a day, so "
          "an emergency service calling you back - from any number - rings until "
          "you can pick up; a call the network marks as their callback is never "
          "answered. The switch says until when, and **Answer again now** ends "
          "the pause early, after a warning. iPhone does the same with its call "
          "screening, for 24 hours."),
    ("p", "**While it takes a message**, your microphone is muted: the caller "
          "hears your greeting, the beep, and nothing of the room you are in. A "
          "notification, **Taking a message**, has **Talk to them** — the "
          "greeting stops, your microphone opens and the call is yours — and "
          "**Hang up**. Unmuting on the call screen takes the call too, and so "
          "does answering a second call, one waiting or one you place. A call you "
          "take is saved as an ordinary recording, not a message, from just "
          "before you took it - or from the caller's first words, if they spoke "
          "first. In **Ask first** it is not: taking the call ends the message "
          "there - what the caller said is kept as the message - and you are "
          "asked, as at the start of any call, whether to record the rest. "
          "Answering machines have always stopped recording when picked up."),
    ("p", "**The message** is saved like any recording, from just before the "
          "caller starts speaking, so it does not begin with them listening to "
          "your greeting. It has a message icon, the **Messages** chip above "
          "the list shows only messages, and a notification with a sound says "
          "who left it. Auto-delete for short calls never removes one. A caller "
          "who hangs up during the greeting without a word leaves nothing; the "
          "Calls tab says **Hung up during the greeting**."),
    ("p", "**Test on this phone.** No app can play sound into a call, so the "
          "recorder does it, as the shell, and whether a phone carries it to "
          "the caller depends on its audio hardware. These tell you:"),
    ("ul", [
        "**Checks** — a second, and no call: whether the recorder has what it "
        "needs and the phone has a way to play into a call. The switch can be "
        "turned on once they pass.",
        "**Test call** — best, someone calls you: the next call within three "
        "minutes is answered with a short tone and your greeting, whoever is "
        "calling, and then you are asked whether they heard it - not in the "
        "day after an emergency call, when that caller may be them. Or JemRec "
        "calls a number that answers by itself, such as your carrier's "
        "information line, and measures what it can; never an emergency "
        "number. 2G, VoLTE and Wi-Fi Calling are kept apart, because each "
        "takes its own path.",
        "**Carrier voicemail** — asks your carrier when its voicemail answers, "
        "and can make it wait the longest it allows, 30 seconds. Your "
        "carrier's voicemail then still takes the calls JemRec does not. It is "
        "your carrier's setting, so it stays if JemRec goes.",
        "**Copy a report** — this phone and what the tests found, with nothing "
        "about any call, to paste into a report on GitHub.",
    ]),
    ("p", "Experimental. Tested on an Honor, on 2G calls (its carrier would not "
          "register it for VoLTE), and on a Galaxy S23 over Wi-Fi Calling. On a "
          "phone not tried yet, the test call is how to know."),

    ("h2", "Playing, sharing and deleting"),
    ("p", "Recordings are listed newest first, under **Today**, **Yesterday** "
          "and **Earlier**. "
          "Tap a recording to play it inside the app. Its waveform appears under "
          "the row - tall where someone is talking, flat where it is quiet - and "
          "a tap or a drag on it goes to that place. A call's waveform is worked "
          "out as it is saved, and an older recording's while the app is open; "
          "one played before then has a plain slider for a moment - about two "
          "and a half seconds for a 30-minute call. The chevron beside it folds "
          "the player away. "
          "The share button offers the original file or a converted copy that "
          "plays anywhere."),
    ("p", "**With the screen off.** A recording plays on when the screen goes "
          "off or you leave the app, under the phone's own media controls: in "
          "the notifications, in Quick Settings, on the lock screen, and on an "
          "Honor in the pill at the top of the screen. They name the caller "
          "only once the phone is unlocked; locked, they say **Call recording**. "
          "A call ringing in pauses it, and it plays on after; headphones coming "
          "out pause it until you play it again. It does not start during a "
          "call. The notification's pause, or the chevron, stops it; swiping "
          "JemRec away leaves it playing, as music apps do."),
    ("p", "**Finding a recording.** The chips above the list narrow it: "
          "favourites, the phone or a messenger, kept, messages. The magnifier "
          "opens a search by name, number or time. Chips of one kind add up - "
          "WhatsApp and Telegram - and different kinds narrow: and favourites. "
          "A chip only appears where it would narrow the list, and one that is "
          "on stays, so you can turn it off. While anything narrows the list, a "
          "line under the chips says what, and how many of how many are shown; "
          "**Show all** brings everything back."),
    ("p", "To delete, tap a contact's photo or long-press a row to start "
          "selecting, tick what you want, and use the bin in the toolbar. It asks "
          "first and tells you how many."),
    ("p", "**Deleting is not immediate.** A deleted recording goes to **Recently "
          "deleted**, in Settings, waits there 30 days, and can be "
          "restored until then. After that it is gone for good. In the standard "
          "Recordings folder this is Android's own trash, so other apps stop "
          "seeing the file at once."),
    ("p", "**Keep what matters.** The bookmark on each row marks a recording to "
          "keep: auto-delete never touches it, and the bookmark chip above the "
          "list shows only kept recordings. To mark several, select them and "
          "use the bookmark in the toolbar."),
    ("p", "The notification after each call has the same three buttons. Play and "
          "Share open JemRec at that recording; Delete asks first, in the "
          "notification itself, and **Keep** puts it back."),
    ("p", "If a recording was made before JemRec was reinstalled, Android asks "
          "before JemRec may move it to the trash, in a dialog of its own. That "
          "is Android checking who owns the file, not JemRec asking twice."),
    ("p", "Recordings are kept when JemRec is uninstalled, because they are yours "
          "rather than the app's — so if you want them gone, delete them **before** "
          "you uninstall. Recently deleted ones in the standard folder are still "
          "emptied by Android on time. In a folder you chose, they stay behind as "
          "hidden files, because removing them is JemRec's job."),
    ("p", "Installed again, JemRec shows your recordings as they were, once you "
          "allow it to read them: which are messages, which you kept, and who "
          "each messenger call was with are written inside each recording, not "
          "only in the app. A folder you chose has to be chosen again, and until "
          "then its recordings are not listed."),

    ("h2", "The Calls tab"),
    ("p", "The **Calls** tab, beside Recordings, is your phone's call log, under "
          "the same day headings. A call "
          "that was recorded has the ▶ beside its length and plays on a tap; one "
          "the answering machine took says **Message**. A call "
          "that was not says why, where JemRec knows: **In Recently deleted**, "
          "**Recording deleted**, **Left out by your rules**, **Too short**, "
          "**Recorded, not saved yet** (still on the recorder, collected when it "
          "can be), "
          "**Recording was off**, **Ask first, not recorded**, **Hung up during "
          "the greeting**, **Answering machine test**. **Not recorded**, "
          "in red, means recording was on and automatic and nothing was saved — "
          "run the self-test. **Greeting could not play**, in red too, means the "
          "answering machine answered and hung up without taking a message - "
          "the greeting would not play, or your microphone would not mute — run "
          "its test call. JemRec remembers deleted and left-out calls for a "
          "week, so it says this only for the last week. **No recording** is an "
          "older call, or one from before JemRec kept track, with no guess at "
          "why. Missed, declined and blocked calls are marked as such. Calls "
          "Google Meet puts in the call log are not phone calls and are not "
          "listed: see Messenger calls."),
    ("p", "Its chips work as the Recordings tab's, with **Not recorded** for "
          "the calls that connected and were not recorded; its search finds a "
          "name or a number. When nothing matches, it says so."),
    ("p", "It needs permission to read the call log, the same optional permission "
          "that puts names on recordings; the tab asks for it if you have not "
          "given it."),

    ("h2", "Three looks"),
    ("p", "**Settings → Theme** changes how JemRec looks, never what it does. "
          "**Memo**, the default, is calm and simple, with one big record button; "
          "**Deck** is a pocket recorder, with a REC key and an LCD; "
          "**Multitrack** is a sound editor, each caller in a colour of their "
          "own. Under them, **Light**, **Dark** or **System**, as the phone is "
          "set. The preview at the top shows the look you pick, with invented "
          "calls, and the app changes at once. Notifications and the Quick "
          "Settings tile always follow the phone."),
    ("p", "The header holds the same things in every look: the record button, "
          "which turns recording on and off; whether JemRec can record right "
          "now, and if not, what to do; a line of what is on - in Memo in words "
          "and icons, on Deck's LCD as **AUTO** or **ASK**, **ANS**, **MSG** and "
          "**DEL** with the age, in Multitrack as a row of LEDs, lit for what is "
          "on; the gear "
          "for Settings; and the **Recordings** and **Calls** tabs, Recordings "
          "with how many there are. The settings the header marks wear the same "
          "mark in Settings. With the phone on its side, the header becomes a "
          "panel at the left - the chips and search in it, with their names, the "
          "tabs at its foot - and the list beside it takes the full height."),

    ("h2", "Turning JemRec off"),
    ("p", "The record button in the header - Memo's round button, Deck's REC "
          "key, Multitrack's armed button - is the on/off control for the whole "
          "app. Off "
          "means no call is recorded and no notification appears. Your recordings "
          "and your pairing are untouched, and the recorder itself keeps running "
          "in the background doing nothing — which is what makes turning it back "
          "on instant, anywhere, with no network."),
    ("p", "The same switch is a **Quick Settings tile**, so you can flip it "
          "without opening the app. Settings has a row that adds it, on "
          "Android 13 and later; on 12, edit the Quick Settings panel and drag "
          "JemRec in. On a locked phone the tile asks for your unlock first. A "
          "long press on the tile opens JemRec."),

    ("h2", "Off, and debugging with it"),
    ("p", "Some banking apps won't open while Developer options or debugging is "
          "on. **Settings → When JemRec is off** chooses what else goes off when "
          "you turn JemRec off, from the record button or the tile:"),
    ("table", (["Choice", "Switches off", "Back on"], [
        ["**Keep debugging on** (the default)", "nothing else", "instantly, anywhere"],
        ["**Hide Developer options**", "Developer options, Wireless debugging",
         "instantly, anywhere"],
        ["**Turn all debugging off**", "Developer options, Wireless debugging, "
         "USB debugging", "needs Wi-Fi - or Start without Wi-Fi - about 15 "
         "seconds; no answering machine until then"],
    ])),
    ("p", "Try **Hide Developer options** first: USB debugging stays on, so the "
          "recorder keeps running and JemRec is back the moment you turn it on. "
          "If your bank still refuses, **Turn all debugging off** - which stops "
          "the recorder too, so away from Wi-Fi JemRec asks before it does. "
          "With **Start without Wi-Fi** on, the first leaves debugging port 5555 "
          "open while JemRec is off, which a bank may look for too; the second "
          "closes it. Turning JemRec back on puts back what it switched off. A "
          "new choice applies the next time you turn JemRec off."),

    ("h2", "Start without Wi-Fi"),
    ("p", "Starting the recorder takes Wireless debugging, and Android allows that "
          "only on a Wi-Fi network the phone has joined - not on its own hotspot, "
          "not on mobile data. **Settings → Start without Wi-Fi** opens a second "
          "way in: debugging port 5555, which works with no network at all. With "
          "it on, turning JemRec back on after **Turn all debugging off** works "
          "anywhere, and so does every restart of the recorder - after a cable "
          "from a computer, say - until the phone restarts."),
    ("p", "JemRec opens the port the next time it starts the recorder on Wi-Fi; "
          "switched on while on Wi-Fi, that is at once - after a call in progress, "
          "and, if When JemRec is off has switched debugging off, once JemRec is "
          "back on - and the recorder restarts for a few seconds. On a Wi-Fi "
          "Android has not allowed Wireless debugging on, Android first asks "
          "**Allow wireless debugging on this network?**: tap Allow, or the port "
          "waits for a Wi-Fi where it is allowed. A phone restart closes the "
          "port, and the first start after it needs Wi-Fi once, as before. "
          "Switching it off closes the port the same way, the next time it can."),
    ("p", "**What it costs:** port 5555 is open on every network the phone joins "
          "- a hotel's Wi-Fi too - whenever USB debugging is on: while JemRec is "
          "on, and while it is off unless **Turn all debugging off** goes with it. "
          "**Hide Developer options** keeps USB debugging on, so with it the port "
          "stays open while JemRec is off, and the phone announces it on each "
          "network by its serial number. A computer that reaches the port, or "
          "an app on the phone, cannot debug the phone without your yes, but it "
          "can put the question on screen; do not allow one you do not expect. With all debugging off, "
          "nothing listens. Measured on the Honor, Android 17."),

    ("h2", "After a restart"),
    ("p", "A reboot kills the recorder, and building it again takes a few seconds "
          "of Wi-Fi. Until that has happened, calls are not recorded and the "
          "header says so — and if it lasts more than two minutes, a notification "
          "does too. Whether it happens without you comes down to one setting on "
          "your phone."),
    ("h3", "Allow JemRec to start automatically"),
    ("p", "This is the fix, and it is worth doing once. JemRec's own Settings has "
          "a **Survive a restart** row that opens the right screen "
          "on your phone, because the list is four levels deep and called "
          "something different on every make. On an Honor it is *Settings → "
          "Battery → App launch*; find JemRec, switch it to **Manage manually** "
          "and leave **Auto-launch** on. Xiaomi calls it Autostart, OPPO and vivo "
          "have their own. The row only appears on a phone that has such a "
          "list."),
    ("p", "What it buys, measured on that Honor: the app was started by the boot "
          "broadcast at 21 seconds of uptime, armed its scheduled job and asked "
          "the system to wake it when Wi-Fi appeared, and the recorder was back "
          "**ten seconds** after Wi-Fi came on — with the app never opened."),
    ("p", "On MagicOS 11 a restart also leaves Wireless debugging switched on "
          "without starting it. Since 0.25.1 JemRec sees that and switches it "
          "off and on itself: recording again **eleven seconds** after its "
          "first try, which came as the phone was unlocked, with the app never "
          "opened."),
    ("h3", "Without it, open JemRec once"),
    ("p", "The same phone with auto-launch off does nothing at all. The system "
          "declined to start the app for the boot broadcast — *don't meet "
          "cpuload*, in its own log — so neither the job nor the Wi-Fi watcher "
          "was ever armed, and seven minutes after Wi-Fi returned nothing had "
          "come back. Opening the app had it recording sixteen seconds later."),
    ("p", "So if you would rather not touch battery settings, treat opening "
          "JemRec once as part of restarting the phone. It always works."),
    ("p", "Either way: Wi-Fi is needed to rebuild the recorder, never to use it. "
          "Once it is up, calls are recorded with Wi-Fi off, on mobile data, "
          "anywhere."),

    ("h2", "Unplugging from a computer"),
    ("p", "On some phones, pulling the cable out of a computer stops the "
          "recorder: measured on an Honor, three seconds after the cable came "
          "out. A charger does not. JemRec starts it again by itself - on "
          "Wi-Fi, within seconds; off Wi-Fi, at the next Wi-Fi, as after a "
          "restart. Nothing to open - but with the screen off, which is how a "
          "cable usually comes out, only if JemRec may start automatically "
          "(the setting just above): measured on that Honor, 13 seconds with "
          "it; without it, the phone's battery manager froze JemRec until the "
          "phone was woken or charged, about four minutes."),

    ("h2", "Stopped in the background"),
    ("p", "Some phones stop apps in the background to save power. The recorder "
          "runs on, but a stopped JemRec cannot start it again once it goes - "
          "after an unplug or a restart - so calls go unrecorded until the app "
          "is opened. The same **App launch** setting as above decides it. Measured on an Honor with App "
          "launch on *Manage automatically*: turning the screen off had the "
          "phone stop JemRec eight seconds later, and nothing brought the "
          "recorder back until the app was opened. On *Manage manually* the "
          "recorder came back on its own in fifteen seconds."),
    ("p", "JemRec cannot read or change that setting, so it does what it can, "
          "only on a phone that restricts apps this way. **Setup asks you**, "
          "once, with a button that opens the phone's own screen for it and a "
          "line saying what to do there in that phone's words: *Manage manually* "
          "on an Honor or Huawei, a switch in Autostart on a Xiaomi, *Never "
          "sleeping apps* on a Samsung. And if the phone stops JemRec anyway, "
          "**it tells you** — the moment it happens if it can, otherwise the "
          "next time JemRec starts — with a notification that says the same "
          "and has the same button. It never claims to have "
          "changed the setting, because it cannot."),
    ("p", "More often the phone does not stop JemRec but **freezes** it. Tried "
          "twice on that Honor, it froze JemRec both times: after an unplug with "
          "the screen off, the recorder stayed down 8.5 minutes, until the screen "
          "was touched. JemRec times its own restart after a cable: the first "
          "moment it is allowed to run and finds minutes gone instead of "
          "seconds, it puts up the same notification, and says how long it was "
          "held back once the recorder is back."),

    ("h2", "What the app tells you"),
    ("p", "The header is the honest indicator:"),
    # (the header's state, the line under it, meaning): the words are the
    # app's, read from its header files (header_words).
    ("status", [
        ("READY", None, "A call would be recorded right now."),
        ("notification", "Recording call", "In the notification, during a call."),
        ("CANNOT", "JOIN_WIFI", "Join any Wi-Fi and it resumes on its own. Usually a "
         "reboot, or a long stretch away from a network."),
        ("CANNOT", "START_AGAIN", "On Wi-Fi, the recorder did not come back. Tap the "
         "line to try now."),
        ("CANNOT", "ALLOW_WIFI", "You are on a Wi-Fi network Android has not allowed "
         "Wireless debugging on. Tap the line, tick **Always allow on this "
         "network** and tap **Allow**; the recorder starts a few seconds "
         "later."),
        ("CANNOT", "PAIR_AGAIN", "Wireless debugging turns JemRec away, as it does "
         "once its pairing is deleted. JemRec goes to the pairing step by itself "
         "as soon as the home screen shows; the line is there for the moment "
         "before."),
        ("STARTING", None, "Rebuilding the recorder. A few seconds."),
        ("OFF", "SWITCH_ON", "You turned the switch off."),
        ("OFF", "SWITCH_ON_OVER_WIFI", "You turned the switch off with When JemRec "
         "is off set to Turn all debugging off. Turning it on needs Wi-Fi - "
         "unless Start without Wi-Fi has opened its port, and then the line is "
         "the plain one above."),
    ]),
    ("p", "And when the app is closed, in notifications:"),
    ("table", (["It says", "Meaning"], [
        ["The caller's name, **Recorded**", "After every recorded call, silently, "
         "with Play, Share and Delete. Can be switched off in Settings."],
        ["**Last call recorded, but silent**", "The file has the right length and "
         "no sound in it. Usually Wi-Fi calling, or a phone that does not hand "
         "calls over at all. Run the self-test, or copy a report for GitHub "
         "(Settings → Diagnostics)."],
        ["**JemRec is not recording calls**", "The recorder has been down for two "
         "minutes. The notification says why: it needs a few seconds of Wi-Fi, "
         "or this Wi-Fi is not allowed for Wireless debugging (with **Allow "
         "this Wi-Fi**), or it cannot connect (with **Pair again**), or it could "
         "not start. It goes away by itself when the recorder is back."],
        ["**A call was not recorded**", "An answered call in your call log has no "
         "recording. Only in Record every call mode, and only with call log "
         "access. Run the self-test."],
        ["**Call not recorded**", "A call was recorded but could not be saved, or no "
         "call audio came through - or the answering machine answered and could "
         "not play your greeting, so it hung up. The notification says which; "
         "for the greeting, run the answering machine's test call."],
        ["**Checking the recorder**", "A USB cable went in or came out, and "
         "the recorder went with it: JemRec is starting it again. A few "
         "seconds; a cable that changed nothing shows nothing. Minutes on a "
         "phone that holds JemRec back with the screen off - see *Unplugging "
         "from a computer*."],
        ["**Taking a message**", "The answering machine has a caller. **Talk to "
         "them** takes the call; **Hang up** ends it."],
        ["**Message from** the caller", "A message, with a sound, Play, Share and "
         "Delete. It stays until you dismiss it, whether or not each call is "
         "shown when it ends."],
        ["The caller, with pause and a seek bar", "A recording you played, going "
         "on with the screen off. On the lock screen it says only **Call "
         "recording**. It goes when the recording is stopped."],
        ["**JemRec was stopped in the background**", "This phone stopped JemRec "
         "in the background, so a call could go unrecorded. Its button opens the "
         "phone's own screen for it, and the notification says what to do there "
         "(on an Honor: **App launch**, **Manage manually**). Only on phones "
         "that stop apps that way — see *Stopped in the background*."],
        ["**JemRec was held back in the background**", "The phone froze JemRec "
         "while it was restarting the recorder after a cable, for the minutes it "
         "says. The same button and the same fix."],
    ])),

    ("h2", "Settings"),
    ("p", "One screen, in five groups, each row showing what it is set to now, "
          "and the settings the header marks wearing the same mark. "
          "Until you allow it, a **Show who called** card sits at the top: it "
          "labels each recording with the contact's name and photo, or the number "
          "when they are not in your contacts, and lets JemRec tell you if a call "
          "went by unrecorded. It needs your call log and contacts, and is asked "
          "for in the app rather than at install. Say no and everything still "
          "works, with the time as each recording's headline."),
    ("p", "**Recording**"),
    ("ul", [
        "**Record every call** — record everything, or be asked each time.",
        "**Whose calls to keep** — your contacts (all, all but some, only some, "
        "or none) and numbers not in your contacts. Needs Show who called.",
        "**Answering machine** — off until you turn it on; see The answering "
        "machine.",
        "**Messenger calls** — a switch for each supported messenger installed, "
        "Google Meet included, and whether to show who called from their call "
        "notifications.",
        "**Exact call tracking** — recordings start at the answer, and calls "
        "nobody answered are not kept. The answering machine needs it: with the "
        "machine on, turning it off asks whether to turn both off.",
    ]),
    ("p", "**Look**"),
    ("ul", [
        "**Theme** — Memo, Deck or Multitrack, and light, dark or as the phone; "
        "see Three looks.",
    ]),
    ("p", "**Saving**"),
    ("ul", [
        "**Saving to** — which folder recordings go in, and back to the default.",
        "**File names** — date and time, with the caller, with the phone number, "
        "or the name Honor's Phone app plays from its call history.",
        "**Auto-delete old recordings** and **Auto-delete short calls** — off "
        "until you choose. Recordings older than 30 days, 90 days, 6 months or a "
        "year; calls shorter than 5, 10 or 30 seconds. Kept recordings are never "
        "touched, and whatever is removed waits in Recently deleted.",
        "**Recently deleted** — what has been deleted in the last 30 days, with "
        "**Restore** for each, **Restore all**, and **Delete all for good**, which "
        "asks first: it cannot be undone.",
    ]),
    ("p", "**Notifications and shortcuts**"),
    ("ul", [
        "**Show each call when it ends** — the silent notification after every "
        "recorded call. Off hides it; a recording that came out silent is "
        "reported either way.",
        "**Quick Settings tile** — adds the on/off tile to the Quick Settings "
        "panel.",
    ]),
    ("p", "**Debugging**"),
    ("ul", [
        "**When JemRec is off** — what else goes off when you turn JemRec off: "
        "nothing, Developer options and Wireless debugging, or all debugging; "
        "see Off, and debugging with it.",
        "**Start without Wi-Fi** — opens debugging port 5555, so that the "
        "recorder can start with no Wi-Fi until the phone restarts; it says "
        "whether the port is open, and that it is open to every network the "
        "phone joins. See Start without Wi-Fi.",
        "**Hide the debugging notification** — the answer to the question in "
        "step 1, changeable. Debugging stays on either way while JemRec is on; "
        "this is only about whether Android's notification saying so is shown. "
        "Turning it on after setup needs Wi-Fi.",
    ]),
    ("p", "**Troubleshooting**"),
    ("ul", [
        "**Survive a restart** — on phones that have one, opens the list of apps "
        "allowed to start by themselves after a reboot.",
        "**Diagnostics** — whether the recorder is running, a self-test that "
        "proves a call would be recorded, a way to start the recorder by "
        "hand, and **Copy a report**: this phone and how its last call was "
        "recorded — which way, how loud each side came out, what was playing "
        "— with no numbers or names in it. It is what a bug report wants.",
        "**Start fresh** — puts JemRec back to how it was the day you installed "
        "it, so setup runs again from step 1. The app closes when you confirm. "
        "**Your recordings are not deleted.**",
    ]),

    ("h2", "If something goes wrong"),
    ("p", "**Pairing fails every time.** Almost always the dialog closed. It "
          "survives only while Settings is on screen, which is why step 4 uses "
          "the notification shade rather than switching back to the app. Reopen "
          "the dialog for a fresh code each attempt."),
    ("p", "**A debugging switch keeps turning itself off.** Android turns "
          "Wireless debugging off whenever Wi-Fi drops; JemRec needs it only to "
          "start its recorder, and turns it back on then. Some phones also switch "
          "USB debugging off each time the Developer options screen opens without "
          "a cable — an Honor does, every time — and that stops the recorder. "
          "JemRec turns the switch back on and starts the recorder again by "
          "itself, on Wi-Fi: at once if you open JemRec, and within about half a "
          "minute once the phone is locked. Away from Wi-Fi it has to wait for "
          "Wi-Fi, so leave Developer options alone there."),
    ("p", "**It says it cannot record.** Off Wi-Fi, join any Wi-Fi and leave it "
          "a few seconds; the app rebuilds its recorder without being asked. On "
          "Wi-Fi, the header's line says what to do: tap it to start the "
          "recorder again, or to allow this Wi-Fi."),
    ("p", "**Android asks to allow wireless debugging on this network.** That "
          "is JemRec restarting the recorder after a reboot, on a Wi-Fi network "
          "you have not used it on before. Tick **Always allow on this network** "
          "and tap **Allow**: Android lets Wireless debugging work only on "
          "networks you allowed, and JemRec needs it for a few seconds. If you "
          "tapped Cancel, the notification and the app's header offer to ask "
          "again."),
    ("p", "**Recordings have only your voice.** Some phones' audio hardware does "
          "not hand the other side to apps at all, and the self-test says so "
          "plainly. If it passes and a call still came out with one side — over "
          "Wi-Fi Calling, say — tap **Copy a report** in Diagnostics right after "
          "that call and paste it into an issue on GitHub. It says which way the "
          "call was recorded and what the phone was playing, which is what it "
          "takes to fix it for a phone the developer has never held."),
    ("p", "**JemRec is back on its pairing step, or a notification says it "
          "cannot connect.** The phone has most likely forgotten its pairing — "
          "Wireless debugging → Paired devices no longer lists JemRec, as after "
          "deleting it there. Once Wireless debugging has turned JemRec away twice "
          "in a row, the home screen gives way to the pairing step, and the "
          "notification's **Pair again** opens it too: a fresh code puts "
          "everything back, recordings and settings included. If the pairing was "
          "fine after all, JemRec reconnects without asking for a code."),
    ("p", "**A notification says a call was not recorded.** Tap **Self-test**. It "
          "says whether the recorder is running and hearing calls; a recorder "
          "that was down at the time is the usual answer."),

    ("h2", "Uninstalling"),
    ("p", "Uninstall it however you like — long-press the icon, or Settings → "
          "Apps. There is nothing special to do."),
    ("p", "The recorder is a separate system process rather than part of the app, "
          "so removing the package does not stop it immediately. It checks every "
          "few minutes whether JemRec is still installed, and when it is not it "
          "turns both debugging switches off, deletes its own files and exits. A "
          "reinstall is never fooled by one that has not got there yet, either: "
          "the app only trusts a recorder it started itself, and retires any "
          "other on sight."),
    ("note", "**What no app can undo.** The phone goes on trusting the key it "
             "paired with, in a list only root can edit, so it stays under "
             "*Wireless debugging → Paired devices*; remove it there with the "
             "gear beside it. Developer options stays on if you turned it on. And "
             "Start fresh leaves the one privileged setting granted, because "
             "handing it back would kill the app halfway through the reset. If "
             "you tapped **Wait 30 seconds** under *Carrier voicemail*, your "
             "carrier still waits 30 seconds before its voicemail answers: that "
             "timer is your carrier's, not the phone's, and JemRec did not keep "
             "what it was before. Ask your carrier to set it back if you want "
             "the shorter wait."),

    ("h2", "Two honest warnings"),
    ("p", "**The other person is being recorded too.** Whether you may do that "
          "without telling them differs by country and sometimes by region. That "
          "is your call to make, not the app's."),
    ("p", "**This uses debugging switches, and they stay on.** They are developer "
          "features that let software on your phone act with elevated privileges. "
          "JemRec needs them to record calls at all, so it keeps them on, and turns "
          "them back on if they go off. While they are on, a computer that has "
          "been paired with this phone can connect to it on the same Wi-Fi, and one "
          "you have allowed before can connect over a cable — so check "
          "*Wireless debugging → Paired devices* now and then and remove any you do "
          "not recognise. If you stop using JemRec, uninstall it first, then turn "
          "Wireless debugging, USB debugging and Developer options back off."),
]


def _inline_html(text: str) -> str:
    """The small Markdown subset the manual uses, as HTML."""
    out = (text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"))
    out = re.sub(r"`([^`]+)`", r"<code>\1</code>", out)
    out = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", out)
    out = re.sub(r"(?<![*\w])\*([^*]+)\*(?!\w)", r"<em>\1</em>", out)
    return out


def _anchor(heading: str) -> str:
    return re.sub(r"[^a-z0-9]+", "-", heading.lower()).strip("-")


_STEP = re.compile(r"Step (\d+) — (.*)")


@functools.cache
def header_words() -> dict:
    """Each look's home header, in the app's own words: the state
    (RecorderStatus) and the line under it (Instruction), read from the three
    header files. The manual's table of what the header says was written by
    hand and had drifted - it gave "Cannot record, needs Wi-Fi" where the
    phone says "Join any Wi-Fi to resume" - and Deck and Multitrack say it
    differently again."""
    ui = ROOT / "app/src/main/java/com/jemcik/jemrec/ui"
    want = {"READY", "STARTING", "OFF", "CANNOT", "SWITCH_ON", "SWITCH_ON_OVER_WIFI",
            "ALLOW_WIFI", "JOIN_WIFI", "PAIR_AGAIN", "START_AGAIN"}
    out = {}
    # The lines that are a tap: the Instruction entries that carry one
    # (HeaderFacts.kt), which Memo draws in its primary colour.
    facts = (ui / "HeaderFacts.kt").read_text()
    enum = facts[facts.index("enum class Instruction"):]
    out["taps"] = set(re.findall(r'^\s*(\w+)\("[^"]+"\)', enum[:enum.index("}")], re.M))
    assert out["taps"] <= want, f"HeaderFacts.kt: unknown taps {out['taps'] - want}"
    for look, file in (("memo", "MemoHeader.kt"), ("deck", "DeckHeader.kt"),
                       ("multitrack", "MultitrackHeader.kt")):
        words: dict = {}
        for key, word in re.findall(r'(?:RecorderStatus|Instruction)\.(\w+) -> "([^"]+)"',
                                    (ui / file).read_text()):
            words.setdefault(key, html.escape(word, quote=False))
        assert want <= set(words), f"{file}: no words for {sorted(want - set(words))}"
        out[look] = words
    return out


def status_html(rows) -> str:
    """What the header says, drawn as each look's header draws it; the page's
    look shows one. A notification is Android's, the same in every look."""
    words = header_words()
    tone = {"READY": "ready", "STARTING": "idle", "OFF": "idle", "CANNOT": "alarm"}
    out = []
    for state, line, meaning in rows:
        if state == "notification":
            pic = (f'<span class="hd hd-notif"><img src="icon.png" alt="" width="18" height="18">'
                   f"<b>{line}</b></span>")
        else:
            pics = []
            for look, _ in LOOKS:
                w = words[look]
                off = " off" if state == "OFF" else ""
                tap = ' class="tap"' if look == "memo" and line in words["taps"] else ""
                said = f'<b class="{tone[state]}">{w[state]}</b>' + (f"<small{tap}>{w[line]}</small>" if line else "")
                busy = " busy" if state == "STARTING" else ""
                mark = {"memo": f'<span class="hd-disc{off}{busy}"><i></i></span>',
                        "deck": f'<span class="hd-key{off}"></span>',
                        "multitrack": f'<span class="hd-ring{off}"><i></i></span>'}[look]
                inner = f'<span class="hd-lcd{off}">{said}</span>' if look == "deck" else f"<span>{said}</span>"
                pics.append(f'<span class="hd hd-{look} look-{look}">{mark}{inner}</span>')
            pic = "".join(pics)
        out.append(f'    <div class="status"><div>{pic}</div><p>{_inline_html(meaning)}</p></div>')
    return ('  <div class="status-list">\n' + "\n".join(out) + "\n  </div>\n"
            '  <p class="muted">Drawn in the look this page is in; the Look button at '
            "the top shows the others, each worded its own way.</p>")


# The manual's contents come in four parts; each starts at one of these.
TOC_PARTS = {
    "Installing": "Setting up",
    "Where recordings go": "Using it",
    "After a restart": "Keeping it running",
    "What the app tells you": "When you need it",
}


def toc_html(blocks) -> str:
    """The manual's contents, every section, a step by its number."""
    parts: list = []
    for kind, payload in blocks:
        if kind != "h2":
            continue
        if payload in TOC_PARTS:
            parts.append((TOC_PARTS[payload], []))
        assert parts, f"{payload} comes before the first part in TOC_PARTS"
        step = _STEP.match(payload)
        label = (f'<span class="n">{step.group(1)}</span> {_inline_html(step.group(2))}'
                 if step else _inline_html(payload))
        parts[-1][1].append(f'<li><a href="#{_anchor(payload)}">{label}</a></li>')
    body = "".join(f"\n      <p>{name}</p><ol>{''.join(items)}</ol>" for name, items in parts)
    return (f'<nav class="toc" aria-label="Contents">\n    <details>'
            f"<summary>Contents</summary>{body}\n    </details>\n  </nav>")


def render_html(blocks) -> str:
    """The manual as the page body."""
    out = []
    steps = sum(1 for kind, payload in blocks if kind == "h2" and _STEP.match(payload))
    for kind, payload in blocks:
        if kind in ("h2", "h3"):
            # Where a step is in the setup, before its heading: what is behind
            # you and what is left.
            step = _STEP.match(payload) if kind == "h2" else None
            if step:
                n = int(step.group(1))
                bars = "".join('<i class="d"></i>' if i < n else '<i class="c"></i>' if i == n else "<i></i>"
                               for i in range(1, steps + 1))
                out.append(f'  <div class="step-rail" aria-hidden="true">{bars}<span>{n} of {steps}</span></div>')
            # An id per heading, so the landing page can link straight to a
            # section rather than to the top of a long manual.
            out.append(f'  <{kind} id="{_anchor(payload)}">{_inline_html(payload)}</{kind}>')
        elif kind == "status":
            out.append(status_html(payload))
        elif kind == "p":
            out.append(f"  <p>{_inline_html(payload)}</p>")
        elif kind in ("ol", "ul"):
            items = "".join(f"\n    <li>{_inline_html(i)}</li>" for i in payload)
            out.append(f"  <{kind}>{items}\n  </{kind}>")
        elif kind == "note":
            out.append(f'  <div class="note info"><p>{_inline_html(payload)}</p></div>')
        elif kind == "video":
            out.append("  " + video_player(VIDEO_CAPTION))
        elif kind == "table":
            headers, rows = payload
            head = "".join(f"<th>{_inline_html(h)}</th>" for h in headers)
            body = "".join(
                "\n    <tr>" + "".join(f"<td>{_inline_html(c)}</td>" for c in r) + "</tr>"
                for r in rows
            )
            out.append(f"  <table>\n    <tr>{head}</tr>{body}\n  </table>")
        else:
            raise ValueError(f"unknown block: {kind}")
    return "\n".join(out)


def render_markdown(blocks) -> str:
    """The same manual as the file people read in the repository.

    Wrapped at 79 columns, because a Markdown file is read in an editor as often
    as in a browser and a paragraph on one endless line is unreadable in one of
    them.
    """
    out = []
    for kind, payload in blocks:
        if kind == "h2":
            out.append(f"## {payload}\n")
        elif kind == "h3":
            out.append(f"### {payload}\n")
        elif kind == "p":
            out.append(textwrap.fill(payload, 79) + "\n")
        elif kind == "ol":
            for n, item in enumerate(payload, 1):
                out.append(textwrap.fill(
                    item, 79, initial_indent=f"{n}. ", subsequent_indent="   "))
            out.append("")
        elif kind == "ul":
            for item in payload:
                out.append(textwrap.fill(
                    item, 79, initial_indent="- ", subsequent_indent="  "))
            out.append("")
        elif kind == "note":
            out.append(textwrap.fill(
                payload, 79, initial_indent="> ", subsequent_indent="> ") + "\n")
        elif kind == "video":
            # GitHub plays no video inside a Markdown file, so the still links out.
            out.append(f"[![The setup video, {VIDEO_LENGTH}](video/setup.jpg)]({VIDEO_URL})\n")
            out.append(textwrap.fill(
                f"**Prefer to watch?** The whole setup, start to finish, in "
                f"{VIDEO_LENGTH} [on YouTube]({VIDEO_URL}), filmed on an Honor Magic 8 Pro.",
                79) + "\n")
        elif kind == "status":
            # A table here, in Memo's words, the app's default.
            words = header_words()["memo"]
            out.append("| It says | Meaning |\n|---|---|")
            for state, line, meaning in payload:
                if state == "notification":
                    said = f"**{line}**"
                else:
                    said = f"**{words[state]}**" + (f" · {words[line]}" if line else "")
                out.append(f"| {said} | {meaning} |")
            out.append("\nIn Memo's words; Deck and Multitrack word the header their own way.\n")
        elif kind == "table":
            headers, rows = payload
            out.append("| " + " | ".join(headers) + " |")
            out.append("|" + "|".join(["---"] * len(headers)) + "|")
            for row in rows:
                out.append("| " + " | ".join(c.replace("\n", " ") for c in row) + " |")
            out.append("")
    return "\n".join(out)


def shot(thumb: str, full: str, alt: str, theme: str, look: str, sizes: str) -> str:
    """A screenshot that opens in the viewer.

    A button rather than an image with a click handler on it: it is a control,
    and the keyboard should be able to reach it and press it.
    """
    return (
        f'<button class="shot look-{look} {theme}-only" type="button" data-full="{full}" aria-label="{alt}">'
        f'<img src="{thumb}" srcset="{thumb} 420w, {full} 1256w" sizes="{sizes}" alt="{alt}" '
        f'loading="lazy" width="420" height="939"></button>'
    )


def video_player(caption: str, video: str = VIDEO, length: str = VIDEO_LENGTH,
                 still: str = "video/setup.jpg", name: str = "setup video") -> str:
    """A video as a still that links to YouTube; the script swaps in the
    player on a click. The still is this site's own file, so nothing from
    YouTube loads with the page."""
    return f"""<figure class="video">
    <div class="video-frame">
      <a href="https://www.youtube.com/watch?v={video}" data-youtube="{video}" data-title="JemRec {name}" aria-label="Play the {name} ({length}), from YouTube">
        <img src="{still}" alt="" width="1280" height="720" loading="lazy">
        <span class="play" aria-hidden="true"></span>
      </a>
    </div>
    <figcaption>{caption}</figcaption>
  </figure>"""


VIDEO_CAPTION = (
    f"<strong>Prefer to watch?</strong> The whole setup, start to finish, in "
    f"{VIDEO_LENGTH}, filmed on an Honor Magic 8 Pro. It plays from YouTube, "
    f"and nothing from YouTube loads until you press play."
)
FEATURES_VIDEO_CAPTION = (
    f"<strong>Prefer to watch?</strong> What it does, in {FEATURES_VIDEO_LENGTH}, "
    f"on the app&rsquo;s own screens. It plays from YouTube, and nothing from "
    f"YouTube loads until you press play."
)


def page(title: str, description: str, body: str, here: str, main_class: str = "") -> str:
    # A link to the page you are already on is furniture. The wordmark goes home
    # from anywhere, so Home only appears when you are somewhere else.
    def nav(href: str, label: str, roomy: bool = False) -> str:
        if href == here:
            return ""
        cls = ' class="roomy"' if roomy else ""
        return f'<a href="{href}"{cls}>{label}</a>'

    return f"""<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title>
<meta name="description" content="{description}">
<meta name="color-scheme" content="light dark">
<link rel="icon" href="icon.png">
{HEAD_SCRIPT}
<link rel="stylesheet" href="jemrec.css">
<script src="jemrec.js" defer></script>
</head>
<body>
<header class="bar">
  <div class="column">
    <a class="brand" href="index.html"><img src="icon.png" alt="" width="34" height="34">JemRec</a>
    <div class="bar-end">
      <nav>{nav("index.html", "Home", roomy=True)}{nav("how-it-works.html", "How it works", roomy=True)}{nav("setup.html", "Setup")}{nav("privacy-policy.html", "Privacy", roomy=True)}<a class="roomy" href="{REPO}">GitHub</a></nav>
      <a class="support-btn" href="{DONATE}" aria-label="Support JemRec on Donatello"><span aria-hidden="true">&#9829;</span><span class="support-name">Support</span></a>
      {LOOK_BUTTON}{THEME_BUTTON}
    </div>
  </div>
</header>
<main{f' class="{main_class}"' if main_class else ""}>
{body}
</main>
<footer>
  <div class="column">
    <nav>
      <a href="how-it-works.html">How it works</a>
      <a href="setup.html">Setup</a>
      <a href="privacy-policy.html">Privacy policy</a>
      <a href="{REPO}/releases">Releases</a>
      <a href="{REPO}">Source on GitHub</a>
      <a href="{DONATE}">Support JemRec</a>
    </nav>
    <p>JemRec is free software under the Apache&nbsp;2.0 licence.</p>
    <p class="credit">The F-Droid logo is by William Theaker, Robert Martinez and
       Andrew Nayenko, under
       <a href="https://creativecommons.org/licenses/by-sa/3.0/">CC&nbsp;BY-SA&nbsp;3.0</a>.</p>
  </div>
</footer>
{VIEWER}
</body>
</html>
"""


# ---------------------------------------------------------------------------
# THE HOME PAGE.
#
# The README's story, told as a page: what it is, why it, how to start, what
# it looks like, what it does, how it works, whether it works on your phone,
# privacy, questions. The words are the README's wherever the README already
# says the thing - two wordings of one fact is how one of them goes stale. The
# deep design, which used to be most of this page, has a page of its own.
# ---------------------------------------------------------------------------

def icon(path: str) -> str:
    """A 24px Material icon, filled in the text colour of wherever it sits."""
    return f'<svg viewBox="0 0 24 24" fill="currentColor" aria-hidden="true"><path d="{path}"/></svg>'


# Material Icons, filled - the set the app's own icons come from (Compose's
# Icons.Filled) - from github.com/google/material-design-icons, src/<group>/
# <name>/materialicons/24px.svg, read 2026-10-27, Apache-2.0 (NOTICE.md).
# Only each icon's shape: the files' empty 24x24 frame path is left out.
ICONS = {
    "both": (  # phone_in_talk
        "M20 15.5c-1.25 0-2.45-.2-3.57-.57-.35-.11-.74-.03-1.02.24l-2.2 2.2c-2.83-1.44-5.15-3.75-6.59-6.59l2.2-2.21c.28-.26.36-.65.25-1C8.7 6.45 8.5 5.25 8.5 4c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1 0 9.39 7.61 17 17 17 .55 0 1-.45 1-1v-3.5c0-.55-.45-1-1-1zM19 12h2c0-4.97-4.03-9-9-9v2c3.87 0 7 3.13 7 7zm-4 0h2c0-2.76-2.24-5-5-5v2c1.66 0 3 1.34 3 3z"),
    "self": (  # smartphone
        "M17 1.01L7 1c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-1.99-2-1.99zM17 19H7V5h10v14z"),
    "lock": (  # lock
        "M18 8h-1V6c0-2.76-2.24-5-5-5S7 3.24 7 6v2H6c-1.1 0-2 .9-2 2v10c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V10c0-1.1-.9-2-2-2zm-6 9c-1.1 0-2-.9-2-2s.9-2 2-2 2 .9 2 2-.9 2-2 2zm3.1-9H8.9V6c0-1.71 1.39-3.1 3.1-3.1 1.71 0 3.1 1.39 3.1 3.1v2z"),
    "bell": (  # notifications
        "M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z"),
    "rules": (  # tune
        "M3 17v2h6v-2H3zM3 5v2h10V5H3zm10 16v-2h8v-2h-8v-2h-2v6h2zM7 9v2H3v2h4v2h2V9H7zm14 4v-2H11v2h10zm-6-4h2V7h4V5h-4V3h-2v6z"),
    "open": (  # code
        "M9.4 16.6L4.8 12l4.6-4.6L8 6l-6 6 6 6 1.4-1.4zm5.2 0l4.6-4.6-4.6-4.6L16 6l6 6-6 6-1.4-1.4z"),
    "mic": (  # mic_off
        "M19 11h-1.7c0 .74-.16 1.43-.43 2.05l1.23 1.23c.56-.98.9-2.09.9-3.28zm-4.02.17c0-.06.02-.11.02-.17V5c0-1.66-1.34-3-3-3S9 3.34 9 5v.18l5.98 5.99zM4.27 3L3 4.27l6.01 6.01V11c0 1.66 1.33 3 2.99 3 .22 0 .44-.03.65-.08l1.66 1.66c-.71.33-1.5.52-2.31.52-2.76 0-5.3-2.1-5.3-5.1H5c0 3.41 2.72 6.23 6 6.72V21h2v-3.28c.91-.13 1.77-.45 2.54-.9L19.73 21 21 19.73 4.27 3z"),
    "cloud": (  # cloud_off
        "M19.35 10.04C18.67 6.59 15.64 4 12 4c-1.48 0-2.85.43-4.01 1.17l1.46 1.46C10.21 6.23 11.08 6 12 6c3.04 0 5.5 2.46 5.5 5.5v.5H19c1.66 0 3 1.34 3 3 0 1.13-.64 2.11-1.56 2.62l1.45 1.45C23.16 18.16 24 16.68 24 15c0-2.64-2.05-4.78-4.65-4.96zM3 5.27l2.75 2.74C2.56 8.15 0 10.77 0 14c0 3.31 2.69 6 6 6h11.73l2 2L21 20.73 4.27 4 3 5.27zM7.73 10l8 8H6c-2.21 0-4-1.79-4-4s1.79-4 4-4h1.73z"),
    "person": (  # person, as on Settings' Whose calls to keep
        "M12 12c2.21 0 4-1.79 4-4s-1.79-4-4-4-4 1.79-4 4 1.79 4 4 4zm0 2c-2.67 0-8 1.34-8 4v2h16v-2c0-2.66-5.33-4-8-4z"),
    "screenlock": (  # screen_lock_portrait
        "M10 16h4c.55 0 1-.45 1-1v-3c0-.55-.45-1-1-1v-1c0-1.11-.9-2-2-2-1.11 0-2 .9-2 2v1c-.55 0-1 .45-1 1v3c0 .55.45 1 1 1zm.8-6c0-.66.54-1.2 1.2-1.2.66 0 1.2.54 1.2 1.2v1h-2.4v-1zM17 1H7c-1.1 0-2 .9-2 2v18c0 1.1.9 2 2 2h10c1.1 0 2-.9 2-2V3c0-1.1-.9-2-2-2zm0 18H7V5h10v14z"),
    "adb": (  # adb: the Android Debug Bridge, which is what stays on
        "M5 16c0 3.87 3.13 7 7 7s7-3.13 7-7v-4H5v4zM16.12 4.37l2.1-2.1-.82-.83-2.3 2.31C14.16 3.28 13.12 3 12 3s-2.16.28-3.09.75L6.6 1.44l-.82.83 2.1 2.1C6.14 5.64 5 7.68 5 10v1h14v-1c0-2.32-1.14-4.36-2.88-5.63zM9 9c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1zm6 0c-.55 0-1-.45-1-1s.45-1 1-1 1 .45 1 1-.45 1-1 1z"),
}

# The two ways to get it, as two buttons of one shape. F-Droid's is in
# F-Droid's blue with F-Droid's logo - docs/fdroid-logo.svg, their artwork,
# unmodified (see NOTICE.md) - because it is the way to install. The APK's is
# the quieter of the two, with GitHub's mark, because that is where it goes.
GITHUB_MARK = (
    '<svg viewBox="0 0 16 16" aria-hidden="true"><path fill="currentColor" d="M8 0C3.58 0 0 '
    '3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53'
    '-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 '
    '1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15'
    '-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53'
    '-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 '
    '3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0'
    '-4.42-3.58-8-8-8z"/></svg>'
)

STORES = f"""<div class="cta">
      <a class="store fdroid" href="{FDROID}">
        <img src="fdroid-logo.svg" alt="" width="32" height="32">
        <span><small>Get it on</small><strong>F-Droid</strong></span>
      </a>
      <a class="store github" href="{REPO}/releases">
        {GITHUB_MARK}
        <span><small>Get it on</small><strong>GitHub</strong></span>
      </a>
    </div>"""


def reason(name: str, title: str, text: str) -> str:
    return f'<div class="reason"><h3>{icon(ICONS[name])}<span>{title}</span></h3><p>{text}</p></div>'


def themed(name: str, alt: str, width: int) -> str:
    """One screen in every look and both themes, for a phone frame `width`
    CSS pixels wide; the page's look and theme show one.

    The 420px copy on a plain screen, the full capture on a sharp one.
    """
    return "".join(
        f'<img class="look-{look} {theme}-only" src="{thumb}" srcset="{thumb} 420w, {full} 1256w" '
        f'sizes="{width}px" alt="{alt}" width="420" height="939" loading="lazy">'
        for look, theme, thumb, full in _variants(name)
    )


def _variants(name: str):
    """(look, theme, thumb, full) for one screen, in every look and theme."""
    for look, _ in LOOKS:
        yield look, "light", f"screenshots/{look}/{name}.png", f"screenshots/{look}/light/{name}.png"
        yield look, "dark", f"screenshots/{look}/{name}-dark.png", f"screenshots/{look}/dark/{name}.png"


# A PART OF A SCREEN, for the rows of What you can do: the rows of a
# screenshot to show, top and bottom, in the 420x939 copies, per look -
# Deck's LCD header and Multitrack's lanes put the same things lower or
# higher. Read off the screenshots of 2026-10-27; light and dark are laid out
# alike. A new set of screenshots (tools/shoot.py) needs a look at these.
CROPS = {
    "home": {"memo": (392, 568), "deck": (425, 642), "multitrack": (388, 555)},
    "calls": {"memo": (418, 765), "deck": (408, 742), "multitrack": (386, 706)},
    "answering": {"memo": (145, 578), "deck": (150, 562), "multitrack": (148, 556)},
    "whenoff": {"memo": (148, 550), "deck": (148, 494), "multitrack": (145, 468)},
}


def crop(name: str, alt: str) -> str:
    """The CROPS part of a screen, in the page's look and theme."""
    out = []
    for look, theme, thumb, full in _variants(name):
        top, bottom = CROPS[name][look]
        out.append(
            f'<div class="crop look-{look} {theme}-only" style="aspect-ratio: 420 / {bottom - top}">'
            f'<img src="{thumb}" srcset="{thumb} 420w, {full} 1256w" sizes="(max-width: 54rem) 92vw, 24rem" '
            f'style="margin-top: -{top / 420 * 100:.2f}%" alt="{alt}" width="420" height="939" loading="lazy"></div>'
        )
    return "".join(out)


def trio() -> str:
    """The home screen in each look, in the page's light or dark: each a
    button that puts the site in that look, as the header's Look button does."""
    def one(look: str, named: str) -> str:
        pics = "".join(
            f'<img class="{theme}-only" src="{thumb}" alt="" width="420" height="939" loading="lazy">'
            for lk, theme, thumb, _ in _variants("home") if lk == look
        )
        return (f'<button type="button" data-set-look="{look}" aria-pressed="false">'
                f'<span class="mini">{pics}</span>{named}</button>')
    return ('<div class="trio-box"><div class="trio" role="group" aria-label="The site&rsquo;s look">'
            + "".join(one(look, named) for look, named in LOOKS)
            + '</div><p class="trio-note">Tap one to see this site in that look.</p></div>')


# Three of the app's notifications in its own words - the one after each call
# and a silent recording (capture/AfterCall.kt), and a recorder down on a
# Wi-Fi Android has not allowed (capture/RecorderHealth.kt) - with two of the
# debug build's invented people. (when, title, text, buttons)
NOTIFICATIONS = [
    ("now", "Olivia Bennett", "Recorded, 4:12", ["Play", "Share", "Delete"]),
    ("2 min", "Last call recorded, but silent",
     "Marcus Chen, 12:05. No sound came through. This happens on calls over "
     "Wi-Fi calling, and on some phones always. Run the self-test to check "
     "this phone.", ["Play", "Self-test", "Delete"]),
    ("5 min", "JemRec is not recording calls",
     "Android has not allowed Wireless debugging on this Wi-Fi, and JemRec needs "
     "it for a few seconds to restart the recorder. Tap Allow this Wi-Fi, tick "
     "Always allow on this network, and tap Allow.", ["Allow this Wi-Fi"]),
]


def shade() -> str:
    def one(when: str, title: str, text: str, buttons: list) -> str:
        acts = "".join(f"<span>{b}</span>" for b in buttons)
        return (f'<div class="notif"><span class="app"><img src="icon.png" alt="" width="16" height="16">'
                f'JemRec &middot; {when}</span><strong>{title}</strong><p>{text}</p>'
                f'<span class="acts">{acts}</span></div>')
    return ('<div class="shade" role="img" aria-label="Three JemRec notifications: a recorded call, '
            'a silent recording, and the recorder down">' + "".join(one(*n) for n in NOTIFICATIONS) + "</div>")


# THE MEASUREMENTS THE CHARTS DRAW, as docs/ARCHITECTURE.md gives each, with
# its phone and date; every bar is placed by these numbers and no other.
# Segments: (from, to, kind, words), in the axis's unit.
MEASURES = {
    # Exact call tracking: an outgoing call that rang for 45 seconds was saved
    # as its 14 seconds of conversation, to a twentieth of a second. Measured
    # for 0.14, merged 2026-10-21 (a4536c6), where the docs first say it.
    "tracking": dict(
        title="Exact call tracking keeps only the conversation",
        axis=(60, (0, 15, 30, 45, 60), " s"),
        rows=[
            ("The call", [(0, 45, "ring", "ringing, 45 s"), (45, 59, "talk", "talk")]),
            ("The recording", [(0, 45, "none", "not saved"), (45, 59, "talk", "14 s")]),
        ],
        caption="Cut to Android&rsquo;s own answer and end, to a twentieth of a "
                "second. A call nobody answers is not saved at all.",
        source="Honor Magic 8 Pro, Android 17, October 2026 (0.14) &middot; ARCHITECTURE.md, Exact call tracking",
    ),
    # The daemon records: while the app muxed the live audio, a 76.7-second
    # call arrived as 14 to 44 seconds. Already in the first commit,
    # 2026-09-10 (48e674f); the docs give no closer date.
    "freeze": dict(
        title="Why the recorder writes the file, not the app",
        axis=(80, (0, 20, 40, 60, 80), " s"),
        rows=[
            ("A 76.7-second call, the app carrying its audio",
             [(0, 14, "talk", "arrived"), (14, 44, "range", "sometimes"), (44, 76.7, "lost", "lost")]),
            ("The recorder writing the file, as it does now",
             [(0, 76.7, "ok", "the whole call")]),
        ],
        caption="The phone froze the app while the dialer was in front, and the "
                "end of every call went with it. The recorder runs as the shell, "
                "which the phone does not freeze.",
        source="Honor Magic 8 Pro, before 2026-09-10 &middot; ARCHITECTURE.md, The daemon records; the app collects",
    ),
    # Unplugged from a computer: adbd, and the recorder with it, stopped three
    # seconds after the cable came out; recording again 12 and 16 seconds
    # after it in the two timed runs with the screen on, 13 with it off on
    # Manage manually.
    "unplug": dict(
        title="Unplugged from a computer, back by itself",
        axis=(20, (0, 3, 10, 20), " s"),
        rows=[
            ("Screen on", [(0, 3, "ok", ""), (3, 12, "down", "not recording"), (12, 20, "ok", "12 s")]),
            ("Screen on, again", [(0, 3, "ok", ""), (3, 16, "down", "not recording"), (16, 20, "ok", "16 s")]),
            ("Screen off, on Manage manually",
             [(0, 3, "ok", ""), (3, 13, "down", "not recording"), (13, 20, "ok", "13 s")]),
        ],
        caption="Three seconds after the cable comes out Android stops its ADB "
                "daemon, and the recorder goes with it. JemRec, woken in those "
                "seconds, starts it again over Wi-Fi with nothing tapped.",
        source="Honor Magic 8 Pro, Android 17, October 2026 &middot; ARCHITECTURE.md, Unplugged from a computer",
    ),
}


def timeline(name: str) -> str:
    """One of MEASURES, every segment placed by its numbers on the axis."""
    m = MEASURES[name]
    span, ticks, unit = m["axis"]

    def pct(v: float) -> str:
        return f"{v / span * 100:.2f}%"

    rows = "".join(
        f'<div class="tl-row"><span class="tl-label">{label}</span><div class="tl-track">'
        + "".join(f'<span class="seg {kind}" style="left: {pct(a)}; width: {pct(b - a)}">{words}</span>'
                  for a, b, kind, words in segs)
        + "</div></div>"
        for label, segs in m["rows"]
    )
    axis = "".join(f'<span style="left: {pct(t)}">{t}{unit if t == ticks[-1] else ""}</span>' for t in ticks)
    return (f'<figure class="chart"><p class="chart-title">{m["title"]}</p>{rows}'
            f'<div class="tl-axis" aria-hidden="true">{axis}</div>'
            f'<figcaption>{m["caption"]}<span class="src">{m["source"]}</span></figcaption></figure>')


def diagram() -> str:
    """The four steps on one phone, in the page's colours (see .dg-*)."""
    def node(x: int, y: int, title: str, sub: str, kind: str = "") -> str:
        return (
            f'<rect class="dg-node {kind}" x="{x}" y="{y}" width="196" height="78" rx="16"/>'
            f'<text class="dg-title" x="{x + 98}" y="{y + 34}">{title}</text>'
            f'<text class="dg-sub" x="{x + 98}" y="{y + 58}">{sub}</text>'
        )

    def step(n: int, x: int, y: int) -> str:
        return (
            f'<circle class="dg-num" cx="{x}" cy="{y}" r="14"/>'
            f'<text class="dg-num-text" x="{x}" y="{y}">{n}</text>'
        )

    # Each step is a group - the part that acts, its arrow, its number and
    # its word - so the step being read can be lit (HOW_STEPS, and the script).
    return f"""<svg class="diagram" viewBox="0 0 540 430" role="img" aria-labelledby="dg-title">
    <title id="dg-title">The app pairs with the phone&rsquo;s own Wireless debugging,
      which starts a recorder running as the shell; the recorder writes each
      call to a file, and the app collects it.</title>
    <defs><marker id="dg-arrow" viewBox="0 0 10 10" refX="9" refY="5"
      markerWidth="7" markerHeight="7" orient="auto"><path class="dg-head" d="M0 0L10 5L0 10z"/></marker>
      <marker id="dg-arrow-lit" viewBox="0 0 10 10" refX="9" refY="5"
      markerWidth="7" markerHeight="7" orient="auto"><path class="dg-head lit" d="M0 0L10 5L0 10z"/></marker></defs>
    <rect class="dg-phone" x="6" y="6" width="528" height="418" rx="40"/>
    <text class="dg-sub" x="270" y="46">Your phone &mdash; nothing leaves it</text>
    <text class="dg-sub" x="270" y="216">all over</text>
    <text class="dg-sub" x="270" y="238">127.0.0.1</text>
    <g class="dg-step" data-step="1">{node(28, 78, "JemRec app", "pairs, then collects", "key")}
      <path class="dg-line" d="M224 117H311" marker-end="url(#dg-arrow)"/>{step(1, 266, 117)}
      <text class="dg-label" x="266" y="152" text-anchor="middle">pairs</text></g>
    <g class="dg-step" data-step="2">{node(316, 78, "Wireless debugging", "Android&rsquo;s own")}
      <path class="dg-line" d="M414 156V295" marker-end="url(#dg-arrow)"/>{step(2, 414, 226)}
      <text class="dg-label" x="436" y="231">starts it</text></g>
    <g class="dg-step" data-step="3">{node(316, 300, "Recorder", "runs as the shell")}
      <path class="dg-line" d="M316 339H229" marker-end="url(#dg-arrow)"/>{step(3, 272, 339)}
      <text class="dg-label" x="272" y="374" text-anchor="middle">records</text></g>
    <g class="dg-step" data-step="4">{node(28, 300, "Recording", "an Opus file", "file")}
      <path class="dg-line" d="M126 300V161" marker-end="url(#dg-arrow)"/>{step(4, 126, 230)}
      <text class="dg-label" x="148" y="235">collects</text></g>
  </svg>"""


# The four steps, said once: the home page and How it works both show them
# beside the diagram, the same words and the same lit parts.
HOW_STEPS = [
    "<strong>The app pairs with the phone&rsquo;s own Wireless debugging</strong> "
    "&mdash; the same pairing a laptop does, with both ends on the same handset.",
    "<strong>For a few seconds, it uses that session to start a small "
    "recorder</strong> that runs as the shell, then closes it. The recorder "
    "outlives the session.",
    "<strong>The recorder watches the calls and writes each one to a "
    "file</strong> itself, so a phone that freezes background apps cannot cut "
    "a recording short.",
    "<strong>The app collects the finished file</strong> over a socket on which "
    "the recorder and the app each prove who they are.",
]


def how_steps() -> str:
    """The numbered steps; pointing at one lights its part of the diagram."""
    items = "".join(f'\n        <li data-step="{n}">{s}</li>' for n, s in enumerate(HOW_STEPS, 1))
    return f'<ol class="numbered">{items}\n      </ol>'


WHY = [
    ("both", "Both sides of every call",
     "Phone calls, and WhatsApp, Telegram, Viber and Google Meet calls on Android "
     "14 or newer."),
    ("self", "No root, no computer, no second app",
     "The phone pairs with its own Wireless debugging and sets itself up, once, "
     "in a few minutes."),
    ("lock", "Nothing leaves your phone",
     "No account, no cloud, no analytics. The only network JemRec uses is the "
     "phone talking to itself."),
    ("bell", "It tells you when something is wrong",
     "A recording that came out silent, a recorder that is down, a call that "
     "went by unrecorded &mdash; each gets a notification, so you do not find "
     "out on the day you need the call."),
    ("rules", "Your rules",
     "Record every call or be asked first, keep only some people&rsquo;s calls, "
     "and let old or very short recordings clear themselves away."),
    ("open", "Free, open and verifiable",
     "Apache-2.0, and F-Droid rebuilds every release from this source and "
     "checks it matches, byte for byte."),
]

# (title, text, where in the manual, the link's words)
STEPS = [
    ("Install it",
     "From F-Droid, or the APK from the Releases page &mdash; the same signed "
     "file either way, so each can update the other.",
     "setup.html#installing", "Trouble installing?"),
    ("Follow the setup",
     "JemRec walks you through it and watches for each step: Developer options, "
     "USB and Wireless debugging (no cable needed), and pairing &mdash; the "
     "phone pairs with itself. Type the six-digit code into JemRec&rsquo;s "
     "<em>notification</em>, not the app.",
     "setup.html#step-1-open-jemrec-and-allow-notifications", "The setup, step by step"),
    ("Let it keep running",
     "On phones that stop apps in the background, setup asks you to allow "
     "JemRec there, and a notification says so if the phone stops it anyway "
     "or holds back its restart. Allowed, the recorder is back seconds after a restart and Wi-Fi.",
     "setup.html#stopped-in-the-background", "Stopped in the background"),
]

# (screen, title, caption)
SCREENS = [
    ("home", "Recordings", "By day, with who each call was with."),
    ("calls", "Calls", "Your call log, each call with its recording &mdash; or why there is none."),
    ("settings", "Settings", "Every choice at a glance, each one a tap away."),
    ("theme", "Theme", "Three looks, each light or dark, and the one you pick, shown."),
    ("answering", "Answering machine", "Your greeting, when it answers, and a test call to prove it."),
    ("whenoff", "When JemRec is off", "For banking apps: what else goes off with JemRec."),
    ("selftest", "Self-test", "Whether your phone hands over both sides, in a second."),
]

# (title, text, section of the manual); FEATURE_ROWS sets them beside pictures.
FEATURES = [
    ("Record every call, or be asked",
     "Automatic mode records every call; ask-first records nothing until you "
     "tap Record. A Quick Settings tile turns recording on and off, and a "
     "long press on it opens JemRec.",
     "choosing-when-to-record"),
    ("Start at the answer",
     "Exact call tracking cuts the ringing, skips calls nobody answered, and "
     "splits call waiting into one recording per call.",
     "exact-call-tracking"),
    ("Keep only the calls you want",
     "All your contacts, all but some, only some, or none &mdash; and other "
     "numbers on their own switch.",
     "whose-calls-to-keep"),
    ("Messenger calls",
     "WhatsApp, Telegram, Viber, and Google Meet calls and meetings, both "
     "sides, each switched on separately, with the caller&rsquo;s name from "
     "the app&rsquo;s notification. Android 14+.",
     "messenger-calls"),
    ("See every call",
     "The Calls tab is your call log, each call with its recording &mdash; or, "
     "if there is none, why. Search either list, or narrow it with a tap.",
     "the-calls-tab"),
    ("Name files your way",
     "By date and time, with the caller, with the phone number for a program "
     "that sorts them, or the way Honor&rsquo;s Phone app plays them from its "
     "call history.",
     "file-names"),
    ("Play, keep, clean up",
     "A call plays as its waveform, to see where people talk, and plays on with "
     "the screen off, under the phone&rsquo;s media controls; a keep mark, a "
     "30-day Recently deleted, and auto-delete for old or very short ones.",
     "playing-sharing-and-deleting"),
    ("Hear about problems",
     "A notification after each call, and one when a recording is silent, the "
     "recorder is down, a call went unrecorded, or the phone stopped JemRec or "
     "held it back in the background. A call that came out wrong? <em>Copy a "
     "report</em> in Diagnostics says how it was recorded, for an issue on "
     "GitHub.",
     "what-the-app-tells-you"),
    ("Answering machine",
     "Off until you switch it on. Answers a phone call you have not taken after 5 to "
     "30 seconds &mdash; after two rings when Do Not Disturb would silence the "
     "caller &mdash; plays the greeting you recorded and keeps the message, by "
     "the same rules as recording. Take the call or hang up from its "
     "notification while they speak. It stands down for a day after you call "
     "an emergency number. A test call tells you whether it works on your "
     "phone.",
     "the-answering-machine"),
    ("Three looks",
     "Memo, calm and simple; Deck, a pocket recorder with an LCD and a REC key; "
     "Multitrack, a sound editor with a colour for each caller. Each light, dark "
     "or as the phone, in Settings &rarr; Theme.",
     "three-looks"),
    ("Open your banking app",
     "Some banking apps won&rsquo;t open while Developer options or debugging "
     "is on. Turning JemRec off can hide Developer options too, or switch all "
     "debugging off; turning it on puts back what it switched off &mdash; away "
     "from Wi-Fi too, with <em>Start without Wi-Fi</em>. Settings &rarr; When "
     "JemRec is off.",
     "off-and-debugging-with-it"),
]

PRIVACY_POINTS = [
    ("mic", "No microphone permission",
     "The app never records; the recorder does the capturing &mdash; calls, "
     "and your answering-machine greeting."),
    ("cloud", "Nothing leaves the phone",
     "No account, no server, no analytics, and nothing is collected about you. "
     "<em>Copy a report</em> in Diagnostics puts how the last call was recorded "
     "on the clipboard, with no number, name or sound in it, only when you tap "
     "it."),
    ("person", "Calls and contacts only if you allow them",
     "To name callers, keep calls by who they were with, and notice a call that "
     "went unrecorded. Everything works without them."),
    ("screenlock", "No caller on the lock screen",
     "While a recording plays, the phone&rsquo;s media controls name the caller "
     "only once it is unlocked; locked, they say <em>Call recording</em>."),
    ("adb", "Debugging stays on while JemRec is on",
     "The recorder needs it; while JemRec is off it can go too (Settings &rarr; "
     "When JemRec is off). A computer you have paired can connect over the "
     "same Wi-Fi, so now and then check <em>Wireless debugging &rarr; Paired "
     "devices</em>. With <em>Start without Wi-Fi</em> on, port 5555 is open "
     "too, to every network the phone joins, and announced there by the "
     "phone&rsquo;s serial number."),
]

FAQ = [
    ("Does it really record both sides?",
     "Yes, where the phone&rsquo;s audio hardware hands both sides over &mdash; "
     "which it did on every phone above. The Self-test tells you for yours in "
     "about a second. In a messenger call each side has a channel of its own, "
     "you on the left and the other person on the right; a phone call has them "
     "so where the phone keeps the two apart, as the Honor does, and on others "
     "&mdash; a Pixel, by one report &mdash; both channels carry the same mix. "
     "On speakerphone, a messenger call&rsquo;s other person is "
     "heard on your side too: your side is the plain microphone, without the "
     "call app&rsquo;s echo cancellation. A call that still comes out with one "
     "side &mdash; over Wi-Fi Calling, say &mdash; is worth an issue on GitHub: "
     "tap <em>Copy a report</em> in Diagnostics right after it and paste that "
     "in."),
    ("Does it record Google Meet?",
     "Yes, calls and meetings, both sides, on Android 14 or newer &mdash; once "
     "you switch Google Meet on under <em>Messenger calls</em>. Like the "
     "messengers, it is off until you do. Meet puts its calls through "
     "Android&rsquo;s own call service, and up to 0.21 JemRec took them for "
     "phone calls and recorded every one, meetings included. Now a call is a "
     "phone call only when Android says the phone itself is in one."),
    ("Will callers hear my greeting?",
     "On the two phones it has been tried on, yes. No app can play sound into a "
     "call, so the recorder does it as the shell: on the Honor through an output "
     "Android calls <em>in-call music</em>, on a Galaxy S23 over Wi-Fi Calling "
     "through Android's call-screening route; not every phone has either. Before you switch the "
     "answering machine on, its <em>Checks</em> tell you in a second whether "
     "yours does, and a <em>Test call</em> settles it for that kind of call: "
     "2G, VoLTE and Wi-Fi Calling differ. Your microphone is muted while the "
     "greeting plays and the message is left, so the caller hears your greeting "
     "and nothing of your room."),
    ("Why does it need Wi-Fi?",
     "Only to <em>start</em> the recorder: that is the one step that needs "
     "Wireless debugging, which Android runs over Wi-Fi. Once started, the "
     "recorder carries on with Wi-Fi off, on mobile data, anywhere. A reboot "
     "stops it, and the next few seconds of Wi-Fi bring it back by themselves "
     "(see <a href=\"#get-started\">Get started</a>, step 3). Unplugging the "
     "phone from a computer can stop it too (a charger does not); on Wi-Fi it "
     "is back within seconds, by itself. With the screen off, step 3 matters "
     "here as well: without it, an Honor&rsquo;s battery manager kept the "
     "recorder down for about four minutes in two tests of three, and 8.5 "
     "minutes in another. Until then nothing is recorded &mdash; the header says "
     "so, a notification says the phone held JemRec back the moment it lets "
     "JemRec run again, and one says the recorder is down if it stays down two "
     "minutes. And if When JemRec is off is set to <em>Turn all debugging "
     "off</em>, turning JemRec back on needs Wi-Fi too. Settings &rarr; "
     "<em>Start without Wi-Fi</em> takes the Wi-Fi away from all of that until "
     "the phone restarts: it opens debugging port 5555, which the recorder can "
     "be started over with no network at all. After a restart, the first start "
     "needs Wi-Fi once, as before. The cost is the open port, on every network "
     "the phone joins whenever USB debugging is on &mdash; unless <em>Turn all "
     "debugging off</em> goes with JemRec."),
    ("Why isn&rsquo;t it on Google Play?",
     "Play removed call recorders in 2022 by forbidding the Accessibility API "
     "for recording, and no policy change since has re-opened the door. That is "
     "a distribution decision, not a technical one &mdash; the app itself never "
     "needed Play, and F-Droid asks only that a build be free software and "
     "reproducible from its source, which this one is. It never will be on "
     "Play."),
    ("Is recording calls legal?",
     "It depends on where you and the other person are: some places need one "
     "party&rsquo;s consent, some everyone&rsquo;s, some a spoken notice. That "
     "is your responsibility; JemRec records what you tell it to and warns "
     "nobody on your behalf."),
    ("Where are my recordings, and what happens to them if I uninstall?",
     "In the phone&rsquo;s standard <code>Recordings/JemRec</code> folder (or "
     "the folder you chose), as Opus files any music app can play; Share can "
     "also convert one to <code>.m4a</code> for iPhones and Macs. They are "
     "yours rather than the app&rsquo;s, so they stay when JemRec is "
     "uninstalled &mdash; delete them first if you want them gone. Deleted ones "
     "wait in Recently deleted for 30 days. Installed again, JemRec shows them "
     "as they were, once you let it read them (it asks): each recording "
     "carries its own marks &mdash; a message, kept, who a messenger call was "
     "with &mdash; inside the file."),
    ("Setup says Android would not let JemRec change debugging settings",
     "Your phone refuses JemRec the one setting setup grants it until a switch "
     "of the phone&rsquo;s own is set, and the card names it. On an OPPO, OnePlus "
     "or realme phone it is <em>Disable system optimization</em>, the last "
     "switch in Developer options&rsquo; Apps section (<em>Disable permission "
     "monitoring</em> on older ones; where it says just <em>Permission "
     "monitoring</em>, turn that off instead); some show it only in English, so if it "
     "is not there, set the phone&rsquo;s language to English, turn it on and "
     "set the language back. On a Xiaomi it is <em>USB debugging (Security "
     "settings)</em>. Then tap <strong>Try again</strong>. Setup does not "
     "finish without that setting: it is what lets JemRec turn Wireless "
     "debugging back on after a restart. Leave it that way afterwards: the "
     "recorder needs it too, to turn debugging off when JemRec is "
     "uninstalled. Reported from two OnePlus phones "
     "(issues #61 and #64); we have none to try it on."),
    ("My banking app won&rsquo;t open while debugging is on",
     "Choose in Settings &rarr; <em>When JemRec is off</em> what goes off with "
     "JemRec, then turn JemRec off &mdash; with the record button or the Quick "
     "Settings tile &mdash; before you open the bank. <em>Hide Developer "
     "options</em> switches Developer options and Wireless debugging off; USB "
     "debugging stays on, so JemRec keeps its recorder and turns back on "
     "instantly, anywhere. <em>Turn all debugging off</em> switches USB "
     "debugging off too, and the recorder with it: turning JemRec back on then "
     "needs Wi-Fi and takes about 15 seconds, the answering machine is off until "
     "then, and away from Wi-Fi JemRec asks first &mdash; unless <em>Start "
     "without Wi-Fi</em> is on (Settings &rarr; Debugging): then it comes back "
     "anywhere, on mobile data or none, until the phone restarts. Try the "
     "first; if your bank "
     "still refuses, the second. With <em>Start without Wi-Fi</em> on, the first "
     "leaves debugging port 5555 open while JemRec is off, which a bank may look "
     "for too; the second closes it. Turning JemRec back on puts back what it "
     "switched off. Our own banks open with debugging on, so we could not test "
     "one that refuses &mdash; if yours needed the second choice, or neither "
     "worked, say so in <a href=\"https://github.com/jemcik/JemRec/issues/37\">"
     "issue #37</a>."),
    ("How do I turn debugging off for good?",
     "Uninstall JemRec first. If the recorder is running, it notices the "
     "uninstall within a few minutes, turns both debugging switches off and "
     "exits &mdash; on an OPPO, OnePlus or realme phone only while its "
     "<em>Disable system optimization</em> switch is set as setup asked, or "
     "they stay on for you to turn off. Either way, once JemRec is gone nothing turns them back on. To "
     "have it off only while JemRec is off, see the question above."),
]


def _tour() -> str:
    """A look around: every screen in the phone, one showing, and the strip
    that picks it (the script)."""
    named = dict(LOOKS)
    screens = "".join(
        f'\n      <div class="tour-screen" data-screen="{name}"{" hidden" if i else ""}>'
        + "".join(
            shot(thumb, full, f"{title}, in {named[look]}{', dark' if theme == 'dark' else ''}",
                 theme, look, "(max-width: 54rem) 16rem, 19rem")
            for look, theme, thumb, full in _variants(name))
        + "</div>"
        for i, (name, title, _) in enumerate(SCREENS)
    )
    thumbs = "".join(
        f'\n        <button type="button" data-screen="{name}" data-title="{title}" data-text="{caption}" '
        f'aria-pressed="{"false" if i else "true"}" aria-label="{title}: {caption}" title="{title}">'
        + "".join(f'<img class="look-{look} {theme}-only" src="{thumb}" alt="" width="420" height="939" loading="lazy">'
                  for look, theme, thumb, _ in _variants(name))
        + "</button>"
        for i, (name, title, caption) in enumerate(SCREENS)
    )
    return f"""<div class="tour">
    <div class="tour-side">
      <h3 class="tour-title">{SCREENS[0][1]}</h3>
      <p class="tour-text" aria-live="polite">{SCREENS[0][2]}</p>
      <div class="thumbs" role="group" aria-label="Screens">{thumbs}
      </div>
    </div>
    <div class="tour-phone">{screens}
    </div>
  </div>"""


# What you can do, a row each: the picture, then the FEATURES beside it.
FEATURE_ROWS = [
    (lambda: timeline("tracking"),
     ["Record every call, or be asked", "Start at the answer", "Keep only the calls you want"]),
    (lambda: crop("calls", "The Calls tab: calls that were missed, not answered, or "
                           "hung up during the greeting"),
     ["See every call", "Messenger calls"]),
    (lambda: crop("home", "A recording playing, as its waveform"),
     ["Play, keep, clean up", "Name files your way"]),
    (shade, ["Hear about problems"]),
    (lambda: crop("answering", "The answering machine&rsquo;s settings"), ["Answering machine"]),
    (trio, ["Three looks"]),
    (lambda: crop("whenoff", "When JemRec is off: its three choices"), ["Open your banking app"]),
]


def _features() -> str:
    by_title = {f[0]: f for f in FEATURES}
    shown = [t for _, titles in FEATURE_ROWS for t in titles]
    assert sorted(shown) == sorted(by_title), "every feature in exactly one row"
    rows = []
    for picture, titles in FEATURE_ROWS:
        text = "".join(
            f'<div class="feature"><h3>{t}</h3><p>{by_title[t][1]}</p>'
            f'<a class="more" href="setup.html#{by_title[t][2]}" aria-label="More on {t}">More &rarr;</a></div>'
            for t in titles)
        rows.append(f'\n    <div class="row"><div class="row-text">{text}</div>'
                    f'<div class="row-pic">{picture()}</div></div>')
    return '<div class="rows">' + "".join(rows) + "\n  </div>"


INDEX_BODY = f"""
<section class="column hero">
  <div>
    <div class="lcd">
      <span class="eyebrow">Call recorder for Android 12 and newer</span>
      <h1>Both sides of every call. <span>No root.</span></h1>
    </div>
    <p class="lead">Phone calls, and WhatsApp, Telegram, Viber and Google Meet calls. No
       computer and no second app &mdash; the phone pairs with its own Wireless
       debugging and sets itself up.</p>
    {STORES}
    <ul class="facts">
      <li>Free and open source</li>
      <li>No account, no cloud</li>
      <li>Built and checked by F-Droid</li>
    </ul>
  </div>
  <div class="phones">
    <div class="phone back">{themed("calls", "The calls tab", 232)}</div>
    <div class="phone front">{themed("home", "The recordings list", 272)}</div>
  </div>
</section>

<section class="column" id="why">
  <h2>Why JemRec</h2>
  <p class="intro">Android stopped letting apps record calls years ago. JemRec
     records them anyway, both sides, without asking you to root your phone.</p>
  <div class="reasons">
    {"".join(reason(*c) for c in WHY)}
  </div>
</section>

<section class="band" id="get-started">
<div class="column">
  <h2>Get started</h2>
  <p class="intro">About three minutes, on the phone, once.</p>
  <ol class="steps">
    {"".join(f'<li><h3>{t}</h3><p>{text}</p><a class="more" href="{href}">{link} &rarr;</a></li>' for t, text, href, link in STEPS)}
  </ol>
  {video_player(VIDEO_CAPTION)}
  <p class="after-steps">Then run <strong>Settings &rarr; Diagnostics &rarr;
     Self-test</strong>: it answers, in about a second, whether your phone hands
     over both sides of a call. <a href="setup.html">The full manual</a> says
     what to do when a step misbehaves.</p>
  <div class="note">
    <p><strong>Recording calls is your responsibility.</strong> Consent law
       differs by country and sometimes by state: one party, all parties, or a
       spoken notice. JemRec records what you tell it to and warns nobody on
       your behalf.</p>
  </div>
</div>
</section>

<section class="column" id="screens">
  <h2>A look around</h2>
  <p class="intro">Pick a screen from the strip; tap the phone to see it full
     size.</p>
  {_tour()}
  <p class="gallery-note">The app comes in three looks, each following your
     phone&rsquo;s light or dark; these follow the page&rsquo;s &mdash; the
     buttons at the top switch the look and the theme. The people in them do
     not exist: a screenshot of a call recorder is a
     screenshot of someone&rsquo;s calls, so the list is filled with invented
     ones. Everything else is real.</p>
</section>

<section class="column" id="features">
  <h2>What you can do</h2>
  <p class="intro">Each of these has its section in the manual.</p>
  {video_player(FEATURES_VIDEO_CAPTION, FEATURES_VIDEO, FEATURES_VIDEO_LENGTH,
                "video/features.jpg", "features video")}
  {_features()}
  <p class="tiles-note">Recordings are Opus files in the phone&rsquo;s standard
     <code>Recordings/JemRec</code> folder, where any file manager or music app
     can open them &mdash; or in a folder you choose.</p>
</section>

<section class="band" id="how">
<div class="column">
  <div class="how how-box">
    <div>
      <h2>How it works</h2>
      <p>Android&rsquo;s <code>voice-call</code> audio source carries both sides
         of a call, but only system-level identities may read it &mdash; and
         the one an ADB session runs as, the <strong>shell</strong>, is among
         them. Getting a shell normally takes a computer, root, or a helper app
         like Shizuku. JemRec makes the phone its own computer:</p>
      {how_steps()}
      <p>Wi-Fi is needed only to start the recorder &mdash; after a reboot, say
         &mdash; never to record.</p>
      <a class="more" href="how-it-works.html">The design, and the measurements
         behind each decision &rarr;</a>
    </div>
    {diagram()}
  </div>
</div>
</section>

<section class="column" id="phones">
  <h2>Will it work on your phone?</h2>
  <p class="intro">Developer options and Wireless debugging are on every Android
     phone, though the menu they live in differs by make; JemRec names the right
     path for yours. OPPO, OnePlus and realme phones on their own systems
     (ColorOS, OxygenOS, realme UI) need one more switch there, and setup names
     that too.</p>
  <div class="works">
    <h3 class="versions">What it records, by Android version</h3>
    <h3 class="tested">Both sides recorded on five phones</h3>
    <div class="table-card versions">
      <table class="strip">
        <tr><th>Android</th>{"".join(f"<th>{v}</th>" for v in range(12, 18))}</tr>
        <tr><th scope="row">Phone calls</th>
            <td class="y" colspan="6"><span aria-hidden="true">&#10003;</span> Android 12 and newer</td></tr>
        <tr><th scope="row">Messenger calls</th>
            <td class="n" colspan="2">Android does not allow it</td>
            <td class="y" colspan="4"><span aria-hidden="true">&#10003;</span> WhatsApp, Telegram, Viber, Google Meet</td></tr>
      </table>
    </div>
    <ul class="table-card devices tested">
      <li>Honor Magic 8 Pro<span>MagicOS 10 and 11, Android 16 and 17 &mdash; the phone it was developed on</span></li>
      <li>Samsung Galaxy S20<span>One UI</span></li>
      <li>Samsung Galaxy S23<span>One UI, Android 16 &mdash; over Wi-Fi Calling too</span></li>
      <li>Samsung Galaxy M34<span>One UI 8.5, Android 16 &mdash; over Wi-Fi Calling too, as its owner reported</span></li>
      <li>OnePlus<span>LineageOS 23</span></li>
    </ul>
  </div>
  <p class="muted works-note">Google Meet was tested on the Honor: calls both ways
     and a meeting. Signal takes the same path as the other messengers and has
     not been tested yet.</p>
  <div class="note info">
    <p><strong>Five is not a compatibility list.</strong> The one thing nobody
       can promise for a phone they have not held is its audio hardware: some
       hand over both sides of the call, some one, some silence. The
       <strong>Self-test</strong> answers it on yours in about a second.</p>
    <p><strong>The answering machine</strong> has been tried on two phones: the
       Honor, on 2G calls (its carrier would not register it for VoLTE), and a
       Galaxy S23 over Wi-Fi Calling. Whether a phone
       carries a greeting to the caller is up to its audio hardware too, so the
       answering machine has its own checks and a test call.</p>
  </div>
</section>

<section class="band" id="privacy">
  <div class="column">
    <h2>Privacy and permissions</h2>
    <p class="intro">JemRec has no servers, no accounts and no analytics: what it
       records stays on your phone.</p>
    <div class="reasons">
      {"".join(reason(*p) for p in PRIVACY_POINTS)}
    </div>
    <div class="links">
      <a class="more" href="privacy-policy.html">Privacy policy &rarr;</a>
      <a class="more" href="how-it-works.html#permissions">Every permission, and why &rarr;</a>
      <a class="more" href="{REPO}/blob/main/docs/ARCHITECTURE.md#what-stays-switched-on">What stays switched on &rarr;</a>
    </div>
  </div>
</section>

<section class="column faq" id="faq">
  <div>
    <h2>Questions</h2>
    <p class="intro">Something else? Ask in
       <a href="{REPO}/discussions">Discussions</a>, where ideas go too. A call
       that came out wrong is worth
       <a href="{REPO}/issues/new?template=bug.yml">an issue</a>,
       with <em>Copy a report</em> from Diagnostics, tapped before the next
       call.</p>
  </div>
  <div>
    {"".join(f"<details><summary>{q}</summary><div><p>{a}</p></div></details>" for q, a in FAQ)}
  </div>
</section>

<section class="column closing">
  <img src="icon.png" alt="" width="72" height="72">
  <h2>Try it on your phone</h2>
  <p class="intro">Free, no account, about three minutes to set up &mdash; and
     the Self-test tells you in a second whether your phone hands over both
     sides.</p>
  {STORES}
  <p class="support" id="support"><span aria-hidden="true">&#9829;</span> JemRec is
     free: no ads, no account, nothing collected. If it has been useful to you,
     <a href="{DONATE}">support its development on Donatello</a>.</p>
</section>
"""

HOW_BODY = f"""
  <h1>How JemRec works</h1>
  <p class="lead">Android&rsquo;s <code>voice-call</code> audio source carries both sides of a
     call, and reading it needs permissions no ordinary app can hold &mdash; but
     the shell user, the one an ADB session runs as, holds them. Getting shell
     privilege normally means a computer, or root, or a second app you keep
     alive. JemRec needs none of them, because the phone can be its own
     computer.</p>

  <div class="how-box solo">
    {diagram()}
    {how_steps()}
  </div>
  <p class="muted">The whole design, with the measurement behind each decision,
     is in <a href="{REPO}/blob/main/docs/ARCHITECTURE.md">ARCHITECTURE.md</a>.</p>

  <h2 id="design">The design</h2>

  <h3>The phone is both ADB host and ADB device</h3>
  <p>The app embeds an ADB client and pairs, over <code>127.0.0.1</code>, with
     the phone&rsquo;s own Wireless debugging &mdash; the same six-digit pairing a
     laptop does, with both ends on the same handset. Nothing leaves the phone.</p>

  <h3>ADB is a bootstrap, not a transport</h3>
  <p>That session lasts seconds. In it the app grants itself one settings
     permission and launches a small daemon from a jar it carries inside the APK,
     then closes the session. A process started from an ADB shell outlives the
     connection, which is the whole trick: with Wi-Fi off there is no ADB at all,
     so anything that needed it when a call arrived would simply not record.</p>

  <h3>The daemon records; the app collects</h3>
  <p>The daemon watches the call state and writes the call to a file. The app is
     never on the audio path. That split was forced by measurement: when the app
     handled live audio, the phone&rsquo;s process manager froze it while the
     dialer was in front and a 76.7-second call arrived as 14 to 44 seconds.
     Afterwards the app fetches the finished file at whatever pace the system
     allows, and a freeze there costs a slower copy rather than a lost
     second.</p>
  {timeline("freeze")}

  <h3>The socket is authenticated both ways</h3>
  <p>The daemon listens on loopback, which every process on the phone shares, so
     each connection proves itself with an HMAC exchange over a token that never
     crosses the socket. The daemon proves itself first &mdash; anything can bind
     that port while the daemon is down, and a scheme that sent the token as a
     password would hand it over.</p>

  <h3>It keeps itself alive</h3>
  <p>Losing Wi-Fi restarts the ADB daemon and kills what it spawned, so the
     recorder keeps one debugging switch on to survive it, and minds that switch
     itself. That is what lets a call be recorded in a field with no network.
     Some phones switch it off themselves &mdash; an Honor&rsquo;s Settings app
     does each time Developer options opens &mdash; and the app is woken the
     moment that happens, to switch it back on.</p>
  <p>A reboot is one thing it cannot survive, because starting the recorder
     is the single operation that needs ADB. A scheduled job and a system-held
     Wi-Fi watcher rebuild it without you within seconds of the phone joining a
     network &mdash; when the phone lets the app start in the background at boot
     to arm them. Where it does not, nothing is armed and nothing begins, which
     is why the manual says to open the app once after a restart. Either way the
     app says plainly that it cannot record rather than looking healthy, and
     after two minutes a notification says it too.</p>
  <p>Unplugging the phone from a computer is the other. Measured on an Honor:
     three seconds after the cable came out, the phone stopped the ADB daemon,
     the recorder went with it, and nothing started the ADB daemon again. So the
     recorder watches the USB port and spends those three seconds waking the
     app, which switches Wireless debugging off and on &mdash; what has Android
     start the ADB daemon &mdash; and starts the recorder: recording again 12 to
     16 seconds after the cable came out, nothing tapped. With the screen off
     that needs the app allowed to start by itself: left to the phone, an
     Honor froze it until it was woken &mdash; about four minutes, once 8.5
     &mdash; and JemRec says so the moment it may run again, with a button to
     the setting. A charger stops nothing.</p>
  {timeline("unplug")}
  <p>Some phones also stop apps in the background to save power, and a stopped
     JemRec cannot start the recorder again once it goes. JemRec cannot change
     that setting, so setup asks you to, on the phone&rsquo;s own screen for
     it; if the phone stops JemRec anyway &mdash; while it was working, or
     leaving it stopped &mdash; a notification says so. Android&rsquo;s routine
     clean-up of an idle app is not counted: on an Honor, 2,015 of 2,062 such
     records were that, and none had left the app stopped.</p>

  <h2 id="what-it-does">What it does</h2>

  <h3>WhatsApp, Telegram, Viber and Google Meet calls too</h3>
  <p>Messenger calls and Google Meet, both sides, each app switched on separately &mdash; and
     the caller&rsquo;s name, if you let JemRec read the app&rsquo;s call
     notification. Tested with WhatsApp, Telegram, Viber and Google Meet; Signal takes the
     same path and is not tested yet. Android allows this from version 14; on
     an older one, Settings says so.</p>

  <h3>An answering machine, if you want one</h3>
  <p>Off until you switch it on. It answers a call you have not taken, plays
     your own greeting with your microphone muted, and keeps the caller&rsquo;s
     message. No app can play sound into a call, so the recorder does it, as the
     shell, through an output Android calls in-call music. Android&rsquo;s own
     answer on whether a phone can do that was wrong on the Honor, so a test
     call, not a guess, says whether it works on yours.</p>

  <h3>It tells you when something is wrong</h3>
  <p>A recorder that fails quietly is found out on the day a call was needed. So
     after each call a silent notification shows the recording, with Play, Share
     and Delete; every recording is measured once it is saved, and one that came
     out silent is reported; a recorder that is down says why, with
     <strong>Pair again</strong> when the pairing is gone and
     <strong>Allow this Wi-Fi</strong> when Android has not allowed the network;
     and with call log
     access, an answered call that has no recording is reported once.</p>

  <h3>Every call, recorded or not</h3>
  <p>A Calls tab shows the phone's call log with a play mark on each recorded
     call, and, on one that was not recorded, why: recording was off, it was too
     short, it is in Recently deleted, or &mdash; in red &mdash; the answering
     machine could not take a message, or nothing explains it.</p>

  <h3>Cut to the call</h3>
  <p>A recording starts when the call is answered, not when you dial: no
     ringing at the start, and a call nobody picked up is not kept. With call
     waiting, each call gets a recording of its own. Android tells JemRec when
     each call is answered; tracking only listens. The one thing that ever
     answers a call is the answering machine, when you switch it on.</p>
  {timeline("tracking")}

  <h3>Only the calls you want</h3>
  <p>Keep every call, only your contacts&rsquo;, only strangers&rsquo;, or
     everyone&rsquo;s but a few people&rsquo;s. Name files by date, or with the
     caller or their number in them &mdash; and on an Honor, the way
     Honor&rsquo;s own Phone app looks for them, so its call history gets a
     play button.</p>

  <h3>Nothing is deleted by accident</h3>
  <p>Delete moves a recording to Recently deleted, where it can be restored for
     30 days, one by one or all at once, unless you empty it first. Auto-delete, off until you choose it, removes recordings past an
     age you pick and calls too short to have been a conversation, into the same
     place. A bookmark marks a recording to keep, and nothing removes a kept one.
     A Quick Settings tile turns recording on and off without opening the
     app, and a long press on it opens the app.</p>

  <h3>Off, and debugging with it, for banking apps</h3>
  <p>Some banking apps won&rsquo;t open while Developer options or debugging is
     on. Turning JemRec off can hide Developer options and Wireless debugging
     too &mdash; the recorder keeps running on USB debugging, so it is back
     the moment JemRec is on &mdash; or switch all debugging off, the recorder
     with it, so that turning it on needs Wi-Fi. Turning it on puts back what it
     switched off. Settings &rarr; When JemRec is off.</p>
  <p><em>Start without Wi-Fi</em>, off until you switch it on, takes that Wi-Fi
     away until the phone restarts. Android allows Wireless debugging only on a
     Wi-Fi network the phone has joined, but adbd has another way in, its
     classic port, 5555, which listens with no network at all; JemRec asks adbd
     to open it, once each boot, over Wireless debugging. The cost is that the
     port is open on every network the phone joins whenever USB debugging is
     on &mdash; while JemRec is on, and while it is off unless all debugging goes
     with it &mdash; and announced there by the phone&rsquo;s serial number: a
     computer that reaches it, or an app on the phone, cannot debug the phone
     without a yes on it, but it can ask.</p>

  <h2 id="permissions">What it asks for, and what it does not</h2>
  <p>No microphone permission. The app never records &mdash; the shell-side
     process does the capturing, your answering-machine greeting included
     &mdash; and a call recorder that asks
     for the microphone anyway is asking for something it does not use. Reading
     your call log and contacts is optional, offered once, and is used for two
     things only: labelling a recording with who the call was with, and noticing
     a call that went unrecorded. Knowing when a call is answered takes one more
     permission, one Android does not let apps ask for: JemRec&rsquo;s recorder
     allows it, and turning off Exact call tracking in Settings takes it
     back.</p>
  <p>Every permission, in the order F-Droid&rsquo;s listing page shows them,
     because a permission a call recorder does not explain is one its user has
     to guess at:</p>
  <table>
    <tr><th>Permission</th><th>Why</th></tr>
    <tr><td><code>INTERNET</code></td><td>Not about the internet: Android requires it for any TCP socket, including one to <code>127.0.0.1</code>.</td></tr>
    <tr><td><code>ACCESS_NETWORK_STATE</code></td><td>To tell whether there is Wi-Fi to rebuild the recorder over, instead of trying and failing.</td></tr>
    <tr><td><code>WRITE_SECURE_SETTINGS</code></td><td>Granted by the app to itself over its own ADB session. Nothing but a shell can grant it, so no user can be talked into it. It lets JemRec switch debugging back on when the phone turns it off, and off with JemRec when you ask it to.</td></tr>
    <tr><td><code>POST_NOTIFICATIONS</code></td><td>The recording notice, the ask-first prompt, the pairing code box.</td></tr>
    <tr><td><code>FOREGROUND_SERVICE</code>, <code>FOREGROUND_SERVICE_SPECIAL_USE</code></td><td>To stay alive while a call is being recorded and while the finished file is collected; while the recorder is restarted after a computer's cable comes out; and for the seconds it takes to warn that the phone stopped JemRec in the background. <code>SPECIAL_USE</code> is the honest type: the microphone type would need a microphone permission this app does not hold.</td></tr>
    <tr><td><code>RECEIVE_BOOT_COMPLETED</code></td><td>A reboot kills the recorder; this is what arms the job that rebuilds it.</td></tr>
    <tr><td><code>READ_MEDIA_AUDIO</code></td><td>To list recordings made before a reinstall. Refusing it only hides those.</td></tr>
    <tr><td><code>READ_EXTERNAL_STORAGE</code></td><td>Android 12 only, which has no <code>READ_MEDIA_AUDIO</code>: the same, to list recordings made before a reinstall. It covers photos and videos too; JemRec reads only its own folder. Refusing it only hides those.</td></tr>
    <tr><td><code>READ_CALL_LOG</code>, <code>READ_CONTACTS</code></td><td>Optional. The only way to know who a call was with: to label a recording, to name a file after the caller, to keep or leave out a call by who it was with, and, for the call log, to notice a call that went unrecorded.</td></tr>
    <tr><td><code>MANAGE_ONGOING_CALLS</code></td><td>For exact call tracking. Not one an app can ask for: the daemon allows it, as the shell, while the switch is on. Android then tells JemRec when each call is answered and ends. Tracking never acts on a call; the answering machine&rsquo;s answering is the recorder&rsquo;s, as the shell.</td></tr>
    <tr><td><code>USE_FULL_SCREEN_INTENT</code></td><td>Lets the ask-first prompt last a minute instead of five seconds. Not auto-granted, and nothing is gated on it.</td></tr>
    <tr><td><code>WAKE_LOCK</code></td><td>So the keep-alive job can finish its few seconds of work, and so a phone with its screen off stays awake while it brings the recorder back after a USB cable, a minute and a half at most. WorkManager declares it too.</td></tr>
  </table>

  <div class="note">
    <p><strong>Recording calls is your responsibility.</strong> Consent law
       differs by country and sometimes by state: one party, all parties, or a
       spoken notice. This app records what you tell it to and warns nobody on
       your behalf.</p>
  </div>

  <h2>Will it work on your phone?</h2>
  <p>One thing cannot be worked around: whether your phone&rsquo;s audio hardware
     hands over the call at all. Some give both sides, some give one, some give
     silence. JemRec answers it in about a second &mdash; Settings, Diagnostics,
     <strong>Self-test</strong> &mdash; by opening a real capture down the same
     path a call takes and telling you what came out.</p>
  <p class="muted">Both sides of the call have been recorded on five phones,
     across three very different Android builds: an Honor Magic&nbsp;8 Pro on
     MagicOS&nbsp;10, a Samsung Galaxy&nbsp;S20, a Galaxy&nbsp;S23 and a
     Galaxy&nbsp;M34 on One&nbsp;UI &mdash; the S23 and the M34 over Wi-Fi
     Calling too &mdash; and a OnePlus on LineageOS&nbsp;23. Five is not a
     compatibility list, which is what the self-test is for.</p>
  <p class="muted">OPPO, OnePlus and realme phones on their own systems
     (ColorOS, OxygenOS, realme UI) refuse JemRec the setting setup grants it
     until a switch of their own is set: <em>Disable system
     optimization</em> on, the last in Developer options&rsquo; Apps section
     (<em>Disable permission monitoring</em> on older ones, or
     <em>Permission monitoring</em> off). Setup names it before it is needed,
     and again if the phone refuses.</p>
"""

# WHAT STAYS ON THE PHONE, a row each: what it is, where it lives, how long
# it is kept, and when there is any - each a chip to scan - and the policy's
# own words for it, unfolded from the row. The chips summarise the words and
# never add to them; where they differ, the words are the policy.
# (data, where, how long, when, the words)
PRIVACY_DATA = [
    ("Call recordings", "Recordings/JemRec, or your folder", "until you delete them", "every recorded call",
     "The phone&rsquo;s <code>Recordings/JemRec</code> folder, or a folder you "
     "choose. Yours to play, share, move or delete like any other file. One "
     "deleted in JemRec waits 30 days in Recently deleted, then is removed: in "
     "the standard folder, Android&rsquo;s own trash does that; in a folder you "
     "chose, it waits as a hidden file that JemRec removes. <em>Delete all for "
     "good</em> removes them all at once, when you ask. Messages the answering "
     "machine took are recordings too; which ones are messages, and which you "
     "marked to keep, is kept by file name in the app&rsquo;s private storage, "
     "and written inside each recording as well, with which way the call went "
     "where JemRec knows it, so that a reinstall still knows."),
    ("Caller colours", "the app&rsquo;s private storage", "until you uninstall", "once you use Multitrack",
     "Only once you use the Multitrack look: which of its six colours each "
     "caller has, so a caller keeps theirs, also after you change the look. Kept "
     "by a one-way code worked out from the name, not the name itself, in the "
     "app&rsquo;s private storage, and in no backup. Uninstalling deletes it."),
    ("Your greeting", "the app&rsquo;s private storage, and the recorder", "until you delete it", "only if you record one",
     "Only if you record one for the answering machine: in the app&rsquo;s "
     "private storage, and a copy the recorder plays from. Deleting it in the "
     "app deletes both, and so does uninstalling."),
    ("Background stops", "the app&rsquo;s private storage", "until you uninstall", "only on phones that stop apps",
     "Only on phones that stop apps in the background: whether setup has asked "
     "you to let JemRec keep running, and the time of the last warning that the "
     "phone stopped it, so one stop is told once. To notice a stop, JemRec reads "
     "Android&rsquo;s own records of when and why JemRec&rsquo;s process ended "
     "and how it started, and whether Android has marked JemRec stopped, which "
     "hold no names and no numbers. In the app&rsquo;s private storage; "
     "uninstalling deletes it."),
    ("The last call&rsquo;s report", "the recorder", "until the next call", "every call",
     "How the recorder recorded the last call: which way, how loud each side "
     "came out, and which apps were playing or capturing sound then. No numbers, "
     "no names, no sound. Kept by the recorder until the next call replaces it, "
     "and deleted with it when you uninstall. <em>Copy a report</em> in "
     "Diagnostics puts it on the clipboard, only when you tap it."),
    ("Answering machine tests", "the app&rsquo;s private storage", "the latest of each kind", "only if you run them",
     "What its checks found about the phone, and the result of each test call by "
     "call type (2G, VoLTE, Wi-Fi Calling). No numbers, no names. In the "
     "app&rsquo;s private storage; uninstalling deletes it. <em>Copy a "
     "report</em> puts them on the clipboard, only when you tap it."),
    ("Contact names and photos", "read where they are", "not copied, unless you choose", "only if you allow it",
     "Read from the phone&rsquo;s own call log and contacts, only if you allow "
     "it, to label a recording, to show the Calls tab, to keep or leave out a "
     "call by who it was with, and to notice a call that was not recorded. Names "
     "and numbers are not copied or stored; the lookup happens each time it is "
     "needed. Two choices change that, and only when you make them: a file name "
     "with the caller or their number in it puts their name or number in the "
     "recording&rsquo;s file name, and so in the app&rsquo;s private storage, "
     "where JemRec keeps what it knows of a recording by its file name (which "
     "ones are marked to keep, say); and the contacts "
     "you pick under Whose calls to keep are stored there as your contacts "
     "app&rsquo;s own links to them. Exact call tracking, below, keeps numbers "
     "too."),
    ("Call tracking", "the app&rsquo;s private storage", "the last 40 phone calls", "while Exact call tracking is on",
     "While Exact call tracking is on, which it is unless you turn it off: for "
     "the last 40 phone calls, when each began, was answered and ended, which "
     "way it went, and how far apart the phone&rsquo;s two clocks were during it "
     "&mdash; and its number, only if you allowed JemRec to read the call log, "
     "which has it anyway. In the app&rsquo;s private storage, to cut each "
     "recording to its call and name it. Turning tracking off deletes it."),
    ("Messenger callers", "the app&rsquo;s private storage, and the recording", "the last 30 calls", "only if Show who called is on",
     "Only if you turn on Show who called for messenger calls: from the call "
     "notifications of the apps whose calls you record &mdash; the messengers "
     "and Google Meet &mdash; the caller&rsquo;s name, the time, and whether the "
     "call rang first, for the last 30 calls, in the app&rsquo;s private "
     "storage. A recording named by date alone, or with the phone number "
     "&mdash; a messenger call has none &mdash; keeps its caller&rsquo;s name "
     "there too, by file name; one named with the caller has it in the file "
     "name. Either way the name is also written inside the recording, where no "
     "music app shows it, so that a reinstall still knows who it was &mdash; and "
     "a recording you share carries it."),
    ("Call times", "the app&rsquo;s private storage", "mostly 7 days", "every call",
     "When each recorded call started, which way it went and how long it lasted, "
     "the time of any call the app told you was not recorded, of any call your "
     "choice of whose calls to keep left out, and of any call whose caller hung "
     "up during your greeting, and of the answering machine&rsquo;s test calls. "
     "No names, no numbers. Kept in the app&rsquo;s private storage for seven "
     "days, so the missed-call check never reports a call twice or takes a "
     "recording you deleted for a call that was missed. Also when you last "
     "called an emergency number &mdash; noted with the answering machine on or "
     "off &mdash; and when you last chose to answer again before a day had "
     "passed, so the answering machine stands down for a day after such a call: "
     "the latest time of each, no number, until the next. Also when recording "
     "was switched on or off or changed mode, so the Calls tab can say why a "
     "call was not recorded: times and the setting only, the last 300 changes."),
    ("Notifications", "read on the phone", "only the IDs of ones it hid", "only if you ask for it",
     "Only if you ask JemRec to hide Android&rsquo;s debugging notification, or "
     "turn on Show who called for messenger calls. It then has notification "
     "access. For the first it looks only at notifications from Android itself, "
     "to find that one, and keeps the IDs of the ones it hid, so it can bring "
     "them back. For the second it looks only at the call notifications of the "
     "messengers you switched on (see Messenger callers). Nothing else is read "
     "or kept."),
    ("Waveforms", "the app&rsquo;s cache", "until the recording is deleted", "every recording",
     "For each recording, its loudness: 800 numbers, no sound, worked out from "
     "the file to draw its waveform - when a call is saved, or while the app is "
     "open. In the app&rsquo;s cache, under a code made from the file&rsquo;s "
     "name rather than the name itself, until the recording is deleted; Android "
     "may clear it sooner."),
    ("Settings", "the app&rsquo;s private storage", "until you uninstall", "always",
     "The app&rsquo;s own private storage: whether recording is on, whether to "
     "ask before each call, whether to show each call when it ends, where to "
     "save, how to name files, whose calls to keep, which messengers&rsquo; "
     "calls to record, the auto-delete rules, the answering machine&rsquo;s, "
     "what turning JemRec off switches off besides, whether Start without "
     "Wi-Fi is on, which recordings you marked to keep (by file name), and "
     "random tokens the app uses to talk to its own recorder and to its own "
     "notifications. And a few notes for the phone&rsquo;s current boot and "
     "Wi-Fi: whether JemRec opened debugging port 5555, or could not open or "
     "close it, and whether Android refused Wireless debugging on the Wi-Fi "
     "the phone is on - kept as the number Android gives that connection, "
     "never the network&rsquo;s name."),
]


def privacy_table() -> str:
    rows = "".join(
        f'\n    <details class="datum"><summary><span class="what">{what}</span>'
        f'<span class="chip where"><b>Where</b> {where}</span>'
        f'<span class="chip long"><b>Kept</b> {kept}</span>'
        f'<span class="chip when{" if" if when.startswith(("only", "once", "while")) else ""}">'
        f'<b>When</b> {when}</span></summary><p>{words}</p></details>'
        for what, where, kept, when, words in PRIVACY_DATA)
    return ('<div class="datamap">\n    <div class="datamap-head" aria-hidden="true"><span>Data</span>'
            '<span>Where it lives</span><span>How long</span><span>When</span></div>'
            + rows + "\n  </div>")


PRIVACY_BODY = f"""
  <h1>Privacy policy</h1>
  <p class="lead">JemRec has no servers, no accounts, and no analytics. Nothing it
     records or reads ever leaves your phone.</p>
  <p class="muted">Last updated: November 2026.</p>

  <h2>What it collects</h2>
  <p><strong>Nothing is sent anywhere.</strong> There is no backend to send it to.
     The developer cannot see your recordings, your contacts, your call history,
     or that you use the app at all.</p>

  <h2>What stays on the phone</h2>
  <p class="muted">Each row in short, then in full: open a row for the
     policy&rsquo;s own words, which are what counts.</p>
  {privacy_table()}

  <h2>While a recording plays</h2>
  <p>The phone&rsquo;s media controls show who the call was with, as they show
     a song&rsquo;s title: in the notifications and Quick Settings, and to a
     Bluetooth device or a car the phone is connected to. On the lock screen they
     say only <em>Call recording</em>. Apps you have allowed to read your
     notifications can see it, as they can any notification; other apps are
     turned away. Nothing of it is kept: it goes when the recording stops.</p>

  <h2>Network use</h2>
  <p>The app holds the <code>INTERNET</code> permission and uses no network. Android
     requires that permission for <em>any</em> TCP socket, including one to
     <code>127.0.0.1</code>, and the app&rsquo;s two sockets are both to the phone
     itself: pairing with the phone&rsquo;s own Wireless debugging during setup, and
     talking to its recorder afterwards. Neither reaches your router, let alone the
     internet.</p>
  <p><em>Start without Wi-Fi</em>, off until you switch it on, has the
     phone&rsquo;s own debugging service listen on port 5555 as well, so that the
     app can reach it at <code>127.0.0.1</code> with no Wi-Fi. The app still
     connects only to the phone itself, but whenever USB debugging is on
     &mdash; while JemRec is on, and while it is off unless <em>Turn all debugging
     off</em> goes with it &mdash; that port is open to every network the phone
     joins, and the phone&rsquo;s debugging service announces it there by the
     phone&rsquo;s serial number; a computer that reaches it, or another app on
     the phone, can ask to debug the phone, and only your yes on the phone lets
     it.</p>
  <p>The answering machine&rsquo;s <em>Carrier voicemail</em> asks your carrier,
     when you tap it, when its voicemail answers, and on a second tap changes
     that. It is a request over the phone network, the same as dialling a
     carrier code, and holds nothing but the timer. A test call to a number you
     type is an ordinary phone call.</p>

  <h2>This website</h2>
  <p>Static files served by GitHub Pages, with no analytics, no cookies, and no
     fonts or scripts fetched from anywhere else. The only things it stores
     are whether you chose light or dark and which look, in your own browser,
     where they stay. The
     two videos, setup and features, are the one exception, and only when you
     ask for one: the pages show a picture of each, and nothing from YouTube
     loads until you press play. Pressing play loads YouTube&rsquo;s player
     from youtube-nocookie.com, and from then on YouTube&rsquo;s own privacy
     policy applies to it. The Support
     link leads to Donatello, a separate service with its own privacy policy;
     nothing about a donation reaches the app.</p>

  <h2>The microphone</h2>
  <p>The app holds no microphone permission and never records. Calls are
     captured by a separate shell-privileged process, which the app starts during
     setup and which writes to a file the app collects afterwards. So is your
     answering-machine greeting, only while you record it; the app plays it back
     to you when you ask.</p>
  <p>While the answering machine takes a message, the recorder mutes your
     microphone, so the caller hears nothing of the room you are in. It gives
     it back when the call ends or when you take the call.</p>

  <h2>Deleting your data</h2>
  <p>Delete individual recordings in the app, or the whole folder from any file
     manager. <em>Start fresh</em> in Settings undoes the setup and hands the
     permissions back, and deliberately keeps your recordings. Uninstalling the app
     removes the app and its settings; the recordings stay, because they are yours
     and are not the app&rsquo;s to take.</p>

  <h2>Children</h2>
  <p>The app is not directed at children and collects nothing from anyone.</p>

  <h2>Changes</h2>
  <p>If this policy ever changes it changes
     <a href="{REPO}/blob/main/tools/build_site.py">in the open</a>, in the same
     repository as the code, with the change visible in the history.</p>

  <h2>Contact</h2>
  <p>Questions and ideas: <a href="{REPO}/discussions">GitHub Discussions</a>.
     Bug reports: <a href="{REPO}/issues">GitHub issues</a>. A security
     problem: <a href="{REPO}/security/advisories/new">report it privately</a>.</p>
"""


if __name__ == "__main__":
    DOCS.mkdir(parents=True, exist_ok=True)
    # Pages runs Jekyll by default, which ignores files beginning with an
    # underscore and would rather render Markdown than serve it. Nothing here
    # needs Jekyll.
    (DOCS / ".nojekyll").write_text("")
    (DOCS / "jemrec.css").write_text(CSS.replace("__PALETTES__", palettes_css()))
    # The looks' fonts and their licences; anything else there is a font that
    # has left FONTS, and would otherwise be served for good.
    fonts = DOCS / "fonts"
    fonts.mkdir(exist_ok=True)
    keep = {name for _, file, licence, *_ in FONTS for name in (file, licence)}
    for old in fonts.iterdir():
        if old.name not in keep:
            old.unlink()
    for _, file, licence, *_ in FONTS:
        shutil.copyfile(ROOT / "app/src/main/res/font" / file, fonts / file)
        shutil.copyfile(ROOT / "app/src/main/assets/licenses" / licence, fonts / licence)
    (DOCS / "jemrec.js").write_text(JS)
    (DOCS / "index.html").write_text(
        page(f"JemRec · {TAGLINE}", TAGLINE, INDEX_BODY, "index.html", "home")
    )
    (DOCS / "how-it-works.html").write_text(
        page(
            "JemRec · How it works",
            "How JemRec records both sides of a call without root: the phone is "
            "its own ADB host, and a recorder running as the shell does the rest.",
            HOW_BODY,
            "how-it-works.html",
        )
    )
    (DOCS / "setup.html").write_text(
        page(
            f"JemRec · {SETUP_TITLE}",
            "How to set up JemRec: three minutes, on the phone, no computer.",
            f'  <h1>{SETUP_TITLE}</h1>\n  <p class="lead">{SETUP_LEAD}</p>\n  '
            + toc_html(SETUP)
            + '\n  <div class="manual-text">\n' + render_html(SETUP) + "\n  </div>",
            "setup.html",
            "manual",
        )
    )
    # The same manual for whoever is reading the repository rather than the site.
    (DOCS / "SETUP.md").write_text(
        f"# {SETUP_TITLE}\n\n"
        + textwrap.fill(SETUP_LEAD, 79) + "\n\n"
        + "<!-- Generated by tools/build_site.py - edit the SETUP blocks there. -->\n\n"
        + render_markdown(SETUP)
    )
    (DOCS / "privacy-policy.html").write_text(
        page(
            "JemRec · Privacy policy",
            "JemRec has no servers and no analytics: nothing leaves your phone.",
            PRIVACY_BODY,
            "privacy-policy.html",
        )
    )
    for name in (".nojekyll", "jemrec.css", "jemrec.js", "index.html",
                 "how-it-works.html", "setup.html", "privacy-policy.html", "SETUP.md",
                 *(f"fonts/{name}" for name in sorted(keep))):
        print(f"wrote docs/{name}")
