"""Offline freeze-policy tests only: no Gradle, MOD output, native execution or ROM access."""
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
import zipfile

SCRIPT = Path(__file__).with_name('build_arcade_release44.py')
spec = importlib.util.spec_from_file_location('arcade44_build_under_test', SCRIPT)
m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)


def plan():
    return {'schema': 'arcade44-reviewed-changes-1', 'approved': True,
        'class_roots': {'fc': ['cn/piq/fcarcade/client/cabinet/CabinetMenuScreen'], 'native': ['cn/piq/nativearcade/client/NativeCabinetBackend']},
        'removed_classes': {'fc': [], 'native': []}, 'language_changes': {'fc': {}, 'native': {}},
        'native_description': 'Reviewed native description.',
        'required_test_suites': {'piq-fc-arcade': ['cn.piq.fcarcade.ReviewedTest'], 'piq-native-arcade': ['cn.piq.nativearcade.ReviewedTest']}}


class PlanPolicyTests(unittest.TestCase):
    def read(self, value):
        with tempfile.TemporaryDirectory(prefix='arcade44-plan-policy-') as directory:
            path = Path(directory) / 'plan.json'
            with path.open('x', encoding='utf-8') as stream: json.dump(value, stream)
            return m.read_plan(path)

    def test_explicit_reviewed_plan(self):
        self.assertEqual(self.read(plan()), plan())

    def test_unapproved_is_blocked(self):
        p = plan(); p['approved'] = False
        with self.assertRaises(ValueError): self.read(p)

    def test_wildcard_root_is_blocked(self):
        p = plan(); p['class_roots']['fc'] = ['cn/piq/fcarcade/*']
        with self.assertRaises(ValueError): self.read(p)

    def test_foreign_owner_is_blocked(self):
        p = plan(); p['class_roots']['fc'] = ['cn/piq/nativearcade/Other']
        with self.assertRaises(ValueError): self.read(p)

    def test_empty_regression_suites_are_blocked(self):
        p = plan(); p['required_test_suites']['piq-fc-arcade'] = []
        with self.assertRaises(ValueError): self.read(p)

    def test_unreviewed_language_path_is_blocked(self):
        p = plan(); p['language_changes']['fc']['assets/other/lang/en_us.json'] = {'key': {'old': 'a', 'new': 'b'}}
        with self.assertRaises(ValueError): self.read(p)

    def test_duplicate_json_key_is_blocked(self):
        with self.assertRaises(ValueError): m.unique_json('{"approved":false,"approved":true}')

    def test_only_exact_outer_and_inner_classes(self):
        root = plan()['class_roots']['fc'][0]
        self.assertTrue(m.allowed_class('fc', root + '.class', plan()))
        self.assertTrue(m.allowed_class('fc', root + '$1.class', plan()))
        self.assertFalse(m.allowed_class('fc', root + 'Other.class', plan()))
        self.assertFalse(m.allowed_class('fc', root + '.java', plan()))


class OverlayPolicyTests(unittest.TestCase):
    def base(self):
        old = {m.b.META: b'[[mods]]\nmodId="piq_fc_arcade"\nversion="0.31.0-alpha.43"\n',
            m.b.MANIFEST: b'Manifest-Version: 1.0\r\nImplementation-Version: 0.31.0-alpha.43\r\n',
            'cn/piq/fcarcade/client/cabinet/CabinetMenuScreen.class': b'old-class',
            'assets/piq_fc_arcade/textures/frozen.png': b'frozen'}
        for path in m.CORE_PINS: old[path] = (m.ROOT / 'piq-fc-arcade/src/main/resources' / path).read_bytes()
        compiled = dict(old)
        compiled[m.b.META] = old[m.b.META].replace(b'alpha.43', b'alpha.44')
        compiled[m.b.MANIFEST] = old[m.b.MANIFEST].replace(b'alpha.43', b'alpha.44')
        return old, compiled

    def test_approved_class_overlay(self):
        old, compiled = self.base(); key = plan()['class_roots']['fc'][0] + '.class'; compiled[key] = b'new-class'
        final, preserved = m.overlay('fc', old, compiled, plan())
        self.assertEqual(final[key], b'new-class'); self.assertEqual(preserved, [])

    def test_unapproved_class_is_blocked(self):
        old, compiled = self.base(); compiled['cn/piq/fcarcade/Other.class'] = b'new'
        with self.assertRaises(ValueError): m.overlay('fc', old, compiled, plan())

    def test_unapproved_class_removal_is_blocked(self):
        old, compiled = self.base(); del compiled[plan()['class_roots']['fc'][0] + '.class']
        with self.assertRaises(ValueError): m.overlay('fc', old, compiled, plan())

    def test_protected_asset_is_blocked(self):
        old, compiled = self.base(); compiled['assets/piq_fc_arcade/textures/frozen.png'] = b'changed'
        with self.assertRaises(ValueError): m.overlay('fc', old, compiled, plan())

    def test_unknown_new_asset_is_blocked(self):
        old, compiled = self.base(); compiled['assets/piq_fc_arcade/textures/new.png'] = b'new'
        with self.assertRaises(ValueError): m.overlay('fc', old, compiled, plan())

    def test_all_three_wasm_are_protected(self):
        for path in m.CORE_PINS:
            old, compiled = self.base(); compiled[path] = b'changed'
            with self.assertRaises(ValueError): m.overlay('fc', old, compiled, plan())

    def test_known_draft_keeps_frozen_asset(self):
        old, compiled = self.base(); name = next(iter(m.DRAFTS)); old[name] = b'published-artwork'
        compiled[name] = (m.ROOT / 'piq-fc-arcade/src/main/resources' / name).read_bytes()
        final, preserved = m.overlay('fc', old, compiled, plan())
        self.assertEqual(final[name], b'published-artwork'); self.assertEqual(preserved, [name])

    def test_new_unrecognized_draft_hash_is_blocked(self):
        old, compiled = self.base(); name = next(iter(m.DRAFTS)); old[name] = b'old'; compiled[name] = b'unreviewed'
        with self.assertRaises(ValueError): m.overlay('fc', old, compiled, plan())

    def test_exact_language_keys(self):
        old, compiled = self.base(); name = 'assets/piq_fc_arcade/lang/en_us.json'; p = plan()
        old[name] = b'{"key":"old %s","untouched":"same"}'
        compiled[name] = b'{"key":"new %s","untouched":"same"}'
        p['language_changes']['fc'][name] = {'key': {'old': 'old %s', 'new': 'new %s'}}
        m.overlay('fc', old, compiled, p)
        compiled[name] = b'{"key":"new %s","untouched":"changed"}'
        with self.assertRaises(ValueError): m.overlay('fc', old, compiled, p)

    def test_language_format_contract_is_preserved(self):
        name = 'assets/piq_fc_arcade/lang/en_us.json'; p = plan()
        p['language_changes']['fc'][name] = {'key': {'old': 'old %s', 'new': 'new %d'}}
        with self.assertRaises(ValueError): m.language('fc', name, b'{"key":"old %s"}', b'{"key":"new %d"}', p)

    def test_metadata_outside_version_is_blocked(self):
        old, compiled = self.base(); compiled[m.b.META] += b'displayName="unreviewed"\n'
        with self.assertRaises(ValueError): m.overlay('fc', old, compiled, plan())


class ArchiveAndLogTests(unittest.TestCase):
    def log(self, text):
        with tempfile.TemporaryDirectory(prefix='arcade44-log-policy-', dir=m.ROOT / 'outputs') as directory:
            path = Path(directory) / 'gradle.log'
            with path.open('x', encoding='utf-8') as stream: stream.write(text)
            return m.gradle_log(path, 0)

    def test_real_core_tasks_allow_unrelated_up_to_date_task(self):
        text = '> Task :prepareNeoFormRuntime UP-TO-DATE\n> Task :compileJava\n> Task :test\n> Task :jar\n> Task :check\nBUILD SUCCESSFUL\n'
        self.assertEqual(len(self.log(text)), 64)

    def test_skipped_test_task_is_not_a_real_build(self):
        with self.assertRaises(ValueError): self.log('> Task :compileJava\n> Task :test UP-TO-DATE\n> Task :jar\n> Task :check\nBUILD SUCCESSFUL\n')

    def test_failed_build_is_blocked(self):
        with self.assertRaises(ValueError): self.log('> Task :compileJava\n> Task :test\n> Task :jar\n> Task :check\nBUILD FAILED\n')

    def test_missing_jar_task_is_blocked(self):
        with self.assertRaises(ValueError): self.log('> Task :compileJava\n> Task :test\n> Task :check\nBUILD SUCCESSFUL\n')

    def archive(self, members, expected):
        with tempfile.TemporaryDirectory(prefix='arcade44-zip-policy-') as directory:
            path = Path(directory) / 'fixture.jar'
            with zipfile.ZipFile(path, 'x') as archive:
                for name, raw in [(m.b.META, b'toml'), (m.b.MANIFEST, b'manifest'), *members]:
                    archive.writestr(name, raw)
            return m.native_archive(path, expected)

    def test_streamed_fixed_resource(self):
        raw = b'opaque'; key = 'native-runtime/fixed.dll'
        entries, resources = self.archive([(key, raw)], {key: (len(raw), hashlib.sha256(raw).hexdigest().upper())})
        self.assertNotIn(key, entries); self.assertEqual(resources[key]['bytes'], len(raw))

    def test_wrong_runtime_digest_is_blocked(self):
        with self.assertRaises(ValueError): self.archive([('native-runtime/fixed.dll', b'bad')], {'native-runtime/fixed.dll': (3, '0' * 64)})

    def test_missing_runtime_is_blocked(self):
        with self.assertRaises(ValueError): self.archive([], {'native-runtime/fixed.dll': (3, '0' * 64)})

    def test_case_collision_is_blocked(self):
        with self.assertRaises(ValueError): self.archive([('A.class', b'a'), ('a.class', b'b')], {})

    def test_traversal_is_blocked(self):
        with self.assertRaises(ValueError): self.archive([('../bad', b'a')], {})

    def test_unrequested_gba_runtime_is_not_a_fixed_resource(self):
        entries, _ = self.archive([('native-runtime/piq-gba/runtime/mgba_libretro.dll', b'fake')], {})
        self.assertIn('native-runtime/piq-gba/runtime/mgba_libretro.dll', entries)
        # It is left as an ordinary entry so overlay rejects the unapproved addition.


if __name__ == '__main__': unittest.main(verbosity=2)
