"""Fake receipt/PE tests; never load a native core or invoke a real build."""
import copy
import io
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest import mock

import ci_artifacts as target
import ci_prepare
from test_verify_candidate import imported_pe


class CandidateArtifactsTest(unittest.TestCase):
    def setUp(self):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        self.root = Path(temp.name)
        self.build = self.root / "build"
        self.source = self.build / "mame"
        self.source.mkdir(parents=True)
        self.output = self.root / "candidate"
        self.core = self.source / "mame_libretro.dll"
        data = bytearray(4096)
        data[:2] = b"MZ"
        struct.pack_into("<I", data, 0x3C, 0x80)
        data[0x80:0x84] = b"PE\0\0"
        struct.pack_into("<H", data, 0x84, 0x8664)
        struct.pack_into("<H", data, 0x94, 240)
        struct.pack_into("<H", data, 0x96, 0x2000)
        struct.pack_into("<H", data, 0x98, 0x20B)
        struct.pack_into("<I", data, 0x98 + 56, 8192)
        self.core.write_bytes(data)
        self.archive = self.build / "candidate-source.zip"
        self.archive.write_bytes(b"fake matching corresponding-source fixture")
        self.source_receipt = self.build / "source-receipt.json"
        self.finished = self.build / "build-20261008T010203Z-finished.json"
        changes = {}
        for name in target.build.CHANGED:
            path = self.source / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b"fake patched source")
            changes[name] = {"before": "1" * 64, "after": target.build.sha(path)}
        self.provenance = {"schema": "piq-mame-lifecycle-source-1", "commit": target.build.COMMIT,
            "source_zip_sha256": target.build.SOURCE_SHA256, "patch_sha256": target.build.PATCH_SHA256,
            "source_root": str(self.source), "checked_source_files": target.build.SOURCE_FILES,
            "changed_files": changes, "prepared_utc": "20261008T010100Z"}
        self.write_json(self.source_receipt, self.provenance)
        self.receipt = {"schema": "piq-mame-lifecycle-build-1", "full_driver_build": True,
            "ci_clean": True, "toolchain_unchanged": True, "source_recheck_exit_code": 0,
            "exit_code": 0, "source_receipt_sha256": target.build.sha(self.source_receipt),
            "launcher_sha256": target.build.sha(Path(target.build.__file__)),
            "artifacts": [{"path": str(self.core), "bytes": self.core.stat().st_size,
                           "sha256": target.build.sha(self.core)}],
            "corresponding_source": {"file": "candidate-source.zip", "sha256": target.build.sha(self.archive)},
            "log": str(self.root / "private.log"), "command": "private build command",
            "launcher_pid": 12345, "portable_msys": str(self.root / "private-toolchain")}
        self.write_json(self.finished, self.receipt)
        environment = mock.patch.dict(target.os.environ, {"GITHUB_SHA": "a" * 40})
        environment.start()
        self.addCleanup(environment.stop)
        for name in ("CDLL", "WinDLL"):
            patcher = mock.patch.object(target.probe.c, name,
                side_effect=AssertionError("real native loading is prohibited in unit tests"), create=True)
            patcher.start()
            self.addCleanup(patcher.stop)

    @staticmethod
    def write_json(path, value):
        path.write_text(json.dumps(value), encoding="utf-8")

    def assert_stage_rejected(self):
        with self.assertRaises((target.ArtifactError, target.probe.ProbeError)):
            target.stage(self.build, self.output)
        self.assertFalse(self.output.exists())

    def save_source_change(self):
        self.write_json(self.source_receipt, self.provenance)
        self.receipt["source_receipt_sha256"] = target.build.sha(self.source_receipt)
        self.write_json(self.finished, self.receipt)

    def test_stages_only_three_files_and_verifies_without_loading(self):
        manifest = target.stage(self.build, self.output)
        self.assertEqual(target.FILES, {path.name for path in self.output.iterdir()})
        self.assertEqual(target.build.sha(self.core), target.verify(self.output))
        self.assertEqual(self.core.read_bytes(), (self.output / "core.dll").read_bytes())
        self.assertEqual(self.archive.read_bytes(), (self.output / "candidate-source.zip").read_bytes())
        self.assertEqual("a" * 40, manifest["run_commit"])
        self.assertEqual(target.SAVE_POLICY, manifest["save_policy"])
        self.assertEqual("unverified-candidate", manifest["status"])
        self.assertEqual({"not-run"}, set(manifest["validation"].values()))
        serialized = (self.output / "candidate.json").read_text(encoding="utf-8")
        for secret in (str(self.root), "private", "command", "launcher_pid", "portable_msys"):
            self.assertNotIn(secret, serialized)

    def test_cli_verify_stdout_is_only_sha_and_no_paths(self):
        target.stage(self.build, self.output)
        out, err = io.StringIO(), io.StringIO()
        with mock.patch("sys.stdout", out), mock.patch("sys.stderr", err):
            self.assertEqual(0, target.main(["verify", "--directory", str(self.output)]))
        self.assertEqual(target.build.sha(self.core) + "\n", out.getvalue())
        self.assertEqual("", err.getvalue())

    def test_cli_failure_has_no_stdout_and_only_fixed_error_code(self):
        out, err = io.StringIO(), io.StringIO()
        with mock.patch("sys.stdout", out), mock.patch("sys.stderr", err):
            self.assertEqual(1, target.main(["verify", "--directory", str(self.root / "private-missing")]))
        self.assertEqual("", out.getvalue())
        self.assertEqual({"status": "failed", "error": "input-path"}, json.loads(err.getvalue()))

    def test_current_job_commit_is_required_at_stage_and_verify(self):
        with mock.patch.dict(target.os.environ, {"GITHUB_SHA": ""}):
            self.assert_stage_rejected()
        target.stage(self.build, self.output)
        with mock.patch.dict(target.os.environ, {"GITHUB_SHA": "b" * 40}):
            with self.assertRaises(target.ArtifactError):
                target.verify(self.output)

    def test_existing_output_and_old_files_are_preserved(self):
        self.output.mkdir()
        marker = self.output / "keep.txt"
        marker.write_bytes(b"keep")
        with self.assertRaises(target.ArtifactError):
            target.stage(self.build, self.output)
        self.assertEqual(b"keep", marker.read_bytes())

    def test_exactly_one_finished_receipt_is_required(self):
        self.write_json(self.build / "build-duplicate-finished.json", self.receipt)
        self.assert_stage_rejected()

    def test_finished_receipt_flags_and_exit_codes_are_strictly_typed(self):
        original = copy.deepcopy(self.receipt)
        for key, value in (("schema", "unknown"), ("full_driver_build", 1), ("ci_clean", False),
                           ("toolchain_unchanged", "true"), ("exit_code", 1), ("exit_code", False),
                           ("source_recheck_exit_code", None), ("source_recheck_exit_code", False),
                           ("validation_error", "still broken"), ("launcher_sha256", "0" * 64)):
            with self.subTest(key=key, value=value):
                self.receipt = copy.deepcopy(original)
                self.receipt[key] = value
                self.write_json(self.finished, self.receipt)
                self.assert_stage_rejected()

    def test_source_commit_archive_patch_and_file_count_are_pinned(self):
        original = copy.deepcopy(self.provenance)
        for key, value in (("commit", "b" * 40), ("source_zip_sha256", "0" * 64),
                           ("patch_sha256", "0" * 64), ("checked_source_files", True),
                           ("checked_source_files", 1), ("source_root", str(self.root))):
            with self.subTest(key=key):
                self.provenance = copy.deepcopy(original)
                self.provenance[key] = value
                self.save_source_change()
                self.assert_stage_rejected()

    def test_source_receipt_digest_cannot_be_replaced_unnoticed(self):
        self.provenance["prepared_utc"] = "different"
        self.write_json(self.source_receipt, self.provenance)
        self.assert_stage_rejected()

    def test_changed_source_list_and_live_patched_bytes_are_checked(self):
        self.provenance["changed_files"]["../foreign"] = {"before": "1" * 64, "after": "2" * 64}
        self.save_source_change()
        self.assert_stage_rejected()
        self.provenance["changed_files"].pop("../foreign")
        self.save_source_change()
        name = sorted(target.build.CHANGED)[0]
        (self.source / name).write_bytes(b"unexpected edit")
        self.assert_stage_rejected()

    def test_artifact_metadata_rejects_extra_items_paths_size_and_sha_forgery(self):
        original = copy.deepcopy(self.receipt)
        corruptions = [[], [original["artifacts"][0], original["artifacts"][0]],
            [{**original["artifacts"][0], "path": "../outside.dll"}],
            [{**original["artifacts"][0], "bytes": True}],
            [{**original["artifacts"][0], "bytes": 1}],
            [{**original["artifacts"][0], "sha256": "0" * 64}],
            [{**original["artifacts"][0], "private_path": "private"}]]
        for artifacts in corruptions:
            with self.subTest(artifacts=artifacts):
                self.receipt = copy.deepcopy(original)
                self.receipt["artifacts"] = artifacts
                self.write_json(self.finished, self.receipt)
                self.assert_stage_rejected()

    def test_other_same_hash_dll_path_is_rejected(self):
        other = self.root / "other.dll"
        other.write_bytes(self.core.read_bytes())
        self.receipt["artifacts"][0]["path"] = str(other)
        self.write_json(self.finished, self.receipt)
        self.assert_stage_rejected()

    def test_extra_libretro_dll_on_disk_is_rejected(self):
        (self.source / "other_libretro.dll").write_bytes(self.core.read_bytes())
        self.assert_stage_rejected()

    def test_corresponding_archive_hash_and_filename_are_checked(self):
        original = copy.deepcopy(self.receipt)
        for change in ({"file": "../candidate-source.zip"}, {"sha256": "0" * 64}, {"bytes": 10}):
            with self.subTest(change=change):
                self.receipt = copy.deepcopy(original)
                self.receipt["corresponding_source"].update(change)
                self.write_json(self.finished, self.receipt)
                self.assert_stage_rejected()

    def test_matching_hash_non_amd64_core_is_rejected(self):
        data = bytearray(self.core.read_bytes())
        struct.pack_into("<H", data, 0x84, 0x14C)
        self.core.write_bytes(data)
        self.receipt["artifacts"][0]["sha256"] = target.build.sha(self.core)
        self.write_json(self.finished, self.receipt)
        self.assert_stage_rejected()

    def test_verify_rejects_extra_file_and_subdirectory(self):
        target.stage(self.build, self.output)
        (self.output / "private.log").write_bytes(b"do not upload")
        with self.assertRaises(target.ArtifactError):
            target.verify(self.output)

    def test_verify_rehashes_both_core_and_source(self):
        target.stage(self.build, self.output)
        for name in ("core.dll", "candidate-source.zip"):
            with self.subTest(name=name):
                path = self.output / name
                previous = path.read_bytes()
                path.write_bytes(previous[:-1] + bytes([previous[-1] ^ 1]))
                with self.assertRaises(target.ArtifactError):
                    target.verify(self.output)
                path.write_bytes(previous)

    def test_verify_rejects_manifest_privacy_fields_and_nested_forgery(self):
        original = target.stage(self.build, self.output)
        changes = [{"private_path": "private"}, {"save_policy": "migrate-old-saves"},
            {"status": "verified"}, {"ci_clean": 1}, {"build_exit_code": False},
            {"validation": {"native_lifecycle": "passed", "java_jni": "not-run", "minecraft": "not-run"}},
            {"artifacts": {**original["artifacts"], "extra": {}}},
            {"artifacts": {**original["artifacts"], "core": {**original["artifacts"]["core"], "file": "../core.dll"}}},
            {"artifacts": {**original["artifacts"], "source": {**original["artifacts"]["source"], "bytes": True}}}]
        for change in changes:
            with self.subTest(change=change):
                self.write_json(self.output / "candidate.json", {**original, **change})
                with self.assertRaises(target.ArtifactError):
                    target.verify(self.output)

    def test_duplicate_json_keys_are_rejected(self):
        self.finished.write_text('{"exit_code":1,"exit_code":0}', encoding="utf-8")
        self.assert_stage_rejected()

    def test_nested_reparse_point_is_rejected(self):
        original = Path.lstat
        def stat_result(path, *args, **kwargs):
            if path == self.core:
                return mock.Mock(st_mode=0, st_file_attributes=0x400)
            return original(path, *args, **kwargs)
        with mock.patch.object(Path, "lstat", stat_result):
            self.assert_stage_rejected()

    def test_post_copy_corruption_is_detected_before_stage_reports_success(self):
        original = target.probe.save_new
        def save_and_corrupt(path, value):
            original(path, value)
            (self.output / "core.dll").write_bytes(b"corrupted copied bytes")
        with mock.patch.object(target.probe, "save_new", side_effect=save_and_corrupt):
            with self.assertRaises(target.ArtifactError):
                target.stage(self.build, self.output)

    def test_changed_input_after_receipt_check_does_not_become_new_manifest_identity(self):
        original = target.inspect_build
        def inspect_and_change(root):
            result = original(root)
            data = bytearray(self.core.read_bytes())
            data[-1] ^= 1  # Still a valid fake PE, but no longer the receipted file.
            self.core.write_bytes(data)
            return result
        with mock.patch.object(target, "inspect_build", side_effect=inspect_and_change):
            with self.assertRaises(target.ArtifactError):
                target.stage(self.build, self.output)


class SharedCandidateArtifactsTest(unittest.TestCase):
    write_json = staticmethod(CandidateArtifactsTest.write_json)

    def setUp(self):
        CandidateArtifactsTest.setUp(self)
        self.core.write_bytes(imported_pe())
        genie = self.source / "scripts/genie.lua"
        genie.parent.mkdir(parents=True, exist_ok=True)
        genie.write_bytes(b"fake shared patched source")
        self.provenance.update(profile="clang64-shared", patches=target.build.patch_identities("clang64-shared"))
        self.provenance["changed_files"]["scripts/genie.lua"] = {"before": "1" * 64, "after": target.build.sha(genie)}
        self.write_json(self.source_receipt, self.provenance)
        self.msys = self.root / "fixed-msys"
        self.runtime = self.msys / "clang64/bin/libc++.dll"
        self.runtime.parent.mkdir(parents=True)
        self.runtime.write_bytes(imported_pe(("KERNEL32.dll",)))
        archive = self.msys / "clang64/lib/libc++.a"
        archive.parent.mkdir()
        archive.write_bytes(b"!<arch>\n" + b"mutex.cpp.obj/".ljust(16) + b"0".ljust(12)
            + b"0".ljust(6) + b"0".ljust(6) + b"100644".ljust(8) + b"0".ljust(10) + b"`\n")
        for obj, key, value in ((target.probe, "RUNTIME_BYTES", self.runtime.stat().st_size),
                               (target.probe, "RUNTIME_SHA", target.probe.sha(self.runtime)),
                               (target, "STATIC_CXX_SHA", target.build.sha(archive))):
            patcher = mock.patch.object(obj, key, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        patcher = mock.patch.object(ci_prepare, "tool_files", return_value={"fake": "fixed"})
        patcher.start()
        self.addCleanup(patcher.stop)
        self.tool_path = self.root / "toolchain-receipt.json"
        self.tool = {"schema": "piq-mame-ci-toolchain-receipt-1", "status": "ready", "profile": "clang64-shared",
            "msys_root": str(self.msys), "lock_sha256": target.build.sha(ci_prepare.lock_path("clang64-shared")),
            "tool_files": {"fake": "fixed"}}
        lock = ci_prepare.load_lock(ci_prepare.lock_path("clang64-shared"))
        self.tool["packages"] = lock["base_manifest"] | {p["name"]: p["version"] for p in lock["packages"]}
        self.write_json(self.tool_path, self.tool)
        self.preflight_path = self.build / "shared-preflight.json"
        self.preflight = {"schema": "piq-mame-shared-preflight-1", "status": "passed", "profile": "clang64-shared",
            "source_receipt_sha256": target.build.sha(self.source_receipt), "toolchain_receipt_sha256": target.build.sha(self.tool_path),
            "preflight_sha256": target.build.sha(Path(target.__file__).with_name("preflight_shared.py")),
            "core_executed": False, "full_build": False}
        self.write_json(self.preflight_path, self.preflight)
        self.link = self.source / "build/mame.map"
        self.link.parent.mkdir()
        self.link.write_text("Address Size Align Out In Symbol\n001 10 4 .tls\n001 0 0 _tls_used\n001 0 0 _tls_index\n", encoding="utf-8")
        self.receipt.update(profile="clang64-shared", patches=target.build.patch_identities("clang64-shared"),
            portable_msys=str(self.msys), source_receipt_sha256=target.build.sha(self.source_receipt),
            toolchain_receipt_sha256=target.build.sha(self.tool_path), shared_preflight_sha256=target.build.sha(self.preflight_path),
            link_map={"path": str(self.link), "bytes": self.link.stat().st_size, "sha256": target.build.sha(self.link)})
        self.receipt["artifacts"][0]["sha256"] = target.build.sha(self.core)
        self.write_json(self.finished, self.receipt)

    def stage(self):
        return target.stage(self.build, self.output, "clang64-shared", self.msys)

    def rejected(self):
        with self.assertRaises((target.ArtifactError, target.probe.ProbeError)):
            self.stage()
        self.assertFalse(self.output.exists())

    def save_preflight(self):
        self.write_json(self.preflight_path, self.preflight)
        self.receipt["shared_preflight_sha256"] = target.build.sha(self.preflight_path)
        self.write_json(self.finished, self.receipt)

    def save_map(self, text):
        self.link.write_text(text, encoding="utf-8")
        self.receipt["link_map"].update(bytes=self.link.stat().st_size, sha256=target.build.sha(self.link))
        self.write_json(self.finished, self.receipt)

    def test_shared_is_exact_eight_files_with_bound_static_evidence_no_runtime_claim(self):
        manifest = self.stage()
        self.assertEqual(8, len(list(self.output.iterdir())))
        self.assertEqual(target.SHARED_FILES, {p.name for p in self.output.iterdir()})
        self.assertEqual(target.SHARED_SCHEMA, manifest["schema"])
        self.assertEqual(target.build.sha(self.core), target.verify(self.output, "clang64-shared"))
        self.assertEqual({"not-run"}, set(manifest["validation"].values()))
        self.assertEqual(target.build.sha(self.link), manifest["static_link"]["map_sha256"])
        serialized = (self.output / "candidate.json").read_text()
        for private in (str(self.root), "portable_msys", "launcher_pid", "tool_files"):
            self.assertNotIn(private, serialized)
        self.assertEqual(self.runtime.read_bytes(), (self.output / "libc++.dll").read_bytes())

    def test_profile_must_be_explicit_both_stage_and_verify(self):
        with self.assertRaises(target.ArtifactError):
            target.stage(self.build, self.output)
        with self.assertRaises(target.ArtifactError):
            target.stage(self.build, self.output, "clang64-shared")
        self.stage()
        with self.assertRaises(target.ArtifactError):
            target.verify(self.output)

    def test_local_tool_receipt_cannot_claim_ci_artifact(self):
        self.tool["schema"] = "piq-mame-local-toolchain-receipt-1"
        self.write_json(self.tool_path, self.tool)
        self.receipt["toolchain_receipt_sha256"] = target.build.sha(self.tool_path)
        self.preflight["toolchain_receipt_sha256"] = target.build.sha(self.tool_path)
        self.save_preflight()
        self.rejected()

    def test_preflight_status_profile_execution_scope_and_identity_fail_closed(self):
        original = copy.deepcopy(self.preflight)
        for key, value in (("status", "failed"), ("profile", "gcc-static"), ("core_executed", 0),
                           ("full_build", 0), ("source_receipt_sha256", "0" * 64), ("preflight_sha256", "0" * 64)):
            with self.subTest(key=key):
                self.preflight = {**original, key: value}
                self.save_preflight()
                self.rejected()

    def test_static_archive_parser_recovers_member_name(self):
        self.assertEqual({"mutex.cpp.obj"}, target.static_archive_members(self.msys / "clang64/lib/libc++.a"))

    def test_static_archive_parser_handles_coff_and_gnu_long_names(self):
        archive = self.msys / "clang64/lib/libc++.a"
        def entry(name, body):
            return (name.encode().ljust(16) + b"0".ljust(12) + b"0".ljust(6) + b"0".ljust(6)
                    + b"100644".ljust(8) + str(len(body)).encode().ljust(10) + b"`\n"
                    + body + (b"\n" if len(body) % 2 else b""))
        for suffix in (b"\0", b"/\n"):
            archive.write_bytes(b"!<arch>\n" + entry("//", b"condition_variable.cpp.obj" + suffix) + entry("/0", b""))
            with mock.patch.object(target, "STATIC_CXX_SHA", target.build.sha(archive)):
                self.assertEqual({"condition_variable.cpp.obj"}, target.static_archive_members(archive))

    def test_map_requires_native_tls_and_rejects_static_member_or_gnu_emutls(self):
        original = self.link.read_text()
        for extra in ("__emutls_get_address", "libwinpthread-1.dll", "libstdc++.a(foo.o)", "libgcc.a(x.o)",
                      "mutex.cpp.obj:(.text)", "C:/fixed/mutex.cpp.obj:(.text)", "libc++.a(foo.o)"):
            with self.subTest(extra=extra):
                self.save_map(original + "0 0 0 " + extra + "\n")
                self.rejected()
        for token in (".tls", "_tls_used", "_tls_index"):
            self.save_map(original.replace(token, "absent"))
            self.rejected()

    def test_map_hash_or_path_cannot_be_forged(self):
        original = copy.deepcopy(self.receipt)
        for key, value in (("sha256", "0" * 64), ("bytes", True), ("path", str(self.core))):
            self.receipt = copy.deepcopy(original)
            self.receipt["link_map"][key] = value
            self.write_json(self.finished, self.receipt)
            self.rejected()

    def test_runtime_and_license_hashes_checked_at_stage_and_verify(self):
        with mock.patch.object(target.probe, "RUNTIME_SHA", "0" * 64):
            self.rejected()
        with mock.patch.object(target, "LICENSE_ROOT", self.root):
            self.rejected()
        self.stage()
        for name in ("libc++.dll", *target.LICENSES):
            path = self.output / name
            original = path.read_bytes()
            path.write_bytes(original[:-1] + bytes([original[-1] ^ 1]))
            with self.assertRaises(target.ArtifactError):
                target.verify(self.output, "clang64-shared")
            path.write_bytes(original)

    def test_manifest_rejects_added_private_fields_static_claims_and_imports(self):
        original = self.stage()
        variants = [{**original, "profile": "gcc-static"}, {**original, "patches": []}]
        for key, value in (("private_path", str(self.root)), ("native_tls_map", 1),
                           ("forbidden_runtime_map_entries", False), ("imports", ["unknown.dll"]),
                           ("core_sha256", "0" * 64), ("static_libcxx_sha256", "0" * 64)):
            variants.append({**original, "static_link": {**original["static_link"], key: value}})
        for value in variants:
            self.write_json(self.output / "candidate.json", value)
            with self.assertRaises(target.ArtifactError):
                target.verify(self.output, "clang64-shared")


if __name__ == "__main__":
    unittest.main()
