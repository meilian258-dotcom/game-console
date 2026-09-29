"""Read-only/synthetic regression tests for private56 freezing; never runs Gradle."""
from pathlib import Path
import copy
import importlib.util
import json
import sys
import tempfile
import unittest
from unittest import mock
import zipfile

sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location('private56_under_test', ROOT / 'piq-fc-arcade/tools/build_private56.py')
m = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(m)
g = m.g


class Private56FreezerTest(unittest.TestCase):
    def metadata(self, kind):
        with zipfile.ZipFile(g.BASE[kind][0]) as archive:
            return {name: archive.read(name) for name in (g.b.META, g.b.MANIFEST)}

    def changed_metadata(self, kind):
        old = self.metadata(kind)
        new = {name: raw.replace(b'0.31.0-alpha.55', b'0.31.0-alpha.56')
               .replace(b'0.1.0-alpha.33', b'0.1.0-alpha.34') for name, raw in old.items()}
        return old, new

    def plan(self):
        return {
            'schema': 'gc018-45-reviewed-changes-1', 'approved': True,
            'class_roots': {kind: sorted(roots) for kind, roots in m.REVIEWABLE_CLASS_ROOTS.items()},
            'removed_classes': {'fc': [], 'sfc': []},
            'language_changes': {'fc': {}, 'sfc': {}}, 'resource_changes': {'fc': {}, 'sfc': {}},
            'required_test_suites': {project: sorted(suites) for project, suites in m.REQUIRED_SUITES.items()},
        }

    def read_synthetic(self, plan):
        with tempfile.TemporaryDirectory(prefix='private56-freezer-test-') as temporary:
            path = Path(temporary) / 'plan.json'
            path.write_text(json.dumps(plan), encoding='utf-8')
            return m.read_plan(path)

    def test_exact_fc_version_delta(self):
        m.metadata('fc', *self.changed_metadata('fc'))

    def test_exact_sfc_version_and_minimum_fc_delta(self):
        m.metadata('sfc', *self.changed_metadata('sfc'))

    def test_old_fc_dependency_rejected(self):
        old, new = self.changed_metadata('sfc')
        new[g.b.META] = new[g.b.META].replace(b'0.31.0-alpha.56', b'0.31.0-alpha.55')
        with self.assertRaises(ValueError):
            m.metadata('sfc', old, new)

    def test_other_dependency_rejected(self):
        old, new = self.changed_metadata('sfc')
        new[g.b.META] = new[g.b.META].replace(b'piq_fc_arcade', b'other_mod')
        with self.assertRaises(ValueError):
            m.metadata('sfc', old, new)

    def test_unrelated_toml_semantics_rejected(self):
        old, new = self.changed_metadata('fc')
        new[g.b.META] += b'\n[unapproved_private56]\nenabled = true\n'
        with self.assertRaises(ValueError):
            m.metadata('fc', old, new)

    def test_core_owner_identity_is_not_silently_changed(self):
        old, new = self.changed_metadata('sfc')
        new[g.b.META] = new[g.b.META].replace(b'piq_sfc_arcade', b'changed_core')
        with self.assertRaises(ValueError):
            m.metadata('sfc', old, new)

    def test_unrelated_manifest_delta_rejected(self):
        old, new = self.changed_metadata('fc')
        new[g.b.MANIFEST] += b'Unapproved-Header: yes\r\n'
        with self.assertRaises(ValueError):
            m.metadata('fc', old, new)

    def test_final_baselines_and_input_witnesses_are_pinned(self):
        pins = [*g.BASE.values(), *g.BASE_WITNESSES.values(), *g.REFERENCES.values(), g.CORE,
                (g.SOURCE_BEFORE, g.SOURCE_BEFORE_SHA), (m.HELPER, m.HELPER_SHA)]
        for path, wanted in pins:
            self.assertEqual(wanted, g.file_hash(path), str(path))
        self.assertTrue(g.BASE['fc'][0].name.endswith('alpha.55.jar'))
        self.assertTrue(g.BASE['sfc'][0].name.endswith('alpha.33.jar'))

    def test_required_regression_suites_and_minimum_counts(self):
        self.assertGreaterEqual(g.b.TEST_LIMITS['piq-fc-arcade'][0], 2031)
        self.assertGreaterEqual(g.b.TEST_LIMITS['piq-sfc-home'][0], 435)
        self.assertEqual(8, g.b.TEST_LIMITS['piq-fc-arcade'][1])
        self.assertEqual(0, g.b.TEST_LIMITS['piq-sfc-home'][1])
        self.assertEqual(self.plan(), self.read_synthetic(self.plan()))

    def test_unapproved_plan_is_not_capture_ready(self):
        plan = self.plan(); plan['approved'] = False
        with self.assertRaises(ValueError):
            self.read_synthetic(plan)

    def test_unknown_production_family_cannot_be_approved_by_plan_only(self):
        plan = self.plan(); plan['class_roots']['fc'].append('cn/piq/fcarcade/server/UnrelatedService')
        with self.assertRaises(ValueError):
            self.read_synthetic(plan)

    def test_core_implementation_is_not_reviewable(self):
        plan = self.plan(); plan['class_roots']['fc'].append('cn/piq/fcarcade/core/wasm/WasmNesCore')
        with self.assertRaises(ValueError):
            self.read_synthetic(plan)

    def test_duplicate_family_is_rejected(self):
        plan = self.plan(); plan['class_roots']['fc'].append(plan['class_roots']['fc'][0])
        with self.assertRaises(ValueError):
            self.read_synthetic(plan)

    def test_missing_private_regression_suite_is_rejected(self):
        plan = self.plan(); plan['required_test_suites']['piq-fc-arcade'].remove('cn.piq.fcarcade.client.privateplay.PrivateSaveStoreTest')
        with self.assertRaises(ValueError):
            self.read_synthetic(plan)

    def test_even_hashed_png_change_is_not_permitted(self):
        plan = self.plan()
        plan['resource_changes']['fc']['assets/piq_fc_arcade/textures/block/television.png'] = {
            'old_sha256': 'A' * 64, 'new_sha256': 'B' * 64}
        with self.assertRaises(ValueError):
            self.read_synthetic(plan)

    def test_language_change_is_not_permitted_without_new_review(self):
        plan = self.plan()
        plan['language_changes']['fc']['assets/piq_fc_arcade/lang/zh_cn.json'] = {'new.private.key': {'old': None, 'new': 'private'}}
        with self.assertRaises(ValueError):
            self.read_synthetic(plan)

    def test_class_family_does_not_grant_similar_named_class(self):
        plan = self.plan()
        root = 'cn/piq/fcarcade/client/privateplay/FcPrivateEngine'
        self.assertTrue(g.allowed_class('fc', root + '.class', plan))
        self.assertTrue(g.allowed_class('fc', root + '$Audio.class', plan))
        self.assertFalse(g.allowed_class('fc', root + 'Other.class', plan))
        self.assertFalse(g.allowed_class('fc', 'cn/piq/fcarcade/core/NesCore.class', plan))

    def test_overlay_rejects_unknown_class_and_undeclared_removal(self):
        plan = self.plan()
        with mock.patch.object(g, 'metadata', return_value=None):
            with self.assertRaises(ValueError):
                g.overlay('sfc', {}, {'cn/piq/sfchome/server/Other.class': b'new'}, plan)
            name = 'cn/piq/sfchome/client/SfcPrivateEngine.class'
            with self.assertRaises(ValueError):
                g.overlay('sfc', {name: b'old'}, {}, plan)

    def test_source_only_controller_draft_is_preserved_not_published(self):
        plan = self.plan()
        name = 'assets/piq_fc_arcade/textures/item/source-only-test.png'
        old, compiled = {name: b'original-frozen-art'}, {name: b'source-draft'}
        with mock.patch.object(g, 'metadata', return_value=None), mock.patch.dict(g.a.CORE_PINS, {}, clear=True), \
                mock.patch.dict(g.a.DRAFTS, {name: g.b.sha(compiled[name])}, clear=True):
            result, preserved = g.overlay('fc', old, compiled, plan)
            self.assertEqual(old, result)
            self.assertEqual([name], preserved)

    def test_wrong_source_draft_checksum_is_rejected(self):
        plan = self.plan()
        name = 'assets/piq_fc_arcade/textures/item/source-only-test.png'
        with mock.patch.object(g, 'metadata', return_value=None), mock.patch.dict(g.a.CORE_PINS, {}, clear=True), \
                mock.patch.dict(g.a.DRAFTS, {name: 'A' * 64}, clear=True):
            with self.assertRaises(ValueError):
                g.overlay('fc', {name: b'original'}, {name: b'wrong-draft'}, plan)

    def test_verify_cli_only_delegates_read_only_full_audit(self):
        with mock.patch.object(g, 'verify_output', return_value={'ok': True}) as verify, \
                mock.patch.object(g, 'freeze') as freeze, mock.patch.object(g.b, 'exclusive_json') as write:
            m.main(['--verify', '--output', str(g.OUT), '--build-witness-sha256', 'A' * 64])
            verify.assert_called_once_with(g.OUT, 'A' * 64)
            freeze.assert_not_called(); write.assert_not_called()

    def test_verify_cli_rejects_capture_or_build_arguments(self):
        with mock.patch.object(g, 'verify_output') as verify:
            with self.assertRaises(ValueError):
                m.main(['--verify', '--build-witness-sha256', 'A' * 64, '--witness', 'source.json'])
            verify.assert_not_called()

    def test_verify_requires_explicit_build_witness_hash(self):
        with mock.patch.object(g, 'verify_output') as verify:
            with self.assertRaises(ValueError):
                m.main(['--verify'])
            verify.assert_not_called()

    def test_unsafe_archive_members_are_rejected(self):
        for name in ('../private.rom', '/absolute', 'a\\b', 'C:/x', 'a//b'):
            with self.assertRaises(ValueError):
                g.member_name(name)


if __name__ == '__main__':
    unittest.main()
