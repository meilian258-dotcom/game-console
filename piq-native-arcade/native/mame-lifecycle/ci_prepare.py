"""Prepare fresh, hash-locked MSYS2 and upstream inputs on a hosted Windows runner.

This is an explicit CI-only network/install entry. It never reads a local build
cache, updates repositories, changes a shared installation, or loads a core.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import shutil
import subprocess
import sys
import urllib.parse
import urllib.request

from build_candidate import COMMIT, SOURCE_SHA256, save, sha, stamp

LOCK = Path(__file__).with_name("ci-toolchain-lock.json")
SOURCE_URL = f"https://github.com/libretro/mame/archive/{COMMIT}.zip"
SOURCE_BYTES = 234551698
GIB = 1024**3


def require_hosted_ci(root: Path, env=None) -> None:
    env = os.environ if env is None else env
    if (env.get("GITHUB_ACTIONS") != "true" or env.get("RUNNER_OS") != "Windows"
            or env.get("RUNNER_ENVIRONMENT") != "github-hosted"):
        raise ValueError("CI mode requires a fresh GitHub-hosted Windows runner")
    temp = Path(env["RUNNER_TEMP"]).resolve()
    if root == temp or not root.is_relative_to(temp) or not str(root).isascii():
        raise ValueError("CI root must be a dedicated ASCII child of RUNNER_TEMP")


def validate_url(url: str, *, redirected=False) -> None:
    parsed = urllib.parse.urlsplit(url)
    hosts = {"github.com", "repo.msys2.org"}
    if redirected:
        hosts |= {"codeload.github.com", "release-assets.githubusercontent.com"}
    if (parsed.scheme != "https" or parsed.hostname not in hosts or parsed.port not in (None, 443)
            or parsed.username or parsed.password or (parsed.query and not redirected) or parsed.fragment):
        raise ValueError("Unexpected upstream download URL")


class LockedRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        validate_url(newurl, redirected=True)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def download(url: str, target: Path, expected_sha: str | None, expected_bytes: int | None,
             *, opener=None, limit: int = 300 * 1024**2) -> dict:
    validate_url(url)
    if opener is None:
        opener = urllib.request.build_opener(LockedRedirectHandler()).open
    digest = hashlib.sha256()
    count = 0
    # No overwrite/retry: a failed or partial download remains evidence in this run.
    with target.open("xb") as output:
        request = urllib.request.Request(url, headers={"User-Agent": "piq-mame-lifecycle-ci/1"})
        with opener(request, timeout=120) as response:
            while chunk := response.read(1024**2):
                count += len(chunk)
                if count > limit or (expected_bytes is not None and count > expected_bytes):
                    raise ValueError("Download exceeded its locked size")
                digest.update(chunk)
                output.write(chunk)
    actual = digest.hexdigest().upper()
    if expected_bytes is not None and count != expected_bytes:
        raise ValueError("Download size mismatch: " + target.name)
    if expected_sha is not None and actual != expected_sha.upper():
        raise ValueError("Download SHA-256 mismatch: " + target.name)
    if count == 0:
        raise ValueError("Empty download")
    return {"file": target.name, "url": url, "bytes": count, "sha256": actual}


def load_lock(path=LOCK) -> dict:
    lock = json.loads(path.read_text(encoding="utf-8"))
    if lock["schema"] != "piq-mame-ci-toolchain-1" or lock["environment"] != "MINGW64":
        raise ValueError("Unexpected toolchain lock schema/environment")
    packages = lock["packages"]
    if len(packages) != 38 or len({p["name"] for p in packages}) != len(packages):
        raise ValueError("Unexpected or duplicate locked package set")
    for item in [lock["base"], *packages]:
        validate_url(item["url"])
        if (Path(item["filename"]).name != item["filename"] or "\\" in item["filename"]
                or not re.fullmatch(r"[a-fA-F0-9]{64}", item["sha256"]) or item["bytes"] <= 0):
            raise ValueError("Malformed locked download")
        if urllib.parse.urlsplit(item["url"]).path.split("/")[-1] != item["filename"]:
            raise ValueError("Locked filename/URL mismatch")
    return lock


def parse_manifest(text: str) -> dict[str, str]:
    result = {}
    for line in text.splitlines():
        if not line.strip():
            continue
        name, version = line.split()
        if name in result:
            raise ValueError("Duplicate package manifest entry")
        result[name] = version
    return result


def tool_files(msys: Path) -> dict[str, str]:
    names = ["usr/bin/bash.exe", "usr/bin/make.exe", "usr/bin/msys-2.0.dll",
             "mingw64/bin/gcc.exe", "mingw64/bin/g++.exe", "mingw64/bin/ar.exe",
             "mingw64/bin/ld.exe", "mingw64/bin/python3.exe"]
    names += [p.relative_to(msys).as_posix() for p in
              (msys / "mingw64/lib/gcc/x86_64-w64-mingw32").glob("*/cc1plus.exe")]
    if len(names) != 9:
        raise ValueError("Expected exactly one pinned C++ compiler backend")
    names += [p.relative_to(msys).as_posix() for p in
              (msys / "mingw64/include/c++").glob("*/bits/version.h")]
    if len(names) != 10:
        raise ValueError("Expected exactly one pinned C++ library version header")
    return {name: sha(msys / name) for name in names}


def disable_key_refresh(msys: Path) -> dict:
    # Same narrow first-start adjustment as msys2/setup-msys2's
    # disableKeyRefresh. Keep offline --init/--populate and Required signatures.
    post = msys / "etc/post-install/07-pacman-key.post"
    original = post.read_bytes()
    if original.count(b"--refresh-keys") != 1:
        raise ValueError("Unexpected pinned MSYS2 key initialization script")
    changed = original.replace(b"--refresh-keys", b"--version")
    post.write_bytes(changed)
    return {"file": "etc/post-install/07-pacman-key.post",
            "before_sha256": hashlib.sha256(original).hexdigest().upper(),
            "after_sha256": hashlib.sha256(changed).hexdigest().upper(),
            "change": "disable online key refresh; preserve offline initialization and required signatures"}


def enforce_offline_keyring(msys: Path) -> dict:
    # --noconfirm alone may approve pacman's explicit missing-key import.
    # Disable GnuPG network access as well as automatic lookup in this new root.
    config = msys / "etc/pacman.d/gnupg/gpg.conf"
    original = config.read_bytes()
    options = b"\nno-auto-key-retrieve\nno-auto-key-import\nauto-key-locate clear\ndisable-dirmngr\n"
    changed = original + options
    config.write_bytes(changed)
    return {"file": "etc/pacman.d/gnupg/gpg.conf",
            "before_sha256": hashlib.sha256(original).hexdigest().upper(),
            "after_sha256": hashlib.sha256(changed).hexdigest().upper(),
            "change": "disable automatic key import/retrieval and Dirmngr network access"}


def run_shell(msys: Path, command: str, log: Path, *, login=False) -> str:
    env = os.environ.copy()
    for key in ("BASH_ENV", "ENV", "CDPATH", "PROMPT_COMMAND", "PYTHONHOME", "PYTHONPATH"):
        env.pop(key, None)
    env.update(MSYSTEM="MSYS", MSYS2_PATH_TYPE="minimal", CHERE_INVOKING="1",
               PATH=str(msys / "usr/bin") + os.pathsep + str(Path(os.environ["SystemRoot"]) / "System32"))
    args = [str(msys / "usr/bin/bash.exe")]
    args += ["--login"] if login else ["--noprofile", "--norc"]
    args += ["-c", "set -eu\n" + command]
    try:
        result = subprocess.run(args, env=env, cwd=msys, text=True, encoding="utf-8",
                                errors="replace", stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                timeout=600, creationflags=getattr(subprocess, "BELOW_NORMAL_PRIORITY_CLASS", 0))
    except subprocess.TimeoutExpired as exc:
        captured = exc.stdout or b""
        if isinstance(captured, bytes):
            captured = captured.decode("utf-8", errors="replace")
        with log.open("x", encoding="utf-8") as output:
            output.write(captured + "\nCI preparation timed out; this run is not reusable.\n")
        raise
    with log.open("x", encoding="utf-8") as output:
        output.write(result.stdout)
    if result.returncode:
        raise RuntimeError(f"MSYS2 preparation failed ({result.returncode}); see {log.name}")
    return result.stdout


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    require_hosted_ci(root)
    if root.exists():
        raise ValueError("CI preparation requires a nonexistent root; no cache reuse")
    if shutil.disk_usage(root.parent).free < 12 * GIB:
        raise ValueError("CI preparation needs 12 GiB free including toolchain and build budget")
    lock = load_lock()
    root.mkdir()
    inputs = root / "inputs"
    inputs.mkdir()
    receipt = {"schema": "piq-mame-ci-toolchain-receipt-1", "started_utc": stamp(),
               "lock_sha256": sha(LOCK), "preparer_sha256": sha(Path(__file__)),
               "status": "preparing", "downloads": [], "host_python": sys.version,
               "runner_image": os.environ.get("ImageVersion"), "cache_reused": False}
    save(root / "toolchain-started.json", receipt)
    try:
        base = lock["base"]
        receipt["downloads"].append(download(base["url"], inputs / base["filename"], base["sha256"], base["bytes"]))
        manifest = inputs / "base-packages.txt"
        receipt["downloads"].append(download(base["manifest_url"], manifest, base["manifest_sha256"], None, limit=65536))
        if parse_manifest(manifest.read_text(encoding="utf-8")) != lock["base_manifest"]:
            raise ValueError("Base package manifest does not match lock")
        # Windows' bundled bsdtar extracts only the authenticated official archive.
        tar = Path(os.environ["SystemRoot"]) / "System32/tar.exe"
        subprocess.run([str(tar), "-xf", str(inputs / base["filename"]), "-C", str(root)], check=True, timeout=300)
        receipt["extractor"] = {"kind": "Windows System32 bsdtar", "sha256": sha(tar)}
        msys = root / "msys64"
        receipt["offline_key_initialization"] = disable_key_refresh(msys)
        run_shell(msys, "true", root / "msys-initialize.log", login=True)
        receipt["offline_keyring_policy"] = enforce_offline_keyring(msys)
        before = run_shell(msys, "pacman -Q", root / "packages-before.txt")
        if parse_manifest(before) != lock["base_manifest"]:
            raise ValueError("Extracted MSYS2 base package set differs from lock")
        paths = []
        verify_commands = []
        for item in lock["packages"]:
            package = inputs / item["filename"]
            receipt["downloads"].append(download(item["url"], package, item["sha256"], item["bytes"]))
            receipt["downloads"].append(download(item["url"] + ".sig", inputs / (item["filename"] + ".sig"), None, None, limit=65536))
            paths.append(shlex.quote(package.as_posix()))
            verify_commands.append("pacman-key --verify " + shlex.quote(package.as_posix() + ".sig") + " " + shlex.quote(package.as_posix()))
        run_shell(msys, "\n".join(verify_commands), root / "packages-signatures.log")
        receipt["offline_signature_checks"] = len(verify_commands)
        receipt["signature_log_sha256"] = sha(root / "packages-signatures.log")
        config = root / "pacman-ci.conf"
        with config.open("x", encoding="utf-8", newline="\n") as output:
            output.write("[options]\nArchitecture = auto\nCheckSpace\nSigLevel = Required DatabaseOptional\nLocalFileSigLevel = Required\nRemoteFileSigLevel = Required\n")
        run_shell(msys, "pacman --config " + shlex.quote(config.as_posix()) +
                  " --noconfirm -U " + " ".join(paths), root / "packages-install.log")
        after = run_shell(msys, "pacman -Q", root / "packages-after.txt")
        expected = lock["base_manifest"] | {p["name"]: p["version"] for p in lock["packages"]}
        if parse_manifest(after) != expected:
            raise ValueError("Installed package set/version differs from complete lock")
        versions = run_shell(msys, "export PATH=/mingw64/bin:/usr/bin\n"
                             "gcc -dumpfullversion\npython3 --version\nmake --version\n"
                             "test \"$(gcc -dumpmachine)\" = x86_64-w64-mingw32\n"
                             "python3 -c 'import sys; assert sys.platform == \"win32\"'", root / "tool-versions.txt")
        if (not versions.startswith(lock["gcc_version"] + "\nPython " + lock["python_version"] + "\n")
                or "GNU Make " + lock["make_version"] not in versions):
            raise ValueError("Executed tool versions differ from lock")
        receipt["downloads"].append(download(SOURCE_URL, inputs / "mame-upstream.zip", SOURCE_SHA256, SOURCE_BYTES))
        receipt.update(status="ready", finished_utc=stamp(), msys_root=str(msys),
                       packages=expected, tool_files=tool_files(msys),
                       package_manifest_sha256=sha(root / "packages-after.txt"),
                       source_zip_sha256=SOURCE_SHA256)
        save(root / "toolchain-receipt.json", receipt)
        print(json.dumps({"status": "ready", "root": str(root), "receipt_sha256": sha(root / "toolchain-receipt.json")}), flush=True)
    except Exception as exc:
        receipt.update(status="failed", finished_utc=stamp(), error=str(exc))
        save(root / "toolchain-failed.json", receipt)
        raise


if __name__ == "__main__":
    main()
