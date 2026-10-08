"""Bounded real-source compile/link preflight; never load a MAME core or probe DLL."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import subprocess
import time

import build_candidate as build
import ci_prepare as ci

PROFILE = "clang64-shared"
PROJECT = "build/projects/retro/mame/gmake-mingw-clang"
UNITS = (("ocore_retro.make", "src/osd/osdsync.cpp"),
         ("osd_retro.make", "src/osd/libretro/retromain.cpp"))
GENERATE_FLAGS = (
    "REGENIE=1 VERBOSE=1 NOWERROR=1 OSD=retro NO_USE_MIDI=1 NO_USE_PORTAUDIO=1 "
    "CONFIG=libretro TARGETOS=windows MINGW64=/clang64 MINGW_PREFIX=/clang64 "
    "LIBRETRO_CPU=x86_64 CC=clang CXX=clang++ OVERRIDE_CC=clang OVERRIDE_CXX=clang++ "
    "AR=llvm-ar OVERRIDE_AR=llvm-ar WINDRES=llvm-windres TARGET=mame SUBTARGET=mame "
    "PTR64=1 PYTHON_EXECUTABLE=python3 IGNORE_GIT=1 LDOPTS=-fuse-ld=lld")
SAMPLE = r'''#include <mutex>
#include <thread>
struct value { int n = 3; ~value() { n = 0; } };
thread_local value local_value;
extern "C" __declspec(dllexport) unsigned retro_api_version() {
    std::recursive_mutex mutex;
    unsigned out = 0;
    std::thread worker([&] { std::lock_guard<std::recursive_mutex> lock(mutex);
        out = static_cast<unsigned>(local_value.n); });
    worker.join();
    return out + local_value.n;
}
'''


def configuration(text):
    blocks = re.findall(r"^ifeq \(\$\(config\),libretro64\)\n(.*?)^endif$", text, re.M | re.S)
    if len(blocks) != 1:
        raise ValueError("Expected exactly one generated libretro64 configuration")
    block = blocks[0]
    for forbidden in ("-femulated-tls", "-static", "libstdc++", "winpthread", "-m32"):
        if forbidden in block:
            raise ValueError("Unexpected generated runtime flag: " + forbidden)
    for required in ("-fno-emulated-tls", "-stdlib=libc++", "-m64"):
        if required not in block:
            raise ValueError("Missing generated runtime flag: " + required)
    return block


def object_target(text, unit):
    block = configuration(text)
    found = re.findall(r"^\s*OBJDIR\s*=\s*(\S+)\s*$", block, re.M)
    if len(found) != 1 or not found[0].startswith("../../../../libretro/obj/x64/libretro/"):
        raise ValueError("Unexpected generated object directory")
    suffix = unit.removesuffix(".cpp") + ".o"
    if f"$(OBJDIR)/{suffix}: ../../../../../{unit} " not in text:
        raise ValueError("Selected real source unit is not a generated target")
    return found[0] + "/" + suffix


def owned_object_files(root, objects):
    """Resolve the two generated object/dependency pairs inside this source tree."""
    if len(objects) != len(UNITS) or len(set(objects)) != len(UNITS):
        raise ValueError("Unexpected preflight object set")
    owned = (root / "mame/build/libretro/obj").resolve()
    files = []
    for obj in objects:
        for path in (obj, obj.with_suffix(".d")):
            resolved = path.resolve()
            if (path.is_symlink() or not resolved.is_relative_to(owned)
                    or resolved == owned):
                raise ValueError("Preflight object escaped the owned build tree")
            files.append(resolved)
    return files


def require_fresh_objects(root, objects):
    for path in owned_object_files(root, objects):
        if path.exists():
            raise ValueError("Preflight cannot reuse an existing object/dependency file")


def retain_probe_objects(root, output, objects):
    """Keep evidence, but force the full build to compile its own mapped objects."""
    files = owned_object_files(root, objects)
    owned = (root / "mame/build/libretro/obj").resolve()
    if any(not path.is_file() or path.stat().st_size == 0 for path in files):
        raise ValueError("Expected new nonempty preflight object and dependency files")
    before = {path: build.sha(path) for path in files}
    destination = output / "objects"
    if (not destination.resolve().is_relative_to((root / "shared-preflight").resolve())
            or destination.exists() or destination.is_symlink()):
        raise ValueError("Preflight object evidence directory must be new and owned")
    destination.mkdir()
    retained = {}
    for path in files:
        target = destination / path.relative_to(owned)
        target.parent.mkdir(parents=True, exist_ok=True)
        if target.exists():
            raise FileExistsError("Never overwrite earlier object evidence")
        # Both paths were resolved under this one dedicated root, on the same
        # volume. Windows rename refuses replacement if a target appears.
        path.rename(target)
        if path.exists() or build.sha(target) != before[path]:
            raise ValueError("Preflight object evidence move did not preserve identity")
        retained[target.relative_to(root).as_posix()] = before[path]
    return retained


def validate_inputs(root, msys, receipt_path):
    source = json.loads((root / "source-receipt.json").read_text(encoding="utf-8"))
    build.validate_source_profile(source, PROFILE)
    if (source["source_zip_sha256"] != build.SOURCE_SHA256
            or source["checked_source_files"] != build.SOURCE_FILES
            or Path(source["source_root"]).resolve() != root / "mame"):
        raise ValueError("Source identity mismatch")
    for name, item in source["changed_files"].items():
        if build.sha(root / "mame" / name) != item["after"]:
            raise ValueError("Patched source drift")
    tool = json.loads(receipt_path.read_text(encoding="utf-8"))
    ci_schema = tool.get("schema") == "piq-mame-ci-toolchain-receipt-1"
    if os.environ.get("GITHUB_ACTIONS") == "true" and not ci_schema:
        raise ValueError("CI cannot use a local preparation receipt")
    if tool.get("schema") not in ("piq-mame-ci-toolchain-receipt-1", "piq-mame-local-toolchain-receipt-1"):
        raise ValueError("Unknown tool preparation receipt")
    lock = ci.load_lock(ci.lock_path(PROFILE))
    if (tool.get("status") != "ready" or tool.get("profile") != PROFILE
            or tool.get("lock_sha256") != build.sha(ci.lock_path(PROFILE))
            or Path(tool["msys_root"]).resolve() != msys
            or tool.get("packages") != lock["base_manifest"] | {p["name"]: p["version"] for p in lock["packages"]}
            or tool.get("tool_files") != ci.tool_files(msys, PROFILE)):
        raise ValueError("Pinned toolchain identity mismatch")
    return source, tool


def run_step(msys, cwd, output, name, command, seconds):
    env = os.environ.copy()
    for key in ("MAKEFLAGS", "MFLAGS", "CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS", "GCC_EXEC_PREFIX",
                "COMPILER_PATH", "CPATH", "CPLUS_INCLUDE_PATH", "C_INCLUDE_PATH", "LIBRARY_PATH",
                "BASH_ENV", "ENV", "CDPATH", "PROMPT_COMMAND", "PYTHONHOME", "PYTHONPATH"):
        env.pop(key, None)
    env.update(MSYSTEM="CLANG64", MSYS2_PATH_TYPE="minimal", CHERE_INVOKING="1", CCACHE_DISABLE="1",
               PATH=str(msys / "usr/bin") + os.pathsep + str(Path(os.environ["SystemRoot"]) / "System32"))
    script = "set -eu\nexport PATH=/clang64/bin:/usr/bin\nunset ANDROID_NDK_HOME ANDROID_NDK_ROOT\n" + command + "\n"
    log = output / (name + ".log")
    started = time.monotonic()
    record = {"command": script, "maximum_seconds": seconds, "core_executed": False}
    build.save(output / (name + "-started.json"), record)
    try:
        with log.open("xb") as stream:
            result = subprocess.run([str(msys / "usr/bin/timeout.exe"), "--signal=TERM", "--kill-after=10s",
                str(seconds) + "s", str(msys / "usr/bin/bash.exe"), "--noprofile", "--norc", "-s"],
                input=script.encode("utf-8"), cwd=cwd, env=env, stdout=stream, stderr=subprocess.STDOUT,
                timeout=seconds + 25, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0)
                | getattr(subprocess, "BELOW_NORMAL_PRIORITY_CLASS", 0))
        record["exit_code"] = result.returncode
    finally:
        record.update(elapsed_seconds=round(time.monotonic() - started, 3), log_sha256=build.sha(log))
        build.save(output / (name + "-finished.json"), record)
    if result.returncode:
        raise ValueError("Shared preflight failed at " + name + ": " + build.failure_excerpt(log))
    return record


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--msys", type=Path, required=True)
    parser.add_argument("--toolchain-receipt", type=Path, required=True)
    args = parser.parse_args()
    root, msys = args.root.resolve(), args.msys.resolve()
    if not str(root).isascii() or root == root.parent or not (root / "mame").is_dir():
        raise ValueError("Expected a dedicated prepared ASCII source tree")
    _, tool = validate_inputs(root, msys, args.toolchain_receipt)
    output = root / "shared-preflight"
    output.mkdir(exist_ok=False)
    report = {"schema": "piq-mame-shared-preflight-1", "status": "failed", "profile": PROFILE,
              "source_receipt_sha256": build.sha(root / "source-receipt.json"),
              "toolchain_receipt_sha256": build.sha(args.toolchain_receipt),
              "preflight_sha256": build.sha(Path(__file__)), "core_executed": False, "full_build": False}
    commands = []
    try:
        commands.append(run_step(msys, root / "mame", output, "generate",
            "make -j2 " + GENERATE_FLAGS + " " + PROJECT + "/Makefile", 600))
        project = root / "mame" / PROJECT
        makefiles = sorted(project.glob("*.make"))
        # GENie counts the top-level Makefile plus 380 per-project .make files.
        if len(makefiles) != 380 or not (project / "Makefile").is_file():
            raise ValueError("Unexpected full-driver generated project count")
        report["generated_projects"] = {p.name: build.sha(p) for p in makefiles}
        for path in makefiles:
            configuration(path.read_text(encoding="utf-8"))
        objects, targets = [], []
        for index, (name, unit) in enumerate(UNITS):
            target = object_target((project / name).read_text(encoding="utf-8"), unit)
            objects.append((project / target).resolve())
            targets.append((name, target))
        require_fresh_objects(root, objects)
        for index, (name, target) in enumerate(targets):
            commands.append(run_step(msys, project, output, "unit-" + str(index),
                "make -j2 -f " + name + " config=libretro64 verbose=1 WINDRES=llvm-windres " + shlex.quote(target), 300))
        # The same generated MAME flags drive a small TLS/thread DLL. It is only
        # inspected, never loaded; runtime lifetime is a separate JNI test gate.
        sample = output / "link-probe.cpp"
        sample.write_text(SAMPLE, encoding="utf-8")
        probe_make = output / "link-probe.make"
        probe_make.write_text(".PHONY: piq-shared-preflight\npiq-shared-preflight:\n\t$(CXX) $(ALL_CXXFLAGS) "
            + shlex.quote(sample.as_posix()) + " $(ALL_LDFLAGS) -Wl,-Map," + (output / "link-probe.map").as_posix()
            + " -o " + shlex.quote((output / "link-probe.dll").as_posix()) + "\n", encoding="utf-8")
        commands.append(run_step(msys, project, output, "link", "make -f mame.make -f "
            + shlex.quote(probe_make.as_posix()) + " config=libretro64 verbose=1 piq-shared-preflight", 120))
        target = shlex.quote((output / "link-probe.dll").as_posix())
        commands.append(run_step(msys, project, output, "inspect", "llvm-readobj --coff-imports --coff-tls-directory "
            + target + "\nllvm-nm " + " ".join(shlex.quote(p.as_posix()) for p in objects), 30))
        inspection = (output / "inspect.log").read_text(encoding="utf-8")
        linkmap = (output / "link-probe.map").read_text(encoding="utf-8")
        combined = (inspection + linkmap).lower()
        tls_fields = [re.search(r"\b" + name + r":\s*(0x[0-9a-f]+)", combined)
                      for name in ("startaddressofrawdata", "endaddressofrawdata", "addressofindex")]
        if ("name: libc++.dll" not in combined or any(not m or int(m[1], 16) == 0 for m in tls_fields)
                or any(x in combined for x in ("emutls", "winpthread", "libstdc++", "libgcc", "libc++.a(", "libc++abi.a("))):
            raise ValueError("Unexpected shared-runtime/TLS link result")
        _, after = validate_inputs(root, msys, args.toolchain_receipt)
        if after != tool:
            raise ValueError("Tool receipt changed during preflight")
        retained = retain_probe_objects(root, output, objects)
        report.update(status="passed", unit_objects={name: digest for name, digest in retained.items()
                                                     if name.endswith(".o")},
                      unit_dependencies={name: digest for name, digest in retained.items() if name.endswith(".d")},
                      full_build_must_recompile_units=True,
                      link_probe_sha256=build.sha(output / "link-probe.dll"),
                      inspection_sha256=build.sha(output / "inspect.log"))
    finally:
        report["command_sha256"] = hashlib.sha256(json.dumps(commands, sort_keys=True).encode()).hexdigest().upper()
        build.save(root / "shared-preflight.json", report)
    print(json.dumps({"status": report["status"], "full_build": False, "core_executed": False}))


if __name__ == "__main__":
    main()
