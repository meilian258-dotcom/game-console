"""Import only the user's unchanged gun body/trigger/PNG; no stand or coil in hand.
Raw source parts and all historical imports remain untouched. Never executes ZIP code.
"""
import argparse,copy,json
from pathlib import Path
from prepare_zapper_model_parts import derive,sha
ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'src/main/resources/assets/piq_fc_arcade'

def production():
    raw,audit=derive();out={}
    for part,count in (('body',130),('trigger',2)):
        original=json.loads(raw['models/block/'+part+'.json']);model=copy.deepcopy(original)
        assert len(model['elements'])==count
        model['textures']['0']='piq_fc_arcade:item/zapper/skin'
        assert model['elements']==original['elements']
        out['models/item/zapper/'+part+'.json']=(json.dumps(model,ensure_ascii=False,separators=(',',':'))+'\n').encode()
    out['textures/item/zapper/skin.png']=raw['textures/block/skin.png']
    out['models/item/fc_zapper.json']=(json.dumps({'parent':'builtin/entity','textures':{'particle':'piq_fc_arcade:item/zapper/skin'}},separators=(',',':'))+'\n').encode()
    return out

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--apply',action='store_true');a=p.parse_args()
    output=production()
    for n,v in output.items():
        path=ASSETS/n
        if a.apply:
            if path.exists():assert path.read_bytes()==v,'Refusing unreviewed existing asset '+n
            else:
                path.parent.mkdir(parents=True,exist_ok=True)
                with path.open('xb')as f:f.write(v)
        else:assert path.read_bytes()==v,'Derived asset differs '+n
    print(json.dumps({'ok':True,'source_parts_unchanged':True,'gun_elements':132,'held_stand_or_coil_elements':0,
                      'files':{n:sha(v)for n,v in output.items()},'minecraft_started':False}))
if __name__=='__main__':main()
