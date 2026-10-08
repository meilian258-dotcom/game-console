"""Build a full MAME 0.289 lifecycle candidate from pinned, clean source.

No downloads, package installs, existing-source changes or deployment. Build
receipts contain local paths and are private diagnostics, not release files.
"""
from __future__ import annotations

import argparse
from collections import deque
import datetime
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shlex
import shutil
import stat
import subprocess
import sys
import time
import zipfile

COMMIT = "4fc9a9312baaf34963847f884961ad9793fbbc1d"
SOURCE_SHA256 = "C24D6FAAB64B4B571AA27F00FAC6D61FE19C9A1A9FEBEB0A57882DF26CE2EB0C"
PATCH = Path(__file__).with_name("lifecycle-4fc9a931.patch")
PATCH_SHA256 = "802DF281F5B5342992A993937E6CF620A5CB412F83FB0D055332A8360EEFD27E"
SHARED_PATCH = Path(__file__).with_name("clang64-shared-4fc9a931.patch")
SHARED_PATCH_SHA256 = "33396A5624F32C495280E114A1D91F57600FBFF24FD7D6CE5F19EDFBC003F357"
PROFILES = ("gcc-static", "clang64-shared")
SOURCE_FILES = 31413
CHANGED = {
    "src/frontend/mame/mame.cpp",
    "src/frontend/mame/clifront.cpp",
    "src/osd/libretro/retromain.cpp",
    "src/osd/libretro/libretro-internal/libretro.cpp",
}


def selected_profile(args) -> str:
    profile = getattr(args, "profile", "gcc-static")
    if profile not in PROFILES:
        raise ValueError("Unknown MAME build profile")
    return profile


def patches(profile: str = "gcc-static") -> list[tuple[Path, str]]:
    selected_profile(argparse.Namespace(profile=profile))
    return [(PATCH, PATCH_SHA256)] + ([(SHARED_PATCH, SHARED_PATCH_SHA256)]
                                     if profile == "clang64-shared" else [])


def changed_files(profile: str = "gcc-static") -> set[str]:
    selected_profile(argparse.Namespace(profile=profile))
    return CHANGED | ({"scripts/genie.lua"} if profile == "clang64-shared" else set())


def patch_identities(profile: str = "gcc-static") -> list[dict]:
    result = []
    for path, expected in patches(profile):
        if sha(path) != expected:
            raise ValueError("Pinned build patch SHA-256 mismatch: " + path.name)
        result.append({"file": path.name, "sha256": expected})
    return result


def validate_source_profile(record: dict, profile: str) -> None:
    expected = patch_identities(profile)
    # Old GCC-only receipts remain readable; never infer a shared-runtime profile.
    if (record.get("profile", "gcc-static") != profile
            or record.get("patches", expected if profile == "gcc-static" else None) != expected
            or record["commit"] != COMMIT or record["patch_sha256"] != PATCH_SHA256
            or set(record["changed_files"]) != changed_files(profile)):
        raise ValueError("Candidate source profile/patch provenance mismatch")


def sha(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest().upper()


def save(path: Path, value) -> None:
    # Exclusive files preserve every earlier attempt and its evidence.
    with path.open("x", encoding="utf-8") as stream:
        json.dump(value, stream, indent=2, ensure_ascii=False)


def stamp() -> str:
    return datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def failure_excerpt(path: Path) -> str:
    """Read only this build's log, with bounded scanning, context and tail."""
    before = deque(maxlen=3)
    first = b""
    markers = (b": fatal error:", b": error:", b"undefined reference to", b"collect2:")
    overlap = b""
    overlap_size = max(map(len, markers)) - 1
    with path.open("rb") as stream:
        remaining = 8 * 1024**2
        while remaining > 0:
            line = stream.readline(min(4096, remaining))
            if not line:
                break
            remaining -= len(line)
            searchable = overlap + line
            offsets = [offset for marker in markers
                       if (offset := searchable.find(marker)) >= 0]
            if offsets:
                # Parallel sub-makes can continue long after the first failure.
                # Keep the error itself even if preceding lines were unusually long.
                context = b"".join(before) + line
                position = len(context) - len(searchable) + min(offsets)
                first = context[max(0, position - 1024):position + 2048] + stream.read(1024)
                break
            before.append(line)
            overlap = searchable[-overlap_size:]
        stream.seek(0, os.SEEK_END)
        stream.seek(max(0, stream.tell() - 16384))
        tail = stream.read(16384).decode("utf-8", "replace")
    sections = []
    if first:
        sections.append("First compiler error context:\n" + first.decode("utf-8", "replace"))
    sections.append("Build log tail:\n" + "\n".join(tail.splitlines()[-60:]))
    return "\n".join(sections)


def prepare(args, root: Path) -> dict:
    profile = selected_profile(args)
    if root.exists():
        raise ValueError("New build requires a nonexistent root; use --resume only for this script's own tree")
    if sha(args.source_zip) != SOURCE_SHA256:
        raise ValueError("Pinned upstream source SHA-256 mismatch")
    patch_records = patch_identities(profile)
    root.mkdir(parents=True)
    source = root / "mame"
    source.mkdir()
    original = {}
    prefix = f"mame-{COMMIT}/"
    with zipfile.ZipFile(args.source_zip) as archive:
        for entry in archive.infolist():
            if not entry.filename.startswith(prefix):
                raise ValueError("Unexpected archive root")
            name = entry.filename[len(prefix):]
            rel = PurePosixPath(name)
            if not name or entry.is_dir():
                continue
            if rel.is_absolute() or ".." in rel.parts or "\\" in name or ":" in name:
                raise ValueError("Unsafe archive member")
            data = archive.read(entry)
            if stat.S_ISLNK(entry.external_attr >> 16):
                if name not in {"3rdparty/zstd/tests/cli-tests/bin/unzstd", "3rdparty/zstd/tests/cli-tests/bin/zstdcat"} or data != b"zstd":
                    raise ValueError("Unexpected source symlink")
                # Materialise only the pinned archive's two same-directory links.
                data = archive.read(prefix + str(rel.parent / "zstd"))
            target = source.joinpath(*rel.parts)
            target.parent.mkdir(parents=True, exist_ok=True)
            with target.open("xb") as stream:
                stream.write(data)
            original[name] = hashlib.sha256(data).hexdigest().upper()
    if len(original) != SOURCE_FILES:
        raise ValueError("Unexpected pinned source file count")
    for patch, _ in patches(profile):
        subprocess.run([str(args.git), "-c", "core.autocrlf=false", "apply", "--check", "--recount", str(patch)],
                       cwd=source, check=True)
        subprocess.run([str(args.git), "-c", "core.autocrlf=false", "apply", "--recount", str(patch)],
                       cwd=source, check=True)
    changes = {}
    for name, before in original.items():
        after = sha(source / name)
        if after != before:
            changes[name] = {"before": before, "after": after}
    if set(changes) != changed_files(profile):
        raise ValueError("Patch changed unexpected source files")
    record = {"schema": "piq-mame-lifecycle-source-1", "commit": COMMIT,
              "profile": profile, "patches": patch_records,
              "source_zip_sha256": SOURCE_SHA256, "patch_sha256": sha(PATCH),
              "source_root": str(source), "checked_source_files": len(original),
              "changed_files": changes, "prepared_utc": stamp()}
    save(root / "source-receipt.json", record)
    print(json.dumps(record), flush=True)
    return record


def build_command(root: Path, msys: Path, jobs: int, ci_clean: bool,
                  profile: str = "gcc-static") -> str:
    selected_profile(argparse.Namespace(profile=profile))
    shared = profile == "clang64-shared"
    maps = []
    for source_path, replacement in ((root, "/mame-build"), (msys, "/toolchain")):
        for prefix in (source_path.as_posix(), "/" + source_path.drive[0].lower() + source_path.as_posix()[2:]):
            maps.extend([f"-ffile-prefix-map={prefix}={replacement}", f"-fdebug-prefix-map={prefix}={replacement}"])
    # timeout owns only its newly-created make process group in this dedicated CI VM.
    # A five-hour build budget leaves time for preparation, evidence upload and probes.
    bounded = "timeout --signal=INT --kill-after=60s 18000s " if ci_clean else ""
    # GNU make's command-line variables propagate to its recursive invocations,
    # including GENie's own make. COMPILER/PREMAKE_TOOLCHAIN are not read upstream.
    target = ("platform=win MSYSTEM=CLANG64 MINGW_PREFIX=/clang64 MINGW64=/clang64 "
              "LIBRETRO_CPU=x86_64 CC=clang CXX=clang++ AR=llvm-ar OVERRIDE_AR=llvm-ar "
              "WINDRES=llvm-windres MAP=1" if shared else
              "platform=win MSYSTEM=MINGW64 MINGW64=/mingw64 CC=gcc CXX=g++ AR=ar")
    cc, cxx = ("clang", "clang++") if shared else ("gcc", "g++")
    return "\n".join([
        "set -eu", "export PATH=/clang64/bin:/usr/bin" if shared else "export PATH=/mingw64/bin:/usr/bin",
        # Makefile.libretro checks Android paths even for platform=win. Hosted
        # runners provide unrelated Windows-form NDK paths; keep this CI target isolated.
        *(["unset ANDROID_NDK_HOME ANDROID_NDK_ROOT"] if ci_clean or shared else []),
        "export MSYS2_ARG_CONV_EXCL='-ffile-prefix-map=;-fdebug-prefix-map='",
        f"{cc} --version", f"{cxx} --version", "make --version", "python3 --version",
        f"test \"$({cc} -dumpmachine)\" = x86_64-w64-windows-gnu" if shared else
        "test \"$(gcc -dumpmachine)\" = x86_64-w64-mingw32",
        *(["llvm-ar --version", "llvm-windres --version", "ld.lld --version"] if shared else []),
        "python3 -c 'import sys; assert sys.platform == \"win32\"'",
        "cd " + shlex.quote((root / "mame").as_posix()),
        bounded + f"make -f Makefile.libretro -j{jobs} {target} TARGET=mame SUBTARGET=mame REGENIE=1 VERBOSE= NOWERROR=1 PTR64=1 PYTHON_EXECUTABLE=python3 IGNORE_GIT=1 ARCHOPTS=" + shlex.quote(" ".join(maps)),
    ])


def validate_ci(args, root: Path) -> dict:
    from ci_prepare import lock_path, load_lock, require_hosted_ci, tool_files
    profile = selected_profile(args)
    require_hosted_ci(root)
    if args.resume or root.exists() or args.prepare_only or args.jobs > 4:
        raise ValueError("CI requires a new complete build with at most four jobs; no resume/cache")
    if not args.toolchain_receipt:
        raise ValueError("CI requires the fresh pinned toolchain receipt")
    if (args.toolchain_receipt.resolve() != root.parent / "toolchain-receipt.json"
            or args.source_zip.resolve() != root.parent / "inputs/mame-upstream.zip"
            or args.msys_root.resolve() != root.parent / "msys64"):
        raise ValueError("CI inputs must belong to this newly prepared sibling root")
    record = json.loads(args.toolchain_receipt.read_text(encoding="utf-8"))
    msys = args.msys_root.resolve()
    locked_path = lock_path(profile)
    lock = load_lock(locked_path)
    expected = lock["base_manifest"] | {p["name"]: p["version"] for p in lock["packages"]}
    if (record["schema"] != "piq-mame-ci-toolchain-receipt-1" or record["status"] != "ready"
            or record.get("profile", "gcc-static") != profile
            or record["lock_sha256"] != sha(locked_path) or record["packages"] != expected
            or record["preparer_sha256"] != sha(Path(__file__).with_name("ci_prepare.py"))
            or record["offline_signature_checks"] != len(lock["packages"])
            or record["offline_keyring_policy"]["after_sha256"] != sha(msys / "etc/pacman.d/gnupg/gpg.conf")
            or record["cache_reused"] is not False or Path(record["msys_root"]).resolve() != msys
            or record["tool_files"] != tool_files(msys, profile)):
        raise ValueError("Fresh pinned toolchain provenance mismatch")
    return record


def validate_shared_preflight(args, root: Path) -> dict:
    path = root / "shared-preflight.json"
    record = json.loads(path.read_text(encoding="utf-8"))
    if (record.get("schema") != "piq-mame-shared-preflight-1"
            or record.get("status") != "passed" or record.get("profile") != "clang64-shared"
            or record.get("source_receipt_sha256") != sha(root / "source-receipt.json")
            or not args.toolchain_receipt
            or record.get("toolchain_receipt_sha256") != sha(args.toolchain_receipt)
            or record.get("preflight_sha256") != sha(Path(__file__).with_name("preflight_shared.py"))):
        raise ValueError("Shared build requires matching, successful bounded preflight provenance")
    return record


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-zip", type=Path, required=True)
    parser.add_argument("--msys-root", type=Path, required=True)
    parser.add_argument("--build-root", type=Path, required=True)
    parser.add_argument("--git", type=Path, required=True)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--profile", choices=PROFILES, default="gcc-static")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--jobs", type=int, choices=(2, 4, 6), default=2)
    parser.add_argument("--ci-clean", action="store_true", help="explicit fresh hosted-Windows CI mode; no resume")
    parser.add_argument("--toolchain-receipt", type=Path)
    args = parser.parse_args()
    profile = selected_profile(args)
    if profile == "clang64-shared" and args.resume:
        raise ValueError("Shared builds require a new source tree and fresh bounded preflight; no resume/cache")
    root = args.build_root.resolve()
    if not str(root).isascii() or len(root.parts) < 2:
        raise ValueError("Use a dedicated ASCII build directory")
    toolchain = validate_ci(args, root) if args.ci_clean else None
    probe = root if root.exists() else root.parent
    minimum_gib = 10 if args.ci_clean else 80
    if shutil.disk_usage(probe).free < minimum_gib * 1024**3:
        raise ValueError(f"Build requires at least {minimum_gib} GiB free on its target volume")
    if args.resume:
        record = json.loads((root / "source-receipt.json").read_text(encoding="utf-8"))
        validate_source_profile(record, profile)
        if (record["commit"] != COMMIT or record["source_zip_sha256"] != SOURCE_SHA256
                or record["patch_sha256"] != PATCH_SHA256 or sha(PATCH) != PATCH_SHA256
                or sha(args.source_zip) != SOURCE_SHA256):
            raise ValueError("Resume provenance mismatch")
        if Path(record["source_root"]).resolve() != root / "mame":
            raise ValueError("Resume source directory mismatch")
        for name, item in record["changed_files"].items():
            if sha(root / "mame" / name) != item["after"]:
                raise ValueError("Patched source has changed")
    else:
        record = prepare(args, root)
    if args.prepare_only:
        return
    if profile == "clang64-shared":
        if not args.toolchain_receipt:
            raise ValueError("Shared full builds require the pinned toolchain receipt and preflight")
        subprocess.run([sys.executable, "-B", str(Path(__file__).with_name("preflight_shared.py")),
                        "--root", str(root), "--msys", str(args.msys_root.resolve()),
                        "--toolchain-receipt", str(args.toolchain_receipt)], check=True)
        validate_shared_preflight(args, root)
        # The preflight validates the same fixed toolchain on a local invocation.
        # Keep its identity for the identical post-build drift gate used by CI.
        if toolchain is None:
            toolchain = json.loads(args.toolchain_receipt.read_text(encoding="utf-8"))
    if args.ci_clean:
        subprocess.run([sys.executable, "-B", str(Path(__file__).with_name("package_source.py")),
                        "--source-zip", str(args.source_zip), "--build-root", str(root),
                        "--profile", profile,
                        "--output", str(root / "candidate-source.zip")], check=True)
    temp = root / "tmp"
    temp.mkdir(exist_ok=True)
    msys = args.msys_root.resolve()
    bash = msys / "usr/bin/bash.exe"
    if not bash.is_file():
        raise ValueError("Missing preinstalled portable MSYS2")
    env = os.environ.copy()
    env.pop("MAKEFLAGS", None)
    env.pop("MFLAGS", None)
    if args.ci_clean or profile == "clang64-shared":
        for key in ("CFLAGS", "CXXFLAGS", "CPPFLAGS", "LDFLAGS", "GCC_EXEC_PREFIX", "COMPILER_PATH",
                    "CPATH", "CPLUS_INCLUDE_PATH", "C_INCLUDE_PATH", "LIBRARY_PATH", "BASH_ENV",
                    "ENV", "CDPATH", "PROMPT_COMMAND", "PYTHONHOME", "PYTHONPATH"):
            env.pop(key, None)
        env["CCACHE_DISABLE"] = "1"
    env.update(MSYSTEM="CLANG64" if profile == "clang64-shared" else "MINGW64",
               MSYS2_PATH_TYPE="minimal", CHERE_INVOKING="1",
               TMP=str(temp), TEMP=str(temp), TMPDIR=str(temp))
    command = build_command(root, msys, args.jobs, args.ci_clean, profile)
    started = stamp()
    log_path = root / ("build-" + started + ".log")
    metadata = {"schema": "piq-mame-lifecycle-build-1", "started_utc": started,
                "profile": profile, "patches": patch_identities(profile),
                "launcher_pid": os.getpid(), "source_receipt_sha256": sha(root / "source-receipt.json"),
                "command": command, "jobs": args.jobs, "full_driver_build": True,
                "portable_msys": str(msys), "log": str(log_path),
                "compiler_sha256": sha(msys / ("clang64/bin/clang++.exe" if profile == "clang64-shared"
                                              else "mingw64/bin/g++.exe")),
                "launcher_sha256": sha(Path(__file__)), "temp_directory": str(temp),
                "ci_clean": args.ci_clean, "minimum_free_gib": minimum_gib,
                "toolchain_receipt_sha256": sha(args.toolchain_receipt) if toolchain else None,
                "shared_preflight_sha256": sha(root / "shared-preflight.json")
                if profile == "clang64-shared" else None,
                "deployment": False, "download": False, "system_environment_modified": False}
    flags = subprocess.BELOW_NORMAL_PRIORITY_CLASS if sys.platform == "win32" else 0
    with log_path.open("xb") as log:
        process = subprocess.Popen([str(bash), "--noprofile", "--norc", "-c", command],
                                   cwd=root, env=env, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=flags)
        metadata["build_pid"] = process.pid
        save(root / ("build-" + started + "-started.json"), metadata)
        print(json.dumps(metadata), flush=True)
        began = time.monotonic()
        while True:
            try:
                result = process.wait(timeout=30)
                break
            except subprocess.TimeoutExpired:
                print(json.dumps({"build_pid": process.pid, "elapsed_seconds": round(time.monotonic() - began),
                                  "log_bytes": log_path.stat().st_size,
                                  "free_gib": round(shutil.disk_usage(root).free / 1024**3, 2)}), flush=True)
    metadata.update(exit_code=result, finished_utc=stamp(), log_sha256=sha(log_path))
    if result != 0 and args.ci_clean:
        # This exact hosted-build log is already in the allowlisted evidence.
        print(failure_excerpt(log_path), flush=True)
    artifacts = []
    for dll in (root / "mame").glob("*libretro*.dll"):
        artifacts.append({"path": str(dll), "bytes": dll.stat().st_size, "sha256": sha(dll)})
    metadata["artifacts"] = artifacts
    if profile == "clang64-shared":
        link_map = root / "mame/build/mame.map"
        if link_map.is_file() and link_map.stat().st_size > 0:
            metadata["link_map"] = {"path": str(link_map), "bytes": link_map.stat().st_size,
                                    "sha256": sha(link_map)}
        elif result == 0:
            result = 1
            metadata.update(exit_code=result, validation_error="Shared candidate link map is missing")
    if args.ci_clean:
        metadata["corresponding_source"] = {"file": "candidate-source.zip",
                                            "sha256": sha(root / "candidate-source.zip")}
    if result == 0 and len(artifacts) != 1:
        result = 1
        metadata.update(exit_code=result, validation_error="Expected exactly one freshly built core")
    if toolchain:
        from ci_prepare import tool_files
        try:
            metadata["toolchain_unchanged"] = tool_files(msys, profile) == toolchain["tool_files"]
        except (OSError, ValueError):
            metadata["toolchain_unchanged"] = False
        if not metadata["toolchain_unchanged"]:
            result = 1
            metadata.update(exit_code=result, validation_error="Toolchain executable changed during build")
        if result == 0:
            verified = subprocess.run([sys.executable, "-B", str(Path(__file__).with_name("package_source.py")),
                                       "--source-zip", str(args.source_zip), "--build-root", str(root),
                                       "--profile", profile, "--verify-only"])
            metadata["source_recheck_exit_code"] = verified.returncode
            if verified.returncode:
                result = 1
                metadata.update(exit_code=result, validation_error="Source input recheck failed after build")
    save(root / ("build-" + started + "-finished.json"), metadata)
    print(json.dumps({"exit_code": result, "artifacts": artifacts}), flush=True)
    raise SystemExit(result)


if __name__ == "__main__":
    main()
