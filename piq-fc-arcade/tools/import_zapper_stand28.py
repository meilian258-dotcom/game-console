"""Trusted derivative of reviewed raw parts; source geometry and PNG remain untouched."""
import argparse,copy,json
from pathlib import Path
from prepare_zapper_model_parts import derive,sha
ROOT=Path(__file__).resolve().parents[1]
RES=ROOT/'src/main/resources'
def derive_stand():
    raw,audit=derive();out={};models={}
    for part in ('stand','cable','connector'):
        model=json.loads(raw['models/block/'+part+'.json']);original=copy.deepcopy(model['elements'])
        model['textures']['0']='piq_fc_arcade:item/zapper/skin';assert model['elements']==original
        models[part]=model;out['assets/piq_fc_arcade/models/block/zapper_stand/'+part+'.json']=model
    # Empty stand is honest in inventory; placing never manufactures a free gun.
    # ItemTransform rotates about the block center, not this very low source part.
    # GUI-only translations center the unchanged part after rotation and scale.
    out['assets/piq_fc_arcade/models/item/zapper_stand.json']={'parent':'piq_fc_arcade:block/zapper_stand/stand','display':{'gui':{'rotation':[25,225,0],'translation':[.220971,7.059978,4.268315],'scale':[1.25]*3}}}
    # Vanilla clamps item display scale to 4. Show the original complete coiled
    # cable + plug, rather than invisibly enlarging its tiny plug beyond that cap.
    out['assets/piq_fc_arcade/models/item/zapper_stand_cable.json']={'textures':copy.deepcopy(models['cable']['textures']),
        'elements':copy.deepcopy(models['cable']['elements']+models['connector']['elements']),
        'display':{'gui':{'rotation':[75,225,0],'translation':[5.060974,6.943014,19.95226],'scale':[3]*3}}}
    out['assets/piq_fc_arcade/blockstates/zapper_stand.json']={'variants':{'':{'model':'piq_fc_arcade:block/zapper_stand/stand'}}}
    out['data/piq_fc_arcade/loot_table/blocks/zapper_stand.json']={'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'piq_fc_arcade:zapper_stand'}],'conditions':[{'condition':'minecraft:survives_explosion'}]}]}
    return {p:(json.dumps(v,ensure_ascii=False,separators=(',',':'))+'\n').encode()for p,v in out.items()}
def main():
    p=argparse.ArgumentParser();p.add_argument('--apply',action='store_true');p.add_argument('--refresh-generated',action='store_true',help='Mechanically regenerate only the seven explicit new stand derivatives, never original assets');args=p.parse_args();out=derive_stand()
    for name,raw in out.items():
        path=RES/name
        if args.refresh_generated or args.apply and not path.exists():path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(raw)
        assert path.read_bytes()==raw,name
    print(json.dumps({'ok':True,'source_geometry_unchanged':True,'new_png':0,'files':{n:sha(v)for n,v in out.items()}}))
if __name__=='__main__':main()
