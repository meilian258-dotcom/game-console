"""Pure packaging guards only: synthetic data; never invoke build_sync39.main/Gradle/javac."""
import copy
import io
import unittest
import zipfile
import build_sync39 as b


class Sync39Guards(unittest.TestCase):
    def minimal(self):
        return {b.META: b'modLoader="javafml"\n', b.MANIFEST: b'Manifest-Version: 1.0\r\n\r\n'}

    def test_requested_version_set_has_five_ids_in_four_jars(self):
        self.assertEqual(4, len(b.NAMES))
        self.assertEqual(5, len(b.VERSIONS))
        self.assertEqual('piq_sfc-0.1.0-alpha.23.jar', b.NAMES['sfc'])
        self.assertEqual('0.2.0-alpha.7', b.VERSIONS['piq_sfc_arcade'])
        self.assertEqual(10, sum(map(len, b.LANG.values())))

    def test_archive_roundtrip_preserves_all_resource_bytes(self):
        entries = dict(self.minimal(), **{'assets/a/texture.png': b'fixture-png', 'core/a.wasm': b'fixture-core', 'META-INF/LICENSE': b'fixture-license'})
        self.assertEqual(entries, b.archive(b.jar_bytes(entries)))
        self.assertEqual(b.jar_bytes(entries), b.jar_bytes(entries))

    def test_duplicate_entries_are_rejected(self):
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, 'w') as jar:
            for name, payload in self.minimal().items():
                jar.writestr(name, payload)
            jar.writestr('same', b'a')
            with self.assertWarns(UserWarning):
                jar.writestr('same', b'b')
        with self.assertRaisesRegex(ValueError, 'Duplicate'):
            b.archive(raw.getvalue())

    def test_traversal_is_rejected(self):
        for name in ('../outside', 'assets/../outside', '/absolute', 'C:/outside', 'a\\b'):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'Unsafe'):
                b.archive(b.jar_bytes(dict(self.minimal(), **{name: b'x'})))

    def test_signed_archive_is_not_repacked(self):
        with self.assertRaisesRegex(ValueError, 'Signed'):
            b.archive(b.jar_bytes(dict(self.minimal(), **{'META-INF/A.RSA': b'x'})))

    def test_only_owned_classes_are_replaceable(self):
        self.assertTrue(b.own_class('fc', 'cn/piq/retro/client/KeyboardInput.class'))
        self.assertTrue(b.own_class('sfc', 'cn/piq/sfcarcade/core/Core$Inner.class'))
        self.assertFalse(b.own_class('fc', 'ai/tegmentum/wasmtime/Runtime.class'))
        self.assertFalse(b.own_class('native', 'cn/piq/retro/client/KeyboardInput.class'))
        self.assertFalse(b.own_class('gba', 'assets/piq_gba/logo.png'))

    def test_known_protected_difference_is_reported_but_never_substituted(self):
        name = 'assets/piq_fc_arcade/textures/old.png'
        old, compiled = {name: b'approved38'}, {name: b'old-source'}
        rows = b.resource_differences('fc', old, compiled, {})
        self.assertEqual('preserve_frozen_baseline', rows[0]['action'])
        self.assertFalse(rows[0]['blocked'])
        self.assertEqual(b.sha(b'approved38'), rows[0]['baseline_sha256'])
        self.assertEqual(b'approved38', old[name])

    def test_new_non_authorized_resource_blocks(self):
        rows = b.resource_differences('fc', {}, {'assets/piq_fc_arcade/new.png': b'x'}, {})
        self.assertTrue(rows[0]['blocked'])

    def test_omitted_historical_source_resource_stays_omitted(self):
        name = 'assets/piq_fc_arcade/unused.png'
        source = 'piq-fc-arcade/src/main/resources/' + name
        rows = b.resource_differences('fc', {}, {name: b'x'}, {source: b.sha(b'x')})
        self.assertFalse(rows[0]['blocked'])
        self.assertEqual('omit_historical_source_only_entry', rows[0]['action'])

    def test_source_png_drift_blocks_but_exact_lang_and_metadata_are_authorized(self):
        png = 'piq-gba/src/main/resources/assets/piq_gba/handheld.png'
        lang = next(iter(b.LANG['gba'].values()))
        meta = 'piq-gba/src/main/resources/' + b.META
        self.assertEqual([{'source': png, 'release38_source_sha256': 'OLD', 'current_sha256': 'NEW'}],
                         b.protected_source_drift({png: 'OLD', lang: 'OLD', meta: 'OLD'}, {png: 'NEW', lang: 'NEW', meta: 'NEW'}))

    def test_runtime_and_license_source_drift_are_not_language_permissions(self):
        for suffix in ('core/game.wasm', 'META-INF/LICENSE', 'piq-gba-runtime.properties'):
            path = 'piq-gba/src/main/resources/' + suffix
            self.assertTrue(b.protected_source_drift({path: 'OLD'}, {path: 'NEW'}))

    def test_duplicate_language_key_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Duplicate language'):
            b.language(b'{"a":"one","a":"two"}', 'fixture')

    def test_nested_runtime_is_rejected(self):
        with self.assertRaisesRegex(ValueError, 'Nested'):
            b.identity_graph({'fc': {'META-INF/jarjar/core.jar': b'x'}})

    def test_rom_and_bios_payload_suffixes_are_rejected(self):
        for name in ('game.nes', 'game.gba', 'neogeo.zip', 'bios/firmware', 'game.rom'):
            with self.subTest(name=name), self.assertRaisesRegex(ValueError, 'ROM/BIOS'):
                b.identity_graph({'fc': {name: b'not-a-real-game'}})

    def test_manifest_updates_only_one_exact_version_attribute(self):
        old = {b.META: b'[[mods]]\nmodId="piq_gba"\nversion="0.1.0-alpha.6"\n',
               b.MANIFEST: b'Manifest-Version: 1.0\r\nImplementation-Version: 0.1.0-alpha.6\r\nImplementation-Vendor: PIQ\r\n\r\n'}
        self.assertEqual(old[b.MANIFEST].replace(b'alpha.6', b'alpha.7'), b.version_manifest('gba', old))
        broken = copy.deepcopy(old); broken[b.MANIFEST] += b'Implementation-Version: 0.1.0-alpha.6\r\n'
        with self.assertRaisesRegex(ValueError, 'Ambiguous'):
            b.version_manifest('gba', broken)


if __name__ == '__main__':
    unittest.main()
