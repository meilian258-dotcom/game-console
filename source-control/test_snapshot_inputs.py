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
        self.assertEqual(len(subject.NIGHTLY), 5)
        self.assertFalse(any('netplay' in member or 'blastem' in member for _, member in subject.NIGHTLY.values()))
        self.assertTrue(set(subject.JNI_BUNDLE_INPUTS).isdisjoint(subject.NIGHTLY))
        self.assertFalse(any('libc++' in member or 'libcxx' in member or 'libretro-jni' in member
                             for _, member in subject.NIGHTLY.values()))

    def test_fc_mapping_includes_cache_to_packaged_runtime_rename(self):
        lock = subject.load_lock()
        self.assertEqual(set(lock), set(subject.FC_RESOURCES))
        self.assertEqual(lock['libcxx-windows']['filename'], 'libcxx.dll')
        self.assertEqual(subject.FC_RESOURCES['libcxx-windows'],
                         'core/libretro-jni/windows-x64/libc++.dll')

    def test_unpublished_bundle_fails_before_network_and_offline_copy(self):
        for offline in (None, self.root / 'missing-offline-bootstrap'):
            output = self.root / 'unprepared'
            with self.subTest(offline=offline), patch.object(subject, 'fetch') as fetch, \
                    patch.object(subject, 'verify') as verify:
                with self.assertRaisesRegex(ValueError, '^JNI dependency bundle requires a matching published bootstrap$'):
                    subject.prepare(output, bootstrap_dir=offline)
                fetch.assert_not_called()
                verify.assert_not_called()
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
