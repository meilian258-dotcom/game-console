"""Bounded package guard fixtures only; never package or install a production delivery."""
import copy
import io
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
import zipfile

import package_watch23_scale12 as target
from package_fc_core_alpha19 import PackagePlan, json_document, snapshot, verify_archive


class PackageWatch23Scale12Test(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='package-watch23-fixture-',
                                              dir=target.ROOT / 'piq-fc-arcade/build')
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.input = self.directory / 'fixture.txt'
        self.input.write_bytes(b'not a production JAR')
        self.package = PackagePlan({'checks/fixture.txt': self.input.read_bytes()},
                                   (snapshot(self.input),), {'fixture_only': True})
        self.output = self.directory / 'fixture-delivery'
        self.redirect = patch.object(target, 'OUT', self.output)
        self.redirect.start(); self.addCleanup(self.redirect.stop)

    def test_check_only_creates_nothing(self):
        result = target.build(self.package, True)
        self.assertTrue(result['check_only'])
        self.assertFalse(self.output.exists())
        self.assertFalse(self.output.with_suffix('.zip').exists())

    def test_fixture_roundtrip_exact_hash_and_crc(self):
        result = target.build(self.package)
        self.assertTrue(result['ok'])
        actual = self.output.with_suffix('.zip').read_bytes()
        self.assertEqual(result['sha256'], target.digest(actual))
        verify_archive(actual, self.package)
        self.assertEqual((self.output / 'checks/fixture.txt').read_bytes(), self.input.read_bytes())

    def test_existing_folder_zip_or_report_is_never_overwritten(self):
        for path in (self.output, self.output.with_suffix('.zip'), self.output.with_suffix('.verification.json')):
            with self.subTest(path=path):
                path.write_bytes(b'protected fixture')
                with self.assertRaises(ValueError): target.build(self.package, True)
                self.assertEqual(path.read_bytes(), b'protected fixture')
                path.unlink()

    def test_changed_snapshotted_input_rejected(self):
        self.input.write_bytes(b'changed after snapshot')
        with self.assertRaises(ValueError): target.build(self.package, True)
        self.assertFalse(self.output.exists())

    def test_zip_extra_file_rejected(self):
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, 'w') as z:
            z.writestr('checks/fixture.txt', self.input.read_bytes())
            z.writestr('mods/forbidden-platform.jar', b'fixture')
        with self.assertRaises(ValueError): verify_archive(raw.getvalue(), self.package)

    def test_zip_changed_entry_rejected(self):
        raw = io.BytesIO()
        with zipfile.ZipFile(raw, 'w') as z: z.writestr('checks/fixture.txt', b'tampered')
        with self.assertRaises(ValueError): verify_archive(raw.getvalue(), self.package)

    def test_zip_unsafe_path_rejected_before_output(self):
        bad = PackagePlan({'../escaped.txt': b'bad fixture'}, self.package.inputs, {})
        with self.assertRaises(ValueError): target.build(bad)
        self.assertFalse(self.output.exists())
        self.assertFalse((self.directory / 'escaped.txt').exists())

    def test_duplicate_json_report_key_rejected(self):
        with self.assertRaises(ValueError): json_document(b'{"ok":true,"ok":false}')

    def test_report_binding_requires_actual_hash_and_path(self):
        item = snapshot(self.input)
        target.bind_jar({'path': str(item.path), 'sha256': item.sha256}, item, 'fixture')
        with self.assertRaises(ValueError):
            target.bind_jar({'path': str(item.path), 'sha256': '0' * 64}, item, 'fixture')
        other = self.directory / 'same-bytes-other-path.txt'; other.write_bytes(item.raw)
        with self.assertRaises(ValueError):
            target.bind_jar({'path': str(other), 'sha256': item.sha256}, item, 'fixture')

    def test_scope_claim_cannot_skip_method_protection(self):
        raw = (target.ROOT / target.REPORTS['watch23-scope-baseline-sfc11']).read_bytes()
        original = json_document(raw)
        baselines = {k: snapshot(target.stage.BASE / name) for k, (name, _) in target.stage.PINNED.items()}
        target.check_scope(original, baselines)
        for change in ('method', 'core', 'wire', 'probe'):
            bad = copy.deepcopy(original)
            if change == 'method': bad['old_method_protection']['old_identical_methods'] = 0
            elif change == 'core': bad['frozen_sfc6_core_entries_byte_identical'] = 0
            elif change == 'wire': bad['old_network_classes_whole_byte_identical']['sfc'] = 0
            else: bad['probes']['production_compiled'] = True
            with self.subTest(change=change), self.assertRaises(ValueError): target.check_scope(bad, baselines)

    def test_wire_claim_cannot_skip_real_write_completion(self):
        original = json_document((target.ROOT / target.REPORTS['watch23-wire-fc23']).read_bytes())
        artifact = snapshot(target.stage.BASE / target.stage.PINNED['fc'][0])
        target.check_wire(original, artifact)
        original['real_codec_and_shared_window']['actual_connection_write_completion'] = False
        with self.assertRaises(ValueError): target.check_wire(original, artifact)

    def test_old_sfc11_worker_report_cannot_stand_in_for_sfc12(self):
        report = json_document((target.ROOT / 'piq-sfc-home/design/watch11-final-worker-v2.json').read_bytes())
        old = {k: snapshot(target.stage.BASE / target.stage.PINNED[k][0]) for k in ('fc', 'sfc')}
        target.check_worker(report, old)
        final = dict(old, sfc=snapshot(self.input))
        with self.assertRaises(ValueError): target.check_worker(report, final)

    def test_old_sfc11_multiplayer_report_cannot_stand_in_for_sfc12(self):
        report = json_document((target.ROOT / 'piq-sfc-home/design/watch11-final-multiplayer-v1.json').read_bytes())
        old = {k: snapshot(target.stage.BASE / target.stage.PINNED[k][0]) for k in ('fc', 'sfc')}
        target.check_multiplayer(report, old)
        final = dict(old, sfc=snapshot(self.input))
        with self.assertRaises(ValueError): target.check_multiplayer(report, final)


if __name__ == '__main__':
    unittest.main()
