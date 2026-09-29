"""Import user BBMODEL cubes with exact UVs; keep originals and legacy assets untouched."""
from pathlib import Path
import json, copy, base64, hashlib, argparse, sys
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
INCOMING = ROOT / 'outputs/tvcoin48/incoming'
ASSETS = ROOT / 'piq-fc-arcade/src/main/resources/assets/piq_fc_arcade'
sys.path.insert(0, str(Path(__file__).resolve().parent))
from render_rocket_arcade_preview import vertices_for

def source(prefix, needle=None):
    found = [p for p in INCOMING.rglob('*.bbmodel') if prefix in str(p) and (not needle or needle in p.name)]
    assert len(found) == 1, found
    return json.loads(found[0].read_text(encoding='utf-8-sig'))

def convert(doc, texture, shift=0, scale=1, center=None):
    assert all(not any(g.get('rotation', [0,0,0])) for g in doc.get('groups', []))
    elements = []
    dims = doc['resolution']
    for source_el in doc['elements']:
        assert source_el['type'] == 'cube' and source_el.get('export', True)
        e = {'name': source_el['name']}
        for key in ('from', 'to'):
            e[key] = [round((v-(center[i] if center else 0))*scale+(8 if center else 0)-(shift if i==0 else 0),8)
                      for i,v in enumerate(source_el[key])]
        rot = source_el.get('rotation') or [0,0,0]
        axes = [i for i,v in enumerate(rot) if v]
        assert len(axes) <= 1
        if axes:
            axis = axes[0]
            assert rot[axis] in (-45,-22.5,22.5,45)
            e['rotation'] = {'axis':'xyz'[axis], 'angle':rot[axis], 'rescale':source_el.get('rescale',False),
                'origin':[(v-(center[i] if center else 0))*scale+(8 if center else 0)-(shift if i==0 else 0)
                          for i,v in enumerate(source_el['origin'])]}
        e['faces'] = {}
        for face, f in source_el['faces'].items():
            if f.get('texture') is None: continue
            assert f['texture'] == 0
            converted = {'uv':[v*16/dims['width' if i%2==0 else 'height'] for i,v in enumerate(f['uv'])], 'texture':'#0'}
            if f.get('rotation'): converted['rotation'] = f['rotation']
            e['faces'][face] = converted
        assert min(e['from']+e['to']) >= -16 and max(e['from']+e['to']) <= 32
        elements.append(e)
    png = base64.b64decode(doc['textures'][0]['source'].split(',',1)[1])
    return {'ambientocclusion':False,'textures':{'0':texture,'particle':'#0'},'elements':elements}, png

def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, separators=(',',':'))+'\n', encoding='utf-8')

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--write',action='store_true'); args=ap.parse_args()
    docs = {'gray_crt_tv':source('02_灰色'), 'red_crt_tv':source('03_红色')}
    for width in (2,3):
        for wall in (False,True):
            docs[f'panel_tv_{width}'+('_wall' if wall else '')] = source(f'0{width-1}_{width}x2x1', '壁挂' if wall else '桌面')
    products = {}; pngs = {}; bounds = {}
    for name, doc in docs.items():
        shift = 16 if name.startswith('panel_tv_3') else 0
        texture = 'piq_fc_arcade:block/user_tv48/'+name
        model, png = convert(doc, texture, shift)
        if name == 'red_crt_tv':
            gray, _ = convert(docs['gray_crt_tv'], 'piq_fc_arcade:block/user_tv48/gray_crt_tv')
            model['textures']['av'] = 'piq_fc_arcade:block/user_tv48/gray_crt_tv'
            # Original red CRT has only antenna/power inputs. Add a dedicated AV cluster,
            # preserving its antenna jack and all of its original atlas pixels.
            for e in gray['elements']:
                if e['name'].startswith('AV视频'):
                    e = copy.deepcopy(e)
                    for k in ('from','to'): e[k][2] -= .995
                    if 'rotation' in e: e['rotation']['origin'][2] -= .995
                    for f in e['faces'].values(): f['texture']='#av'
                    model['elements'].append(e)
            lamp = copy.deepcopy(next(e for e in gray['elements'] if e['name']=='红色待机灯'))
            for k in ('from','to'):
                lamp[k][0] += .58; lamp[k][1] += .35; lamp[k][2] += .15
            for f in lamp['faces'].values(): f['texture']='#av'
            model['elements'].append(lamp)
        if name.startswith('panel'):
            power = next(e for e in model['elements'] if e['name']=='下沿电源按钮')
            for offset,label in ((-2.0,'音量减'),(-1.0,'音量加')):
                button = copy.deepcopy(power); button['name']=label
                for k in ('from','to'): button[k][0] += offset
                model['elements'].append(button)
        # Compute bounds after real cube rotations, not unrotated AABBs (the feet use 45°).
        pts=np.concatenate([vertices_for(e,face) for e in model['elements'] for face in e['faces']])
        pts[:,0]+=shift
        bounds[name]={'min':np.round(pts.min(axis=0),6).tolist(),'max':np.round(pts.max(axis=0),6).tolist(),
                      'baked_x_shift':-shift,'elements':len(model['elements'])}
        products[f'models/block/user_tv48/{name}.json'] = model
        pngs[f'textures/block/user_tv48/{name}.png'] = png
        products[f'blockstates/{name}.json']={'variants':{f'facing={f}':{'model':f'piq_fc_arcade:block/user_tv48/{name}', **({'y':i*90} if i else {})}
                                                       for i,f in enumerate(('north','east','south','west'))}}
        if not name.endswith('_wall'):
            scale = .32 if name.startswith('panel') else .65
            products[f'models/item/{name}.json']={'parent':f'piq_fc_arcade:block/user_tv48/{name}',
                'display':{'gui':{'rotation':[15,145,0], 'translation':[0,-1,0],'scale':[scale]*3},
                    'firstperson_righthand':{'rotation':[0,30,0],'translation':[0,1,0],'scale':[.35]*3},
                    'firstperson_lefthand':{'rotation':[0,30,0],'translation':[0,1,0],'scale':[.35]*3},
                    'thirdperson_righthand':{'rotation':[0,0,0],'translation':[0,2,0],'scale':[.25]*3},
                    'thirdperson_lefthand':{'rotation':[0,0,0],'translation':[0,2,0],'scale':[.25]*3}}}
            if name == 'panel_tv_2':
                # This world model is centred at x=16, not the item origin x=8.
                # Offset only the display transforms; retain block/cable coordinates.
                display = products[f'models/item/{name}.json']['display']
                for context, translation in {
                    'gui':[2.097029,-1.380038,1.418323],
                    'firstperson_righthand':[-2.424871,1,1.4],
                    'firstperson_lefthand':[2.424871,1,-1.4],
                    'thirdperson_righthand':[-2,2,0],
                    'thirdperson_lefthand':[2,2,0],
                }.items():
                    display[context]['translation'] = translation
    coin, png = convert(source('01_银色'), 'piq_fc_arcade:block/user_tv48/arcade_coin', scale=10, center=[8,.5,8])
    coin['display']={'gui':{'rotation':[0,0,0],'scale':[1.15]*3},'ground':{'scale':[.3]*3},
        'firstperson_righthand':{'rotation':[0,0,-15],'translation':[1,1,0],'scale':[.7]*3},
        'firstperson_lefthand':{'rotation':[0,0,15],'translation':[1,1,0],'scale':[.7]*3},
        'thirdperson_righthand':{'rotation':[0,0,0],'translation':[0,2,0],'scale':[.45]*3},
        'thirdperson_lefthand':{'rotation':[0,0,0],'translation':[0,2,0],'scale':[.45]*3}}
    products['models/item/arcade_coin.json']=coin
    pngs['textures/block/user_tv48/arcade_coin.png']=png
    if args.write:
        for path,model in products.items():
            write_json(ASSETS/path,model)
        for path,png in pngs.items():
            dest=ASSETS/path;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(png)
        for name in ('gray_crt_tv','red_crt_tv'):
            write_json(ASSETS.parent.parent/'data/piq_fc_arcade/loot_table/blocks'/f'{name}.json',
                {'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':f'piq_fc_arcade:{name}'}],
                                                  'conditions':[{'condition':'minecraft:survives_explosion'}]}]})
    report={'models':bounds,'texture_sha256':{p:hashlib.sha256(v).hexdigest() for p,v in pngs.items()},'written':args.write}
    (ROOT/'outputs/tvcoin48/model-conversion.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(report,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
