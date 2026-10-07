"""Publish one verified seven-mod snapshot; never replace an existing tag or release.

The build job only calls --validate-only. A separate GitHub-hosted job supplies
its short-lived contents:write token for the final draft/upload/verify/publish
transaction. Build artifacts are data, never imported or executed here.
"""
from __future__ import annotations

import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import urllib.parse
import zipfile

from release_artifacts import inspect, sha256

MOD_SETS = {
    frozenset({'piq_fc_arcade'}), frozenset({'piq_sfc_home', 'piq_sfc_arcade'}),
    frozenset({'piq_md_home'}), frozenset({'piq_gba'}),
    frozenset({'piq_native_arcade'}), frozenset({'piq_computer'}), frozenset({'piq_pvz'}),
}
METADATA = {'manifest.json', 'SHA256SUMS.txt'}
STAMP = 'META-INF/game-console/snapshot-cores.json'
CORE_LOCATIONS = {
    'mesen-windows': ('piq_fc_arcade', 'core/libretro/windows-x64/mesen_libretro.dll',
                      'windows-x64', 'mesen_libretro.dll'),
    'mesen-linux': ('piq_fc_arcade', 'core/libretro/linux-x64/mesen_libretro.so',
                    'linux-x64', 'mesen_libretro.so'),
    'mesen-s-windows': ('piq_sfc_home', 'core/sfc-libretro/windows-x64/mesen-s_libretro.dll',
                        'windows-x64', 'mesen-s_libretro.dll'),
    'genesis-plus-gx-windows': ('piq_md_home', 'core/windows-x64/genesis_plus_gx_libretro.dll',
                               'windows-x64', 'genesis_plus_gx_libretro.dll'),
    'mgba-windows': ('piq_gba', 'native-runtime/win-x64-v1/piq-gba/runtime/mgba_libretro.dll',
                     'windows-x64', 'mgba_libretro.dll'),
}
CORE_URLS = {
    'windows-x64': 'https://buildbot.libretro.com/nightly/windows/x86_64/latest/',
    'linux-x64': 'https://buildbot.libretro.com/nightly/linux/x86_64/latest/',
}
MAX_ASSET_BYTES = 1024 * 1024 * 1024
MAX_METADATA_BYTES = 4 * 1024 * 1024
HEX40 = re.compile(r'[0-9a-f]{40}')
HEX64 = re.compile(r'[0-9a-f]{64}')


def context(env: dict) -> dict:
    if env.get('GITHUB_REF') != 'refs/heads/main':
        raise ValueError('Snapshots may only run from refs/heads/main')
    if env.get('GITHUB_EVENT_NAME') not in {'push', 'workflow_dispatch'}:
        raise ValueError('Unsupported snapshot event')
    repository = env.get('GITHUB_REPOSITORY', '')
    commit = env.get('GITHUB_SHA', '')
    run_id, attempt = env.get('GITHUB_RUN_ID', ''), env.get('GITHUB_RUN_ATTEMPT', '')
    if not re.fullmatch(r'[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+', repository):
        raise ValueError('Invalid repository identity')
    if not HEX40.fullmatch(commit) or any(not re.fullmatch(r'[1-9][0-9]{0,19}', v)
                                         for v in (run_id, attempt)):
        raise ValueError('Invalid commit or run identity')
    return dict(repository=repository, commit=commit, run_id=run_id, attempt=attempt,
                tag=f'snapshot-{run_id}-{attempt}-{commit[:12]}')


def validate(directory: Path, commit: str) -> list[dict]:
    """Independently check the flat allowlist, IDs, canonical names and hashes."""
    if not HEX40.fullmatch(commit):
        raise ValueError('Expected a full commit SHA')
    if directory.is_symlink() or not directory.is_dir():
        raise ValueError('Expected a regular artifact directory')
    paths = list(directory.iterdir())
    if len(paths) != 9 or any(p.is_symlink() or not p.is_file() for p in paths):
        raise ValueError('Expected exactly seven regular JARs and two metadata files')
    if not METADATA.issubset({p.name for p in paths}):
        raise ValueError('Missing snapshot metadata')
    for name in METADATA:
        if (directory / name).stat().st_size > MAX_METADATA_BYTES:
            raise ValueError('Oversized snapshot metadata')
    manifest = json.loads((directory / 'manifest.json').read_text(encoding='utf8'))
    if manifest.get('schema') != 1 or manifest.get('commit') != commit:
        raise ValueError('Snapshot manifest belongs to another schema or commit')
    if manifest.get('minecraftTested') is not False:
        raise ValueError('Automatic snapshots must not claim Minecraft validation')
    if manifest.get('sourceDirty') is not False:
        raise ValueError('Dirty or unverified source checkout cannot be published')
    core_sources = validate_core_sources(manifest)
    artifacts = manifest.get('artifacts')
    if not isinstance(artifacts, list) or len(artifacts) != 7:
        raise ValueError('Snapshot manifest must contain seven JARs')
    checked = []; seen_names = set(); seen_mods = set()
    for entry in artifacts:
        name = entry.get('name', '')
        if not isinstance(name, str) or not re.fullmatch(r'game-console-[A-Za-z0-9._+-]+\.jar', name):
            raise ValueError('Invalid release asset name')
        if name in seen_names:
            raise ValueError('Duplicate release asset')
        seen_names.add(name)
        path = directory / name
        if not path.is_file() or path.is_symlink() or not 0 < path.stat().st_size <= MAX_ASSET_BYTES:
            raise ValueError('Missing or oversized release JAR')
        actual = inspect(path)
        mod_set = frozenset(actual['versions'])
        if mod_set not in MOD_SETS or mod_set in seen_mods or actual['fileName'] != name:
            raise ValueError('Unexpected, incomplete or misnamed player mod')
        seen_mods.add(mod_set)
        if set(entry.get('modIds', [])) != set(mod_set):
            raise ValueError('Manifest mod IDs differ from the JAR')
        if actual['bytes'] != entry.get('bytes') or actual['sha256'] != entry.get('sha256'):
            raise ValueError('Manifest JAR size or SHA-256 mismatch')
        with zipfile.ZipFile(path) as jar:
            if sum(z.file_size for z in jar.infolist()) > 2 * MAX_ASSET_BYTES:
                raise ValueError('Excessive expanded JAR size')
            if jar.testzip() is not None:
                raise ValueError('Invalid JAR CRC')
            validate_jar_provenance(jar, manifest, core_sources, mod_set)
        checked.append(dict(name=name, bytes=actual['bytes'], sha256=actual['sha256']))
    if seen_mods != MOD_SETS or {p.name for p in paths} != seen_names | METADATA:
        raise ValueError('Unexpected release file set')
    manifest_path = directory / 'manifest.json'
    checked.append(dict(name='manifest.json', bytes=manifest_path.stat().st_size,
                        sha256=sha256(manifest_path)))
    expected_sums = {entry['name']: entry['sha256'] for entry in checked}
    actual_sums = {}
    for line in (directory / 'SHA256SUMS.txt').read_text(encoding='utf8').splitlines():
        match = re.fullmatch(r'([0-9a-f]{64})  ([A-Za-z0-9._+-]+)', line)
        if not match or match[2] in actual_sums:
            raise ValueError('Malformed or duplicate checksum entry')
        actual_sums[match[2]] = match[1]
    if actual_sums != expected_sums:
        raise ValueError('Checksum list does not match all seven JARs and manifest')
    sums = directory / 'SHA256SUMS.txt'
    checked.append(dict(name=sums.name, bytes=sums.stat().st_size, sha256=sha256(sums)))
    return sorted(checked, key=lambda entry: entry['name'])


def validate_core_sources(manifest: dict) -> dict[str, dict]:
    if not isinstance(manifest.get('inputReceiptSha256'), str) or not HEX64.fullmatch(manifest['inputReceiptSha256']):
        raise ValueError('Missing snapshot input receipt identity')
    sources = manifest.get('coreSources')
    if not isinstance(sources, list) or len(sources) != len(CORE_LOCATIONS):
        raise ValueError('Expected the five reviewed nightly core identities')
    cores = {}
    for source in sources:
        ident = source.get('id')
        if ident not in CORE_LOCATIONS or ident in cores:
            raise ValueError('Unknown or duplicated nightly core identity')
        _, _, platform, member = CORE_LOCATIONS[ident]
        if (source.get('platform') != platform or source.get('path') != f'nightly/{platform}/{member}'
                or source.get('url') != CORE_URLS[platform] + member + '.zip'):
            raise ValueError('Nightly core source is not the reviewed official URL/path')
        if (type(source.get('bytes')) is not int or not 0 < source['bytes'] <= 32 * 1024 * 1024
                or any(not isinstance(source.get(key), str) or not HEX64.fullmatch(source[key])
                       for key in ('sha256', 'archiveSha256'))):
            raise ValueError('Invalid nightly core digest or size')
        cores[ident] = source
    return cores


def validate_jar_provenance(jar: zipfile.ZipFile, manifest: dict,
                            cores: dict[str, dict], mod_set: frozenset) -> None:
    """A recomputed outer manifest cannot turn an older JAR into this snapshot."""
    for name in (STAMP, 'META-INF/MANIFEST.MF'):
        if name not in jar.namelist() or jar.getinfo(name).file_size > MAX_METADATA_BYTES:
            raise ValueError('Missing or oversized embedded snapshot provenance')
    embedded = json.loads(jar.read(STAMP))
    if (embedded.get('schema') != 1 or embedded.get('commit') != manifest['commit']
            or embedded.get('inputReceiptSha256') != manifest['inputReceiptSha256']
            or embedded.get('coreSources') != manifest['coreSources']
            or embedded.get('sourceDirty') is not False
            or embedded.get('minecraftTested') is not False):
        raise ValueError('Embedded snapshot commit, input receipt or core inventory mismatch')
    lines = jar.read('META-INF/MANIFEST.MF').decode('utf8').splitlines()
    identity = [line for line in lines if line.startswith('Game-Console-Snapshot-Commit:')]
    if identity != ['Game-Console-Snapshot-Commit: ' + manifest['commit']]:
        raise ValueError('JAR manifest snapshot commit mismatch')
    for ident, (owner, resource, _, _) in CORE_LOCATIONS.items():
        if owner not in mod_set:
            continue
        expected = cores[ident]
        if resource not in jar.namelist() or jar.getinfo(resource).file_size != expected['bytes']:
            raise ValueError('Missing nightly core or packed core size mismatch')
        with jar.open(resource) as stream:
            actual = hashlib.file_digest(stream, 'sha256').hexdigest()
        if actual != expected['sha256']:
            raise ValueError('Packed nightly core SHA-256 differs from snapshot provenance')


class GitHub:
    def __init__(self, repository: str, token: str):
        if not token:
            raise ValueError('GH_TOKEN is required for publication')
        self.repository = repository
        self.token = token

    def headers(self) -> dict:
        return {'Accept': 'application/vnd.github+json', 'Authorization': 'Bearer ' + self.token,
                'X-GitHub-Api-Version': '2022-11-28', 'User-Agent': 'game-console-snapshot'}

    def request(self, method: str, path: str, body=None, missing_ok=False):
        # Hosts, API prefix and credentials are not read from the downloaded artifact.
        if not path.startswith('/') or path.startswith('//'):
            raise ValueError('Invalid GitHub API path')
        data = json.dumps(body).encode('utf8') if body is not None else None
        headers = self.headers()
        if data is not None:
            headers['Content-Type'] = 'application/json'
        conn = http.client.HTTPSConnection('api.github.com', timeout=90)
        try:
            conn.request(method, '/repos/' + self.repository + path, body=data, headers=headers)
            response = conn.getresponse()
            payload = response.read(MAX_METADATA_BYTES + 1)
            if response.status == 404 and missing_ok:
                return None
            if not 200 <= response.status < 300 or len(payload) > MAX_METADATA_BYTES:
                raise RuntimeError(f'GitHub {method} {path}: HTTP {response.status}; no automatic overwrite or retry')
            return json.loads(payload) if payload else None
        finally:
            conn.close()

    def upload(self, release_id: int, path: Path, expected: dict):
        # Verify once more immediately before streaming; never retry an ambiguous upload.
        if path.is_symlink() or path.stat().st_size != expected['bytes'] or sha256(path) != expected['sha256']:
            raise ValueError('Release asset changed after validation')
        headers = self.headers()
        headers.update({'Content-Type': 'application/octet-stream', 'Content-Length': str(expected['bytes'])})
        url = f'/repos/{self.repository}/releases/{release_id}/assets?name=' + urllib.parse.quote(path.name, safe='')
        conn = http.client.HTTPSConnection('uploads.github.com', timeout=180)
        try:
            conn.putrequest('POST', url)
            for key, value in headers.items():
                conn.putheader(key, value)
            conn.endheaders()
            digest = hashlib.sha256(); size = 0
            with path.open('rb') as source:
                while chunk := source.read(1024 * 1024):
                    digest.update(chunk); size += len(chunk); conn.send(chunk)
            response = conn.getresponse()
            payload = response.read(MAX_METADATA_BYTES + 1)
            if response.status != 201 or len(payload) > MAX_METADATA_BYTES:
                raise RuntimeError(f'GitHub asset upload: HTTP {response.status}; release remains draft')
            if size != expected['bytes'] or digest.hexdigest() != expected['sha256']:
                raise ValueError('Asset changed while uploading; release remains draft')
            return json.loads(payload)
        finally:
            conn.close()


def check_assets(assets: list[dict], expected: list[dict]) -> None:
    if len(assets) != len(expected) or len({a.get('name') for a in assets}) != len(expected):
        raise ValueError('Remote assets are missing, extra or duplicated')
    by_name = {asset['name']: asset for asset in assets}
    for entry in expected:
        actual = by_name.get(entry['name'], {})
        if (actual.get('state') != 'uploaded' or actual.get('size') != entry['bytes']
                or actual.get('digest') != 'sha256:' + entry['sha256']):
            raise ValueError('Remote asset size, state or SHA-256 mismatch')


def publish(api, ctx: dict, directory: Path, files: list[dict]) -> dict:
    tag = ctx['tag']
    if api.request('GET', '/git/ref/tags/' + tag, missing_ok=True) is not None:
        raise ValueError('Snapshot tag already exists; refusing to move or replace it')
    if api.request('GET', '/releases/tags/' + tag, missing_ok=True) is not None:
        raise ValueError('Snapshot release already exists; refusing to change it')
    commit = api.request('GET', '/commits/' + ctx['commit'])
    if commit.get('sha') != ctx['commit']:
        raise ValueError('Remote source commit mismatch')
    body = (f"自动构建快照，源码：`{ctx['commit']}`。\n\n"
            "七个 JAR：主模组（内置 FC）、SFC、MD、GBA、街机、电脑，以及可选 PvZ 开发验证组件。"
            "不要同时安装同一模组的多个版本；升级前备份。\n\n"
            "Libretro 普通核心来自本次 manifest.json 记录的官方 nightly，实际 SHA-256、"
            "构建输入和验证范围随清单保留；定制运行库仍使用审核过的固定输入。"
            "包含 Windows/Linux 核心不等于全部运行模式已完成 Linux 实机验证。\n\n"
            "这是预发行测试候选，不是稳定版；自动检查不能替代 Minecraft 多人、光影及长时验收。"
            "不包含 ROM、BIOS、游戏数据或玩家存档。PvZ 仍需自行提供合法游戏资源。\n\n"
            f"[构建记录](https://github.com/{ctx['repository']}/actions/runs/{ctx['run_id']}) · "
            "文件校验见 SHA256SUMS.txt。")
    release = api.request('POST', '/releases', dict(tag_name=tag, target_commitish=ctx['commit'],
                          name='Snapshot ' + tag.removeprefix('snapshot-'), body=body,
                          draft=True, prerelease=True, make_latest='false'))
    release_id = release.get('id')
    if (not isinstance(release_id, int) or release_id <= 0 or release.get('tag_name') != tag
            or release.get('draft') is not True or release.get('prerelease') is not True):
        raise ValueError('Unexpected draft response; publication stopped')
    print(f'Created draft {tag}; upload failure will not publish it.', flush=True)
    for entry in files:
        check_assets([api.upload(release_id, directory / entry['name'], entry)], [entry])
        print('Uploaded ' + entry['name'], flush=True)
    assets = api.request('GET', f'/releases/{release_id}/assets?per_page=100')
    check_assets(assets, files)
    # Reserve/verify the exact commit before making the release public. GitHub
    # ignores target_commitish when a tag already exists, so checking afterwards
    # alone would be too late if another writer created the tag during upload.
    ref = api.request('GET', '/git/ref/tags/' + tag, missing_ok=True)
    if ref is None:
        ref = api.request('POST', '/git/refs', dict(ref='refs/tags/' + tag, sha=ctx['commit']))
    if ref.get('object', {}).get('type') != 'commit' or ref.get('object', {}).get('sha') != ctx['commit']:
        raise ValueError('Snapshot tag has an unexpected target; release remains draft')
    # No deletion, force-push, overwrite or update of older snapshots anywhere.
    release = api.request('PATCH', f'/releases/{release_id}',
                          dict(draft=False, prerelease=True, make_latest='false'))
    if release.get('draft') is not False or release.get('prerelease') is not True or release.get('tag_name') != tag:
        raise ValueError('Published release verification failed')
    ref = api.request('GET', '/git/ref/tags/' + tag)
    if ref.get('object', {}).get('type') != 'commit' or ref.get('object', {}).get('sha') != ctx['commit']:
        raise ValueError('Published snapshot tag has an unexpected target')
    return release


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--directory', type=Path, required=True)
    parser.add_argument('--validate-only', action='store_true')
    args = parser.parse_args()
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip()
    if not HEX40.fullmatch(head):
        raise ValueError('Invalid local source commit')
    # A read-only local check needs no Actions identity or credentials. It still
    # verifies all provenance and rejects dirty-source artifacts inside validate.
    # The publication path below always enforces the complete main/event context.
    if args.validate_only and os.environ.get('GITHUB_ACTIONS') != 'true':
        validate(args.directory, head)
        print(f'Validated seven JARs and two metadata files for local commit {head}', flush=True)
        return
    ctx = context(os.environ)
    if head != ctx['commit']:
        raise ValueError('Publisher checkout must match the triggering commit')
    files = validate(args.directory, ctx['commit'])
    print(f"Validated seven JARs and two metadata files for {ctx['tag']}", flush=True)
    if args.validate_only:
        return
    api = GitHub(ctx['repository'], os.environ.get('GH_TOKEN', ''))
    release = publish(api, ctx, args.directory, files)
    # Derive the public URL ourselves, do not turn untrusted response data into shell output.
    print(f"Published https://github.com/{ctx['repository']}/releases/tag/{ctx['tag']}")


if __name__ == '__main__':
    try:
        main()
    except (ValueError, RuntimeError, OSError, zipfile.BadZipFile) as error:
        print('Snapshot stopped: ' + str(error), file=sys.stderr)
        sys.exit(1)
