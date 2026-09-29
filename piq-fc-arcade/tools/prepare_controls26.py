"""Freeze only reviewed controls/lightgun deltas from the delivered alpha25 candidate."""
import argparse,json,tomllib
from pathlib import Path
from freeze_fc_core_alpha19 import read_jar,safe_path,digest,require,META,MANIFEST
from freeze_fc_compact_alpha20 import RESTORE
from prepare_cabinet_multiplayer21 import clean,jar_bytes,merged_sfc,HELPER_SHA as OLD_HELPER_SHA

ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'piq-fc-arcade/build/review-controls25-v1'
VERSIONS={'fc':'0.31.0-alpha.26','sfc':'0.1.0-alpha.15','native':'0.1.0-alpha.10'}
PREVIOUS={'fc':'0.31.0-alpha.25','sfc':'0.1.0-alpha.14','native':'0.1.0-alpha.9'}
NAMES={'fc':'piq_fc_arcade-'+VERSIONS['fc']+'.jar','sfc':'piq_sfc-'+VERSIONS['sfc']+'.jar','native':'piq_native_arcade-'+VERSIONS['native']+'.jar'}
PINNED={
 'fc':('piq_fc_arcade-0.31.0-alpha.25.jar','A43F805B1AE6D18A839004DEE2BEB8AE9ABE9B2CF75A746965290B847A6A9125'),
 'sfc':('piq_sfc-0.1.0-alpha.14.jar','0D66F782EA8AE04A603EBC5550214E39362C8FBEF2581BD651B741F84D4D5F27'),
 'native':('piq_native_arcade-0.1.0-alpha.9.jar','D09ECAC8E3AB3F4AF79341517086000172DAAA22A3B847512042F8F072C17D5A')}
BUILDS={'fc':ROOT/'piq-fc-arcade/build/libs'/NAMES['fc'],
 'sfc':ROOT/'piq-sfc-home/build/libs/piq_sfc_home-0.1.0-alpha.15.jar',
 'native':ROOT/'piq-native-arcade/build/libs'/NAMES['native']}
HELPER_PATH=ROOT/'piq-native-arcade/build/review-numbered-buttons26-v1/piq-native-helper.jar'
HELPER_SHA='20F6F3028D76DAEB01212D1808BE90E35BFB5429D1E06153B7D8B32DD73E943C'
# No blanket package allowance. These lists will be completed only after code review.
STEMS={'fc':{'cn/piq/retro/client/'+n for n in (
 'KeyboardConfig','KeyboardConfigStore','KeyboardInput','KeyboardControlState','KeyboardRouting',
 'KeyboardMappingState','ControlSettingsScreen','KeyboardPresentation','ControlHubLayout','KeyboardPresetScreen')},
 'sfc':set(),'native':set()}
STEMS['fc'].update('cn/piq/fcarcade/'+n for n in ('mixin/KeyMappingStateAccess',
 'registry/ModItems','registry/CreativeTabCatalog','home/HomeZapperItem','home/HomeZapperAim',
 'layout/ZapperAimGeometry','layout/ZapperPoseLayout','client/zapper/ZapperArmPoseParameters',
 'client/zapper/ZapperInputState','client/zapper/ZapperClient','client/zapper/ZapperItemRenderer',
 'client/ClientArcadeEvents'))
STEMS['fc'].update('cn/piq/fcarcade/'+n for n in (
 'ArcadeSessionPayload','ArcadeFramePayload','ArcadeHistoryPayload','FcNetwork',
 'session/LockstepState','session/LockstepInputRun','session/LockstepTimeline',
 'server/ServerArcadeSessions','client/ClientNesWorker','client/ClientArcadeSession',
 'session/NesCoreVariant','session/ZapperInput','session/ZapperInputQueue',
 'home/ZapperBinding','home/ZapperData','home/HomeZapperService',
 'ArcadeZapperInputPayload','ArcadeZapperSessionPayload'))
STEMS['native'].update('cn/piq/nativearcade/bridge/'+n for n in ('BridgeProtocol','NativeProcessSession','NativeArcadeButtons'))
REMOVED={'fc':{'cn/piq/retro/client/ControlSettingsScreen$1.class','cn/piq/retro/client/KeyboardInput$1.class'},'sfc':set(),'native':set()}
RESOURCE_SHA={
 'assets/piq_fc_arcade/models/item/zapper/body.json':'57877F01B43BE62C548097856052A0B833DB9740929A850C22017942BDDDDF1E',
 'assets/piq_fc_arcade/models/item/zapper/trigger.json':'3A3AF4EA4290D68BB178BCF02AB47A088F0638F535E626F7C64C74617F25AF3F',
 'assets/piq_fc_arcade/textures/item/zapper/skin.png':'AB5C925B7BD21AD2CBBFCC96A0F38CC4FE6C4E9013BCE57DF8F866E9EA16167F',
 'assets/piq_fc_arcade/models/item/fc_zapper.json':'DF645D82F64276D6EB3511B33CA8C8D5373DE7D0D5AC8C1C31B98B490E07DED3',
 'assets/piq_fc_arcade/lang/zh_cn.json':'C8B72873F4A8843A5636C96747B5CA63BFE4A0075A587E06355380659C4A8D7C',
 'assets/piq_fc_arcade/lang/en_us.json':'5ABE2604EFAFAAC56292826540B5BD90E4A6EEE5B2E6CDB28B4BC1F9D23DD605',
 'META-INF/piq-fc-controller-enumextensions.json':'D5F080C6D24EC77E583AD79158AC46033F1ED3595D47DD9D0EAC2721863972C0'}
MIXIN={'required':True,'minVersion':'0.8','package':'cn.piq.fcarcade.mixin','compatibilityLevel':'JAVA_21',
 'client':['KeyboardHandlerMixin','KeyMappingStateAccess'],'injectors':{'defaultRequire':1}}

def metadata(kind,old,new):
    before=tomllib.loads(old[META].decode());after=tomllib.loads(new[META].decode())
    owner={'fc':'piq_fc_arcade','sfc':'piq_sfc_home','native':'piq_native_arcade'}[kind]
    for mod in before['mods']:
        if mod['modId']==owner:mod['version']=VERSIONS[kind]
    if kind!='fc':
        for dep in before['dependencies'][owner]:
            if dep['modId']=='piq_fc_arcade':dep['versionRange']='[0.31.0-alpha.26,0.32.0)'
    require(before==after,'Unexpected metadata delta '+kind)
    require(new[MANIFEST]==old[MANIFEST].replace(PREVIOUS[kind].encode(),VERSIONS[kind].encode()),'Unexpected manifest delta '+kind)

def classify(kind,old,new):
    removed=set(old)-set(new);require(removed==REMOVED[kind],'Unexpected removed '+kind+': '+str(sorted(removed)))
    added=set(new)-set(old);changed={n for n in old.keys()&new.keys()if old[n]!=new[n]}
    for n in added|changed:
        if n in (META,MANIFEST):continue
        if kind=='fc'and n in RESOURCE_SHA:
            require(digest(new[n])==RESOURCE_SHA[n],'Unreviewed resource '+n);continue
        if kind=='fc'and n=='piq_fc_keyboard.mixins.json':
            require(json.loads(new[n])==MIXIN,'Wrong Mixin configuration');continue
        stem=n.removesuffix('.class').split('$')[0]
        require(n.endswith('.class')and stem in STEMS[kind],'Unreviewed delta '+kind+': '+n)
    protected=[n for n in old if n.startswith(('assets/','data/','core/'))and n not in RESOURCE_SHA]
    require(all(n in new and old[n]==new[n]for n in protected),'Unrelated assets/data/core changed')
    if kind=='fc':
        require(all(n in new and digest(new[n])==sha for n,sha in RESOURCE_SHA.items()),'Missing approved asset')
        require(json.loads(new['piq_fc_keyboard.mixins.json'])==MIXIN,'Missing Mixin configuration')
    return {'added':{n:digest(new[n])for n in sorted(added)},
      'changed':{n:{'before':digest(old[n]),'after':digest(new[n])}for n in sorted(changed)},
      'removed':sorted(removed),'protected_unchanged':len(protected),'unchanged_entries':sum(n in old and old[n]==v for n,v in new.items())}

def plan():
    files={};entries={};report={'schema':'piq-controls26-stage-1','mods':{},'installed':False,'minecraft_started':False}
    for kind,path in BUILDS.items():
        source_sha,_,built=read_jar(path);built=clean(built)
        name,pin=PINNED[kind];old_sha,_,old=read_jar(BASE/name);old=clean(old);require(old_sha==pin,'Baseline changed '+kind)
        if kind=='fc':
            for n,(source,frozen)in RESTORE.items():
                require(digest(old[n])==frozen and digest(built[n])in(source,frozen),'Unexpected FC texture');built[n]=old[n]
        if kind=='sfc':
            require(built[MANIFEST]==b'Manifest-Version: 1.0\r\nImplementation-Title: PIQ SFC Home\r\nImplementation-Version: 0.1.0-alpha.15\r\n\r\n','Unexpected SFC manifest')
            built=merged_sfc(built);built[MANIFEST]=old[MANIFEST].replace(PREVIOUS[kind].encode(),VERSIONS[kind].encode())
        metadata(kind,old,built)
        report['mods'][kind]={'source_sha256':source_sha,'baseline_sha256':old_sha,'scope':classify(kind,old,built)}
        entries[kind]=built;files[NAMES[kind]]=jar_bytes(built)
    old_helper=safe_path(BASE/'piq-native-arcade/runtime/piq-native-helper.jar',True).read_bytes()
    require(digest(old_helper)==OLD_HELPER_SHA,'Baseline helper changed')
    helper=safe_path(HELPER_PATH,True).read_bytes()
    require(digest(helper)==HELPER_SHA,'Unreviewed numbered-button helper');files['piq-native-arcade/runtime/piq-native-helper.jar']=helper
    report['helper']={'path':str(HELPER_PATH),'sha256':HELPER_SHA,'baseline_sha256':OLD_HELPER_SHA}
    owners={}
    for kind,values in entries.items():
        for n in values:
            if n.endswith('.class'):require(n not in owners,'Duplicate class '+n);owners[n]=kind
    report.update(ok=True,unique_classes=len(owners),files={n:{'bytes':len(v),'sha256':digest(v)}for n,v in files.items()})
    return files,report

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--check-only',action='store_true');a=p.parse_args()
    out=safe_path(a.output);require(out.is_relative_to(ROOT)and not out.exists(),'Refuse existing/outside output')
    files,report=plan()
    if not a.check_only:
        out.mkdir(parents=True)
        for n,raw in files.items():
            path=safe_path(out/n);path.parent.mkdir(parents=True,exist_ok=True)
            with path.open('xb')as f:f.write(raw)
            require(path.read_bytes()==raw,'Stage readback mismatch')
        with (out/'stage-verification.json').open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'output':str(out),'files':report['files'],'unique_classes':report['unique_classes']}))
if __name__=='__main__':main()
