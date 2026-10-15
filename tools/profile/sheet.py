#!/usr/bin/env python3
"""Tiles a test run's screenshots in step order: sheet.py RESULTS_DIR OUT.jpg [first-step=0] [columns=2] [steps=a,b,c]"""
import sys, glob, re, os
from PIL import Image
d, out = sys.argv[1], sys.argv[2]
skip = int(sys.argv[3]) if len(sys.argv) > 3 else 0
cols = int(sys.argv[4]) if len(sys.argv) > 4 else 2
only = set(int(x) for x in sys.argv[5].split(',')) if len(sys.argv) > 5 else None
fs = sorted(glob.glob(os.path.join(d, 'selftest-*.jpg')) + glob.glob(os.path.join(d, 'selftest-*.png')), key=lambda f: int(re.search(r'selftest-(\d+)-', f).group(1)))
fs = [f for f in fs if int(re.search(r'selftest-(\d+)-', f).group(1)) >= skip and (only is None or int(re.search(r'selftest-(\d+)-', f).group(1)) in only)]
w = 1280 // cols; h = w * 9 // 16
ims = [Image.open(f).convert('RGB').resize((w, h)) for f in fs]
rows = (len(ims) + cols - 1) // cols
m = Image.new('RGB', (cols * (w + 4), max(rows, 1) * (h + 4)))
for i, im in enumerate(ims): m.paste(im, ((i % cols) * (w + 4), (i // cols) * (h + 4)))
m.save(out, quality=82); print([os.path.basename(f) for f in fs])
