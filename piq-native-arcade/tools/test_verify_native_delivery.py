"""Synthetic negative packaging tests plus independently regenerated original PoC chips."""
import copy
import importlib.util
import json
from pathlib import Path
import struct
import tempfile
import unittest
from unittest.mock import patch
import warnings
import zipfile
import verify_native_delivery as q


def pool_class(utf=(), references=(), major=65):
    # Minimal pool fixture only; never executed as Java bytecode.
    values = list(utf)
    for value in references:
        if value not in values:
            values.append(value)
    encoded = [b'\x01' + struct.pack('>H', len(v.encode())) + v.encode() for v in values]
    encoded += [b'\x07' + struct.pack('>H', values.index(v) + 1) for v in references]
    return b'\xca\xfe\xba\xbe' + struct.pack('>HHH', 0, major, len(encoded) + 1) + b''.join(encoded)


class NativeDeliveryTests(unittest.TestCase):
    def frozen_source(self):
        archive=q.ROOT.parent/'制作Mod/03-街机模拟/PIQ原生街机/0.1.0-alpha.1/piq-native-arcade-0.1.0-alpha.1-source-v2.zip'
        self.assertEqual(q.file_sha(archive),'1647EF02747741F5E11F2E1E808E9D653C68711C9AA12E58004BF2CB92C7DE5A')
        return q.checked_zip(archive)

    def setUp(self):
        self.protocol = pool_class(['frameBounds'])
        self.main = {q.PROTOCOL: self.protocol, q.PREFIX + 'bridge/NativeProcessSession.class':
                     pool_class(['cn.piq.nativearcade.bridge.NativeCoreWorker'])}
        self.helper = {q.PROTOCOL: self.protocol, q.WORKER + '.class':
                       pool_class(['main', '([Ljava/lang/String;)V', 'com/sun/jna/Native', 'load'])}
        self.manifest = json.loads((q.ROOT / 'tools/native-diagnostic-original-manifest.json').read_text())

    def test_main_allows_only_string_worker_launch_and_counts_actual_classes(self):
        self.assertEqual(q.validate_main(self.main)['own_java21_classes'], 2)

    def test_main_rejects_typed_worker_link(self):
        self.main[q.PREFIX + 'Oops.class'] = pool_class(references=[q.WORKER])
        with self.assertRaisesRegex(ValueError, 'typed link'):
            q.validate_main(self.main)

    def test_main_rejects_jna_reference(self):
        self.main[q.PREFIX + 'Oops.class'] = pool_class(['Lcom/sun/jna/Pointer;'])
        with self.assertRaisesRegex(ValueError, 'JNA linked'):
            q.validate_main(self.main)

    def test_main_rejects_embedded_worker(self):
        self.main[q.WORKER + '.class'] = pool_class()
        with self.assertRaisesRegex(ValueError, 'worker embedded'):
            q.validate_main(self.main)

    def test_main_rejects_foreign_class(self):
        self.main['org/example/Something.class'] = pool_class()
        with self.assertRaisesRegex(ValueError, 'foreign class'):
            q.validate_main(self.main)

    def test_main_rejects_embedded_runtime(self):
        for name in ('mame_libretro.dll', 'lib/jna.jar', 'invaders.zip', 'helper.exe'):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'embedded runtime'):
                q.validate_main(dict(self.main, **{name: b'bad'}))

    def test_main_rejects_java17(self):
        self.main[q.PROTOCOL] = pool_class(major=61)
        with self.assertRaisesRegex(ValueError, 'not Java 21'):
            q.validate_main(self.main)

    def test_common_rejects_client_link(self):
        self.main[q.PREFIX + 'Oops.class'] = pool_class(['net/minecraft/client/Minecraft'])
        with self.assertRaisesRegex(ValueError, 'common class client'):
            q.validate_main(self.main)

    def test_client_class_may_link_minecraft(self):
        self.main[q.PREFIX + 'client/Good.class'] = pool_class(['net/minecraft/client/Minecraft'])
        self.assertEqual(q.validate_main(self.main)['own_java21_classes'], 3)

    def test_helper_accepts_actual_protocol_bytes_and_native_worker_pool(self):
        self.assertEqual(q.validate_helper(self.main, self.helper)['java21_classes'], 2)

    def test_helper_rejects_any_protocol_byte_drift_even_debug_tables(self):
        self.helper[q.PROTOCOL] += b'\0'
        with self.assertRaisesRegex(ValueError, 'protocol bytes differ'):
            q.validate_helper(self.main, self.helper)

    def test_helper_rejects_missing_entry(self):
        del self.helper[q.WORKER + '.class']
        with self.assertRaisesRegex(ValueError, 'main class missing'):
            q.validate_helper(self.main, self.helper)

    def test_helper_rejects_wrong_main_descriptor(self):
        self.helper[q.WORKER + '.class'] = pool_class(['main', '(I)V', 'com/sun/jna/Native', 'load'])
        with self.assertRaisesRegex(ValueError, 'executable/JNA entry'):
            q.validate_helper(self.main, self.helper)

    def test_class_parser_rejects_nonclass_and_truncation(self):
        for data in (b'no', b'\xca\xfe\xba\xbe', pool_class(['abc'])[:-2]):
            with self.subTest(data=data), self.assertRaises(ValueError):
                q.class_info(data)

    def test_metadata_frozen_alpha1_fc14_dependency(self):
        template = self.frozen_source()['piq-native-arcade/src/main/templates/META-INF/neoforge.mods.toml']
        entries = {'META-INF/neoforge.mods.toml': template.replace(b'${version}', q.VERSION.encode()),
                   'META-INF/MANIFEST.MF': ('Implementation-Version: ' + q.VERSION).encode()}
        self.assertEqual(q.validate_metadata(entries)['dependencies']['piq_fc_arcade'], '[0.31.0-alpha.14,0.32.0)')
        entries['META-INF/neoforge.mods.toml'] = entries['META-INF/neoforge.mods.toml'].replace(b'alpha.14', b'alpha.13')
        with self.assertRaisesRegex(ValueError, 'wrong dependency'):
            q.validate_metadata(entries)

    def test_old_validator_rejects_current_alpha2_metadata(self):
        template=(q.ROOT/'src/main/templates/META-INF/neoforge.mods.toml').read_bytes()
        # Even relabelling alpha.2 as alpha.1 must not permit its changed dependency range.
        entries={'META-INF/neoforge.mods.toml':template.replace(b'${version}',q.VERSION.encode()),
                 'META-INF/MANIFEST.MF':('Implementation-Version: '+q.VERSION).encode()}
        with self.assertRaisesRegex(ValueError,'wrong dependency'):
            q.validate_metadata(entries)

    def test_zip_rejects_duplicates(self):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'bad.zip'
            with warnings.catch_warnings():
                warnings.simplefilter('ignore', UserWarning)
                with zipfile.ZipFile(path, 'w') as archive:
                    archive.writestr('a', b'x'); archive.writestr('a', b'y')
            with self.assertRaisesRegex(ValueError, 'duplicate'):
                q.checked_zip(path)

    def test_zip_rejects_traversal_backslashes_absolute_and_oversize(self):
        with tempfile.TemporaryDirectory() as temp:
            for index, name in enumerate(('../a', 'a\\b', '/a', 'C:a', 'okay')):
                path = Path(temp) / (str(index) + '.zip')
                with zipfile.ZipFile(path, 'w') as archive:
                    entry = zipfile.ZipInfo('fixture')
                    entry.filename = name  # Avoid Windows ZipInfo constructor normalizing the malformed fixture.
                    archive.writestr(entry, b'xx')
                with self.subTest(name=name), self.assertRaises(ValueError):
                    q.checked_zip(path, max_member=1 if name == 'okay' else 10)

    def original_chips(self):
        # Invoke the original PoC's pure generator, not the delivery generator under test.
        source = q.ROOT.parent / 'piq-native-arcade-poc/probe.py'
        self.assertEqual(q.file_sha(source), self.manifest['original_probe_sha256'])
        spec = importlib.util.spec_from_file_location('native_original_probe', source)
        module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / 'invaders.zip'
            self.assertEqual(module.diagnostic_rom(path).upper(), q.RAW_SHA)
            return q.checked_zip(path)

    def test_original_four_chips_manifest_matches_independent_poc_generator(self):
        result = q.validate_diagnostic(self.original_chips(), self.manifest)
        self.assertFalse(result['commercial_game_code_included'])

    def test_diagnostic_rejects_altered_program(self):
        data = self.original_chips(); first = self.manifest['chips'][0]['name']
        data[first] = b'\x00' + data[first][1:]
        with self.assertRaisesRegex(ValueError, 'non-original'):
            q.validate_diagnostic(data, self.manifest)

    def test_diagnostic_rejects_missing_extra_or_renamed_chip(self):
        for mutate in ('missing', 'extra', 'rename'):
            data = self.original_chips()
            if mutate == 'extra': data['commercial.bin'] = b'x'
            else:
                value = data.pop(self.manifest['chips'][0]['name'])
                if mutate == 'rename': data['wrong.h1'] = value
            with self.subTest(mutate=mutate), self.assertRaisesRegex(ValueError, 'exactly four'):
                q.validate_diagnostic(data, self.manifest)

    def test_diagnostic_manifest_cannot_rebaseline_bad_program(self):
        data = self.original_chips(); manifest = copy.deepcopy(self.manifest)
        first = manifest['chips'][0]
        data[first['name']] = b'\x00' * 2048; first['sha256'] = q.sha(data[first['name']])
        with self.assertRaisesRegex(ValueError, 'not original'):
            q.validate_diagnostic(data, manifest)

    def test_runtime_hash_mismatch_fails_before_native_load(self):
        with tempfile.TemporaryDirectory() as temp:
            (Path(temp) / 'mame_libretro.dll').write_bytes(b'not the reviewed native core')
            with self.assertRaisesRegex(ValueError, 'runtime SHA mismatch'):
                q.validate_runtime(temp)

    def test_frozen_source_and_exact_uncropped_corners(self):
        frozen=self.frozen_source()
        client='src/main/java/cn/piq/nativearcade/client/NativeArcadeClient.java'
        with tempfile.TemporaryDirectory(prefix='native-alpha1-frozen-source-') as td:
            fixture=Path(td)
            for name in [*q.SOURCE_PINS,client]:
                target=fixture/name;target.parent.mkdir(parents=True,exist_ok=True)
                target.write_bytes(frozen['piq-native-arcade/'+name])
            with patch.object(q,'ROOT',fixture):
                self.assertTrue(q.source_checks()['uncropped_uv_and_pixel_copy'])
                original=(fixture/client).read_text(encoding='utf-8')
                (fixture/client).write_text(original.replace('{{0,1},{1,1},{1,0},{0,0}}','{{.1f,.9f},{1,1},{1,0},{0,0}}'),encoding='utf-8')
                with self.assertRaisesRegex(ValueError,'reviewed client source'):
                    q.source_checks()
                (fixture/client).write_text(original,encoding='utf-8')
                first=next(iter(q.SOURCE_PINS));(fixture/first).write_bytes(b'altered source')
                with self.assertRaisesRegex(ValueError,'reviewed bridge source drift'):
                    q.source_checks()

    def test_old_validator_rejects_current_alpha2_source(self):
        with self.assertRaisesRegex(ValueError,'reviewed bridge source drift'):
            q.source_checks()

    def protocol_verbose_fixture(self):
        return '''Classfile jar:file:/fixture.jar!/BridgeProtocol.class
  Last modified 2026-09-10; size 100 bytes
  SHA-256 checksum ignored_provenance
public final class cn.piq.nativearcade.bridge.BridgeProtocol
  minor version: 0
  major version: 65
  flags: (0x0031) ACC_PUBLIC, ACC_FINAL, ACC_SUPER
  this_class: #7 // cn/piq/nativearcade/bridge/BridgeProtocol
Constant pool:
 #7 = Class #8
 #8 = Utf8 cn/piq/nativearcade/bridge/BridgeProtocol
{
  public static final int VERSION;
    descriptor: I
    flags: (0x0019) ACC_PUBLIC, ACC_STATIC, ACC_FINAL
    ConstantValue: int 1
  public static void frameBounds(int, int, float, int, int) throws java.io.IOException;
    descriptor: (IIFII)V
    flags: (0x0009) ACC_PUBLIC, ACC_STATIC
    Code:
      stack=4, locals=5, args_size=5
         0: iload_0
         1: invokestatic #11 // Method java/lang/Float.isFinite:(F)Z
         4: ldc #17 // String keep  two spaces and #17
         6: return
      LineNumberTable:
        line 12: 0
      LocalVariableTable:
        Start Length Slot Name Signature
        0 7 0 width I
      StackMapTable: number_of_entries = 0
    Exceptions:
      throws java.io.IOException
}
SourceFile: "BridgeProtocol.java"
'''

    def test_full_protocol_semantics_accepts_debug_and_constant_pool_index_difference(self):
        old = self.protocol_verbose_fixture()
        new = old.replace('  Last modified 2026-09-10; size 100 bytes', '  Last modified today; size 80 bytes')
        new = new.replace('      LocalVariableTable:\n        Start Length Slot Name Signature\n        0 7 0 width I\n', '')
        new = new.replace('line 12: 0', 'line 90: 0').replace('invokestatic #11', 'invokestatic #123')
        self.assertEqual(q.canonical_protocol_verbose(old), q.canonical_protocol_verbose(new))

    def test_full_protocol_semantics_rejects_constant_instruction_signature_and_flags_change(self):
        old = self.protocol_verbose_fixture()
        for before, after in [('ConstantValue: int 1', 'ConstantValue: int 2'),
                              ('0: iload_0', '0: iload_1'), ('(IIFII)V', '(IIIII)V'),
                              ('ACC_PUBLIC, ACC_STATIC', 'ACC_PUBLIC'),
                              ('stack=4, locals=5', 'stack=5, locals=5'),
                              ('keep  two spaces and #17', 'keep two spaces and #17'),
                              ('Float.isFinite:(F)Z', 'Float.isNaN:(F)Z')]:
            with self.subTest(before=before):
                try: changed = q.canonical_protocol_verbose(old.replace(before, after))
                except ValueError: continue
                self.assertNotEqual(q.canonical_protocol_verbose(old), changed)


if __name__ == '__main__':
    unittest.main()
