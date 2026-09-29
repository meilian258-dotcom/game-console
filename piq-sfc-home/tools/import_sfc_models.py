"""Adopt the frozen native SFC v2 meshes into only the new SFC Home namespace."""
from __future__ import annotations
import argparse,hashlib,json
from pathlib import Path

PROJECT=Path(__file__).resolve().parents[1]
WORKSPACE=PROJECT.parent
FROZEN=WORKSPACE/'制作Mod/03-街机模拟/PIQ-FC街机/SFC附属首版模型草案/卡槽与GUI校正-v2'
ASSETS=PROJECT/'src/main/resources/assets/piq_sfc_home'
MODELS={
 'models/block/sfc_console_body.json':('sfc_console_body.json','714C26067AA3F66444BD92BF4380869D80CFD4429D547F347FB2A754142BF711'),
 'models/block/sfc_console_controller_1.json':('sfc_console_controller_1.json','4766ED85CD79FEA8E0507AC1CE6F5791A0ACF3466B0959C9F283D0B2EC5CB81B'),
 'models/block/sfc_console_controller_2.json':('sfc_console_controller_2.json','6BB2C378E091FAD30A5A8DE839730D54FA300846E17BBD5AD743DEABABF2DA4C'),
 'models/block/sfc_console_slot_cover.json':('sfc_console_slot_cover.json','8C875203841ED1B49CCB545B2F3E14EAF02D6E6558FA033F88C1F55CF9CFAB64'),
 'models/block/sfc_console_cartridge_inserted.json':('sfc_console_cartridge_inserted.json','25033954C547AF826E6760F9B66F0D9E2E5FA9FD74D478A4E202E142A578AAAE'),
 'models/block/sfc_console_inventory.json':('sfc_console_assembly_preview.json','279EC13E55D5E4FEB240B35A592D500CE921CFD8E9E60CE324F6A8E8C3B96DCC'),
 'models/item/controller.json':('sfc_controller.json','2B95EE13E4BA518551398B1D6C577464CB872188FF144CD0428A7E833091EF05'),
 'models/item/cartridge.json':('sfc_cartridge.json','B243342B8DD2C1498FF53AC0F56E71A8F7BBA35995C495036FDF4209936DB04B')}

def sha(raw):return hashlib.sha256(raw).hexdigest().upper()
def encoded(model):return (json.dumps(model,ensure_ascii=False,separators=(',',':'))+'\n').encode()
def blockstate():
    return {'variants':{'facing='+direction:{'model':'piq_sfc_home:block/sfc_console_body',**({'y':angle} if angle else {})} for direction,angle in (('north',0),('east',90),('south',180),('west',270))}}
def console_item():
    return {'parent':'piq_sfc_home:block/sfc_console_inventory','display':{
      'firstperson_righthand':{'rotation':[0,45,0],'translation':[0,3,0],'scale':[.55]*3},
      'firstperson_lefthand':{'rotation':[0,45,0],'translation':[0,3,0],'scale':[.55]*3},
      'thirdperson_righthand':{'rotation':[75,45,0],'translation':[0,2.5,0],'scale':[.375]*3},
      'thirdperson_lefthand':{'rotation':[75,45,0],'translation':[0,2.5,0],'scale':[.375]*3}}}
def outputs():
    out={}
    for rel,(name,expected) in MODELS.items():
        raw=(FROZEN/name).read_bytes()
        if sha(raw)!=expected:raise ValueError('Frozen SFC v2 changed: '+name)
        out[ASSETS/rel]=raw
    out[ASSETS/'models/item/console.json']=encoded(console_item())
    out[ASSETS/'blockstates/console.json']=encoded(blockstate())
    return out
def main():
    p=argparse.ArgumentParser();p.add_argument('--write',action='store_true');a=p.parse_args();out=outputs()
    for path,raw in out.items():
        if path.is_symlink() or (path.exists() and path.read_bytes()!=raw):raise ValueError('Refusing to replace existing different model '+str(path))
    if a.write:
        for path,raw in out.items():
            if not path.exists():path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(raw)
    report={'ok':all(p.exists() and p.read_bytes()==raw for p,raw in out.items()),'models':{str(p.relative_to(ASSETS)):sha(raw) for p,raw in out.items()},
      'note':'Eight frozen native meshes copied byte-for-byte; only new item-parent and blockstate wrappers use piq_sfc_home. No PNG writes.'}
    print(json.dumps(report,ensure_ascii=True,indent=2))
    if not report['ok']:raise SystemExit(1)
if __name__=='__main__':main()
