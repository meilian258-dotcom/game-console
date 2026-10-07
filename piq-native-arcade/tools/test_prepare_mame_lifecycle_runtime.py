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


if __name__ == "__main__":
    unittest.main()
