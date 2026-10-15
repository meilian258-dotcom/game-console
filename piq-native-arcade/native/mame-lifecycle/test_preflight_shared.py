"""Preflight contract tests only: fake subprocesses never compile or load code."""
from __future__ import annotations

import io
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import build_candidate as build
import preflight_shared as preflight


def generated_config(extra=""):
    return ("ifeq ($(config),libretro64)\n"
            "  OBJDIR = ../../../../libretro/obj/x64/libretro/fixture\n"
            "  ALL_CXXFLAGS += -m64 -fno-emulated-tls -stdlib=libc++ " + extra + "\n"
            "endif\n")


class GeneratedRuleTests(unittest.TestCase):
    def test_one_correct_release_configuration_accepted(self):
        text = generated_config()
        self.assertIn("-fno-emulated-tls", preflight.configuration(text))

    def test_missing_wrong_or_duplicate_configuration_rejected(self):
        for text in ("", generated_config().replace("libretro64", "libretro32"),
                     generated_config() + generated_config()):
            with self.subTest(text=text), self.assertRaises(ValueError):
                preflight.configuration(text)

    def test_native_tls_and_shared_cpp_flags_are_required(self):
        for flag in ("-fno-emulated-tls", "-stdlib=libc++", "-m64"):
            with self.subTest(flag=flag), self.assertRaisesRegex(ValueError, "Missing"):
                preflight.configuration(generated_config().replace(flag, ""))

    def test_each_conflicting_runtime_flag_is_rejected(self):
        for flag in ("-femulated-tls", "-static", "-static-libgcc", "libstdc++", "winpthread", "-m32"):
            with self.subTest(flag=flag), self.assertRaisesRegex(ValueError, "Unexpected"):
                preflight.configuration(generated_config(flag))

    def test_selected_real_translation_unit_target_accepted(self):
        unit = "src/osd/osdsync.cpp"
        text = generated_config() + "$(OBJDIR)/src/osd/osdsync.o: ../../../../../src/osd/osdsync.cpp \n"
        self.assertEqual(preflight.object_target(text, unit),
                         "../../../../libretro/obj/x64/libretro/fixture/src/osd/osdsync.o")

    def test_object_rule_requires_exact_source_and_known_output_tree(self):
        text = generated_config() + "$(OBJDIR)/src/osd/osdsync.o: ../../../../../src/osd/osdsync.cpp \n"
        for bad in (text.replace("osdsync.cpp ", "other.cpp "),
                    text.replace("../../../../libretro/obj/x64/libretro/", "/other/"),
                    text.replace("  OBJDIR =", "  DIFFERENT =")):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                preflight.object_target(bad, "src/osd/osdsync.cpp")


class InputGateTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="piq-preflight-input-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.msys = self.root / "msys64"
        self.tool_path = self.root / "toolchain.json"
        self.changes = {}
        for name in build.changed_files("clang64-shared"):
            path = self.root / "mame" / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"inert source fixture")
            self.changes[name] = {"before": "0" * 64, "after": build.sha(path)}
        self.source = {"commit": build.COMMIT, "profile": "clang64-shared",
                       "patch_sha256": build.PATCH_SHA256,
                       "patches": build.patch_identities("clang64-shared"),
                       "source_zip_sha256": build.SOURCE_SHA256,
                       "checked_source_files": build.SOURCE_FILES,
                       "source_root": str(self.root / "mame"), "changed_files": self.changes}
        lock = preflight.ci.load_lock(preflight.ci.lock_path("clang64-shared"))
        self.tool = {"schema": "piq-mame-ci-toolchain-receipt-1", "status": "ready",
                     "profile": "clang64-shared", "msys_root": str(self.msys),
                     "lock_sha256": build.sha(preflight.ci.lock_path("clang64-shared")),
                     "packages": lock["base_manifest"] | {p["name"]: p["version"] for p in lock["packages"]},
                     "tool_files": {"fixture": "1" * 64}}

    def invoke(self, source=None, tool=None):
        (self.root / "source-receipt.json").write_text(json.dumps(source or self.source), encoding="utf-8")
        self.tool_path.write_text(json.dumps(tool or self.tool), encoding="utf-8")
        with patch.object(preflight.ci, "tool_files", return_value=self.tool["tool_files"]):
            return preflight.validate_inputs(self.root, self.msys, self.tool_path)

    def test_exact_source_and_tool_profile_pass(self):
        self.assertEqual(self.invoke(), (self.source, self.tool))

    def test_wrong_archive_count_or_root_rejected(self):
        for field, value in (("source_zip_sha256", "0" * 64), ("checked_source_files", 1),
                             ("source_root", str(self.root / "foreign"))):
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "Source identity"):
                self.invoke(source=self.source | {field: value})

    def test_modified_source_rejected_before_tool_gate(self):
        (self.root / "mame/scripts/genie.lua").write_bytes(b"changed fixture")
        with self.assertRaisesRegex(ValueError, "source drift"):
            self.invoke()

    def test_tool_profile_lock_packages_files_and_root_fail_closed(self):
        for field, value in (("status", "failed"), ("profile", "gcc-static"),
                             ("lock_sha256", "0" * 64), ("packages", {}), ("tool_files", {}),
                             ("msys_root", str(self.root / "foreign"))):
            with self.subTest(field=field), self.assertRaisesRegex(ValueError, "toolchain identity"):
                self.invoke(tool=self.tool | {field: value})

    def test_ci_rejects_local_receipt_even_if_other_fields_match(self):
        with patch.dict(os.environ, {"GITHUB_ACTIONS": "true"}), self.assertRaisesRegex(ValueError, "local"):
            self.invoke(tool=self.tool | {"schema": "piq-mame-local-toolchain-receipt-1"})

    def test_unknown_tool_receipt_schema_rejected(self):
        with self.assertRaises(ValueError):
            self.invoke(tool=self.tool | {"schema": "untrusted"})


class BoundedProcessTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="piq-preflight-step-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def invoke(self, failure=None):
        def fake_run(command, **kw):
            kw["stdout"].write(b"inert compiler fixture\nfile.cpp: error: fixture rejection\n")
            if isinstance(failure, BaseException):
                raise failure
            return subprocess.CompletedProcess(command, failure or 0)

        with patch.dict(os.environ, {"SystemRoot": "C:/Windows", "BASH_ENV": "injected", "MAKEFLAGS": "injected"}), \
                patch.object(preflight.subprocess, "run", side_effect=fake_run) as run:
            result = preflight.run_step(self.root / "msys", self.root, self.root, "unit", "fixture-command", 30)
        return result, run

    def test_only_owned_bounded_shell_runs_and_receipts_written(self):
        result, run = self.invoke()
        self.assertEqual(result["exit_code"], 0)
        self.assertFalse(result["core_executed"])
        kwargs = run.call_args.kwargs
        self.assertEqual(kwargs["timeout"], 55)
        self.assertEqual(kwargs["cwd"], self.root)
        self.assertNotIn("BASH_ENV", kwargs["env"])
        self.assertNotIn("MAKEFLAGS", kwargs["env"])
        self.assertEqual(kwargs["env"]["MSYSTEM"], "CLANG64")
        self.assertEqual(run.call_args.args[0][1:4], ["--signal=TERM", "--kill-after=10s", "30s"])
        self.assertIn(b"fixture-command", kwargs["input"])
        self.assertTrue((self.root / "unit-started.json").is_file())
        self.assertTrue((self.root / "unit-finished.json").is_file())

    def test_nonzero_retains_log_and_failed_receipt(self):
        with self.assertRaisesRegex(ValueError, "preflight failed at unit"):
            self.invoke(2)
        record = json.loads((self.root / "unit-finished.json").read_text(encoding="utf-8"))
        self.assertEqual(record["exit_code"], 2)
        self.assertIn(b"fixture rejection", (self.root / "unit.log").read_bytes())

    def test_timeout_retains_log_and_finished_evidence_without_success_claim(self):
        with self.assertRaises(subprocess.TimeoutExpired):
            self.invoke(subprocess.TimeoutExpired("fixture", 55))
        record = json.loads((self.root / "unit-finished.json").read_text(encoding="utf-8"))
        self.assertNotEqual(record.get("exit_code"), 0)
        self.assertFalse(record["core_executed"])
        self.assertIn(b"fixture rejection", (self.root / "unit.log").read_bytes())

    def test_existing_step_is_not_overwritten_or_repeated(self):
        self.invoke()
        before = (self.root / "unit.log").read_bytes()
        with patch.object(preflight.subprocess, "run") as run, self.assertRaises(FileExistsError):
            preflight.run_step(self.root / "msys", self.root, self.root, "unit", "fixture", 30)
        run.assert_not_called()
        self.assertEqual((self.root / "unit.log").read_bytes(), before)


class ObjectHandoffTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix="piq-preflight-handoff-test-")
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name).resolve()
        self.output = self.root / "shared-preflight"
        self.output.mkdir()
        self.owned = self.root / "mame/build/libretro/obj"
        self.objects = [self.owned / "x64/libretro/ocore/src/osd/osdsync.o",
                        self.owned / "x64/libretro/osd/src/osd/libretro/retromain.o"]

    def write_pairs(self):
        for obj in self.objects:
            obj.parent.mkdir(parents=True, exist_ok=True)
            obj.write_bytes(b"inert object " + obj.name.encode())
            obj.with_suffix(".d").write_bytes(b"inert dependency " + obj.name.encode())

    def test_fresh_targets_accept_only_absent_object_and_dependency_files(self):
        preflight.require_fresh_objects(self.root, self.objects)
        self.write_pairs()
        with self.assertRaisesRegex(ValueError, "existing"):
            preflight.require_fresh_objects(self.root, self.objects)

    def test_stale_dependency_alone_rejects_reuse(self):
        dep = self.objects[0].with_suffix(".d")
        dep.parent.mkdir(parents=True)
        dep.write_bytes(b"stale fixture")
        with self.assertRaisesRegex(ValueError, "existing"):
            preflight.require_fresh_objects(self.root, self.objects)

    def test_moves_both_pairs_without_losing_data_or_leaving_reusable_objects(self):
        self.write_pairs()
        before = {path: path.read_bytes() for obj in self.objects for path in (obj, obj.with_suffix(".d"))}
        retained = preflight.retain_probe_objects(self.root, self.output, self.objects)
        self.assertEqual(len(retained), 4)
        for original, data in before.items():
            self.assertFalse(original.exists())
            target = self.output / "objects" / original.relative_to(self.owned)
            self.assertEqual(target.read_bytes(), data)
            self.assertEqual(retained[target.relative_to(self.root).as_posix()], build.sha(target))
        preflight.require_fresh_objects(self.root, self.objects)

    def test_outside_or_duplicate_object_set_rejected_before_move(self):
        self.write_pairs()
        for objects in ([self.objects[0], self.objects[0]],
                        [self.objects[0], self.root / "foreign.o"], [self.objects[0]]):
            with self.subTest(objects=objects), self.assertRaises(ValueError):
                preflight.retain_probe_objects(self.root, self.output, objects)
        self.assertTrue(all(path.exists() for path in self.objects))
        self.assertFalse((self.output / "objects").exists())

    def test_missing_dependency_rejects_before_any_move(self):
        self.write_pairs()
        self.objects[1].with_suffix(".d").unlink()
        with self.assertRaisesRegex(ValueError, "nonempty"):
            preflight.retain_probe_objects(self.root, self.output, self.objects)
        self.assertTrue(all(path.exists() for path in self.objects))
        self.assertFalse((self.output / "objects").exists())

    def test_empty_object_rejects_before_any_move(self):
        self.write_pairs()
        self.objects[1].write_bytes(b"")
        with self.assertRaisesRegex(ValueError, "nonempty"):
            preflight.retain_probe_objects(self.root, self.output, self.objects)
        self.assertTrue(all(path.exists() for path in self.objects))

    def test_existing_evidence_directory_preserved(self):
        self.write_pairs()
        destination = self.output / "objects"
        destination.mkdir()
        earlier = destination / "earlier.txt"
        earlier.write_bytes(b"old evidence fixture")
        with self.assertRaisesRegex(ValueError, "new and owned"):
            preflight.retain_probe_objects(self.root, self.output, self.objects)
        self.assertEqual(earlier.read_bytes(), b"old evidence fixture")
        self.assertTrue(all(path.exists() for path in self.objects))

    def test_destination_must_stay_in_preflight_evidence_tree(self):
        self.write_pairs()
        foreign = self.root / "foreign-evidence"
        foreign.mkdir()
        with self.assertRaisesRegex(ValueError, "new and owned"):
            preflight.retain_probe_objects(self.root, foreign, self.objects)
        self.assertTrue(all(path.exists() for path in self.objects))


if __name__ == "__main__":
    unittest.main()
