"""CLANG64 preparation tests use inert files and mocked subprocesses only."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import ci_prepare as ci


class ClangPreparationTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory(prefix="piq-clang-test-")
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name).resolve()
        self.msys = self.root / "msys64"
        self.lock = ci.load_lock(ci.lock_path("clang64-shared"))

    def test_exact_independent_closure(self):
        self.assertEqual(self.lock["environment"], "CLANG64")
        self.assertEqual(len(self.lock["packages"]), 38)
        self.assertEqual(sum(p["bytes"] for p in self.lock["packages"]), 181363533)
        self.assertEqual(self.lock["clang_version"], "22.1.8")
        self.assertEqual(self.lock["python_version"], "3.14.8")
        self.assertEqual(ci.load_lock()["environment"], "MINGW64")
        with self.assertRaisesRegex(ValueError, "Unknown"):
            ci.lock_path("anything")

    def test_mixed_environment_and_missing_required_package_rejected(self):
        for old, replacement in (("mingw-w64-clang-x86_64-clang", "missing"),
                                 ("mingw-w64-clang-x86_64-bzip2", "mingw-w64-x86_64-bzip2")):
            value = json.loads(json.dumps(self.lock))
            next(p for p in value["packages"] if p["name"] == old)["name"] = replacement
            target = self.root / "lock.json"
            target.write_text(json.dumps(value), encoding="utf-8")
            with self.assertRaises(ValueError):
                ci.load_lock(target)

    def test_actual_clang_target_and_stdin_script_are_selected(self):
        for name in ci.CLANG_BUILD_FILES:
            path = self.msys / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"inert")
        versions = "22.1.8\nPython 3.14.8\nGNU Make 4.4.1\n"
        with patch.dict(os.environ, {"SystemRoot": "C:/Windows"}), patch.object(ci.subprocess,
                "run", return_value=subprocess.CompletedProcess([], 0, versions)) as run:
            result = ci.check_build_dependencies(self.msys, self.root, self.lock)
        self.assertEqual(result["status"], "passed")
        self.assertEqual(set(result["files"]), set(ci.CLANG_BUILD_FILES))
        self.assertEqual(run.call_args.args[0][-1], "-s")
        command = run.call_args.kwargs["input"]
        self.assertIn('clang -dumpmachine)" = x86_64-w64-windows-gnu', command)
        self.assertIn("50s clang++ -std=gnu++17 -fsyntax-only", command)
        self.assertIn("llvm-windres --version", command)
        self.assertNotIn("/mingw64", command)
        self.assertNotIn("./probe", command)

    def test_long_signature_script_is_not_in_windows_argv(self):
        script = "# bounded inert fixture\n" * 5000
        with patch.dict(os.environ, {"SystemRoot": "C:/Windows"}), patch.object(ci.subprocess,
                "run", return_value=subprocess.CompletedProcess([], 0, "ok\n")) as run:
            ci.run_shell(self.msys, script, self.root / "signatures.log")
        self.assertEqual(run.call_args.kwargs["input"], "set -eu\n" + script)
        self.assertLess(sum(map(len, run.call_args.args[0])), 1000)
        self.assertEqual(run.call_args.kwargs["timeout"], 600)

    def test_dynamic_compiler_backend_linker_and_timeout_drift_are_fingerprinted(self):
        # Discover the production allowlist, then materialise inert fixtures for
        # every entry. No real DLL/EXE is copied or executed by this test.
        names = []

        def collect(path):
            names.append(path)
            return "0" * 64

        with patch.object(ci, "sha", side_effect=collect):
            ci.tool_files(self.msys, "clang64-shared")
        for path in names:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"fixed inert input")
        before = ci.tool_files(self.msys, "clang64-shared")
        for relative in ("clang64/bin/libclang-cpp.dll", "clang64/bin/libLLVM-22.dll",
                         "clang64/bin/lld.exe", "usr/bin/timeout.exe"):
            with self.subTest(relative=relative):
                path = self.msys / relative
                self.assertIn(relative, before)
                path.write_bytes(b"changed inert input")
                after = ci.tool_files(self.msys, "clang64-shared")
                self.assertEqual({name for name in before if before[name] != after[name]}, {relative})
                path.write_bytes(b"fixed inert input")
        self.assertEqual(ci.tool_files(self.msys, "clang64-shared"), before)

    def test_missing_compiler_backend_is_not_a_matching_toolchain(self):
        names = []
        with patch.object(ci, "sha", side_effect=lambda path: names.append(path) or "0" * 64):
            ci.tool_files(self.msys, "clang64-shared")
        for path in names:
            path.parent.mkdir(parents=True, exist_ok=True)
            if path.name != "libclang-cpp.dll":
                path.write_bytes(b"fixed inert input")
        with self.assertRaises(FileNotFoundError):
            ci.tool_files(self.msys, "clang64-shared")


if __name__ == "__main__":
    unittest.main()
