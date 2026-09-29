"""Import the user's cabinet as data, preserving all vertices/UVs/PNG bytes.

Only derives render-part JSONs; originals and old assets are never rewritten.
"""
from __future__ import annotations
import argparse, base64, copy, hashlib, json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT/'design/user-models-20260911/source/01_双人街机'
ASSETS = ROOT/'src/main/resources/assets/piq_fc_arcade'
EXPECTED = {
    '街机_双人通用_加深机柜版.bbmodel':'EBED5C823E371F80B85E8A9210091369C20746F3C2CBDFDFA7CD6BA2050FDC58',
    'arcade_universal_deep.json':'A054E7920C9DC3E9D97F1B057C958FC6E55CF75EBD120E4B1E49A763BC325B73',
    '双人街机_加深机柜UV.png':'01B61C621DE7EC93353F34868C1F6FCC52659321A365E997206C7220AB93B011',
    '按键动画映射.json':'484ED53EA6E8CEF135A7B9E198AD3D412D1569C184404496F56F65643AFCC2E8',
}
TEXTURE = 'piq_fc_arcade:block/user_dual/skin'

def sha(data): return hashlib.sha256(data).hexdigest().upper()

def derive():
    raw = {name:(SOURCE/name).read_bytes() for name in EXPECTED}
    assert {name:sha(data) for name,data in raw.items()} == EXPECTED, 'Incoming source changed'
    bb = json.loads(raw['街机_双人通用_加深机柜版.bbmodel'])
    source = json.loads(raw['arcade_universal_deep.json'])
    mapping = json.loads(raw['按键动画映射.json'])
    png = raw['双人街机_加深机柜UV.png']
    assert base64.b64decode(bb['textures'][0]['source'].split(',',1)[1]) == png
    assert bb['resolution'] == {'width':2048,'height':2048}
    assert len(bb['elements']) == len(source['elements']) == 236
    assert len({e['uuid'] for e in bb['elements']}) == 236
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
    assert len(models['body'])==114
    result={}
    for name,elements in models.items():
        model={'credit':'User supplied 2026-09-11; exact original geometry/UV, runtime group split only.',
               'textures':{'0':TEXTURE,'particle':'#0'},'elements':elements}
        result[ASSETS/f'models/block/user_dual/{name}.json']=(json.dumps(model,ensure_ascii=False,separators=(',',':'))+'\n').encode('utf-8')
    result[ASSETS/'textures/block/user_dual/skin.png']=png
    return result, mapping

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--check',action='store_true');args=ap.parse_args()
    files,_=derive()
    for path,data in files.items():
        if path.exists(): assert path.read_bytes()==data, f'Refusing to overwrite differing asset: {path}'
        elif args.check: raise AssertionError(f'Missing asset: {path}')
        else:
            path.parent.mkdir(parents=True,exist_ok=True)
            with path.open('xb') as f:f.write(data)
    print(json.dumps({'ok':True,'model_parts':17,'elements':236,'animated_elements':122,
                      'texture_original_sha256':EXPECTED['双人街机_加深机柜UV.png'],
                      'source_modified':False,'old_assets_modified':False,
                      'files':{str(p.relative_to(ROOT)):sha(v) for p,v in files.items()}},ensure_ascii=False))

if __name__=='__main__':main()
