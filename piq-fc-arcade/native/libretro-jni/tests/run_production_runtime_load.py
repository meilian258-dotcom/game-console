"""Bounded Windows JVM checks using real platform sources, never test bridge shadows.

All DLL paths and hashes are explicit. This compiles Java only, never a native
core, and runs four fresh JVMs sequentially. No Minecraft or game content is used.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import time

ROOT = Path(__file__).resolve().parents[4]
HERE = Path(__file__).resolve().parent
MAX_DLL = 16 * 1024 * 1024


def no_links(path: Path) -> Path:
    path = path.absolute()
    for item in (path, *path.parents):
        if item.exists() or item.is_symlink():
            info = item.lstat()
            if stat.S_ISLNK(info.st_mode) or getattr(info, "st_file_attributes", 0) & 0x400:
                raise ValueError("Linked input/output path refused")
    return path


def digest(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def checked_dll(path: Path, sha: str) -> Path:
    path = no_links(path)
    if not re.fullmatch(r"[a-fA-F0-9]{64}", sha):
        raise ValueError("Explicit SHA256 required")
    if not path.is_file() or not 0 < path.stat().st_size <= MAX_DLL or digest(path) != sha.lower():
        raise ValueError("DLL identity/size mismatch")
    return path


def copy_verified(source: Path, target: Path, expected_sha: str) -> None:
    checked_dll(source, expected_sha)
    target.parent.mkdir(parents=True, exist_ok=True)
    no_links(target)
    with source.open("rb") as incoming, target.open("xb") as outgoing:
        shutil.copyfileobj(incoming, outgoing, 1024 * 1024)
    if digest(target) != expected_sha.lower():
        raise ValueError("Staged DLL changed")


def run(command: list[str], cwd: Path, log: Path, timeout: int, env: dict[str, str]) -> dict:
    started = time.monotonic()
    child = None
    with log.open("xb") as stream:
        try:
            child = subprocess.Popen(command, cwd=cwd, stdout=stream, stderr=subprocess.STDOUT,
                                     env=env, creationflags=subprocess.CREATE_NO_WINDOW | subprocess.BELOW_NORMAL_PRIORITY_CLASS)
            code = child.wait(timeout=timeout)
        finally:
            if child is not None and child.poll() is None:
                child.kill()  # Only this Popen-owned JVM/compiler, never a name-based kill.
                child.wait(timeout=10)
    if log.stat().st_size > 1024 * 1024:
        raise ValueError("Child output exceeded technical log budget")
    return dict(exit_code=code, seconds=round(time.monotonic() - started, 3), reaped=child.poll() is not None)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-root", type=Path, default=ROOT)
    parser.add_argument("--jdk", type=Path, required=True)
    for name in ("bridge", "runtime", "old-bridge"):
        parser.add_argument("--" + name, type=Path, required=True)
        parser.add_argument("--" + name + "-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if os.name != "nt":
        raise ValueError("Windows x64 JVM runner only")
    root, output, jdk = no_links(args.source_root), no_links(args.output), no_links(args.jdk)
    if not str(output).isascii() or output.exists():
        raise ValueError("Use a new ASCII-only output directory")
    bridge = checked_dll(args.bridge, args.bridge_sha256)
    runtime = checked_dll(args.runtime, args.runtime_sha256)
    old = checked_dll(args.old_bridge, args.old_bridge_sha256)
    java, javac = jdk / "bin/java.exe", jdk / "bin/javac.exe"
    if not java.is_file() or not javac.is_file():
        raise ValueError("Explicit JDK is incomplete")
    sources = sorted((root / "piq-retro-platform/src/main/java").rglob("*.java"))
    if not sources or len(sources) > 512:
        raise ValueError("Unexpected production source inventory")
    source_hashes = {path.relative_to(root).as_posix(): digest(no_links(path)) for path in sources}
    output.mkdir(parents=True, exist_ok=False)
    classes = output / "classes"
    classes.mkdir()
    argfile = output / "javac.args"
    arguments = ["--release", "21", "-encoding", "UTF-8", "-proc:none", "-d", classes.as_posix(),
                 *(path.as_posix() for path in sources), (HERE / "ProductionRuntimeLoadProbe.java").as_posix()]
    if any('"' in item or "\n" in item or "\r" in item for item in arguments):
        raise ValueError("Unsupported compiler argument")
    argfile.write_text("\n".join('"' + item + '"' for item in arguments), encoding="utf-8")
    env = dict(os.environ)
    for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS", "CLASSPATH"):
        env.pop(key, None)
    receipt = dict(schema=1, production_sources=source_hashes,
                   inputs={name: dict(sha256=digest(path), bytes=path.stat().st_size)
                           for name, path in (("bridge", bridge), ("runtime", runtime), ("old_bridge", old))},
                   cases=[], passed=False, scope="Production Java loader only; no emulator core or Minecraft acceptance")
    result_file = output / "receipt.json"

    def save():
        result_file.write_text(json.dumps(receipt, indent=2, ensure_ascii=True) + "\n", encoding="utf-8")

    save()
    try:
        receipt["compile"] = run([str(javac), "@" + str(argfile)], output, output / "javac.log", 90, env)
        save()
        if receipt["compile"]["exit_code"]:
            raise ValueError("Production Java compilation failed; inspect private javac.log")
        if source_hashes != {path.relative_to(root).as_posix(): digest(path) for path in sources}:
            raise ValueError("Production sources changed during compilation")
        for case in ("normal", "wrong-sha", "old-bridge", "foreign-runtime"):
            work = output / case
            resources = work / "resources"
            native = resources / "core/libretro-jni/windows-x64"
            instance, temporary = work / "instance", work / "tmp"
            instance.mkdir(parents=True)
            temporary.mkdir()
            chosen = old if case == "old-bridge" else bridge
            chosen_sha = args.old_bridge_sha256 if case == "old-bridge" else args.bridge_sha256
            copy_verified(chosen, native / "piq-libretro-jni.dll", chosen_sha)
            copy_verified(runtime, native / "libc++.dll", args.runtime_sha256)
            sha = "0" * 64 if case == "wrong-sha" else args.runtime_sha256.lower()
            manifest = ("abi=2\nruntime-dependency-api=1\nwindows-x64.sha256=" + chosen_sha.lower()
                        + "\nwindows-x64.runtime.count=1\nwindows-x64.runtime.0.name=libc++.dll"
                        + "\nwindows-x64.runtime.0.sha256=" + sha
                        + "\nwindows-x64.runtime.0.bytes=" + str(receipt["inputs"]["runtime"]["bytes"]) + "\n")
            (native.parent / "runtime.properties").write_text(manifest, encoding="ascii")
            command = [str(java), "-Xmx128m", "-Xcheck:jni", "-XX:ErrorFile=hs_err_pid%p.log",
                       "-Djava.io.tmpdir=" + str(temporary), "-cp", str(classes) + os.pathsep + str(resources),
                       "ProductionRuntimeLoadProbe", case, str(instance), str(classes)]
            if case == "foreign-runtime":
                foreign = work / "foreign/libc++.dll"
                copy_verified(runtime, foreign, args.runtime_sha256)
                command.append(str(foreign))
            entry = dict(case=case, **run(command, work, work / "child.log", 60, env))
            lines = (work / "child.log").read_text(encoding="utf-8", errors="replace").splitlines()
            results = [json.loads(line.removeprefix("PRODUCTION_RUNTIME_RESULT ")) for line in lines
                       if line.startswith("PRODUCTION_RUNTIME_RESULT ")]
            entry["result"] = results[0] if len(results) == 1 else None
            entry["crash_files"] = len(list(work.glob("hs_err_pid*.log")))
            root_dir = instance / "game-console/runtime-sessions"
            entry["retained_workspaces_after_exit"] = sum(path.is_dir() for path in root_dir.iterdir()) if root_dir.exists() else 0
            receipt["cases"].append(entry)
            save()
            expected_dirs = 0 if case == "wrong-sha" else 1
            if (entry["exit_code"] != 0 or not entry["reaped"] or entry["crash_files"]
                    or entry["result"] is None or entry["result"].get("passed") is not True
                    or entry["retained_workspaces_after_exit"] != expected_dirs):
                raise ValueError("Production runtime case failed: " + case)
        checked_dll(bridge, args.bridge_sha256)
        checked_dll(runtime, args.runtime_sha256)
        checked_dll(old, args.old_bridge_sha256)
        receipt["passed"] = True
        save()
        print(json.dumps(dict(passed=True, cases=len(receipt["cases"]), production_classes=True, core_loaded=False)))
        return 0
    except BaseException as error:
        receipt["failure_type"] = type(error).__name__
        save()
        raise


if __name__ == "__main__":
    raise SystemExit(main())
