"""Freeze FC33/Native11. Historical artifacts and every model/core asset are immutable."""
import argparse,json,os,subprocess,tomllib,xml.etree.ElementTree as ET
from pathlib import Path
from freeze_fc_core_alpha19 import read_jar,safe_path,digest,require,META,MANIFEST
from freeze_fc_compact_alpha20 import RESTORE
from prepare_cabinet_multiplayer21 import clean,jar_bytes

ROOT=Path(__file__).resolve().parents[2]
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot')
NAMES={'fc':'piq_fc_arcade-0.31.0-alpha.33.jar','native':'piq_native_arcade-0.1.0-alpha.11.jar'}
PROJECTS={'fc':'piq-fc-arcade','native':'piq-native-arcade'}
BASE={
 'fc':(ROOT/'piq-fc-arcade/build/review-sync32-v1/piq_fc_arcade-0.31.0-alpha.32.jar','090781E86821275A82E786494B9F98CF905B8CC8EED1478C3F06356ADDAFD16B'),
 'native':(ROOT/'piq-fc-arcade/build/review-controls30-v1/piq_native_arcade-0.1.0-alpha.10.jar','F4011CBDE8DC3F3F3FA44FD03077638421C7D3334B33A55AFFB0E78C740C2453')}
FC_STEMS='''cabinet/CabinetBackends cabinet/CabinetSyncCore cabinet/CabinetSyncPolicy cabinet/CabinetSyncRecoveryWindow
cabinet/CabinetRooms cabinet/CabinetSynchronizer cabinet/CabinetSyncSettings cabinet/CabinetSyncNetwork
client/cabinet/CabinetBackend client/cabinet/CabinetClientBackends client/cabinet/CabinetSyncWorker
client/cabinet/CabinetSyncClient client/cabinet/CabinetSyncUploads client/cabinet/CabinetMenuScreen client/cabinet/CabinetSyncSettingsScreen'''
NATIVE_STEMS='''NativeArcadeMod NativeSnapshotProfile client/NativeCabinetBackend client/NativeSnapshotCore
bridge/NativeProcessSession bridge/NativeStepProtocol bridge/NativeStepSession
bridge/NativeSnapshotState bridge/NativeSnapshotWorkspace bridge/NativeSnapshotSession'''
STEMS={'fc':{'cn/piq/fcarcade/'+n for n in FC_STEMS.split()},'native':{'cn/piq/nativearcade/'+n for n in NATIVE_STEMS.split()}}

def inputs():
    paths=[]
    for project in ['piq-fc-arcade','piq-retro-platform','piq-native-arcade']:
        base=ROOT/project
        for folder in ['src','gradle','helper/src']:paths+=list((base/folder).rglob('*'))
        paths += [base/n for n in ['gradle.properties','build.gradle','settings.gradle','gradlew.bat','LICENSE']]
    return {p.relative_to(ROOT).as_posix():digest(p.read_bytes()) for p in sorted(set(paths)) if p.is_file()}

def main():
    p=argparse.ArgumentParser();p.add_argument('--output',required=True,type=Path);a=p.parse_args()
    out=safe_path(a.output);require(out.is_relative_to(ROOT/'piq-fc-arcade/build') and not out.exists(),'New build stage required')
    before=inputs();env=dict(os.environ,JAVA_HOME=str(JAVA))
    for project in PROJECTS.values():
        subprocess.run(['cmd.exe','/d','/c','gradlew.bat','check','jar','--offline'],cwd=ROOT/project,env=env,check=True)
    require(before==inputs(),'Sources changed during build')
    report={'schema':'piq-sync33-build-1','ok':True,'inputs':before,'mods':{},'tests':{},'test_xml':{},'installed':False,'real_network_tested':False};staged={}
    for kind,project in PROJECTS.items():
        baseline,pin=BASE[kind];sha,_,old=read_jar(baseline);old=clean(old);require(sha==pin,'Frozen baseline identity '+kind)
        compiled,_,new=read_jar(ROOT/project/'build/libs'/NAMES[kind]);new=clean(new)
        if kind=='fc':
            for name,(source,frozen) in RESTORE.items():
                require(digest(old[name])==frozen and digest(new[name]) in (source,frozen),'Historical controller source changed');new[name]=old[name]
        removed=set(old)-set(new)
        permitted_removed={'cn/piq/fcarcade/client/cabinet/CabinetSyncClient$Upload.class'} if kind=='fc' else set()
        require(removed==permitted_removed,'Unexpected removed entries: '+str(sorted(removed)))
        added=set(new)-set(old);changed={n for n in old.keys()&new.keys() if old[n]!=new[n]}
        for name in added|changed:
            require(name in (META,MANIFEST) or name.endswith('.class') and name[:-6].split('$')[0] in STEMS[kind],'Unreviewed '+kind+' entry '+name)
        expected=tomllib.loads(old[META].decode());actual=tomllib.loads(new[META].decode())
        owner='piq_fc_arcade' if kind=='fc' else 'piq_native_arcade'
        oldversion='0.31.0-alpha.32' if kind=='fc' else '0.1.0-alpha.10'
        version='0.31.0-alpha.33' if kind=='fc' else '0.1.0-alpha.11'
        for mod in expected['mods']:
            if mod['modId']==owner:mod['version']=version
        if kind=='native':
            for dep in expected['dependencies'][owner]:
                if dep['modId']=='piq_fc_arcade':dep['versionRange']='[0.31.0-alpha.33,0.32.0)'
        require(expected==actual,'Unexpected metadata changes '+kind)
        require(new[MANIFEST]==old[MANIFEST].replace(oldversion.encode(),version.encode()),'Unexpected manifest '+kind)
        protected=[n for n in old if n.startswith(('assets/','data/','core/'))]
        require(all(new[n]==old[n] for n in protected),'Protected model/asset/core changed '+kind)
        counts={n:0 for n in ['tests','failures','errors','skipped']}
        xmls=list((ROOT/project/'build/test-results/test').glob('TEST-*.xml'));require(xmls,'No JUnit evidence')
        for file in xmls:
            xml=ET.fromstring(file.read_bytes());report['test_xml'][file.relative_to(ROOT).as_posix()]=digest(file.read_bytes())
            for name in counts:counts[name]+=int(xml.attrib[name])
        require(counts['tests']>=(1363 if kind=='fc' else 40) and counts['failures']==counts['errors']==0,'Regression failed '+kind)
        require(counts['skipped']==(7 if kind=='fc' else 0),'Unexpected skipped tests '+kind)
        raw=jar_bytes(new);staged[NAMES[kind]]=raw;report['tests'][kind]=counts
        report['mods'][kind]={'sha256':digest(raw),'compiled_sha256':compiled,'baseline_sha256':sha,'added':sorted(added),'changed':sorted(changed),'removed':sorted(removed),'protected_unchanged':len(protected)}
    require(before==inputs(),'Sources changed during freeze');out.mkdir(parents=True)
    for name,raw in staged.items():
        with (out/name).open('xb')as f:f.write(raw)
        require((out/name).read_bytes()==raw,'Frozen readback mismatch')
    with (out/'build-witness.json').open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'output':str(out),'mods':report['mods'],'tests':report['tests']},ensure_ascii=False))
if __name__=='__main__':main()
