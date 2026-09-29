import json
from pathlib import Path
import tempfile
import unittest
import warnings
import zipfile

from release_artifacts import META, PROFILES, inspect, stage


class ReleaseNamesTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def jar(self, mods, name='legacy.jar'):
        path = self.root / name
        with zipfile.ZipFile(path, 'x') as archive:
            metadata = '\n'.join(f'[[mods]]\nmodId="{i}"\nversion="{v}"' for i, v in mods)
            archive.writestr(META, metadata)
            archive.writestr('assets/piq_example/models/block/example.json', '{}')
        return path

    def test_every_profile_and_sfc_primary_version(self):
        for index, (ids, (prefix, main_id, dev)) in enumerate(PROFILES.items()):
            with self.subTest(prefix=prefix):
                p = self.jar([(i, '1.2.3' if i == main_id else '0.2.0') for i in sorted(ids)], f'{index}.jar')
                result = inspect(p, allow_development=True)
                self.assertEqual(f'{prefix}-1.2.3.jar', result['fileName'])
                self.assertEqual(dev, result['developmentOnly'])

    def test_preserves_source_and_entire_jar(self):
        source = self.jar([('piq_gba', '0.1.0-alpha.11')])
        before = source.read_bytes()
        result = stage([source], self.root / 'new')
        entry = result['files'][0]
        self.assertEqual(before, source.read_bytes())
        self.assertEqual(before, (self.root / 'new' / entry['fileName']).read_bytes())
        self.assertFalse(result['rebuilt'])
        self.assertEqual(result, json.loads((self.root / 'new/release-manifest.json').read_text('utf8')))

    def test_refuses_overwrite_and_duplicate_inputs(self):
        source = self.jar([('piq_fc_arcade', '1.2')])
        with self.assertRaises(ValueError):
            stage([source], self.root)
        with self.assertRaises(ValueError):
            stage([source, source], self.root / 'duplicate')
        self.assertFalse((self.root / 'duplicate').exists())

    def test_rejects_incomplete_sfc_by_default(self):
        for index, mod_id in enumerate(('piq_sfc_home', 'piq_sfc_arcade')):
            with self.assertRaises(ValueError):
                inspect(self.jar([(mod_id, '1.2')], f'thin{index}.jar'))

    def test_rejects_unsafe_unresolved_and_empty_versions(self):
        for index, version in enumerate(('../bad', '1/../../bad', '${file.jarVersion}', '', '1:bad', '1' * 97)):
            with self.assertRaises(ValueError):
                inspect(self.jar([('piq_fc_arcade', version)], f'bad{index}.jar'))

    def test_rejects_unknown_empty_and_duplicate_ids(self):
        for index, mods in enumerate(([], [('unknown', '1')], [('piq_fc_arcade', '1'), ('piq_fc_arcade', '2')])):
            with self.assertRaises(ValueError):
                inspect(self.jar(mods, f'ids{index}.jar'))

    def test_rejects_missing_metadata(self):
        path = self.root / 'internal.jar'
        with zipfile.ZipFile(path, 'x') as archive:
            archive.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n')
        with self.assertRaises(ValueError):
            inspect(path)

    def test_rejects_duplicate_zip_members(self):
        path = self.jar([('piq_fc_arcade', '1')])
        with warnings.catch_warnings():
            warnings.simplefilter('ignore', UserWarning)
            with zipfile.ZipFile(path, 'a') as archive:
                archive.writestr(META, '[[mods]]\nmodId="piq_gba"\nversion="1"')
        with self.assertRaises(ValueError):
            inspect(path)

    def test_rejects_oversized_metadata_before_read(self):
        path = self.root / 'large.jar'
        with zipfile.ZipFile(path, 'x', compression=zipfile.ZIP_DEFLATED) as archive:
            archive.writestr(META, '#' * (1024 * 1024 + 1))
        with self.assertRaises(ValueError):
            inspect(path)

    def test_preflight_failure_leaves_no_output(self):
        good = self.jar([('piq_fc_arcade', '1')], 'good.jar')
        bad = self.jar([('unknown', '1')], 'unknown.jar')
        with self.assertRaises(ValueError):
            stage([good, bad], self.root / 'preflight')
        self.assertFalse((self.root / 'preflight').exists())


if __name__ == '__main__':
    unittest.main()
