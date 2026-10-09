import hashlib
import json
from pathlib import Path
import struct
import tempfile
import unittest

import prepare_mame_lifecycle_runtime as target


class LifecyclePackagingTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.core = self.root / "candidate.dll"
        raw = bytearray(4096)
        raw[:2] = b"MZ"
        struct.pack_into("<I", raw, 0x3c, 0x80)
        raw[0x80:0x84] = b"PE\0\0"
        struct.pack_into("<H", raw, 0x84, 0x8664)
        struct.pack_into("<H", raw, 0x96, 0x2000)
        struct.pack_into("<H", raw, 0x98, 0x20b)
        self.core.write_bytes(raw)
        self.patch = self.root / "fix.patch"
        self.patch.write_bytes(b"reviewed source patch")
        self.manifest = {"schema": 1, "upstreamCommit": target.UPSTREAM,
                         "resource": target.RESOURCE, "platform": "windows-x64",
                         "savePolicy": "separate-identity-no-migration",
                         "patchSha256": target.sha(self.patch),
                         "artifact": {"bytes": len(raw), "sha256": hashlib.sha256(raw).hexdigest()}}
        self.lock = self.root / "runtime.json"
        self.save_lock()

    def save_lock(self):
        self.lock.write_text(json.dumps(self.manifest), encoding="utf-8")

    def test_stages_only_fixed_dll_and_public_notice_then_reuses(self):
        out = self.root / "resources"
        manifest = target.load_manifest(self.lock, self.patch)
        self.assertFalse(target.prepare(self.core, manifest, out)["reused"])
        self.assertTrue(target.prepare(self.core, manifest, out)["reused"])
        self.assertEqual({target.RESOURCE, target.NOTICE},
                         {p.relative_to(out).as_posix() for p in out.rglob("*") if p.is_file()})
        self.assertEqual(self.core.read_bytes(), (out / target.RESOURCE).read_bytes())

    def test_accepts_gradle_empty_generated_directory(self):
        out = self.root / "resources"
        out.mkdir()
        target.prepare(self.core, self.manifest, out)
        target.verify_output(out, self.manifest)

    def test_wrong_hash_or_changed_patch_is_rejected(self):
        self.core.write_bytes(b"x" * 4096)
        with self.assertRaises(ValueError):
            target.prepare(self.core, self.manifest, self.root / "resources")
        self.patch.write_bytes(b"different patch")
        with self.assertRaises(ValueError):
            target.load_manifest(self.lock, self.patch)

    def test_manifest_without_reviewed_artifact_is_rejected(self):
        self.manifest.pop("artifact")
        self.save_lock()
        with self.assertRaises(ValueError):
            target.load_manifest(self.lock, self.patch)

    def test_notice_does_not_copy_private_receipt_fields(self):
        self.manifest["sourceRoot"] = "private-local-path"
        self.manifest["commandLine"] = "private-build-command"
        self.manifest["artifact"]["sourcePath"] = "private-core-path"
        self.save_lock()
        manifest = target.load_manifest(self.lock, self.patch)
        self.assertNotIn(b"private", target.notice_bytes(manifest))

    def test_wrong_pe_architecture_even_with_matching_hash_is_rejected(self):
        raw = bytearray(self.core.read_bytes())
        struct.pack_into("<H", raw, 0x84, 0x14c)
        self.core.write_bytes(raw)
        self.manifest["artifact"]["sha256"] = target.sha(self.core)
        with self.assertRaises(ValueError):
            target.verify_core(self.core, self.manifest)

    def test_does_not_overwrite_nonempty_unknown_or_corrupt_output(self):
        out = self.root / "resources"
        out.mkdir()
        private = out / "existing.txt"
        private.write_bytes(b"preserve me")
        with self.assertRaises(ValueError):
            target.prepare(self.core, self.manifest, out)
        self.assertEqual(b"preserve me", private.read_bytes())
        valid = self.root / "valid"
        target.prepare(self.core, self.manifest, valid)
        (valid / target.RESOURCE).write_bytes(b"damaged")
        with self.assertRaises(ValueError):
            target.prepare(self.core, self.manifest, valid)
        self.assertEqual(b"damaged", (valid / target.RESOURCE).read_bytes())

    def test_manifest_rejects_other_resource_or_save_migration(self):
        for key, value in (("resource", "../core.dll"), ("savePolicy", "migrate"),
                           ("platform", "linux-x64"), ("upstreamCommit", "latest")):
            old = self.manifest[key]
            self.manifest[key] = value
            self.save_lock()
            with self.assertRaises(ValueError):
                target.load_manifest(self.lock, self.patch)
            self.manifest[key] = old

    def shared_manifest(self):
        self.shared_patch = self.root / "shared.patch"
        self.shared_patch.write_bytes(b"reviewed shared-runtime build patch")
        self.manifest.pop("patchSha256")
        self.manifest.update(schema=2, profile="clang64-shared", runtime=dict(target.SHARED_RUNTIME),
                             patches={target.PATCH.name: target.sha(self.patch),
                                      target.SHARED_PATCH.name: target.sha(self.shared_patch)})
        self.save_lock()

    def load_shared(self):
        return target.load_manifest(self.lock, self.patch, self.shared_patch)

    def test_shared_stages_same_two_resources_with_explicit_public_notice(self):
        self.shared_manifest()
        manifest = self.load_shared()
        self.assertEqual(self.manifest, manifest)
        out = self.root / "shared-resources"
        self.assertFalse(target.prepare(self.core, manifest, out)["reused"])
        self.assertTrue(target.prepare(self.core, manifest, out)["reused"])
        self.assertEqual({target.RESOURCE, target.NOTICE},
                         {p.relative_to(out).as_posix() for p in out.rglob("*") if p.is_file()})
        self.assertEqual(manifest, json.loads((out / target.NOTICE).read_text(encoding="utf-8")))

    def test_shared_requires_exact_two_source_patches(self):
        self.shared_manifest()
        expected = self.manifest["patches"]
        for patches in (None, {}, {target.PATCH.name: expected[target.PATCH.name]},
                        {target.SHARED_PATCH.name: expected[target.SHARED_PATCH.name]},
                        {**expected, "extra.patch": "0" * 64},
                        {**expected, target.SHARED_PATCH.name: "0" * 64}):
            with self.subTest(patches=patches):
                self.manifest["patches"] = patches
                self.save_lock()
                with self.assertRaises(ValueError):
                    self.load_shared()

    def test_shared_rejects_either_changed_source_patch(self):
        self.shared_manifest()
        for patch in (self.patch, self.shared_patch):
            with self.subTest(patch=patch.name):
                original = patch.read_bytes()
                patch.write_bytes(b"unreviewed change")
                with self.assertRaises(ValueError):
                    self.load_shared()
                patch.write_bytes(original)

    def test_shared_requires_explicit_profile_and_fixed_runtime(self):
        self.shared_manifest()
        for key, value in (("profile", None), ("profile", "gcc-static"),
                           ("runtime", None), ("runtime", {}),
                           ("runtime", {**target.SHARED_RUNTIME, "file": "other.dll"}),
                           ("runtime", {**target.SHARED_RUNTIME, "bytes": 1659393}),
                           ("runtime", {**target.SHARED_RUNTIME, "bytes": 1659392.0}),
                           ("runtime", {**target.SHARED_RUNTIME, "sha256": "0" * 64}),
                           ("runtime", {**target.SHARED_RUNTIME, "sourcePath": "private"})):
            with self.subTest(key=key, value=value):
                original = self.manifest[key]
                self.manifest[key] = value
                self.save_lock()
                with self.assertRaises(ValueError):
                    self.load_shared()
                self.manifest[key] = original

    def test_shared_notice_omits_private_fields_and_acceptance_claims(self):
        self.shared_manifest()
        self.manifest.update(sourceRoot="private-local-path", commandLine="private-command", passed=True)
        self.manifest["artifact"]["sourcePath"] = "private-core-path"
        self.save_lock()
        manifest = self.load_shared()
        self.assertNotIn(b"private", target.notice_bytes(manifest))
        self.assertNotIn("passed", manifest)
        self.assertEqual({"bytes", "sha256"}, set(manifest["artifact"]))

    def test_shared_keeps_pe_identity_and_no_overwrite_rules(self):
        self.shared_manifest()
        manifest = self.load_shared()
        out = self.root / "shared-resources"
        target.prepare(self.core, manifest, out)
        (out / target.NOTICE).write_bytes(b"preserve existing notice")
        with self.assertRaises(ValueError):
            target.prepare(self.core, manifest, out)
        self.assertEqual(b"preserve existing notice", (out / target.NOTICE).read_bytes())
        raw = bytearray(self.core.read_bytes())
        struct.pack_into("<H", raw, 0x84, 0x14c)
        self.core.write_bytes(raw)
        manifest["artifact"]["sha256"] = target.sha(self.core)
        with self.assertRaises(ValueError):
            target.prepare(self.core, manifest, self.root / "wrong-architecture")

    def test_schema_one_notice_remains_byte_identical_without_shared_fields(self):
        manifest = target.load_manifest(self.lock, self.patch)
        self.assertEqual(self.manifest, manifest)
        self.assertEqual((json.dumps(self.manifest, sort_keys=True, indent=2) + "\n").encode("utf-8"),
                         target.notice_bytes(manifest))

    def test_schema_requires_integer_not_boolean_or_float(self):
        for schema in (True, 1.0):
            with self.subTest(schema=schema):
                self.manifest["schema"] = schema
                self.save_lock()
                with self.assertRaises(ValueError):
                    target.load_manifest(self.lock, self.patch)
        self.shared_manifest()
        self.manifest["schema"] = 2.0
        self.save_lock()
        with self.assertRaises(ValueError):
            self.load_shared()


if __name__ == "__main__":
    unittest.main()
