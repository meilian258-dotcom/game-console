"""Stage and verify the restricted candidate artifact passed between CI jobs.

No native libraries are loaded. Raw build receipts and logs never enter the
restricted output (three legacy files, eight shared-runtime files). Both
commands require the current job's GITHUB_SHA.
"""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys

import build_candidate as build
import verify_candidate as probe

SCHEMA = "piq-mame-ci-candidate-1"
FILES = frozenset(("core.dll", "candidate-source.zip", "candidate.json"))
SHARED_SCHEMA = "piq-mame-ci-candidate-2"
LICENSES = {
    "libcxx-LICENSE.txt": (16703, "539DD7AED86E8A4F12CBDD0E6C50C189C7D74847E4FECC64CE2C6EE3A01DA38B"),
    "libcxxabi-LICENSE.txt": (16706, "E2B35BE49F7284A45B7BACA8FC7B3AB7440E7902392B2528A457816B5BB2A15C"),
    "libunwind-LICENSE.txt": (16706, "B5EFEBCACA80879234098E52D1725E6D9EB8FB96A19FCE625D39184B705F7B6D"),
    "NOTICE.md": (1541, "C902A5667BC3451ED93E99F1F73D5CB3FD66E1B2A18EA58C61E51907C810578D")}
LICENSE_ROOT = Path(__file__).resolve().parents[3] / "piq-fc-arcade/native/libretro-jni/licenses"
SHARED_FILES = FILES | {probe.RUNTIME_NAME} | set(LICENSES)
STATIC_CXX_SHA = "B8B3BA71508B988B03B9BAD852841230C276E41C9F57F4B01565D502098A08CF"
SAVE_POLICY = "separate-identity-no-migration"
ERRORS = frozenset(("arguments", "run-commit", "input-path", "existing-output",
    "build-receipt", "source-receipt", "source-archive", "core-identity",
    "artifact-layout", "manifest", "io-error", "profile", "runtime-identity", "shared-preflight", "link-map"))
MANIFEST_KEYS = frozenset(("schema", "run_commit", "upstream_commit", "upstream_version",
    "candidate", "upstream_source_zip_sha256", "patch_sha256", "source_receipt_sha256",
    "build_receipt_sha256", "full_driver_build", "ci_clean", "toolchain_unchanged",
    "build_exit_code", "source_recheck_exit_code", "platform", "save_policy",
    "status", "validation", "artifacts"))
SHARED_KEYS = MANIFEST_KEYS | {"profile", "patches", "shared_preflight_sha256", "toolchain_receipt_sha256", "static_link"}


class ArtifactError(Exception):
    def __init__(self, code):
        self.code = code if code in ERRORS else "io-error"
        super().__init__(self.code)


def digest(value):
    if not isinstance(value, str) or not re.fullmatch(r"[a-fA-F0-9]{64}", value):
        raise ArtifactError("manifest")
    return value.upper()


def run_commit():
    value = os.environ.get("GITHUB_SHA", "")
    if not re.fullmatch(r"[a-fA-F0-9]{40}", value):
        raise ArtifactError("run-commit")
    return value.lower()


def regular(path):
    try:
        path = probe.safe_path(path)
        if not stat.S_ISREG(path.stat().st_mode):
            raise ArtifactError("input-path")
        return path
    except (OSError, probe.ProbeError) as error:
        raise ArtifactError("input-path") from error


def directory(path):
    try:
        path = probe.safe_path(path)
        if not path.is_dir():
            raise ArtifactError("input-path")
        return path
    except (OSError, probe.ProbeError) as error:
        raise ArtifactError("input-path") from error


def unique_json(path, limit=1024 * 1024):
    path = regular(path)
    if path.stat().st_size > limit:
        raise ArtifactError("manifest")
    def object_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ArtifactError("manifest")
            result[key] = value
        return result
    try:
        result = json.loads(path.read_text(encoding="utf-8"), object_pairs_hook=object_pairs)
    except (UnicodeError, ValueError) as error:
        raise ArtifactError("manifest") from error
    if not isinstance(result, dict):
        raise ArtifactError("manifest")
    return result


def require_zero(value, code):
    if type(value) is not int or value != 0:
        raise ArtifactError(code)


def require_size(value):
    if type(value) is not int or value <= 0:
        raise ArtifactError("manifest")
    return value


def pinned_source(receipt, root, profile="gcc-static"):
    if (receipt.get("schema") != "piq-mame-lifecycle-source-1"
            or receipt.get("commit") != build.COMMIT
            or digest(receipt.get("source_zip_sha256")) != build.SOURCE_SHA256
            or digest(receipt.get("patch_sha256")) != build.PATCH_SHA256
            or build.sha(regular(build.PATCH)) != build.PATCH_SHA256
            or type(receipt.get("checked_source_files")) is not int
            or receipt["checked_source_files"] != build.SOURCE_FILES):
        raise ArtifactError("source-receipt")
    if not isinstance(receipt.get("source_root"), str):
        raise ArtifactError("source-receipt")
    if directory(receipt["source_root"]) != directory(root / "mame"):
        raise ArtifactError("source-receipt")
    changed = receipt.get("changed_files")
    if not isinstance(changed, dict) or set(changed) != build.changed_files(profile):
        raise ArtifactError("source-receipt")
    try:
        build.validate_source_profile(receipt, profile)
    except (ValueError, OSError) as error:
        raise ArtifactError("source-receipt") from error
    for name, entry in changed.items():
        if not isinstance(entry, dict) or set(entry) != {"before", "after"}:
            raise ArtifactError("source-receipt")
        before, after = digest(entry["before"]), digest(entry["after"])
        if before == after or build.sha(regular(root / "mame" / name)) != after:
            raise ArtifactError("source-receipt")


def inspect_build(root, profile="gcc-static"):
    root = directory(root)
    finished = list(root.glob("build-*-finished.json"))
    if len(finished) != 1:
        raise ArtifactError("build-receipt")
    finished_path = regular(finished[0])
    finished_sha = build.sha(finished_path)
    receipt = unique_json(finished_path)
    if (receipt.get("schema") != "piq-mame-lifecycle-build-1"
            or receipt.get("full_driver_build") is not True
            or receipt.get("ci_clean") is not True
            or receipt.get("toolchain_unchanged") is not True
            or "validation_error" in receipt):
        raise ArtifactError("build-receipt")
    if receipt.get("profile", "gcc-static") != profile:
        raise ArtifactError("profile")
    if profile == "clang64-shared" and receipt.get("patches") != build.patch_identities(profile):
        raise ArtifactError("build-receipt")
    require_zero(receipt.get("exit_code"), "build-receipt")
    require_zero(receipt.get("source_recheck_exit_code"), "build-receipt")
    source_path = regular(root / "source-receipt.json")
    if digest(receipt.get("source_receipt_sha256")) != build.sha(source_path):
        raise ArtifactError("source-receipt")
    if digest(receipt.get("launcher_sha256")) != build.sha(regular(Path(build.__file__))):
        raise ArtifactError("build-receipt")
    pinned_source(unique_json(source_path), root, profile)
    artifacts = receipt.get("artifacts")
    if not isinstance(artifacts, list) or len(artifacts) != 1:
        raise ArtifactError("build-receipt")
    item = artifacts[0]
    if not isinstance(item, dict) or set(item) != {"path", "bytes", "sha256"}:
        raise ArtifactError("build-receipt")
    core = regular(root / "mame/mame_libretro.dll")
    if (not isinstance(item["path"], str) or not Path(item["path"]).is_absolute()
            or regular(item["path"]) != core
            or set(directory(root / "mame").glob("*libretro*.dll")) != {core}
            or require_size(item["bytes"]) != core.stat().st_size):
        raise ArtifactError("core-identity")
    expected = digest(item["sha256"])
    try:
        probe.verify_core(core, expected, profile)
    except probe.ProbeError as error:
        raise ArtifactError("core-identity") from error
    corresponding = receipt.get("corresponding_source")
    if (not isinstance(corresponding, dict) or set(corresponding) != {"file", "sha256"}
            or corresponding["file"] != "candidate-source.zip"):
        raise ArtifactError("source-archive")
    source = regular(root / "candidate-source.zip")
    if not source.stat().st_size or build.sha(source) != digest(corresponding["sha256"]):
        raise ArtifactError("source-archive")
    # Freeze the checked identities. A later source-file change must fail the
    # copy recheck, not silently become a newly accepted manifest identity.
    identities = {"core": {"file": "core.dll", "bytes": item["bytes"], "sha256": expected},
                  "source": {"file": "candidate-source.zip", "bytes": source.stat().st_size,
                             "sha256": digest(corresponding["sha256"])}}
    if build.sha(finished_path) != finished_sha or build.sha(source_path) != digest(receipt["source_receipt_sha256"]):
        raise ArtifactError("build-receipt")
    return core, source, digest(receipt["source_receipt_sha256"]), finished_sha, identities


def manifest_for(source_receipt_sha, build_receipt_sha, identities, commit):
    return {"schema": SCHEMA, "run_commit": commit, "upstream_commit": build.COMMIT,
        "upstream_version": "0.289", "candidate": "piq-lifecycle1",
        "upstream_source_zip_sha256": build.SOURCE_SHA256, "patch_sha256": build.PATCH_SHA256,
        "source_receipt_sha256": source_receipt_sha, "build_receipt_sha256": build_receipt_sha,
        "full_driver_build": True, "ci_clean": True, "toolchain_unchanged": True,
        "build_exit_code": 0, "source_recheck_exit_code": 0, "platform": "windows-x64",
        "save_policy": SAVE_POLICY, "status": "unverified-candidate",
        "validation": {"native_lifecycle": "not-run", "java_jni": "not-run", "minecraft": "not-run"},
        "artifacts": identities}


def static_archive_members(path):
    """Read the pinned archive's member names, never link or load the archive."""
    path = regular(path)
    if path.stat().st_size > 16 * 1024 * 1024 or build.sha(path) != STATIC_CXX_SHA:
        raise ArtifactError("runtime-identity")
    data = path.read_bytes()
    if not data.startswith(b"!<arch>\n"):
        raise ArtifactError("runtime-identity")
    offset, table, names = 8, b"", set()
    while offset < len(data):
        head = data[offset:offset + 60]
        if len(head) != 60 or head[-2:] != b"`\n":
            raise ArtifactError("runtime-identity")
        try:
            length = int(head[48:58].decode("ascii").strip())
            name = head[:16].decode("ascii").strip()
            body = offset + 60
            if length < 0 or body + length > len(data):
                raise ValueError()
            if name == "//":
                table = data[body:body + length]
            elif name.startswith("/") and name[1:].isdigit():
                index = int(name[1:])
                if index >= len(table) or (index and table[index - 1] not in (0, 10)):
                    raise ValueError()
                # LLVM's COFF archive uses NUL, GNU ar uses slash-newline.
                ends = [end for delimiter in (b"\0", b"/\n") if (end := table.find(delimiter, index)) >= 0]
                end = min(ends)
                names.add(table[index:end].decode("ascii").lower())
            elif name not in ("/", "/SYM64/"):
                names.add(name.removesuffix("/").lower())
        except (ValueError, UnicodeError) as error:
            raise ArtifactError("runtime-identity") from error
        offset = body + length + length % 2
    if offset != len(data) or not names or any(not re.fullmatch(r"[a-z0-9_.-]+\.obj", n) for n in names):
        raise ArtifactError("runtime-identity")
    return names


def audit_link_map(path, members):
    path = regular(path)
    if not 1 <= path.stat().st_size <= 1024 * 1024 * 1024:
        raise ArtifactError("link-map")
    tls = set()
    with path.open("r", encoding="utf-8", errors="strict") as stream:
        for index, line in enumerate(stream):
            if len(line) > 1024 * 1024:
                raise ArtifactError("link-map")
            if index == 0 and line.split() != ["Address", "Size", "Align", "Out", "In", "Symbol"]:
                raise ArtifactError("link-map")
            lower = line.lower()
            if any(x in lower for x in ("emutls", "winpthread", "libstdc++", "libgcc", "libc++.a(", "libc++abi.a(")):
                raise ArtifactError("link-map")
            # LLD drops archive filenames in this map format but keeps members.
            for obj in re.findall(r"(?:^|[\\/\s(])([a-z0-9_.-]+\.obj)(?=[:)])", lower):
                if obj in members:
                    raise ArtifactError("link-map")
            tail = lower.split()[-1:]  # Require standalone output/symbol fields.
            if tail and tail[0] in (".tls", "_tls_used", "_tls_index"):
                tls.add(tail[0])
    if tls != {".tls", "_tls_used", "_tls_index"}:
        raise ArtifactError("link-map")


def shared_evidence(root, msys, core, expected):
    root, msys = directory(root), directory(msys)
    receipt_path = next(root.glob("build-*-finished.json"))
    receipt = unique_json(receipt_path)
    if directory(receipt.get("portable_msys", "")) != msys:
        raise ArtifactError("runtime-identity")
    tool_path = regular(root.parent / "toolchain-receipt.json")
    tool = unique_json(tool_path)
    import ci_prepare as ci
    lock = ci.load_lock(ci.lock_path("clang64-shared"))
    if (digest(receipt.get("toolchain_receipt_sha256")) != build.sha(tool_path)
            or tool.get("schema") != "piq-mame-ci-toolchain-receipt-1"
            or tool.get("status") != "ready" or tool.get("profile") != "clang64-shared"
            or directory(tool.get("msys_root", "")) != msys
            or digest(tool.get("lock_sha256")) != build.sha(ci.lock_path("clang64-shared"))
            or tool.get("packages") != lock["base_manifest"] | {p["name"]: p["version"] for p in lock["packages"]}
            or tool.get("tool_files") != ci.tool_files(msys, "clang64-shared")):
        raise ArtifactError("runtime-identity")
    preflight_path = regular(root / "shared-preflight.json")
    preflight_sha = build.sha(preflight_path)
    preflight = unique_json(preflight_path)
    if (digest(receipt.get("shared_preflight_sha256")) != preflight_sha
            or preflight.get("schema") != "piq-mame-shared-preflight-1"
            or preflight.get("status") != "passed" or preflight.get("profile") != "clang64-shared"
            or preflight.get("core_executed") is not False or preflight.get("full_build") is not False
            or preflight.get("source_receipt_sha256") != receipt["source_receipt_sha256"]
            or preflight.get("toolchain_receipt_sha256") != receipt["toolchain_receipt_sha256"]
            or digest(preflight.get("preflight_sha256")) != build.sha(Path(__file__).with_name("preflight_shared.py"))):
        raise ArtifactError("shared-preflight")
    link = receipt.get("link_map")
    path = regular(root / "mame/build/mame.map")
    if (not isinstance(link, dict) or set(link) != {"path", "bytes", "sha256"}
            or regular(link["path"]) != path or require_size(link["bytes"]) != path.stat().st_size
            or digest(link["sha256"]) != build.sha(path)):
        raise ArtifactError("link-map")
    audit_link_map(path, static_archive_members(msys / "clang64/lib/libc++.a"))
    if (build.sha(path) != digest(link["sha256"]) or build.sha(preflight_path) != preflight_sha
            or build.sha(tool_path) != digest(receipt["toolchain_receipt_sha256"])):
        raise ArtifactError("link-map")
    imports = probe.verify_core(core, expected, "clang64-shared")
    evidence = {"schema": "piq-mame-static-link-1", "status": "passed", "core_sha256": expected,
        "map_sha256": digest(link["sha256"]), "map_bytes": link["bytes"],
        "static_libcxx_sha256": STATIC_CXX_SHA, "native_tls_map": True,
        "forbidden_runtime_map_entries": 0, "imports": imports["imports"],
        "delayed_imports": imports["delayed_imports"]}
    return {"shared_preflight_sha256": preflight_sha, "toolchain_receipt_sha256": build.sha(tool_path),
            "static_link": evidence}


def check_manifest(value, profile="gcc-static"):
    shared = profile == "clang64-shared"
    if profile not in probe.PROFILES or set(value) != (SHARED_KEYS if shared else MANIFEST_KEYS):
        raise ArtifactError("manifest")
    fixed = {"schema": SHARED_SCHEMA if shared else SCHEMA, "run_commit": run_commit(), "upstream_commit": build.COMMIT,
        "upstream_version": "0.289", "candidate": "piq-lifecycle1-clang64-shared" if shared else "piq-lifecycle1", "platform": "windows-x64",
        "save_policy": SAVE_POLICY, "status": "unverified-candidate",
        "validation": {"native_lifecycle": "not-run", "java_jni": "not-run", "minecraft": "not-run"}}
    if any(value[key] != wanted for key, wanted in fixed.items()):
        raise ArtifactError("manifest")
    for key in ("full_driver_build", "ci_clean", "toolchain_unchanged"):
        if value[key] is not True:
            raise ArtifactError("manifest")
    for key in ("build_exit_code", "source_recheck_exit_code"):
        require_zero(value[key], "manifest")
    if (digest(value["upstream_source_zip_sha256"]) != build.SOURCE_SHA256
            or digest(value["patch_sha256"]) != build.PATCH_SHA256
            or build.sha(regular(build.PATCH)) != build.PATCH_SHA256):
        raise ArtifactError("manifest")
    for key in ("source_receipt_sha256", "build_receipt_sha256"):
        digest(value[key])
    artifacts = value["artifacts"]
    names = {"core": "core.dll", "source": "candidate-source.zip"}
    if shared:
        names.update(runtime=probe.RUNTIME_NAME, **{name: name for name in LICENSES})
    if not isinstance(artifacts, dict) or set(artifacts) != set(names):
        raise ArtifactError("manifest")
    for key, name in names.items():
        item = artifacts[key]
        if not isinstance(item, dict) or set(item) != {"file", "bytes", "sha256"} or item["file"] != name:
            raise ArtifactError("manifest")
        digest(item["sha256"])
        require_size(item["bytes"])
        fixed_identity = LICENSES.get(name)
        if key == "runtime":
            fixed_identity = (probe.RUNTIME_BYTES, probe.RUNTIME_SHA.upper())
        if fixed_identity and (item["bytes"], digest(item["sha256"])) != fixed_identity:
            raise ArtifactError("runtime-identity")
    if shared:
        if value["profile"] != profile or value["patches"] != build.patch_identities(profile):
            raise ArtifactError("profile")
        for key in ("shared_preflight_sha256", "toolchain_receipt_sha256"):
            digest(value[key])
        evidence = value["static_link"]
        if (not isinstance(evidence, dict) or set(evidence) != {"schema", "status", "core_sha256", "map_sha256",
                "map_bytes", "static_libcxx_sha256", "native_tls_map", "forbidden_runtime_map_entries", "imports", "delayed_imports"}
                or evidence["schema"] != "piq-mame-static-link-1" or evidence["status"] != "passed"
                or evidence["core_sha256"] != artifacts["core"]["sha256"]
                or evidence["static_libcxx_sha256"] != STATIC_CXX_SHA or evidence["native_tls_map"] is not True):
            raise ArtifactError("manifest")
        digest(evidence["map_sha256"])
        require_size(evidence["map_bytes"])
        require_zero(evidence["forbidden_runtime_map_entries"], "manifest")
        for key in ("imports", "delayed_imports"):
            entries = evidence[key]
            if (not isinstance(entries, list) or not all(isinstance(x, str) for x in entries)
                    or entries != sorted(set(entries)) or set(entries) - probe.SYSTEM_IMPORTS - {probe.RUNTIME_NAME}):
                raise ArtifactError("manifest")
        if probe.RUNTIME_NAME not in evidence["imports"]:
            raise ArtifactError("manifest")


def verify(output, profile="gcc-static"):
    output = directory(output)
    files = SHARED_FILES if profile == "clang64-shared" else FILES
    if profile not in probe.PROFILES or {path.name for path in output.iterdir()} != files:
        raise ArtifactError("artifact-layout")
    for name in files:
        regular(output / name)
    manifest = unique_json(output / "candidate.json")
    check_manifest(manifest, profile)
    for item in manifest["artifacts"].values():
        path = regular(output / item["file"])
        if path.stat().st_size != item["bytes"] or build.sha(path) != digest(item["sha256"]):
            raise ArtifactError("core-identity" if item["file"] == "core.dll" else "source-archive")
    expected = digest(manifest["artifacts"]["core"]["sha256"])
    try:
        inspected = probe.verify_core(output / "core.dll", expected, profile)
        if profile == "clang64-shared":
            probe.verify_runtime(output / probe.RUNTIME_NAME)
            if any(inspected[key] != manifest["static_link"][key] for key in ("imports", "delayed_imports")):
                raise ArtifactError("core-identity")
    except probe.ProbeError as error:
        raise ArtifactError("core-identity") from error
    return expected


def stage(build_root, output, profile="gcc-static", msys_root=None):
    commit = run_commit()
    if profile not in probe.PROFILES or (profile == "clang64-shared") != (msys_root is not None):
        raise ArtifactError("profile")
    result = inspect_build(build_root, profile) if profile == "clang64-shared" else inspect_build(build_root)
    core, source, source_receipt_sha, finished_sha, identities = result
    output = probe.safe_path(output)
    if output.exists():
        raise ArtifactError("existing-output")
    manifest = manifest_for(source_receipt_sha, finished_sha, identities, commit)
    copies = [(core, "core.dll"), (source, "candidate-source.zip")]
    if profile == "clang64-shared":
        evidence = shared_evidence(build_root, msys_root, core, identities["core"]["sha256"])
        runtime = regular(directory(msys_root) / "clang64/bin" / probe.RUNTIME_NAME)
        probe.verify_runtime(runtime)
        copies.append((runtime, probe.RUNTIME_NAME))
        identities["runtime"] = {"file": probe.RUNTIME_NAME, "bytes": probe.RUNTIME_BYTES, "sha256": probe.RUNTIME_SHA.upper()}
        for name, (size, expected) in LICENSES.items():
            origin = regular(LICENSE_ROOT / name)
            if origin.stat().st_size != size or build.sha(origin) != expected:
                raise ArtifactError("runtime-identity")
            copies.append((origin, name))
            identities[name] = {"file": name, "bytes": size, "sha256": expected}
        manifest.update(schema=SHARED_SCHEMA, profile=profile, candidate="piq-lifecycle1-clang64-shared",
                        patches=build.patch_identities(profile), **evidence)
        # A checked build receipt must not change while the additional gates run.
        finished = list(directory(build_root).glob("build-*-finished.json"))
        if len(finished) != 1 or build.sha(finished[0]) != finished_sha:
            raise ArtifactError("build-receipt")
    check_manifest(manifest, profile)
    output.mkdir(parents=True, exist_ok=False)
    for origin, name in copies:
        with origin.open("rb") as reader, (output / name).open("xb") as writer:
            shutil.copyfileobj(reader, writer, length=1024 * 1024)
    probe.save_new(output / "candidate.json", manifest)
    verify(output, profile)  # Rehash the copies, not just the original files.
    return manifest


class Parser(argparse.ArgumentParser):
    def error(self, message):
        raise ArtifactError("arguments")


def main(argv=None):
    parser = Parser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    prepare = commands.add_parser("stage")
    prepare.add_argument("--build-root", required=True, type=Path)
    prepare.add_argument("--output", required=True, type=Path)
    prepare.add_argument("--profile", choices=probe.PROFILES, default="gcc-static")
    prepare.add_argument("--msys-root", type=Path)
    check = commands.add_parser("verify")
    check.add_argument("--directory", required=True, type=Path)
    check.add_argument("--profile", choices=probe.PROFILES, default="gcc-static")
    try:
        args = parser.parse_args(argv)
        if args.command == "stage":
            stage(args.build_root, args.output, args.profile, args.msys_root)
            print(json.dumps({"status": "staged", "files": len(SHARED_FILES if args.profile == "clang64-shared" else FILES)}))
        else:
            print(verify(args.directory, args.profile))  # This stdout is intentionally SHA only.
        return 0
    except Exception as error:
        code = error.code if isinstance(error, ArtifactError) else "io-error"
        print(json.dumps({"status": "failed", "error": code}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
