import copy
from pathlib import Path
import tempfile
import tomllib
import unittest
import zipfile

from neoforge_compat import (META, PLAYER_IDS, load_policy, read_metadata, render_metadata,
                             revision, validate_bundle_metadata, validate_compile_version)

ROOT = Path(__file__).resolve().parents[1]
MODULES = ('piq-fc-arcade', 'piq-sfc-home', 'piq-md-home', 'piq-native-arcade',
           'piq-computer', 'piq-pvz-addon')


class NeoForgeCompatibilityTests(unittest.TestCase):
    def setUp(self):
        self.policy = load_policy()

    def bundle(self):
        documents = []
        for owner in sorted(PLAYER_IDS - {'piq_sfc_arcade'}):
            owners = [owner, 'piq_sfc_arcade'] if owner == 'piq_sfc_home' else [owner]
            documents.append(dict(mods=[dict(modId=o, version='1.0') for o in owners], dependencies={
                o: [dict(modId='neoforge', type='required', side='BOTH',
                         versionRange=f"[{self.policy['neo_min_version']},22)"),
                    dict(modId='minecraft', type='required', side='BOTH',
                         versionRange='[1.21.1,1.21.2)')] for o in owners}))
        return documents

    def test_policy_separates_default_compile_from_candidate_floor(self):
        self.assertEqual('1.21.1', self.policy['minecraft_version'])
        self.assertEqual('21.1.236', self.policy['neo_version'])
        self.assertLessEqual(revision(self.policy['neo_min_version']), revision(self.policy['neo_version']))
        before = dict(self.policy)
        for target in (self.policy['neo_min_version'], '21.1.236', '21.1.250'):
            self.assertEqual(target, validate_compile_version(self.policy, target))
        self.assertEqual(before, self.policy)

    def test_rejects_other_mc_lines_prereleases_and_targets_below_floor(self):
        below = '21.1.' + str(revision(self.policy['neo_min_version']) - 1)
        for target in (below, '21.0.236', '21.2.1', '22.1.1', '21.1.229-beta', '21.1.0229'):
            with self.subTest(target=target), self.assertRaises(ValueError):
                validate_compile_version(self.policy, target)

    def test_policy_rejects_duplicate_or_unknown_values(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'policy.properties'
            good = '\n'.join(k + '=' + v for k, v in self.policy.items())
            for bad in (good + '\nneo_min_version=21.1.1', good + '\nunknown=value',
                        good.replace('minecraft_version=1.21.1', 'minecraft_version=1.21.2')):
                path.write_text(bad, encoding='utf8')
                with self.assertRaises(ValueError):
                    load_policy(path)

    def test_gradle_modules_use_shared_compile_and_independent_metadata_floor(self):
        for module in MODULES:
            with self.subTest(module=module):
                build = (ROOT / module / 'build.gradle').read_text('utf8')
                self.assertIn("apply from: '../source-control/neoforge-compat.gradle'", build)
                self.assertIn('version = neo_version', build)
                self.assertIn('neo_min_version', build)
                template = ROOT / module / 'src/main/templates' / META
                if not template.exists():
                    template = ROOT / module / 'src/main/resources' / META
                text = template.read_text('utf8')
                self.assertIn('[${neo_min_version},22)', text)
                self.assertNotIn('${neo_version}', text)
        self.assertNotIn('neo_version=', (ROOT / 'piq-fc-arcade/gradle.properties').read_text('utf8'))

    def test_gba_metadata_is_expanded_and_unknown_placeholders_rejected(self):
        raw = (ROOT / 'piq-gba/src/main/resources' / META).read_bytes()
        rendered = render_metadata(raw, self.policy)
        self.assertNotIn(b'${', rendered)
        self.assertEqual('0.1.0-alpha.15', tomllib.loads(rendered.decode())['mods'][0]['version'])
        with self.assertRaises(ValueError):
            render_metadata(raw + b'\n# ${unknown}', self.policy)
        with self.assertRaises(ValueError):
            render_metadata(rendered, self.policy)
        builder = (ROOT / 'piq-gba/tools/build_current.py').read_text('utf8')
        self.assertIn('render_metadata(entries[', builder)
        self.assertIn('[POLICY,a.fc,a.runtime_base', builder)

    def test_complete_sfc_checks_both_dependency_owners(self):
        bundle = self.bundle()
        result = validate_bundle_metadata(bundle, self.policy)
        self.assertEqual(8, result['modIdCount'])
        self.assertFalse(result['runtimeCompatibilityVerified'])
        changed = copy.deepcopy(bundle)
        sfc = next(d for d in changed if 'piq_sfc_arcade' in d['dependencies'])
        sfc['dependencies']['piq_sfc_arcade'][0]['versionRange'] = '[21.1.236,)'
        # If the floor is deliberately restored to 236, still exercise a wrong frozen floor.
        if self.policy['neo_min_version'] == '21.1.236':
            sfc['dependencies']['piq_sfc_arcade'][0]['versionRange'] = '[21.1.235,)'
        with self.assertRaisesRegex(ValueError, 'piq_sfc_arcade'):
            validate_bundle_metadata(changed, self.policy)

    def test_rejects_missing_duplicate_and_foreign_bundle_owners(self):
        original = self.bundle()
        cases = [original[:-1], original + [original[0]], original[:-1] + [original[0]]]
        foreign = copy.deepcopy(original)
        foreign[0]['dependencies']['unknown'] = []
        cases.append(foreign)
        missing = copy.deepcopy(original)
        missing[0]['dependencies'].clear()
        cases.append(missing)
        for case in cases:
            with self.assertRaises(ValueError):
                validate_bundle_metadata(case, self.policy)

    def test_rejects_unexpanded_final_jar_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'candidate.jar'
            with zipfile.ZipFile(path, 'x') as archive:
                archive.writestr(META, 'value="${neo_min_version}"')
            with self.assertRaisesRegex(ValueError, 'Unexpanded'):
                read_metadata(path)

    def test_game_dependency_floors_match_required_public_api_generations(self):
        expected = {
            # Shared launch protocol / legacy-save adapter require FC 76.41.
            'piq-sfc-home': '[0.31.0-alpha.76.41,0.31.0-alpha.77)',
            # Unified content paths / file-backed JNI content require FC 76.40.
            'piq-sfc-arcade': '[0.31.0-alpha.76.40,0.31.0-alpha.77)',
            'piq-md-home': '[0.31.0-alpha.76.36,0.31.0-alpha.77)',
            # JNI diagnostics and cabinet adapter require FC 76.45.
            'piq-native-arcade': '[0.31.0-alpha.76.45,0.31.0-alpha.77)',
            'piq-gba': '[0.31.0-alpha.76.31,0.31.0-alpha.77)',
            'piq-computer': '[0.31.0-alpha.76.26,0.31.0-alpha.77)',
            'piq-pvz-addon': '[0.31.0-alpha.76.22,0.31.0-alpha.77)',
        }
        for module, required_fc in expected.items():
            template = ROOT / module / 'src/main/templates' / META
            if not template.exists():
                template = ROOT / module / 'src/main/resources' / META
            text = template.read_text('utf8')
            if module == 'piq-sfc-arcade':
                self.assertIn('mod_id=piq_sfc_arcade',
                              (ROOT / module / 'gradle.properties').read_text('utf8'))
                text = text.replace('${mod_id}', 'piq_sfc_arcade')
            data = tomllib.loads(text)
            owner = data['mods'][0]['modId']
            deps = {d['modId']: d for d in data['dependencies'][owner]}
            self.assertEqual(required_fc, deps['piq_fc_arcade']['versionRange'])
            if owner == 'piq_computer':
                self.assertEqual('optional', deps['piq_pvz']['type'])
                self.assertEqual('[0.1.0-prototype.12,0.1.0-prototype.13)', deps['piq_pvz']['versionRange'])
            if owner == 'piq_sfc_home':
                self.assertEqual('[0.2.0-alpha.11,0.3.0)', deps['piq_sfc_arcade']['versionRange'])


if __name__ == '__main__':
    unittest.main()
