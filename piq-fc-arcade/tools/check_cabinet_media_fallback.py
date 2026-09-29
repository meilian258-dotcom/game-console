"""Only production RetroFrame/codec plus JUnit and synthetic CPU/payload measurements."""
import argparse,hashlib,json,os,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
def run(command):
    p=subprocess.run(list(map(str,command)),capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
    if p.returncode:raise RuntimeError(p.stdout+'\n'+p.stderr)
    return p.stdout
def result(output):return json.loads(next(line for line in reversed(output.splitlines()) if line.startswith('{')))
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--report',type=Path,required=True);args=parser.parse_args()
    if args.report.exists():raise FileExistsError(args.report)
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1');deps=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:
        deps.extend(p for p in(cache/group).rglob('*.jar')if version in p.parts and '-sources'not in p.name and '-javadoc'not in p.name)
    sources=[ROOT.parent/'piq-retro-platform/src/main/java/cn/piq/retro/api/RetroFrame.java',ROOT/'src/main/java/cn/piq/fcarcade/cabinet/CabinetMediaCodec.java',ROOT/'src/test/java/cn/piq/fcarcade/cabinet/CabinetMediaCodecTest.java',ROOT/'tools/qa/CabinetMediaFallbackTestRunner.java',ROOT/'tools/qa/CabinetMediaCpuProbe.java']
    hashes={str(p.relative_to(ROOT.parent)):hashlib.sha256(p.read_bytes()).hexdigest().upper()for p in sources}
    with tempfile.TemporaryDirectory(prefix='piq-media-fallback-')as directory:
        classes=Path(directory);cp=os.pathsep.join(map(str,[classes,*deps]))
        run([JAVA/'javac.exe','--release','21','-encoding','UTF-8','-proc:none','-cp',cp,'-d',classes,*sources])
        java=[JAVA/'java.exe','-Xmx256m','-cp',cp]
        tests=run([*java,'CabinetMediaFallbackTestRunner']);cpu=run([*java,'CabinetMediaCpuProbe'])
    for p in sources:
        if hashlib.sha256(p.read_bytes()).hexdigest().upper()!=hashes[str(p.relative_to(ROOT.parent))]:raise ValueError('Source changed during probe')
    report={'ok':True,'tests':result(tests),'cpu_and_payload':result(cpu),'production_origin':'selected-production-source','source_sha256':hashes,'limits':['Synthetic generated pictures only; no commercial ROM, Minecraft, helper or MAME process started.','CPU times are local measurements, not network FPS guarantees.','Bandwidth estimates exclude packet/TCP headers; the 1MiB budget estimate reserves 192000 bytes/s stereo PCM and allows whole-video drops.']}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False))
if __name__=='__main__':main()
