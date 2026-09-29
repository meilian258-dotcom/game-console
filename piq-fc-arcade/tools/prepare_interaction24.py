"""Freeze FC24/SFC13/Native8, protecting the previously delivered cores and every asset."""
import argparse,json,tomllib
from pathlib import Path
from freeze_fc_core_alpha19 import read_jar,safe_path,digest,require,META,MANIFEST
from freeze_fc_compact_alpha20 import RESTORE
from prepare_cabinet_multiplayer21 import clean,jar_bytes,merged_sfc,HELPER_SHA

ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'piq-fc-arcade/build/review-watch23-scale12-v1'
VERSIONS={'fc':'0.31.0-alpha.24','sfc':'0.1.0-alpha.13','native':'0.1.0-alpha.8'}
NAMES={'fc':'piq_fc_arcade-'+VERSIONS['fc']+'.jar','sfc':'piq_sfc-'+VERSIONS['sfc']+'.jar','native':'piq_native_arcade-'+VERSIONS['native']+'.jar'}
PINNED={'fc':('piq_fc_arcade-0.31.0-alpha.23.jar','B7BF04BEEA5F58E8E9C0DFA23E1BD8F2CAF6B11455085D843F568B42D9021686'),
 'sfc':('piq_sfc-0.1.0-alpha.12.jar','EEE8BDCC710BEEBEBF58CF85CA1B19B2D314FE4DA9EBE8D36470B62BD383E8D1'),
 'native':('piq_native_arcade-0.1.0-alpha.7.jar','8BE543D920BB3D49627F440CBE02EBD66AA2BB4370EA249202EF1F1066E1608A')}
BUILDS={'fc':ROOT/'piq-fc-arcade/build/libs'/NAMES['fc'],
 'sfc':ROOT/'piq-sfc-home/build/libs/piq_sfc_home-0.1.0-alpha.13.jar',
 'native':ROOT/'piq-native-arcade/build/libs'/NAMES['native']}
OLD={
 'fc':{'cn/piq/fcarcade/'+n for n in ('cabinet/CabinetNetwork','cabinet/CabinetRooms','client/cabinet/CabinetClientBackends','client/cabinet/CabinetMenuScreen','client/cabinet/CabinetImmersiveInput')},
 'sfc':{'cn/piq/sfchome/'+n for n in ('server/SfcHomeServer','server/SfcControllerAuthority','item/SfcControllerItem','world/SfcHomeConsoleBlock','client/SfcHomeClient')},
 'native':{'cn/piq/nativearcade/client/NativeArcadeClient','cn/piq/nativearcade/layout/NativeCabinetLayout'}}
NEW={
 'fc':{'cn/piq/fcarcade/'+n for n in ('cabinet/CabinetJoinGate','cabinet/CabinetJoinNetwork','client/cabinet/CabinetJoinClient','client/cabinet/CabinetPromptGuard')},
 'sfc':{'cn/piq/sfchome/'+n for n in ('server/SfcControllerInventory','client/SfcInputFocus')},'native':set()}

def classify(kind,old,new):
    require(not set(old)-set(new),'Removed entries '+kind)
    changed={n for n in new if n not in old or new[n]!=old[n]}
    for name in changed:
        if name in (META,MANIFEST):continue
        stem=name.removesuffix('.class').split('$')[0]
        require(name.endswith('.class') and stem in (OLD[kind] if name in old else OLD[kind]|NEW[kind]),'Unexpected interaction delta '+kind+': '+name)
    assets=[n for n in old if n.startswith(('assets/','data/'))]
    require(all(new[n]==old[n] for n in assets),'Asset changed '+kind)
    return {'added':sorted(set(new)-set(old)),
      'changed':{n:{'before':digest(old[n]),'after':digest(new[n])} for n in sorted(changed&set(old))},
      'removed':[],'unchanged_entries':len(old)-len(changed&set(old)),'assets_and_data_unchanged':len(assets)}

def metadata(kind,old,new):
    before=tomllib.loads(old[META].decode());after=tomllib.loads(new[META].decode())
    owner={'fc':'piq_fc_arcade','sfc':'piq_sfc_home','native':'piq_native_arcade'}[kind]
    for mod in before['mods']:
        if mod['modId']==owner:mod['version']=VERSIONS[kind]
    if kind!='fc':
        for dep in before['dependencies'][owner]:
            if dep['modId']=='piq_fc_arcade':dep['versionRange']='[0.31.0-alpha.24,0.32.0)'
    require(after==before,'Unexpected metadata delta '+kind)
    require(VERSIONS[kind] in new[MANIFEST].decode(),'Wrong manifest version')

def plan():
    files={};entries={};report={'schema':'piq-interaction24-stage-1','mods':{},'installed':False,'minecraft_started':False}
    for kind,path in BUILDS.items():
        source_sha,_,built=read_jar(path);built=clean(built)
        name,pin=PINNED[kind];old_sha,_,old=read_jar(BASE/name);old=clean(old)
        require(old_sha==pin,'Frozen baseline changed '+kind)
        if kind=='fc':
            for n,(source,frozen) in RESTORE.items():
                require(digest(old[n])==frozen and digest(built[n]) in (source,frozen),'Unexpected original FC controller texture');built[n]=old[n]
        if kind=='sfc':
            built=merged_sfc(built)
            built[MANIFEST]=b'Manifest-Version: 1.0\r\nImplementation-Title: PIQ SFC\r\nImplementation-Version: 0.1.0-alpha.13\r\n\r\n'
        metadata(kind,old,built)
        report['mods'][kind]={'source_sha256':source_sha,'baseline_sha256':old_sha,'scope':classify(kind,old,built)}
        entries[kind]=built;files[NAMES[kind]]=jar_bytes(built)
    helper=safe_path(BASE/'piq-native-arcade/runtime/piq-native-helper.jar',True).read_bytes()
    require(digest(helper)==HELPER_SHA,'Helper changed');files['piq-native-arcade/runtime/piq-native-helper.jar']=helper
    owners={}
    for kind,values in entries.items():
        for n in values:
            if n.endswith('.class'):
                require(n not in owners,'Duplicate class '+n);owners[n]=kind
    report.update(ok=True,unique_classes=len(owners),files={n:{'bytes':len(v),'sha256':digest(v)} for n,v in files.items()})
    return files,report

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--check-only',action='store_true');a=p.parse_args()
    output=safe_path(a.output);require(output.is_relative_to(ROOT) and not output.exists(),'Refuse existing/outside stage')
    files,report=plan()
    if not a.check_only:
        output.mkdir(parents=True)
        for n,raw in files.items():
            path=safe_path(output/n);path.parent.mkdir(parents=True,exist_ok=True)
            with path.open('xb')as f:f.write(raw)
            require(path.read_bytes()==raw,'Stage readback failed')
        with (output/'stage-verification.json').open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'output':str(output),'files':report['files'],'unique_classes':report['unique_classes']}))
if __name__=='__main__':main()
