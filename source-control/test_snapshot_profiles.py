"""Nightly profile generator contract tests; no downloads or native execution."""
import base64
import json
from pathlib import Path
import shutil
import tempfile
import unittest
from unittest.mock import patch
from types import SimpleNamespace
import zipfile

import snapshot_profiles as profiles

ROOT = Path(__file__).resolve().parents[1]


class SnapshotProfilesTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='snapshot-profiles-test-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def cores(self):
        entries = []
        files = []
        for ident, (platform, filename) in profiles.ARTIFACTS.items():
            rel = f'nightly/{platform}/{filename}'
            path = self.root / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(('test-fixture-' + ident).encode())
            archive = self.root / f'downloads/{platform}/{filename}.zip'
            archive.parent.mkdir(parents=True, exist_ok=True)
            with zipfile.ZipFile(archive, 'w') as z:
                z.writestr(filename, path.read_bytes())
            files.extend([profiles.record(path, self.root), profiles.record(archive, self.root)])
            os_name = 'windows' if platform == 'windows-x64' else 'linux'
            entries.append(dict(profiles.record(path, self.root), id=ident, platform=platform,
                                url=f'https://buildbot.libretro.com/nightly/{os_name}/x86_64/latest/{filename}.zip',
                                archiveSha256=profiles.sha(archive.read_bytes())))
        return dict(schema=1, nightly=entries, files=files)

    def test_exact_five_downloaded_identities(self):
        receipt = self.cores()
        found = profiles.nightly_records(receipt, self.root)
        self.assertEqual(set(profiles.ARTIFACTS), set(found))
        for mutation in (
            lambda r: r['nightly'].pop(),
            lambda r: r['nightly'].append(r['nightly'][0]),
            lambda r: r['nightly'][0].update(platform='linux-x64'),
            lambda r: r['nightly'][0].update(url='https://example.com/core.zip'),
            lambda r: r['nightly'][0].update(sha256='b' * 64),
            lambda r: r['nightly'][0].update(bytes=1),
            lambda r: r['nightly'][0].update(path='../escape'),
            lambda r: r['nightly'][0].update(archiveSha256='a' * 64),
            lambda r: r['files'].pop(0),
            lambda r: r['files'].append(r['files'][0]),
        ):
            changed = json.loads(json.dumps(receipt))
            mutation(changed)
            with self.assertRaises(ValueError):
                profiles.nightly_records(changed, self.root)
        target = self.root / receipt['nightly'][0]['path']
        target.write_bytes(b'tampered after receipt')
        with self.assertRaises(ValueError):
            profiles.nightly_records(receipt, self.root)

    def test_strict_replacement_and_line_endings(self):
        target = self.root / 'template.java'
        target.write_bytes(b'one\r\nTWO\r\n')
        profiles.replace_exact(target, 'TWO', 'three')
        self.assertEqual(b'one\r\nthree\r\n', target.read_bytes())
        for value in (b'absent', b'TWO TWO'):
            target.write_bytes(value)
            with self.assertRaises(ValueError):
                profiles.replace_exact(target, 'TWO', 'three')
            self.assertEqual(value, target.read_bytes())

    def copy_templates(self):
        paths = [
            'piq-fc-arcade/src/main/resources/core/libretro/mesen-profile.properties',
            profiles.FC + 'core/libretro/GenericLibretroNesCore.java',
            profiles.FC + 'netplay/NetplayProfile.java',
            profiles.FC + 'runtime/RuntimeCatalog.java',
            'piq-fc-arcade/src/test/java/cn/piq/fcarcade/core/libretro/GenericLibretroCompatibilitySmoke.java',
            'piq-fc-arcade/src/test/java/cn/piq/fcarcade/netplay/NetplayProfileTest.java',
            'piq-fc-arcade/src/test/java/cn/piq/fcarcade/runtime/RuntimeInstallerTest.java',
            'piq-sfc-home/src/main/java/cn/piq/sfchome/core/LibretroSfcCore.java',
            'piq-sfc-home/src/main/java/cn/piq/sfchome/core/SfcNetplayProfile.java',
            'piq-md-home/build.gradle',
            'piq-md-home/src/main/java/cn/piq/mdhome/client/MdProfile.java',
            'piq-md-home/src/main/java/cn/piq/mdhome/MdNetplayProfile.java',
            'piq-md-home/src/test/java/cn/piq/mdhome/MdNetplayContractTest.java',
            'piq-md-home/src/test/java/cn/piq/mdhome/client/MdPureTest.java',
            profiles.GBA + 'GbaProcessSession.java', profiles.GBA + 'GbaJniSession.java',
            'piq-gba/helper/src/main/java/cn/piq/gba/bridge/GbaCore.java',
            'piq-gba/tools/build_current.py', 'piq-gba/tools/qa/GbaJniProbe.java',
        ]
        before = {}
        for name in paths:
            source, target = ROOT / name, self.root / name
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
            before[name] = source.read_bytes()
        return before

    def test_generated_identity_namespace_and_custom_core_preservation(self):
        before = self.copy_templates()
        cores = profiles.nightly_records(self.cores(), self.root)
        worker = dict(sha256='c' * 64, bytes=2048)
        helper = dict(sha256='d' * 64, bytes=4096)
        profiles.patch_gba(self.root, cores['mgba-windows'], '0.11-212-test')
        identity = profiles.patch_profiles(self.root, cores, '0.11-212-test', worker, helper)
        profile = self.root / 'piq-fc-arcade/src/main/resources/core/libretro/mesen-profile.properties'
        self.assertEqual(profiles.sha(profile.read_bytes()), identity['fcProfileSha256'])
        self.assertNotEqual(profiles.OLD_PROFILE, identity['fcProfileSha256'])
        self.assertIn(worker['sha256'], profile.read_text(encoding='utf-8'))
        self.assertIn('snapshot-', identity['sfcBuild'])
        self.assertLessEqual(len(identity['sfcBuild'] + ':jni-v1'), 96, 'SFC network field bound')
        self.assertIn(cores['mgba-windows']['sha256'], identity['gbaProcessNamespace'])
        self.assertIn(cores['mgba-windows']['sha256'], identity['gbaJniNamespace'])
        generic = (self.root / (profiles.FC + 'core/libretro/GenericLibretroNesCore.java')).read_text(encoding='utf-8')
        self.assertIn(identity['fcProfileSha256'], generic)
        self.assertIn(cores['mesen-linux']['sha256'], generic)
        catalog = (self.root / (profiles.FC + 'runtime/RuntimeCatalog.java')).read_text(encoding='utf-8')
        self.assertIn(helper['sha256'].upper(), catalog)
        self.assertIn('4096', catalog)
        self.assertIn(profiles.OLD['mgba-windows'].upper(), catalog, 'old offline pack stays pinned')
        self.assertIn(profiles.OLD_HELPER.upper(), catalog, 'old offline helper stays pinned')
        custom = 'piq-md-home/src/main/java/cn/piq/mdhome/MdNetplayProfile.java'
        self.assertEqual(before[custom], (self.root / custom).read_bytes())
        # The custom core's expectation remains asserted even in the rewritten test.
        test = (self.root / 'piq-md-home/src/test/java/cn/piq/mdhome/MdNetplayContractTest.java').read_text(encoding='utf-8')
        self.assertIn('v1.7.4c2838c7-piqnp1', test)
        self.assertIn('Genesis Plus GX PIQ Netplay', test)
        for name, data in before.items():
            self.assertEqual(data, (ROOT / name).read_bytes(), 'checkout must remain untouched')
        with self.assertRaises(ValueError):
            profiles.patch_profiles(self.root, cores, '0.11-212-test', worker, helper)

    def test_version_must_not_inject_source(self):
        for version in ('', 'x";throw new Error();//', 'x\nnew statement', 'x' * 97):
            with self.assertRaises(ValueError):
                profiles.patch_gba(self.root, dict(sha256='f' * 64), version)

    def test_probe_requires_exact_mgba_api_identity(self):
        def line(name='mGBA', version='0.11-test', api=1):
            encode = lambda v: base64.b64encode(v.encode()).decode()
            return f'SNAPSHOT_CORE_INFO:{api}:{encode(name)}:{encode(version)}\n'
        for index, text in enumerate((line(), line('other'), line(api=2), line() + line(), line(version='bad\nvalue'))):
            with patch.object(profiles, 'command', side_effect=['', text]):
                args = (self.root / 'core.dll', self.root / 'jna.jar', self.root / str(index), self.root)
                if index == 0:
                    result = profiles.probe_gba(*args)
                    self.assertEqual('0.11-test', result['version'])
                    self.assertTrue(result['processIsolated'])
                    self.assertFalse(result['romStarted'])
                else:
                    with self.assertRaises(ValueError):
                        profiles.probe_gba(*args)

    def test_zip_is_deterministic_and_rejects_paths(self):
        one, two = self.root / 'one.jar', self.root / 'two.jar'
        entries = {'b.class': b'b', 'a.class': b'a'}
        profiles.deterministic_zip(one, entries)
        profiles.deterministic_zip(two, dict(reversed(list(entries.items()))))
        self.assertEqual(one.read_bytes(), two.read_bytes())
        with self.assertRaises(ValueError):
            profiles.deterministic_zip(self.root / 'bad.jar', {'../private': b'no'})

    def test_runtime_base_rejects_unknown_cores(self):
        base = self.root / 'base.jar'
        prefix = 'native-runtime/win-x64-v1/piq-gba/runtime/'
        with zipfile.ZipFile(base, 'x') as z:
            z.writestr(prefix + 'mgba_libretro.dll', b'unapproved')
            z.writestr(prefix + 'piq-gba-helper.jar', b'unapproved')
        with self.assertRaises(ValueError):
            profiles.runtime_base(base, self.root / 'result.jar', self.root / 'core', self.root / 'helper')
        self.assertFalse((self.root / 'result.jar').exists())

    def test_checkout_copy_excludes_untracked_private_files(self):
        source, output = self.root / 'checkout', self.root / 'stage'
        (source / 'source-control').mkdir(parents=True)
        output.mkdir()
        (source / 'tracked.txt').write_bytes(b'tracked source')
        (source / 'source-control/snapshot_profiles.py').write_bytes(b'known build script')
        (source / 'source-control/private_local.py').write_bytes(b'must not copy')
        with patch.object(profiles.subprocess, 'run', return_value=SimpleNamespace(stdout=b'tracked.txt\0')):
            profiles.copy_checkout(source, output)
        self.assertEqual(b'tracked source', (output / 'tracked.txt').read_bytes())
        self.assertTrue((output / 'source-control/snapshot_profiles.py').is_file())
        self.assertFalse((output / 'source-control/private_local.py').exists())


if __name__ == '__main__':
    unittest.main()
