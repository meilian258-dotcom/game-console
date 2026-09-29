"""Freeze FC21/SFC9/Native7 into a NEW QA directory. No install, ROMs, or world operations."""
import argparse,io,json,sys,tomllib,zipfile
from pathlib import Path
from freeze_fc_core_alpha19 import read_jar,safe_path,digest,require,META,MANIFEST
from freeze_fc_compact_alpha20 import RESTORE

ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'制作Mod/03-街机模拟'
BASELINES={
 'fc':(BASE/'PIQ-FC街机/alpha20-compact-vanilla-ui/piq_fc_arcade-0.31.0-alpha.20.jar','CAC47CAE12E7183A76CF8874C6E8B8C4242435C01759C797EE78A5BD8A80C864'),
 'sfc':(BASE/'PIQ-SFC家用/0.1.0-alpha.8/piq_sfc-0.1.0-alpha.8.jar','3C678DC03EF9479564BC3F7005210E9DE868792A8D29DD0CDD62CAB3C79BC1E5'),
 'native':(BASE/'PIQ原生街机/0.1.0-alpha.6/piq_native_arcade-0.1.0-alpha.6.jar','B503F5BE9F0C1DAA3640CE1926CCAA268577A76FE709CEFBFA05D9FFEEF5E422')}
BUILDS={
 'fc':ROOT/'piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.21.jar',
 'sfc':ROOT/'piq-sfc-home/build/libs/piq_sfc_home-0.1.0-alpha.9.jar',
 'native':ROOT/'piq-native-arcade/build/libs/piq_native_arcade-0.1.0-alpha.7.jar'}
NAMES={'fc':'piq_fc_arcade-0.31.0-alpha.21.jar','sfc':'piq_sfc-0.1.0-alpha.9.jar','native':'piq_native_arcade-0.1.0-alpha.7.jar'}
HELPER=ROOT/'piq-native-arcade/build/native-four-port7/runtime/piq-native-helper.jar'
HELPER_SHA='229268989AD4E263277FDF0BD5D49E59F69EEB3E980B948DD1D3628182437120'
LANG_KEYS={'item.piq_fc_arcade.cabinet_link_cable','tooltip.piq_fc_arcade.cabinet_link_cable'}
FC_CLASSES={'FcArcadeMod','registry/ModItems','registry/CreativeTabCatalog','world/FcArcadeBlock','world/DualCabinetBlock','world/DualCabinetPartBlock','world/LegacyFcArcadeBlock'}

def clean(entries):return {n:v for n,v in entries.items()if not n.endswith('/')}
def jar_bytes(entries):
    output=io.BytesIO()
    with zipfile.ZipFile(output,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9)as archive:
        for name,value in sorted(entries.items()):
            info=zipfile.ZipInfo(name,(2026,9,11,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;archive.writestr(info,value)
    return output.getvalue()
def allowed_class(kind,name):
    if not name.endswith('.class'):return False
    stem=name[:-6].split('$')[0]
    if kind=='fc':return (name.startswith('cn/piq/fcarcade/cabinet/') or
        name.startswith('cn/piq/fcarcade/client/cabinet/CabinetClientBackends') or
        any(name.startswith('cn/piq/fcarcade/client/cabinet/'+p)for p in ('CabinetAssignmentHistory','CabinetPeerInputs','CabinetPcmBuffer','CabinetMedia')) or
        stem=='cn/piq/retro/api/RetroEmulator' or stem.removeprefix('cn/piq/fcarcade/')in FC_CLASSES)
    if kind=='sfc':return name.startswith('cn/piq/sfchome/client/cabinet/')or stem=='cn/piq/sfchome/SfcHomeMod'
    return name.startswith('cn/piq/nativearcade/bridge/')or stem in ('cn/piq/nativearcade/NativeArcadeMod','cn/piq/nativearcade/client/NativeCabinetBackend')
def classify(kind,old,new):
    added=set(new)-set(old);removed=set(old)-set(new)
    require(not removed,'Unexpected removed '+kind+' entries: '+repr(sorted(removed)))
    changed={n for n in old.keys()&new.keys()if old[n]!=new[n]}
    for name in added|changed:
        if name in (META,MANIFEST)or allowed_class(kind,name):continue
        if kind=='fc'and name in ('assets/piq_fc_arcade/lang/zh_cn.json','assets/piq_fc_arcade/lang/en_us.json'):
            before=json.loads(old[name]);after=json.loads(new[name]);require(set(after)==set(before)|LANG_KEYS,'Unexpected language keys')
            require(all(after[k]==v for k,v in before.items()),'Old translation changed');continue
        if kind=='fc'and name=='assets/piq_fc_arcade/models/item/cabinet_link_cable.json':
            require(name in added and json.loads(new[name])=={'parent':'minecraft:item/generated','textures':{'layer0':'minecraft:item/string'}},'Unexpected cable asset');continue
        raise ValueError('Unexpected change '+kind+': '+name)
    assets=[n for n in old if n.startswith('assets/')and '/lang/'not in n]
    require(all(new[n]==old[n]for n in assets),'Existing models/textures/core changed')
    return {'added':sorted(added),'changed':{n:{'before':digest(old[n]),'after':digest(new[n])}for n in sorted(changed)},'removed':[],
            'unchanged_entries':sum(n in old and old[n]==v for n,v in new.items()),'existing_non_language_assets_unchanged':len(assets)}
def metadata(kind,entries):
    value=tomllib.loads(entries[META].decode('utf-8'));versions={m['modId']:m['version']for m in value['mods']}
    expected={'fc':{'piq_fc_arcade':'0.31.0-alpha.21'},'sfc':{'piq_sfc_arcade':'0.2.0-alpha.6','piq_sfc_home':'0.1.0-alpha.9'},'native':{'piq_native_arcade':'0.1.0-alpha.7'}}[kind]
    require(versions==expected and len(value['mods'])==len(expected),'Wrong mod version or duplicate Mod owner')
    if kind!='fc':
        owner='piq_sfc_home'if kind=='sfc'else 'piq_native_arcade';fc=[d for d in value['dependencies'][owner]if d['modId']=='piq_fc_arcade']
        require(len(fc)==1 and all(fc[0].get(k)==v for k,v in {'versionRange':'[0.31.0-alpha.21,0.32.0)','ordering':'AFTER','type':'required','side':'BOTH'}.items()),'FC21 dependency not enforced')
    return versions
def merged_sfc(home):
    sys.path.insert(0,str(ROOT/'piq-sfc-home/tools'));import merge_sfc_addon as merger
    core_sha,core=merger.read_archive(merger.FROZEN_CORE);require(core_sha==merger.FROZEN_SHA,'Frozen core SHA changed')
    merger.ownership(home,'sfchome');merger.ownership(core,'sfcarcade');require(set(home)&set(core)==merger.SYNTHESIZED,'Duplicate SFC entries')
    c=merger.parsed_metadata(core[META],'piq_sfc_arcade');h=merger.parsed_metadata(home[META],'piq_sfc_home')
    def body(raw):text=raw.decode('utf-8');return text[text.index('[[mods]]'):].strip()+'\n'
    combined=('modLoader="javafml"\nloaderVersion="[4,)"\nlicense="GPL-3.0-or-later"\n\n'+body(core[META])+'\n'+body(home[META])).encode('utf-8')
    expected=dict(c);expected['mods']=c['mods']+h['mods'];expected['dependencies']=c['dependencies']|h['dependencies'];require(tomllib.loads(combined.decode())==expected,'Metadata changed during merge')
    result={n:v for n,v in core.items()if n not in merger.SYNTHESIZED};result.update({n:v for n,v in home.items()if n not in merger.SYNTHESIZED})
    result[META]=combined;result[MANIFEST]=b'Manifest-Version: 1.0\r\nImplementation-Title: PIQ SFC\r\nImplementation-Version: 0.1.0-alpha.9\r\n\r\n'
    return result
def plan():
    plans={};report={'schema':'piq-two-cabinet-multiplayer21-stage-1','installed':False,'minecraft_started':False,'commercial_roms_or_bios_added':False,'mods':{}}
    for kind,path in BUILDS.items():
        build_sha,_,built=read_jar(path);built=clean(built);base,expected=BASELINES[kind];base_sha,_,old=read_jar(base);old=clean(old);require(base_sha==expected,'Baseline SHA mismatch '+kind)
        if kind=='fc':
            for name,(source_sha,frozen_sha)in RESTORE.items():
                require(digest(old[name])==frozen_sha and digest(built[name])in (source_sha,frozen_sha),'Unexpected controller PNG');built[name]=old[name]
        if kind=='sfc':built=merged_sfc(built)
        report['mods'][kind]={'source_sha256':build_sha,'baseline_sha256':base_sha,'versions':metadata(kind,built),'scope':classify(kind,old,built)}
        plans[NAMES[kind]]=jar_bytes(built)
    owners={}
    for name,raw in plans.items():
        with zipfile.ZipFile(io.BytesIO(raw))as jar:
            for path in jar.namelist():
                if path.endswith('.class'):require(path not in owners,'Duplicate class owner '+path);owners[path]=name
    require(digest(safe_path(HELPER,True).read_bytes())==HELPER_SHA,'Native helper SHA changed')
    plans['piq-native-arcade/runtime/piq-native-helper.jar']=HELPER.read_bytes()
    report['unique_classes']=len(owners);report['files']={name:{'bytes':len(raw),'sha256':digest(raw)}for name,raw in plans.items()};report['ok']=True
    return plans,report
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--check-only',action='store_true');args=parser.parse_args()
    output=safe_path(args.output);require(output.is_relative_to(ROOT),'Output must stay in workspace');require(not output.exists(),'Refusing to overwrite previous stage')
    files,report=plan()
    if not args.check_only:
        output.mkdir(parents=True)
        for name,raw in files.items():
            path=safe_path(output/name);path.parent.mkdir(parents=True,exist_ok=True)
            with path.open('xb')as stream:stream.write(raw)
            require(path.read_bytes()==raw,'Staged byte mismatch')
        with (output/'stage-verification.json').open('x',encoding='utf-8')as stream:json.dump(report,stream,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'output':str(output),'files':report['files'],'unique_classes':report['unique_classes'],'installed':False},ensure_ascii=True))
if __name__=='__main__':main()
