"""Freeze reviewed appliance interaction changes; never install or overwrite a prior stage."""
import argparse,json,tomllib
from pathlib import Path
from freeze_fc_core_alpha19 import read_jar,safe_path,digest,require,META,MANIFEST
from freeze_fc_compact_alpha20 import RESTORE
from prepare_cabinet_multiplayer21 import clean,jar_bytes,merged_sfc

ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'piq-fc-arcade/build/review-controls26-v2'
FC_BASE=ROOT/'制作Mod/03-街机模拟/街机按钮动画修正-alpha27-补丁-20260911/mods/piq_fc_arcade-0.31.0-alpha.27.jar'
PINNED={
 'fc':(FC_BASE,'5C50F63325B7B5027C31FBFFD422E8DA114AA4BF6BF092182027847100A21883'),
 'sfc':(BASE/'piq_sfc-0.1.0-alpha.15.jar','EC4BAF1CE1819BAA85229B2873AA9B97A70B19340E6B7EA23CB43FD479BC63C6'),
 'native':(BASE/'piq_native_arcade-0.1.0-alpha.10.jar','F4011CBDE8DC3F3F3FA44FD03077638421C7D3334B33A55AFFB0E78C740C2453')}
BUILDS={'fc':ROOT/'piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.28.jar',
 'sfc':ROOT/'piq-sfc-home/build/libs/piq_sfc_home-0.1.0-alpha.16.jar'}
NAMES={'fc':'piq_fc_arcade-0.31.0-alpha.28.jar','sfc':'piq_sfc-0.1.0-alpha.16.jar','native':'piq_native_arcade-0.1.0-alpha.10.jar'}
HELPER='piq-native-arcade/runtime/piq-native-helper.jar'
HELPER_SHA='20F6F3028D76DAEB01212D1808BE90E35BFB5429D1E06153B7D8B32DD73E943C'
FC_STEMS='''ArcadeHomeInputPayload ArcadeHomeReadyPayload ArcadeJoinApprovalPayload ArcadeJoinDecisionPayload ArcadeSessionPayload ClientArcadeSupport FcArcadeMod FcNetwork
client/ArcadeBlockScreenRenderer client/ClientArcadeEvents client/ClientArcadeSession client/HomeApplianceClient client/HomeHardwareRenderer client/HomeVideoDisplay client/NoSignalPattern client/zapper/ZapperStandRenderer
home/ApplianceControls home/ApplianceRay home/HomeApplianceControl home/HomeApplianceService home/HomeConsoleRuntime home/HomeControllerService home/HomeEndpointBlockEntity home/HomeHardware home/HomeRuntimeAuthority home/HomeSystems home/HomeTvBlockEntity home/HomeZapperService home/TelevisionState
home/ZapperDock home/ZapperStandBlock home/ZapperStandBlockEntity home/ZapperStandCableItem home/ZapperStandLinks home/ZapperStandOrigin home/ZapperStandService layout/ZapperStandGeometry
registry/CreativeTabCatalog registry/ModBlockEntities registry/ModBlocks registry/ModItems server/ServerArcadeSessions session/SessionRoster'''
FC_STEMS+=' world/FamicomConsoleBlock home/RetroTvBlock home/HomeTvPartBlock home/LargeLcdTvPartBlock home/WideLcdTvPartBlock home/SuborPartBlock'
SFC_STEMS='''SfcHomeMod world/SfcHomeConsoleBlock client/SfcControlGrantGate client/SfcHomeClient client/SfcJoinClient client/SfcPlayback client/SfcWatchClient client/SfcWatchPublisher client/SfcStartupProgress layout/SfcApplianceControls net/SfcHomeNetwork net/SfcJoinNetwork server/SfcHomeServer'''
STEMS={'fc':{'cn/piq/fcarcade/'+n for n in FC_STEMS.split()},'sfc':{'cn/piq/sfchome/'+n for n in SFC_STEMS.split()}}
LCD={'assets/piq_fc_arcade/models/block/'+n+'.json'for n in ('home_lcd_tv','home_large_lcd_tv','home_wide_lcd_tv')}
NEW_RESOURCES={'assets/piq_fc_arcade/'+n for n in ('blockstates/zapper_stand.json','models/block/zapper_stand/cable.json','models/block/zapper_stand/connector.json','models/block/zapper_stand/stand.json','models/item/zapper_stand.json','models/item/zapper_stand_cable.json')}
NEW_RESOURCES.add('data/piq_fc_arcade/loot_table/blocks/zapper_stand.json')
LANG={'assets/piq_fc_arcade/lang/'+n+'.json'for n in ('en_us','zh_cn')}

def classify(kind,old,new):
    removed=set(old)-set(new);require(not removed,'Removed entries: '+repr(sorted(removed)))
    added=set(new)-set(old);changed={n for n in old.keys()&new.keys()if old[n]!=new[n]}
    for name in added|changed:
        if name in (META,MANIFEST):continue
        if name.endswith('.class'):
            require(name[:-6].split('$')[0]in STEMS[kind],'Unreviewed class '+kind+': '+name);continue
        if kind=='fc'and name in NEW_RESOURCES:
            require(name in added,'Stand resource was not new');json.loads(new[name]);continue
        if kind=='fc'and name in LANG:
            before=json.loads(old[name]);after=json.loads(new[name]);require(set(before)<=set(after),'Removed translations')
            for key in set(after):
                if before.get(key)==after[key]:continue
                require('appliance_'in key or 'zapper_stand'in key or 'zapper_'in key or key in ('item.piq_fc_arcade.zapper_stand_cable','message.piq_fc_arcade.controller_wrong_console'),'Unreviewed translation '+key)
            continue
        if kind=='fc'and name in LCD:
            before=json.loads(old[name]);after=json.loads(new[name]);extra=after['elements'][-2:]
            require(len(extra)==2 and len(after['elements'])==len(before['elements'])+2,'Unexpected LCD element change')
            after['elements']=after['elements'][:-2];require(after==before,'Old LCD geometry changed')
            require(all(e.get('name')in ('音量增加','音量减少')for e in extra),'Unexpected LCD addition');continue
        raise ValueError('Unreviewed resource '+kind+': '+name)
    protected=[n for n in old if n.startswith(('assets/','data/','core/'))and n not in LANG|LCD]
    require(all(new.get(n)==old[n]for n in protected),'Unrelated assets or emulator changed')
    return {'added':{n:digest(new[n])for n in sorted(added)},'changed':{n:{'before':digest(old[n]),'after':digest(new[n])}for n in sorted(changed)},'removed':[],'protected_unchanged':len(protected)}

def plan():
    files={};archives={};report={'schema':'piq-appliance28-stage-1','ok':True,'mods':{},'installed':False,'minecraft_started':False}
    for kind in ('fc','sfc','native'):
        base,pin=PINNED[kind];sha,_,old=read_jar(base);old=clean(old);require(sha==pin,'Baseline changed '+kind)
        if kind=='native':
            files[NAMES[kind]]=base.read_bytes();archives[kind]=old
            report['mods'][kind]={'baseline_sha256':sha,'unchanged':True};continue
        source_sha,_,new=read_jar(BUILDS[kind]);new=clean(new)
        if kind=='fc':
            for name,(source,frozen)in RESTORE.items():
                require(digest(old[name])==frozen and digest(new[name])in(source,frozen),'Unreviewed controller texture');new[name]=old[name]
        else:
            new=merged_sfc(new);new[MANIFEST]=old[MANIFEST].replace(b'0.1.0-alpha.15',b'0.1.0-alpha.16')
        before=tomllib.loads(old[META].decode());after=tomllib.loads(new[META].decode());owner='piq_fc_arcade'if kind=='fc'else 'piq_sfc_home'
        version='0.31.0-alpha.28'if kind=='fc'else '0.1.0-alpha.16'
        for mod in before['mods']:
            if mod['modId']==owner:mod['version']=version
        if kind=='sfc':
            for dep in before['dependencies'][owner]:
                if dep['modId']=='piq_fc_arcade':dep['versionRange']='[0.31.0-alpha.28,0.32.0)'
        require(before==after,'Unexpected metadata '+kind)
        require(new[MANIFEST]==old[MANIFEST].replace(b'0.31.0-alpha.27'if kind=='fc'else b'0.1.0-alpha.15',version.encode()),'Unexpected manifest')
        report['mods'][kind]={'source_sha256':source_sha,'baseline_sha256':sha,'scope':classify(kind,old,new)}
        files[NAMES[kind]]=jar_bytes(new);archives[kind]=new
    helper=safe_path(BASE/HELPER,True).read_bytes();require(digest(helper)==HELPER_SHA,'Helper changed');files[HELPER]=helper
    classes={};mods={}
    for kind,entries in archives.items():
        for name in entries:
            if name.endswith('.class'):require(name not in classes,'Duplicate class '+name);classes[name]=kind
        for mod in tomllib.loads(entries[META].decode())['mods']:
            require(mod['modId']not in mods,'Duplicate Mod ID');mods[mod['modId']]=mod['version']
    require(mods=={'piq_fc_arcade':'0.31.0-alpha.28','piq_sfc_arcade':'0.2.0-alpha.6','piq_sfc_home':'0.1.0-alpha.16','piq_native_arcade':'0.1.0-alpha.10'},'Unexpected owners')
    sources={}
    for kind,project in (('fc',ROOT/'piq-fc-arcade'),('sfc',ROOT/'piq-sfc-home')):
        selected=[project/'src/main/java'/(stem+'.java')for stem in STEMS[kind]]+[project/'gradle.properties',project/'build.gradle']
        selected+=list((project/'src/main/templates').rglob('*'))
        if kind=='fc':selected+=[project/'src/main/resources'/name for name in LCD|LANG|NEW_RESOURCES]
        for path in selected:
            if path.is_file():sources[path.relative_to(ROOT).as_posix()]=digest(path.read_bytes())
    report['reviewed_source_snapshot']=dict(sorted(sources.items()))
    report.update(unique_classes=len(classes),mod_versions=mods,files={n:{'bytes':len(raw),'sha256':digest(raw)}for n,raw in files.items()})
    return files,report

def main():
    p=argparse.ArgumentParser();p.add_argument('--output',type=Path,required=True);p.add_argument('--check-only',action='store_true');a=p.parse_args()
    out=safe_path(a.output);require(out.is_relative_to(ROOT)and not out.exists(),'Refuse existing or external stage')
    files,report=plan()
    if not a.check_only:
        out.mkdir(parents=True)
        for name,raw in files.items():
            path=safe_path(out/name);path.parent.mkdir(parents=True,exist_ok=True)
            with path.open('xb')as f:f.write(raw)
            require(path.read_bytes()==raw,'Stage readback differs')
        with (out/'stage-verification.json').open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'output':str(out),'files':report['files'],'unique_classes':report['unique_classes']},ensure_ascii=True))
if __name__=='__main__':main()
