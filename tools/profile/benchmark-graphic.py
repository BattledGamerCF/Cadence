#!/usr/bin/env python3
"""Draws packaging/gallery/3-benchmark.png from measured numbers (no numbers are typed into the picture by hand).
usage: benchmark-graphic.py OUT.png OPENGL_FPS METAL_FPS BSL_FPS BSL_SCALE_PERCENT "SETUP LINE" "FOOTNOTE"
"""
import sys
from PIL import Image, ImageDraw, ImageFont

out, gl, metal, bsl, scale, setup, foot = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4]), sys.argv[5], sys.argv[6], sys.argv[7]
W, H = 1600, 900
SF = '/System/Library/Fonts/SFNS.ttf'


def font(size, weight='Regular'):
    f = ImageFont.truetype(SF, size)
    try:
        f.set_variation_by_name(weight)
    except Exception:
        pass
    return f


img = Image.new('RGB', (W, H))
d = ImageDraw.Draw(img)
for y in range(H):  # the original's dark navy to purple-grey gradient
    t = y / H
    d.line([(0, y), (W, y)], fill=(int(22 + 10 * t), int(20 + 8 * t), int(70 - 25 * t)))
white, soft, dim = (255, 255, 255), (200, 200, 235), (150, 150, 190)
d.text((80, 60), "Cadence vs. Minecraft's OpenGL renderer", font=font(54, 'Bold'), fill=white)
d.text((80, 132), setup, font=font(27), fill=soft)

# without shaders: two bars
d.text((80, 225), "Without shaders", font=font(34, 'Semibold'), fill=(235, 235, 255))
base, top = 760, 345
hmax = base - top
for i, (label, value, color) in enumerate([("OpenGL\n(standard)", gl, (110, 115, 150)), ("Cadence\n(Metal)", metal, (122, 170, 255))]):
    x = 80 + i * 240
    h = max(60, int(hmax * value / max(gl, metal)))
    d.rounded_rectangle([x, base - h, x + 170, base], radius=14, fill=color)
    num = f"{value:.0f}"
    w = d.textlength(num, font=font(46, 'Bold'))
    d.text((x + 85 - w / 2, base - h - 62), num, font=font(46, 'Bold'), fill=white)
    for j, line in enumerate(label.split('\n')):
        w = d.textlength(line, font=font(26))
        d.text((x + 85 - w / 2, base + 22 + j * 32), line, font=font(26), fill=soft)
ratio = metal / gl
d.text((600, 330), f"{ratio:.1f}×", font=font(84, 'Bold'), fill=(140, 210, 255))
d.text((600, 425), "faster", font=font(34), fill=(140, 210, 255))

# with BSL: a ring with the average
d.text((900, 225), "With BSL shaders", font=font(34, 'Semibold'), fill=(235, 235, 255))
cx, cy, r = 1160, 520, 170
d.ellipse([cx - r, cy - r, cx + r, cy + r], outline=(70, 72, 125), width=26)
frac = min(1.0, bsl / 120.0)  # ring: share of 120 fps
d.arc([cx - r, cy - r, cx + r, cy + r], start=-90, end=-90 + 360 * frac, fill=(190, 140, 255), width=26)
num = f"{bsl:.0f}"
w = d.textlength(num, font=font(110, 'Bold'))
d.text((cx - w / 2, cy - 85), num, font=font(110, 'Bold'), fill=white)
w = d.textlength("fps average", font=font(30))
d.text((cx - w / 2, cy + 45), "fps average", font=font(30), fill=soft)
for j, line in enumerate(["BSL 10.1.8, default settings", f"Auto resolution (settled at {scale}%, MetalFX upscaled)"]):
    w = d.textlength(line, font=font(26))
    d.text((cx - w / 2, 760 + j * 36), line, font=font(26), fill=soft)
d.text((80, 852), foot, font=font(22), fill=dim)
img.save(out)
print("wrote", out)
