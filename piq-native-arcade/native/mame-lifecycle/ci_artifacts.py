"""Stage and verify the restricted candidate artifact passed between CI jobs.

No native libraries are loaded. Raw build receipts and logs never enter the
three-file output. Both commands require the current job's GITHUB_SHA.
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
SAVE_POLICY = "separate-identity-no-migration"
ERRORS = frozenset(("arguments", "run-commit", "input-path", "existing-output",
    "build-receipt", "source-receipt", "source-archive", "core-identity",
    "artifact-layout", "manifest", "io-error"))
MANIFEST_KEYS = frozenset(("schema", "run_commit", "upstream_commit", "upstream_version",
    "candidate", "upstream_source_zip_sha256", "patch_sha256", "source_receipt_sha256",
    "build_receipt_sha256", "full_driver_build", "ci_clean", "toolchain_unchanged",
    "build_exit_code", "source_recheck_exit_code", "platform", "save_policy",
    "status", "validation", "artifacts"))


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


def pinned_source(receipt, root):
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
    if not isinstance(changed, dict) or set(changed) != build.CHANGED:
        raise ArtifactError("source-receipt")
    for name, entry in changed.items():
        if not isinstance(entry, dict) or set(entry) != {"before", "after"}:
            raise ArtifactError("source-receipt")
        before, after = digest(entry["before"]), digest(entry["after"])
        if before == after or build.sha(regular(root / "mame" / name)) != after:
            raise ArtifactError("source-receipt")


def inspect_build(root):
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
    require_zero(receipt.get("exit_code"), "build-receipt")
    require_zero(receipt.get("source_recheck_exit_code"), "build-receipt")
    source_path = regular(root / "source-receipt.json")
    if digest(receipt.get("source_receipt_sha256")) != build.sha(source_path):
        raise ArtifactError("source-receipt")
    if digest(receipt.get("launcher_sha256")) != build.sha(regular(Path(build.__file__))):
        raise ArtifactError("build-receipt")
    pinned_source(unique_json(source_path), root)
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
        probe.verify_core(core, expected)
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


def check_manifest(value):
    if set(value) != MANIFEST_KEYS:
        raise ArtifactError("manifest")
    fixed = {"schema": SCHEMA, "run_commit": run_commit(), "upstream_commit": build.COMMIT,
        "upstream_version": "0.289", "candidate": "piq-lifecycle1", "platform": "windows-x64",
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
    if not isinstance(artifacts, dict) or set(artifacts) != {"core", "source"}:
        raise ArtifactError("manifest")
    for key, name in (("core", "core.dll"), ("source", "candidate-source.zip")):
        item = artifacts[key]
        if not isinstance(item, dict) or set(item) != {"file", "bytes", "sha256"} or item["file"] != name:
            raise ArtifactError("manifest")
        digest(item["sha256"])
        require_size(item["bytes"])


def verify(output):
    output = directory(output)
    if {path.name for path in output.iterdir()} != FILES:
        raise ArtifactError("artifact-layout")
    for name in FILES:
        regular(output / name)
    manifest = unique_json(output / "candidate.json")
    check_manifest(manifest)
    for item in manifest["artifacts"].values():
        path = regular(output / item["file"])
        if path.stat().st_size != item["bytes"] or build.sha(path) != digest(item["sha256"]):
            raise ArtifactError("core-identity" if item["file"] == "core.dll" else "source-archive")
    expected = digest(manifest["artifacts"]["core"]["sha256"])
    try:
        probe.verify_core(output / "core.dll", expected)
    except probe.ProbeError as error:
        raise ArtifactError("core-identity") from error
    return expected


def stage(build_root, output):
    commit = run_commit()
    core, source, source_receipt_sha, finished_sha, identities = inspect_build(build_root)
    output = probe.safe_path(output)
    if output.exists():
        raise ArtifactError("existing-output")
    manifest = manifest_for(source_receipt_sha, finished_sha, identities, commit)
    check_manifest(manifest)
    output.mkdir(parents=True, exist_ok=False)
    for origin, name in ((core, "core.dll"), (source, "candidate-source.zip")):
        with origin.open("rb") as reader, (output / name).open("xb") as writer:
            shutil.copyfileobj(reader, writer, length=1024 * 1024)
    probe.save_new(output / "candidate.json", manifest)
    verify(output)  # Rehash the copies, not just the original files.
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
    check = commands.add_parser("verify")
    check.add_argument("--directory", required=True, type=Path)
    try:
        args = parser.parse_args(argv)
        if args.command == "stage":
            stage(args.build_root, args.output)
            print('{"status":"staged","files":3}')
        else:
            print(verify(args.directory))  # This stdout is intentionally SHA only.
        return 0
    except Exception as error:
        code = error.code if isinstance(error, ArtifactError) else "io-error"
        print(json.dumps({"status": "failed", "error": code}), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
