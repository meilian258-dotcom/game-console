"""Package verified local test files and a separate corresponding-source companion. No installation."""
import argparse,json,re,shutil,zipfile,hashlib
from pathlib import Path
from build_sync33 import ROOT,inputs,NAMES
from freeze_fc_core_alpha19 import require
LAB=ROOT/'piq-native-snapshot-lab'
PINNED={
 'mods/piq_sfc-0.1.0-alpha.19.jar':(ROOT/'piq-fc-arcade/build/review-sync32-v1/piq_sfc-0.1.0-alpha.19.jar','DC50C9378C83E38907C259373CE92B06FAD403B3779D0E4B6772D94AA0343BC3'),
 'mods/piq_gba-0.1.0-alpha.2.jar':(ROOT/'piq-gba/build/server-v2-1/piq_gba-0.1.0-alpha.2.jar','7311F33C5DC8827EAA92672FF897295A4CAB62E9BD5BC0CC09372734D46D2F10'),
 'piq-native-arcade/runtime-snapshot-v1/piqneogeo_libretro.dll':(LAB/'candidates/rtc-audio-lua-v2/piqneogeo_libretro.dll','E8F435903332AC80468769604779295A6965046DC25D4706A583D14D91C35201'),
 'piq-native-arcade/runtime-snapshot-v1/piq-snapshot-helper.jar':(LAB/'build/standalone/piq-snapshot-helper.jar','F175B7CB60B37A95E1F5B2ED5FB48CE066FC1B6AE1ECD8089B8E6F957EF76C97'),
 'piq-native-arcade/runtime-snapshot-v1/jna-5.14.0.jar':(LAB/'build/standalone/jna-5.14.0.jar','34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6')}
SOURCE=LAB/'vendor/mame-4fc9a9312baaf34963847f884961ad9793fbbc1d.zip'
SOURCE_SHA='72D9472975173C1CE43201945F19D3866F9DA3EA51F67131C1134D66D7C6B22D'

def sha(file):
    h=hashlib.sha256()
    with file.open('rb')as f:
        for b in iter(lambda:f.read(1048576),b''):h.update(b)
    return h.hexdigest().upper()

def copy(source,target):
    require(not target.exists(),'Refuse overwrite '+str(target));before=sha(source)
    target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
    require(sha(source)==before==sha(target),'Copy identity mismatch')

def zipper(folder,target):
    require(not target.exists(),'New archive required')
    records={p.relative_to(folder).as_posix():{'sha256':sha(p),'bytes':p.stat().st_size}for p in sorted(folder.rglob('*'))if p.is_file()}
    with (folder/'SHA256.json').open('x',encoding='utf-8')as f:json.dump(records,f,ensure_ascii=False,indent=2)
    records['SHA256.json']={'sha256':sha(folder/'SHA256.json'),'bytes':(folder/'SHA256.json').stat().st_size}
    with zipfile.ZipFile(target,'x',zipfile.ZIP_DEFLATED,compresslevel=6)as z:
        for name in records:z.write(folder/name,name,compress_type=zipfile.ZIP_STORED if name.endswith('-source.zip')else zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(target)as z:
        require(z.testzip()is None and len(z.infolist())==len(records),'ZIP CRC/count failed')
        for name,row in records.items():require(hashlib.sha256(z.read(name)).hexdigest().upper()==row['sha256'],'ZIP content mismatch '+name)
    return {'path':str(target),'sha256':sha(target),'bytes':target.stat().st_size,'files':len(records)}

def main():
    p=argparse.ArgumentParser();p.add_argument('--stage',type=Path,required=True);p.add_argument('--name',required=True)
    p.add_argument('--evidence',type=Path,action='append',required=True);a=p.parse_args()
    require(re.fullmatch(r'[\w\u4e00-\u9fff-]{1,100}',a.name),'Simple output name required')
    stage=a.stage.resolve(strict=True);witness=json.loads((stage/'build-witness.json').read_text(encoding='utf-8'))
    require(witness['ok'] and witness['inputs']==inputs(),'Build source fence changed')
    expected={k:row['sha256']for k,row in witness['mods'].items()}
    all_evidence=[]
    for file in a.evidence:
        data=json.loads(file.read_text(encoding='utf-8'))
        require(data.get('ok') and data.get('mode')=='final-jar-only' and data.get('production_compiled')is False,'Evidence is not passing final-JAR QA '+str(file))
        require(set(data.get('jars',{}))&set(expected),'Evidence does not identify a final JAR '+str(file))
        for key,row in data.get('jars',{}).items():
            if key in expected:require(row['sha256']==expected[key],'Stale JAR evidence '+str(file))
        all_evidence.append((file,data))
    require(any(d.get('schema')=='piq-sync33-final-1' for _,d in all_evidence),'Missing common/final codec QA')
    require(any(d.get('schema')=='piq-native-snapshot-boundary-1' for _,d in all_evidence),'Missing real native boundary QA')
    require(any(d.get('schema')=='piq-native-cabinet-worker-1' and d.get('real_cabinet_sync_worker') for _,d in all_evidence),'Missing actual Native CabinetSyncWorker QA')
    require(any(d.get('actual',{}).get('generic_actual_factory_openSync') for _,d in all_evidence),'Missing unchanged SFC final worker regression')
    require(sha(SOURCE)==SOURCE_SHA,'Corresponding MAME source changed')
    for source,pin in PINNED.values():require(sha(source)==pin,'Frozen runtime/addon changed')
    parent=(ROOT/'制作Mod/03-街机模拟').resolve();folder=parent/a.name;source_folder=parent/(a.name+'-核心源码')
    for target in [folder,source_folder,folder.with_suffix('.zip'),source_folder.with_suffix('.zip')]:require(not target.exists(),'Output already exists '+str(target))
    folder.mkdir();source_folder.mkdir()
    for kind,name in NAMES.items():
        file=stage/name;require(sha(file)==expected[kind],'Frozen mod changed');copy(file,folder/'mods'/name)
    for name,(file,_)in PINNED.items():copy(file,folder/name)
    copy(ROOT/'piq-fc-arcade/design/同步实验-alpha33-使用说明.md',folder/'使用说明.md')
    copy(stage/'build-witness.json',folder/'verification/build-witness.json')
    for file,_ in all_evidence:copy(file,folder/'verification'/file.name)
    # The matching full upstream source and exact applied patches are a separately offered download.
    copy(SOURCE,source_folder/'source/mame-4fc9a931-source.zip')
    for project in ['piq-fc-arcade','piq-retro-platform','piq-native-arcade']:
        base=ROOT/project
        for sub in ['src','helper/src','gradle']:
            for file in sorted((base/sub).rglob('*')):
                if file.is_file():copy(file,source_folder/'source'/project/file.relative_to(base))
        for name in ['build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat','LICENSE']:
            if (base/name).is_file():copy(base/name,source_folder/'source'/project/name)
    for sub in ['patches','tools','helper','build-records']:
        for file in sorted((LAB/sub).rglob('*')):
            if file.is_file() and '__pycache__'not in file.parts and file.suffix in {'.java','.py','.md','.patch','.json','.log','.cmd','.cpp','.h','.c'}:
                # Historical logs are useful provenance; omit large failed command logs.
                if file.suffix=='.log' and file.name!='compile-20260912-041210.log':continue
                copy(file,source_folder/'source/lab'/file.relative_to(LAB))
    for sub in ['backend/src','ui/src']:
        for file in sorted((LAB/sub).rglob('*')):
            if file.is_file() and file.suffix=='.java':copy(file,source_folder/'source/lab'/file.relative_to(LAB))
    for name in ['build_sync33.py','package_sync33.py','freeze_fc_core_alpha19.py','freeze_fc_compact_alpha20.py','prepare_cabinet_multiplayer21.py','verify_compact_ui_alpha20.py','verify_retro_alpha19.py','verify_device_ui_alpha18.py','check_fc_cartridge_workbench.py','check_confirmation_screens.py']:
        copy(ROOT/'piq-fc-arcade/tools'/name,source_folder/'source/piq-fc-arcade/tools'/name)
    for name in ['official-build-inputs.json','extracted-build-inputs-v2.json']:copy(LAB/'vendor'/name,source_folder/'source/lab/vendor'/name)
    copy(LAB/'candidates/rtc-audio-lua-v2/build.json',source_folder/'verification/native-build.json')
    copy(ROOT/'piq-native-arcade/build/native-step-prototype-v1/piq-native-step-helper.jar',source_folder/'source/prerequisites/piq-native-step-helper.jar')
    for file in [ROOT/'piq-fc-arcade/design/sync33-contract.md',ROOT/'piq-fc-arcade/design/同步实验-alpha33-使用说明.md']:
        copy(file,source_folder/file.name)
    copy(ROOT/'piq-fc-arcade/design/同步实验-alpha33-源码说明.md',source_folder/'源码说明.md')
    # Licence notices travel with binary and full source, not just with the optional source download.
    with zipfile.ZipFile(SOURCE)as z:
        prefix=z.namelist()[0].split('/')[0]+'/'
        for entry in z.infolist():
            name=entry.filename.removeprefix(prefix)
            if not entry.is_dir() and (name=='COPYING' or name.startswith('docs/legal/')):
                require(not any(part in ['..','.','']for part in name.split('/')),'Unsafe legal entry')
                for dest in [folder,source_folder]:
                    target=dest/'licenses/MAME'/name;target.parent.mkdir(parents=True,exist_ok=True)
                    with target.open('xb')as f:f.write(z.read(entry))
    with zipfile.ZipFile(LAB/'build/standalone/jna-5.14.0.jar')as z:
        for dest in [folder,source_folder]:
            target=dest/'licenses/JNA-LICENSE';target.parent.mkdir(parents=True,exist_ok=True)
            with target.open('xb')as f:f.write(z.read('META-INF/LICENSE'))
    copy(ROOT/'piq-native-arcade/LICENSE',folder/'licenses/Native-LICENSE')
    require(witness['inputs']==inputs(),'Source changed during packaging')
    result={'ok':True,'installed':False,'published':False,'includes_rom_bios_saves':False,'test_package':zipper(folder,folder.with_suffix('.zip')),'corresponding_source':zipper(source_folder,source_folder.with_suffix('.zip'))}
    report=parent/(a.name+'.verification.json')
    with report.open('x',encoding='utf-8')as f:json.dump(result,f,ensure_ascii=False,indent=2)
    print(json.dumps(result,ensure_ascii=False))
if __name__=='__main__':main()
