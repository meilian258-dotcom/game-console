"""Fixed SFC resource/snapshot contracts. No network, core execution or Gradle."""
import hashlib
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import zipfile

import sfc_stock as stock
import snapshot_build as build
import snapshot_inputs as inputs
import snapshot_profiles as profiles


class SfcStockTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='sfc-fixed-test-')
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.original = {k: dict(v) for k, v in stock.INPUTS.items()}
        self.core = self.root / 'original.dll'
        self.core.write_bytes(b'unexecuted fixed core fixture')
        self.source = self.root / 'original.zip'
        self.source_entries = {
            'LICENSE': b'original non-commercial license\r\n',
            'filter/snes_ntsc-license.txt': b'full original LGPL fixture\r\n',
            'libretro/Makefile.common': b'SOURCES=filter/snes_ntsc.c\n',
            'apu/bapu/dsp/SPC_DSP.cpp': b'#include "x.h"\n/* Copyright Shay Green\nGNU Lesser General Public License */\nint x;',
            'libretro/libretro.h': b'/* Copyright The RetroArch team\nPermission is hereby granted\nMIT conditions */\n',
            'inline.h': b'// Copyright fixture\n// Permission is hereby granted\n// Preserve all conditions\nint a;',
        }
        self.write_zip(self.source_entries)
        pins = {role: dict(pin, bytes=p.stat().st_size, sha256=hashlib.sha256(p.read_bytes()).hexdigest())
                for role, pin, p in (('core', stock.INPUTS['core'], self.core), ('source', stock.INPUTS['source'], self.source))}
        self.patcher = patch.dict(stock.INPUTS, pins, clear=True)
        self.patcher.start()
        self.addCleanup(self.patcher.stop)

    def write_zip(self, entries):
        with zipfile.ZipFile(self.source, 'w') as archive:
            for name, raw in entries.items():
                archive.writestr(stock.SOURCE_ROOT + name, raw)

    def sources(self):
        return dict(core=self.core, source=self.source)

    def records(self):
        return {stock.PREFIX + pin['filename']: dict(path=stock.PREFIX + pin['filename'], bytes=pin['bytes'], sha256=pin['sha256'])
                for pin in stock.INPUTS.values()}

    def test_real_manifest_two_identities_and_complete_reference_texts(self):
        self.assertEqual('5ce01616184f255623405793e192f79aef5089fa796b29bc11ddc5e052060124', self.original['core']['sha256'])
        self.assertEqual(3840000, self.original['core']['bytes'])
        self.assertNotEqual(self.original['core']['sha256'], stock.LEGACY['sha256'])
        self.assertNotEqual(stock.CORE_RESOURCE, stock.LEGACY_RESOURCE)
        base = stock.ROOT / 'piq-sfc-home/src/main/resources/core/sfc-snes9x'
        reference = json.loads((base / 'reference-only/sources.json').read_text('utf8'))
        for item in reference['files']:
            raw = (base / 'reference-only' / item['file']).read_bytes()
            self.assertEqual(item['bytes'], len(raw))
            self.assertEqual(item['sha256'], hashlib.sha256(raw).hexdigest())
        self.assertIn('not to Snes9x derivitives', (base / 'reference-only/JMA-license.txt').read_text('utf8'))
        self.assertIn('END OF TERMS AND CONDITIONS', (base / 'reference-only/xBRZ-license.txt').read_text('utf8'))

    def test_missing_pair_and_empty_cache_fail_closed(self):
        for core, source in ((self.core, None), (None, self.source), (None, None)):
            with self.assertRaises(ValueError):
                stock.inspect_pair(core, source, self.root / 'empty-cache')

    def test_wrong_size_and_same_size_hash_rejected_without_output(self):
        for raw in (b'short', b'x' * stock.INPUTS['core']['bytes']):
            self.core.write_bytes(raw)
            with self.assertRaises(ValueError):
                stock.prepare(self.root / 'output', self.sources())
            self.assertFalse((self.root / 'output').exists())

    def test_generated_license_bytes_and_inline_notices_preserved(self):
        resources = stock.license_resources(self.source)
        self.assertEqual(self.source_entries['LICENSE'], resources[stock.RESOURCE + 'licenses/LICENSE-Snes9x.txt'])
        self.assertEqual(self.source_entries['filter/snes_ntsc-license.txt'], resources[stock.RESOURCE + 'licenses/LICENSE-LGPL-2.1.txt'])
        comments = resources[stock.RESOURCE + 'licenses/UPSTREAM-COMMENTS.txt']
        for value in (b'Copyright Shay Green', b'GNU Lesser General Public License', b'MIT conditions', b'Preserve all conditions'):
            self.assertIn(value, comments)
        self.assertNotIn(b'int x;', comments)

    def test_prepare_idempotent_preserves_sources_no_source_zip_embedded(self):
        before = {r: (p.read_bytes(), p.stat().st_mtime_ns) for r, p in self.sources().items()}
        output = self.root / 'prepared'
        stock.prepare(output, self.sources())
        stock.prepare(output, self.sources())
        self.assertEqual(self.core.read_bytes(), (output / stock.CORE_RESOURCE).read_bytes())
        self.assertEqual(5, len([p for p in output.rglob('*') if p.is_file()]))
        self.assertEqual([], list(output.rglob('*.zip')))
        self.assertEqual(before, {r: (p.read_bytes(), p.stat().st_mtime_ns) for r, p in self.sources().items()})

    def test_conflicting_or_foreign_output_never_overwritten(self):
        for name in (stock.CORE_RESOURCE, 'foreign/private.txt'):
            output = self.root / name.replace('/', '_')
            target = output / name
            target.parent.mkdir(parents=True)
            target.write_bytes(b'do not overwrite')
            with self.assertRaises(ValueError):
                stock.prepare(output, self.sources())
            self.assertEqual(b'do not overwrite', target.read_bytes())

    def test_cache_import_requires_exact_pair_no_fallback(self):
        cache = self.root / 'cache'
        stock.import_inputs(stock.INPUTS, self.sources(), cache)
        found = stock.inspect_pair(cache=cache)
        self.assertEqual(self.core.read_bytes(), found['core'].read_bytes())
        found['core'].write_bytes(b'wrong')
        with self.assertRaises(ValueError):
            stock.inspect_pair(cache=cache)

    def test_receipt_cannot_rewrite_pins_omit_source_or_add_fixed_file(self):
        output = self.root / 'inputs'
        stock.import_pair(output, self.sources())
        records = self.records()
        stock.verify_receipt(output, records)
        first = next(iter(records))
        for changed in ({k: v for k, v in records.items() if k != first},
                        {**records, first: dict(records[first], sha256='0' * 64)},
                        {**records, stock.PREFIX + 'extra': dict(path=stock.PREFIX + 'extra', bytes=1, sha256='0' * 64)}):
            with self.assertRaises(ValueError):
                stock.verify_receipt(output, changed)

    def test_snapshot_import_and_gradle_properties_use_fixed_pair(self):
        output = self.root / 'inputs'
        with patch.object(inputs, 'require_published_jni_bundle'), patch.object(inputs, 'extract_bootstrap'), \
                patch.object(inputs, 'fixed_fc_inputs', return_value={}), \
                patch.object(inputs.snapshot_stock, 'inspect_pair', return_value={}), \
                patch.object(inputs.snapshot_stock, 'import_pair'), patch.object(inputs.snapshot_stock, 'verify_receipt'), \
                patch.dict(inputs.BOOTSTRAP, {}, clear=True), patch.dict(inputs.NIGHTLY, {}, clear=True), \
                patch.object(inputs, 'fetch') as fetch:
            receipt = inputs.prepare(output, sfc_snes9x_core=self.core, sfc_snes9x_source=self.source)
        fetch.assert_not_called()
        self.assertEqual(self.records(), {e['path']: e for e in receipt['files']})
        with patch.object(build.snapshot_stock, 'verify_receipt', return_value={}), \
                patch.dict(build.BOOTSTRAP, {}, clear=True), patch.dict(build.RESOURCE_SCOPES, {}, clear=True):
            verified = build.verify_inputs(output)
        props = build.sfc_gradle_properties(self.root / 'fc.jar', self.root / 'core.jar', verified)
        self.assertEqual(build.sys.executable, props['sfcRuntimePython'])
        self.assertEqual(str(output / stock.PREFIX / stock.INPUTS['core']['filename']), props['sfcSnes9xCore'])
        with patch.object(profiles, 'checked', wraps=profiles.checked), patch.object(inputs.snapshot_stock, 'verify_receipt'):
            profiles.copy_receipted_inputs(output, self.root / 'copied', receipt)
        stock.verify_receipt(self.root / 'copied', self.records())

    def test_sfc_snapshot_resource_rejects_legacy_core_hash_rewrite(self):
        output = self.root / 'inputs'
        name = 'resources/sfc-home/' + stock.LEGACY_RESOURCE
        path = output / name
        path.parent.mkdir(parents=True)
        path.write_bytes(b'new nightly may not become old Mesen-S')
        receipt = output / 'receipt.json'
        receipt.write_text(json.dumps(dict(schema=1, files=[dict(path=name, bytes=path.stat().st_size, sha256=hashlib.sha256(path.read_bytes()).hexdigest())])))
        with self.assertRaisesRegex(ValueError, 'absent or changed'):
            build.verify_resources(output / 'resources/sfc-home', receipt)

    def test_source_archive_member_shape_validated_even_with_reviewed_hash(self):
        self.write_zip({**self.source_entries, '../escape.txt': b'bad'})
        stock.INPUTS['source'].update(bytes=self.source.stat().st_size, sha256=hashlib.sha256(self.source.read_bytes()).hexdigest())
        with self.assertRaises(ValueError):
            stock.license_resources(self.source)


if __name__ == '__main__':
    unittest.main()
