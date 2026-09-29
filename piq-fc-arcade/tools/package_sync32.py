"""Package a frozen matching cohort and final-JAR evidence; no install or live-world writes."""
import argparse,io,json,tomllib,zipfile
from pathlib import Path
from build_sync32 import ROOT,NAMES,inputs
from freeze_fc_core_alpha19 import safe_path,digest,require,read_jar,META

LEGACY={
 'native':(ROOT/'piq-fc-arcade/build/review-controls30-v1/piq_native_arcade-0.1.0-alpha.10.jar','F4011CBDE8DC3F3F3FA44FD03077638421C7D3334B33A55AFFB0E78C740C2453'),
 'gba':(ROOT/'piq-gba/build/server-v2-1/piq_gba-0.1.0-alpha.2.jar','7311F33C5DC8827EAA92672FF897295A4CAB62E9BD5BC0CC09372734D46D2F10')}
REQUIRED={'cohort-final.json':('fc','sfc','native','gba'),'cabinet-sync-final.json':('fc',),'shared-game-final.json':('fc',),'sfc-core-final.json':('fc','sfc'),'sfc-workers-final.json':('fc','sfc'),'sfc-watch-final.json':('fc','sfc'),'sfc-multiplayer-final.json':('fc','sfc')}
SOURCE_EXCLUDED={'.nes','.sfc','.smc','.gba','.gb','.gbc','.zip','.7z','.rar','.dll','.exe','.so','.dylib','.sav','.srm','.state','.bin'}
def zip_bytes(files):
    buffer=io.BytesIO()
    with zipfile.ZipFile(buffer,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for name,raw in sorted(files.items()):
            info=zipfile.ZipInfo(name,(2026,9,12,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,raw)
    return buffer.getvalue()
def main():
    p=argparse.ArgumentParser();p.add_argument('--stage',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    stage=safe_path(a.stage);output=safe_path(a.output);verification=output.with_suffix('.verification.json')
    require(stage.is_relative_to(ROOT/'piq-fc-arcade/build') and stage.is_dir(),'Frozen workspace stage required')
    require(output.parent==safe_path(ROOT/'制作Mod/03-街机模拟') and output.suffix=='.zip' and not output.exists() and not verification.exists(),'New delivery ZIP required')
    fence={};files={};mods={};hashes={}
    def take(path):
        path=safe_path(path);raw=path.read_bytes();fence[path]=digest(raw);return raw
    witness_raw=take(stage/'build-witness.json');w=json.loads(witness_raw);require(w['ok'] and w['schema']=='piq-sync32-build-1' and inputs()==w['inputs'],'Source/build witness mismatch')
    for name,sha in w['test_xml'].items():require(digest(take(ROOT/name))==sha,'Test XML changed')
    for kind,name in NAMES.items():
        raw=take(stage/name);sha=digest(raw);require(sha==w['mods'][kind]['sha256'] and not w['mods'][kind]['removed'],'Frozen candidate mismatch');files['mods/'+name]=raw;hashes[kind]=sha;mods[kind]=stage/name
    for kind,(path,pin) in LEGACY.items():
        raw=take(path);require(digest(raw)==pin,'Unchanged companion hash mismatch');files['mods/'+path.name]=raw;hashes[kind]=pin;mods[kind]=path
    owners={};ids={}
    for kind,path in mods.items():
        _,_,entries=read_jar(path);meta=tomllib.loads(entries[META].decode())
        for mod in meta['mods']:require(mod['modId'] not in ids,'Duplicate mod ID');ids[mod['modId']]=mod['version']
        for name in entries:
            if name.endswith('.class'):require(name not in owners,'Duplicate class owner '+name);owners[name]=kind
    require(ids=={'piq_fc_arcade':'0.31.0-alpha.32','piq_sfc_arcade':'0.2.0-alpha.6','piq_sfc_home':'0.1.0-alpha.19','piq_native_arcade':'0.1.0-alpha.10','piq_gba':'0.1.0-alpha.2'},'Unexpected cohort')
    evidence={}
    for name,kinds in REQUIRED.items():
        raw=take(stage/'checks'/name);record=json.loads(raw)
        passed=record.get('ok') is True
        if name=='sfc-multiplayer-final.json':
            passed=record.get('passed') is True and record.get('actual',{}).get('passed') is True and record.get('appliance_protocol',{}).get('ok') is True
        require(passed and record.get('mode')=='final-jar-only' and record.get('production_compiled') is False,'Non-final evidence '+name)
        bound={k:v['sha256'].upper() for k,v in record.get('jars',{}).items()}
        if name=='shared-game-final.json':bound['fc']=record['sha256'].upper()
        for kind in kinds:require(bound.get(kind)==hashes[kind],'Wrong final JAR in '+name+' '+kind)
        files['checks/'+name]=raw;evidence[name]=digest(raw)
    common=json.loads(files['checks/cohort-final.json'])['common']
    require(common['actual_fml_reader'] and common['client_and_native_load_attempts']==0,'Missing actual FML/common-only validation')
    files['checks/build-witness.json']=witness_raw
    # Include any additional final evidence, but only when its declared candidates match this stage.
    for path in (stage/'checks').glob('*.json'):
        if path.name in REQUIRED:continue
        raw=take(path);record=json.loads(raw)
        if record.get('ok') is True and record.get('mode')=='final-jar-only' and record.get('production_compiled') is False:
            bound={k:v['sha256'].upper() for k,v in record.get('jars',{}).items()}
            if bound and all(hashes.get(k)==sha for k,sha in bound.items()):files['checks/'+path.name]=raw
    guide=ROOT/'piq-fc-arcade/design/同步优化-alpha32-使用说明.md';files['先看这里.md']=take(guide)
    files['docs/实现约定.md']=take(ROOT/'piq-fc-arcade/design/sync32-contract.md')
    for path in [ROOT/'piq-fc-arcade/design/cabinet-sync32-handoff-20260912.md',ROOT/'piq-sfc-home/design/sync19-handoff.md',ROOT/'piq-fc-arcade/design/工作笔记-alpha32-20260912.md']:
        files['docs/'+path.name]=take(path)
    study=ROOT/'piq-native-arcade/design/native-sync-study'
    for name in ('README-20260912.md','kof97-v3.json','mslug2-v1.json'):
        raw=take(study/name)
        if name.endswith('.json'):
            r=json.loads(raw);require(r['ok'] and r['sync_qualified'] is False and r['production_modified'] is False and r['source_roms_unchanged'],'MAME study gate changed')
        files['checks/mame-study/'+name]=raw
    source={}
    # Reproducible project source/assets, excluding user data, build caches, runtimes and test ROM binaries.
    for name,sha in w['inputs'].items():
        raw=take(ROOT/name);require(digest(raw)==sha,'Source drift '+name)
        if Path(name).suffix.lower() in SOURCE_EXCLUDED:continue
        source[name]=raw
    for project in ('piq-native-arcade','piq-gba','piq-sfc-arcade'):
        base=ROOT/project
        for directory in ('src','gradle'):
            for path in (base/directory).rglob('*'):
                if path.is_file() and path.suffix.lower() not in SOURCE_EXCLUDED:source[path.relative_to(ROOT).as_posix()]=take(path)
        for name in ('LICENSE','build.gradle','gradle.properties','settings.gradle','gradlew.bat'):
            if (base/name).is_file():source[(base/name).relative_to(ROOT).as_posix()]=take(base/name)
    for project in ('piq-fc-arcade','piq-sfc-home','piq-native-arcade'):
        for path in (ROOT/project/'tools').rglob('*'):
            if path.is_file() and path.suffix in ('.py','.java') and '__pycache__' not in path.parts:source[path.relative_to(ROOT).as_posix()]=take(path)
    source['阅读说明.txt']=('这是本工作区对应源码与验证工具快照，不是完整离线构建环境。\n'
      '未包含Gradle依赖缓存、历史冻结JAR、模拟器构建工具链或用户ROM/BIOS/存档。\n'
      '部分构建/封包脚本按固定SHA引用工作区旧基线；离开原工作区需先准备相同依赖，勿修改校验绕过。\n'
      '交付包mods目录才是已验证的最终运行文件，报告绑定其SHA-256。\n').encode('utf-8')
    files['source/对应源码与验证工具.zip']=zip_bytes(source)
    summary={'schema':'piq-sync32-delivery-1','ok':True,'mods_sha256':hashes,'cohort':ids,'tests':w['tests'],'final_evidence':evidence,
      'protected_assets':{k:v['protected_unchanged'] for k,v in w['mods'].items()},'unique_classes':len(owners),
      'protocols':['cabinet-room-4','cabinet-game-1','cabinet-sync-1','sfc-repair-1'],'mame_local_sync_enabled':False,
      'installed':False,'uploaded':False,'published':False,'live_minecraft_multiplayer_tested':False,'rom_bios_saves_included':False,
      'runtime_dlls_included':False,'legacy_companions_unchanged':True,'source_files':len(source)}
    files['checks/summary.json']=(json.dumps(summary,ensure_ascii=False,indent=2)+'\n').encode()
    files['SHA256.txt']=''.join(digest(raw)+'  '+name+'\n' for name,raw in sorted(files.items())).encode()
    require(inputs()==w['inputs'] and all(digest(path.read_bytes())==sha for path,sha in fence.items()),'Packaging input changed')
    archive=zip_bytes(files)
    with output.open('xb') as f:f.write(archive)
    with zipfile.ZipFile(output) as z:
        require(z.testzip() is None and set(z.namelist())==set(files) and len(z.namelist())==len(files),'ZIP CRC/inventory failed')
        for name,raw in files.items():require(z.read(name)==raw,'ZIP readback mismatch')
    require(digest(output.read_bytes())==digest(archive),'Archive readback failed')
    require(inputs()==w['inputs'] and all(digest(path.read_bytes())==sha for path,sha in fence.items()),'Inputs changed during ZIP write')
    summary.update(path=str(output),bytes=len(archive),sha256=digest(archive),files={n:digest(raw) for n,raw in files.items()})
    with verification.open('x',encoding='utf-8') as f:json.dump(summary,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'path':str(output),'bytes':len(archive),'sha256':digest(archive),'mods':hashes},ensure_ascii=False))
if __name__=='__main__':main()
