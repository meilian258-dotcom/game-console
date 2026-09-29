"""Smoke frozen Java bridge against frozen helper in a new isolated game directory."""
import argparse, hashlib, json, pathlib, shutil, subprocess, time, zipfile
parser = argparse.ArgumentParser()
parser.add_argument('--version', choices=['0.1.0-prototype.1','0.1.0-prototype.2','0.1.0-prototype.3'], default='0.1.0-prototype.3')
options = parser.parse_args()
WORK = pathlib.Path(__file__).resolve().parents[2]
PROJECT = WORK / "piq-flash-box"
JAR = PROJECT / ("build/libs/game_console_flash_box-" + options.version + ".jar")
GSON = pathlib.Path(r"C:\Users\13498\.gradle\caches\modules-2\files-2.1\com.google.code.gson\gson\2.11.0\527175ca6d81050b53bdd4c457a6d6e017626b0e\gson-2.11.0.jar")
JDK = pathlib.Path(r"C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot\bin")
SWF = PROJECT / "runtime/private-test/the-forest-temple.swf"
PUBLISH = PROJECT / {'0.1.0-prototype.1':'runtime/bin/Release/net6.0-windows/win-x64/publish',
    '0.1.0-prototype.2':'runtime/publish-0.1.1', '0.1.0-prototype.3':'runtime/publish-0.1.2'}[options.version]
OUTPUT = WORK / ("outputs/flash-box-prototype" + options.version.rsplit('.',1)[1] + "/java-smoke") / time.strftime("run-%Y%m%d-%H%M%S")
OUTPUT.mkdir(parents=True, exist_ok=False)
classes = OUTPUT / "classes"; classes.mkdir()
game = OUTPUT / "game"; game.mkdir()
runtime = game / "piq-flash-box/runtime"
shutil.copytree(PUBLISH, runtime)
jar_sha = hashlib.sha256(JAR.read_bytes()).hexdigest().upper()
manifest_sha = hashlib.sha256((PUBLISH / "runtime-manifest.json").read_bytes()).hexdigest().upper()
with zipfile.ZipFile(JAR) as archive:
    frozen = archive.read("flash-runtime.sha256").decode("utf-8")
for line in frozen.splitlines():
    if not line or line.startswith("#"): continue
    expected, relative = line.split("  ", 1)
    candidate = (runtime / relative).resolve()
    assert candidate.is_relative_to(runtime.resolve())
    assert hashlib.sha256(candidate.read_bytes()).hexdigest().upper() == expected.upper(), relative
classpath = str(JAR) + ";" + str(GSON)
compile_result = subprocess.run([str(JDK/"javac.exe"), "-encoding", "UTF-8", "-cp", classpath,
                                 "-d", str(classes), str(PROJECT/"tools/JavaRuntimeSmoke.java")],
                                capture_output=True, text=True, encoding="utf-8")
(OUTPUT/"compile.log").write_text(compile_result.stdout+compile_result.stderr, encoding="utf-8")
assert compile_result.returncode == 0, compile_result.stderr
run = subprocess.run([str(JDK/"java.exe"), "-cp", str(classes)+";"+classpath, "JavaRuntimeSmoke",
                      str(game), str(SWF), str(OUTPUT/"report.json"), options.version],
                     capture_output=True, text=True, encoding="utf-8", timeout=70, creationflags=subprocess.CREATE_NO_WINDOW)
(OUTPUT/"run.log").write_text(run.stdout+run.stderr, encoding="utf-8")
assert hashlib.sha256(JAR.read_bytes()).hexdigest().upper() == jar_sha, "Frozen JAR changed during test"
assert hashlib.sha256((PUBLISH/"runtime-manifest.json").read_bytes()).hexdigest().upper() == manifest_sha
receipt={"output":str(OUTPUT),"jarSha256":jar_sha,"runtimeManifestSha256":manifest_sha,"exitCode":run.returncode,
         "privateSwfCopiedToPackage":False,"productionOrigin":"frozen-jar-only"}
(OUTPUT/"receipt.json").write_text(json.dumps(receipt,ensure_ascii=False,indent=2),encoding="utf-8")
summary=json.loads((OUTPUT/"report.json").read_text(encoding="utf-8")) if (OUTPUT/"report.json").exists() else {"ok":False,"failure":"Java smoke produced no report"}
summary["receipt"]=receipt
summary["ok"]=bool(summary.get("ok")) and run.returncode==0
(OUTPUT.parent/"report.json").write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding="utf-8")
print(json.dumps(receipt,ensure_ascii=False,indent=2)); print(run.stdout); print(run.stderr)
raise SystemExit(run.returncode)
