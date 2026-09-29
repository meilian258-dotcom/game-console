"""Explicit source preflight or final-JAR-only snapshot admission/codec QA. Never starts Minecraft or a native core."""
import argparse,json,os,shutil,tempfile
from pathlib import Path
import verify_retro_alpha19 as q

ROOT=Path(__file__).resolve().parents[1]
OWNED=['CabinetBackends','CabinetSyncPolicy','CabinetSyncRecoveryWindow','CabinetSyncNetwork','CabinetSynchronizer','CabinetRooms','CabinetSyncSettings']
TESTS=['CabinetSyncPolicyTest','CabinetSyncRecoveryWindowTest','CabinetSyncGateTest','CabinetSyncStateTest','CabinetSyncTimelineTest']
SOURCE_TESTS=['CabinetSnapshotSyncSourceTest','CabinetSingleSeatSourceTest']

def check(fc,source=False):
    digest=q.digest(fc.read_bytes())
    with tempfile.TemporaryDirectory(prefix='piq-snapshot33-') as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir();jar=tmp/'fc.jar';shutil.copyfile(fc,jar)
        assert q.digest(jar.read_bytes())==digest
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        preferred=[p for p in q.CACHE.glob('org.ow2.asm/*/9.8/*/*.jar') if '-sources' not in p.name and '-javadoc' not in p.name]
        cp=os.pathsep.join(map(str,[out,*preferred,jar,q.MC,resources,*q.dependencies()]))
        args=tmp/'cp.args';args.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        names=TESTS+(SOURCE_TESTS if source else [])
        sources=[ROOT/'src/main/java/cn/piq/fcarcade/cabinet'/(name+'.java') for name in OWNED] if source else []
        tests=[ROOT/'src/test/java/cn/piq/fcarcade/cabinet'/(name+'.java') for name in names]
        probes=[ROOT/'tools/qa/CabinetRoomTestRunner.java',ROOT/'tools/qa/CabinetSnapshotSync33Probe.java']
        fenced={str(p.relative_to(ROOT)):q.digest(p.read_bytes()) for p in [*sources,*tests,*probes]}
        compile_log=q.run([q.JAVA/'javac.exe','@'+str(args),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources,*tests,*probes],ROOT)
        behavior_log=q.run([q.JAVA/'java.exe','@'+str(args),'CabinetRoomTestRunner',*['cn.piq.fcarcade.cabinet.'+name for name in names]],ROOT)
        wire_log=q.run([q.JAVA/'java.exe','@'+str(args),'cn.piq.fcarcade.cabinet.CabinetSnapshotSync33Probe',out if source else jar],ROOT)
        behavior=q.parse_last_json(behavior_log);wire=q.parse_last_json(wire_log)
        assert wire['ok'] and wire['actual_outer_codecs'] and wire['snapshot_chunks']==136
        assert fenced=={str(p.relative_to(ROOT)):q.digest(p.read_bytes()) for p in [*sources,*tests,*probes]},'QA source fence changed'
        if not source:assert all(not(out/'cn/piq/fcarcade/cabinet'/(name+'.class')).exists() for name in OWNED)
        result={'ok':True,'mode':'source-preflight-real-api' if source else 'final-jar-only','production_compiled':source,
                'jars':{'fc':{'path':str(fc.resolve()),'sha256':digest}},'behavior':behavior,'wire':wire,'sources':fenced,
                'logs':{'compile':compile_log,'behavior':behavior_log,'wire':wire_log},
                'limits':['No Minecraft world, socket, native emulator or two-computer play is started.',
                          'The 3,334,118-byte state is synthetic diagnostic bytes; real native restore is independently tested.',
                          'Pure admission policy and existing state/timeline/gates are executed; server world authorization wiring is reviewed separately.']}
    assert q.digest(fc.read_bytes())==digest
    return result

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--fc',required=True,type=Path);p.add_argument('--report',required=True,type=Path);p.add_argument('--source',action='store_true');a=p.parse_args()
    assert not a.report.exists(),'Report must be a new file';result=check(a.fc,a.source);a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8') as f:json.dump(result,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(a.report),'tests':result['behavior']['passed_tests'],'wire':result['wire']}))
if __name__=='__main__':main()
