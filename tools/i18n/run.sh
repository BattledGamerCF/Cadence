#!/bin/bash
# Translations: lists Cadence's English UI text (exact strings and templates of text put together from parts) in
# build/i18n/en.json, then checks every language file in metal/src/main/resources/cadence/lang against it: keys missing
# or left over, and {} placeholders, § codes and line breaks that don't match. New or changed English text shows up
# as missing keys; add their translations to each file (the English is the key).
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$ROOT" || exit 1
mkdir -p build/i18n
grep -rlE 'Lang\.literal\(|Tips\.of\(|Tips\.text\(' metal/src/main/java shaders/src/main/java > build/i18n/files.txt
python3 tools/i18n/extract.py . build/i18n/files.txt build/i18n/en.json >/dev/null
python3 tools/i18n/templates.py . build/i18n/files.txt build/i18n/en.json >/dev/null
python3 - build/i18n/en.json <<'PY'
import json, re, sys
k = json.load(open(sys.argv[1]))
for x in [x for x in k if re.search(r'%[.0-9]*[sdf%]', x) or x.startswith('[') or re.fullmatch(r'[\w.{}]*\{\}[\w.{}]*', x) or x.startswith('-D') or '=...' in x]: del k[x]
for w in ('on', 'off', 's', 'resolution', 'cooling', 'background fps'): k.setdefault(w, 'value inside a sentence (on/off; the plural s)')
json.dump(k, open(sys.argv[1], 'w'), indent=1, ensure_ascii=False)
PY
python3 tools/i18n/normalize.py build/i18n/en.json
python3 tools/i18n/check.py build/i18n/en.json metal/src/main/resources/cadence/lang
