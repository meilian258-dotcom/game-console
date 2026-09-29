"""Import the user's cabinet as data, preserving all vertices/UVs/PNG bytes.

New upper-body revision only. The alpha22 importer and incoming originals stay unchanged.
--apply replaces only the exactly known alpha22 derived body/skin; control parts must be identical.
"""
from __future__ import annotations
import argparse, base64, copy, hashlib, json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT/'design/refined-dual-zapper-20260911/source/02_双人街机_上半部重塑大屏版'
ASSETS = ROOT/'src/main/resources/assets/piq_fc_arcade'
EXPECTED = {
    '街机_双人_上半部重塑大屏版.bbmodel':'8EEB98567BB7A34598CCE7FE95E91FDA1EE263B45427F021BBDF908A55F0FF97',
    'arcade_refined_upper.json':'142E29CC81B10AAAFD73662E11999F94CBE5AA2E4596C43D39405EB460EFE296',
    '双人街机_重塑版UV.png':'3E6081A5F18CBFD9277B229718996E3D9583C8A072C0119E9ED8380484DE5265',
    '按键动画映射.json':'5D3B09FE979D8A9EB1D6CF1E751B873FC1C7AB7F64C99DD6935BC8D8468869AE',
}
TEXTURE = 'piq_fc_arcade:block/user_dual/skin'

def sha(data): return hashlib.sha256(data).hexdigest().upper()

def derive():
    raw = {name:(SOURCE/name).read_bytes() for name in EXPECTED}
    assert {name:sha(data) for name,data in raw.items()} == EXPECTED, 'Incoming source changed'
    bb = json.loads(raw['街机_双人_上半部重塑大屏版.bbmodel'])
    source = json.loads(raw['arcade_refined_upper.json'])
    mapping = json.loads(raw['按键动画映射.json'])
    png = raw['双人街机_重塑版UV.png']
    assert base64.b64decode(bb['textures'][0]['source'].split(',',1)[1]) == png
    assert bb['resolution'] == {'width':2048,'height':2048}
    assert len(bb['elements']) == len(source['elements']) == 239
    assert len({e['uuid'] for e in bb['elements']}) == 239
    # Export lists retain identical element order, including repeated display names.
    ids = {}
    for b,e in zip(bb['elements'],source['elements']):
        assert b['type']=='cube' and b['name']==e['name']
        assert b['from']==e['from'] and b['to']==e['to']
        for face,f in e['faces'].items():
            assert f['texture']=='#0'
            assert all(abs(a/128-c)<1e-8 for a,c in zip(b['faces'][face]['uv'],f['uv']))
        ids[b['uuid']] = e
    models = {}; moved=set()
    for group in mapping['groups']:
        if not group['movable']: continue
        name=group['groupName']; members=group['elementUuids']
        assert name in [f'p{p}_{part}' for p in (1,2) for part in
                        [*(f'button_{i}' for i in range(1,7)),'start','joystick']]
        assert not moved.intersection(members) and all(i in ids for i in members)
        moved.update(members)
        models[name] = [copy.deepcopy(ids[i]) for i in members]
    assert len(models)==16 and len(moved)==122
    models['body'] = [copy.deepcopy(e) for b,e in zip(bb['elements'],source['elements']) if b['uuid'] not in moved]
    assert len(models['body'])==117
    result={}
    for name,elements in models.items():
        model={'credit':'User supplied 2026-09-11; exact original geometry/UV, runtime group split only.',
               'textures':{'0':TEXTURE,'particle':'#0'},'elements':elements}
        result[ASSETS/f'models/block/user_dual/{name}.json']=(json.dumps(model,ensure_ascii=False,separators=(',',':'))+'\n').encode('utf-8')
    result[ASSETS/'textures/block/user_dual/skin.png']=png
    return result, mapping

def main():
    ap=argparse.ArgumentParser()
    mode=ap.add_mutually_exclusive_group()
    mode.add_argument('--check',action='store_true')
    mode.add_argument('--apply',action='store_true')
    args=ap.parse_args()
    files,mapping=derive()
    import import_user_dual_model as baseline
    old,old_mapping=baseline.derive()
    assert mapping['groups']==old_mapping['groups'], 'Control mapping changed'
    changed=[p for p in files if files[p]!=old[p]]
    assert set(changed)=={ASSETS/'models/block/user_dual/body.json',ASSETS/'textures/block/user_dual/skin.png'}
    # Validate all outputs first; do not overwrite any unknown local edit.
    for p,data in files.items():
        assert p.is_file() and p.read_bytes() in (old[p],data), f'Unknown asset: {p}'
        if args.check: assert p.read_bytes()==data, f'Not imported: {p}'
    if args.apply:
        for p in changed:
            if p.read_bytes()!=files[p]: p.write_bytes(files[p])
    if args.apply or args.check:
        assert all(p.read_bytes()==data for p,data in files.items())
    print(json.dumps({'ok':True,'mode':'apply' if args.apply else 'check' if args.check else 'dry-run',
        'model_parts':17,'elements':239,'body_elements':117,'animated_elements':122,
        'source_sha256':EXPECTED,'source_modified':False,'legacy_importer_modified':False,
        'changed_assets':[str(p.relative_to(ASSETS)) for p in changed],
        'files':{str(p.relative_to(ROOT)):sha(v) for p,v in files.items()}},ensure_ascii=False))

if __name__=='__main__':main()
