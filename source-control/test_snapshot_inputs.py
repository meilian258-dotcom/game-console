import hashlib
import io
import struct
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import zipfile

import snapshot_inputs as subject


class InputTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    @staticmethod
    def pe():
        data = bytearray(128)
        data[:2] = b'MZ'
        struct.pack_into('<I', data, 60, 64)
        data[64:68] = b'PE\0\0'
        struct.pack_into('<H', data, 68, 0x8664)
        struct.pack_into('<H', data, 86, 0x2000)
        return bytes(data)

    def make_zip(self, entries):
        path = self.root/'input.zip'
        with zipfile.ZipFile(path, 'w') as z:
            for name, data in entries:
                if isinstance(name, str):
                    entry = zipfile.ZipInfo('placeholder')
                    entry.filename = name  # preserve hostile backslashes on Windows
                else:
                    entry = name
                z.writestr(entry, data)
        return path

    def test_official_directories_and_no_custom_core_download(self):
        self.assertEqual(set(subject.NIGHTLY_BASE), {'windows-x64', 'linux-x64'})
        self.assertEqual(len(subject.NIGHTLY), 4)
        self.assertNotIn('fceumm-retroarch-pad', subject.NIGHTLY)
        self.assertEqual(subject.FC_LOCAL_INPUTS, {'fceumm-retroarch-pad'})
        self.assertNotIn('mesen-s-windows', subject.NIGHTLY)
        self.assertFalse(any('snes9x' in member for _, member in subject.NIGHTLY.values()))
        self.assertFalse(any('netplay' in member or 'blastem' in member for _, member in subject.NIGHTLY.values()))
        self.assertTrue(set(subject.JNI_BUNDLE_INPUTS).isdisjoint(subject.NIGHTLY))
        self.assertFalse(any('libc++' in member or 'libcxx' in member or 'libretro-jni' in member
                             for _, member in subject.NIGHTLY.values()))
        self.assertFalse(any('mame' in member for _, member in subject.NIGHTLY.values()))

    def test_mame_scope_is_an_exact_pair_shared_with_build_validation(self):
        import snapshot_build
        expected = ('core/mame-jni/windows-x64/mame_piq_lifecycle1.dll',
                    'META-INF/piq-native/mame-lifecycle/runtime.json')
        self.assertEqual(expected, subject.MAME_LIFECYCLE_RESOURCES)
        self.assertEqual(subject.BUCKETS['arcade'][2], snapshot_build.RESOURCE_SCOPES['arcade'])
        self.assertEqual(expected, subject.BUCKETS['arcade'][2][-2:])

    def extract_arcade_fixture(self, entries, validator):
        fc = self.root / 'fc.jar'
        with zipfile.ZipFile(fc, 'w'):
            pass
        arcade = self.make_zip(list(entries.items()))
        with patch.object(subject, 'load_lock', return_value={}), \
                patch.object(subject, 'require_published_jni_bundle'), \
                patch.dict(subject.FC_RESOURCES, {}, clear=True), \
                patch.dict(subject.BUCKETS, {'arcade': subject.BUCKETS['arcade']}, clear=True), \
                patch.object(subject, 'verify_mame_lifecycle_resources', validator):
            subject.extract_bootstrap({'fc': fc, 'arcade': arcade}, self.root / 'out', self.root / 'source')

    def test_mame_extraction_keeps_both_files_and_invokes_fixed_validator(self):
        from unittest.mock import Mock
        entries = dict(zip(subject.MAME_LIFECYCLE_RESOURCES, (b'core fixture', b'notice fixture')))
        validator = Mock()
        self.extract_arcade_fixture(entries, validator)
        for name, data in entries.items():
            self.assertEqual(data, (self.root / 'out/resources/arcade' / name).read_bytes())
        validator.assert_called_once_with(self.root / 'out/resources/arcade', self.root / 'source')

    def test_bootstrap_stock_is_excluded_so_current_task_is_single_packaging_owner(self):
        from unittest.mock import Mock
        entries = dict(zip(subject.MAME_LIFECYCLE_RESOURCES, (b'core fixture', b'notice fixture')))
        stock = {prefix + 'old-stock-input': b'must not be bootstrapped'
                 for prefix in subject.snapshot_stock.GENERATED_PREFIXES}
        self.extract_arcade_fixture({**entries, **stock}, Mock())
        for name in stock:
            self.assertFalse((self.root / 'out/resources/arcade' / name).exists())
        for name, data in entries.items():
            self.assertEqual(data, (self.root / 'out/resources/arcade' / name).read_bytes())

    def test_mame_extraction_rejects_missing_pair_even_with_legacy_payload(self):
        from unittest.mock import Mock
        for omitted in ((subject.MAME_LIFECYCLE_RESOURCES[0],),
                        (subject.MAME_LIFECYCLE_RESOURCES[1],), subject.MAME_LIFECYCLE_RESOURCES):
            entries = {name: b'fixture' for name in subject.MAME_LIFECYCLE_RESOURCES if name not in omitted}
            entries['native-runtime/legacy.bin'] = b'legacy is not the new core'
            validator = Mock()
            with self.subTest(omitted=omitted), self.assertRaisesRegex(ValueError, 'core/notice pair'):
                self.extract_arcade_fixture(entries, validator)
            validator.assert_not_called()

    def test_mame_extraction_cannot_ignore_fixed_identity_failure(self):
        from unittest.mock import Mock
        validator = Mock(side_effect=ValueError('fixed identity mismatch'))
        entries = dict.fromkeys(subject.MAME_LIFECYCLE_RESOURCES, b'fixture')
        with self.assertRaisesRegex(ValueError, 'fixed identity mismatch'):
            self.extract_arcade_fixture(entries, validator)

    def test_fc_mapping_includes_cache_to_packaged_runtime_rename(self):
        lock = subject.load_lock()
        self.assertEqual(set(lock), set(subject.FC_RESOURCES))
        self.assertEqual(lock['libcxx-windows']['filename'], 'libcxx.dll')
        self.assertEqual(subject.FC_RESOURCES['libcxx-windows'],
                         'core/libretro-jni/windows-x64/libc++.dll')

    def test_fixed_fc_core_requires_exact_import_without_download_or_legacy_fallback(self):
        data=self.pe()
        entry=dict(filename='fceumm_libretro.dll',bytes=len(data),sha256=hashlib.sha256(data).hexdigest())
        lock={'fceumm-retroarch-pad':entry}
        with patch.object(subject,'fetch',side_effect=AssertionError('must not fetch nightly')):
            with self.assertRaisesRegex(ValueError,'Missing input'):
                subject.fixed_fc_inputs(lock,self.root)
            path=subject.cache_path(self.root/'.build-inputs',entry)
            path.parent.mkdir(parents=True)
            path.write_bytes(data[:-1])
            with self.assertRaisesRegex(ValueError,'Wrong size'):
                subject.fixed_fc_inputs(lock,self.root)
            path.write_bytes(data[:-1]+b'X')
            with self.assertRaisesRegex(ValueError,'SHA256 mismatch'):
                subject.fixed_fc_inputs(lock,self.root)
            path.write_bytes(data)
            self.assertEqual(subject.fixed_fc_inputs(lock,self.root),{'fceumm-retroarch-pad':path})
            self.assertEqual(subject.fixed_fc_inputs({},self.root),{})

    def test_public_bootstrap_catalog_retains_exact_artifacts_and_release_tags(self):
        expected = {
            'fc': ('game-console-0.31.0-alpha.76.54.jar', 39954627, '3632de31cc6658f32f9e7c498c916c1680218fe58ae160f58e9fa0f0551d0549'),
            'sfc': ('game-console-sfc-0.1.0-alpha.51.jar', 3087373, 'a9c8bdd2ae068383310ac57efcfb18df29eca67238218b12bc6da38b47cf1816'),
            'md': ('game-console-md-0.1.0-alpha.15.jar', 2371782, '9ea8727a77e2cc10fc59a2e34bb1b16466be2fcb1d23e941216b79921fc4b6df'),
            'gba': ('game-console-gba-0.1.0-alpha.15.jar', 2670862, '7e991d3151c140c01a4acc094d68f9ccaf2e285b6a0fadf6abb15cb345d40f0a'),
            'arcade': ('game-console-arcade-0.1.5.12.jar', 246392457, '32ffbf3e2245495dd35dbfed9704595a534a40690dedaec709771e3c07d62b3c'),
            'pvz': ('game-console-pvz-0.1.0-prototype.12.jar', 5004879, 'e010b4185c8a9e51edcba3ef40718a81781068320681892cf869d68c3046f04a'),
        }
        self.assertEqual(expected, subject.BOOTSTRAP)
        self.assertEqual(set(expected), set(subject.BOOTSTRAP_RELEASES))
        for ident, (filename, _, _) in expected.items():
            tag = 'device-polish-fc7654-20261012-r1' if ident in ('fc', 'arcade') else 'full-test-20261007-r1'
            base = 'https://github.com/meilian258-dotcom/game-console/releases/download/' + tag + '/'
            self.assertEqual(base, subject.BOOTSTRAP_RELEASES[ident])
            self.assertEqual(filename, Path(filename).name)
            self.assertEqual(filename, subject.relative(filename).as_posix())
            self.assertEqual(base + filename, subject.checked_url(base + filename))
        filenames = [entry[0].casefold() for entry in expected.values()]
        self.assertEqual(len(filenames), len(set(filenames)))

    def test_prepare_fetches_each_exact_release_url_with_its_pin(self):
        class ReachedDownload(Exception):
            pass

        for ident, entry in subject.BOOTSTRAP.items():
            # Put this artifact first without altering any production pin or the
            # real JNI guard. Fixed pairs are unrelated fixtures in this test.
            ordered = {ident: entry, **subject.BOOTSTRAP}
            output = self.root / ident
            tag = 'device-polish-fc7654-20261012-r1' if ident in ('fc', 'arcade') else 'full-test-20261007-r1'
            url = 'https://github.com/meilian258-dotcom/game-console/releases/download/' + tag + '/' + entry[0]
            with self.subTest(ident=ident), patch.object(subject, 'BOOTSTRAP', ordered), \
                    patch.object(subject.snapshot_stock, 'inspect_pair', return_value={}), \
                    patch.object(subject.sfc_stock, 'inspect_pair', return_value={}), \
                    patch.object(subject, 'fixed_fc_inputs', return_value={}), \
                    patch.object(subject.snapshot_stock, 'import_pair'), \
                    patch.object(subject.sfc_stock, 'import_pair'), \
                    patch.object(subject, 'fetch', side_effect=ReachedDownload) as fetch:
                with self.assertRaises(ReachedDownload):
                    subject.prepare(output)
                fetch.assert_called_once_with(url, output / 'bootstrap' / entry[0], entry[1],
                                              dict(bytes=entry[1], sha256=entry[2]))

    def test_missing_fixed_fc_core_fails_before_any_download_or_output(self):
        lock=subject.load_lock()
        with patch.object(subject.snapshot_stock,'inspect_pair',return_value={}), \
                patch.object(subject.sfc_stock,'inspect_pair',return_value={}), \
                patch.object(subject,'load_lock',return_value=lock), \
                patch.object(subject,'fetch') as fetch:
            with self.assertRaisesRegex(ValueError,'Missing input'):
                subject.prepare(self.root/'output',source=self.root/'source')
            fetch.assert_not_called()
            self.assertFalse((self.root/'output').exists())

    def test_current_public_fc_bundle_matches_exact_locked_pair(self):
        lock = subject.load_lock()
        subject.require_published_jni_bundle(lock)
        expected = {
            'libretro-jni-abi2': (1320960, 'fc731f3b83b0b23f473b48bb9f399582ace264c0646eec45fbffafaf0fb36923'),
            'libcxx-windows': (1659392, '7344daed05388589e9bd691ed1d30c568c374da4b8b6a12e1502185948c03cd4'),
        }
        self.assertEqual(expected, subject.PUBLISHED_JNI_BUNDLES[subject.BOOTSTRAP['fc'][2]])
        self.assertEqual(expected, {ident: (lock[ident]['bytes'], lock[ident]['sha256'])
                                    for ident in subject.JNI_BUNDLE_INPUTS})

    def test_unpublished_bundle_fails_before_network_and_offline_copy(self):
        for offline in (None, self.root / 'missing-offline-bootstrap'):
            output = self.root / 'unprepared'
            with self.subTest(offline=offline), patch.object(subject, 'fetch') as fetch, \
                    patch.object(subject, 'verify') as verify, \
                    patch.dict(subject.PUBLISHED_JNI_BUNDLES, {}, clear=True):
                with self.assertRaisesRegex(ValueError, '^JNI dependency bundle requires a matching published bootstrap$'):
                    subject.prepare(output, bootstrap_dir=offline)
                fetch.assert_not_called()
                verify.assert_not_called()
                self.assertFalse(output.exists())

    def test_wrong_bundle_fails_before_network_or_fixed_input_import(self):
        lock = subject.load_lock()
        cases = [('old-fc', ('game-console-0.31.0-alpha.76.44.jar', 39308439,
                            '7d4db04cc8bafb3f7dc426e3e8412459ba1308dac9d33c434f99c1fcf5eac716'), lock),
                 ('unknown-fc', subject.BOOTSTRAP['fc'][:2] + ('0' * 64,), lock),
                 ('missing-runtime', subject.BOOTSTRAP['fc'],
                  {key: value for key, value in lock.items() if key != 'libcxx-windows'})]
        for ident in subject.JNI_BUNDLE_INPUTS:
            for field, value in (('bytes', lock[ident]['bytes'] + 1), ('sha256', '0' * 64)):
                cases.append((ident + '-' + field, subject.BOOTSTRAP['fc'],
                              {**lock, ident: {**lock[ident], field: value}}))
        for label, pin, candidate in cases:
            for offline in (None, self.root / 'offline'):
                output = self.root / ('reject-' + label)
                with self.subTest(case=label, offline=offline), \
                        patch.dict(subject.BOOTSTRAP, {'fc': pin}), \
                        patch.object(subject, 'load_lock', return_value=candidate), \
                        patch.object(subject, 'fetch') as fetch, \
                        patch.object(subject, 'verify') as verify, \
                        patch.object(subject.snapshot_stock, 'inspect_pair') as fixed:
                    with self.assertRaisesRegex(ValueError, 'matching published bootstrap'):
                        subject.prepare(output, bootstrap_dir=offline)
                    fetch.assert_not_called()
                    verify.assert_not_called()
                    fixed.assert_not_called()
                    self.assertFalse(output.exists())

    def test_bundle_guard_requires_exact_published_hash_and_size(self):
        lock = subject.load_lock()
        published = {ident: (lock[ident]['bytes'], lock[ident]['sha256'])
                     for ident in subject.JNI_BUNDLE_INPUTS}
        with patch.dict(subject.PUBLISHED_JNI_BUNDLES, {subject.BOOTSTRAP['fc'][2]: published}, clear=True):
            subject.require_published_jni_bundle(lock)
            for ident in subject.JNI_BUNDLE_INPUTS:
                for change in ({'bytes': lock[ident]['bytes'] + 1}, {'sha256': '0' * 64}):
                    with self.subTest(ident=ident, change=change), self.assertRaisesRegex(ValueError, 'matching published bootstrap'):
                        subject.require_published_jni_bundle({**lock, ident: {**lock[ident], **change}})
            with self.assertRaisesRegex(ValueError, 'matching published bootstrap'):
                subject.require_published_jni_bundle({k: v for k, v in lock.items() if k != 'libcxx-windows'})
        with patch.dict(subject.PUBLISHED_JNI_BUNDLES, {}, clear=True), \
                self.assertRaisesRegex(ValueError, 'matching published bootstrap'):
            subject.require_published_jni_bundle(lock)

    def test_redirect_rejects_foreign_insecure_credentials(self):
        for url in ('http://buildbot.libretro.com/core.zip', 'https://evil.test/file',
                    'https://github.com@evil.test/file', 'https://a@github.com/file',
                    'https://github.com:8080/file', 'https://github.com/file#fragment'):
            with self.subTest(url=url), self.assertRaises(ValueError):
                subject.checked_url(url)
        subject.checked_url('https://release-assets.githubusercontent.com/file?signature=example')

    def test_good_core_checks_crc_and_architecture(self):
        archive = self.make_zip([('mesen_libretro.dll', self.pe())])
        target = self.root/'nightly'/'mesen_libretro.dll'
        subject.unpack_core(archive, target, target.name, 'windows-x64')
        self.assertEqual(target.read_bytes(), self.pe())

    def test_foreign_member_refused(self):
        archive = self.make_zip([('mesen_libretro.dll', self.pe()), ('extra.dll', b'other')])
        with self.assertRaises(ValueError):
            subject.unpack_core(archive, self.root/'out.dll', 'mesen_libretro.dll', 'windows-x64')

    def test_traversal_member_refused(self):
        for name in ('../bad.dll', '/bad.dll', 'a\\bad.dll', 'c:bad.dll', 'a//bad.dll'):
            archive = self.make_zip([(name, b'x')])
            with self.subTest(name=name), zipfile.ZipFile(archive) as z, self.assertRaises(ValueError):
                subject.members(z)

    def test_case_alias_refused(self):
        archive = self.make_zip([('a.dll', b'a'), ('A.dll', b'b')])
        with zipfile.ZipFile(archive) as z, self.assertRaises(ValueError):
            subject.members(z)

    def test_nul_member_rejected_before_normalization(self):
        archive = self.make_zip([('mesen_libretro.dll\0extra', self.pe())])
        with zipfile.ZipFile(archive) as z, self.assertRaises(ValueError):
            subject.members(z)

    def test_symlink_refused(self):
        entry = zipfile.ZipInfo('core.dll')
        entry.create_system = 3
        entry.external_attr = 0o120777 << 16
        archive = self.make_zip([(entry, b'target')])
        with zipfile.ZipFile(archive) as z, self.assertRaises(ValueError):
            subject.members(z)

    def test_expansion_limit(self):
        archive = self.make_zip([('a', b'1234'), ('b', b'5678')])
        with zipfile.ZipFile(archive) as z, self.assertRaises(ValueError):
            subject.members(z, maximum=7)

    def test_non_native_or_wrong_arch_refused(self):
        for data in (b'html error page', self.pe().replace(b'\x64\x86', b'\x4c\x01')):
            with self.assertRaises(ValueError):
                subject.check_native(data, 'windows-x64')

    def test_elf_x64(self):
        data = bytearray(64)
        data[:6] = b'\x7fELF\x02\x01'
        struct.pack_into('<H', data, 18, 62)
        subject.check_native(bytes(data), 'linux-x64')
        data[4] = 1
        with self.assertRaises(ValueError):
            subject.check_native(bytes(data), 'linux-x64')

    def test_put_never_overwrites_different_file(self):
        path = self.root/'file'
        subject.put(path, b'old')
        subject.put(path, b'old')
        with self.assertRaises(ValueError):
            subject.put(path, b'new')
        self.assertEqual(path.read_bytes(), b'old')

    def test_pinned_cache_reverified_before_network(self):
        path = self.root/'cached'
        subject.put(path, b'good')
        expected = dict(bytes=4, sha256=hashlib.sha256(b'good').hexdigest())
        with patch.object(subject.urllib.request, 'build_opener') as opener:
            subject.fetch(subject.RELEASE+'a.jar', path, 4, expected)
            opener.assert_not_called()
        subject.put(self.root/'bad', b'evil')
        with self.assertRaises(ValueError):
            subject.fetch(subject.RELEASE+'a.jar', self.root/'bad', 4, expected)

    def test_nightly_not_reused_silently(self):
        subject.put(self.root/'nightly.zip', b'x')
        with self.assertRaises(ValueError):
            subject.fetch(subject.NIGHTLY_BASE['windows-x64']+'a.zip', self.root/'nightly.zip', 100)

    def test_resolved_receipt_not_overwritten(self):
        subject.put(self.root/'receipt.json', b'{}')
        with self.assertRaises(ValueError):
            subject.prepare(self.root)


if __name__ == '__main__':
    unittest.main()
