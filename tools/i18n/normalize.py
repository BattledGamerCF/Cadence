"""Trims keys the way dev.cadence.metal.Lang trims text before looking it up (spaces and punctuation at the edges)."""
import json, sys
EDGE = " :;,.()·\n"
keys = json.load(open(sys.argv[1]))
out = {}
for k, v in keys.items():
    c = k.strip(EDGE)
    if c and (c.replace('{}', '').strip(EDGE) or k in ('s',)): out.setdefault(c, v)
json.dump(out, open(sys.argv[1], 'w'), indent=1, ensure_ascii=False)
print(len(out), 'keys after trimming')
