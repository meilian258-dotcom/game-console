"""Fast fixture tests; no network, Gradle, native library or Minecraft required."""
import hashlib
import json
from pathlib import Path
import tempfile
import tomllib
import unittest
from unittest.mock import patch
import zipfile

import snapshot_build as build


def metadata(ident, version='0.1.0'):
    return (f'modLoader="javafml"\nloaderVersion="[4,)"\nlicense="GPL-3.0-or-later"\n'
            f'[[mods]]\nmodId="{ident}"\nversion="{version}"\n'
            f'[[dependencies.{ident}]]\nmodId="minecraft"\nversionRange="[1.21.1,1.21.2)"\n').encode()


def jar(path, entries):
    with zipfile.ZipFile(path, 'x') as archive:
        for name, data in entries.items():
            archive.writestr(name, data)


class SnapshotBuildTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='snapshot-build-tests-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)

    def resources(self, resource='core/windows-x64/genesis_plus_gx_libretro.dll', data=b'fixture'):
        relative = 'resources/md/' + resource
        path = self.root / relative
        path.parent.mkdir(parents=True)
        path.write_bytes(data)
        receipt = self.root / 'receipt.json'
        receipt.write_text(json.dumps(dict(schema=1, files=[dict(path=relative, bytes=len(data),
                                                               sha256=hashlib.sha256(data).hexdigest())])))
        return path, receipt

    def test_resource_is_revalidated(self):
        path, receipt = self.resources()
        build.verify_resources(self.root / 'resources/md', receipt)
        path.write_bytes(b'changed')
        with self.assertRaises(ValueError):
            build.verify_resources(self.root / 'resources/md', receipt)

    def test_unreceipted_resource_fails(self):
        path, receipt = self.resources()
        path.with_name('extra.dll').write_bytes(b'not in receipt')
        with self.assertRaises(ValueError):
            build.verify_resources(self.root / 'resources/md', receipt)

    def test_mod_class_and_retired_core_are_refused(self):
        for name in ('core/windows-x64/Forged.class', 'core/windows-x64/blastem_libretro.dll'):
            with self.subTest(name=name), tempfile.TemporaryDirectory() as folder:
                old = self.root
                self.root = Path(folder)
                _, receipt = self.resources(name)
                with self.assertRaises(ValueError):
                    build.verify_resources(self.root / 'resources/md', receipt)
                self.root = old

    def test_path_traversal_is_refused(self):
        for name in ('../outside', 'a/../b', '/absolute', 'C:/private', 'a\\b', 'a//b', '.'):
            with self.subTest(name=name), self.assertRaises(ValueError):
                build.safe_name(name)

    def test_metadata_preserves_all_component_fields(self):
        a, b = metadata('piq_sfc_arcade'), metadata('piq_sfc_home')
        merged = tomllib.loads(build.combine_metadata(a, b).decode())
        self.assertEqual(['piq_sfc_arcade', 'piq_sfc_home'], [m['modId'] for m in merged['mods']])
        self.assertEqual({'piq_sfc_arcade', 'piq_sfc_home'}, set(merged['dependencies']))
        with self.assertRaises(ValueError):
            build.combine_metadata(a, b'unknown="preserve me"\n' + b)

    def sfc(self, home_extra=None):
        core, home = self.root / 'core.jar', self.root / 'home.jar'
        jar(core, {build.META: metadata('piq_sfc_arcade'), 'cn/piq/sfcarcade/Core.class': b'new core',
                   'assets/piq_sfc_arcade/core/piq_sfc_wasm.wasm': b'wasm',
                   'META-INF/accesstransformer.cfg': b'public fixture'})
        entries = {build.META: metadata('piq_sfc_home'), 'cn/piq/sfchome/Home.class': b'new home',
                   'core/sfc-libretro/windows-x64/mesen-s_libretro.dll': b'core'}
        entries.update(home_extra or {})
        jar(home, entries)
        return core, home

    def test_sfc_merger_uses_both_fresh_class_sets(self):
        core, home = self.sfc()
        merged = build.merge_sfc(core, home, self.root / 'merged.jar')
        entries = build.archive_entries(merged)
        self.assertEqual(b'new core', entries['cn/piq/sfcarcade/Core.class'])
        self.assertEqual(b'new home', entries['cn/piq/sfchome/Home.class'])
        self.assertEqual({'piq_sfc_arcade', 'piq_sfc_home'}, set(build.inspect(merged)['versions']))

    def test_sfc_merger_refuses_foreign_class(self):
        core, home = self.sfc({'cn/piq/retro/Foreign.class': b'old bootstrap class'})
        with self.assertRaises(ValueError):
            build.merge_sfc(core, home, self.root / 'merged.jar')

    def test_sfc_merger_refuses_conflicting_resource(self):
        core, home = self.sfc({'META-INF/accesstransformer.cfg': b'different'})
        with self.assertRaises(ValueError):
            build.merge_sfc(core, home, self.root / 'merged.jar')

    def test_snapshot_stamp_does_not_rewrite_classes_or_versions(self):
        source = self.root / 'input.jar'
        entries = {build.META: metadata('piq_pvz'), 'cn/piq/pvz/New.class': b'compiled'}
        jar(source, entries)
        provenance = json.dumps(dict(schema=1, commit='a' * 40, coreSources=[])).encode()
        output = build.stamp_snapshot(source, self.root / 'output.jar', provenance)
        result = build.archive_entries(output)
        for name, raw in entries.items():
            self.assertEqual(raw, result[name])
        self.assertEqual(provenance, result['META-INF/game-console/snapshot-cores.json'])
        with self.assertRaises(ValueError):
            build.stamp_snapshot(output, self.root / 'second.jar', provenance)

    def test_arcade_helper_must_match_fresh_build_manifest_and_fc_catalog(self):
        helper = b'fresh helper fixture'
        digest = hashlib.sha256(helper).hexdigest()
        fresh = self.root / 'helper.jar'
        fresh.write_bytes(helper)
        catalog = self.root / 'RuntimeCatalog.java'
        catalog.write_text(f'file("piq-native-arcade/runtime", "piq-native-helper-v4.jar", {len(helper)}, "{digest}")')
        arcade = self.root / 'arcade.jar'
        manifest = dict(artifacts=[dict(resourcePath=build.HELPER, size=len(helper), sha256=digest)])
        jar(arcade, {build.HELPER: helper, 'native-runtime/win-x64-v1/manifest-native011.json': json.dumps(manifest).encode()})
        with patch.object(build, 'HELPER_BYTES', len(helper)), patch.object(build, 'HELPER_SHA256', digest):
            build.verify_arcade_helper(arcade, fresh, catalog)
            catalog.write_text(catalog.read_text().replace(digest, '0' * 64))
            with self.assertRaisesRegex(ValueError, 'FC runtime catalog'):
                build.verify_arcade_helper(arcade, fresh, catalog)
            fresh.write_bytes(b'old helper')
            with self.assertRaisesRegex(ValueError, 'Current helper'):
                build.verify_arcade_helper(arcade, fresh, catalog)
        fresh.write_bytes(helper)
        with self.assertRaisesRegex(ValueError, 'reviewed custom identity'):
            build.verify_arcade_helper(arcade, fresh, catalog)

    def test_gba_probe_classpath_preserves_bytes_from_unicode_paths(self):
        deps = self.root / '依赖'
        deps.mkdir()
        (deps / 'library.jar').write_bytes(b'library bytes')
        minecraft = self.root / '游戏资源.jar'
        minecraft.write_bytes(b'resource bytes')
        staging = self.root / 'ascii-staging'
        staging.mkdir()
        receipt = self.root / 'probe-inputs.json'
        with patch.object(build.tempfile, 'mkdtemp', return_value=str(staging)):
            copied, client = build.prepare_gba_probe_classpath(deps, minecraft, receipt)
        self.assertEqual(b'library bytes', (copied / 'dependency-000.jar').read_bytes())
        self.assertEqual(b'resource bytes', client.read_bytes())
        self.assertTrue(str(client).isascii())
        self.assertEqual(2, len(json.loads(receipt.read_text())['files']))

    def test_seven_artifacts_with_exact_ids_and_checksum_manifest(self):
        groups = [('piq_fc_arcade',), ('piq_sfc_arcade', 'piq_sfc_home'), ('piq_md_home',),
                  ('piq_gba',), ('piq_native_arcade',), ('piq_computer',), ('piq_pvz',)]
        paths = []
        for index, ids in enumerate(groups):
            path = self.root / (str(index) + '.jar')
            meta = metadata(ids[0]) if len(ids) == 1 else build.combine_metadata(metadata(ids[0]), metadata(ids[1]))
            jar(path, {build.META: meta, 'cn/piq/fixture' + str(index) + '/Class.class': b'fresh'})
            paths.append(path)
        output = self.root / 'dist'
        manifest = build.stage_outputs(paths, output, dict(commit='a' * 40, minecraftTested=False))
        self.assertEqual(7, len(manifest['artifacts']))
        self.assertEqual(9, len(list(output.iterdir())))
        self.assertEqual(8, len((output / 'SHA256SUMS.txt').read_text().splitlines()))
        with self.assertRaises(ValueError):
            build.stage_outputs(paths, output, {})


if __name__ == '__main__':
    unittest.main()
