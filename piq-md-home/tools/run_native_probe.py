from pathlib import Path
import sys,os,subprocess,json,hashlib
from diagnostic_rom import create
ROOT=Path(__file__).resolve().parents[2];here=Path(__file__).resolve().parent
out=ROOT/'outputs/md-gx-20260930'/sys.argv[1];out.mkdir();classes=out/'classes';classes.mkdir()
fc=ROOT/'piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.76.28.jar';md=ROOT/'piq-md-home/build/libs/game_console_md-0.1.0-alpha.3.jar'
if len(sys.argv)>3:fc=Path(sys.argv[3]);md=Path(sys.argv[4])
java=Path(os.environ.get('JAVA_HOME','C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot'))/'bin'
cp=os.pathsep.join(map(str,[fc,md]));(out/'diagnostic.md').write_bytes(create())
r=subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-cp',cp,'-d',str(classes),str(here/'MdNativeProbe.java')],capture_output=True)
(out/'compile.log').write_bytes(r.stdout+r.stderr);assert not r.returncode,r.stderr.decode(errors='replace')
jars={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in [fc,md]}
core=sys.argv[5] if len(sys.argv)>5 else 'GENESIS_PLUS_GX'
rollback=len(sys.argv)>6 and sys.argv[6]=='rollback'
r=subprocess.run([str(java/'java.exe'),'-Xcheck:jni','-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-Dmd.probe.rollback='+str(rollback).lower(),'-cp',str(classes)+os.pathsep+cp,'MdNativeProbe',str(out),sys.argv[2],core],capture_output=True,timeout=100)
(out/'probe.log').write_bytes(r.stdout+r.stderr);result={'ok':r.returncode==0 and b'PASS checks=' in r.stdout,'exit':r.returncode,'jars':jars,'backend':sys.argv[2],'core':core,'scope':'netplay-release-gate' if rollback else 'private-runtime'}
(out/'receipt.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8');print(json.dumps(result,ensure_ascii=False));print((r.stdout+r.stderr).decode('utf-8',errors='replace')[-5500:])
sys.exit(0 if result['ok'] else 1)
