#!/usr/bin/env python3
"""Draw site/og.png, the 1200x630 card link previews show (og:image, twitter:image).

Rerun after changing the headline or screenshot.png, and commit the result:
    python3 site/og-image.py
Needs Pillow and fontTools (with brotli, for the woff2 fonts): dnf install
python3-pillow python3-fonttools python3-brotli.
"""
import io
import os

from fontTools.ttLib import TTFont
from fontTools.varLib import instancer
from PIL import Image, ImageDraw, ImageFont

SITE = os.path.dirname(os.path.abspath(__file__))
HEADLINE = "Give your AI coding agents their own machines —"
HIGHLIGHT = "not your credentials"
DOMAIN = "isx.run"

W, H, PAD = 1200, 630, 64
BG, FG, MUTED, ACCENT, BORDER = (11, 13, 16), (232, 234, 237), (161, 169, 179), (52, 211, 153), (40, 46, 54)


def font(name, weight, size):
    """The site's own variable font, pinned to one weight (Pillow reads only static fonts)."""
    tt = TTFont(os.path.join(SITE, "vendor", "fonts", name + ".woff2"))
    tt.flavor = None
    if "fvar" in tt:
        tt = instancer.instantiateVariableFont(tt, {"wght": weight})
    buf = io.BytesIO()
    tt.save(buf)
    buf.seek(0)
    return ImageFont.truetype(buf, size)


def main():
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    mono, display, body = (font("jetbrains-mono-v24-latin", 500, 34), font("space-grotesk-v22-latin", 700, 38),
                           font("inter-v20-latin", 400, 28))

    d.text((PAD, 48), ">_", font=mono, fill=ACCENT)
    d.text((PAD + d.textlength(">_", font=mono) + 12, 44), "isx", font=display, fill=FG)
    d.text((W - PAD - d.textlength(DOMAIN, font=body), 52), DOMAIN, font=body, fill=MUTED)

    headline = font("space-grotesk-v22-latin", 700, 52)
    words = [(w, FG) for w in HEADLINE.split()] + [(w, ACCENT) for w in HIGHLIGHT.split()]
    lines, line, width = [], [], 0
    for word, colour in words:
        w = d.textlength(word + " ", font=headline)
        if line and width + w > W - 2 * PAD:
            lines.append(line)
            line, width = [], 0
        line.append((word, colour))
        width += w
    lines.append(line)
    y = 122
    for line in lines:
        x = PAD
        for word, colour in line:
            d.text((x, y), word, font=headline, fill=colour)
            x += d.textlength(word + " ", font=headline)
        y += 64

    # The top-left of the TUI screenshot, running off the bottom edge.
    top, sw = y + 28, W - 2 * PAD
    sh = H - top + 20
    shot = Image.open(os.path.join(SITE, "screenshot.png")).convert("RGB")
    shot = shot.crop((0, 0, 2680, int(2680 * sh / sw))).resize((sw, sh), Image.LANCZOS)
    mask = Image.new("L", (sw, sh), 0)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, sw - 1, sh + 40), radius=14, fill=255)
    im.paste(shot, (PAD, top), mask)
    d.rounded_rectangle((PAD - 1, top - 1, PAD + sw, H + 40), radius=14, outline=BORDER, width=2)

    # 128 flat colours: a quarter of the size, no visible loss on flat terminal colours.
    im.quantize(colors=128, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE).save(
        os.path.join(SITE, "og.png"), optimize=True)


if __name__ == "__main__":
    main()
