"""Negative fixtures for the independent alpha15 archive contract; no production builds."""
from __future__ import annotations
import io
import json
import struct
from pathlib import Path
import tempfile
import unittest
import zipfile
import verify_unified_cabinet_alpha15 as audit

CLASS21 = b"\xca\xfe\xba\xbe\x00\x00\x00Afixture"


def archive(files: dict[str, bytes]) -> zipfile.ZipFile:
    data = io.BytesIO()
    with zipfile.ZipFile(data, "w") as target:
        for name, content in files.items():
            target.writestr(name, content)
    data.seek(0)
    return zipfile.ZipFile(data)


class Alpha15ArchiveContractTest(unittest.TestCase):
    def setUp(self):
        self.before = {
            "assets/piq_fc_arcade/models/block/legacy.json": b'{"elements":[]}',
            "assets/piq_fc_arcade/textures/block/legacy.png": b"original-png",
            "assets/piq_fc_arcade/blockstates/legacy.json": b"{}",
            "assets/piq_fc_arcade/meshes/legacy.json": b"{}",
            "cn/piq/fcarcade/core/NesCore.class": CLASS21,
            "cn/piq/fcarcade/client/ClientNesWorker.class": CLASS21,
            "core/nes_rust_wasm_bg.wasm": b"\0asmfixed-runtime",
            "natives/windows-x86_64/wasmtime4j.dll": b"fixed-native",
            "META-INF/neoforge.mods.toml": b"old-metadata",
            "META-INF/MANIFEST.MF": b"old-manifest",
            "cn/piq/fcarcade/home/AvCableItem.class": CLASS21,
        }
        for name in audit.LANG_LABELS:
            self.before[name] = json.dumps({"itemGroup.piq_fc_arcade": "PIQ FC", "other": "unchanged"}).encode()
        self.after = dict(self.before)
        self.after.update({name: CLASS21 for name in audit.ADDED_CLASSES})
        for name, label in audit.LANG_LABELS.items():
            self.after[name] = json.dumps({"itemGroup.piq_fc_arcade": label, "other": "unchanged"}).encode()
        self.after["cn/piq/fcarcade/home/AvCableItem.class"] += b"permitted-cable-fix"

    def delta(self):
        with archive(self.after) as new, archive(self.before) as old:
            return audit.validate_delta(new, old)

    def test_exact_new_classes_and_scoped_old_change_pass(self):
        result = self.delta()
        self.assertEqual(36, len(result["added_classes"]))
        self.assertEqual(19, result["new_common_no_client_link_classes"])

    def test_removing_any_old_entry_fails(self):
        del self.after["cn/piq/fcarcade/core/NesCore.class"]
        with self.assertRaisesRegex(ValueError, "removed"):
            self.delta()

    def test_models_textures_blockstates_meshes_are_all_byte_frozen(self):
        for path in [p for p in self.before if p.startswith("assets/") and "/lang/" not in p]:
            with self.subTest(path=path):
                previous = self.after[path]; self.after[path] = previous + b"changed"
                with self.assertRaisesRegex(ValueError, "Unauthorized"):
                    self.delta()
                self.after[path] = previous

    def test_unapproved_core_and_input_bytecode_fails(self):
        for path in ("cn/piq/fcarcade/core/NesCore.class", "cn/piq/fcarcade/client/ClientNesWorker.class"):
            with self.subTest(path=path):
                previous = self.after[path]; self.after[path] += b"changed"
                with self.assertRaisesRegex(ValueError, "Unauthorized"):
                    self.delta()
                self.after[path] = previous

    def test_runtime_replacement_fails(self):
        self.after["core/nes_rust_wasm_bg.wasm"] += b"changed"
        with self.assertRaisesRegex(ValueError, "Unauthorized"):
            self.delta()

    def test_unlisted_new_class_fails_even_inside_new_package(self):
        self.after["cn/piq/fcarcade/cabinet/Unexpected.class"] = CLASS21
        with self.assertRaisesRegex(ValueError, "36-class"):
            self.delta()

    def test_missing_new_class_fails(self):
        del self.after["cn/piq/fcarcade/cabinet/CabinetNetwork.class"]
        with self.assertRaisesRegex(ValueError, "36-class"):
            self.delta()

    def test_source_test_and_helper_packaging_fails(self):
        for path in ("cn/piq/fcarcade/cabinet/FakeTest.class", "tools/Alpha15CabinetProbe.class", "helper.jar"):
            with self.subTest(path=path):
                self.after[path] = CLASS21
                with self.assertRaisesRegex(ValueError, "36-class"):
                    self.delta()
                del self.after[path]

    def test_new_common_client_and_native_links_fail(self):
        path = "cn/piq/fcarcade/cabinet/CabinetFrame.class"
        for forbidden in audit.FORBIDDEN_COMMON:
            with self.subTest(forbidden=forbidden):
                self.after[path] = CLASS21 + forbidden
                with self.assertRaisesRegex(ValueError, "links client/core"):
                    self.delta()
        self.after[path] = CLASS21

    def test_java_version_and_invalid_header_fail(self):
        path = "cn/piq/fcarcade/cabinet/CabinetFrame.class"
        for raw in (b"not-a-class", b"\xca\xfe\xba\xbe\x00\x00\x00="):
            self.after[path] = raw
            with self.assertRaisesRegex(ValueError, "Java 21"):
                self.delta()

    def test_language_changes_beyond_exact_tab_label_fail(self):
        path = next(iter(audit.LANG_LABELS))
        self.after[path] = json.dumps({"itemGroup.piq_fc_arcade": audit.LANG_LABELS[path], "other": "changed"}).encode()
        with self.assertRaisesRegex(ValueError, "Only the unified"):
            self.delta()

    def test_unexpected_new_resource_and_directory_fail(self):
        for path in ("assets/piq_fc_arcade/models/new.json", "unrelated/"):
            self.after[path] = b"{}"
            with self.assertRaises(ValueError):
                self.delta()
            del self.after[path]

    def test_only_structural_new_directories_are_allowed(self):
        self.after["cn/piq/fcarcade/cabinet/"] = b""
        self.assertEqual(["cn/piq/fcarcade/cabinet/"], self.delta()["added_directories"])

    def test_duplicate_zip_entries_fail(self):
        data = io.BytesIO()
        with zipfile.ZipFile(data, "w") as target:
            target.writestr("duplicate", b"a")
            with self.assertWarns(UserWarning):
                target.writestr("duplicate", b"b")
        data.seek(0)
        with zipfile.ZipFile(data) as target, self.assertRaisesRegex(ValueError, "Duplicate"):
            audit.unique(target)


class Alpha15DebugComparisonTest(unittest.TestCase):
    @staticmethod
    def fixture(line=1, opcode=0xB1, stack=1, access=1, name="X", source_index=12, local_start=0):
        u2 = lambda value: struct.pack(">H", value)
        u4 = lambda value: struct.pack(">I", value)
        utf = lambda text: b"\x01" + u2(len(text.encode())) + text.encode()
        pool = [utf(name), b"\x07" + u2(1), utf("java/lang/Object"), b"\x07" + u2(3),
                utf("<init>"), utf("()V"), utf("Code"), utf("LineNumberTable"), utf("LocalVariableTable"),
                utf("LocalVariableTypeTable"), utf("SourceFile"), utf("X.java"), utf("Signature"), utf("Ljava/lang/Object;")]
        attr = lambda index, data: u2(index) + u4(len(data)) + data
        debug = attr(8, u2(1) + u2(0) + u2(line))
        debug += attr(9, u2(1) + u2(local_start) + u2(1) + u2(1) + u2(14) + u2(0))
        debug += attr(10, u2(1) + u2(local_start) + u2(1) + u2(1) + u2(14) + u2(0))
        code = u2(stack) + u2(1) + u4(1) + bytes([opcode]) + u2(0) + u2(3) + debug
        header = b"\xca\xfe\xba\xbe\x00\x00\x00A" + u2(15) + b"".join(pool)
        return header + u2(0x21) + u2(2) + u2(4) + u2(0) + u2(0) + u2(1) + u2(access) + u2(5) + u2(6) + u2(1) + attr(7, code) + u2(1) + attr(11, u2(source_index))

    def test_only_code_debug_line_and_variable_tables_are_ignored(self):
        before = self.fixture(line=1, local_start=0)
        after = self.fixture(line=65535, local_start=1)
        self.assertNotEqual(before, after)
        self.assertEqual(audit.without_code_debug(before), audit.without_code_debug(after))

    def test_opcode_change_is_never_ignored(self):
        self.assertNotEqual(audit.without_code_debug(self.fixture()), audit.without_code_debug(self.fixture(opcode=0)))

    def test_stack_limit_access_flags_pool_and_other_attributes_are_preserved(self):
        for keyword in ({"stack": 2}, {"access": 9}, {"name": "Y"}, {"source_index": 1}):
            with self.subTest(keyword=keyword):
                self.assertNotEqual(audit.without_code_debug(self.fixture()), audit.without_code_debug(self.fixture(**keyword)))

    def test_truncated_class_and_trailing_bytes_fail(self):
        raw = self.fixture()
        for broken in (raw[:9], raw[:-1], raw + b"trailing"):
            with self.assertRaises(ValueError):
                audit.without_code_debug(broken)

    def test_known_companion_cannot_sneak_an_opcode_change(self):
        fixture = Alpha15ArchiveContractTest(); fixture.setUp()
        path = "cn/piq/fcarcade/world/DualCabinetStructure$Owner.class"
        fixture.before[path] = self.fixture()
        fixture.after[path] = self.fixture(line=500)
        self.assertIn(path, fixture.delta()["debug_only_identical_pool_opcodes_and_nondebug_attributes"])
        fixture.after[path] = self.fixture(line=500, opcode=0)
        with self.assertRaisesRegex(ValueError, "Implementation changed"):
            fixture.delta()


class Alpha15AddonMetadataTest(unittest.TestCase):
    def metadata(self, mod="piq_native_arcade", minimum="0.31.0-alpha.15", side="BOTH", version="0.1.0-alpha.2"):
        result = f'''[[mods]]
modId="{mod}"
version="{version}"
[[dependencies.{mod}]]
modId="piq_fc_arcade"
type="required"
versionRange="[{minimum},0.32.0)"
ordering="AFTER"
side="{side}"
'''
        if mod == "piq_sfc_home":
            result += '''[[dependencies.piq_sfc_home]]
modId="piq_sfc_arcade"
type="required"
versionRange="[0.2.0-alpha.6,0.3.0)"
side="BOTH"
'''
        return result

    def addon(self, text, mod="piq_native_arcade"):
        with tempfile.TemporaryDirectory(prefix="piq-alpha15-metadata-test-") as temp:
            jar = Path(temp) / "addon.jar"
            with zipfile.ZipFile(jar, "w") as archive:
                archive.writestr("META-INF/neoforge.mods.toml", text)
            return audit.addon(jar, mod)

    def test_both_alpha2_addons_require_alpha15(self):
        for mod in ("piq_native_arcade", "piq_sfc_home"):
            with self.subTest(mod=mod):
                self.assertEqual("0.1.0-alpha.2", self.addon(self.metadata(mod=mod), mod)["version"])

    def test_alpha14_lower_bound_and_client_only_dependency_fail(self):
        for text in (self.metadata(minimum="0.31.0-alpha.14"), self.metadata(side="CLIENT")):
            with self.assertRaisesRegex(ValueError, "FC alpha15"):
                self.addon(text)

    def test_wrong_addon_version_fails(self):
        with self.assertRaisesRegex(ValueError, "id/version"):
            self.addon(self.metadata(version="0.1.0-alpha.1"))

    def test_sfc_core_required_dependency_cannot_be_removed(self):
        text = self.metadata(mod="piq_sfc_home").replace('modId="piq_sfc_arcade"', 'modId="unrelated"')
        with self.assertRaisesRegex(ValueError, "old core"):
            self.addon(text, "piq_sfc_home")


if __name__ == "__main__":
    unittest.main()
