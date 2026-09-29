"""Freeze matched FC37 runtime onboarding / quiet UI; preserve protocols, models and old releases."""
import argparse, json, os, subprocess, sys, tomllib
from pathlib import Path
import xml.etree.ElementTree as ET
import build_audit34 as b
from freeze_fc_core_alpha19 import read_jar, safe_path, digest, require, META, MANIFEST
from freeze_fc_compact_alpha20 import RESTORE
from prepare_cabinet_multiplayer21 import clean, jar_bytes

ROOT=b.ROOT
NAMES={'fc':'piq_fc_arcade-0.31.0-alpha.37.jar','native':'piq_native_arcade-0.1.0-alpha.13.jar',
       'sfc':'piq_sfc-0.1.0-alpha.21.jar','gba':'piq_gba-0.1.0-alpha.5.jar'}
BASE={
 'fc':('piq-fc-arcade/build/review-furniture36-v1/piq_fc_arcade-0.31.0-alpha.36.jar','FA1FE823BFAE92C41299386D687AEF1CE98BC2A89195A171FFF35E852DB1E043'),
 'native':('piq-fc-arcade/build/review-audit34-v1/piq_native_arcade-0.1.0-alpha.12.jar','DC67BB2705EC9C8724F0300CC6CC1361D2C8FA93428974DDDDE858CF59F26867'),
 'sfc':('piq-fc-arcade/build/review-audit34-v1/piq_sfc-0.1.0-alpha.20.jar','9A131723FCF5DF3FF2E807B345EE29C9E93BDA3D209F1BD3DED6681144C17004'),
 'gba':('piq-fc-arcade/build/review-audit34-v1/piq_gba-0.1.0-alpha.4.jar','0D51438C93E0D365B5A19A86B22B7AC94A4316EA9D3F5A3C3B3E13311322EEA1')}
OLDVERS={'fc':'0.31.0-alpha.36','native':'0.1.0-alpha.12','sfc':'0.1.0-alpha.20','gba':'0.1.0-alpha.4'}
VERS={'piq_fc_arcade':'0.31.0-alpha.37','piq_native_arcade':'0.1.0-alpha.13','piq_sfc_home':'0.1.0-alpha.21',
      'piq_sfc_arcade':'0.2.0-alpha.7','piq_gba':'0.1.0-alpha.5'}
STEMS={
 'fc':{'cn/piq/fcarcade/'+s for s in '''FcArcadeMod furniture/FurnitureRegistry registry/ModCreativeTabs
 client/HomeApplianceClient client/ClientArcadeEvents client/ClientArcadeSession client/ClientNesWorker
 client/ClientRomTransfers client/ClientSkinManager
 client/cabinet/CabinetMenuScreen client/cabinet/CabinetClientBackends client/cabinet/CabinetSharedGames
 client/cabinet/CabinetJoinClient client/cabinet/CabinetSyncWorker'''.split()}|{'cn/piq/retro/client/KeyboardInput'},
 'native':{'cn/piq/nativearcade/'+s for s in ['client/NativeArcadeClient','bridge/NativeProcessSession']},
 'sfc':{'cn/piq/sfchome/client/'+s for s in ['SfcHomeClient','SfcJoinClient','SfcRepairClient','cabinet/SfcCabinetSession']},
 'gba':{'cn/piq/gba/'+s for s in ['client/GbaHandheldClient','client/GbaHandheldScreen','bridge/GbaProcessSession']}}
NEW_PREFIX=('cn/piq/fcarcade/runtime/','cn/piq/fcarcade/client/runtime/',
 'cn/piq/fcarcade/client/ui/DeviceNotices','cn/piq/fcarcade/client/ui/DeviceNoticePolicy')

def merge_sfc():
    cs,core=b.load(ROOT/'piq-sfc-arcade/build/libs/piq_sfc_arcade-0.2.0-alpha.7.jar')
    hs,home=b.load(ROOT/'piq-sfc-home/build/libs/piq_sfc_home-0.1.0-alpha.21.jar')
    b.ownership(core,'sfcarcade');b.ownership(home,'sfchome')
    require(set(core)&set(home)=={META,MANIFEST},'SFC shared class/resource ownership')
    def body(raw):
        text=raw.decode().replace('\r\n','\n');require(text.count('[[mods]]')==1,'SFC table count')
        return text[text.index('[[mods]]'):].rstrip()+'\n'
    result={n:v for source in [core,home] for n,v in source.items() if n not in (META,MANIFEST)}
    result[META]=('modLoader="javafml"\nloaderVersion="[4,)"\nlicense="GPL-3.0-or-later"\n\n'+body(core[META])+'\n'+body(home[META])).encode()
    result[MANIFEST]=b'Manifest-Version: 1.0\r\nImplementation-Title: PIQ SFC\r\nImplementation-Version: 0.1.0-alpha.21\r\n\r\n'
    return result,{'core_compiled_sha256':cs,'home_compiled_sha256':hs}

def validate(kind,old,new):
    require(not(set(old)-set(new)),'Deleted historical entries '+kind)
    changed=sorted(n for n in old if old[n]!=new[n]);added=sorted(set(new)-set(old))
    for n in changed+added:
        allowed=n in (META,MANIFEST) or n.endswith('.class') and (
            n[:-6].split('$')[0] in STEMS[kind] or kind=='fc' and n not in old and n.startswith(NEW_PREFIX))
        require(allowed,'Unreviewed '+kind+' difference '+n)
    expected=tomllib.loads(old[META].decode())
    for mod in expected['mods']:mod['version']=VERS[mod['modId']]
    if kind!='fc':
        for owner,deps in expected['dependencies'].items():
            if owner=='piq_sfc_arcade':continue # bundled core7 metadata remains untouched
            for dep in deps:
                if dep['modId']=='piq_fc_arcade':dep['versionRange']='[0.31.0-alpha.37,0.32)' if kind=='gba' else '[0.31.0-alpha.37,0.32.0)'
    require(tomllib.loads(new[META].decode())==expected,'Unexpected metadata '+kind)
    version=VERS[{'fc':'piq_fc_arcade','native':'piq_native_arcade','sfc':'piq_sfc_home','gba':'piq_gba'}[kind]]
    require(new[MANIFEST]==old[MANIFEST].replace(OLDVERS[kind].encode(),version.encode()),'Unexpected manifest '+kind)
    protected=[n for n in old if n.startswith(('assets/','data/','core/'))]
    require(all(new[n]==old[n] for n in protected),'Protected model/recipe/core/lang changed '+kind)
    return dict(changed=changed,added=added,removed=[],protected_unchanged=len(protected))

def main():
    sys.stdout.reconfigure(encoding='utf-8');sys.stderr.reconfigure(encoding='utf-8')
    p=argparse.ArgumentParser();p.add_argument('--output',required=True,type=Path);a=p.parse_args()
    out=safe_path(a.output);require(out.is_relative_to(ROOT/'piq-fc-arcade/build') and not out.exists(),'New freeze folder required')
    before=b.inputs();logs={}
    for project in ['piq-fc-arcade','piq-native-arcade','piq-sfc-home']:
        print('Checking '+project,flush=True)
        r=subprocess.run(['cmd.exe','/d','/c','gradlew.bat','check','jar','--offline'],cwd=ROOT/project,
            env=dict(os.environ,JAVA_HOME=str(b.JAVA)),capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=900)
        logs[project]=r.stdout+'\n'+r.stderr;print(logs[project][-7000:],flush=True);require(r.returncode==0,'Check failed '+project)
    require(before==b.inputs(),'Source inputs changed during compile; retry after handoff')
    report=dict(schema='piq-runtime37-build-1',ok=True,inputs=before,mods={},tests={},test_xml={},
                installed=False,published=False,minecraft_started=False,real_network_tested=False)
    archives={};staged={}
    for kind in NAMES:
        path,pin=BASE[kind];sha,old=b.load(ROOT/path);require(sha==pin,'Baseline drift '+kind)
        if kind=='sfc':new,details=merge_sfc()
        elif kind=='gba':
            new,details=b.compile_gba(staged[NAMES['fc']],old)
            new[MANIFEST]=old[MANIFEST].replace(b'0.1.0-alpha.4',b'0.1.0-alpha.5')
        else:
            project='piq-fc-arcade' if kind=='fc' else 'piq-native-arcade'
            compiled,new=b.load(ROOT/project/'build/libs'/NAMES[kind]);details={'compiled_sha256':compiled}
        if kind=='fc':
            for n,(draft,frozen) in RESTORE.items():
                require(digest(old[n])==frozen and digest(new[n]) in (draft,frozen),'Historical controller changed')
                new[n]=old[n]
        details.update(validate(kind,old,new));raw=jar_bytes(new);archives[kind]=new;staged[NAMES[kind]]=raw
        report['mods'][kind]=dict(details,name=NAMES[kind],bytes=len(raw),sha256=digest(raw),baseline_sha256=sha)
    ids={};owners={}
    for kind,entries in archives.items():
        for n in entries:
            if n.endswith('.class'):require(n not in owners,'Duplicate class '+n);owners[n]=kind
        for mod in tomllib.loads(entries[META].decode())['mods']:
            require(mod['modId'] not in ids,'Duplicate mod id');ids[mod['modId']]=mod['version']
    require(ids==VERS,'Final matched version set');report['mod_ids']=ids;report['duplicate_classes']=0
    for project,minimum,allowed_skips in [('piq-fc-arcade',1502,8),('piq-native-arcade',80,0),('piq-sfc-home',311,0)]:
        counts=dict(tests=0,errors=0,failures=0,skipped=0)
        for xml in sorted((ROOT/project/'build/test-results/test').glob('TEST-*.xml')):
            raw=xml.read_bytes();root=ET.fromstring(raw);report['test_xml'][xml.relative_to(ROOT).as_posix()]=digest(raw)
            for k in counts:counts[k]+=int(root.attrib[k])
        require(counts['tests']>=minimum and counts['failures']==counts['errors']==0 and counts['skipped']<=allowed_skips,'Tests '+project+str(counts))
        report['tests'][project]=counts
    require(before==b.inputs(),'Fence changed before freeze');out.mkdir(parents=True)
    for name,raw in staged.items():
        with (out/name).open('xb') as f:f.write(raw)
        require(read_jar(out/name)[0]==digest(raw),'Readback mismatch')
    for project,log in logs.items():
        with (out/(project+'-check.log')).open('x',encoding='utf-8') as f:f.write(log)
    with (out/'build-witness.json').open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({k:report[k] for k in ('ok','mods','tests','mod_ids')},ensure_ascii=False))

if __name__=='__main__':main()
