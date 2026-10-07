import copy
import hashlib
import json
from pathlib import Path
import re
import subprocess
import tempfile
import unittest
from unittest import mock
import zipfile

import snapshot_release as release
from release_artifacts import PROFILES, sha256


COMMIT = 'a' * 40
ENV = dict(GITHUB_REF='refs/heads/main', GITHUB_EVENT_NAME='push',
           GITHUB_REPOSITORY='example/game-console', GITHUB_SHA=COMMIT,
           GITHUB_RUN_ID='12345', GITHUB_RUN_ATTEMPT='2')


class ContextTests(unittest.TestCase):
    def test_unique_tag_has_run_attempt_and_exact_source_identity(self):
        self.assertEqual('snapshot-12345-2-' + 'a' * 12, release.context(ENV)['tag'])
        self.assertNotEqual(release.context(ENV)['tag'],
                            release.context(dict(ENV, GITHUB_RUN_ATTEMPT='3'))['tag'])

    def test_manual_dispatch_is_still_main_only(self):
        release.context(dict(ENV, GITHUB_EVENT_NAME='workflow_dispatch'))
        for key, value in [('GITHUB_REF', 'refs/heads/feature'), ('GITHUB_REF', 'refs/tags/main'),
                           ('GITHUB_EVENT_NAME', 'pull_request_target'), ('GITHUB_SHA', 'main'),
                           ('GITHUB_RUN_ID', '../../123'), ('GITHUB_RUN_ATTEMPT', '0'),
                           ('GITHUB_REPOSITORY', 'example/repo/../../elsewhere')]:
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                release.context(dict(ENV, **{key: value}))


class CommandLineTests(unittest.TestCase):
    def invoke(self, env, validate_only=True):
        argv = ['snapshot_release.py', '--directory', 'snapshot-dist']
        if validate_only:
            argv.append('--validate-only')
        with mock.patch.dict(release.os.environ, env, clear=True), \
                mock.patch.object(release.sys, 'argv', argv), \
                mock.patch.object(release.subprocess, 'check_output', return_value=COMMIT + '\n'), \
                mock.patch.object(release, 'validate', return_value=[]) as validate, \
                mock.patch.object(release, 'GitHub') as api:
            release.main()
            api.assert_not_called()
            validate.assert_called_once_with(Path('snapshot-dist'), COMMIT)

    def test_local_readonly_check_needs_no_github_environment_or_token(self):
        self.invoke({})

    def test_actions_readonly_check_still_requires_main_event(self):
        self.invoke(dict(ENV, GITHUB_ACTIONS='true'))
        with self.assertRaisesRegex(ValueError, 'refs/heads/main'):
            self.invoke(dict(ENV, GITHUB_ACTIONS='true', GITHUB_REF='refs/heads/feature'))

    def test_local_publish_cannot_bypass_event_gate(self):
        with self.assertRaisesRegex(ValueError, 'refs/heads/main'):
            self.invoke({}, validate_only=False)

    def test_local_readonly_check_does_not_bypass_dirty_rejection(self):
        with mock.patch.dict(release.os.environ, {}, clear=True), \
                mock.patch.object(release.sys, 'argv', ['snapshot_release.py', '--directory', 'snapshot-dist', '--validate-only']), \
                mock.patch.object(release.subprocess, 'check_output', return_value=COMMIT), \
                mock.patch.object(release, 'validate', side_effect=ValueError('Dirty snapshot rejected')) as validate, \
                mock.patch.object(release, 'GitHub') as api:
            with self.assertRaisesRegex(ValueError, 'Dirty snapshot'):
                release.main()
            validate.assert_called_once_with(Path('snapshot-dist'), COMMIT)
            api.assert_not_called()


class ArtifactTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='snapshot-release-test-')
        self.addCleanup(self.tmp.cleanup)
        self.directory = Path(self.tmp.name)
        self.core_bytes = {ident: ('original fixture core ' + ident).encode() for ident in release.CORE_LOCATIONS}
        sources = []
        for ident, (_, _, platform, member) in release.CORE_LOCATIONS.items():
            raw = self.core_bytes[ident]
            sources.append(dict(id=ident, platform=platform, path=f'nightly/{platform}/{member}',
                                url=release.CORE_URLS[platform] + member + '.zip', bytes=len(raw),
                                sha256=hashlib.sha256(raw).hexdigest(), archiveSha256='d' * 64))
        self.manifest = dict(schema=1, commit=COMMIT, artifacts=[], minecraftTested=False, sourceDirty=False,
                             inputReceiptSha256='f' * 64, coreSources=sources)
        for mod_set in sorted(release.MOD_SETS, key=lambda value: sorted(value)):
            prefix, _, _ = PROFILES[mod_set]
            name = prefix + '-1.0-test.jar'
            path = self.directory / name
            with zipfile.ZipFile(path, 'w') as jar:
                metadata = '\n'.join('[[mods]]\nmodId="' + mod_id + '"\nversion="1.0-test"\n'
                                     for mod_id in sorted(mod_set))
                jar.writestr('META-INF/neoforge.mods.toml', metadata)
                jar.writestr('example.txt', 'original fixture, not a runtime binary')
                jar.writestr('META-INF/MANIFEST.MF', 'Manifest-Version: 1.0\n'
                             'Game-Console-Snapshot-Commit: ' + COMMIT + '\n\n')
                jar.writestr(release.STAMP, json.dumps({key: self.manifest[key] for key in
                             ('schema', 'commit', 'minecraftTested', 'sourceDirty', 'inputReceiptSha256', 'coreSources')}))
                for ident, (owner, resource, _, _) in release.CORE_LOCATIONS.items():
                    if owner in mod_set:
                        jar.writestr(resource, self.core_bytes[ident])
            self.manifest['artifacts'].append(dict(name=name, bytes=path.stat().st_size,
                                                   sha256=sha256(path), modIds=sorted(mod_set)))
        self.write_manifest()

    def write_manifest(self):
        path = self.directory / 'manifest.json'
        path.write_text(json.dumps(self.manifest), encoding='utf8')
        sums = {entry['name']: entry['sha256'] for entry in self.manifest['artifacts']}
        sums[path.name] = sha256(path)
        (self.directory / 'SHA256SUMS.txt').write_text(
            ''.join(f'{digest}  {name}\n' for name, digest in sorted(sums.items())), encoding='utf8')

    def rewrite_jar(self, index, change):
        entry = self.manifest['artifacts'][index]
        path = self.directory / entry['name']
        with zipfile.ZipFile(path) as jar:
            entries = {name: jar.read(name) for name in jar.namelist()}
        change(entries)
        with zipfile.ZipFile(path, 'w') as jar:
            for name, raw in entries.items():
                jar.writestr(name, raw)
        entry.update(bytes=path.stat().st_size, sha256=sha256(path))
        self.write_manifest()

    def test_exact_seven_complete_mods_pass(self):
        files = release.validate(self.directory, COMMIT)
        self.assertEqual(9, len(files))
        self.assertEqual(7, sum(entry['name'].endswith('.jar') for entry in files))

    def test_extra_unlisted_file_is_rejected(self):
        (self.directory / 'private.txt').write_text('should never publish', encoding='utf8')
        with self.assertRaisesRegex(ValueError, 'exactly seven'):
            release.validate(self.directory, COMMIT)

    def test_other_commit_and_unearned_validation_claim_rejected(self):
        for change in (dict(commit='b' * 40), dict(schema=2), dict(minecraftTested=True), dict(sourceDirty=True)):
            with self.subTest(change=change):
                original = self.manifest.copy()
                self.manifest.update(change)
                self.write_manifest()
                with self.assertRaises(ValueError):
                    release.validate(self.directory, COMMIT)
                self.manifest = original

    def test_manifest_hash_and_mod_id_mismatch_rejected(self):
        original = copy.deepcopy(self.manifest)
        for key, value in [('sha256', '0' * 64), ('bytes', 0), ('modIds', ['piq_flash_box'])]:
            with self.subTest(key=key):
                self.manifest = copy.deepcopy(original)
                self.manifest['artifacts'][0][key] = value
                self.write_manifest()
                with self.assertRaises(ValueError):
                    release.validate(self.directory, COMMIT)

    def test_manifest_path_traversal_rejected(self):
        self.manifest['artifacts'][0]['name'] = '../game-console-1.0.jar'
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, 'asset name'):
            release.validate(self.directory, COMMIT)

    def test_missing_jar_is_not_treated_as_optional(self):
        path = self.directory / self.manifest['artifacts'][0]['name']
        path.rename(self.directory / 'unlisted.txt')
        with self.assertRaises(ValueError):
            release.validate(self.directory, COMMIT)

    def test_checksums_must_include_manifest_and_no_duplicates(self):
        sums = self.directory / 'SHA256SUMS.txt'
        text = sums.read_text(encoding='utf8')
        for invalid in (text + text.splitlines()[0] + '\n',
                        '\n'.join(line for line in text.splitlines() if 'manifest.json' not in line)):
            sums.write_text(invalid, encoding='utf8')
            with self.assertRaises(ValueError):
                release.validate(self.directory, COMMIT)

    def test_symlink_rejected_without_platform_privilege(self):
        from unittest.mock import patch
        with patch.object(Path, 'is_symlink', return_value=True), self.assertRaises(ValueError):
            release.validate(self.directory, COMMIT)

    def test_older_jar_without_stamp_cannot_be_relabelled_snapshot(self):
        self.rewrite_jar(0, lambda entries: entries.pop(release.STAMP))
        with self.assertRaisesRegex(ValueError, 'embedded snapshot provenance'):
            release.validate(self.directory, COMMIT)

    def test_other_embedded_commit_is_rejected_even_with_new_jar_checksum(self):
        def change(entries):
            stamp = json.loads(entries[release.STAMP]); stamp['commit'] = 'b' * 40
            entries[release.STAMP] = json.dumps(stamp)
        self.rewrite_jar(0, change)
        with self.assertRaisesRegex(ValueError, 'inventory mismatch'):
            release.validate(self.directory, COMMIT)

    def test_other_embedded_receipt_or_core_inventory_rejected(self):
        for key, value in [('inputReceiptSha256', '0' * 64), ('coreSources', []), ('sourceDirty', True)]:
            def change(entries):
                stamp = json.loads(entries[release.STAMP]); stamp[key] = value
                entries[release.STAMP] = json.dumps(stamp)
            self.rewrite_jar(0, change)
            with self.assertRaisesRegex(ValueError, 'inventory mismatch'):
                release.validate(self.directory, COMMIT)

    def test_other_jar_manifest_commit_is_rejected(self):
        self.rewrite_jar(0, lambda entries: entries.update(
            {'META-INF/MANIFEST.MF': 'Game-Console-Snapshot-Commit: ' + 'b' * 40 + '\n'}))
        with self.assertRaisesRegex(ValueError, 'JAR manifest snapshot commit'):
            release.validate(self.directory, COMMIT)

    def test_changed_actual_core_fails_even_when_outer_jar_hashes_agree(self):
        index = next(i for i, item in enumerate(self.manifest['artifacts']) if item['modIds'] == ['piq_fc_arcade'])
        _, resource, _, _ = release.CORE_LOCATIONS['mesen-windows']
        self.rewrite_jar(index, lambda entries: entries.update({resource: b'x' * len(entries[resource])}))
        with self.assertRaisesRegex(ValueError, 'Packed nightly core SHA-256'):
            release.validate(self.directory, COMMIT)

    def test_foreign_core_source_url_is_rejected(self):
        self.manifest['coreSources'][0]['url'] = 'https://example.invalid/core.zip'
        self.write_manifest()
        with self.assertRaisesRegex(ValueError, 'official URL/path'):
            release.validate(self.directory, COMMIT)


class FakeAPI:
    def __init__(self, files):
        self.files = files; self.calls = []; self.uploads = []
        self.fail_at = None; self.tag_exists = False; self.release_exists = False
        self.bad_digest = False; self.wrong_tag_after_upload = False
        self.ref_created = False
        self.ctx = release.context(ENV)

    def request(self, method, path, body=None, missing_ok=False):
        self.calls.append((method, path, body))
        if method == 'GET' and path.startswith('/git/ref/tags/'):
            if self.wrong_tag_after_upload and self.uploads:
                return dict(object=dict(type='commit', sha='b' * 40))
            return dict(object=dict(type='commit', sha=COMMIT)) if self.tag_exists or self.ref_created else None
        if method == 'GET' and path.startswith('/releases/tags/'):
            return {'id': 99} if self.release_exists else None
        if method == 'GET' and path.startswith('/commits/'):
            return dict(sha=COMMIT)
        if method == 'POST' and path == '/releases':
            return dict(id=1, tag_name=self.ctx['tag'], draft=True, prerelease=True)
        if method == 'GET' and path.endswith('/assets?per_page=100'):
            return self.uploads
        if method == 'POST' and path == '/git/refs':
            self.ref_created = True
            return dict(object=dict(type='commit', sha=COMMIT))
        if method == 'PATCH' and path == '/releases/1':
            return dict(id=1, tag_name=self.ctx['tag'], draft=False, prerelease=True)
        raise AssertionError('Unexpected API operation: ' + method + ' ' + path)

    def upload(self, release_id, path, expected):
        if self.fail_at == len(self.uploads):
            raise RuntimeError('fixture upload failure')
        asset = dict(name=expected['name'], size=expected['bytes'], state='uploaded',
                     digest='sha256:' + ('0' * 64 if self.bad_digest else expected['sha256']))
        self.uploads.append(asset)
        return asset


class TransactionTests(unittest.TestCase):
    def setUp(self):
        self.files = [dict(name=f'fixture-{n}.jar', bytes=n + 1,
                           sha256=hashlib.sha256(str(n).encode()).hexdigest()) for n in range(9)]
        self.ctx = release.context(ENV)
        self.api = FakeAPI(self.files)

    def invoke(self):
        return release.publish(self.api, self.ctx, Path('unused'), self.files)

    def test_publish_only_after_all_verified_assets_and_commit_tag(self):
        self.invoke()
        methods = [(method, path) for method, path, _ in self.api.calls]
        self.assertEqual(9, len(self.api.uploads))
        self.assertLess(methods.index(('GET', '/releases/1/assets?per_page=100')),
                        methods.index(('POST', '/git/refs')))
        self.assertLess(methods.index(('POST', '/git/refs')), methods.index(('PATCH', '/releases/1')))
        create = next(body for method, path, body in self.api.calls if method == 'POST' and path == '/releases')
        self.assertIs(create['draft'], True)
        self.assertIs(create['prerelease'], True)
        self.assertEqual('false', create['make_latest'])
        self.assertEqual(COMMIT, create['target_commitish'])

    def test_failures_leave_draft_without_publication_or_delete(self):
        for failure in (0, 4, 8):
            with self.subTest(failure=failure):
                self.api = FakeAPI(self.files); self.api.fail_at = failure
                with self.assertRaises(RuntimeError):
                    self.invoke()
                self.assertFalse(any(method in {'PATCH', 'DELETE'} for method, _, _ in self.api.calls))
                self.assertFalse(self.api.ref_created)

    def test_existing_tags_and_releases_are_not_overwritten(self):
        for key in ('tag_exists', 'release_exists'):
            with self.subTest(key=key):
                self.api = FakeAPI(self.files); setattr(self.api, key, True)
                with self.assertRaises(ValueError):
                    self.invoke()
                self.assertTrue(all(method == 'GET' for method, _, _ in self.api.calls))

    def test_bad_remote_digest_keeps_draft(self):
        self.api.bad_digest = True
        with self.assertRaises(ValueError):
            self.invoke()
        self.assertFalse(any(method == 'PATCH' for method, _, _ in self.api.calls))

    def test_concurrent_wrong_tag_keeps_draft(self):
        self.api.wrong_tag_after_upload = True
        with self.assertRaisesRegex(ValueError, 'unexpected target'):
            self.invoke()
        self.assertFalse(any(method == 'PATCH' for method, _, _ in self.api.calls))


class WorkflowTests(unittest.TestCase):
    def setUp(self):
        self.root = Path(__file__).resolve().parents[1]
        self.text = (self.root / '.github/workflows/snapshot.yml').read_text(encoding='utf8')

    def test_only_main_events_no_pr_or_workflow_run_token_escalation(self):
        self.assertIn('branches: [main]', self.text)
        self.assertIn('workflow_dispatch:', self.text)
        self.assertEqual(2, self.text.count("github.ref == 'refs/heads/main'"))
        self.assertNotRegex(self.text, r'(?m)^\s*(?:pull_request|pull_request_target|workflow_run):')
        self.assertNotRegex(self.text, r'(?m)^concurrency:')

    def test_pinned_actions_and_separated_write_permission(self):
        references = re.findall(r'uses:\s*(\S+)', self.text)
        self.assertEqual(7, len(references))
        self.assertTrue(all(re.fullmatch(r'actions/[a-z-]+@[0-9a-f]{40}', ref) for ref in references))
        build, publish = self.text.split('  publish:', 1)
        self.assertNotIn('contents: write', build)
        self.assertNotIn('GH_TOKEN:', build)
        self.assertIn('contents: write', publish)
        self.assertIn('needs: build', publish)
        self.assertIn('cancel-in-progress: false', publish)
        self.assertNotIn('cancel-in-progress: true', publish)
        self.assertEqual(2, self.text.count('persist-credentials: false'))
        self.assertNotIn('cache:', self.text)

    def test_artifact_allowlist_does_not_upload_workspace(self):
        self.assertIn('snapshot-dist/*.jar', self.text)
        self.assertIn('snapshot-dist/manifest.json', self.text)
        self.assertIn('snapshot-dist/SHA256SUMS.txt', self.text)
        self.assertIn('include-hidden-files: false', self.text)
        self.assertIn('if-no-files-found: error', self.text)
        self.assertNotIn('snapshot-dist/**', self.text)

    def test_gitignore_opens_only_one_workflow(self):
        with tempfile.TemporaryDirectory(prefix='snapshot-ignore-test-') as name:
            root = Path(name)
            subprocess.run(['git', 'init', '-q', str(root)], check=True, capture_output=True)
            (root / '.gitignore').write_bytes((self.root / '.gitignore').read_bytes())
            for relative, ignored in [('.github/workflows/snapshot.yml', False),
                                      ('.github/workflows/other.yml', True),
                                      ('.github/private.txt', True),
                                      ('.github/actions/local/action.yml', True)]:
                path = root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text('fixture', encoding='utf8')
                result = subprocess.run(['git', '-C', str(root), 'check-ignore', '-q', relative])
                self.assertEqual(0 if ignored else 1, result.returncode, relative)


if __name__ == '__main__':
    unittest.main()
