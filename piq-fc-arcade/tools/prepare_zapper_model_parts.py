"""Prepare raw static Zapper parts, not registered items or an installed resource pack.
User ZIP scripts are never imported. Geometry, face UVs, rotations and PNG are unchanged.
"""
from __future__ import annotations
import argparse, base64, copy, hashlib, json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'design/refined-dual-zapper-20260911/source/01_光枪_带展示支架'
EXPECTED={
 'Zapper_橙灰光枪_展示支架版.bbmodel':'A55A6550E4BDE2EBA0B0B8D2EB64FB1A2A840014713DEBCDAD74B2408E79F57B',
 'zapper_stand.json':'89CDFAD0A6C02F628AB4D50F7DD5FC66E3B0052FF66A1A18250EAFB4755CA9E1',
 'Zapper_支架版完整UV.png':'AB5C925B7BD21AD2CBBFCC96A0F38CC4FE6C4E9013BCE57DF8F866E9EA16167F',
 '扳机分组映射.json':'4B43C6187409F0C9AF14E5933192219735461088277E3E7609C3CC349055318B',
}
def sha(raw):return hashlib.sha256(raw).hexdigest().upper()

def derive():
    raw={n:(SOURCE/n).read_bytes() for n in EXPECTED}
    assert {n:sha(v)for n,v in raw.items()}==EXPECTED
    bb=json.loads(raw['Zapper_橙灰光枪_展示支架版.bbmodel']);model=json.loads(raw['zapper_stand.json'])
    trigger=json.loads(raw['扳机分组映射.json'])['controls'][0]
    assert bb['resolution']=={'width':2048,'height':2048}
    assert base64.b64decode(bb['textures'][0]['source'].split(',',1)[1])==raw['Zapper_支架版完整UV.png']
    assert len(bb['elements'])==len(model['elements'])==286
    assert len({b['uuid']for b in bb['elements']})==286
    groups={g['uuid']:g for g in bb['groups']}
    assert all(g.get('rotation',[0,0,0])==[0,0,0]for g in groups.values())
    ids={};order=[]
    for b,e in zip(bb['elements'],model['elements']):
        assert b['type']=='cube' and b['name']==e['name'] and b['from']==e['from'] and b['to']==e['to']
        for face,f in e['faces'].items():
            assert f['texture']=='#0' and all(abs(a/128-c)<1e-8 for a,c in zip(b['faces'][face]['uv'],f['uv']))
        ids[b['uuid']]=e;order.append(b['uuid'])
    membership={};all_members=[]
    def walk(node,part):
        if isinstance(node,str):
            assert node in ids;all_members.append(node);membership.setdefault(part,[]).append(node);return
        name=groups[node['uuid']]['name']
        if name in ('trigger','cable','connector'):part=name
        if name=='display_stand':part='stand'
        for child in node['children']:walk(child,part)
    for node in bb['outliner']:walk(node,'body')
    assert len(all_members)==len(set(all_members))==286 and set(all_members)==set(ids)
    assert set(membership)=={'body','trigger','stand','cable','connector'}
    assert set(membership['trigger'])==set(trigger['elementUuids']) and len(membership['trigger'])==2
    assert trigger['pivot']==[9.52327672,4.03390858,8.204]
    assert trigger['suggestedRotationWhenPressed']==[0,0,8]
    output={};parts={}
    for name,members in membership.items():
        selected=set(members);elements=[copy.deepcopy(ids[i])for i in order if i in selected]
        # Exact source elements, including per-face rotations, no rescaling/recentering.
        part={k:copy.deepcopy(v)for k,v in model.items()if k!='elements'};part['elements']=elements
        output[f'models/block/{name}.json']=(json.dumps(part,ensure_ascii=False,separators=(',',':'))+'\n').encode('utf-8')
        assert elements==[ids[i]for i in order if i in selected]
        parts[name]={'elements':len(elements),'element_uuids':[i for i in order if i in selected]}
    output['textures/block/skin.png']=raw['Zapper_支架版完整UV.png']
    report={'ok':True,'raw_only':True,'minecraft_started':False,'source_sha256':EXPECTED,
        'source_geometry_and_uv_preserved':True,'elements':286,'parts':parts,'trigger':trigger,
        'coordinate_system':'original source model units; 16 units/block; muzzle -X; no hand transform applied',
        'registered_items':0,'core_or_network_changes':False,
        'files':{n:sha(v)for n,v in output.items()}}
    output['parts-audit.json']=(json.dumps(report,ensure_ascii=False,indent=2)+'\n').encode('utf-8')
    return output,report

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--output',type=Path,required=True);args=ap.parse_args()
    output,report=derive()
    assert not args.output.exists(),'Use a new independent output directory'
    args.output.mkdir(parents=True)
    for name,raw in output.items():
        p=args.output/name;p.parent.mkdir(parents=True,exist_ok=True)
        with p.open('xb')as f:f.write(raw)
        assert p.read_bytes()==raw
    print(json.dumps({'ok':True,'raw_only':True,'parts':{n:v['elements']for n,v in report['parts'].items()},
        'report':str((args.output/'parts-audit.json').resolve())}))
if __name__=='__main__':main()
