"""Package an immutable, verified alpha29 stage. No installation, game or service operations."""
import argparse,json,zipfile,xml.etree.ElementTree as ET
from pathlib import Path
from prepare_appliance29 import ROOT,NAMES,HELPER,BUILD_WITNESS,plan
from freeze_fc_core_alpha19 import safe_path,digest,require

def counts(project):
    result={k:0 for k in ('tests','failures','errors','skipped')}
    paths=list((ROOT/project/'build/test-results/test').glob('TEST-*.xml'));require(paths,'Missing test results')
    for path in paths:
        xml=ET.fromstring(path.read_bytes())
        for key in result:result[key]+=int(xml.attrib[key])
    require(result['tests']>200 and result['failures']==result['errors']==0,'Full build failed '+project)
    return result

def main():
    p=argparse.ArgumentParser();p.add_argument('--stage',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    stage=safe_path(a.stage);require(stage.is_dir(),'Missing stage directory');out=safe_path(a.output);archive=out.with_suffix('.zip');verification=out.with_suffix('.verification.json')
    require(stage.is_relative_to(ROOT)and out.is_relative_to(ROOT),'Must remain in workspace')
    require(not out.exists()and not archive.exists()and not verification.exists(),'Never overwrite an earlier delivery')
    frozen=json.loads((stage/'stage-verification.json').read_text(encoding='utf-8'));raws,current=plan();require(current==frozen,'Sources or baseline changed after freeze')
    for name,raw in raws.items():require((stage/name).read_bytes()==raw,'Frozen artifact changed '+name)
    hashes={k:digest(raws[name])for k,name in NAMES.items()}
    required={'appliance-final.json':('fc','sfc'),'fc-runtime-final.json':('fc',),'stand-final.json':('fc',),'sfc-runtime-final.json':('fc','sfc'),'sfc-watch-final.json':('fc','sfc'),'tv-nbt-final.json':('fc',),'controller-cable-final.json':('fc','sfc'),
        'data-cable-final.json':('fc',),'cartridge-save-final.json':('fc',),'sfc-receipts-final.json':('fc','sfc'),'gun-core-final.json':('fc',)}
    evidence={}
    for name,kinds in required.items():
        path=stage/'checks'/name;data=json.loads(path.read_text(encoding='utf-8'))
        require(data.get('ok',data.get('passed',False))and data.get('mode')=='final-jar-only'and data.get('production_compiled')is False,'Not successful packaged-code evidence '+name)
        bound={k:v['sha256'].upper()for k,v in data.get('jars',{}).items()}
        if 'sha256'in data:bound['fc']=data['sha256'].upper()
        for kind in kinds:require(bound.get(kind)==hashes[kind],'Evidence bound to wrong JAR '+name+' '+kind)
        evidence[name]=data
    tests={'fc':counts('piq-fc-arcade'),'sfc':counts('piq-sfc-home')}
    require(tests['fc']['skipped']==7 and tests['sfc']['skipped']==0,'Unexpected skipped tests')
    require(tests['fc']['tests']>=1229 and tests['sfc']['tests']>=309,'Missing full new regression suite')
    files={'mods/'+name:raws[name]for name in NAMES.values()};files[HELPER]=raws[HELPER]
    guide=ROOT/'piq-fc-arcade/design/有线控制与设备音光-alpha29-使用说明.md';files['先看这里.md']=guide.read_bytes()
    for name in required:files['checks/'+name]=(stage/'checks'/name).read_bytes()
    files['checks/stage-verification.json']=(stage/'stage-verification.json').read_bytes()
    files['checks/build-witness.json']=BUILD_WITNESS.read_bytes()
    report={'ok':True,'schema':'piq-appliance29-delivery-1','builds':tests,'mods':hashes,'helper_sha256':digest(raws[HELPER]),'evidence':list(required),
        'source_scope_verified':True,'installed':False,'minecraft_started':False,'real_network_tested':False,'commercial_roms_included':False,
        'limits':['Real core workers, outer payload codecs, state/geometry/NBT and package checks are not a live Minecraft multiplayer test.','GUI, physical clicks, protection plugins, real network and controller hardware await in-game acceptance.']}
    files['checks/summary.json']=json.dumps(report,ensure_ascii=False,indent=2).encode()
    files['SHA256.txt']=''.join(digest(raw)+'  '+name+'\n'for name,raw in sorted(files.items())).encode('utf-8')
    out.mkdir(parents=True)
    for name,raw in files.items():
        path=safe_path(out/name);require(path.is_relative_to(out),'Invalid target path');path.parent.mkdir(parents=True,exist_ok=True)
        with path.open('xb')as f:f.write(raw)
        require(path.read_bytes()==raw,'Output readback differs')
    with zipfile.ZipFile(archive,'x',compression=zipfile.ZIP_DEFLATED)as z:
        for name,raw in files.items():z.writestr(name,raw)
    with zipfile.ZipFile(archive)as z:
        require(z.testzip()is None and set(z.namelist())==set(files),'ZIP contents/CRC invalid')
        for name,raw in files.items():require(z.read(name)==raw==(out/name).read_bytes(),'ZIP readback differs')
    again,scope=plan();require(again==raws and scope==frozen,'Inputs changed during packaging')
    report.update(package=str(archive),package_sha256=digest(archive.read_bytes()),package_bytes=archive.stat().st_size)
    with verification.open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=True))
if __name__=='__main__':main()
