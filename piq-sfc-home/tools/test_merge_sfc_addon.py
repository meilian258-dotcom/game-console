"""Offline merger tests. Home6 metadata is a synthetic fixture, not a release.

No production build/config/source changes; no game/native process or installation.
"""
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile
import merge_sfc_addon as merger

HOME5 = merger.ROOT / "制作Mod/03-街机模拟/PIQ-SFC家用/0.1.0-alpha.5/piq_sfc_home-0.1.0-alpha.5.jar"
HOME5_SHA = "82578C8DEF9567B1408D8B7F384E8DCC92D388498091ADC0EC308D56AF5D811C"


def archive(path, entries):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as z:
        for name, content in entries.items():
            info = zipfile.ZipInfo(name)
            # Windows ZipInfo normalizes backslashes during construction; keep
            # intentionally malformed test names so the reader sees the attack.
            info.filename = name
            z.writestr(info, content)


class MergerTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        core_sha, cls.core = merger.read_archive(merger.FROZEN_CORE)
        home_sha, cls.home5 = merger.read_archive(HOME5)
        if core_sha != merger.FROZEN_SHA or home_sha != HOME5_SHA:
            raise AssertionError("Historical frozen inputs must be unchanged")

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="piq-sfc-merge-test-")
        self.addCleanup(self.temp.cleanup)
        self.folder = Path(self.temp.name)
        self.home = dict(self.home5)
        self.home[merger.META] = self.home[merger.META].replace(b'0.1.0-alpha.5', b'0.1.0-alpha.6').replace(b'0.31.0-alpha.18', b'0.31.0-alpha.19')
        self.input = self.folder / "synthetic-home6-metadata-fixture.jar"
        archive(self.input, self.home)

    def plan(self):
        return merger.plan(merger.FROZEN_CORE, self.input, merger.sha(self.input.read_bytes()), "0.1.0-alpha.6")

    def test_deterministic_merge_and_every_original_entry(self):
        p = self.plan()
        a = merger.build(self.folder / "a.jar", p)
        b = merger.build(self.folder / "b.jar", p)
        self.assertEqual(a["delivery_sha256"], b["delivery_sha256"])
        self.assertEqual(a["protected"]["frozen_sfc6"]["classes"], 50)
        self.assertEqual(a["protected"]["home"]["classes"], 120)
        self.assertEqual(a["duplicate_classes"], 0)
        self.assertEqual(a["wasm_owners"], [merger.WASM])
        self.assertFalse(a["installed"])
        self.assertFalse(a["minecraft_started"])
        actual = merger.read_archive(self.folder / "a.jar")[1]
        for source in (self.core, self.home):
            for name, value in source.items():
                if name not in merger.SYNTHESIZED:
                    self.assertEqual(value, actual[name], name)

    def test_two_legacy_ids_and_original_core_metadata_semantics(self):
        merged = merger.tomllib.loads(self.plan().entries[merger.META].decode())
        c = merger.tomllib.loads(self.core[merger.META].decode())
        self.assertEqual(merged["mods"][0], c["mods"][0])
        self.assertEqual(merged["dependencies"]["piq_sfc_arcade"], c["dependencies"]["piq_sfc_arcade"])
        self.assertEqual([x["modId"] for x in merged["mods"]], ["piq_sfc_arcade", "piq_sfc_home"])
        self.assertEqual(merged["mods"][1]["version"], "0.1.0-alpha.6")
        self.assertNotIn("piq_retro_platform", merged["dependencies"])

    def test_core_hash_mutation(self):
        bad = self.folder / "core.jar"
        data = dict(self.core)
        data[merger.WASM] += b"corruption"
        archive(bad, data)
        with self.assertRaisesRegex(ValueError, "Frozen SFC6 archive SHA"):
            merger.plan(bad, self.input, merger.sha(self.input.read_bytes()), "0.1.0-alpha.6")

    def test_wrong_home_hash(self):
        with self.assertRaisesRegex(ValueError, "Home archive SHA"):
            merger.plan(merger.FROZEN_CORE, self.input, "0" * 64, "0.1.0-alpha.6")

    def test_old_home_cannot_be_mislabeled_new_release(self):
        with self.assertRaisesRegex(ValueError, "Home version"):
            merger.plan(merger.FROZEN_CORE, HOME5, HOME5_SHA, "0.1.0-alpha.6")

    def test_existing_output_survives(self):
        output = self.folder / "out.jar"
        output.write_bytes(b"must survive")
        with self.assertRaisesRegex(ValueError, "overwrite"):
            merger.build(output, self.plan())
        self.assertEqual(output.read_bytes(), b"must survive")

    def test_class_wasm_at_resource_tampering(self):
        p = self.plan()
        names = [merger.WASM, merger.AT, "pack.mcmeta"]
        names += [next(x for x in self.core if x.endswith(".class")), next(x for x in self.home if x.endswith(".class"))]
        for index, name in enumerate(names):
            with self.subTest(name=name):
                altered = dict(p.entries)
                altered[name] += b"changed"
                output = self.folder / f"mutated-{index}.jar"
                archive(output, altered)
                with self.assertRaisesRegex(ValueError, "Delivery bytes changed"):
                    merger.verify(output, p)

    def test_missing_or_extra_entry(self):
        p = self.plan()
        for add in (False, True):
            bad = dict(p.entries)
            if add:
                bad["unknown.txt"] = b"bad"
            else:
                del bad[merger.WASM]
            path = self.folder / f"entry-{add}.jar"
            archive(path, bad)
            with self.assertRaisesRegex(ValueError, "entry set"):
                merger.verify(path, p)

    def test_duplicate_class_owner(self):
        self.home[next(x for x in self.core if x.endswith(".class"))] = b"duplicate"
        archive(self.input, self.home)
        with self.assertRaisesRegex(ValueError, "class owner"):
            self.plan()

    def test_native_runtime_or_platform_classes(self):
        for name in ("cn/piq/retro/Input.class", "io/github/kawamuray/wasmtime/Engine.class", "assets/piq_sfc_home/core/x.dll", "assets/piq_sfc_home/core/x.wasm"):
            with self.subTest(name=name):
                bad = dict(self.home)
                bad[name] = b"runtime"
                archive(self.input, bad)
                with self.assertRaises(ValueError):
                    self.plan()

    def test_metadata_mutations(self):
        changes = ((b'javafml', b'unknown'), (b'GPL-3.0-or-later', b'unknown'),
                   (b'piq_sfc_home', b'wrong_mod_id'), (b'0.31.0-alpha.19', b'0.31.0-alpha.1'),
                   (b'type="required"', b'type="optional"'), (b'side="BOTH"', b'side="CLIENT"'))
        for before, after in changes:
            with self.subTest(before=before):
                bad = dict(self.home)
                bad[merger.META] = bad[merger.META].replace(before, after)
                archive(self.input, bad)
                with self.assertRaises(ValueError):
                    self.plan()

    def test_independent_platform_dependency(self):
        self.home[merger.META] += b'\n[[dependencies.piq_sfc_home]]\nmodId="piq_retro_platform"\ntype="required"\nversionRange="[0.1,)"\nordering="AFTER"\nside="BOTH"\n'
        archive(self.input, self.home)
        with self.assertRaisesRegex(ValueError, "Expected only FC"):
            self.plan()

    def test_unknown_metadata_field_not_discarded(self):
        self.home[merger.META] = b'unknown="must not discard"\n' + self.home[merger.META]
        archive(self.input, self.home)
        with self.assertRaisesRegex(ValueError, "metadata structure"):
            self.plan()

    def test_shared_resource_even_identical(self):
        self.home["pack.mcmeta"] = self.core["pack.mcmeta"]
        archive(self.input, self.home)
        with self.assertRaises(ValueError):
            self.plan()

    def test_rom_program_and_foreign_resources(self):
        for name in ("assets/piq_sfc_home/roms/game.sfc", "assets/piq_sfc_home/roms/neogeo.zip", "assets/piq_fc_arcade/duplicate.json", "assets/piq_sfc_home/x.exe"):
            with self.subTest(name=name):
                bad = dict(self.home)
                bad[name] = b"not a real ROM"
                archive(self.input, bad)
                with self.assertRaises(ValueError):
                    self.plan()

    def test_zip_unsafe_paths(self):
        for name in ("../escape.txt", "/abs", "C:/drive", "a\\escape", "a//b"):
            with self.subTest(name=name):
                bad = dict(self.home)
                bad[name] = b"unsafe"
                archive(self.input, bad)
                with self.assertRaisesRegex(ValueError, "Unsafe ZIP path"):
                    self.plan()

    def test_duplicate_zip_entry(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", UserWarning)
            with zipfile.ZipFile(self.input, "a") as z:
                z.writestr(merger.META, self.home[merger.META])
        with self.assertRaisesRegex(ValueError, "Duplicate ZIP entry"):
            self.plan()

    def test_signature_nested_and_multirelease(self):
        for name in ("META-INF/X.SF", "META-INF/jarjar/x.jar", "META-INF/versions/21/X.class"):
            bad = dict(self.home)
            bad[name] = b"bad"
            archive(self.input, bad)
            with self.assertRaises(ValueError):
                self.plan()

    def test_manifest_version_injection(self):
        with self.assertRaisesRegex(ValueError, "Invalid release version"):
            merger.plan(merger.FROZEN_CORE, self.input, merger.sha(self.input.read_bytes()), "0.1.0\r\nInjected: true")

    def test_frozen_inputs_unchanged(self):
        self.plan()
        self.assertEqual(merger.read_archive(merger.FROZEN_CORE)[0], merger.FROZEN_SHA)
        self.assertEqual(merger.read_archive(HOME5)[0], HOME5_SHA)


if __name__ == "__main__":
    unittest.main()
