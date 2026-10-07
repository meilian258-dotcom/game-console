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
from unittest.mock import MagicMock, patch

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
        with self.assertRaisesRegex(ValueError, "size mismatch") as error:
            self.fetch(size=len(self.data) + 1)
        self.assertIn(f"actual_bytes={len(self.data)}", str(error.exception))
        self.assertIn(f"actual_sha256={self.digest}", str(error.exception))
        self.assertEqual(self.path.read_bytes(), self.data)

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


class ExtractionTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="piq-extract-test-")
        self.addCleanup(self.tmp.cleanup)
        fixture = Path(self.tmp.name).resolve()
        self.root = fixture / "prepare"
        (self.root / "inputs").mkdir(parents=True)
        self.archive = self.root / "inputs/base.sfx.exe"
        self.archive.write_bytes(b"authenticated archive fixture; never extracted")
        self.base = {"filename": self.archive.name, "bytes": self.archive.stat().st_size,
                     "sha256": build.sha(self.archive)}

    def layout(self):
        for name in ("usr/bin/bash.exe", "usr/bin/pacman.exe", "usr/bin/msys-2.0.dll",
                     "etc/post-install/07-pacman-key.post"):
            path = self.root / "msys64" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"non-executable layout fixture")

    def invoke(self, outcome=None, *, layout=False):
        def run(command, **kwargs):
            kwargs["stdout"].write(b"x msys64/usr/bin/bash.exe\n")
            if layout:
                self.layout()
            if outcome is not None:
                raise outcome
            return subprocess.CompletedProcess(command, 0)
        with patch.object(ci.subprocess, "run", side_effect=run) as mocked, \
                patch("sys.stdout", new_callable=io.StringIO):
            result = ci.extract_base(self.root, self.base)
        return result, mocked

    def finished(self):
        return json.loads((self.root / "toolchain-extract-finished.json").read_text(encoding="utf-8"))

    def test_bounded_sfx_extraction_records_hashes_and_layout(self):
        result, run = self.invoke(layout=True)
        self.assertEqual(result, self.finished())
        self.assertEqual(result["status"], "complete")
        self.assertEqual(result["archive_sha256"], self.base["sha256"])
        self.assertEqual(result["sha256"], build.sha(self.archive))
        self.assertEqual(result["kind"], "official MSYS2 base SFX")
        self.assertEqual(result["log_sha256"], build.sha(self.root / "msys-extract.log"))
        self.assertTrue(result["child_reaped"])
        self.assertEqual(run.call_count, 1)
        self.assertEqual(run.call_args.args[0], [str(self.archive), "-y"])
        self.assertTrue(self.archive.is_absolute())
        self.assertEqual(run.call_args.kwargs["cwd"], self.root)
        self.assertEqual(run.call_args.kwargs["timeout"], 1200)
        self.assertEqual(run.call_args.kwargs["stdin"], subprocess.DEVNULL)
        self.assertEqual(run.call_args.kwargs["stderr"], subprocess.STDOUT)
        self.assertTrue(run.call_args.kwargs["check"])
        started = json.loads((self.root / "toolchain-extract-started.json").read_text(encoding="utf-8"))
        self.assertEqual(started["status"], "extracting")

    def test_timeout_preserves_log_partial_tree_and_finished_receipt(self):
        with self.assertRaises(subprocess.TimeoutExpired):
            self.invoke(subprocess.TimeoutExpired("fixture", 1200), layout=True)
        result = self.finished()
        self.assertEqual(result["status"], "timed-out")
        self.assertTrue(result["timed_out"])
        self.assertTrue(result["child_reaped"])
        self.assertGreater(result["log_bytes"], 0)
        before = (self.root / "msys-extract.log").read_bytes()
        with patch.object(ci.subprocess, "run") as run, self.assertRaises(FileExistsError):
            ci.extract_base(self.root, self.base)
        run.assert_not_called()
        self.assertEqual((self.root / "msys-extract.log").read_bytes(), before)
        self.assertTrue((self.root / "msys64/usr/bin/bash.exe").is_file())

    def test_nonzero_extractor_exit_is_not_success(self):
        with self.assertRaises(subprocess.CalledProcessError):
            self.invoke(subprocess.CalledProcessError(2, "fixture"))
        self.assertEqual(self.finished()["exit_code"], 2)
        self.assertEqual(self.finished()["status"], "failed")

    def test_zero_exit_with_wrong_archive_layout_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "msys64 layout"):
            self.invoke()
        self.assertEqual(self.finished()["status"], "failed")
        self.assertTrue(self.finished()["child_reaped"])

    def test_changed_base_is_rejected_before_extractor_or_receipt(self):
        self.archive.write_bytes(b"changed fixture")
        with patch.object(ci.subprocess, "run") as run, self.assertRaisesRegex(ValueError, "changed"):
            ci.extract_base(self.root, self.base)
        run.assert_not_called()
        self.assertFalse((self.root / "toolchain-extract-started.json").exists())

    def test_interruption_retains_receipt_without_claiming_confirmed_reap(self):
        with self.assertRaises(KeyboardInterrupt):
            self.invoke(KeyboardInterrupt())
        self.assertEqual(self.finished()["status"], "interrupted")
        self.assertFalse(self.finished()["child_reaped"])

    @unittest.skipUnless(os.name == "nt", "Windows subprocess.run timeout cleanup path")
    def test_stdlib_timeout_kills_only_owned_popen_and_finishes_communication(self):
        # Exercise the real subprocess.run wrapper with a fake Popen, not SFX.
        process = MagicMock()
        process.__enter__.return_value = process
        events = []
        process.kill.side_effect = lambda: events.append("kill")
        process.wait.side_effect = lambda: events.append("wait")

        def communicate(*args, **kwargs):
            if kwargs.get("timeout") is not None:
                raise subprocess.TimeoutExpired("fixture", 1200)
            process.wait()  # Popen.communicate's documented post-kill behavior.
            return None, None

        real_save = ci.save

        def save(path, value):
            if path.name == "toolchain-extract-finished.json":
                events.append("finished")
            real_save(path, value)

        process.communicate.side_effect = communicate
        process.__exit__.side_effect = lambda *args: process.wait()
        with patch.object(ci.subprocess, "Popen", return_value=process) as popen, \
                patch.object(ci, "save", side_effect=save), \
                patch("sys.stdout", new_callable=io.StringIO), self.assertRaises(subprocess.TimeoutExpired):
            ci.extract_base(self.root, self.base)
        self.assertEqual(popen.call_count, 1)
        process.kill.assert_called_once_with()
        self.assertEqual(process.communicate.call_count, 2)
        self.assertEqual(process.communicate.call_args_list[-1].args, ())
        self.assertEqual(process.communicate.call_args_list[-1].kwargs, {})
        process.__exit__.assert_called_once()
        self.assertLess(events.index("kill"), events.index("wait"))
        self.assertLess(events.index("wait"), events.index("finished"))
        self.assertEqual(self.finished()["status"], "timed-out")

    def test_main_extraction_failure_never_initializes_msys(self):
        target = self.root / "fresh"

        def download(url, path, *args, **kwargs):
            path.write_bytes(b"")
            return {"fixture": path.name}

        base = self.base | {"url": "https://repo.msys2.org/fixture",
                           "manifest_url": "https://repo.msys2.org/manifest", "manifest_sha256": "0" * 64}
        with patch("sys.argv", ["ci_prepare.py", "--root", str(target)]), \
                patch.object(ci, "require_hosted_ci"), \
                patch.object(ci.shutil, "disk_usage", return_value=SimpleNamespace(free=20 * ci.GIB)), \
                patch.object(ci, "load_lock", return_value={"base": base, "base_manifest": {}}), \
                patch.object(ci, "download", side_effect=download) as fetch, \
                patch.object(ci, "extract_base", side_effect=subprocess.TimeoutExpired("fixture", 1200)), \
                patch.object(ci, "disable_key_refresh") as refresh, patch.object(ci, "run_shell") as shell, \
                self.assertRaises(subprocess.TimeoutExpired):
            ci.main()
        self.assertEqual(fetch.call_count, 2)
        refresh.assert_not_called()
        shell.assert_not_called()
        self.assertFalse((target / "toolchain-receipt.json").exists())
        failed = json.loads((target / "toolchain-failed.json").read_text(encoding="utf-8"))
        self.assertEqual(failed["status"], "failed")


class BuildDependencyTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="piq-dependencies-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.msys = self.root / "msys64"
        self.lock = ci.load_lock()
        for name in ci.BUILD_DEPENDENCY_FILES:
            path = self.msys / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"inert installed dependency fixture")
        self.versions = (self.lock["gcc_version"] + "\nPython " + self.lock["python_version"]
                         + "\nGNU Make " + self.lock["make_version"] + "\n")

    def invoke(self, *, exit_code=0, stdout=None, outcome=None):
        completed = subprocess.CompletedProcess([], exit_code, self.versions if stdout is None else stdout)
        with patch.dict(os.environ, {"SystemRoot": "C:/Windows"}), \
                patch.object(ci.subprocess, "run", return_value=completed, side_effect=outcome) as run:
            result = ci.check_build_dependencies(self.msys, self.root, self.lock)
        return result, run

    def test_preflight_is_bounded_header_syntax_only_and_uses_existing_log(self):
        result, run = self.invoke()
        self.assertEqual(result["status"], "passed")
        self.assertEqual(result["timeout_seconds"], 60)
        self.assertEqual(result["log"], "tool-versions.txt")
        self.assertEqual(result["log_sha256"], build.sha(self.root / "tool-versions.txt"))
        self.assertEqual(set(result["files"]), set(ci.BUILD_DEPENDENCY_FILES))
        self.assertEqual(run.call_count, 1)
        self.assertEqual(run.call_args.kwargs["timeout"], 60)
        command = run.call_args.args[0][-1]
        self.assertIn("timeout --signal=TERM --kill-after=5s 50s g++", command)
        self.assertIn("-std=gnu++17 -fsyntax-only -x c++ -", command)
        self.assertIn("#include <SDL2/SDL.h>", command)
        self.assertIn("SDL_MAJOR_VERSION != 2 || SDL_MINOR_VERSION != 32 || SDL_PATCHLEVEL != 10", command)
        for forbidden in ("make -f", "-o ", "-lSDL", "./probe", "NO_USE_", "taskkill", "pkill"):
            self.assertNotIn(forbidden, command)

    def test_each_missing_or_empty_installed_dependency_fails_before_process(self):
        for name in ci.BUILD_DEPENDENCY_FILES:
            path = self.msys / name
            for missing in (True, False):
                with self.subTest(file=name, missing=missing):
                    if missing:
                        path.unlink()
                    else:
                        path.write_bytes(b"")
                    with patch.object(ci.subprocess, "run") as run, \
                            self.assertRaisesRegex(ValueError, "Missing installed build dependency"):
                        ci.check_build_dependencies(self.msys, self.root, self.lock)
                    run.assert_not_called()
                    path.write_bytes(b"restored inert fixture")
        self.assertFalse((self.root / "tool-versions.txt").exists())

    def test_compiler_failure_is_not_a_pass_and_retains_diagnostic(self):
        with self.assertRaisesRegex(RuntimeError, "preparation failed"):
            self.invoke(exit_code=1, stdout="fixture: SDL2/SDL.h missing\n")
        self.assertIn("SDL2/SDL.h missing", (self.root / "tool-versions.txt").read_text())

    def test_preflight_timeout_preserves_partial_log(self):
        with self.assertRaises(subprocess.TimeoutExpired):
            self.invoke(outcome=subprocess.TimeoutExpired("fixture", 60, output=b"checking headers\n"))
        log = (self.root / "tool-versions.txt").read_text()
        self.assertIn("checking headers", log)
        self.assertIn("not reusable", log)

    def test_zero_exit_does_not_hide_wrong_compiler_version(self):
        with self.assertRaisesRegex(ValueError, "tool versions"):
            self.invoke(stdout="incorrect compiler\n")

    def test_existing_tool_hash_gate_covers_new_dependency_drift(self):
        names = ("usr/bin/bash.exe", "usr/bin/make.exe", "usr/bin/msys-2.0.dll",
                 "mingw64/bin/gcc.exe", "mingw64/bin/g++.exe", "mingw64/bin/ar.exe",
                 "mingw64/bin/ld.exe", "mingw64/bin/python3.exe",
                 "mingw64/lib/gcc/x86_64-w64-mingw32/16.2.0/cc1plus.exe",
                 "mingw64/include/c++/16.2.0/bits/version.h")
        for name in names:
            path = self.msys / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"inert compiler fixture")
        before = ci.tool_files(self.msys)
        self.assertEqual(len(before), 18)
        self.assertTrue(set(ci.BUILD_DEPENDENCY_FILES).issubset(before))
        changed = "mingw64/include/SDL2/SDL.h"
        (self.msys / changed).write_bytes(b"drifted header fixture")
        after = ci.tool_files(self.msys)
        self.assertEqual({name for name in before if before[name] != after[name]}, {changed})

    def test_failed_preflight_never_downloads_mame_or_marks_toolchain_ready(self):
        target = self.root / "fresh"
        expected = self.lock["base_manifest"] | {p["name"]: p["version"] for p in self.lock["packages"]}

        def manifest(packages):
            return "\n".join(name + " " + version for name, version in packages.items()) + "\n"

        def download(url, path, *args, **kwargs):
            path.write_text(manifest(self.lock["base_manifest"]) if path.name == "base-packages.txt" else "fixture",
                            encoding="utf-8")
            return {"fixture": path.name}

        def shell(msys, command, log, **kwargs):
            packages = expected if log.name == "packages-after.txt" else self.lock["base_manifest"]
            output = manifest(packages) if command == "pacman -Q" else "fixture output\n"
            log.write_text(output, encoding="utf-8")
            return output

        with patch("sys.argv", ["ci_prepare.py", "--root", str(target)]), \
                patch.object(ci, "require_hosted_ci"), \
                patch.object(ci.shutil, "disk_usage", return_value=SimpleNamespace(free=20 * ci.GIB)), \
                patch.object(ci, "download", side_effect=download) as fetch, \
                patch.object(ci, "extract_base", return_value={"status": "complete"}), \
                patch.object(ci, "disable_key_refresh", return_value={}), \
                patch.object(ci, "enforce_offline_keyring", return_value={}), \
                patch.object(ci, "run_shell", side_effect=shell) as commands, \
                patch.object(ci, "check_build_dependencies", side_effect=RuntimeError("fixture preflight failure")), \
                self.assertRaisesRegex(RuntimeError, "preflight failure"):
            ci.main()
        self.assertEqual(fetch.call_count, 2 + 2 * len(self.lock["packages"]))
        self.assertNotIn(ci.SOURCE_URL, [call.args[0] for call in fetch.call_args_list])
        signatures = next(call.args[1] for call in commands.call_args_list
                          if call.args[2].name == "packages-signatures.log")
        self.assertEqual(signatures.count("pacman-key --verify "), 40)
        for package in ("mingw-w64-x86_64-SDL2", "mingw-w64-x86_64-vulkan-loader"):
            self.assertIn(package, signatures)
        config = (target / "pacman-ci.conf").read_text()
        self.assertIn("LocalFileSigLevel = Required", config)
        self.assertIn("RemoteFileSigLevel = Required", config)
        self.assertFalse((target / "toolchain-receipt.json").exists())
        self.assertEqual(json.loads((target / "toolchain-failed.json").read_text())["status"], "failed")


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
        self.assertEqual(lock["base"]["filename"], "msys2-base-x86_64-20260927.sfx.exe")
        self.assertEqual(lock["base"]["bytes"], 43117824)
        self.assertEqual(lock["base"]["sha256"],
                         "AD336CCCFDA47758B5E15CDA993FBBA421115CB0B126697DAEF1EE4DFE37209F")
        self.assertEqual(len(lock["packages"]), 40)
        self.assertEqual(len(lock["base_manifest"]), 85)
        self.assertEqual(sum(p["bytes"] for p in lock["packages"]), 122177555)
        self.assertEqual(lock["gcc_version"], "16.2.0")
        self.assertEqual(lock["python_version"], "3.14.8")
        self.assertEqual(build.sha(build.PATCH), build.PATCH_SHA256)
        self.assertEqual(ci.SOURCE_URL, f"https://github.com/libretro/mame/archive/{build.COMMIT}.zip")

    def test_sdl2_pins_add_only_runtime_dependency_closure_not_package_build_tools(self):
        packages = {p["name"]: p for p in ci.load_lock()["packages"]}
        expected = {
            "mingw-w64-x86_64-SDL2": ("2.32.10-1", 1549601,
                "5991AFBCFEB2F8B838AB80B2270D713A727199CA392715677A7C1931A0D9ECEF"),
            "mingw-w64-x86_64-vulkan-loader": ("1~1.4.363.0-1", 223456,
                "281FA31B1A023485D4FD04A092A8B5E9CA2F59804DF7FCF9F2D4E5AAA5438AF0"),
        }
        for name, (version, size, digest) in expected.items():
            item = packages[name]
            self.assertEqual((item["version"], item["bytes"], item["sha256"]), (version, size, digest))
            self.assertEqual(item["url"], "https://repo.msys2.org/mingw/mingw64/" + item["filename"])
        self.assertTrue(ci.REQUIRED_BUILD_PACKAGES.issubset(packages))
        for package in ("vulkan-headers", "cmake", "ninja", "pkgconf", "sdl2-compat"):
            self.assertNotIn("mingw-w64-x86_64-" + package, packages)

    def test_same_size_lock_cannot_substitute_required_sdl2_dependency(self):
        for name in ci.REQUIRED_BUILD_PACKAGES:
            lock = ci.load_lock()
            next(p for p in lock["packages"] if p["name"] == name)["name"] = "unexpected-package"
            target = self.root / "substituted-lock.json"
            target.write_text(json.dumps(lock), encoding="utf-8")
            with self.subTest(package=name), self.assertRaisesRegex(ValueError, "dependency closure"):
                ci.load_lock(target)

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
        # Exact five-line initialization output from the pinned pacman package;
        # the digest also matches the failed hosted run's before_sha256 receipt.
        original = (b"no-greeting\nno-permission-warning\nkeyserver-options timeout=10\n"
                    b"keyserver-options import-clean\nkeyserver-options no-self-sigs-only\n")
        config.write_bytes(original)
        receipt = ci.enforce_offline_keyring(self.root)
        actual = config.read_bytes()
        self.assertTrue(actual.startswith(original))
        options = ["lock-never", "no-auto-key-retrieve", "no-auto-key-import",
                   "auto-key-locate clear", "disable-dirmngr"]
        for option in options:
            self.assertEqual(actual.splitlines().count(option.encode("ascii")), 1)
        self.assertEqual(receipt["before_sha256"],
                         "588CE84F80E9B421FD01C4EA58D40ADF5EDAFED0A26958A03B6115E3AFCF1E4A")
        self.assertEqual(receipt["after_sha256"], build.sha(config))
        self.assertEqual(receipt["added_options"], options)
        repeated = ci.enforce_offline_keyring(self.root)
        self.assertEqual(config.read_bytes(), actual)
        self.assertEqual(repeated["before_sha256"], receipt["after_sha256"])
        self.assertEqual(repeated["after_sha256"], receipt["after_sha256"])
        self.assertEqual(repeated["added_options"], [])

    def test_offline_keyring_preserves_existing_options_and_line_endings(self):
        config = self.root / "etc/pacman.d/gnupg/gpg.conf"
        config.parent.mkdir(parents=True)
        original = b"no-greeting\r\n lock-never \r\nno-auto-key-import"
        config.write_bytes(original)
        receipt = ci.enforce_offline_keyring(self.root)
        actual = config.read_bytes()
        self.assertTrue(actual.startswith(original + b"\n"))
        self.assertNotIn("lock-never", receipt["added_options"])
        self.assertNotIn("no-auto-key-import", receipt["added_options"])
        self.assertEqual([line.strip() for line in actual.splitlines()].count(b"lock-never"), 1)

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
                  "preparer_sha256": build.sha(Path(ci.__file__)), "offline_signature_checks": len(lock["packages"]),
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
        self.assertEqual(run.call_args.args[0],
                         [str(self.root / "usr/bin/bash.exe"), "--noprofile", "--norc", "-c", "set -eu\nfixture"])
        self.assertEqual(run.call_args.kwargs["cwd"], self.root)
        self.assertEqual(env["PATH"], str(self.root / "usr/bin") + os.pathsep + str(Path("C:/Windows/System32")))
        self.assertEqual(env["MSYSTEM"], "MSYS")


class BuildCommandTests(unittest.TestCase):
    def test_full_driver_flags_and_privacy_maps(self):
        command = build.build_command(PureWindowsPath("D:/ci/build"), PureWindowsPath("D:/ci/msys64"), 4, True)
        self.assertIn("-j4", command)
        self.assertIn("TARGET=mame SUBTARGET=mame", command)
        self.assertIn("timeout --signal=INT --kill-after=60s 18000s", command)
        self.assertIn("unset ANDROID_NDK_HOME ANDROID_NDK_ROOT\n", command)
        self.assertLess(command.index("unset ANDROID_NDK_HOME"), command.index("make -f Makefile.libretro"))
        for forbidden in ("SOURCES=", "PIQ_SNAPSHOT", "SOUND_DISABLE_THREADING", "ccache"):
            self.assertNotIn(forbidden, command)
        for value in ("D:/ci/build", "/d/ci/build", "D:/ci/msys64", "/d/ci/msys64"):
            self.assertIn("-ffile-prefix-map=" + value, command)
            self.assertIn("-fdebug-prefix-map=" + value, command)

    def test_local_command_has_no_ci_timeout(self):
        command = build.build_command(PureWindowsPath("D:/build"), PureWindowsPath("D:/msys64"), 2, False)
        self.assertNotIn("timeout ", command)
        self.assertNotIn("unset ANDROID_NDK", command)
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
