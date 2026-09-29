"""MC API compile and pure shared-input lifecycle tests; no Gradle/core/game/installation."""
import argparse,json,os,sys,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'piq-fc-arcade/tools'))
import verify_retro_alpha19 as q

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--report',type=Path,required=True);args=parser.parse_args()
    if args.report.exists():raise ValueError('Refusing to overwrite a report')
    sources=[ROOT/'piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/cabinet/CabinetImmersiveInput.java',ROOT/'piq-native-arcade/src/main/java/cn/piq/nativearcade/client/NativeArcadeClient.java']
    tests=[ROOT/'piq-fc-arcade/src/test/java/cn/piq/fcarcade/client/cabinet'/name for name in ('CabinetImmersiveInputTest.java','CabinetInputLifecycleTest.java')]
    deps=q.dependencies();junit=[p for p in deps if any(token in str(p) for token in ('org.junit','org.opentest4j','org.apiguardian'))]
    stage=ROOT/'piq-fc-arcade/build/review-watch23-scale12-v1';before={name:q.digest((stage/name).read_bytes())for name in ('piq_fc_arcade-0.31.0-alpha.23.jar','piq_native_arcade-0.1.0-alpha.7.jar')}
    with tempfile.TemporaryDirectory(prefix='piq-input24-')as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir();jars=[]
        for i,name in enumerate(before):
            jar=tmp/(str(i)+'.jar');jar.write_bytes((stage/name).read_bytes());jars.append(jar)
        cp=os.pathsep.join(map(str,[out,*jars,q.MC,*deps]));argfile=tmp/'compile.args';argfile.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        q.run([q.JAVA/'javac.exe','@'+str(argfile),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources,*tests,ROOT/'piq-fc-arcade/tools/qa/DeviceUiTestRunner.java'],tmp)
        result=q.parse_last_json(q.run([q.JAVA/'java.exe','-cp',os.pathsep.join(map(str,[out,*junit])),'DeviceUiTestRunner','cn.piq.fcarcade.client.cabinet.CabinetImmersiveInputTest','cn.piq.fcarcade.client.cabinet.CabinetInputLifecycleTest'],tmp))
        if result['passed_tests']!=14:raise AssertionError('Missing lifecycle tests')
        classes=sorted(str(p.relative_to(out)).replace('\\','/')for p in out.rglob('*.class'))
    if any(q.digest((stage/name).read_bytes())!=digest for name,digest in before.items()):raise AssertionError('Frozen baseline changed')
    report={'ok':True,'mode':'source-api-compile-and-mc-free-production-behavior','pure_tests':result['passed_tests'],'source_sha256':{str(p.relative_to(ROOT)):q.digest(p.read_bytes())for p in sources+tests},'compiled_classes':classes,'frozen_api_dependency_sha256':before,'production_compiled':True,'minecraft_started':False,'core_started':False,'physical_device_tested':False,'network_or_instance_operations':False,'limits':['Source test, not a final-JAR audit.','No physical keyboard/gamepad, Minecraft window or real focus-event verification.','Native core still runs while menu/focus suppresses local inputs; no pause protocol added.']}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as stream:json.dump(report,stream,ensure_ascii=False,indent=2);stream.write('\n')
    print(json.dumps({'ok':True,'tests':report['pure_tests'],'report':str(args.report),'sha256':q.digest(args.report.read_bytes())},ensure_ascii=False))
if __name__=='__main__':main()
