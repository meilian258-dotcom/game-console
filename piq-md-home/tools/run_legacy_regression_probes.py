"""Sequential, fresh-directory packaged GX MEDIA/private regressions, never force-kill JNI owners.

Usage: python run_legacy_regression_probes.py <fresh-name> <fc.jar> <md.jar>
Reuses the unchanged older probe classes and original diagnostic ROM generators.
"""
from pathlib import Path
import hashlib,json,os,shutil,subprocess,sys,zipfile
from diagnostic_rom import create as private_rom
from public_diagnostic_rom import create as public_rom

sys.stdout.reconfigure(encoding="utf-8")
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[1]
out=ROOT/"outputs/md-jni-netplay-20261003"/sys.argv[1];out.mkdir(parents=True,exist_ok=False)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
inputs=[Path(p).resolve() for p in sys.argv[2:4]]
input_hashes={str(p):sha(p) for p in inputs}
frozen=out/"jars";frozen.mkdir();jars=[]
for p in inputs:
    dest=frozen/p.name;shutil.copy2(p,dest)
    if sha(dest)!=input_hashes[str(p)] or sha(p)!=input_hashes[str(p)]:raise RuntimeError("Input changed during snapshot")
    jars.append(dest)
core_hashes={}
with zipfile.ZipFile(jars[1]) as jar:
    for name in ["genesis_plus_gx_libretro.dll","blastem_libretro.dll"]:
        core_hashes[name]=hashlib.sha256(jar.read("core/windows-x64/"+name)).hexdigest()
if core_hashes!={"genesis_plus_gx_libretro.dll":"9ffa10a115b20e1b49e9caf0b53f287c640ed4e5bb93f7ed9a23b416a4ccfdf7",
                 "blastem_libretro.dll":"3e275e9656e389be11a2623a47a6b454b214a91966af984a2105fa7c8249d016"}:
    raise RuntimeError("Legacy core bytes changed")
classes=out/"classes";classes.mkdir();cp=os.pathsep.join(map(str,[classes,*jars]))
java=Path("C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin")
sources=[HERE/"MdPublicNativeProbe.java",HERE/"MdNativeProbe.java"]
compile=subprocess.run([str(java/"javac.exe"),"-encoding","UTF-8","-cp",cp,"-d",str(classes),*map(str,sources)],capture_output=True)
(out/"compile.log").write_bytes(compile.stdout+compile.stderr)
if compile.returncode:raise RuntimeError(compile.stderr.decode("utf-8",errors="replace"))
results=[]
for name,main,args,rom_name,rom in [
    ("media-jni","MdPublicNativeProbe",[],"two-port.md",public_rom()),
    ("private-jni","MdNativeProbe",["JNI_TRIAL","GENESIS_PLUS_GX"],"diagnostic.md",private_rom()),
    ("private-process","MdNativeProbe",["PROCESS","GENESIS_PLUS_GX"],"diagnostic.md",private_rom())]:
    case=out/name;case.mkdir();(case/rom_name).write_bytes(rom)
    with (case/"probe.log").open("wb") as log:
        result=subprocess.run([str(java/"java.exe"),"-Xcheck:jni","-Dfile.encoding=UTF-8","-Dsun.stdout.encoding=UTF-8","-Dsun.stderr.encoding=UTF-8",
                               "-cp",cp,main,str(case),*args],stdout=log,stderr=subprocess.STDOUT)
    text=(case/"probe.log").read_text(encoding="utf-8",errors="replace")
    checks=next((line for line in text.splitlines() if line.startswith("PASS checks=")),None)
    receipt={"ok":result.returncode==0 and checks is not None,"exit":result.returncode,"scope":name+"; real original GX; no Minecraft integration",
             "overlay":False,"inputJars":input_hashes,"coreSha256":core_hashes["genesis_plus_gx_libretro.dll"],"result":checks}
    (case/"receipt.json").write_text(json.dumps(receipt,indent=2),encoding="utf-8");results.append(receipt)
    print(json.dumps(receipt),flush=True)
    if not receipt["ok"]:break
summary={"passed":len(results)==3 and all(r["ok"] for r in results),"inputJars":input_hashes,"overlay":False,
         "legacyCoreHashes":core_hashes,"sources":{str(p):sha(p) for p in sources},"cases":results}
(out/"receipt.json").write_text(json.dumps(summary,indent=2),encoding="utf-8")
print(json.dumps(summary));sys.exit(0 if summary["passed"] else 1)
