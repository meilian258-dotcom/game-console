"""Compile provider pure-Java production classes, unit tests and original-firmware adapter probe; no Gradle/game launch."""
import argparse, hashlib, json, os, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
FC=ROOT.parent/'piq-fc-arcade'
SFC_JAR=ROOT.parent/'制作Mod/03-街机模拟/PIQ-SFC街机/piq_sfc_arcade-0.2.0-alpha.6.jar'
FC_JAR=ROOT.parent/'制作Mod/03-街机模拟/PIQ-FC街机/piq_fc_arcade-0.31.0-alpha.14.jar'
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
def run(cmd,cwd):
    p=subprocess.run(list(map(str,cmd)),cwd=cwd,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
    if p.returncode:raise AssertionError(p.stdout+'\n'+p.stderr)
    return p.stdout
def main():
    a=argparse.ArgumentParser();a.add_argument('--report',type=Path,required=True)
    a.add_argument('--home-jar',type=Path);a.add_argument('--fc-jar',type=Path);args=a.parse_args()
    if bool(args.home_jar)!=bool(args.fc_jar):raise ValueError('Final JAR mode requires both --home-jar and --fc-jar')
    if args.report.exists():raise ValueError('Do not overwrite an existing report')
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1')
    dependencies=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:
        dependencies.extend(p for p in (cache/group).rglob('*.jar') if version in p.parts and '-sources' not in p.name and '-javadoc' not in p.name)
    source=ROOT/'src/main/java/cn/piq/sfchome'
    production=[source/'client/SfcCoreLease.java',*[source/'client/cabinet'/n for n in ['SfcCabinetInputs.java','SfcCabinetFrames.java','SfcCabinetRom.java','SfcCabinetSession.java']],
                FC/'src/main/java/cn/piq/fcarcade/cabinet/CabinetFrame.java',FC/'src/main/java/cn/piq/fcarcade/cabinet/CabinetEmulator.java']
    tests=[ROOT/'src/test/java/cn/piq/sfchome/client/SfcCoreLeaseTest.java',*(ROOT/'src/test/java/cn/piq/sfchome/client/cabinet').glob('*Test.java')]
    probes=[ROOT/'tools/qa/SfcCabinetTestRunner.java',ROOT/'tools/qa/SfcCabinetActualCoreProbe.java',FC/'tools/qa/SfcTwoPortInputProbe.java',ROOT.parent/'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/core/SfcLegalTestRom.java']
    expected='38FA46C5D283EAD9E1F6666D01398F495E2E1A3260A1517BE8EE959710963363'
    assert hashlib.sha256(SFC_JAR.read_bytes()).hexdigest().upper()==expected
    with tempfile.TemporaryDirectory(prefix='sfc-cabinet-provider-') as folder:
        tmp=Path(folder);empty=tmp/'empty';empty.mkdir();classes=tmp/'classes';classes.mkdir()
        final_jars=[args.home_jar.resolve(),args.fc_jar.resolve()] if args.home_jar else []
        final_hashes={str(p):hashlib.sha256(p.read_bytes()).hexdigest().upper() for p in final_jars}
        cp=os.pathsep.join(map(str,[classes,*final_jars,SFC_JAR,FC_JAR,*dependencies]))
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',classes,
             *([] if final_jars else production),*tests,*probes],ROOT)
        assert not (classes/'cn/piq/sfcarcade/core/wasm/WasmSfcCore.class').exists()
        if final_jars:
            assert not (classes/'cn/piq/sfchome/client/cabinet/SfcCabinetSession.class').exists()
            assert not (classes/'cn/piq/fcarcade/cabinet/CabinetFrame.class').exists()
        units=run([JAVA/'java.exe','-cp',cp,'SfcCabinetTestRunner'],ROOT)
        actual=run([JAVA/'java.exe','-cp',cp,'cn.piq.sfchome.client.cabinet.SfcCabinetActualCoreProbe'],ROOT)
    assert hashlib.sha256(SFC_JAR.read_bytes()).hexdigest().upper()==expected
    for path,digest in final_hashes.items():assert hashlib.sha256(Path(path).read_bytes()).hexdigest().upper()==digest
    report={'passed':True,'mode':'final-jar' if final_hashes else 'production-source','final_jars':final_hashes,
            'unit_test_output':units,'actual_core':json.loads(actual.strip()),'old_sfc_jar_sha256':expected,
            'production_source_sha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest().upper() for p in production},
            'limits':['No commercial ROM or Minecraft scene tested.','Original fixture produces silent clock PCM, not audible game music.',
                      'Native Wasmtime calls have no fuel timeout; a stuck worker remains occupied until client restart.','This provider uses local-only cabinet authority; home SFC multiplayer is unchanged.']}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
