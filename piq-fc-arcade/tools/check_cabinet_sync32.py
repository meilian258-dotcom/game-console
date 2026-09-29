"""Bounded cabinet sync behavior and real outer packet codecs. --source-pure is explicitly non-final QA."""
import argparse,json,os,shutil,tempfile
from pathlib import Path
import verify_retro_alpha19 as q
ROOT=Path(__file__).resolve().parents[1]
TESTS=['cabinet.CabinetSyncStateTest','cabinet.CabinetSyncTimelineTest','cabinet.CabinetSyncGateTest','client.cabinet.CabinetSyncWorkerTest']
PURE=['cabinet/CabinetSyncState','cabinet/CabinetSyncTimeline','cabinet/CabinetSyncGate','client/cabinet/CabinetSyncWorker']
def check(fc,source_pure=False):
    digest=q.digest(fc.read_bytes())
    with tempfile.TemporaryDirectory(prefix='piq-sync32-')as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir();stage=tmp/'production.jar';shutil.copyfile(fc,stage)
        assert q.digest(stage.read_bytes())==digest
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        preferred=[p for p in q.CACHE.glob('org.ow2.asm/*/9.8/*/*.jar')if '-sources'not in p.name and '-javadoc'not in p.name]
        cp=os.pathsep.join(map(str,[out,*preferred,stage,q.MC,resources,*q.dependencies()]))
        arg=tmp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        tests=[ROOT/'src/test/java/cn/piq/fcarcade'/(name.replace('.','/')+'.java')for name in TESTS]
        probes=[ROOT/'tools/qa/CabinetRoomTestRunner.java']
        if not source_pure:probes.append(ROOT/'tools/qa/CabinetSync32Probe.java')
        sources=[ROOT/'src/main/java/cn/piq/fcarcade'/(name+'.java')for name in PURE]if source_pure else[]
        compile_log=q.run([q.JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources,*tests,*probes],ROOT)
        behavior_log=q.run([q.JAVA/'java.exe','@'+str(arg),'CabinetRoomTestRunner',*['cn.piq.fcarcade.'+name for name in TESTS]],ROOT)
        behavior=q.parse_last_json(behavior_log);assert behavior['passed_tests']>=29
        wire_log='';wire=None
        if not source_pure:
            wire_log=q.run([q.JAVA/'java.exe','@'+str(arg),'cn.piq.fcarcade.cabinet.CabinetSync32Probe',stage],ROOT)
            wire=q.parse_last_json(wire_log);assert wire['ok']and wire['actual_outer_codecs']and wire['production_origin']=='final-jar-only'
            assert all(not(out/('cn/piq/fcarcade/'+name+'.class')).exists()for name in PURE)
        result={'ok':True,'mode':'explicit-source-pure-qa'if source_pure else'final-jar-only','production_compiled':source_pure,
                'jars':{'fc':{'path':str(fc.resolve()),'sha256':digest}},'behavior':behavior,'wire':wire,
                'minecraft_world_or_socket_started':False,'native_core_started':False,
                'sources':{str(p.relative_to(ROOT)):q.digest(p.read_bytes())for p in [*sources,*tests,*probes]},
                'logs':{'compile':compile_log,'behavior':behavior_log,'wire':wire_log},
                'limits':['Worker tests execute real production worker against a deterministic diagnostic Java core; separate SFC Wasm probes establish the actual emulator path.',
                          'Real cached Minecraft/NeoForge outer packet codecs are used without starting a world, graphical client, socket, or native core.',
                          'No remote-play latency, public server, installed instance, private ROM, or real hardware behavior is claimed.']}
    assert q.digest(fc.read_bytes())==digest
    return result
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--fc',required=True,type=Path);p.add_argument('--report',required=True,type=Path);p.add_argument('--source-pure',action='store_true');a=p.parse_args()
    assert not a.report.exists(),'Exclusive new report required';result=check(a.fc,a.source_pure);a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(result,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(a.report),'tests':result['behavior']['passed_tests'],'wire':result['wire']}))
if __name__=='__main__':main()
