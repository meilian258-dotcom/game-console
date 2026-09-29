"""Tool-only unit tests; read pinned previous JARs, never compile/run any mod."""
import copy
import io
import json
from pathlib import Path
import tempfile
import tomllib
import unittest
from unittest.mock import patch
import zipfile

import build_release38 as b


class Release38Tests(unittest.TestCase):
    def test_safe_entries_reject_broad_or_ambiguous_paths(self):
        for name in ['', '../a', '/a', 'a\\b', 'C:/a', 'assets/*', 'assets/x?.png', 'a//b', 'x/./y', 'a\n.png']:
            with self.subTest(name=name), self.assertRaises(ValueError):
                b.entry_path(name)
        self.assertEqual('assets/piq_fc_arcade/textures/block/famicom.png',
                         b.entry_path('assets/piq_fc_arcade/textures/block/famicom.png'))

    def test_all_real_frozen_metadata_preserve_every_non_version_field(self):
        for kind, (name, sha) in b.BASE.items():
            actual, _, old = b.read_jar(b.BASE_DIR / name)
            self.assertEqual(sha, actual)
            meta, mf = b.metadata(kind, old, [])
            expected = tomllib.loads(old[b.META].decode())
            for mod in expected['mods']:
                mod['version'] = b.VERSIONS[mod['modId']]
            self.assertEqual(expected, tomllib.loads(meta.decode()))
            self.assertEqual(tomllib.loads(old[b.META].decode())['dependencies'], expected['dependencies'])
            if kind == 'native':
                self.assertEqual(old[b.META], meta)
                self.assertEqual(old[b.MANIFEST], mf)
            elif kind == 'sfc':
                self.assertEqual('0.2.0-alpha.7', next(m['version'] for m in expected['mods'] if m['modId'] == 'piq_sfc_arcade'))

    def test_license_change_is_semantically_exact(self):
        _, _, old = b.read_jar(b.BASE_DIR / b.BASE['fc'][0])
        before = tomllib.loads(old[b.META].decode())['license']
        result, _ = b.metadata('fc', old, [dict(kind='fc', field='license', before=before,
                                               after='See LICENSE', reason='synthetic test')])
        parsed = tomllib.loads(result.decode())
        self.assertEqual('See LICENSE', parsed['license'])
        with self.assertRaises(ValueError):
            b.metadata('fc', old, [dict(kind='fc', field='license', before='not the old value', after='x', reason='test')])

    def test_resource_removal_finds_direct_class_json_references(self):
        name = 'assets/piq_fc_arcade/textures/block/unused.png'
        archives = {'fc': {'Model.class': b'\0piq_fc_arcade:block/unused\0', 'entry.json': b'{"x":"textures/block/unused.png"}'}}
        self.assertEqual(['fc:Model.class', 'fc:entry.json'], b.removed_reference_hits('fc', name, archives))
        self.assertEqual([], b.removed_reference_hits('fc', name, {'fc': {'entry.json': b'{"x":"unrelated"}'}}))

    def test_archive_keeps_exact_resource_and_class_bytes(self):
        files = {'cn/piq/Test.class': b'CAFEBABE\0test', b.MANIFEST: b'Manifest-Version: 1.0\r\n\r\n',
                 'assets/piq/textures/x.png': b'opaque payload'}
        raw = b.jar_bytes(files)
        self.assertEqual(raw, b.jar_bytes(files))
        with zipfile.ZipFile(io.BytesIO(raw)) as z:
            self.assertEqual(files, {n: z.read(n) for n in z.namelist()})
            self.assertIsNone(z.testzip())

    def test_plan_rejects_classes_language_runtime_globs_and_native_mutation(self):
        template = dict(kind='fc', entry='assets/piq/textures/test.png', action='add', category='texture',
                        source='payload.png', before_sha256=None, after_sha256=b.digest(b'payload'), reason='synthetic only')
        with tempfile.TemporaryDirectory(prefix='piq-release38-tool-test-') as temp:
            root = Path(temp)
            (root / 'payload.png').write_bytes(b'payload')
            path = root / 'plan.json'
            def load(op, metadata=None, extra=None):
                value = dict(schema=b.SCHEMA, operations=[op], metadata_changes=metadata or [])
                value.update(extra or {})
                raw = json.dumps(value).encode()
                path.write_bytes(raw)
                return b.load_plan(path, b.digest(raw))
            with patch.object(b, 'ROOT', root):
                load(template)
                for entry, category in [('Test.class', 'license'), ('core/test.wasm', 'license'),
                                         ('assets/piq/lang/zh_cn.json', 'unused-resource'), ('assets/*', 'texture'),
                                         ('META-INF/neoforge.mods.toml', 'license'), ('META-INF/x.mixins.json', 'license')]:
                    with self.subTest(entry=entry), self.assertRaises(ValueError):
                        load(dict(template, entry=entry, category=category))
                with self.assertRaises(ValueError):
                    load(dict(template, kind='native'))
                with self.assertRaises(ValueError):
                    load(template, extra={'allow_all_assets': True})
                with self.assertRaises(ValueError):
                    load(template, metadata=[dict(kind='fc', field='dependencies', before='x', after='y', reason='test')])
                with self.assertRaises(ValueError):
                    b.load_plan(path, '0' * 64)


if __name__ == '__main__':
    unittest.main()
