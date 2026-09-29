"""Small inert addition/manifest tests; no full runtime generation or native execution."""
from pathlib import Path
import importlib.util
import json
import tempfile
import unittest
import zipfile
from unittest.mock import patch

TOOL = Path(__file__).with_name('prepare_embedded_runtime011.py')
spec = importlib.util.spec_from_file_location('runtime011_under_test', TOOL)
m = importlib.util.module_from_spec(spec)
spec.loader.exec_module(m)


class Runtime011Tests(unittest.TestCase):
    def test_new_manifest_preserves_original_six_payload_identities(self):
        old = json.loads(m.legacy.manifest_bytes(m.legacy.PROFILE))
        new = json.loads(m.manifest_bytes())
        self.assertEqual(new['artifacts'][:-1], old['artifacts'])
        self.assertEqual(new['licenses'], old['licenses'])
        self.assertEqual(new['excludedArtifacts'], old['excludedArtifacts'])
        self.assertEqual(new['modVersion'], '0.1.1')
        self.assertEqual(new['helperBuild']['privateProtocol'], 4)
        self.assertEqual(new['selectedOrdinaryHelper'], 'piq-native-arcade/runtime/piq-native-helper-v4.jar')
        self.assertEqual(new['legacyManifest']['sha256'], m.digest(m.legacy.manifest_bytes(m.legacy.PROFILE)))

    def test_real_new_helper_is_pinned_and_contains_only_four_source_roots(self):
        raw = m.HELPER.read_bytes(); additions = m.addition_bytes(raw)
        self.assertEqual(len(additions), 3)
        self.assertEqual(len(raw), 18858)
        with zipfile.ZipFile(m.HELPER) as archive:
            self.assertIsNone(archive.testzip())
            for name in archive.namelist():
                self.assertTrue(name.endswith('.class'))
                self.assertIn(name.removesuffix('.class').split('$')[0].rsplit('/', 1)[-1],
                              ('NativeCoreWorker', 'BridgeProtocol', 'NativeInputPorts', 'NativeArcadeButtons'))
        self.assertNotIn(m.legacy.PREFIX + 'manifest.json', additions)
        self.assertNotIn(m.legacy.PREFIX + 'piq-native-arcade/runtime/piq-native-helper.jar', additions)

    def test_helper_hash_or_size_drift_is_rejected(self):
        for bad in (b'', b'not a helper', m.HELPER.read_bytes() + b'x'):
            with self.assertRaises(m.legacy.VerificationError): m.addition_bytes(bad)

    def test_helper_source_identity_still_matches_compiled_binary(self):
        for name, pin in m.SOURCE_PINS.items(): self.assertEqual(m.digest((m.PROJECT / name).read_bytes()), pin)

    def test_exact_addition_output_rejects_extra_corrupt_and_missing_files(self):
        with tempfile.TemporaryDirectory(prefix='native011-inert-test-') as folder:
            root = Path(folder); entries = {'nested/helper.jar': b'inert-helper', 'manifest.json': b'{}'}
            for name, raw in entries.items():
                path = root / name; path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(raw)
            m.verify_output(root, entries)
            unexpected = root / 'empty-foreign'; unexpected.mkdir()
            with self.assertRaises(m.legacy.VerificationError): m.verify_output(root, entries)
            unexpected.rmdir()
            extra = root / 'extra.txt'; extra.write_bytes(b'foreign')
            with self.assertRaises(m.legacy.VerificationError): m.verify_output(root, entries)
            extra.unlink(); (root / 'manifest.json').write_bytes(b'changed')
            with self.assertRaises(m.legacy.VerificationError): m.verify_output(root, entries)
            (root / 'manifest.json').unlink()
            with self.assertRaises(m.legacy.VerificationError): m.verify_output(root, entries)

    def test_existing_foreign_output_is_not_overwritten(self):
        with tempfile.TemporaryDirectory(prefix='native011-inert-test-') as folder:
            root = Path(folder) / 'output'; root.mkdir(); existing = root / 'foreign'; existing.write_bytes(b'keep')
            with patch.object(m, 'validate_inputs', return_value={'helper.jar': b'inert'}):
                with self.assertRaises(m.legacy.VerificationError): m.prepare(None, None, None, root)
            self.assertEqual(existing.read_bytes(), b'keep')
            self.assertFalse((root / 'helper.jar').exists())

    def test_gradle_precreated_empty_output_and_exact_reuse(self):
        with tempfile.TemporaryDirectory(prefix='native011-inert-test-') as folder:
            root = Path(folder) / 'output'; root.mkdir(); entries = {'sub/helper.jar': b'inert'}
            with patch.object(m, 'validate_inputs', return_value=entries):
                self.assertFalse(m.prepare(None, None, None, root)['reused'])
                identity = (root / 'sub/helper.jar').stat().st_mtime_ns
                self.assertTrue(m.prepare(None, None, None, root)['reused'])
                self.assertEqual((root / 'sub/helper.jar').stat().st_mtime_ns, identity)

    def test_input_drift_never_publishes_or_leaves_staging(self):
        with tempfile.TemporaryDirectory(prefix='native011-inert-test-') as folder:
            root = Path(folder) / 'output'
            with patch.object(m, 'validate_inputs', side_effect=[{'helper.jar': b'original'}, {'helper.jar': b'changed'}]):
                with self.assertRaises(m.legacy.VerificationError): m.prepare(None, None, None, root)
            self.assertFalse(root.exists())
            self.assertEqual(list(Path(folder).iterdir()), [])


if __name__ == '__main__':
    unittest.main(verbosity=2)
