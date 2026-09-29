"""Freeze this release only. Preserve inherited FC artwork from the tested alpha17 archive.

Does not change source artwork, a prior delivery, or an installed Minecraft instance.
"""
from pathlib import Path
import argparse, hashlib, json, zipfile

ROOT=Path(__file__).resolve().parents[2]
DELIVERY=ROOT/'制作Mod'/'03-街机模拟'
BASE=DELIVERY/'PIQ-FC街机/alpha17-immersive/piq_fc_arcade-0.31.0-alpha.17.jar'
EXPECTED='BB80909B99B4F357F71C3CBC86E580CA7F80D172C5153D52B9F14F6F90494540'
TARGETS={
    'fc':('piq-fc-arcade','piq_fc_arcade-0.31.0-alpha.18.jar','PIQ-FC街机/alpha18-device-ui-v2'),
    'sfc':('piq-sfc-home','piq_sfc_home-0.1.0-alpha.5.jar','PIQ-SFC家用/0.1.0-alpha.5'),
    'native':('piq-native-arcade','piq_native_arcade-0.1.0-alpha.5.jar','PIQ原生街机/0.1.0-alpha.5'),
}
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest().upper()
def freeze(key):
    project,name,directory=TARGETS[key];source=ROOT/project/'build/libs'/name;out=DELIVERY/directory/name
    if out.exists():raise ValueError('Refusing to overwrite frozen artifact: '+str(out))
    if key=='fc' and sha(BASE)!=EXPECTED:raise ValueError('FC17 baseline hash changed')
    out.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(source) as built:
        names=built.namelist()
        if len(names)!=len(set(names)) or built.testzip() is not None:raise ValueError('Invalid source archive')
        if any('/test/' in n or n.startswith('private-qa/') for n in names):raise ValueError('Unexpected private/test artifact')
        with zipfile.ZipFile(BASE) as old:
            artwork={n:old.read(n) for n in old.namelist() if n.startswith('assets/') and not n.endswith('/')}
        if key=='fc' and set(artwork)!={n for n in names if n.startswith('assets/') and not n.endswith('/')}:
            raise ValueError('FC artwork inventory changed unexpectedly')
        with zipfile.ZipFile(out,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as final:
            for entry in built.infolist():
                data=artwork[entry.filename] if key=='fc' and entry.filename in artwork else built.read(entry)
                final.writestr(entry,data)
    with zipfile.ZipFile(out) as check:
        if check.testzip() is not None:raise ValueError('Frozen archive CRC failed')
        if key=='fc' and any(check.read(n)!=raw for n,raw in artwork.items()):raise ValueError('FC inherited artwork drift')
    result={'source':str(source),'source_sha256':sha(source),'path':str(out),'sha256':sha(out),'bytes':out.stat().st_size,
            'inherited_fc_assets_preserved':len(artwork) if key=='fc' else 0,'installed':False}
    with out.with_suffix('.freeze.json').open('x',encoding='utf-8') as f:json.dump(result,f,ensure_ascii=False,indent=2)
    return result
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('component',choices=TARGETS);a=p.parse_args()
    print(json.dumps(freeze(a.component),ensure_ascii=False))
