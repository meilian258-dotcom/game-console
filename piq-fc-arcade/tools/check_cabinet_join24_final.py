"""Final-JAR-only narrow consent/input/actual-wire audit; not full world or game startup."""
import argparse,json,os,tempfile
from pathlib import Path
import verify_retro_alpha19 as q
ROOT=Path(__file__).resolve().parents[1]

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--fc',type=Path,required=True);parser.add_argument('--report',type=Path,required=True);args=parser.parse_args()
    if args.report.exists():raise ValueError('Refusing to replace a report')
    final=args.fc.resolve(strict=True);digest=q.digest(final.read_bytes());deps=q.dependencies()
    resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
    resources=resources.resolve(strict=True);resources_sha=q.digest(resources.read_bytes())
    testpaths=['cabinet/CabinetJoinGateTest.java','client/cabinet/CabinetPromptGuardTest.java','client/cabinet/CabinetImmersiveInputTest.java','client/cabinet/CabinetInputLifecycleTest.java']
    tests=[ROOT/'src/test/java/cn/piq/fcarcade'/name for name in testpaths]
    probe=ROOT/'tools/qa/CabinetJoin24FinalProbe.java'
    with tempfile.TemporaryDirectory(prefix='piq-join24-final-')as folder:
        tmp=Path(folder);jar=tmp/'fc.jar';jar.write_bytes(final.read_bytes());out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir()
        cp=os.pathsep.join(map(str,[out,jar,q.MC,resources,*deps]));argfile=tmp/'compile.args';argfile.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        q.run([q.JAVA/'javac.exe','@'+str(argfile),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,probe,*tests],tmp)
        classes=[p.name for p in out.rglob('*.class')]
        if any(not(name.startswith('CabinetJoin24FinalProbe')or name.endswith('Test.class'))for name in classes):raise AssertionError('Unexpected production compilation')
        result=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(argfile),'cn.piq.fcarcade.cabinet.CabinetJoin24FinalProbe',jar],tmp))
        if not result.get('ok')or result.get('production_origin')!='final-jar-only':raise AssertionError('Probe failed/origin mismatch')
        if q.digest(jar.read_bytes())!=digest:raise AssertionError('Temporary final copy changed')
    if q.digest(final.read_bytes())!=digest:raise AssertionError('Final jar changed')
    report={'ok':True,'jar':str(final),'sha256':digest,'probe':result,'compiled_only_probes_and_tests':True,'vanilla_resources':{'path':str(resources),'sha256':resources_sha},'source_sha256':{str(p.relative_to(ROOT)):q.digest(p.read_bytes())for p in [probe,*tests]},'limits':['No real Minecraft GUI/window, socket, server world/permissions or emulator launched.','Pure gate tests execute final classes; server event-reentrancy integration was reviewed separately, not simulated as a live world.']}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as stream:json.dump(report,stream,ensure_ascii=False,indent=2);stream.write('\n')
    print(json.dumps({'ok':True,'report':str(args.report),'sha256':q.digest(args.report.read_bytes()),'fc_sha256':digest},ensure_ascii=False))
if __name__=='__main__':main()
