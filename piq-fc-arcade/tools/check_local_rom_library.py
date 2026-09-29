"""Direct Java21 execution of real LocalRomLibrary and JUnit fixtures, without Gradle/Minecraft."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]
JDK=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
CACHE=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1')
def run():
    deps=[]
    for group,name,version in [('org.junit.jupiter','junit-jupiter-api','5.13.4'),('org.junit.platform','junit-platform-commons','1.13.4'),('org.opentest4j','opentest4j','1.3.0'),('org.apiguardian','apiguardian-api','1.1.2')]:
        matches=list((CACHE/group/name/version).glob('*/'+name+'-'+version+'.jar'));assert len(matches)==1;deps+=matches
    source=ROOT/'src/main/java/cn/piq/fcarcade/client/rom/LocalRomLibrary.java'
    tests=ROOT/'src/test/java/cn/piq/fcarcade/client/rom/LocalRomLibraryTest.java'
    probe=ROOT/'tools/qa/LocalRomLibraryProbe.java'
    with tempfile.TemporaryDirectory(prefix='piq-local-rom-library-') as td:
        tmp=Path(td);classes=tmp/'classes';classes.mkdir();cases=tmp/'cases';cases.mkdir()
        cp=os.pathsep.join(map(str,[classes,*deps]))
        subprocess.run(list(map(str,[JDK/'javac.exe','--release','21','-encoding','UTF-8','-cp',cp,'-d',classes,source,tests,probe])),check=True,timeout=30)
        result=subprocess.run(list(map(str,[JDK/'java.exe','-cp',cp,'cn.piq.fcarcade.client.rom.LocalRomLibraryProbe',cases])),capture_output=True,timeout=30)
        assert result.returncode==0,(result.stdout+result.stderr).decode('utf-8','replace')
        return {'status':'passed','stdout':result.stdout.decode('utf-8','replace'),'sha256':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest().upper() for p in (source,tests,probe)},'boundary':'Real production Java metadata/creation/filter/cancellation/bounded daemon queue, controlled temporary fixture files; no actual ROM contents, Minecraft or emulator launched. Symlink tests explicitly skipped only when OS privilege absent.'}
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--report',type=Path,required=True);args=parser.parse_args();assert not args.report.exists()
    result=run();args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8') as out:json.dump(result,out,ensure_ascii=False,indent=2)
    print(json.dumps(result,ensure_ascii=True,indent=2))
