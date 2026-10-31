import json,re,sys,glob,os
en=json.load(open(sys.argv[1]))
for f in sorted(glob.glob(sys.argv[2]+'/*.json')):
    c=os.path.basename(f)[:-5]
    try: t=json.load(open(f))
    except Exception as e: print(c,'INVALID',e); continue
    if c=='en_us': print(c,len(t),'entries'); continue
    miss=[k for k in en if k not in t]; extra=[k for k in t if k not in en]; bad=[]
    for k,v in t.items():
        n=k.count('{}'); idx=sorted(re.findall(r'\{(\d)\}',v)); plain=v.count('{}')
        ok=(plain==n and not idx) or (not plain and idx==[str(i) for i in range(1,n+1)]) or (n==0 and plain==0 and not idx)
        if not ok or k.count('§')!=v.count('§') or k.count('\n')!=v.count('\n'): bad.append(k)
    same=sum(1 for k,v in t.items() if v==k and re.search('[a-z]{4} [a-z]{4}',k))
    print(f'{c}: {len(t)} keys, missing {len(miss)}, extra {len(extra)}, placeholder/format errors {len(bad)}, left in English {same}', bad[:3])
