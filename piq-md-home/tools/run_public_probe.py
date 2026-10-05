"""Use fresh output name. Optionally compile current engine on top of frozen jars for isolated probes."""
from pathlib import Path
import sys,os,subprocess,json,hashlib
from public_diagnostic_rom import create
ROOT=Path(__file__).resolve().parents[2];here=Path(__file__).resolve().parent
out=ROOT/'outputs/md-public-20261002'/sys.argv[1];out.mkdir(parents=True,exist_ok=False)
classes=out/'classes';classes.mkdir();fc=Path(sys.argv[2]);md=Path(sys.argv[3])
java=Path(os.environ.get('JAVA_HOME','C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot'))/'bin'
cp=os.pathsep.join(map(str,[fc,md]));(out/'two-port.md').write_bytes(create())
sources=[here/'MdPublicNativeProbe.java']
overlay=len(sys.argv)>4 and sys.argv[4]=='overlay'
if overlay:
    base=ROOT/'piq-md-home/src/main/java/cn/piq/mdhome'
    sources += [base/'client/MdEngine.java',base/'client/MdProfile.java',base/'client/MdPublicInputBuffer.java',base/'save/MdSaveCatalog.java']
jars={str(p):hashlib.sha256(p.read_bytes()).hexdigest() for p in [fc,md]}
source_hash={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources}
r=subprocess.run([str(java/'javac.exe'),'-encoding','UTF-8','-cp',cp,'-d',str(classes),*map(str,sources)],capture_output=True)
(out/'compile.log').write_bytes(r.stdout+r.stderr)
if r.returncode:print(r.stderr.decode(errors='replace'));sys.exit(r.returncode)
r=subprocess.run([str(java/'java.exe'),'-Xcheck:jni','-Dfile.encoding=UTF-8','-Dsun.stdout.encoding=UTF-8','-cp',str(classes)+os.pathsep+cp,'MdPublicNativeProbe',str(out)],capture_output=True,timeout=120)
(out/'probe.log').write_bytes(r.stdout+r.stderr)
unchanged=all(hashlib.sha256(Path(p).read_bytes()).hexdigest()==sha for p,sha in jars.items()) and all(hashlib.sha256((ROOT/p).read_bytes()).hexdigest()==sha for p,sha in source_hash.items())
result={'ok':unchanged and r.returncode==0 and b'PASS checks=' in r.stdout,'unchangedInputs':unchanged,'exit':r.returncode,'jars':jars,'sources':source_hash,'overlay':overlay,'scope':'real GX/JNI engine, original two-port ROM, fake transport; not Minecraft integration or Netplay'}
(out/'receipt.json').write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(result,ensure_ascii=False));print((r.stdout+r.stderr).decode('utf-8',errors='replace')[-8000:]);sys.exit(0 if result['ok'] else 1)
