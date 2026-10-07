"""Offline helper tests: no package install, native compilation or core loading."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path, PureWindowsPath
import tempfile
import subprocess
from types import SimpleNamespace
import unittest
import zipfile
from unittest.mock import patch

import build_candidate as build
import ci_prepare as ci
import package_source


class DownloadTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="piq-ci-test-")
        self.addCleanup(self.tmp.cleanup)
        self.path = Path(self.tmp.name) / "input"
        self.data = b"small authenticated fixture"
        self.digest = hashlib.sha256(self.data).hexdigest().upper()

    def fetch(self, **kw):
        return ci.download("https://repo.msys2.org/test", self.path,
                           kw.pop("digest", self.digest), kw.pop("size", len(self.data)),
                           opener=lambda *a, **k: io.BytesIO(self.data), **kw)

    def test_download_verifies_bytes_and_digest(self):
        self.assertEqual(self.fetch()["sha256"], self.digest)
        self.assertEqual(self.path.read_bytes(), self.data)

    def test_digest_mismatch_is_preserved_not_retried(self):
        with self.assertRaisesRegex(ValueError, "SHA-256"):
            self.fetch(digest="0" * 64)
        self.assertEqual(self.path.read_bytes(), self.data)

    def test_short_download_rejected(self):
        with self.assertRaisesRegex(ValueError, "size mismatch"):
            self.fetch(size=len(self.data) + 1)

    def test_oversize_rejected(self):
        with self.assertRaisesRegex(ValueError, "exceeded"):
            self.fetch(limit=2)

    def test_existing_download_never_overwritten(self):
        self.path.write_bytes(b"earlier evidence")
        with self.assertRaises(FileExistsError):
            self.fetch()
        self.assertEqual(self.path.read_bytes(), b"earlier evidence")

    def test_empty_signature_rejected(self):
        self.data = b""
        with self.assertRaisesRegex(ValueError, "Empty"):
            self.fetch(digest=None, size=None)

    def test_no_untrusted_initial_urls(self):
        for url in ("http://github.com/x", "https://evil.example/x", "https://u:p@github.com/x",
                    "https://github.com:444/x", "https://github.com/x?token=example"):
            with self.subTest(url=url), self.assertRaises(ValueError):
                ci.validate_url(url)

    def test_redirect_allowlist(self):
        ci.validate_url("https://release-assets.githubusercontent.com/file?signature=example", redirected=True)
        ci.validate_url("https://codeload.github.com/libretro/mame/zip/commit", redirected=True)
        for url in ("http://repo.msys2.org/x", "https://untrusted.example/x"):
            with self.assertRaises(ValueError):
                ci.LockedRedirectHandler().redirect_request(None, None, 302, "", {}, url)


class LockAndIsolationTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="piq-ci-test-")
        self.addCleanup(self.tmp.cleanup)
        # Production CLI entry points resolve roots before validating them.
        # Windows TEMP may contain an 8.3 alias instead of its long spelling.
        self.root = Path(self.tmp.name).resolve()

    def test_ci_fixtures_normalize_noncanonical_temporary_directory(self):
        real_temporary_directory = tempfile.TemporaryDirectory

        def aliased_temporary_directory(*args, **kwargs):
            temporary = real_temporary_directory(*args, **kwargs)
            canonical = Path(temporary.name).resolve()
            # Exercise the same lexical-versus-canonical mismatch without
            # requiring NTFS short names or permission to create a symlink.
            alias = canonical.parent / ".." / canonical.parent.name / canonical.name
            self.assertNotEqual(alias, canonical)
            self.assertEqual(alias.resolve(), canonical)
            # Cleanup retains the real TemporaryDirectory's original path.
            return SimpleNamespace(name=str(alias), cleanup=temporary.cleanup)

        for name in ("test_ci_bounds", "test_ci_receipt_accepts_exact_input_set_and_rejects_drift"):
            with self.subTest(case=name), patch.object(
                    tempfile, "TemporaryDirectory", side_effect=aliased_temporary_directory):
                fixture = LockAndIsolationTests(name)
                result = unittest.TestResult()
                fixture.run(result)
                self.assertTrue(result.wasSuccessful(), result.errors + result.failures)
                self.assertEqual(fixture.root, fixture.root.resolve())

    def test_complete_lock_and_pins(self):
        lock = ci.load_lock()
        self.assertEqual(len(lock["packages"]), 38)
        self.assertEqual(len(lock["base_manifest"]), 85)
        self.assertEqual(sum(p["bytes"] for p in lock["packages"]), 120404498)
        self.assertEqual(lock["gcc_version"], "16.2.0")
        self.assertEqual(lock["python_version"], "3.14.8")
        self.assertEqual(build.sha(build.PATCH), build.PATCH_SHA256)
        self.assertEqual(ci.SOURCE_URL, f"https://github.com/libretro/mame/archive/{build.COMMIT}.zip")

    def test_duplicate_lock_rejected(self):
        lock = ci.load_lock()
        lock["packages"][1] = lock["packages"][0]
        target = self.root / "bad-lock.json"
        target.write_text(json.dumps(lock), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            ci.load_lock(target)

    def test_ci_refuses_local_and_self_hosted(self):
        for env in ({}, {"GITHUB_ACTIONS": "true", "RUNNER_OS": "Windows", "RUNNER_ENVIRONMENT": "self-hosted"}):
            with self.assertRaises(ValueError):
                ci.require_hosted_ci(self.root / "new", env)

    def test_ci_bounds(self):
        env = {"GITHUB_ACTIONS": "true", "RUNNER_OS": "Windows", "RUNNER_ENVIRONMENT": "github-hosted",
               "RUNNER_TEMP": str(self.root)}
        ci.require_hosted_ci(self.root / "new", env)
        for path in (self.root, self.root.parent / "outside", self.root / "非ASCII"):
            with self.assertRaises(ValueError):
                ci.require_hosted_ci(path, env)

    def test_manifest_duplicate_and_exact_versions(self):
        self.assertEqual(ci.parse_manifest("gcc 16.2.0-4\nmake 4.4.1-3\n"), {"gcc": "16.2.0-4", "make": "4.4.1-3"})
        with self.assertRaises(ValueError):
            ci.parse_manifest("gcc 1\ngcc 2\n")

    def test_key_refresh_is_only_narrow_change(self):
        post = self.root / "etc/post-install/07-pacman-key.post"
        post.parent.mkdir(parents=True)
        content = b"pacman-key --init\npacman-key --populate msys2\npacman-key --refresh-keys || true\n"
        post.write_bytes(content)
        result = ci.disable_key_refresh(self.root)
        self.assertEqual(post.read_bytes(), content.replace(b"--refresh-keys", b"--version"))
        self.assertNotEqual(result["before_sha256"], result["after_sha256"])
        with self.assertRaises(ValueError):
            ci.disable_key_refresh(self.root)

    def test_offline_keyring_disables_even_explicit_network_import(self):
        config = self.root / "etc/pacman.d/gnupg/gpg.conf"
        config.parent.mkdir(parents=True)
        original = b"no-greeting\n"
        config.write_bytes(original)
        receipt = ci.enforce_offline_keyring(self.root)
        actual = config.read_bytes()
        self.assertTrue(actual.startswith(original))
        for option in (b"no-auto-key-retrieve", b"no-auto-key-import", b"auto-key-locate clear", b"disable-dirmngr"):
            self.assertIn(option, actual.splitlines())
        self.assertNotEqual(receipt["before_sha256"], receipt["after_sha256"])

    def test_ci_rejects_resume_before_reading_receipt(self):
        args = argparse.Namespace(resume=True, prepare_only=False, jobs=4, toolchain_receipt=None)
        with patch.object(ci, "require_hosted_ci"), self.assertRaisesRegex(ValueError, "no resume"):
            build.validate_ci(args, self.root / "new")

    def test_ci_rejects_six_jobs(self):
        args = argparse.Namespace(resume=False, prepare_only=False, jobs=6, toolchain_receipt=None)
        with patch.object(ci, "require_hosted_ci"), self.assertRaisesRegex(ValueError, "four jobs"):
            build.validate_ci(args, self.root / "new")

    def test_ci_requires_receipt(self):
        args = argparse.Namespace(resume=False, prepare_only=False, jobs=4, toolchain_receipt=None)
        with patch.object(ci, "require_hosted_ci"), self.assertRaisesRegex(ValueError, "receipt"):
            build.validate_ci(args, self.root / "new")

    def test_ci_rejects_external_inputs(self):
        args = argparse.Namespace(resume=False, prepare_only=False, jobs=4,
                                  toolchain_receipt=self.root / "foreign.json",
                                  source_zip=self.root / "foreign.zip", msys_root=self.root / "foreign-msys")
        with patch.object(ci, "require_hosted_ci"), self.assertRaisesRegex(ValueError, "sibling"):
            build.validate_ci(args, self.root / "new")

    def test_ci_receipt_accepts_exact_input_set_and_rejects_drift(self):
        root = self.root / "build"
        args = argparse.Namespace(resume=False, prepare_only=False, jobs=4,
                                  toolchain_receipt=self.root / "toolchain-receipt.json",
                                  source_zip=self.root / "inputs/mame-upstream.zip", msys_root=self.root / "msys64")
        lock = ci.load_lock()
        key_config = args.msys_root / "etc/pacman.d/gnupg/gpg.conf"
        key_config.parent.mkdir(parents=True)
        key_config.write_bytes(b"test offline configuration")
        record = {"schema": "piq-mame-ci-toolchain-receipt-1", "status": "ready",
                  "lock_sha256": ci.sha(ci.LOCK), "cache_reused": False,
                  "preparer_sha256": build.sha(Path(ci.__file__)), "offline_signature_checks": 38,
                  "offline_keyring_policy": {"after_sha256": build.sha(key_config)},
                  "msys_root": str(args.msys_root), "tool_files": {"g++": "fake-test-hash"},
                  "packages": lock["base_manifest"] | {p["name"]: p["version"] for p in lock["packages"]}}
        args.toolchain_receipt.write_text(json.dumps(record), encoding="utf-8")
        with patch.object(ci, "require_hosted_ci"), patch.object(ci, "tool_files", return_value=record["tool_files"]):
            self.assertEqual(build.validate_ci(args, root), record)
        for field, replacement in (("cache_reused", True), ("status", "failed"), ("packages", {}), ("tool_files", {}),
                                   ("preparer_sha256", "0" * 64), ("offline_signature_checks", 0),
                                   ("offline_keyring_policy", {"after_sha256": "0" * 64})):
            bad = record | {field: replacement}
            args.toolchain_receipt.write_text(json.dumps(bad), encoding="utf-8")
            with self.subTest(field=field), patch.object(ci, "require_hosted_ci"), \
                    patch.object(ci, "tool_files", return_value=record["tool_files"]), \
                    self.assertRaisesRegex(ValueError, "provenance"):
                build.validate_ci(args, root)

    def test_shell_timeout_keeps_partial_log(self):
        log = self.root / "timeout.log"
        timeout = subprocess.TimeoutExpired("fixture", 600, output=b"before timeout\n")
        with patch.dict(os.environ, {"SystemRoot": "C:/Windows"}), \
                patch.object(ci.subprocess, "run", side_effect=timeout), self.assertRaises(subprocess.TimeoutExpired):
            ci.run_shell(self.root, "fixture", log)
        self.assertIn("before timeout", log.read_text(encoding="utf-8"))
        self.assertIn("not reusable", log.read_text(encoding="utf-8"))

    def test_shell_environment_cannot_source_injected_file(self):
        done = subprocess.CompletedProcess([], 0, "ok\n")
        with patch.dict(os.environ, {"BASH_ENV": "injected", "PYTHONPATH": "injected", "SystemRoot": "C:/Windows"}), \
                patch.object(ci.subprocess, "run", return_value=done) as run:
            ci.run_shell(self.root, "fixture", self.root / "shell.log")
        env = run.call_args.kwargs["env"]
        self.assertNotIn("BASH_ENV", env)
        self.assertNotIn("PYTHONPATH", env)
        self.assertNotIn("injected", env["PATH"])


class BuildCommandTests(unittest.TestCase):
    def test_full_driver_flags_and_privacy_maps(self):
        command = build.build_command(PureWindowsPath("D:/ci/build"), PureWindowsPath("D:/ci/msys64"), 4, True)
        self.assertIn("-j4", command)
        self.assertIn("TARGET=mame SUBTARGET=mame", command)
        self.assertIn("timeout --signal=INT --kill-after=60s 18000s", command)
        for forbidden in ("SOURCES=", "PIQ_SNAPSHOT", "SOUND_DISABLE_THREADING", "ccache"):
            self.assertNotIn(forbidden, command)
        for value in ("D:/ci/build", "/d/ci/build", "D:/ci/msys64", "/d/ci/msys64"):
            self.assertIn("-ffile-prefix-map=" + value, command)
            self.assertIn("-fdebug-prefix-map=" + value, command)

    def test_local_command_has_no_ci_timeout(self):
        command = build.build_command(PureWindowsPath("D:/build"), PureWindowsPath("D:/msys64"), 2, False)
        self.assertNotIn("timeout ", command)
        self.assertIn("-j2", command)

    def test_helpers_packaged_without_private_outputs(self):
        self.assertIn("ci-toolchain-lock.json", package_source.HELPERS)
        self.assertIn("ci_prepare.py", package_source.HELPERS)
        self.assertIn("test_ci_build.py", package_source.HELPERS)
        for name in package_source.HELPERS:
            self.assertTrue(Path(__file__).with_name(name).is_file())
            self.assertFalse(name.endswith((".dll", ".o", ".log")))

    def test_default_disk_protection_unchanged(self):
        args = ["build_candidate.py", "--source-zip", "source.zip", "--msys-root", "msys64",
                "--build-root", str(Path(tempfile.gettempdir()) / "piq-nonexistent-build"), "--git", "git"]
        with patch("sys.argv", args), patch.object(build.shutil, "disk_usage") as usage:
            usage.return_value.free = 14 * 1024**3
            with self.assertRaisesRegex(ValueError, "80 GiB"):
                build.main()


class CorrespondingSourceTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="piq-source-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.source_zip = self.root / "upstream.zip"
        self.source = self.root / "mame"
        self.changed = {}
        with zipfile.ZipFile(self.source_zip, "x") as archive:
            for name in sorted(build.CHANGED) + ["COPYING"]:
                before = ("original " + name).encode()
                after = ("modified " + name).encode() if name in build.CHANGED else before
                archive.writestr(f"mame-{build.COMMIT}/" + name, before)
                path = self.source / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(after)
                if name in build.CHANGED:
                    self.changed[name] = {"before": hashlib.sha256(before).hexdigest().upper(),
                                          "after": hashlib.sha256(after).hexdigest().upper()}
        receipt = {"commit": build.COMMIT, "patch_sha256": build.PATCH_SHA256, "changed_files": self.changed}
        (self.root / "source-receipt.json").write_text(json.dumps(receipt), encoding="utf-8")

    def invoke(self, mode):
        args = ["package_source.py", "--source-zip", str(self.source_zip), "--build-root", str(self.root), *mode]
        # Fixture-only replacement of pinned archive identity/count: production
        # never accepts a caller-provided source pin or file-count override.
        with patch("sys.argv", args), patch.object(package_source, "SOURCE_SHA256", build.sha(self.source_zip)), \
                patch.object(package_source, "SOURCE_FILES", 5), patch("sys.stdout", new_callable=io.StringIO):
            package_source.main()

    def test_complete_source_and_helpers_packaged(self):
        output = self.root / "candidate-source.zip"
        self.invoke(["--output", str(output)])
        with zipfile.ZipFile(output) as archive:
            self.assertIsNone(archive.testzip())
            names = archive.namelist()
            self.assertEqual(len(names), 5 + len(package_source.HELPERS) + 1)
            self.assertIn("mame-0.289-piq-lifecycle1/piq-lifecycle/ci-toolchain-lock.json", names)
        self.assertFalse(output.with_suffix(".zip.partial").exists())
        with self.assertRaises(FileExistsError):
            self.invoke(["--output", str(output)])

    def test_verify_only_checks_unchanged_and_patched_sources(self):
        self.invoke(["--verify-only"])
        for name in ["COPYING", next(iter(build.CHANGED))]:
            path = self.source / name
            saved = path.read_bytes()
            path.write_bytes(b"tampered fixture")
            with self.assertRaisesRegex(ValueError, "Unexpected source modification"):
                self.invoke(["--verify-only"])
            path.write_bytes(saved)

    def test_failed_package_retains_partial_without_final_name(self):
        (self.source / "COPYING").write_bytes(b"tampered fixture")
        output = self.root / "candidate-source.zip"
        with self.assertRaises(ValueError):
            self.invoke(["--output", str(output)])
        self.assertFalse(output.exists())
        self.assertTrue(output.with_suffix(".zip.partial").exists())


if __name__ == "__main__":
    unittest.main()
