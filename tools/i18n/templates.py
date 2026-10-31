"""Templates of string concatenations: literal pieces kept, other operands -> {}. Adds to en.json."""
import re, sys, json, pathlib
sys.path.insert(0, sys.argv[0].rsplit('/',1)[0])
ROOT = pathlib.Path(sys.argv[1]); files = [l.strip() for l in open(sys.argv[2]) if l.strip()]
keys = json.load(open(sys.argv[3]))
TOK = re.compile(r'"(?:[^"\\\n]|\\.)*"|\'(?:[^\'\\]|\\.)*\'|//[^\n]*|/\*.*?\*/|[A-Za-z_$][\w$.]*|\d[\w.]*|->|&&|\|\||==|!=|\+\+|\+=|[^\s\w]', re.S)
def unq(s):
    try: return json.loads(s.replace("\\'", "'"), strict=False)
    except Exception: return s[1:-1]
STOP = {',', ';', '?', ':', '=', '->', '&&', '||', '==', '!=', '{', '}', 'return', '+=', '<', '>', '!'}
new = 0
def human(t):
    t = t.replace('{}', '').strip(' #;:,.()|-·')
    return bool(re.search(r'[A-Za-z]{2}', t))
def flush(chain):
    global new
    ops = [o for o in chain if o]
    if len(ops) < 2 or not any(o[0] == 'L' for o in ops): return  # (literals only: javac joins them into one string)
    t = ''
    for kind, val in ops:
        if kind == 'E':
            if not t.endswith('{}'): t += '{}'
        else: t += val
    if t.startswith('#'): t = t[1:]
    for part in (t.split('|', 1) if '|' in t else [t]):
        p = part.strip()
        if human(p) and p not in keys and p.replace('{}','').strip():
            keys[p] = 'tmpl'; new += 1
for f in files:
    toks = [t for t in TOK.findall((ROOT / f).read_text()) if not t.startswith('//') and not t.startswith('/*')]
    stack = [[[], None]]  # per depth: chain of operands, current operand
    def cur(): return stack[-1]
    for t in toks:
        lvl = cur()
        if t in '([':
            if lvl[1] is None: lvl[1] = ('E', None)
            elif lvl[1][0] == 'L': lvl[1] = ('E', None)
            stack.append([[], None]); continue
        if t in ')]':
            lvl[0].append(lvl[1]); flush(lvl[0])
            if len(stack) > 1: stack.pop()
            continue
        if t == '+':
            lvl[0].append(lvl[1]); lvl[1] = None; continue
        if t in STOP:
            lvl[0].append(lvl[1]); flush(lvl[0]); lvl[0] = []; lvl[1] = None; continue
        if t.startswith('"'):
            lvl[1] = ('L', unq(t)) if lvl[1] is None else ('E', None)
        else:
            lvl[1] = ('E', None)
json.dump(keys, open(sys.argv[3], 'w'), indent=1, ensure_ascii=False)
print(new, 'templates added;', len(keys), 'keys total')
