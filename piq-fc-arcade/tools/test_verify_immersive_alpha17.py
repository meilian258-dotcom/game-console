"""Fail-closed archive fixtures for the alpha17 immersive audit. No game or builds."""
from __future__ import annotations
import io
import struct
import unittest
from unittest.mock import patch
import zipfile
import verify_immersive_alpha17 as audit

CLASS = b"\xca\xfe\xba\xbe\x00\x00\x00Atest-fixture"


def archive(entries):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as output:
        for name, data in entries.items():
            member = zipfile.ZipInfo()
            member.filename = name
            output.writestr(member, data)
    buffer.seek(0)
    return zipfile.ZipFile(buffer)


def metadata(contract, new=False):
    version = contract["version"] if new else contract["old_version"]
    mod = contract["id"]
    text = f'modLoader="javafml"\n[[mods]]\nmodId="{mod}"\nversion="{version}"\n'
    if mod != "piq_fc_arcade":
        minimum = "17" if new else "16"
        text += f'[[dependencies.{mod}]]\nmodId="piq_fc_arcade"\ntype="required"\nversionRange="[0.31.0-alpha.{minimum},0.32.0)"\nordering="AFTER"\nside="BOTH"\n'
    if mod == "piq_sfc_home":
        text += '[[dependencies.piq_sfc_home]]\nmodId="piq_sfc_arcade"\ntype="required"\nversionRange="[0.2.0-alpha.6,0.3.0)"\nordering="AFTER"\nside="BOTH"\n'
    return text.encode()


def manifest(contract, new=False):
    return ("Manifest-Version: 1.0\nImplementation-Title: retained\nImplementation-Version: "
            + contract["version" if new else "old_version"] + "\n\n").encode()


class Alpha17ArchiveTest(unittest.TestCase):
    def fixture(self, key="fc"):
        self.contract = audit.CONTRACTS[key]
        self.before = {
            audit.META: metadata(self.contract), audit.MANIFEST: manifest(self.contract),
            "assets/original/models/block/cabinet.json": b"model",
            "assets/original/textures/cabinet.png": b"png",
            "assets/original/blockstates/cabinet.json": b"blockstate",
            "assets/original/meshes/cabinet.json": b"mesh",
            "assets/original/lang/zh_cn.json": b"translations",
            "core/wasm.wasm": b"fixed-wasm", "natives/core.dll": b"fixed-native",
            "cn/piq/original/core/Worker.class": CLASS,
            "cn/piq/original/server/Sessions.class": CLASS,
            "cn/piq/original/world/Block.class": CLASS,
            "cn/piq/original/client/Input.class": CLASS,
        }
        self.before.update({name: CLASS for name in self.contract["changed"]})
        self.after = dict(self.before)
        self.after.update({name: CLASS for name in self.contract["added"]})
        self.after[audit.META] = metadata(self.contract, True)
        self.after[audit.MANIFEST] = manifest(self.contract, True)

    def validate(self):
        with archive(self.after) as after, archive(self.before) as before:
            return audit.delta(after, before, self.contract)

    def setUp(self):
        self.fixture()

    def test_all_three_explicit_candidate_contracts_pass(self):
        for key in audit.CONTRACTS:
            with self.subTest(key=key):
                self.fixture(key)
                result = self.validate()
                self.assertEqual(sorted(self.contract["added"]), result["added_files"])
                self.assertEqual(5, result["byte_identical_all_assets"])

    def test_each_named_ui_change_is_allowed(self):
        for name in self.contract["changed"]:
            self.after[name] += b"allowed-ui"
        self.validate()

    def test_every_original_entry_must_remain(self):
        for name in self.before:
            saved = self.after.pop(name)
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "removed"):
                self.validate()
            self.after[name] = saved

    def test_every_asset_is_frozen_including_languages(self):
        for name in [n for n in self.before if n.startswith("assets/")]:
            saved = self.after[name]
            self.after[name] += b"changed"
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Unauthorized inherited"):
                self.validate()
            self.after[name] = saved

    def test_core_server_world_input_and_native_bytes_are_frozen(self):
        for name in [n for n in self.before if n.startswith(("cn/piq/original/", "core/", "natives/"))]:
            saved = self.after[name]
            self.after[name] += b"changed"
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "Unauthorized inherited"):
                self.validate()
            self.after[name] = saved

    def test_arbitrary_new_classes_fail_even_in_approved_directory(self):
        for name in (audit.FC + "client/rom/Unexpected.class", "tools/Probe.class", "helper.jar", "assets/new.json"):
            self.after[name] = CLASS
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, "explicit whitelist"):
                self.validate()
            del self.after[name]

    def test_missing_expected_new_class_fails(self):
        self.after.pop(next(iter(self.contract["added"])))
        with self.assertRaisesRegex(ValueError, "explicit whitelist"):
            self.validate()

    def test_only_empty_structural_new_directories_pass(self):
        self.after[audit.FC + "client/cabinet/"] = b""
        self.validate()
        self.after[audit.FC + "client/cabinet/"] = b"hidden-data"
        with self.assertRaisesRegex(ValueError, "structural"):
            self.validate()

    def test_unrelated_new_directory_fails(self):
        self.after["unrelated/"] = b""
        with self.assertRaisesRegex(ValueError, "structural"):
            self.validate()

    def test_bad_class_header_and_wrong_java_level_fail(self):
        name = next(iter(self.contract["added"]))
        for value in (b"notaclass", b"\xca\xfe\xba\xbe\x00\x00\x00=oldjava"):
            self.after[name] = value
            with self.assertRaisesRegex(ValueError, "Java 21"):
                self.validate()

    def test_manifest_cannot_change_title_or_add_entrypoint(self):
        for value in (manifest(self.contract, True).replace(b"retained", b"changed"),
                      manifest(self.contract, True) + b"Main-Class: malicious\n"):
            self.after[audit.MANIFEST] = value
            with self.assertRaisesRegex(ValueError, "manifest fields"):
                self.validate()

    def test_manifest_line_endings_do_not_change_semantics(self):
        self.after[audit.MANIFEST] = manifest(self.contract, True).replace(b"\n", b"\r\r\n")
        self.validate()

    def test_modid_modcount_and_version_are_pinned(self):
        correct = metadata(self.contract, True)
        for value in (correct.replace(b"piq_fc_arcade", b"different"),
                      correct.replace(b"alpha.17", b"alpha.18"), correct + b'[[mods]]\nmodId="injected"\n'):
            self.after[audit.META] = value
            with self.assertRaises(ValueError):
                self.validate()

    def test_unrelated_metadata_field_is_frozen(self):
        self.after[audit.META] = metadata(self.contract, True).replace(b'javafml', b'otherloader')
        with self.assertRaisesRegex(ValueError, "Unauthorized metadata"):
            self.validate()

    def test_addons_require_fc17_both_sides(self):
        for key in ("sfc", "native"):
            self.fixture(key)
            correct = metadata(self.contract, True)
            for value in (correct.replace(b"alpha.17", b"alpha.16"), correct.replace(b'BOTH', b'CLIENT', 1),
                          correct.replace(b'required', b'optional', 1)):
                self.after[audit.META] = value
                with self.assertRaisesRegex(ValueError, "FC alpha17"):
                    self.validate()

    def test_sfc_old_core_dependency_cannot_change(self):
        self.fixture("sfc")
        self.after[audit.META] = metadata(self.contract, True).replace(b'0.2.0-alpha.6', b'0.2.0-alpha.1')
        with self.assertRaisesRegex(ValueError, "old SFC core"):
            self.validate()

    def test_sfc_common_classes_cannot_link_client_code(self):
        self.fixture("sfc")
        name=audit.SFC+"server/ReviewFixture.class"
        self.contract=dict(self.contract,changed=self.contract["changed"]|{name})
        self.before[name]=CLASS
        for link in (b"net/minecraft/client/",b"cn/piq/sfchome/client/",b"cn/piq/fcarcade/client/",
                     b"com/mojang/blaze3d/",b"neoforge/client/"):
            self.after[name]=CLASS+link
            with self.subTest(link=link), self.assertRaisesRegex(ValueError,"common class links client"):
                self.validate()

    def test_unfrozen_sfc_contract_blocks_full_audit_before_any_file_access(self):
        with patch.object(audit,"SFC_CONTRACT_FROZEN",False), self.assertRaisesRegex(ValueError,"not frozen"):
            audit.inspect({}, {}, None)

    def test_unsafe_member_names_fail_before_delta(self):
        for name in ("../escape", "dir/../escape", "C:/escape", "/escape", "dir\\escape"):
            with archive({name: b""}) as test, self.assertRaisesRegex(ValueError, "Unsafe"):
                audit.unique(test)

    def test_duplicate_member_fails_before_delta(self):
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as target:
            target.writestr("duplicate", b"one")
            with self.assertWarns(UserWarning):
                target.writestr("duplicate", b"two")
        buffer.seek(0)
        with zipfile.ZipFile(buffer) as test, self.assertRaisesRegex(ValueError, "Duplicate"):
            audit.unique(test)


class Alpha17DebugOnlyTest(unittest.TestCase):
    @staticmethod
    def fixture(line=1, opcode=0xB1, stack=1, access=1, name="X"):
        u2 = lambda value: struct.pack(">H", value)
        u4 = lambda value: struct.pack(">I", value)
        utf = lambda value: b"\x01" + u2(len(value.encode())) + value.encode()
        pool = [utf(name), b"\x07"+u2(1), utf("java/lang/Object"), b"\x07"+u2(3),
                utf("<init>"),utf("()V"),utf("Code"),utf("LineNumberTable")]
        attr = lambda index, body: u2(index)+u4(len(body))+body
        code=u2(stack)+u2(1)+u4(1)+bytes([opcode])+u2(0)+u2(1)+attr(8,u2(1)+u2(0)+u2(line))
        return b"\xca\xfe\xba\xbe\x00\x00\x00A"+u2(9)+b"".join(pool)+u2(0x21)+u2(2)+u2(4)+u2(0)+u2(0)+u2(1)+u2(access)+u2(5)+u2(6)+u2(1)+attr(7,code)+u2(0)

    def test_only_line_numbers_can_change(self):
        self.assertEqual(audit.without_code_debug(self.fixture()),audit.without_code_debug(self.fixture(line=65535)))

    def test_pool_opcodes_stack_and_access_remain_protected(self):
        for options in ({"opcode":0},{"stack":2},{"access":9},{"name":"Y"}):
            self.assertNotEqual(audit.without_code_debug(self.fixture()),audit.without_code_debug(self.fixture(**options)))

    def test_malformed_and_trailing_class_bytes_fail(self):
        for raw in (self.fixture()[:9],self.fixture()[:-1],self.fixture()+b"extra"):
            with self.assertRaises(ValueError): audit.without_code_debug(raw)

    def test_native_setup_nested_class_cannot_change_opcodes(self):
        fixture=Alpha17ArchiveTest();fixture.fixture("native")
        name=audit.NATIVE+"client/NativeArcadeClient$Setup.class"
        fixture.before[name]=self.fixture();fixture.after[name]=self.fixture(line=65535)
        self.assertEqual([name],fixture.validate()["debug_only_identical_pool_opcodes_and_other_attributes"])
        fixture.after[name]=self.fixture(line=65535,opcode=0)
        with self.assertRaisesRegex(ValueError,"Execution body"):
            fixture.validate()


class Alpha17BackendMethodTest(unittest.TestCase):
    def test_method_comparison_only_normalizes_pool_labels_and_comment_alignment(self):
        before='class X {\n  public void open();\n    Code:\n       0: invokestatic #2       // Method Safe.open:()V\n       3: return\n}\n'
        after=before.replace('#2       //','#123  //')
        self.assertEqual(audit.methods(before),audit.methods(after))
        for tampered in (after.replace('Safe.open','Unsafe.open'),after.replace('3: return','3: nop'),
                         after.replace('0: invokestatic','0: invokevirtual')):
            self.assertNotEqual(audit.methods(before),audit.methods(tampered))

    def test_abstract_and_concrete_methods_are_kept_separately(self):
        code='interface X {\n  public abstract void open();\n\n  public default java.lang.String label();\n    Code:\n       0: ldc #2 // String original text\n       2: areturn\n}\n'
        methods=audit.methods(code)
        self.assertEqual(2,len(methods))
        self.assertEqual('',methods['public abstract void open();'])
        self.assertIn('original text',methods['public default java.lang.String label();'])


if __name__ == "__main__":
    unittest.main()
