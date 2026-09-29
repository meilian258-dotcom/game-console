"""Build/freeze FC32 + merged SFC19 with source fences and exact protected-asset baselines."""
import argparse,json,os,subprocess,tomllib,xml.etree.ElementTree as ET
from pathlib import Path
from freeze_fc_core_alpha19 import read_jar,safe_path,digest,require,META,MANIFEST
from freeze_fc_compact_alpha20 import RESTORE
from prepare_cabinet_multiplayer21 import clean,jar_bytes,merged_sfc

ROOT=Path(__file__).resolve().parents[2]
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot')
NAMES={'fc':'piq_fc_arcade-0.31.0-alpha.32.jar','sfc':'piq_sfc-0.1.0-alpha.19.jar'}
BASE={
 'fc':(ROOT/'piq-fc-arcade/build/review-gba-server31-v2/piq_fc_arcade-0.31.0-alpha.31.jar','EB71CBF7F4FD3940C2B840F3E74629D0E925902EB4D4195C97973B5A8A874E58'),
 'sfc':(ROOT/'piq-fc-arcade/build/review-controls30-v1/piq_sfc-0.1.0-alpha.18.jar','7BDF3B5BAB4722B35D85303CD2F18C9A7BEA640B3DB7B00C3E9CAFF24E1B49DB')}
FC_STEMS='''FcArcadeMod cabinet/CabinetBackends cabinet/CabinetRoomLedger cabinet/CabinetRooms cabinet/CabinetRoomNetwork
cabinet/CabinetGameManifest cabinet/CabinetGameStore cabinet/CabinetSharedGameData cabinet/CabinetGameNetwork cabinet/CabinetSharedGameService cabinet/CabinetGameBudget cabinet/CabinetGameTransfer
cabinet/CabinetMediaSender cabinet/CabinetSyncCore cabinet/CabinetSyncGate cabinet/CabinetSynchronizer cabinet/CabinetSyncMode cabinet/CabinetSyncNetwork cabinet/CabinetSyncSender cabinet/CabinetSyncSettings cabinet/CabinetSyncState cabinet/CabinetSyncTimeline
client/cabinet/CabinetClientBackends client/cabinet/CabinetBackend client/cabinet/CabinetSharedGames client/cabinet/CabinetSyncClient client/cabinet/CabinetSyncWorker client/cabinet/CabinetSyncSettingsScreen
client/cabinet/CabinetMenuScreen client/cabinet/CabinetSetupScreen client/cabinet/CabinetMediaStream client/cabinet/CabinetMediaPolicy client/cabinet/CabinetMediaTuning client/cabinet/WatchMediaStream'''
SFC_STEMS='''SfcHomeMod client/SfcHomeClient client/SfcJoinClient client/SfcPlayback client/SfcExecutionCore client/SfcCheckpoints client/SfcRepairClient client/SfcWatchPublisher
client/cabinet/SfcCabinetProvider client/cabinet/SfcCabinetSyncCore net/SfcRepairNetwork server/SfcHomeServer server/SfcRepairLedger'''
STEMS={'fc':{'cn/piq/fcarcade/'+n for n in FC_STEMS.split()},'sfc':{'cn/piq/sfchome/'+n for n in SFC_STEMS.split()}}
def inputs():
    paths=[]
    for name in ('piq-fc-arcade','piq-retro-platform','piq-sfc-home'):
        project=ROOT/name;paths+=list((project/'src').rglob('*'))+list((project/'gradle').rglob('*'))
        paths += [project/n for n in ('gradle.properties','build.gradle','settings.gradle','gradlew.bat','LICENSE')]
    return {p.relative_to(ROOT).as_posix():digest(p.read_bytes()) for p in sorted(set(paths)) if p.is_file()}
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',required=True,type=Path);a=parser.parse_args()
    out=safe_path(a.output);require(out.is_relative_to(ROOT/'piq-fc-arcade/build') and not out.exists(),'New stage required')
    before=inputs();env=dict(os.environ,JAVA_HOME=str(JAVA))
    for name in ('piq-fc-arcade','piq-sfc-home'):
        subprocess.run(['cmd.exe','/d','/c','gradlew.bat','check','jar','--offline'],cwd=ROOT/name,env=env,check=True)
    require(before==inputs(),'Source changed during full build; do not freeze')
    staged={};report={'schema':'piq-sync32-build-1','ok':True,'inputs':before,'mods':{},'tests':{},'test_xml':{},'installed':False,'real_network_tested':False}
    for kind,project in (('fc','piq-fc-arcade'),('sfc','piq-sfc-home')):
        baseline,pin=BASE[kind];sha,_,old=read_jar(baseline);old=clean(old);require(sha==pin,'Baseline changed '+kind)
        built=ROOT/project/'build/libs'/('piq_sfc_home-0.1.0-alpha.19.jar' if kind=='sfc' else NAMES[kind]);compiled,_,new=read_jar(built);new=clean(new)
        if kind=='fc':
            for name,(source,frozen) in RESTORE.items():
                require(digest(old[name])==frozen and digest(new[name]) in (source,frozen),'Unexpected historical controller asset');new[name]=old[name]
        else:
            new=merged_sfc(new);new[MANIFEST]=old[MANIFEST].replace(b'0.1.0-alpha.18',b'0.1.0-alpha.19')
        removed=set(old)-set(new);require(not removed,'Entries removed '+str(sorted(removed)))
        added=set(new)-set(old);changed={n for n in old.keys()&new.keys() if old[n]!=new[n]}
        for name in added|changed:
            require(name in (META,MANIFEST) or name.endswith('.class') and name[:-6].split('$')[0] in STEMS[kind],'Unreviewed change '+kind+': '+name)
        expected=tomllib.loads(old[META].decode());actual=tomllib.loads(new[META].decode());owner='piq_fc_arcade' if kind=='fc' else 'piq_sfc_home'
        for mod in expected['mods']:
            if mod['modId']==owner:mod['version']='0.31.0-alpha.32' if kind=='fc' else '0.1.0-alpha.19'
        if kind=='sfc':
            for dep in expected['dependencies'][owner]:
                if dep['modId']=='piq_fc_arcade':dep['versionRange']='[0.31.0-alpha.32,0.32.0)'
        require(expected==actual,'Metadata changes outside version/minimum core')
        require(new[MANIFEST]==old[MANIFEST].replace(b'0.31.0-alpha.31' if kind=='fc' else b'0.1.0-alpha.18',b'0.31.0-alpha.32' if kind=='fc' else b'0.1.0-alpha.19'),'Unexpected manifest')
        protected=[n for n in old if n.startswith(('assets/','data/','core/'))];require(all(new[n]==old[n] for n in protected),'Model, asset or core changed')
        counts={n:0 for n in ('tests','failures','errors','skipped')}
        xmls=list((ROOT/project/'build/test-results/test').glob('TEST-*.xml'));require(xmls,'No tests')
        for path in xmls:
            xml=ET.fromstring(path.read_bytes());report['test_xml'][path.relative_to(ROOT).as_posix()]=digest(path.read_bytes())
            for name in counts:counts[name]+=int(xml.attrib[name])
        require(counts['tests']>=(1305 if kind=='fc' else 311) and counts['failures']==counts['errors']==0 and counts['skipped']==(7 if kind=='fc' else 0),'Full regression failed '+kind)
        raw=jar_bytes(new);staged[NAMES[kind]]=raw;report['tests'][kind]=counts
        report['mods'][kind]={'sha256':digest(raw),'compiled_sha256':compiled,'baseline_sha256':sha,'added':sorted(added),'changed':sorted(changed),'removed':[],'protected_unchanged':len(protected)}
    require(before==inputs(),'Source changed during freeze');out.mkdir(parents=True)
    for name,raw in staged.items():
        with (out/name).open('xb') as f:f.write(raw)
        require((out/name).read_bytes()==raw,'Stage readback mismatch')
    with (out/'build-witness.json').open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'output':str(out),'mods':report['mods'],'tests':report['tests']},ensure_ascii=False))
if __name__=='__main__':main()
