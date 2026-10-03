"""Fresh-directory real JNI diagnostic. Does not overwrite evidence or force-kill a native owner."""
from pathlib import Path
import hashlib
import json
import os
import subprocess
import shutil
import sys
from diagnostic_rom import create

ROOT = Path(__file__).resolve().parents[2]
HERE = Path(__file__).resolve().parent
out = ROOT / "outputs/md-jni-netplay-20261003" / sys.argv[1]
out.mkdir(parents=True, exist_ok=False)
classes = out / "classes"
classes.mkdir()
fc = ROOT / "piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.76.34.jar"
md = ROOT / "piq-md-home/build/libs/game_console_md-0.1.0-alpha.10.jar"
if len(sys.argv) > 2:
    fc, md = Path(sys.argv[2]), Path(sys.argv[3])
java = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot")) / "bin"
cp = os.pathsep.join(map(str, [fc, md]))
if os.environ.get("PIQ_PROBE_STRONG")=="1":
    from netplay_diagnostic_rom import create as strong_rom
    (out / "diagnostic.md").write_bytes(strong_rom())
else:
    (out / "diagnostic.md").write_bytes(create())
compile_result = subprocess.run([str(java / "javac.exe"), "-encoding", "UTF-8", "-cp", cp, "-d", str(classes), str(HERE / "MdRollbackGateProbe.java")], capture_output=True)
(out / "compile.log").write_bytes(compile_result.stdout + compile_result.stderr)
if compile_result.returncode:
    raise RuntimeError(compile_result.stderr.decode("utf-8", errors="replace"))
jars = {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in (fc, md)}
# Java owns normal core close; no subprocess timeout/TerminateProcess against a native owner.
override=os.environ.get("PIQ_PROBE_CORE")
core=Path(override).resolve() if override else ROOT / "piq-md-home/src/main/resources/core/windows-x64/genesis_plus_gx_libretro.dll"
extra=[]
if override:
    dest=classes/"core/windows-x64/gx_probe.dll"; dest.parent.mkdir(parents=True)
    shutil.copy2(core,dest); extra=["-Dpiq.probe.core.sha="+hashlib.sha256(core.read_bytes()).hexdigest()]
result = subprocess.run([str(java / "java.exe"), "-Xcheck:jni", "-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8", *extra, "-cp", str(classes) + os.pathsep + cp, "MdRollbackGateProbe", str(out)], capture_output=True)
(out / "probe.log").write_bytes(result.stdout + result.stderr)
receipt = {"diagnosticCompleted": result.returncode == 0, "netplayAccepted": False, "exit": result.returncode, "jars": jars,
           "coreSha256": hashlib.sha256(core.read_bytes()).hexdigest(),
           "romSha256": hashlib.sha256((out / "diagnostic.md").read_bytes()).hexdigest()}
(out / "receipt.json").write_text(json.dumps(receipt, ensure_ascii=False, indent=2), encoding="utf-8")
print(json.dumps(receipt, ensure_ascii=False))
print((result.stdout + result.stderr).decode("utf-8", errors="replace")[-18000:])
sys.exit(result.returncode)
