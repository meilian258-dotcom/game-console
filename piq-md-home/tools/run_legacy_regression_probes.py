"""Sequential, fresh-directory packaged GX MEDIA/private regressions, never force-kill JNI owners.

Usage: python run_legacy_regression_probes.py <fresh-name> <fc.jar> <md.jar> [--java-tmpdir <existing-ascii-directory>]
Reuses the original GX diagnostic ROM generators. "Legacy" means original GX MEDIA/private,
not the retired BlastEm core. New packages must exclude BlastEm and retain both pinned GX cores.
"""
from pathlib import Path
import argparse,hashlib,json,os,re,shutil,subprocess,sys,zipfile
from diagnostic_rom import create as private_rom
from public_diagnostic_rom import create as public_rom

sys.stdout.reconfigure(encoding="utf-8")
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[1]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('fresh_name');parser.add_argument('fc_jar',type=Path);parser.add_argument('md_jar',type=Path)
parser.add_argument('--java-tmpdir',type=Path)
args=parser.parse_args()
if not re.fullmatch('[a-zA-Z0-9][a-zA-Z0-9_-]*',args.fresh_name):raise SystemExit('Fresh name must be one directory name')
temporary=args.java_tmpdir.resolve() if args.java_tmpdir is not None else None
if temporary is not None and (not temporary.is_dir() or not str(temporary).isascii()):
    raise SystemExit('Explicit JVM temporary directory must already exist and have an ASCII path')
java_temp=['-Djava.io.tmpdir='+str(temporary)] if temporary is not None else []
out=ROOT/"outputs/md-jni-netplay-20261003"/args.fresh_name;out.mkdir(parents=True,exist_ok=False)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
inputs=[args.fc_jar.resolve(),args.md_jar.resolve()]
input_hashes={str(p):sha(p) for p in inputs}
frozen=out/"jars";frozen.mkdir();jars=[]
for p in inputs:
    dest=frozen/p.name;shutil.copy2(p,dest)
    if sha(dest)!=input_hashes[str(p)] or sha(p)!=input_hashes[str(p)]:raise RuntimeError("Input changed during snapshot")
    jars.append(dest)
core_hashes={}
with zipfile.ZipFile(jars[1]) as jar:
    if any(name.lower().endswith("/blastem_libretro.dll") for name in jar.namelist()):
        raise RuntimeError("Retired BlastEm executable must not be packaged")
    for name in ["genesis_plus_gx_libretro.dll","genesis_plus_gx_piq_netplay_libretro.dll"]:
        core_hashes[name]=hashlib.sha256(jar.read("core/windows-x64/"+name)).hexdigest()
if core_hashes!={"genesis_plus_gx_libretro.dll":"9ffa10a115b20e1b49e9caf0b53f287c640ed4e5bb93f7ed9a23b416a4ccfdf7",
                 "genesis_plus_gx_piq_netplay_libretro.dll":"8200a6d5e7c39f60afefd8e87e369d253f737de6a73bf8d920d2ab81888a5fb5"}:
    raise RuntimeError("Pinned active GX core bytes changed")
classes=out/"classes";classes.mkdir();cp=os.pathsep.join(map(str,[classes,*jars]))
java=Path("C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin")
sources=[HERE/"MdPublicNativeProbe.java",HERE/"MdNativeProbe.java"]
compile=subprocess.run([str(java/"javac.exe"),*['-J'+value for value in java_temp],"-encoding","UTF-8","-cp",cp,"-d",str(classes),*map(str,sources)],capture_output=True)
(out/"compile.log").write_bytes(compile.stdout+compile.stderr)
if compile.returncode:raise RuntimeError(compile.stderr.decode("utf-8",errors="replace"))
results=[]
for name,main,args,rom_name,rom in [
    ("media-jni","MdPublicNativeProbe",[],"two-port.md",public_rom()),
    ("private-jni","MdNativeProbe",["JNI_TRIAL","GENESIS_PLUS_GX"],"diagnostic.md",private_rom()),
    ("private-process","MdNativeProbe",["PROCESS","GENESIS_PLUS_GX"],"diagnostic.md",private_rom())]:
    case=out/name;case.mkdir();(case/rom_name).write_bytes(rom)
    with (case/"probe.log").open("wb") as log:
        result=subprocess.run([str(java/"java.exe"),*java_temp,"-Xcheck:jni","-Dfile.encoding=UTF-8","-Dsun.stdout.encoding=UTF-8","-Dsun.stderr.encoding=UTF-8",
                               "-cp",cp,main,str(case),*args],stdout=log,stderr=subprocess.STDOUT)
    text=(case/"probe.log").read_text(encoding="utf-8",errors="replace")
    checks=next((line for line in text.splitlines() if line.startswith("PASS checks=")),None)
    receipt={"ok":result.returncode==0 and checks is not None,"exit":result.returncode,"scope":name+"; real original GX; no Minecraft integration",
             "overlay":False,"inputJars":input_hashes,"coreSha256":core_hashes["genesis_plus_gx_libretro.dll"],"result":checks}
    (case/"receipt.json").write_text(json.dumps(receipt,indent=2),encoding="utf-8");results.append(receipt)
    print(json.dumps(receipt),flush=True)
    if not receipt["ok"]:break
unchanged=all(sha(p)==input_hashes[str(p)] for p in inputs)
summary={"passed":len(results)==3 and all(r["ok"] for r in results) and unchanged,"inputJars":input_hashes,"overlay":False,
         "inputJarsUnchanged":unchanged,"javaTmpdir":str(temporary) if temporary else None,
         "runnerSha256":sha(Path(__file__)),"legacyCoreHashes":core_hashes,"sources":{str(p):sha(p) for p in sources},"cases":results}
(out/"receipt.json").write_text(json.dumps(summary,indent=2),encoding="utf-8")
print(json.dumps(summary));sys.exit(0 if summary["passed"] else 1)
