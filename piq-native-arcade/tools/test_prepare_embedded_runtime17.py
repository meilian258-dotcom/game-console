"""Small synthetic fixtures only; never extract or execute a real runtime."""
from dataclasses import replace
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile


SPEC = importlib.util.spec_from_file_location("embedded_runtime17", Path(__file__).with_name("prepare_embedded_runtime17.py"))
runtime = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runtime
SPEC.loader.exec_module(runtime)


def digest(data):
    return hashlib.sha256(data).hexdigest().upper()


class EmbeddedRuntimeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="native17-fixture-")
        self.addCleanup(self.temp.cleanup)
        self.base = Path(self.temp.name)
        self.archive = self.base / "runtime.zip"
        self.licenses = self.base / "licenses"
        self.licenses.mkdir()
        self.output = self.base / "generated/embeddedRuntime17"
        self.contents = {item.path: f"opaque fixture {index}".encode()
                         for index, item in enumerate(runtime.PROFILE.artifacts)}
        self.write_archive(self.contents.items())
        licenses = []
        for item in runtime.PROFILE.licenses:
            content = ("original license " + item.path).encode()
            (self.licenses / item.path).write_bytes(content)
            licenses.append(replace(item, size=len(content), sha256=digest(content)))
        self.profile = runtime.Profile(self.archive.stat().st_size, digest(self.archive.read_bytes()),
            tuple(replace(item, size=len(self.contents[item.path]), sha256=digest(self.contents[item.path]))
                  for item in runtime.PROFILE.artifacts), tuple(licenses))

    def write_archive(self, pairs):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.archive, "w", compression=zipfile.ZIP_DEFLATED) as output:
                for name, content in pairs:
                    output.writestr(name, content)

    def repin_archive(self):
        self.profile = replace(self.profile, archive_size=self.archive.stat().st_size,
                               archive_sha256=digest(self.archive.read_bytes()))

    def prepare(self):
        return runtime.prepare(self.archive, self.licenses, self.output, self.profile)

    def test_plan_is_read_only_and_excludes_three_gba_payloads(self):
        before = sorted(path.relative_to(self.base).as_posix() for path in self.base.rglob("*"))
        report = runtime.plan(self.archive, self.licenses, self.profile)
        self.assertTrue(report["readOnly"])
        self.assertEqual((9, 6, 3, 12), (report["archiveEntries"], report["embeddedPayloads"],
                                       report["excludedPayloads"], len(report["outputEntries"])))
        self.assertFalse(any("piq-gba/" in item["resourcePath"] for item in report["outputEntries"]))
        self.assertEqual(before, sorted(path.relative_to(self.base).as_posix() for path in self.base.rglob("*")))

    def test_prepare_opaque_payloads_license_manifest_and_notice(self):
        before = self.archive.read_bytes()
        report = self.prepare()
        self.assertFalse(report["reused"])
        runtime.verify_output(self.output, self.profile)
        actual = {path.relative_to(self.output).as_posix() for path in self.output.rglob("*") if path.is_file()}
        self.assertEqual(set(runtime.expected_output(self.profile)), actual)
        for item in self.profile.artifacts:
            path = self.output / (runtime.PREFIX + item.path)
            if item.embedded:
                self.assertEqual(self.contents[item.path], path.read_bytes())
            else:
                self.assertFalse(path.exists())
        for item in self.profile.licenses:
            self.assertEqual((self.licenses / item.path).read_bytes(),
                             (self.output / (runtime.LICENSE_PREFIX + item.path)).read_bytes())
        manifest = json.loads((self.output / (runtime.PREFIX + "manifest.json")).read_bytes())
        self.assertEqual("0.1.0-alpha.17", manifest["modVersion"])
        self.assertEqual(6, len(manifest["artifacts"]))
        self.assertEqual(3, len(manifest["excludedArtifacts"]))
        self.assertEqual(before, self.archive.read_bytes())
        self.assertFalse(any(path.name.endswith(".class") for path in self.output.rglob("*")))

    def test_exact_output_is_reused_without_rewriting(self):
        self.prepare()
        before = {path: path.stat().st_mtime_ns for path in self.output.rglob("*") if path.is_file()}
        self.assertTrue(self.prepare()["reused"])
        self.assertEqual(before, {path: path.stat().st_mtime_ns for path in before})

    def test_gradle_precreated_empty_output_is_published_without_recursive_delete(self):
        self.output.mkdir(parents=True)
        self.assertFalse(self.prepare()["reused"])
        runtime.verify_output(self.output, self.profile)

    def test_changed_archive_overall_hash_rejected_before_output(self):
        with self.archive.open("ab") as stream:
            stream.write(b"unexpected trailing bytes")
        with self.assertRaisesRegex(runtime.VerificationError, "source archive"):
            self.prepare()
        self.assertFalse(self.output.parent.exists())

    def test_missing_input_does_not_reuse_old_output(self):
        self.prepare()
        self.archive.unlink()
        with self.assertRaisesRegex(runtime.VerificationError, "Missing"):
            self.prepare()
        runtime.verify_output(self.output, self.profile)

    def test_duplicate_member_rejected(self):
        self.write_archive([*self.contents.items(), next(iter(self.contents.items()))])
        self.repin_archive()
        with self.assertRaisesRegex(runtime.VerificationError, "Duplicate"):
            self.prepare()

    def test_unexpected_archive_member_rejected(self):
        self.write_archive([*self.contents.items(), ("unexpected.class", b"unwanted")])
        self.repin_archive()
        with self.assertRaisesRegex(runtime.VerificationError, "entry set"):
            self.prepare()

    def test_missing_archive_member_rejected(self):
        self.write_archive(list(self.contents.items())[:-1])
        self.repin_archive()
        with self.assertRaisesRegex(runtime.VerificationError, "entry set"):
            self.prepare()

    def test_payload_hash_checked_even_with_repinning_outer_archive(self):
        name = next(iter(self.contents))
        self.contents[name] = bytes([self.contents[name][0] ^ 1]) + self.contents[name][1:]
        self.write_archive(self.contents.items())
        self.repin_archive()
        with self.assertRaisesRegex(runtime.VerificationError, "Identity mismatch"):
            self.prepare()

    def test_excluded_gba_content_is_still_verified(self):
        name = next(item.path for item in self.profile.artifacts if not item.embedded)
        self.contents[name] += b"changed"
        self.write_archive(self.contents.items())
        self.repin_archive()
        with self.assertRaisesRegex(runtime.VerificationError, "size mismatch"):
            self.prepare()

    def test_license_change_is_rejected_without_output(self):
        (self.licenses / self.profile.licenses[0].path).write_bytes(b"changed license")
        with self.assertRaisesRegex(runtime.VerificationError, "Identity mismatch"):
            self.prepare()
        self.assertFalse(self.output.parent.exists())

    def test_unsafe_paths_rejected(self):
        for name in ("../escape", "/absolute", "C:/drive", "back\\slash", "a//b", "a/./b", "a/../b"):
            with self.subTest(name=name), self.assertRaises(runtime.VerificationError):
                runtime.safe_relative(name)

    def test_archive_symlink_entry_rejected(self):
        with zipfile.ZipFile(self.archive, "w") as output:
            for index, (name, content) in enumerate(self.contents.items()):
                info = zipfile.ZipInfo(name)
                if index == 0:
                    info.create_system = 3
                    info.external_attr = (0o120777 << 16)
                output.writestr(info, content)
        self.repin_archive()
        with self.assertRaisesRegex(runtime.VerificationError, "Unsupported archive"):
            self.prepare()

    def test_existing_unknown_tree_never_deleted_or_overwritten(self):
        self.output.mkdir(parents=True)
        marker = self.output / "user-file.txt"
        marker.write_bytes(b"preserve me")
        with self.assertRaisesRegex(runtime.VerificationError, "Unexpected generated resource"):
            self.prepare()
        self.assertEqual(b"preserve me", marker.read_bytes())
        self.assertEqual([marker], list(self.output.iterdir()))

    def test_missing_generated_file_fails_without_silent_repair(self):
        self.prepare()
        (self.output / (runtime.PREFIX + self.profile.artifacts[0].path)).unlink()
        with self.assertRaisesRegex(runtime.VerificationError, "incomplete"):
            self.prepare()

    def test_extra_empty_directory_rejected(self):
        self.prepare()
        (self.output / "unapproved").mkdir()
        with self.assertRaisesRegex(runtime.VerificationError, "Unexpected generated directory"):
            self.prepare()

    def test_final_input_drift_does_not_publish_or_leave_staging(self):
        # The initial plan succeeds, then source changes during the extraction phase.
        real_inspect = runtime.inspect_licenses
        count = 0
        def change_on_second_check(root, profile):
            nonlocal count
            count += 1
            if count == 2:
                raise runtime.VerificationError("fixture source drift")
            return real_inspect(root, profile)
        with mock.patch.object(runtime, "inspect_licenses", side_effect=change_on_second_check):
            with self.assertRaisesRegex(runtime.VerificationError, "fixture source drift"):
                self.prepare()
        self.assertFalse(self.output.exists())
        self.assertEqual([], list(self.output.parent.iterdir()))

    def test_cli_refuses_output_outside_owned_generated_directory(self):
        with mock.patch.object(sys, "argv", ["prepare", "--prepare", "--output", str(self.base / "unsafe")]):
            with mock.patch("sys.stdout", new_callable=io.StringIO) as output:
                self.assertEqual(1, runtime.main())
                self.assertIn("--output must be", output.getvalue())
        self.assertFalse((self.base / "unsafe").exists())

    def test_pinned_production_profile_is_six_native_three_gba(self):
        self.assertEqual(9, len(runtime.PROFILE.artifacts))
        self.assertEqual(432744687, sum(item.size for item in runtime.PROFILE.artifacts if item.embedded))
        self.assertEqual(4854713, sum(item.size for item in runtime.PROFILE.artifacts if not item.embedded))
        for item in runtime.PROFILE.artifacts:
            self.assertEqual(64, len(item.sha256))
            self.assertEqual(item.embedded, item.path.startswith("piq-native-arcade/"))

    def test_gradle_uses_jar_only_opaque_resources_and_fc48(self):
        build = (Path(__file__).resolve().parents[1] / "build.gradle").read_text(encoding="utf-8")
        self.assertIn("piq_fc_arcade-0.31.0-alpha.48.jar", build)
        self.assertIn("from(embeddedRuntimeOutput)", build)
        self.assertIn("from(embeddedRuntime011)", build)
        self.assertIn("dependsOn prepareEmbeddedRuntime", build)
        self.assertIn("getOrElse('python')", build)
        self.assertNotIn("sourceSets.main.resources.srcDir embeddedRuntime", build)
        self.assertNotIn("zipTree", build)
        self.assertNotIn("jarJar(", build)


if __name__ == "__main__":
    unittest.main()
