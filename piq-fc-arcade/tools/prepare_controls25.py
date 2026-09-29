"""Freeze the unified controls candidate, allowing only reviewed deltas from delivered alpha24."""
import argparse,json,tomllib
from pathlib import Path
from freeze_fc_core_alpha19 import read_jar,safe_path,digest,require,META,MANIFEST
from freeze_fc_compact_alpha20 import RESTORE
from prepare_cabinet_multiplayer21 import clean,jar_bytes,merged_sfc,HELPER_SHA

ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'piq-fc-arcade/build/review-interaction24-v1'
VERSIONS={'fc':'0.31.0-alpha.25','sfc':'0.1.0-alpha.14','native':'0.1.0-alpha.9'}
NAMES={'fc':'piq_fc_arcade-'+VERSIONS['fc']+'.jar','sfc':'piq_sfc-'+VERSIONS['sfc']+'.jar','native':'piq_native_arcade-'+VERSIONS['native']+'.jar'}
PINNED={
 'fc':('piq_fc_arcade-0.31.0-alpha.24.jar','AE3185660442BA3BB38344165F3D60F736DB5DA3E63F2E4FE9D7DCC79C1F170B'),
 'sfc':('piq_sfc-0.1.0-alpha.13.jar','2DED10D744F22F7C32A988669C6E67E3A4744ECD6DB5799541EE3D6FDAD1FB15'),
 'native':('piq_native_arcade-0.1.0-alpha.8.jar','C57BA0EEF241BE2AB10BF5E82B33E6C8645F57B308BCA39A6A82DEC09DB66FEC')}
BUILDS={'fc':ROOT/'piq-fc-arcade/build/libs'/NAMES['fc'],
 'sfc':ROOT/'piq-sfc-home/build/libs/piq_sfc_home-0.1.0-alpha.14.jar',
 'native':ROOT/'piq-native-arcade/build/libs'/NAMES['native']}
OLD={
 'fc':{'cn/piq/fcarcade/'+n for n in ('client/ArcadeBlockScreenRenderer','client/ArcadeKeyMappings','client/ArcadeSettingsScreen',
  'client/ClientArcadeEvents','client/ClientArcadeSession','client/HomeVideoDisplay','client/cabinet/CabinetClientBackends',
  'core/NesCore','layout/DualCabinetGeometry')} | {'cn/piq/retro/client/'+n for n in ('GamepadConfig','GamepadConfigStore','GamepadInput','GamepadMixer','GamepadSettingsScreen')},
 'sfc':{'cn/piq/sfchome/client/SfcHomeClient','cn/piq/sfchome/client/SfcHomeKeys'},
 'native':{'cn/piq/nativearcade/client/NativeArcadeClient'}}
NEW={
 'fc':{'cn/piq/fcarcade/'+n for n in ('core/wasm/ZapperWasmNesCore','layout/ScreenRayMapping','layout/ScreenSurfaceGeometry','mixin/KeyboardHandlerMixin')} |
 {'cn/piq/retro/client/'+n for n in ('ControlLabels','ControlPanelLayout','ControlSettingsScreen','GamepadDeviceScreen','KeyboardBindingsScreen',
  'KeyboardConfig','KeyboardConfigStore','KeyboardControlState','KeyboardInput','KeyboardRouting')},
 'sfc':set(),'native':set()}
REMOVED={'fc':{'cn/piq/fcarcade/client/ArcadeBlockScreenRenderer$1.class'},'sfc':set(),'native':set()}
NEW_CLASSES={'fc':{stem+'.class'for stem in NEW['fc']} | {
 'cn/piq/fcarcade/layout/ScreenRayMapping$Pixel.class',
 'cn/piq/fcarcade/layout/ScreenSurfaceGeometry$1.class','cn/piq/fcarcade/layout/ScreenSurfaceGeometry$Surface.class',
 'cn/piq/retro/client/ControlSettingsScreen$1.class','cn/piq/retro/client/KeyboardBindingsScreen$Row.class',
 'cn/piq/retro/client/KeyboardConfig$Bindings.class','cn/piq/retro/client/KeyboardConfig$Preset.class','cn/piq/retro/client/KeyboardConfig$Profile.class',
 'cn/piq/retro/client/KeyboardConfigStore$Loaded.class','cn/piq/retro/client/KeyboardControlState$Mode.class',
 'cn/piq/retro/client/KeyboardInput$1.class','cn/piq/retro/client/KeyboardInput$Sample.class','cn/piq/retro/client/KeyboardRouting$Route.class'},
 'sfc':set(),'native':set()}
RESOURCES={
 'assets/piq_fc_arcade/models/block/user_dual/body.json':'D0154C7CFF449A37B8EE09BD847C72483729AB437ECA23843D7E623B16175B2F',
 'assets/piq_fc_arcade/textures/block/user_dual/skin.png':'3E6081A5F18CBFD9277B229718996E3D9583C8A072C0119E9ED8380484DE5265',
 'core/nes_zapper_v1.wasm':'C8D8824E5CAF727678C642E6B0539DEAA7C0084F33524D96779D90C7B5DA79EF'}
MIXIN={'required':True,'minVersion':'0.8','package':'cn.piq.fcarcade.mixin','compatibilityLevel':'JAVA_21',
 'client':['KeyboardHandlerMixin'],'injectors':{'defaultRequire':1}}

def classify(kind,old,new):
    removed=set(old)-set(new);require(removed==REMOVED[kind],'Unexpected removed entries '+kind+': '+str(removed))
    added=set(new)-set(old);changed={n for n in old.keys()&new.keys() if old[n]!=new[n]}
    for n in added|changed:
        if n in (META,MANIFEST):continue
        if kind=='fc' and n in RESOURCES:
            require(digest(new[n])==RESOURCES[n],'Unreviewed resource bytes '+n);continue
        if kind=='fc' and n=='piq_fc_keyboard.mixins.json':
            require(n in added and json.loads(new[n])==MIXIN,'Unexpected keyboard Mixin registration');continue
        stem=n.removesuffix('.class').split('$')[0]
        require(n.endswith('.class') and (stem in OLD[kind] if n in old else n in NEW_CLASSES[kind]),'Unexpected controls delta '+kind+': '+n)
    protected=[n for n in old if n.startswith(('assets/','data/','core/')) and n not in RESOURCES]
    require(all(n in new and old[n]==new[n] for n in protected),'Unrelated model, data or legacy core changed')
    if kind=='fc':
        require(all(n in new and digest(new[n])==sha for n,sha in RESOURCES.items()),'Missing reviewed resource')
        require(NEW_CLASSES[kind] <= set(new),'Missing reviewed control/core implementation')
        require('piq_fc_keyboard.mixins.json' in new and json.loads(new['piq_fc_keyboard.mixins.json'])==MIXIN,'Missing exact required Mixin config')
    return {'added':sorted(added),'changed':{n:{'before':digest(old[n]),'after':digest(new[n])} for n in sorted(changed)},
      'removed':sorted(removed),'unchanged_entries':sum(n in old and v==old[n] for n,v in new.items()),'protected_assets_data_cores_unchanged':len(protected)}

def metadata(kind,old,new):
    before=tomllib.loads(old[META].decode());after=tomllib.loads(new[META].decode())
    owner={'fc':'piq_fc_arcade','sfc':'piq_sfc_home','native':'piq_native_arcade'}[kind]
    for mod in before['mods']:
        if mod['modId']==owner:mod['version']=VERSIONS[kind]
    if kind=='fc':
        require('mixins' not in before,'Unexpected baseline mixins')
        before['mixins']=[{'config':'piq_fc_keyboard.mixins.json'}]
    else:
        for dep in before['dependencies'][owner]:
            if dep['modId']=='piq_fc_arcade':dep['versionRange']='[0.31.0-alpha.25,0.32.0)'
    require(before==after,'Unexpected metadata delta '+kind)
    previous={'fc':'0.31.0-alpha.24','sfc':'0.1.0-alpha.13','native':'0.1.0-alpha.8'}[kind]
    require(new[MANIFEST]==old[MANIFEST].replace(previous.encode(),VERSIONS[kind].encode()),'Unexpected manifest delta '+kind)

def plan():
    files={};entries={};report={'schema':'piq-controls25-stage-1','mods':{},'installed':False,'minecraft_started':False}
    for kind,path in BUILDS.items():
        source_sha,_,built=read_jar(path);built=clean(built)
        name,pin=PINNED[kind];old_sha,_,old=read_jar(BASE/name);old=clean(old);require(old_sha==pin,'Frozen baseline changed '+kind)
        if kind=='fc':
            for n,(source,frozen) in RESTORE.items():
                require(digest(old[n])==frozen and digest(built[n]) in (source,frozen),'Unexpected FC controller PNG');built[n]=old[n]
        if kind=='sfc':
            require(built[MANIFEST]==b'Manifest-Version: 1.0\r\nImplementation-Title: PIQ SFC Home\r\nImplementation-Version: 0.1.0-alpha.14\r\n\r\n','Unexpected thin SFC manifest')
            built=merged_sfc(built)
            built[MANIFEST]=old[MANIFEST].replace(b'0.1.0-alpha.13',b'0.1.0-alpha.14')
        metadata(kind,old,built)
        report['mods'][kind]={'source_sha256':source_sha,'baseline_sha256':old_sha,'scope':classify(kind,old,built)}
        entries[kind]=built;files[NAMES[kind]]=jar_bytes(built)
    helper=safe_path(BASE/'piq-native-arcade/runtime/piq-native-helper.jar',True).read_bytes()
    require(digest(helper)==HELPER_SHA,'Helper changed');files['piq-native-arcade/runtime/piq-native-helper.jar']=helper
    owners={}
    for kind,values in entries.items():
        for n in values:
            if n.endswith('.class'):require(n not in owners,'Duplicate class '+n);owners[n]=kind
    report.update(ok=True,unique_classes=len(owners),files={n:{'bytes':len(v),'sha256':digest(v)}for n,v in files.items()})
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
