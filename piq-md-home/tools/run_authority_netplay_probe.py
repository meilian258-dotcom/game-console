"""New-output-only real GX JNI authority session gate; no process/native kill."""
from pathlib import Path
import hashlib,json,os,shutil,subprocess,sys,zipfile
from netplay_diagnostic_rom import create
sys.stdout.reconfigure(encoding="utf-8")
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[1]
out=ROOT/"outputs/md-jni-netplay-20261003"/sys.argv[1];out.mkdir(parents=True,exist_ok=False)
fc=ROOT/"piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.76.35.jar"
md=ROOT/"piq-md-home/build/libs/game_console_md-0.1.0-alpha.10.jar"
if len(sys.argv)>3:fc,md=Path(sys.argv[3]).resolve(),Path(sys.argv[4]).resolve()
packaged=sys.argv[2]=="--packaged";sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
# Freeze package bytes for the entire JVM run, even if a later build replaces its input path.
inputs={str(p):sha(p) for p in [fc,md]};frozen=out/"jars";frozen.mkdir()
def freeze(p):
    dest=frozen/p.name;shutil.copy2(p,dest)
    if sha(dest)!=inputs[str(p)] or sha(p)!=inputs[str(p)]:raise RuntimeError("Input jar changed during snapshot: "+str(p))
    return dest
fc,md=freeze(fc),freeze(md)
classes=out/"classes";classes.mkdir()
if packaged:
    with zipfile.ZipFile(md) as jar: core_sha=hashlib.sha256(jar.read("core/windows-x64/genesis_plus_gx_piq_netplay_libretro.dll")).hexdigest()
    properties=["-Dpiq.probe.packaged=true","-Dpiq.probe.expected.sha="+core_sha]
else:
    core=Path(sys.argv[2]).resolve();core_sha=sha(core)
    dest=classes/"core/windows-x64/gx_probe.dll";dest.parent.mkdir(parents=True);shutil.copy2(core,dest)
    properties=["-Dpiq.probe.core.sha="+core_sha]
(out/"diagnostic.md").write_bytes(create());cp=os.pathsep.join(map(str,[classes,fc,md]))
java=Path("C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin")
r=subprocess.run([str(java/"javac.exe"),"-encoding","UTF-8","-cp",cp,"-d",str(classes),str(HERE/"MdAuthorityNetplayProbe.java")],capture_output=True)
(out/"compile.log").write_bytes(r.stdout+r.stderr)
if r.returncode:raise RuntimeError(r.stderr.decode("utf-8",errors="replace"))
with (out/"probe.log").open("wb") as log:
    r=subprocess.run([str(java/"java.exe"),"-Xcheck:jni","-Dfile.encoding=UTF-8","-Dsun.stdout.encoding=UTF-8","-Dsun.stderr.encoding=UTF-8",*properties,"-cp",cp,"MdAuthorityNetplayProbe",str(out)],stdout=log,stderr=subprocess.STDOUT)
receipt={"passed":r.returncode==0,"exit":r.returncode,"packagedProfileWithoutOverlay":packaged,"coreSha256":core_sha,"inputJars":inputs,"jars":{str(p):sha(p) for p in [fc,md]},"romSha256":sha(out/"diagnostic.md")}
(out/"receipt.json").write_text(json.dumps(receipt,indent=2),encoding="utf-8");print(json.dumps(receipt));print((out/"probe.log").read_text(encoding="utf-8",errors="replace")[-12000:]);sys.exit(r.returncode)
