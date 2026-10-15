from contextlib import ExitStack
import hashlib
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import prepare_fbneo_stock_runtime as prepare


class StockRuntimeTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.vendor = self.root / 'vendor'
        self.vendor.mkdir()
        self.core = self.vendor / 'fbneo_libretro.dll'
        self.source = self.vendor / prepare.SOURCE_NAME
        self.license = self.vendor / 'license.txt'
        self.core.write_bytes(b'fixed fake core')
        self.source.write_bytes(b'fixed fake source')
        self.license.write_bytes(b'license'.ljust(11249, b' '))
        self.stack = ExitStack()
        self.addCleanup(self.stack.close)
        for field, value in dict(ROOT=self.root, CORE_BYTES=self.core.stat().st_size,
                                 CORE_SHA=self.sha(self.core), SOURCE_BYTES=self.source.stat().st_size,
                                 SOURCE_SHA=self.sha(self.source), LICENSE_SHA=self.sha(self.license)).items():
            self.stack.enter_context(patch.object(prepare, field, value))
        self.output = self.root / 'build' / 'stock-v1'

    @staticmethod
    def sha(path):
        return hashlib.sha256(path.read_bytes()).hexdigest().upper()

    def run_prepare(self):
        return prepare.prepare(self.core, self.source, self.license, self.output)

    def test_stages_only_fixed_core_and_independent_notice_with_external_source(self):
        originals = {p: p.read_bytes() for p in (self.core, self.source, self.license)}
        self.run_prepare()
        expected = {prepare.RESOURCE, prepare.NOTICE + '/LICENSE.txt', prepare.NOTICE + '/provenance.json'}
        self.assertEqual(expected, {p.relative_to(self.output).as_posix()
                                    for p in self.output.rglob('*') if p.is_file()})
        for original, destination in ((self.core, prepare.RESOURCE),
                                      (self.license, prepare.NOTICE + '/LICENSE.txt')):
            self.assertEqual(originals[original], (self.output / destination).read_bytes())
            self.assertEqual(originals[original], original.read_bytes())
        receipt = json.loads((self.output / prepare.NOTICE / 'provenance.json').read_text('utf8'))
        self.assertEqual('retroarch-stock-v1', receipt['variant'])
        self.assertFalse(receipt['modified'])
        self.assertFalse(receipt['binaryToSourceExactMappingVerified'])
        self.assertFalse(receipt['reproducibleBuildVerified'])
        self.assertEqual(prepare.SOURCE_NAME, receipt['sourceArchive'])
        self.assertEqual(prepare.SOURCE_SHA, receipt['sourceArchiveSha256'])
        self.assertEqual(originals[self.source], self.source.read_bytes())
        self.assertTrue(receipt['sourceArchiveUrl'].startswith('https://codeload.github.com/libretro/FBNeo/zip/'))
        self.assertNotIn(str(self.root), json.dumps(receipt))

    def test_identical_restage_does_not_overwrite_existing_outputs(self):
        self.run_prepare()
        before = {p: (p.stat().st_mtime_ns, p.read_bytes()) for p in self.output.rglob('*') if p.is_file()}
        self.run_prepare()
        self.assertEqual(before, {p: (p.stat().st_mtime_ns, p.read_bytes()) for p in before})

    def test_wrong_core_or_source_or_license_refused_before_output(self):
        for original in (self.core, self.source, self.license):
            correct = original.read_bytes()
            original.write_bytes(b'!'.ljust(len(correct), b'!'))
            with self.assertRaisesRegex(ValueError, 'identity mismatch'):
                self.run_prepare()
            self.assertFalse(self.output.exists())
            original.write_bytes(correct)

    def test_size_mismatch_refused_before_output(self):
        self.core.write_bytes(b'wrong')
        with self.assertRaisesRegex(ValueError, 'size/type mismatch'):
            self.run_prepare()
        self.assertFalse(self.output.exists())

    def test_output_cannot_be_build_root_or_outside_project_build(self):
        for output in (self.root / 'build', self.root / 'outside', self.root / 'build' / '..' / 'escape'):
            with self.assertRaisesRegex(ValueError, 'child of this project build'):
                prepare.prepare(self.core, self.source, self.license, output)
        self.assertFalse((self.root / 'build').exists())

    def test_existing_wrong_core_is_not_overwritten(self):
        target = self.output / prepare.RESOURCE
        target.parent.mkdir(parents=True)
        target.write_bytes(b'preserve this other file')
        with self.assertRaisesRegex(ValueError, 'mismatch'):
            self.run_prepare()
        self.assertEqual(b'preserve this other file', target.read_bytes())

    def test_extra_output_and_changed_notice_are_not_silently_accepted(self):
        self.run_prepare()
        receipt = self.output / prepare.NOTICE / 'provenance.json'
        receipt.write_text('{}', encoding='utf8')
        with self.assertRaisesRegex(ValueError, 'provenance mismatch'):
            self.run_prepare()
        self.assertEqual('{}', receipt.read_text('utf8'))
        extra = self.output / 'unexpected.dll'
        extra.write_bytes(b'not trusted')
        with self.assertRaisesRegex(ValueError, 'Unexpected file'):
            self.run_prepare()
        self.assertEqual(b'not trusted', extra.read_bytes())

    def test_symlink_and_windows_reparse_components_are_rejected(self):
        for details in (SimpleNamespace(st_mode=prepare.stat.S_IFLNK, st_file_attributes=0),
                        SimpleNamespace(st_mode=prepare.stat.S_IFREG, st_file_attributes=0x400)):
            with patch.object(Path, 'lstat', return_value=details):
                with self.assertRaisesRegex(ValueError, 'Linked path'):
                    prepare.no_links(self.core)


if __name__ == '__main__':
    unittest.main()
