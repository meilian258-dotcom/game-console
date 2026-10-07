"""Static policy tests for the isolated candidate workflow, not a cloud run."""
from pathlib import Path
import re
import unittest


class CandidateWorkflowPolicyTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.root = Path(__file__).resolve().parents[1]
        cls.text = (cls.root / '.github/workflows/mame-lifecycle-candidate.yml').read_text(encoding='utf8')

    def test_only_the_repair_branch_push_can_trigger(self):
        trigger = self.text.split('on:\n', 1)[1].split('\npermissions:', 1)[0]
        self.assertIn('branches: [codex/arcade-jni-crash-latency]', trigger)
        for event in ('pull_request', 'workflow_dispatch', 'workflow_run', 'schedule:', 'branches: [main]'):
            self.assertNotIn(event, trigger)
        self.assertEqual(2, self.text.count("if: github.ref == 'refs/heads/codex/arcade-jni-crash-latency'"))

    def test_no_publish_or_credential_capability(self):
        self.assertIn('permissions:\n  contents: read\n', self.text)
        self.assertEqual(2, self.text.count('persist-credentials: false'))
        self.assertEqual(2, self.text.count('submodules: false'))
        for forbidden in ('contents: write', 'id-token: write', 'secrets.', 'github.token',
                          'GH_TOKEN', 'self-hosted', 'actions/cache', 'snapshot_release.py',
                          'gh release', 'git push', 'publish:'):
            self.assertNotIn(forbidden, self.text)

    def test_all_actions_are_immutable_reviewed_pins(self):
        known = {
            'actions/checkout': '34e114876b0b11c390a56381ad16ebd13914f8d5',
            'actions/setup-python': 'a26af69be951a213d495a4c3e4e4022e16d87065',
            'actions/upload-artifact': 'ea165f8d65b6e75b540449e92b4886f43607fa02',
            'actions/download-artifact': 'd3f86a106a0bac45b974a628896c90dbdf5c8093',
        }
        uses = re.findall(r'uses: ([^\s@]+)@([^\s]+)', self.text)
        self.assertEqual(8, len(uses))
        for action, revision in uses:
            self.assertEqual(known[action], revision)

    def test_clean_complete_build_and_separate_validation_budget(self):
        self.assertEqual(2, self.text.count('runs-on: windows-2022'))
        self.assertIn('timeout-minutes: 360', self.text)
        self.assertIn('timeout-minutes: 60', self.text)
        self.assertIn('timeout-minutes: 45', self.text)
        self.assertIn('--ci-clean --jobs 4', self.text)
        self.assertIn('needs: build', self.text)
        self.assertIn("needs.build.result == 'success'", self.text)
        self.assertEqual(2, self.text.count('git config --global core.autocrlf input'))
        for unsafe in ('--resume', 'SOURCES=', 'setup-msys2', 'pacman -S', 'Remove-Item'):
            self.assertNotIn(unsafe, self.text)

    def test_file_transfers_are_allowlisted(self):
        paths = re.findall(r'^            (\$\{\{ runner.temp \}\}/[^\n]+)$', self.text, re.M)
        self.assertEqual(17, len(paths))
        self.assertTrue(all('/**' not in p and '/staging' not in p and '/inputs/' not in p for p in paths))
        candidate = [p.rsplit('/', 1)[-1] for p in paths if '/candidate/' in p]
        self.assertEqual(['core.dll', 'candidate-source.zip', 'candidate.json'], candidate)
        receipts = [p for p in paths if '/mame-verification/' in p]
        self.assertEqual(2, len(receipts))
        self.assertIn('ci_artifacts.py verify --directory', self.text)
        self.assertIn('if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }', self.text)
        self.assertIn('verify_candidate.py --core', self.text)
        self.assertEqual(2, self.text.count('name: mame-candidate-${{ github.run_id }}-${{ github.run_attempt }}'))


if __name__ == '__main__':
    unittest.main()
