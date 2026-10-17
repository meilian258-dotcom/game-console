"""Fixed-stock fixtures: no network, native execution, Gradle or real vendor inputs."""
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import snapshot_build as build
import snapshot_inputs as inputs
import snapshot_profiles as profiles
import snapshot_stock as stock


class StockInputTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='snapshot-stock-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.original_pins = {k: dict(v) for k, v in stock.INPUTS.items()}
        self.raw = dict(core=b'fixed core fixture', source=b'fixed source archive fixture')
        self.pins = {role: dict(path=stock.INPUTS[role]['path'], bytes=len(raw),
                               sha256=hashlib.sha256(raw).hexdigest()) for role, raw in self.raw.items()}
        self.addCleanup(patch.stopall)
        # SFC's separate fixed pair is exercised by test_sfc_stock, not these FBNeo fixtures.
        patch.object(inputs.sfc_stock, 'inspect_pair', return_value={}).start()
        patch.object(inputs.sfc_stock, 'import_pair').start()
        patch.object(inputs.sfc_stock, 'verify_receipt', return_value={}).start()
        patch.dict(stock.INPUTS, self.pins, clear=True).start()
        self.origins = {}
        for role, raw in self.raw.items():
            path = self.root / ('source-' + role)
            path.write_bytes(raw)
            self.origins[role] = path

    def imported(self):
        output = self.root / 'inputs'
        stock.import_pair(output, stock.inspect_pair(**self.origins))
        records = {entry['path']: dict(entry) for entry in self.pins.values()}
        return output, records

    def receipt(self, output, records):
        path = output / 'receipt.json'
        path.write_text(json.dumps(dict(schema=1, files=list(records.values()), bootstrap=[], nightly=[])))
        return path

    def test_fixed_contract_matches_real_packaging_tool(self):
        path = Path(__file__).resolve().parents[1] / 'piq-native-arcade/tools/prepare_fbneo_stock_runtime.py'
        spec = importlib.util.spec_from_file_location('stock_packaging_contract', path)
        tool = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(tool)
        self.assertEqual((tool.CORE_BYTES, tool.CORE_SHA.lower()),
                         (self.original_pins['core']['bytes'], self.original_pins['core']['sha256']))
        self.assertEqual((tool.SOURCE_BYTES, tool.SOURCE_SHA.lower()),
                         (self.original_pins['source']['bytes'], self.original_pins['source']['sha256']))
        self.assertEqual(tool.SOURCE_NAME, Path(self.original_pins['source']['path']).name)
        self.assertNotIn('fbneo_libretro.dll', [member for _, member in inputs.NIGHTLY.values()])

    def test_pair_is_mandatory_without_vendor_or_network_fallback(self):
        for core, source in ((None, None), (self.origins['core'], None), (None, self.origins['source'])):
            with self.subTest(core=core, source=source), self.assertRaisesRegex(ValueError, 'require both'):
                stock.inspect_pair(core, source)
            with patch.object(inputs, 'require_published_jni_bundle'), patch.object(inputs, 'fetch') as fetch, \
                    self.assertRaisesRegex(ValueError, 'require both'):
                inputs.prepare(self.root / 'unprepared', fbneo_stock_core=core, fbneo_stock_source=source)
            fetch.assert_not_called()
            self.assertFalse((self.root / 'unprepared').exists())

    def test_import_copies_exact_pair_preserves_sources_and_allows_identical_resume(self):
        before = {k: (p.read_bytes(), p.stat().st_mtime_ns) for k, p in self.origins.items()}
        output, records = self.imported()
        stock.import_pair(output, self.origins)
        self.assertEqual(before, {k: (p.read_bytes(), p.stat().st_mtime_ns) for k, p in self.origins.items()})
        actual = stock.verify_receipt(output, records)
        self.assertEqual({k: p.read_bytes() for k, p in actual.items()}, self.raw)
        self.assertEqual({p.relative_to(output).as_posix() for p in output.rglob('*') if p.is_file()}, set(records))

    def test_prepare_places_both_fixed_inputs_in_existing_files_receipt_without_download(self):
        output = self.root / 'prepared'
        with patch.object(inputs, 'require_published_jni_bundle'), \
                patch.object(inputs, 'extract_bootstrap'), \
                patch.object(inputs, 'fixed_fc_inputs', return_value={}), \
                patch.dict(inputs.BOOTSTRAP, {}, clear=True), patch.dict(inputs.NIGHTLY, {}, clear=True), \
                patch.object(inputs, 'fetch') as fetch:
            receipt = inputs.prepare(output, fbneo_stock_core=self.origins['core'],
                                     fbneo_stock_source=self.origins['source'])
        fetch.assert_not_called()
        self.assertEqual({entry['path']: entry for entry in self.pins.values()},
                         {entry['path']: entry for entry in receipt['files']})
        self.assertEqual(receipt, json.loads((output / 'receipt.json').read_text('utf8')))

    def test_valid_stock_pair_does_not_bypass_original_unpublished_jni_blocker(self):
        output = self.root / 'blocked'
        with patch.object(inputs, 'fetch') as fetch, patch.object(stock, 'import_pair') as copy, \
                patch.dict(inputs.PUBLISHED_JNI_BUNDLES, {}, clear=True), \
                self.assertRaisesRegex(ValueError, 'JNI dependency bundle requires a matching published bootstrap'):
            inputs.prepare(output, fbneo_stock_core=self.origins['core'],
                           fbneo_stock_source=self.origins['source'])
        fetch.assert_not_called()
        copy.assert_not_called()
        self.assertFalse(output.exists())

    def test_wrong_source_size_and_same_size_hash_fail_before_copy(self):
        for raw in (b'short', b'x' * len(self.raw['core'])):
            self.origins['core'].write_bytes(raw)
            with self.assertRaises(ValueError):
                stock.inspect_pair(**self.origins)
            with self.assertRaises(ValueError):
                stock.import_pair(self.root / 'unprepared', self.origins)
            self.assertFalse((self.root / 'unprepared').exists())

    def test_conflicting_output_is_never_overwritten(self):
        output, _ = self.imported()
        target = output / self.pins['core']['path']
        target.write_bytes(b'conflicting existing output')
        with self.assertRaises(ValueError):
            stock.import_pair(output, self.origins)
        self.assertEqual(b'conflicting existing output', target.read_bytes())

    def test_missing_record_extra_fixed_record_or_rewritten_pin_rejected(self):
        output, records = self.imported()
        core_path = self.pins['core']['path']
        for changed in (
            {k: v for k, v in records.items() if k != core_path},
            {**records, stock.PREFIX + 'extra.dll': dict(path=stock.PREFIX + 'extra.dll', bytes=1, sha256='0' * 64)},
            {**records, core_path: dict(records[core_path], sha256='0' * 64)},
            {**records, core_path: dict(records[core_path], path='../escape')},
        ):
            with self.subTest(changed=changed), self.assertRaises(ValueError):
                stock.verify_receipt(output, changed)

    def test_rewritten_receipt_cannot_accept_changed_bytes_through_build_verifier(self):
        output, records = self.imported()
        target = output / self.pins['core']['path']
        target.write_bytes(b'x' * len(self.raw['core']))
        records[self.pins['core']['path']]['sha256'] = hashlib.sha256(target.read_bytes()).hexdigest()
        self.receipt(output, records)
        with self.assertRaisesRegex(ValueError, 'cannot change a fixed'):
            build.verify_inputs(output)

    def test_build_verifies_pair_and_passes_explicit_native_gradle_parameters(self):
        output, records = self.imported()
        self.receipt(output, records)
        with patch.dict(build.BOOTSTRAP, {}, clear=True), patch.dict(build.RESOURCE_SCOPES, {}, clear=True):
            verified = build.verify_inputs(output)
        props = build.arcade_gradle_properties(self.root / 'final-fc.jar', verified)
        self.assertEqual(str(output / self.pins['core']['path']), props['fbneoStockCore'])
        self.assertEqual(str(output / self.pins['source']['path']), props['fbneoStockSource'])
        self.assertEqual(str(self.root / 'final-fc.jar'), props['gameConsoleJar'])
        self.assertEqual(build.sys.executable, props['embeddedRuntimePython'])
        (output / self.pins['source']['path']).unlink()
        with self.assertRaises(ValueError):
            build.verify_inputs(output)

    def test_profiles_copy_exact_receipted_pair_and_recheck_fixed_pins(self):
        source, records = self.imported()
        receipt = dict(files=list(records.values()), bootstrap=[], nightly=[])
        destination = self.root / 'profile/snapshot-inputs'
        profiles.copy_receipted_inputs(source, destination, receipt)
        self.assertEqual(self.raw, {k: p.read_bytes() for k, p in stock.verify_receipt(destination, records).items()})
        # A matching edited receipt is still not a new trusted fixed identity.
        entry = receipt['files'][0]
        (source / entry['path']).write_bytes(b'x' * entry['bytes'])
        entry['sha256'] = hashlib.sha256((source / entry['path']).read_bytes()).hexdigest()
        with self.assertRaisesRegex(ValueError, 'cannot change a fixed'):
            profiles.copy_receipted_inputs(source, self.root / 'profile-bad', receipt)

    def test_receipt_path_escape_is_rejected_before_build_input_access(self):
        output, records = self.imported()
        records['../outside'] = dict(path='../outside', bytes=1, sha256='0' * 64)
        self.receipt(output, records)
        with self.assertRaisesRegex(ValueError, 'Unsafe relative'):
            build.verify_inputs(output)

    def test_linked_source_is_not_accepted(self):
        linked = self.root / 'linked-core'
        try:
            linked.symlink_to(self.origins['core'])
        except OSError:
            self.skipTest('Symlink creation unavailable on this host')
        with self.assertRaisesRegex(ValueError, 'Linked'):
            stock.inspect_pair(linked, self.origins['source'])

    def test_bootstrapped_stock_resource_rejected_even_with_its_own_receipt(self):
        output = self.root / 'bootstrap-input'
        relative = 'resources/arcade/' + stock.GENERATED_PREFIXES[0] + 'fbneo_libretro.dll'
        path = output / relative
        path.parent.mkdir(parents=True)
        path.write_bytes(self.raw['core'])
        receipt = self.receipt(output, {relative: dict(path=relative, bytes=len(self.raw['core']), sha256=hashlib.sha256(self.raw['core']).hexdigest())})
        with self.assertRaisesRegex(ValueError, 'stock resource'):
            build.verify_resources(output / 'resources/arcade', receipt)


if __name__ == '__main__':
    unittest.main()
