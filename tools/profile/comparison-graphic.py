#!/usr/bin/env python3
"""Draws the Cadence vs. vanilla comparison card (packaging/gallery/4-comparison.png).
usage: comparison-graphic.py BACKGROUND.png ICON.png OUT.png
Numbers: docs/dev-log/DAY-10-18.md section 7 (alternated launches, M2 Air) and SHADER-PACKS.md; edit ROWS when they change."""
import sys
from PIL import Image, ImageDraw, ImageFont, ImageFilter, ImageEnhance

bg_path, icon_path, out = sys.argv[1], sys.argv[2], sys.argv[3]
W, H = 1920, 1080
SF = '/System/Library/Fonts/SFNS.ttf'


def font(size, weight='Regular'):
    f = ImageFont.truetype(SF, size)
    try:
        f.set_variation_by_name(weight)
    except Exception:
        pass
    return f


# (what it is, technical line, vanilla, cadence, gain)
ROWS = [
    ("Frame rate", "average fps, standing", "76 fps", "520 fps", "6.9x"),
    ("Frame rate while turning", "average fps, camera spinning", "86 fps", "545 fps", "6.3x"),
    ("Stutter", "slowest 1% of frames", "16-47 ms", "3 ms", "smooth"),
    ("Worst hitch", "longest single frame", "up to 177 ms", "21 ms", "8x shorter"),
    ("CPU work", "game's CPU use, capped at 60 fps", "45%", "36%", "-20%"),
    ("Memory", "RAM the game uses (macOS footprint)", "1,860 MB", "1,676 MB", "-185 MB"),
    ("Shader packs", "OptiFine / Iris packs, run on Metal", "Not supported", "23 of 25 tested", "built in"),
    ("Graphics engine", "the API that draws the game", "OpenGL 4.1 (deprecated)", "Metal (Apple's own)", "native"),
]

img = Image.open(bg_path).convert('RGB')
scale = max(W / img.width, H / img.height)
img = img.resize((round(img.width * scale), round(img.height * scale)), Image.LANCZOS)
img = img.crop(((img.width - W) // 2, (img.height - H) // 2, (img.width - W) // 2 + W, (img.height - H) // 2 + H))
img = ImageEnhance.Brightness(img).enhance(0.85)
over = Image.new('RGBA', (W, H), (0, 0, 0, 0))
od = ImageDraw.Draw(over)
for y in range(H):  # darker at the top (title) and bottom (footer)
    a = int(150 * max(0, 1 - y / 260)) + int(120 * max(0, (y - 900) / 180))
    od.line([(0, y), (W, y)], fill=(8, 10, 22, min(a, 200)))
img = Image.alpha_composite(img.convert('RGBA'), over)

# the table's glass panel: the background blurred and darkened behind it
px, py, pw = 150, 205, W - 300
rh, hh = 76, 58
ph = hh + rh * len(ROWS) + 16
panel = img.crop((px, py, px + pw, py + ph)).filter(ImageFilter.GaussianBlur(22))
panel = Image.alpha_composite(panel, Image.new('RGBA', panel.size, (10, 14, 30, 178)))
mask = Image.new('L', panel.size, 0)
ImageDraw.Draw(mask).rounded_rectangle([0, 0, pw - 1, ph - 1], radius=28, fill=255)
img.paste(panel, (px, py), mask)
d = ImageDraw.Draw(img)
d.rounded_rectangle([px, py, px + pw, py + ph], radius=28, outline=(255, 255, 255, 60), width=2)

white, soft, dim, blue, grey = (255, 255, 255), (214, 220, 240), (150, 158, 185), (110, 185, 255), (170, 172, 185)
# header
icon = Image.open(icon_path).convert('RGBA').resize((96, 96), Image.LANCZOS)
im = Image.new('L', icon.size, 0)
ImageDraw.Draw(im).rounded_rectangle([0, 0, 95, 95], radius=22, fill=255)
img.paste(icon, (64, 52), im)
d.text((186, 46), "Cadence vs. vanilla Minecraft", font=font(60, 'Bold'), fill=white)
d.text((188, 120), "Same Mac, same world, same view, same settings. Real measurements.", font=font(30), fill=soft)

# columns
c_what, c_van, c_cad, c_gain = px + 40, px + 690, px + 1040, px + 1420
y = py + 16
for x, t, col in ((c_what, "", dim), (c_van, "Vanilla (OpenGL)", grey), (c_cad, "Cadence (Metal)", blue), (c_gain, "Difference", dim)):
    d.text((x, y + 6), t, font=font(26, 'Semibold'), fill=col)
y += hh
for i, (what, tech, van, cad, gain) in enumerate(ROWS):
    d.line([(px + 30, y), (px + pw - 30, y)], fill=(255, 255, 255, 40) if i else (255, 255, 255, 70), width=1)
    d.text((c_what, y + 10), what, font=font(30, 'Semibold'), fill=white)
    d.text((c_what, y + 46), tech, font=font(20), fill=dim)
    d.text((c_van, y + 20), van, font=font(30), fill=grey)
    d.text((c_cad, y + 20), cad, font=font(30, 'Bold'), fill=blue)
    gf = font(26, 'Bold')
    gw = d.textlength(gain, font=gf)
    d.rounded_rectangle([c_gain, y + 16, c_gain + gw + 36, y + 60], radius=22, fill=(245, 132, 50))
    d.text((c_gain + 18, y + 21), gain, font=gf, fill=white)
    y += rh

foot = ("MacBook Air M2 (8 GB), 2940x1686, render distance 12, no shader pack for the numbers; launches alternated, "
        "OpenGL = Minecraft's own renderer. Background: BSL 10.1.8 running on Cadence.")
f = font(21)
d.text(((W - d.textlength(foot, font=f)) / 2, H - 52), foot, font=f, fill=soft)
img.convert('RGB').save(out)
print(out)
