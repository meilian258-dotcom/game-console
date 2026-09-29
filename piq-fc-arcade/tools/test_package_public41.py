import io
import unittest
import zipfile
from package_public41 import zip_bytes, verify


class InstallPreviewArchiveTest(unittest.TestCase):
    def test_roundtrip(self):
        entries = {'mods/main.jar': b'fixture-not-real-mod', '安装说明.md': '候选'.encode('utf-8')}
        verify(zip_bytes(entries), entries)

    def test_duplicate_rejected(self):
        out = io.BytesIO()
        with zipfile.ZipFile(out, 'w') as archive:
            archive.writestr('a', b'a')
            archive.writestr('a', b'a')
        with self.assertRaises(ValueError):
            verify(out.getvalue(), {'a': b'a'})

    def test_extra_member_rejected(self):
        with self.assertRaises(ValueError):
            verify(zip_bytes({'a': b'a', 'b': b'b'}), {'a': b'a'})

    def test_content_mismatch_rejected(self):
        with self.assertRaises(ValueError):
            verify(zip_bytes({'a': b'b'}), {'a': b'a'})

    def test_bad_paths(self):
        for name in ('/a', '../a', 'a/../b', 'a//b', 'C:/a', 'a\\b'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                zip_bytes({name: b'a'})

    def test_reproducible(self):
        self.assertEqual(zip_bytes({'b': b'2', 'a': b'1'}), zip_bytes({'a': b'1', 'b': b'2'}))


if __name__ == '__main__':
    unittest.main()
