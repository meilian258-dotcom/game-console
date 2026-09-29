"""Small synthetic-only regression suite; never creates real delivery packages."""
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock
import warnings
import zipfile

SPEC = importlib.util.spec_from_file_location('package_brand40', Path(__file__).with_name('package_brand40.py'))
p = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(p)


class PackagingTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='brand40-package-test-')
        self.root = Path(self.temp.name).absolute()
        self.patch = mock.patch.object(p, 'ROOT', self.root)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.addCleanup(self.temp.cleanup)

    def file(self, name, data=b'original'):
        path = self.root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        return path

    def jar(self, version=None, extra=None, manifest=None, kind='fc', stale_dep=False):
        mods, deps = [], []
        for owner in sorted(p.OWNERS[kind]):
            mods.append(f'[[mods]]\nmodId="{owner}"\nversion="{version or p.VERSIONS[owner]}"\n')
            dep = 'neoforge' if owner == 'piq_fc_arcade' else 'piq_fc_arcade'
            version_range = '[21.1.236,22)' if dep == 'neoforge' else ('[0.31.0-alpha.39,0.32)' if stale_dep else '[0.31.0-alpha.40,0.32)')
            deps.append(f'[[dependencies.{owner}]]\nmodId="{dep}"\ntype="required"\nside="BOTH"\nversionRange="{version_range}"\n')
        owner = 'piq_sfc_home' if kind == 'sfc' else next(iter(p.OWNERS[kind]))
        files = {'META-INF/neoforge.mods.toml': ''.join(mods + deps).encode(),
                 'META-INF/MANIFEST.MF': f'Implementation-Version: {manifest or p.VERSIONS[owner]}\r\n'.encode(),
                 'example/Test.class': b'not executed'}
        files.update(extra or {})
        path = self.root / 'test.jar'
        with zipfile.ZipFile(path, 'w') as z:
            for name, data in files.items():
                z.writestr(name, data)
        return path

    def test_unsafe_members(self):
        for name in ('/absolute', '../escape', 'x/../escape', 'C:/escape', 'x\\evil', 'x//evil', 'nul\0name'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                p.member(name)

    def test_outside_workspace_rejected(self):
        with self.assertRaises(ValueError):
            p.safe(self.root.parent / 'outside', False)

    def test_duplicate_casefold_members_rejected(self):
        path = self.root / 'bad.zip'
        with zipfile.ZipFile(path, 'w') as z:
            z.writestr('a.txt', b'a')
            z.writestr('A.txt', b'b')
        with self.assertRaises(ValueError):
            p.inventory(path)

    def test_crc_failure_read_to_eof(self):
        path = self.root / 'crc.zip'
        with zipfile.ZipFile(path, 'w') as z:
            z.writestr('file.txt', b'fixture-unique-payload', compress_type=zipfile.ZIP_STORED)
        raw = path.read_bytes()
        path.write_bytes(raw.replace(b'fixture-unique-payload', b'FIXTURE-unique-payload', 1))
        with self.assertRaises(zipfile.BadZipFile):
            p.inventory(path)

    def test_expansion_budget(self):
        path = self.root / 'big.zip'
        with zipfile.ZipFile(path, 'w') as z:
            z.writestr('a', b'1234')
        with self.assertRaises(ValueError):
            p.inventory(path, 3)

    def test_correct_jar_identity(self):
        entries = p.check_jar(self.jar(), 'fc', {})
        self.assertIn('example/Test.class', entries)

    def test_wrong_jar_version(self):
        with self.assertRaises(ValueError):
            p.check_jar(self.jar(version='0.31.0-alpha.39'), 'fc', {})

    def test_wrong_manifest(self):
        with self.assertRaises(ValueError):
            p.check_jar(self.jar(manifest='0.31.0-alpha.39'), 'fc', {})

    def test_old_fc_dependency_rejected(self):
        with self.assertRaises(ValueError):
            p.check_jar(self.jar(kind='gba', stale_dep=True), 'gba', {})

    def test_merged_sfc_identity(self):
        p.check_jar(self.jar(kind='sfc'), 'sfc', {})

    def test_duplicate_class_rejected(self):
        with self.assertRaises(ValueError):
            p.check_jar(self.jar(), 'fc', {'example/Test.class': 'another'})

    def test_game_payload_rejected(self):
        with self.assertRaises(ValueError):
            p.check_jar(self.jar(extra={'roms/example.nes': b'dummy'}), 'fc', {})

    def test_missing_or_partial_stage_rejected(self):
        stage = self.root / 'stage'
        stage.mkdir()
        with mock.patch.object(p, 'STAGE', stage):
            with self.assertRaises(ValueError):
                p.freeze(p.Inputs(), stage / 'guide.md')
            for witness in ({'ok': True}, {'schema': 'block-arcade-brand40-build-1', 'ok': False, 'mode': 'freeze'},
                            {'schema': 'block-arcade-brand40-build-1', 'ok': True, 'mode': 'preflight'}):
                (stage / 'build-witness.json').write_text(json.dumps(witness), encoding='utf-8')
                with self.subTest(witness=witness), self.assertRaises(ValueError):
                    p.freeze(p.Inputs(), stage / 'guide.md')

    def test_pin_mismatch(self):
        with self.assertRaises(ValueError):
            p.Inputs().remember(self.file('x'), {'bytes': 8, 'sha256': '0' * 64})

    def test_input_drift_detected(self):
        inputs = p.Inputs()
        path = inputs.remember(self.file('x'))
        path.write_bytes(b'modified')
        with self.assertRaises(ValueError):
            inputs.unchanged()

    def test_archive_sha_crc_readback_and_no_overwrite(self):
        inputs = p.Inputs()
        path = inputs.remember(self.file('input.jar'))
        dest = self.root / 'fixture.zip'
        result = p.archive(dest, {'mods/input.jar': path, 'note.md': b'local test'}, inputs)
        self.assertTrue(result['all_entries_crc_and_sha256_verified'])
        self.assertEqual(2, len(result['entries']))
        original = dest.read_bytes()
        with self.assertRaises(ValueError):
            p.archive(dest, {'note.md': b'replace'}, inputs)
        self.assertEqual(original, dest.read_bytes())

    def test_archive_rejects_input_changed_after_collection(self):
        inputs = p.Inputs()
        source = inputs.remember(self.file('input.jar'))
        source.write_bytes(b'different')
        with self.assertRaises(ValueError):
            p.archive(self.root / 'partial.zip', {'mods/input.jar': source}, inputs)

    def test_duplicate_output_rejected(self):
        entries = {}
        p.add(entries, 'mods/a.jar', b'a')
        with self.assertRaises(ValueError):
            p.add(entries, 'MODS/A.jar', b'b')


if __name__ == '__main__':
    unittest.main()
