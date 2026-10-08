"""Offline profile/provenance tests. Optional fixed-ZIP patch replay uses Git only."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PureWindowsPath
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import zipfile

import build_candidate as build
import package_source


class ProfileTests(unittest.TestCase):
    def source_record(self, profile="gcc-static"):
        return {"commit": build.COMMIT, "patch_sha256": build.PATCH_SHA256,
                "profile": profile, "patches": build.patch_identities(profile),
                "changed_files": {name: {} for name in build.changed_files(profile)}}

    def test_original_lifecycle_identity_unchanged(self):
        self.assertEqual(build.sha(build.PATCH),
                         "802DF281F5B5342992A993937E6CF620A5CB412F83FB0D055332A8360EEFD27E")
        self.assertEqual(len(build.CHANGED), 4)
        self.assertEqual(build.patches(), [(build.PATCH, build.PATCH_SHA256)])

    def test_shared_is_exactly_one_additional_build_file(self):
        self.assertEqual(build.changed_files("clang64-shared") - build.CHANGED,
                         {"scripts/genie.lua"})
        self.assertEqual(build.sha(build.SHARED_PATCH), build.SHARED_PATCH_SHA256)
        self.assertEqual(build.patches("clang64-shared"),
                         [(build.PATCH, build.PATCH_SHA256),
                          (build.SHARED_PATCH, build.SHARED_PATCH_SHA256)])

    def test_unknown_profiles_fail_closed(self):
        for function in (build.patches, build.changed_files, build.patch_identities,
                         package_source.helpers):
            with self.subTest(function=function), self.assertRaises(ValueError):
                function("unlocked")

    def test_original_receipts_remain_gcc_only(self):
        record = self.source_record()
        del record["profile"], record["patches"]
        build.validate_source_profile(record, "gcc-static")
        with self.assertRaises(ValueError):
            build.validate_source_profile(record, "clang64-shared")

    def test_shared_receipt_requires_exact_profile_patch_and_file_set(self):
        record = self.source_record("clang64-shared")
        build.validate_source_profile(record, "clang64-shared")
        for field, replacement in (("profile", "gcc-static"), ("patches", []),
                                   ("changed_files", {name: {} for name in build.CHANGED}),
                                   ("commit", "0" * 40), ("patch_sha256", "0" * 64)):
            with self.subTest(field=field), self.assertRaises(ValueError):
                build.validate_source_profile(record | {field: replacement}, "clang64-shared")
        for field in ("profile", "patches"):
            incomplete = dict(record)
            del incomplete[field]
            with self.assertRaises(ValueError):
                build.validate_source_profile(incomplete, "clang64-shared")

    def test_shared_receipt_rejected_as_gcc(self):
        with self.assertRaises(ValueError):
            build.validate_source_profile(self.source_record("clang64-shared"), "gcc-static")

    def test_no_environment_selected_profile(self):
        with patch.dict(os.environ, {"MAME_PROFILE": "clang64-shared"}):
            self.assertEqual(build.selected_profile(argparse.Namespace()), "gcc-static")

    def test_shared_command_binds_recursive_tools_and_native_64_bit_target(self):
        command = build.build_command(PureWindowsPath("D:/ci/build"),
                                      PureWindowsPath("D:/ci/msys64"), 4, True, "clang64-shared")
        for term in ("PATH=/clang64/bin:/usr/bin", "MSYSTEM=CLANG64", "MINGW_PREFIX=/clang64",
                     "MINGW64=/clang64", "LIBRETRO_CPU=x86_64", "CC=clang", "CXX=clang++",
                     "AR=llvm-ar", "OVERRIDE_AR=llvm-ar", "WINDRES=llvm-windres",
                     "MAP=1",
                     "TARGET=mame SUBTARGET=mame", "PTR64=1", "IGNORE_GIT=1",
                     "PYTHON_EXECUTABLE=python3", "-j4", "18000s", "ld.lld --version"):
            self.assertIn(term, command)
        for forbidden in ("COMPILER=", "PREMAKE_TOOLCHAIN=", "CC=gcc", "CXX=g++",
                          "SOURCES=", "SOUND_DISABLE_THREADING", "PIQ_SNAPSHOT"):
            self.assertNotIn(forbidden, command)
        for prefix in ("D:/ci/build", "/d/ci/build", "D:/ci/msys64", "/d/ci/msys64"):
            self.assertIn("-ffile-prefix-map=" + prefix, command)
            self.assertIn("-fdebug-prefix-map=" + prefix, command)

    def test_default_command_stays_gcc(self):
        values = (PureWindowsPath("D:/ci/build"), PureWindowsPath("D:/ci/msys64"), 4, True)
        self.assertEqual(build.build_command(*values), build.build_command(*values, "gcc-static"))
        self.assertNotIn("clang", build.build_command(*values))

    def test_resume_shared_rejected_before_any_preparation_or_build(self):
        args = ["build_candidate.py", "--profile", "clang64-shared", "--resume",
                "--source-zip", "source.zip", "--msys-root", "msys64", "--build-root", "build",
                "--git", "git"]
        with patch("sys.argv", args), patch.object(build, "prepare") as prepare, \
                patch.object(build.subprocess, "run") as run, self.assertRaisesRegex(ValueError, "no resume"):
            build.main()
        prepare.assert_not_called()
        run.assert_not_called()


class PreflightGateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="piq-shared-gate-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.receipt = self.root / "toolchain-receipt.json"
        self.receipt.write_bytes(b"inert toolchain receipt fixture")
        (self.root / "source-receipt.json").write_bytes(b"inert source receipt fixture")
        self.args = argparse.Namespace(toolchain_receipt=self.receipt)
        self.record = {"schema": "piq-mame-shared-preflight-1", "status": "passed",
                       "profile": "clang64-shared",
                       "source_receipt_sha256": build.sha(self.root / "source-receipt.json"),
                       "toolchain_receipt_sha256": build.sha(self.receipt),
                       "preflight_sha256": "A" * 64}

    def invoke(self, record):
        (self.root / "shared-preflight.json").write_text(json.dumps(record), encoding="utf-8")
        real_sha = build.sha

        def fixture_sha(path):
            return "A" * 64 if Path(path).name == "preflight_shared.py" else real_sha(path)

        with patch.object(build, "sha", side_effect=fixture_sha):
            return build.validate_shared_preflight(self.args, self.root)

    def test_exact_preflight_binding_accepted(self):
        self.assertEqual(self.invoke(self.record), self.record)

    def test_each_binding_or_status_mismatch_rejected(self):
        for field in self.record:
            with self.subTest(field=field), self.assertRaises(ValueError):
                self.invoke(self.record | {field: "wrong"})

    def test_changed_source_after_preflight_rejected(self):
        (self.root / "source-receipt.json").write_bytes(b"different fixture")
        with self.assertRaises(ValueError):
            self.invoke(self.record)

    def test_missing_toolchain_receipt_rejected(self):
        self.args.toolchain_receipt = None
        with self.assertRaises(ValueError):
            self.invoke(self.record)

    def test_failed_preflight_prevents_full_make_process(self):
        target = self.root / "fresh"
        argv = ["build_candidate.py", "--profile", "clang64-shared",
                "--source-zip", "source.zip", "--msys-root", "msys64", "--build-root", str(target),
                "--git", "git", "--toolchain-receipt", str(self.receipt)]

        def prepare(args, root):
            root.mkdir()
            return {}

        with patch("sys.argv", argv), patch.object(build, "prepare", side_effect=prepare), \
                patch.object(build.shutil, "disk_usage", return_value=SimpleNamespace(free=100 * 1024**3)), \
                patch.object(build.subprocess, "run", side_effect=subprocess.CalledProcessError(1, "preflight")) as run, \
                patch.object(build.subprocess, "Popen") as full, self.assertRaises(subprocess.CalledProcessError):
            build.main()
        self.assertEqual(run.call_count, 1)
        self.assertEqual(Path(run.call_args.args[0][2]).name, "preflight_shared.py")
        self.assertTrue(run.call_args.kwargs["check"])
        full.assert_not_called()

    def test_source_prepare_only_never_calls_preflight_or_full_make(self):
        target = self.root / "fresh"
        argv = ["build_candidate.py", "--profile", "clang64-shared", "--prepare-only",
                "--source-zip", "source.zip", "--msys-root", "msys64", "--build-root", str(target),
                "--git", "git"]
        with patch("sys.argv", argv), patch.object(build, "prepare", return_value={}), \
                patch.object(build.shutil, "disk_usage", return_value=SimpleNamespace(free=100 * 1024**3)), \
                patch.object(build.subprocess, "run") as run, patch.object(build.subprocess, "Popen") as full:
            build.main()
        run.assert_not_called()
        full.assert_not_called()


class SharedCiReceiptTests(unittest.TestCase):
    def setUp(self):
        import ci_prepare as ci
        self.ci = ci
        self.tmp = tempfile.TemporaryDirectory(prefix="piq-shared-ci-test-")
        self.addCleanup(self.tmp.cleanup)
        self.parent = Path(self.tmp.name).resolve()
        self.root = self.parent / "build"
        self.args = argparse.Namespace(profile="clang64-shared", resume=False, prepare_only=False, jobs=4,
                                       toolchain_receipt=self.parent / "toolchain-receipt.json",
                                       source_zip=self.parent / "inputs/mame-upstream.zip",
                                       msys_root=self.parent / "msys64")
        lock = ci.load_lock(ci.lock_path("clang64-shared"))
        config = self.args.msys_root / "etc/pacman.d/gnupg/gpg.conf"
        config.parent.mkdir(parents=True)
        config.write_bytes(b"inert offline policy fixture")
        self.record = {"schema": "piq-mame-ci-toolchain-receipt-1", "status": "ready",
                       "profile": "clang64-shared", "lock_sha256": build.sha(ci.lock_path("clang64-shared")),
                       "packages": lock["base_manifest"] | {p["name"]: p["version"] for p in lock["packages"]},
                       "preparer_sha256": build.sha(Path(ci.__file__)),
                       "offline_signature_checks": len(lock["packages"]),
                       "offline_keyring_policy": {"after_sha256": build.sha(config)},
                       "cache_reused": False, "msys_root": str(self.args.msys_root),
                       "tool_files": {"clang++": "inert fixture identity"}}

    def invoke(self, record):
        self.args.toolchain_receipt.write_text(json.dumps(record), encoding="utf-8")
        with patch.object(self.ci, "require_hosted_ci"), \
                patch.object(self.ci, "tool_files", return_value=self.record["tool_files"]) as files:
            result = build.validate_ci(self.args, self.root)
        files.assert_called_once_with(self.args.msys_root, "clang64-shared")
        return result

    def test_exact_shared_lock_profile_accepted(self):
        self.assertEqual(self.invoke(self.record), self.record)

    def test_gcc_lock_or_missing_shared_profile_rejected(self):
        for replacement in ({"profile": "gcc-static"},
                            {"lock_sha256": build.sha(self.ci.LOCK)},
                            {"packages": {}}, {"tool_files": {}}):
            with self.subTest(replacement=replacement), self.assertRaisesRegex(ValueError, "provenance"):
                self.invoke(self.record | replacement)
        missing = dict(self.record)
        del missing["profile"]
        with self.assertRaisesRegex(ValueError, "provenance"):
            self.invoke(missing)

    def test_shared_ci_retains_new_root_no_resume_no_prepare_only_gate(self):
        for field in ("resume", "prepare_only"):
            setattr(self.args, field, True)
            with self.assertRaisesRegex(ValueError, "no resume"):
                self.invoke(self.record)
            setattr(self.args, field, False)
        self.root.mkdir()
        with self.assertRaisesRegex(ValueError, "no resume"):
            self.invoke(self.record)


class SharedCorrespondingSourceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="piq-shared-source-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source_zip = self.root / "original.zip"
        self.source = self.root / "mame"
        self.changed = {}
        with zipfile.ZipFile(self.source_zip, "x") as archive:
            for name in sorted(build.changed_files("clang64-shared")) + ["COPYING"]:
                before = ("original " + name).encode()
                after = ("modified " + name).encode() if name != "COPYING" else before
                archive.writestr(f"mame-{build.COMMIT}/" + name, before)
                path = self.source / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(after)
                if before != after:
                    self.changed[name] = {"before": hashlib.sha256(before).hexdigest().upper(),
                                          "after": hashlib.sha256(after).hexdigest().upper()}
        self.record = {"commit": build.COMMIT, "patch_sha256": build.PATCH_SHA256,
                       "profile": "clang64-shared", "patches": build.patch_identities("clang64-shared"),
                       "changed_files": self.changed}
        (self.root / "source-receipt.json").write_text(json.dumps(self.record), encoding="utf-8")

    def invoke(self, *mode):
        argv = ["package_source.py", "--profile", "clang64-shared", "--source-zip", str(self.source_zip),
                "--build-root", str(self.root), *mode]
        with patch("sys.argv", argv), patch.object(package_source, "SOURCE_SHA256", build.sha(self.source_zip)), \
                patch.object(package_source, "SOURCE_FILES", 6), patch("sys.stdout", new_callable=io.StringIO):
            package_source.main()

    def test_shared_five_file_source_verified(self):
        self.invoke("--verify-only")

    def test_shared_build_script_tampering_rejected(self):
        (self.source / "scripts/genie.lua").write_bytes(b"modified after receipt")
        with self.assertRaisesRegex(ValueError, "Unexpected source modification"):
            self.invoke("--verify-only")

    def test_shared_source_manifest_includes_both_patches_and_profile_helpers(self):
        output = self.root / "candidate-source.zip"
        self.invoke("--output", str(output))
        with zipfile.ZipFile(output) as archive:
            prefix = "mame-0.289-piq-lifecycle1/piq-lifecycle/"
            manifest = json.loads(archive.read(prefix + "source.json"))
            self.assertEqual(manifest["profile"], "clang64-shared")
            self.assertEqual(manifest["patches"], build.patch_identities("clang64-shared"))
            self.assertEqual(set(manifest["changed_files"]), build.changed_files("clang64-shared"))
            self.assertIn(prefix + "ci-clang64-toolchain-lock.json", archive.namelist())
            self.assertIn(prefix + "clang64-shared-4fc9a931.patch", archive.namelist())
            self.assertIn(prefix + "preflight_shared.py", archive.namelist())
            self.assertIsNone(archive.testzip())


@unittest.skipUnless(os.environ.get("PIQ_TEST_MAME_SOURCE_ZIP") and shutil.which("git"),
                     "set PIQ_TEST_MAME_SOURCE_ZIP to the already verified fixed archive for Git replay")
class FixedUpstreamPatchTests(unittest.TestCase):
    def test_two_patches_replay_without_overlap_on_fixed_official_source(self):
        archive_path = Path(os.environ["PIQ_TEST_MAME_SOURCE_ZIP"])
        self.assertEqual(build.sha(archive_path), build.SOURCE_SHA256)
        with tempfile.TemporaryDirectory(prefix="piq-fixed-patch-") as directory:
            root = Path(directory)
            originals = {}
            with zipfile.ZipFile(archive_path) as archive:
                for name in sorted(build.changed_files("clang64-shared")):
                    originals[name] = archive.read(f"mame-{build.COMMIT}/" + name)
                    path = root / name
                    path.parent.mkdir(parents=True, exist_ok=True)
                    path.write_bytes(originals[name])
            for item, _ in build.patches("clang64-shared"):
                for flags in (("--check",), ()):
                    subprocess.run([shutil.which("git"), "-c", "core.autocrlf=false", "apply",
                                    "--recount", *flags, str(item)],
                                   cwd=root, check=True, capture_output=True, timeout=30)
            changed = {name for name, before in originals.items() if (root / name).read_bytes() != before}
            self.assertEqual(changed, build.changed_files("clang64-shared"))
            for item, _ in reversed(build.patches("clang64-shared")):
                subprocess.run([shutil.which("git"), "-c", "core.autocrlf=false", "apply",
                                "--recount", "--reverse", str(item)],
                               cwd=root, check=True, capture_output=True, timeout=30)
            for name, before in originals.items():
                self.assertEqual((root / name).read_bytes(), before)


if __name__ == "__main__":
    unittest.main()
