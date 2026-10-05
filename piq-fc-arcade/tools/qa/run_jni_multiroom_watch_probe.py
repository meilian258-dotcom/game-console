"""Snapshot current development classes; isolated four-slot JNI test, not a frozen JAR/MC claim."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
JAVA = Path("C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin")


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("label")
    parser.add_argument("--jar", type=Path, help="Optional exact candidate JAR; snapshots rather than modifies it")
    parser.add_argument("--java-tmpdir", type=Path, help="Existing ASCII temporary directory for this child JVM only")
    args = parser.parse_args()
    temporary = args.java_tmpdir.resolve(strict=True) if args.java_tmpdir is not None else None
    if temporary is not None and (not temporary.is_dir() or not str(temporary).isascii()):
        raise ValueError("JVM temporary directory must be an existing ASCII directory")
    java_temp = ["-Djava.io.tmpdir=" + str(temporary)] if temporary is not None else []
    if not re.fullmatch(r"(?:development|jar)-[a-zA-Z0-9_-]+", args.label) or args.jar is None and not args.label.startswith("development-"):
        raise ValueError("Use a fresh development-* label, or jar-* with --jar")
    out = ROOT / "outputs/watch-multisource-20261003" / args.label
    out.mkdir(parents=True, exist_ok=False)
    snapshot = out / "snapshot"
    snapshot.mkdir()
    jar = args.jar.resolve(strict=True) if args.jar is not None else None
    if jar is not None:
        shutil.copy2(jar, snapshot / "candidate.jar")
    else:
        shutil.copytree(ROOT / "piq-fc-arcade/build/classes/java/main", snapshot / "main")
        for name in ("libretro-jni", "libretro-jni-netplay"):
            shutil.copytree(ROOT / "piq-fc-arcade/build/resources/main/core" / name, snapshot / "resources/core" / name)
    current = Path(__file__).with_name("JniMultiRoomWatchProbe.java")
    sources = out / "sources"
    sources.mkdir()
    shutil.copy2(current, sources / current.name)
    classes = out / "probe-classes"
    classes.mkdir()
    files = sorted(path for path in snapshot.rglob("*") if path.is_file())
    before = {str(path.relative_to(snapshot)): digest(path) for path in files}
    result = {"stage": "provided-jar-snapshot" if jar is not None else "development-current-class-snapshot", "minecraftAcceptance": False,
              "nativeDllRebuilt": False, "snapshot": before, "ok": False}
    result.update(javaTmpdir=str(temporary) if temporary else None, noForceKill=True)
    if jar is not None:
        result["jar"] = str(jar)
        result["jarSha256"] = digest(jar)
    classpath = os.pathsep.join(map(str, (classes, snapshot / "candidate.jar") if jar is not None else (classes, snapshot / "main", snapshot / "resources")))
    try:
        compiled = subprocess.run([str(JAVA / "javac.exe"), *["-J"+arg for arg in java_temp], "-encoding", "UTF-8", "-cp", classpath,
                                   "-d", str(classes), str(sources / current.name)],
                                  capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=45)
        (out / "compile.log").write_text(compiled.stdout + compiled.stderr, encoding="utf-8")
        result["compileExit"] = compiled.returncode
        if compiled.returncode:
            raise RuntimeError("Probe compile failed; inspect compile.log")
        with (out / "probe.log").open("w", encoding="utf-8") as log:
            probe = subprocess.run([str(JAVA / "java.exe"), *java_temp, "-Xcheck:jni", "-Xmx1024m", "-Dfile.encoding=UTF-8",
                                    "-Dsun.stdout.encoding=UTF-8", "-Dsun.stderr.encoding=UTF-8", "-cp", classpath,
                                    "cn.piq.fcarcade.netplay.JniMultiRoomWatchProbe", str(out / "runtime"), "jar" if jar is not None else "development"],
                                   cwd=out, stdout=log, stderr=subprocess.STDOUT)
        result["probeExit"] = probe.returncode
        content = (out / "probe.log").read_text(encoding="utf-8", errors="replace")
        result["checks"] = len(re.findall(r"^CHECK ", content, re.M))
        result["jniWarning"] = bool(re.search(r"WARNING in native method|FATAL ERROR in native method|JNI DETECTED ERROR", content))
        result["snapshotUnchanged"] = all(digest(snapshot / path) == sha for path, sha in before.items())
        if jar is not None:
            result["snapshotUnchanged"] &= digest(jar) == result["jarSha256"]
        result["ok"] = probe.returncode == 0 and "PASS " in content and not result["jniWarning"] and result["snapshotUnchanged"]
    except Exception as error:
        result["failure"] = str(error)
        raise
    finally:
        (out / "receipt.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({key: value for key, value in result.items() if key != "snapshot"}, ensure_ascii=False))
    if not result["ok"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
