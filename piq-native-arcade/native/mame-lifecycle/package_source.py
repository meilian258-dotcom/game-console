"""Package corresponding candidate source without generated or private files."""
from __future__ import annotations

import argparse
import contextlib
import hashlib
import json
from pathlib import Path, PurePosixPath
import stat
import zipfile

from build_candidate import (CHANGED, COMMIT, PATCH, PATCH_SHA256, PROFILES, SOURCE_FILES,
                             SOURCE_SHA256, changed_files, patch_identities,
                             validate_source_profile, sha)

HELPERS = ("lifecycle-4fc9a931.patch", "build_candidate.py", "package_source.py",
           "ci_prepare.py", "ci-toolchain-lock.json", "test_ci_build.py",
           "test_build_diagnostics.py", "BUILDING.txt", "LICENSE")


def helpers(profile: str = "gcc-static") -> tuple[str, ...]:
    if profile not in PROFILES:
        raise ValueError("Unknown MAME source profile")
    return HELPERS + (("clang64-shared-4fc9a931.patch", "ci-clang64-toolchain-lock.json",
                       "preflight_shared.py", "test_preflight_shared.py",
                       "test_shared_build.py", "CLANG64-SHARED.txt")
                      if profile == "clang64-shared" else ())


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-zip", type=Path, required=True)
    parser.add_argument("--build-root", type=Path, required=True)
    parser.add_argument("--profile", choices=PROFILES, default="gcc-static")
    output_mode = parser.add_mutually_exclusive_group(required=True)
    output_mode.add_argument("--output", type=Path)
    output_mode.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    if sha(args.source_zip) != SOURCE_SHA256:
        raise ValueError("Pinned source archive mismatch")
    if sha(PATCH) != PATCH_SHA256:
        raise ValueError("Pinned lifecycle patch mismatch")
    receipt = json.loads((args.build_root / "source-receipt.json").read_text(encoding="utf-8"))
    validate_source_profile(receipt, args.profile)
    changed = changed_files(args.profile)
    source = args.build_root / "mame"
    original_prefix = f"mame-{COMMIT}/"
    output_prefix = "mame-0.289-piq-lifecycle1/"
    checked = 0
    partial = None
    if not args.verify_only:
        if args.output.exists():
            raise FileExistsError("Never replace an earlier source artifact")
        partial = args.output.with_name(args.output.name + ".partial")
    destination = (contextlib.nullcontext() if args.verify_only else
                   zipfile.ZipFile(partial, "x", compression=zipfile.ZIP_DEFLATED, compresslevel=6))
    with zipfile.ZipFile(args.source_zip) as original, destination as output:
        for entry in original.infolist():
            if not entry.filename.startswith(original_prefix):
                raise ValueError("Unexpected upstream archive member")
            name = entry.filename[len(original_prefix):]
            if not name or entry.is_dir():
                continue
            rel = PurePosixPath(name)
            if rel.is_absolute() or ".." in rel.parts or "\\" in name or ":" in name:
                raise ValueError("Unsafe archive member")
            data = original.read(entry)
            expected = hashlib.sha256(data).hexdigest().upper()
            if stat.S_ISLNK(entry.external_attr >> 16):
                if name not in {"3rdparty/zstd/tests/cli-tests/bin/unzstd", "3rdparty/zstd/tests/cli-tests/bin/zstdcat"} or data != b"zstd":
                    raise ValueError("Unexpected source symlink")
                expected = hashlib.sha256(original.read(original_prefix + str(rel.parent / "zstd"))).hexdigest().upper()
            if name in changed:
                change = receipt["changed_files"][name]
                if expected != change["before"]:
                    raise ValueError("Changed-file original hash mismatch")
                expected = change["after"]
                data = (source / name).read_bytes()
            if sha(source / name) != expected:
                raise ValueError("Unexpected source modification: " + name)
            target = zipfile.ZipInfo(output_prefix + name, date_time=entry.date_time)
            target.create_system = entry.create_system
            target.external_attr = entry.external_attr
            target.compress_type = zipfile.ZIP_DEFLATED
            if output is not None:
                output.writestr(target, data)
            checked += 1
        if checked != SOURCE_FILES:
            raise ValueError("Unexpected input file count")
        if args.verify_only:
            print(json.dumps({"source_files": checked, "status": "all pinned source inputs verified"}))
            return
        helper_hashes = {}
        for name in helpers(args.profile):
            helper = Path(__file__).with_name(name)
            data = helper.read_bytes()
            helper_hashes[name] = hashlib.sha256(data).hexdigest().upper()
            target = zipfile.ZipInfo(output_prefix + "piq-lifecycle/" + name, date_time=(2026, 10, 7, 0, 0, 0))
            target.compress_type = zipfile.ZIP_DEFLATED
            output.writestr(target, data)
        public = {"upstream_repository": "https://github.com/libretro/mame", "upstream_commit": COMMIT,
                  "upstream_source_zip_sha256": SOURCE_SHA256, "upstream_version": "0.289",
                  "candidate": "piq-lifecycle1", "verified_input_files": checked,
                  "profile": args.profile, "patches": patch_identities(args.profile),
                  "patch_sha256": receipt["patch_sha256"], "changed_files": receipt["changed_files"],
                  "helpers": helper_hashes, "full_driver_build": True, "save_format_changes": False,
                  "status": "source candidate; binary behavior validation required"}
        target = zipfile.ZipInfo(output_prefix + "piq-lifecycle/source.json", date_time=(2026, 10, 7, 0, 0, 0))
        target.compress_type = zipfile.ZIP_DEFLATED
        output.writestr(target, json.dumps(public, indent=2).encode("utf-8"))
    # Do not expose a failed partial archive as the corresponding-source artifact.
    if args.output.exists():
        raise FileExistsError("Source output appeared during packaging")
    partial.rename(args.output)
    print(json.dumps({"path": str(args.output), "bytes": args.output.stat().st_size, "sha256": sha(args.output), "source_files": checked}))


if __name__ == "__main__":
    main()
