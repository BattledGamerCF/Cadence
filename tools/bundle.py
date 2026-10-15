#!/usr/bin/env python3
"""Build the single installable jar: the cadence-metal jar (mod id cadence) (natives inside) with the fps counter and the shader-pack
loader nested through Fabric's jar-in-jar.

  tools/bundle.py --metal METAL.jar --fps FPS.jar [--shaders SHADERS.jar] --out OUT.jar

The outer jar stays mod id cadence (java -jar still explains the install). It gains META-INF/jars/<fps jar>, a
"jars" list in fabric.mod.json, and META-INF/licenses/cadence-hud/ with the nested jar's own licence files. Sodium is
never bundled: it's a separate download.
"""
import argparse, io, json, os, re, zipfile

LICENCE_NAME = re.compile(r'^(META-INF/)?(LICEN[CS]E|COPYING|NOTICE)[^/]*$', re.I)


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--metal', required=True)
    p.add_argument('--fps', required=True)
    p.add_argument('--shaders')
    p.add_argument('--out', required=True)
    a = p.parse_args()

    nested = [('cadence-hud', os.path.basename(a.fps), open(a.fps, 'rb').read())]  # (slug, file name, bytes)
    if a.shaders:
        nested.append(('cadence-shaders', os.path.basename(a.shaders), open(a.shaders, 'rb').read()))
    licences = {}  # archive path -> bytes
    listing = []
    for slug, name, data in nested:
        with zipfile.ZipFile(io.BytesIO(data)) as z:
            meta = json.loads(z.read('fabric.mod.json').decode('utf-8'), strict=False)
            for n in z.namelist():
                if LICENCE_NAME.match(n):
                    licences[f'META-INF/licenses/{slug}/{os.path.basename(n)}'] = z.read(n)
        listing.append(f"{meta['id']} {meta['version']} ({meta.get('license', 'no licence field')})")

    with zipfile.ZipFile(a.metal) as src:
        meta = json.loads(src.read('fabric.mod.json').decode('utf-8'))
        meta['jars'] = [{'file': f'META-INF/jars/{name}'} for _, name, _ in nested]
        os.makedirs(os.path.dirname(os.path.abspath(a.out)), exist_ok=True)
        with zipfile.ZipFile(a.out + '.part', 'w', zipfile.ZIP_DEFLATED) as out:
            for info in src.infolist():
                if info.filename == 'fabric.mod.json':
                    continue
                out.writestr(info, src.read(info.filename))
            out.writestr('fabric.mod.json', json.dumps(meta, indent='\t') + '\n')
            for _, name, data in nested:
                # stored: Fabric opens nested jars in memory, no need to inflate them twice
                out.writestr(zipfile.ZipInfo(f'META-INF/jars/{name}', (1980, 2, 1, 0, 0, 0)), data, compress_type=zipfile.ZIP_STORED)
            for path, data in sorted(licences.items()):
                out.writestr(zipfile.ZipInfo(path, (1980, 2, 1, 0, 0, 0)), data, compress_type=zipfile.ZIP_DEFLATED)
    os.replace(a.out + '.part', a.out)
    print(a.out)
    for l in listing:
        print('  nested:', l)


if __name__ == '__main__':
    main()
