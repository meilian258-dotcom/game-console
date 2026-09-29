"""Only the explicitly user-supplied KOF97 and NeoGeo BIOS; all test output remains local."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]
JDK=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
ROM=Path('E:/Dow/rom/neogeo/kof97.zip')
BIOS=Path('E:/Dow/rom/WinKawaks1.65模拟器/WinKawaks1.65/roms/neogeo/neogeo.zip')
RUNTIME=ROOT.parent/'制作Mod/03-街机模拟/PIQ原生街机/0.1.0-alpha.1/piq-native-arcade/runtime'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest().upper()
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--report-dir',type=Path,required=True);parser.add_argument('--jar',type=Path);parser.add_argument('--jar-sha256');args=parser.parse_args()
    if args.report_dir.exists():raise FileExistsError(args.report_dir)
    args.report_dir.mkdir(parents=True);roms=args.report_dir/'private-rom-fixture';roms.mkdir();screens=args.report_dir/'screens'
    source_sha={str(p):sha(p) for p in (ROM,BIOS)}
    for p in (ROM,BIOS):shutil.copyfile(p,roms/p.name);assert sha(roms/p.name)==source_sha[str(p)]
    report={'status':'started','source_sha256':source_sha,'commercial_content_notice':'User supplied ROM/BIOS copied for authorized local testing only. Exclude private-rom-fixture from all delivery archives.','runtime_sha256':{p.name:sha(p) for p in RUNTIME.iterdir() if p.name in ('mame_libretro.dll','jna-5.14.0.jar','piq-native-helper.jar')}}
    try:
        with tempfile.TemporaryDirectory(prefix='piq-kof97-probe-') as td:
            temp=Path(td);classes=temp/'classes';classes.mkdir()
            if args.jar:
                assert args.jar_sha256 and sha(args.jar)==args.jar_sha256.upper();jar=temp/'candidate.jar';shutil.copyfile(args.jar,jar);sources=[]
                report['parent_jar_sha256']=args.jar_sha256.upper()
            else:jar=None;sources=list((ROOT/'src/main/java/cn/piq/nativearcade/bridge').glob('*.java'))
            cp=os.pathsep.join(map(str,([jar] if jar else [])+[classes]));probe=ROOT/'tools/qa/NativeUserRomProbe.java'
            subprocess.run(list(map(str,[JDK/'javac.exe','-J-Duser.language=en','--release','21','-encoding','UTF-8','-cp',cp,'-d',classes,*sources,probe])),check=True,timeout=30)
            result=subprocess.run(list(map(str,[JDK/'java.exe','-Xmx256m','-cp',cp,'cn.piq.nativearcade.bridge.NativeUserRomProbe',RUNTIME,roms/'kof97.zip',screens])),capture_output=True,timeout=65)
            report.update(status='passed' if result.returncode==0 else 'failed',returncode=result.returncode,stdout=result.stdout.decode('utf-8','replace'),stderr=result.stderr.decode('utf-8','replace'))
            if jar:assert sha(args.jar)==args.jar_sha256.upper()
        assert source_sha=={str(p):sha(p) for p in (ROM,BIOS)},'Source ROM/BIOS changed'
    except Exception as ex:report.update(status='failed',exception=repr(ex));raise
    finally:
        with (args.report_dir/'audit.json').open('x',encoding='utf-8') as out:json.dump(report,out,ensure_ascii=False,indent=2)
        print(json.dumps(report,ensure_ascii=True,indent=2))
    if report['status']!='passed':raise SystemExit(1)
if __name__=='__main__':main()
