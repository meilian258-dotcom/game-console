import unittest
from pathlib import Path
import subprocess
import tempfile
from unittest.mock import patch
import audit as guard
from audit import path_issues, content_issues

class GuardTests(unittest.TestCase):
    def test_source_scope(self):
        self.assertEqual([],path_issues('README.md'))
        self.assertEqual([],path_issues('piq-fc-arcade/src/main/java/cn/piq/Test.java'))
        self.assertEqual([],path_issues('piq-md-home/src/main/resources/assets/piq_md_home/models/block/md2_empty.json'))
        self.assertIn('outside-source-scope',path_issues('piq-server-assistant/server.py'))
    def test_only_reviewed_workflow_is_allowed(self):
        self.assertEqual([], path_issues('.github/workflows/snapshot.yml'))
        for name in ('.github/workflows/another.yml', '.github/actions/run/action.yml',
                     '.github/workflows/snapshot.yml/hidden.txt', '.github/secret.json'):
            self.assertIn('outside-source-scope', path_issues(name))
    def test_credentials(self):
        for name in ['.env','登录信息.txt','keys/server.pem','private/config.json']:
            self.assertTrue(path_issues('piq-gba/'+name),name)
        self.assertIn('private-key',content_issues('sample.txt',b'-----BEGIN '+b'PRIVATE KEY-----'))
        self.assertIn('possible-secret-literal',content_issues('sample.txt',b'password = '+b'"a123456789abcdef"'))
    def test_no_false_positive_variable(self):
        self.assertEqual([],content_issues('sample.java',b'var password = prompt();'))
        self.assertEqual([],content_issues('sample.py',b'password = "test-placeholder"'))
    def test_no_build_rom_saves(self):
        for name in ['build/libs/mod.jar','run/world/level.dat','game-console/save.json','tools/game.nes','src/main/resources/pvz/main.pak']:
            self.assertTrue(path_issues('piq-fc-arcade/'+name),name)
    def test_wrapper_exception(self):
        name='piq-fc-arcade/gradle/wrapper/gradle-wrapper.jar'
        self.assertEqual([],path_issues(name));self.assertEqual([],content_issues(name,b'PK\x03\x04'))
        self.assertTrue(path_issues('piq-gba/arbitrary.jar'))
    def test_mislabeled_executable(self):
        self.assertIn('executable-magic',content_issues('piq-gba/innocent.txt',b'MZ'+b'\0'*60))
        self.assertIn('rom-magic',content_issues('piq-gba/innocent.txt',b'NES\x1a'+b'\0'*60))
    def test_size_and_paths(self):
        self.assertTrue(path_issues('../secret.txt'))
        self.assertTrue(path_issues('piq-gba/../../secret.txt'))
        self.assertIn('over-10MiB-review-required',content_issues('big.txt',b' '* (10*1024*1024+1)))
    def test_no_game_captures(self):
        self.assertTrue(path_issues('piq-fc-arcade/design/zapper-private/frame-0001.png'))
        self.assertTrue(path_issues('piq-gba/design/frame-0123.png'))
    def test_world_java_package_is_source_not_save_directory(self):
        self.assertEqual([],path_issues('piq-fc-arcade/src/main/java/cn/piq/fcarcade/world/FcArcadeBlock.java'))
        self.assertEqual([],path_issues('piq-fc-arcade/src/test/java/cn/piq/fcarcade/world/FcArcadeBlockTest.java'))
        self.assertTrue(path_issues('piq-fc-arcade/world/playerdata.json'))
        self.assertTrue(path_issues('piq-fc-arcade/run/world/level.dat'))

class SubmoduleGuardTests(unittest.TestCase):
    module = 'piq-pvz-addon/vendor/PvZ-Portable'
    url = 'https://github.com/KLuoNuoYa/PvZ-Portable.git'
    missing_oid = b'1234567890abcdef1234567890abcdef12345678'

    def setUp(self):
        self.repo = Path(tempfile.mkdtemp(prefix='piq-submodule-guard-')).resolve()
        self.git('init', '-b', 'main')
        self.config = ('[submodule "' + self.module + '"]\n'
                       '\tpath = ' + self.module + '\n'
                       '\turl = ' + self.url + '\n'
                       '\tbranch = libretro\n').encode('utf8')
        self.write_config(self.config)
        self.git('add', '.gitmodules')
        self.pin(self.missing_oid)
        self.root_patch = patch.object(guard, 'ROOT', self.repo)
        self.root_patch.start()
        self.addCleanup(self.root_patch.stop)
        # Retain tiny isolated repositories for inspection, never mutate user HEAD.

    def git(self, *args, data=None, at=None, check=True):
        return subprocess.run(['git', '-C', str(at or self.repo), *args], input=data,
                              capture_output=True, check=check)

    def write_config(self, data):
        (self.repo / '.gitmodules').write_bytes(data)

    def pin(self, oid, name=None):
        self.git('update-index', '--add', '--cacheinfo', '160000',
                 oid.decode('ascii'), name or self.module)

    def rules(self, staged=True):
        return {rule for item in guard.audit(staged)['findings'] for rule in item['rules']}

    def initialize(self):
        child = self.repo / self.module
        child.mkdir(parents=True)
        self.git('init', '-b', 'libretro', at=child)
        (child / 'README.md').write_text('isolated upstream fixture\n', encoding='utf8')
        self.git('add', 'README.md', at=child)
        self.git('-c', 'user.name=Guard Fixture', '-c', 'user.email=fixture@local.invalid',
                 '-c', 'core.hooksPath=', 'commit', '-m', 'fixture', at=child)
        oid = self.git('rev-parse', 'HEAD', at=child).stdout.strip()
        self.pin(oid)
        return child, oid

    def test_registered_gitlink_does_not_need_commit_object_in_parent(self):
        self.assertNotEqual(0, self.git('cat-file', '-e', self.missing_oid.decode(), check=False).returncode)
        result = guard.audit(True)
        self.assertTrue(result['ok'], result['findings'])
        self.assertEqual(1, result['count'])  # .gitmodules only, no claim to audit upstream files.
        self.assertEqual(self.missing_oid.decode(), result['submodules'][0]['commit'])
        self.assertEqual('not-inspected', result['submodules'][0]['checkout'])
        self.assertIn('not recursively audited', result['submodules'][0]['auditScope'])

    def test_uninitialized_missing_and_empty_checkout_are_explicit(self):
        for present in (False, True):
            if present: (self.repo / self.module).mkdir(parents=True)
            result = guard.audit(False)
            self.assertTrue(result['ok'], result['findings'])
            self.assertEqual('uninitialized', result['submodules'][0]['checkout'])

    def test_index_and_worktree_use_their_own_gitmodules(self):
        self.write_config(self.config.replace(b'branch = libretro', b'branch = other'))
        self.assertTrue(guard.audit(True)['ok'])
        self.assertIn('unapproved-submodule-config', self.rules(False))
        self.git('add', '.gitmodules')
        self.write_config(self.config)
        self.assertIn('unapproved-submodule-config', self.rules(True))
        self.assertTrue(guard.audit(False)['ok'])

    def test_reject_unapproved_config_without_running_or_following_it(self):
        cases = [
            self.config.replace(self.url.encode(), b'https://example.invalid/core.git'),
            self.config.replace(b'branch = libretro', b'branch = main'),
            self.config.replace(b'\tpath = ' + self.module.encode(), b'\tpath = ../../elsewhere'),
            self.config + b'\tupdate = !never-execute-this\n',
            self.config + b'\tignore = all\n',
            self.config + b'\tbranch = libretro\n',
            self.config + b'[include]\n\tpath = missing-private-config\n',
            self.config + b'[submodule "unapproved"]\n\tpath = another\n',
            self.config.replace(b'\tbranch = libretro\n', b''),
            b'[malformed\n',
            b'\xff',
        ]
        for config in cases:
            with self.subTest(config=config):
                self.write_config(config)
                self.git('add', '.gitmodules')
                self.assertFalse(guard.audit(True)['ok'])
                self.assertFalse(guard.audit(False)['ok'])

    def test_ordinary_git_quoted_values_are_supported(self):
        self.write_config(self.config.replace(b'branch = libretro', b'branch = "libretro"'))
        self.git('add', '.gitmodules')
        self.assertTrue(guard.audit(True)['ok'])

    def test_missing_gitmodules_rejects_gitlink(self):
        self.git('update-index', '--force-remove', '.gitmodules')
        self.assertIn('missing-gitmodules', self.rules())
        self.assertIn('gitlink-without-approved-config', self.rules())

    def test_unknown_gitlink_rejected(self):
        self.pin(self.missing_oid, 'piq-gba/vendor/unapproved')
        self.assertIn('unapproved-gitlink', self.rules())

    def test_declared_path_must_be_gitlink_not_regular_blob(self):
        self.git('update-index', '--force-remove', self.module)
        oid = self.git('hash-object', '-w', '--stdin', data=b'not a submodule\n').stdout.strip()
        self.git('update-index', '--add', '--cacheinfo', '100644', oid.decode(), self.module)
        self.assertIn('approved-submodule-path-not-gitlink', self.rules())
        self.assertIn('declared-submodule-not-gitlink', self.rules())
        self.git('update-index', '--force-remove', '.gitmodules')
        self.assertIn('approved-submodule-path-not-gitlink', self.rules())

    def test_unmerged_gitlink_is_rejected(self):
        self.git('update-index', '--force-remove', self.module)
        line = b'160000 ' + self.missing_oid + b' 2\t' + self.module.encode() + b'\n'
        self.git('update-index', '--index-info', data=line)
        self.assertIn('non-regular-or-unmerged', self.rules())
        self.assertIn('non-regular-or-unmerged', self.rules(False))

    def test_unmerged_gitmodules_is_rejected(self):
        oid = self.git('rev-parse', ':.gitmodules').stdout.strip()
        self.git('update-index', '--force-remove', '.gitmodules')
        self.git('update-index', '--index-info', data=b'100644 ' + oid + b' 2\t.gitmodules\n')
        self.assertIn('non-regular-or-unmerged', self.rules())
        self.assertIn('non-regular-or-unmerged', self.rules(False))

    def test_initialized_checkout_pin_and_dirty_state(self):
        child, _ = self.initialize()
        result = guard.audit(False)
        self.assertTrue(result['ok'], result['findings'])
        self.assertEqual('initialized', result['submodules'][0]['checkout'])
        (child / 'README.md').write_text('modified source\n', encoding='utf8')
        self.assertIn('submodule-dirty-checkout', self.rules(False))
        self.assertTrue(guard.audit(True)['ok'])  # The staged pin remains unchanged.
        self.pin(self.missing_oid)
        self.assertIn('submodule-checkout-pin-mismatch', self.rules(False))

    def test_untracked_child_file_is_dirty(self):
        child, _ = self.initialize()
        (child / 'new-source.txt').write_text('untracked\n', encoding='utf8')
        self.assertIn('submodule-dirty-checkout', self.rules(False))

    def test_replaced_checkout_directory_and_file_are_rejected(self):
        target = self.repo / self.module
        target.parent.mkdir(parents=True)
        target.write_text('replaced directory\n', encoding='utf8')
        self.assertIn('submodule-path-not-directory', self.rules(False))
        target.rename(target.with_name('retained-replacement'))
        target.mkdir()
        (target / 'README.md').write_text('not initialized source\n', encoding='utf8')
        self.assertIn('submodule-directory-not-checkout', self.rules(False))

    def test_linked_checkout_is_rejected(self):
        target = self.repo / self.module
        target.parent.mkdir(parents=True)
        real = self.repo / 'real-child'
        real.mkdir()
        try:
            target.symlink_to(real, target_is_directory=True)
        except OSError:
            self.skipTest('Windows symlink privilege unavailable')
        self.assertIn('submodule-path-is-link', self.rules(False))

    def test_link_rejection_branch_without_os_symlink_privilege(self):
        with patch.object(Path, 'is_symlink', return_value=True):
            state, rules = guard.working_submodule(self.module, self.missing_oid)
        self.assertEqual('invalid', state)
        self.assertIn('submodule-path-is-link', rules)

    def test_regular_staged_secret_checks_still_apply(self):
        target = self.repo / 'source-control' / 'sample.txt'
        target.parent.mkdir()
        target.write_bytes(b'password = "' + b'q7V2m4Z8p9R3k6T1' + b'"\n')
        self.git('add', 'source-control/sample.txt')
        target.write_text('now safe worktree\n', encoding='utf8')
        self.assertIn('possible-secret-literal', self.rules())


if __name__=='__main__':unittest.main()
