"""Exercise real Git index + hook in a new isolated repository, never user HEAD."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


class GitGuardIntegration(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.repo = Path(tempfile.mkdtemp(prefix='piq-git-guard-')).resolve()
        (cls.repo / 'source-control/hooks').mkdir(parents=True)
        origin = Path(__file__).resolve().parent
        for name in ('audit.py', 'setup.py', 'hooks/pre-commit'):
            shutil.copyfile(origin / name, cls.repo / 'source-control' / name)
        cls.git('init', '-b', 'main')
        subprocess.run([sys.executable, str(cls.repo/'source-control/setup.py'), '--name',
                        'Git Guard Fixture', '--email', 'fixture@local.invalid'], check=True,
                       capture_output=True)

    @classmethod
    def git(cls, *args):
        return subprocess.run(['git', '-C', str(cls.repo), *args], capture_output=True)

    def test_git_world_ignore_is_scoped(self):
        shutil.copyfile(Path(__file__).resolve().parents[1]/'.gitignore', self.repo/'.gitignore')
        source='piq-fc-arcade/src/main/java/cn/piq/fcarcade/world/FcArcadeBlock.java'
        path=self.repo/source; path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text('// source fixture\n',encoding='utf8')
        self.assertEqual(self.git('check-ignore','--no-index',source).returncode,1)
        self.assertEqual(self.git('check-ignore','--no-index','piq-fc-arcade/world/playerdata.json').returncode,0)
        self.assertEqual(self.git('check-ignore','--no-index','piq-fc-arcade/run/world/level.dat').returncode,0)

    def test_real_hook_and_index(self):
        self.assertEqual(self.git('add', 'source-control').returncode, 0)
        # Force staging credential-looking data: the real hook must reject it.
        suspect = self.repo / 'source-control/secrets.json'
        suspect.write_text('{}', encoding='utf8')
        self.assertEqual(self.git('add', '-f', 'source-control/secrets.json').returncode, 0)
        denied = self.git('commit', '-m', 'fixture must be rejected')
        self.assertNotEqual(denied.returncode, 0)
        self.assertIn(b'credential-file', denied.stdout + denied.stderr)
        self.assertNotEqual(self.git('rev-parse', '--verify', 'HEAD').returncode, 0)
        self.assertEqual(self.git('rm', '--cached', 'source-control/secrets.json').returncode, 0)
        # Check the staged blob, not merely a now-safe working file.
        candidate = self.repo / 'source-control/candidate.txt'
        candidate.write_bytes(b'password = "' + b'q7V2m4Z8p9R3k6T1' + b'"\n')
        self.assertEqual(self.git('add', 'source-control/candidate.txt').returncode, 0)
        candidate.write_text('safe working copy\n', encoding='utf8')
        denied = self.git('commit', '-m', 'staged fixture must be rejected')
        self.assertNotEqual(denied.returncode, 0)
        self.assertIn(b'possible-secret-literal', denied.stdout + denied.stderr)
        self.assertNotEqual(self.git('rev-parse', '--verify', 'HEAD').returncode, 0)
        self.assertEqual(self.git('add', 'source-control/candidate.txt').returncode, 0)
        accepted = self.git('commit', '-m', 'safe isolated guard fixture')
        self.assertEqual(accepted.returncode, 0, accepted.stderr.decode(errors='replace'))
        self.assertEqual(self.git('rev-parse', '--verify', 'HEAD').returncode, 0)
        checked = subprocess.run([sys.executable, str(self.repo/'source-control/audit.py'),
                                  '--staged'], capture_output=True, check=True)
        self.assertTrue(json.loads(checked.stdout)['ok'])
        # Retain this tiny fixture for inspection; no cleanup/deletion of user paths.
        print('Isolated hook fixture:', self.repo)


if __name__ == '__main__': unittest.main()
