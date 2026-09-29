"""Stage user-model FC22/SFC10; preserve all non-visual baseline bytes. No install."""
import argparse,json,sys
from pathlib import Path
from freeze_fc_core_alpha19 import read_jar,safe_path,digest,require,META,MANIFEST
from freeze_fc_compact_alpha20 import RESTORE
from prepare_cabinet_multiplayer21 import clean,jar_bytes,merged_sfc,HELPER_SHA
import tomllib

ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'piq-fc-arcade/build/review-linked21-v1'
NAMES={'fc':'piq_fc_arcade-0.31.0-alpha.22.jar','sfc':'piq_sfc-0.1.0-alpha.10.jar',
       'native':'piq_native_arcade-0.1.0-alpha.7.jar'}
BASELINES={
 'fc':('piq_fc_arcade-0.31.0-alpha.21.jar','0147D49C542E82DDF2DD37CDAFFBDB135CDA20E11907B4060B471A2FBC0D3F93'),
 'sfc':('piq_sfc-0.1.0-alpha.9.jar','F951146515D7F35257585A9DA7536453F1DFFA70285F51EDD612D65C771DF951'),
 'native':(NAMES['native'],'8BE543D920BB3D49627F440CBE02EBD66AA2BB4370EA249202EF1F1066E1608A')}
BUILDS={'fc':ROOT/'piq-fc-arcade/build/libs'/NAMES['fc'],
        'sfc':ROOT/'piq-sfc-home/build/libs/piq_sfc_home-0.1.0-alpha.10.jar'}
CLASSES={
 'fc':{'client/DualCabinetRenderer','layout/DualCabinetGeometry','layout/DualCabinetControls',
       'layout/CabinetVideoGeometry','layout/ArcadeOccupancyLabelLayout','server/ArcadeOccupancyDisplay',
       'world/DualCabinetFootprint','client/ClientArcadeEvents',
       'client/cabinet/CabinetClientBackends','client/cabinet/CabinetPeerInputs',
       'client/cabinet/CabinetMenuScreen','client/RomLibraryScreen','client/SkinLibraryScreen'},
 'sfc':{'client/SfcHardwareRenderer','client/SfcHardwareMeshData','client/SfcHardwareMesh',
        'client/SfcHardwareItems','client/SfcCoverGeometry','client/SfcControllerPose',
        'client/SfcButtonAnimation','client/SfcAvCableGeometry','client/SfcHomeClient',
        'client/SfcCartridgeRenderer'}}

def asset_plan():
    from import_user_dual_model import derive
    fc,_=derive()
    sys.path.insert(0,str(ROOT/'piq-sfc-home/tools'))
    import import_user_sfc_20260911 as sfc
    doc,_=sfc.build()
    sc={sfc.ASSETS/'meshes/sfc_hardware.json':json.dumps(doc,ensure_ascii=False,separators=(',',':')).encode('utf-8')}
    sc[sfc.ASSETS/'models/item/console.json']=sfc.console_item_bytes()
    for _,(resource,path) in sfc.TEXTURES.items():
        sc[sfc.ASSETS/'textures'/(resource.split(':',1)[1]+'.png')]=path.read_bytes()
    roots={'fc':ROOT/'piq-fc-arcade/src/main/resources','sfc':ROOT/'piq-sfc-home/src/main/resources'}
    return {kind:{p.relative_to(roots[kind]).as_posix():b for p,b in values.items()} for kind,values in [('fc',fc),('sfc',sc)]}

def classify(kind,old,new,assets):
    removed=set(old)-set(new);require(not removed,'Removed entries: '+repr(sorted(removed)))
    delta={n for n,v in new.items() if n not in old or old[n]!=v}
    for name in delta:
        if name in (META,MANIFEST):continue
        if name in assets:
            require(new[name]==assets[name],'Derived user asset differs: '+name);continue
        prefix='cn/piq/'+('fcarcade' if kind=='fc' else 'sfchome')+'/'
        stem=name.removeprefix(prefix).removesuffix('.class').split('$')[0]
        require(name.startswith(prefix) and name.endswith('.class') and stem in CLASSES[kind],
                'Unexpected production change '+kind+': '+name)
    require(all(new.get(n)==raw for n,raw in assets.items()),'Missing or changed derived user assets')
    protected=[n for n in old if n not in delta]
    return {'explicit_allowlist_enforced':True,'removed':[],
            'added':sorted(set(new)-set(old)),
            'changed':{n:{'before':digest(old[n]),'after':digest(new[n])} for n in sorted(delta&set(old))},
            'unchanged_entries':len(protected),
            'source_derived_assets':{n:digest(v) for n,v in assets.items()}}

def metadata(kind,entries):
    doc=tomllib.loads(entries[META].decode('utf-8'))
    expected={'fc':{'piq_fc_arcade':'0.31.0-alpha.22'},
              'sfc':{'piq_sfc_arcade':'0.2.0-alpha.6','piq_sfc_home':'0.1.0-alpha.10'}}[kind]
    actual={m['modId']:m['version'] for m in doc['mods']}
    require(actual==expected and len(doc['mods'])==len(expected),'Unexpected MOD identity')
    if kind=='sfc':
        dep=[d for d in doc['dependencies']['piq_sfc_home'] if d['modId']=='piq_fc_arcade']
        require(len(dep)==1 and dep[0]['versionRange']=='[0.31.0-alpha.22,0.32.0)' and dep[0]['type']=='required'
                and dep[0]['side']=='BOTH' and dep[0]['ordering']=='AFTER','SFC requires FC22 on both sides')
    return actual

def plan():
    assets=asset_plan();files={};entries={};report={'schema':'piq-user-models22-stage-1','mods':{},'installed':False,
        'minecraft_started':False,'commercial_roms_or_bios_added':False}
    for kind,path in BUILDS.items():
        build_sha,_,built=read_jar(path);built=clean(built)
        base_name,expected=BASELINES[kind];old_sha,_,old=read_jar(BASE/base_name);old=clean(old)
        require(old_sha==expected,'Baseline SHA changed '+kind)
        if kind=='fc':
            for name,(source_sha,frozen_sha) in RESTORE.items():
                require(digest(old[name])==frozen_sha and digest(built[name]) in (source_sha,frozen_sha),'Unreviewed FC artwork')
                built[name]=old[name]
        else:
            built=merged_sfc(built)
            built[MANIFEST]=b'Manifest-Version: 1.0\r\nImplementation-Title: PIQ SFC\r\nImplementation-Version: 0.1.0-alpha.10\r\n\r\n'
        report['mods'][kind]={'source_sha256':build_sha,'baseline_sha256':old_sha,
            'versions':metadata(kind,built),'scope':classify(kind,old,built,assets[kind])}
        entries[kind]=built;files[NAMES[kind]]=jar_bytes(built)
    native_path=BASE/BASELINES['native'][0];native_sha,_,native=read_jar(native_path)
    require(native_sha==BASELINES['native'][1],'Unchanged Native7 SHA mismatch')
    entries['native']=clean(native);files[NAMES['native']]=native_path.read_bytes()
    helper=safe_path(BASE/'piq-native-arcade/runtime/piq-native-helper.jar',True).read_bytes()
    require(digest(helper)==HELPER_SHA,'Unchanged helper SHA mismatch')
    files['piq-native-arcade/runtime/piq-native-helper.jar']=helper
    owners={}
    for kind,values in entries.items():
        for name in values:
            if name.endswith('.class'):
                require(name not in owners,'Duplicate class '+name);owners[name]=kind
    report['unique_classes']=len(owners);report['files']={n:{'bytes':len(v),'sha256':digest(v)} for n,v in files.items()}
    report['ok']=True
    return files,report

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--check-only',action='store_true');a=p.parse_args()
    output=safe_path(a.output);require(output.is_relative_to(ROOT) and not output.exists(),'Output exists/outside workspace')
    files,report=plan()
    if not a.check_only:
        output.mkdir(parents=True)
        for name,raw in files.items():
            path=safe_path(output/name);path.parent.mkdir(parents=True,exist_ok=True)
            with path.open('xb') as stream:stream.write(raw)
            require(path.read_bytes()==raw,'Stage readback mismatch')
        with (output/'stage-verification.json').open('x',encoding='utf-8') as stream:json.dump(report,stream,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'files':report['files'],'unique_classes':report['unique_classes'],'output':str(output)},ensure_ascii=True))

if __name__=='__main__':main()
