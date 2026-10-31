"""Collect Cadence's English UI strings: exact literals plus templates ({} for non-literal parts) of concatenations."""
import re, sys, json, pathlib
ROOT = pathlib.Path(sys.argv[1]); files = [l.strip() for l in open(sys.argv[2]) if l.strip()]
LIT = r'"(?:[^"\\]|\\.)*"'
SKIP_LINE = re.compile(r'\bhas\(p,|String\[\] around|saveKey\(|configValue\(|getProperty|setProperty|System\.(out|err)|LOG\.|\.equals\(|startsWith\(|endsWith\(|contains\(|Class\.forName|findStatic|findVirtual|@(Inject|Redirect|At|Mixin|Overwrite|ModifyArg)|replaceAll|split\(|indexOf\(|\.put\(|\.get\(|getString|Path\.of|resolve\(|keyMapping|KeyMapping|"key\.|"category|^\s*\*|^\s*//')
def unq(s):
    try: return json.loads(s.replace("\\'", "'"), strict=False)
    except Exception: return s[1:-1]
def human(s):
    t = s.strip(' #;:,.()|-·')
    return bool(re.search(r'[A-Za-z]{2}', t)) and not re.fullmatch(r'[a-z0-9_.\-/:]+', t) and not re.fullmatch(r'[%#.\w]*%[\w.]*', t)
keys = {}
def add(k, where):
    k = k.replace('\\"', '"')
    if k.startswith('#'): k = k[1:]
    parts = k.split('|', 1) if '|' in k and not k.startswith('{}') else [k]
    for p in parts:
        core = p.strip()
        if human(core.replace('{}', '')): keys.setdefault(core, where)
for f in files:
    src = (ROOT / f).read_text()
    for n, line in enumerate(src.splitlines(), 1):
        if SKIP_LINE.search(line): continue
        code = re.sub(r'//.*$', '', line) if '"' not in line.split('//')[0][-1:] else line
        for m in re.finditer(LIT, code):
            s = unq(m.group(0))
            add(s, f'{f}:{n}')
    # templates: string concatenations (literal + expr ...) within one statement
    for m in re.finditer(r'((?:' + LIT + r'|[^;{}"])*?\+\s*(?:' + LIT + r'|[^;{}"])*)', src):
        expr = m.group(1)
        if expr.count('"') < 2 or '+' not in expr: continue
        # split at top-level '+'
        depth = 0; buf = ''; pieces = []; i = 0
        while i < len(expr):
            c = expr[i]
            if c == '"':
                j = i + 1
                while expr[j] != '"': j += 2 if expr[j] == '\\' else 1
                buf += expr[i:j+1]; i = j + 1; continue
            if c in '([': depth += 1
            if c in ')]': depth -= 1
            if depth < 0: pieces = []; buf = ''; depth = 0; i += 1; continue
            if c == ',' and depth == 0: pieces.append(buf); pieces.append(None); buf=''; i+=1; continue
            if c == '+' and depth == 0 and expr[i+1:i+2] not in '+=' and expr[i-1:i] != '+': pieces.append(buf); buf = ''
            else: buf += c
            i += 1
        pieces.append(buf)
        seq = []
        for p in pieces:
            if p is None: seq.append(None); continue
            p = p.strip()
            if re.fullmatch(LIT, p): seq.append(unq(p))
            elif p: seq.append('{}')
        cur = []
        for x in seq + [None]:
            if x is None:
                lits = [y for y in cur if y != '{}']
                if lits and len(cur) > 1:
                    t = ''
                    for y in cur:
                        if y == '{}' and t.endswith('{}'): continue
                        t += y
                    if '{}' in t: add(t, f'{f}:tmpl')
                cur = []
            else: cur.append(x)
json.dump(keys, open(sys.argv[3], 'w'), indent=1, ensure_ascii=False)
print(len(keys), 'keys,', sum(len(k) for k in keys), 'chars,', sum('{}' in k for k in keys), 'templates')
