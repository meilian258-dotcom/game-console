"""Build seven snapshot MODs from this checkout, using receipt-verified binary inputs.

Old release JARs supply only explicitly pinned native resources. They are never
published as this build, nor used as the implementation of a newly compiled MOD.
This command does not upload, install, modify a game instance, or read player ROMs.
Verification may execute the repository's original synthetic diagnostic programs.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import tomllib
import zipfile

from release_artifacts import inspect, sha256

ROOT = Path(__file__).resolve().parents[1]
META = 'META-INF/neoforge.mods.toml'
MANIFEST = 'META-INF/MANIFEST.MF'
HELPER = 'native-runtime/win-x64-v1/piq-native-arcade/runtime/piq-native-helper-v4.jar'
HELPER_BYTES = 18858
HELPER_SHA256 = '51a1a6a0a326e5e855647a414dbb78894ca01e9d766627ef272ce59bc809a304'
BOOTSTRAP = {
    'fc': '7d4db04cc8bafb3f7dc426e3e8412459ba1308dac9d33c434f99c1fcf5eac716',
    'sfc': 'a9c8bdd2ae068383310ac57efcfb18df29eca67238218b12bc6da38b47cf1816',
    'md': '9ea8727a77e2cc10fc59a2e34bb1b16466be2fcb1d23e941216b79921fc4b6df',
    'gba': '7e991d3151c140c01a4acc094d68f9ccaf2e285b6a0fadf6abb15cb345d40f0a',
    'arcade': '575f16d2123f1e91cadba12d8ace9dead63936770fcdec4476c260c13ce5d925',
    'pvz': 'e010b4185c8a9e51edcba3ef40718a81781068320681892cf869d68c3046f04a',
}
RESOURCE_SCOPES = {
    'sfc-core': ('assets/piq_sfc_arcade/core/piq_sfc_wasm.wasm', 'META-INF/piq-sfc-wasm-privacy.json'),
    'sfc-home': ('core/sfc-libretro/',),
    'md': ('core/windows-x64/',),
    'pvz': ('core/pvz/', 'META-INF/pvz/licenses/'),
    'arcade': ('native-runtime/', 'META-INF/piq-native/fbneo/', 'META-INF/licenses/native-runtime17/'),
}
EXPECTED_IDS = {'piq_fc_arcade', 'piq_sfc_arcade', 'piq_sfc_home', 'piq_md_home',
                'piq_gba', 'piq_native_arcade', 'piq_computer', 'piq_pvz'}


def safe_name(name: str) -> str:
    if (not isinstance(name, str) or not name or '\\' in name or ':' in name
            or PurePosixPath(name).is_absolute()
            or any(part in ('', '.', '..') for part in name.split('/'))):
        raise ValueError('Unsafe relative input name')
    return name


def no_links(path: Path) -> None:
    for part in (path, *path.parents):
        if part.is_symlink() or (hasattr(part, 'is_junction') and part.is_junction()):
            raise ValueError('Linked input/output path is not accepted')


def load_receipt(receipt_path: Path) -> tuple[dict, dict[str, dict]]:
    no_links(receipt_path.absolute())
    receipt = json.loads(receipt_path.read_text(encoding='utf-8'))
    if receipt.get('schema') != 1 or not isinstance(receipt.get('files'), list):
        raise ValueError('Unsupported snapshot input receipt')
    records = {}
    for entry in receipt['files']:
        name = safe_name(entry['path'])
        if name in records or not re.fullmatch('[0-9a-f]{64}', entry.get('sha256', '')):
            raise ValueError('Duplicate input or invalid input hash')
        if type(entry.get('bytes')) is not int or entry['bytes'] < 1:
            raise ValueError('Invalid input size')
        records[name] = entry
    return receipt, records


def verify_file(path: Path, record: dict) -> None:
    no_links(path.absolute())
    if not path.is_file() or path.stat().st_size != record['bytes'] or sha256(path) != record['sha256']:
        raise ValueError('Snapshot input is absent or changed: ' + record.get('path', path.name))


def verify_resources(resource_root: Path, receipt_path: Path) -> None:
    """Called again by Gradle immediately before processResources."""
    input_root = receipt_path.absolute().parent
    no_links(resource_root.absolute())
    relative = resource_root.absolute().relative_to(input_root).as_posix()
    bucket = resource_root.name
    if relative != 'resources/' + bucket or bucket not in RESOURCE_SCOPES:
        raise ValueError('Unknown generated resource bucket')
    _, records = load_receipt(receipt_path)
    expected = {name: entry for name, entry in records.items() if name.startswith(relative + '/')}
    actual = set()
    for path in resource_root.rglob('*'):
        no_links(path)
        if path.is_dir():
            continue
        name = path.relative_to(input_root).as_posix()
        resource = path.relative_to(resource_root).as_posix()
        safe_name(resource)
        if not any(resource == prefix or (prefix.endswith('/') and resource.startswith(prefix))
                   for prefix in RESOURCE_SCOPES[bucket]):
            raise ValueError('Foreign snapshot resource: ' + resource)
        if resource.endswith('.class') or resource == HELPER or resource.lower().endswith('blastem_libretro.dll'):
            raise ValueError('Compiled MOD classes/current helper/retired core must not be bootstrapped')
        if name not in expected:
            raise ValueError('Unreceipted resource: ' + resource)
        verify_file(path, expected[name])
        actual.add(name)
    if not actual or actual != set(expected):
        raise ValueError('Incomplete snapshot resource tree')


def verify_inputs(input_root: Path) -> dict:
    receipt_path = input_root / 'receipt.json'
    receipt, records = load_receipt(receipt_path)
    for name, record in records.items():
        verify_file(input_root / name, record)
    pins = {}
    for entry in receipt.get('bootstrap', []):
        ident = entry.get('id')
        if ident not in BOOTSTRAP or ident in pins or entry.get('sha256') != BOOTSTRAP[ident]:
            raise ValueError('Unapproved or duplicate bootstrap distribution')
        name = safe_name(entry['path'])
        if name not in records or records[name]['sha256'] != entry['sha256']:
            raise ValueError('Bootstrap is not bound to the input receipt')
        pins[ident] = input_root / name
    if set(pins) != set(BOOTSTRAP):
        raise ValueError('Exactly the six approved native input distributions are required')
    for bucket in RESOURCE_SCOPES:
        verify_resources(input_root / 'resources' / bucket, receipt_path)
    return dict(receipt=receipt, bootstrap=pins, receipt_path=receipt_path)


def archive_entries(path: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(path) as jar:
        names = [entry.filename for entry in jar.infolist() if not entry.is_dir()]
        if len(names) != len(set(names)) or jar.testzip() is not None:
            raise ValueError('Duplicate/corrupt archive entries')
        for entry in jar.infolist():
            if entry.is_dir():
                continue
            safe_name(entry.filename)
            if stat.S_ISLNK(entry.external_attr >> 16) or entry.flag_bits & 1:
                raise ValueError('Unsupported archive member')
        return {name: jar.read(name) for name in names}


def combine_metadata(core: bytes, home: bytes) -> bytes:
    a, b = (tomllib.loads(raw.decode('utf-8')) for raw in (core, home))
    allowed = {'modLoader', 'loaderVersion', 'license', 'mods', 'dependencies'}
    if set(a) - allowed or set(b) - allowed:
        raise ValueError('New SFC metadata needs an explicit merger review')
    for field in ('modLoader', 'loaderVersion', 'license'):
        if a[field] != b[field]:
            raise ValueError('Incompatible SFC metadata: ' + field)
    if [mod['modId'] for mod in a['mods']] != ['piq_sfc_arcade'] or [mod['modId'] for mod in b['mods']] != ['piq_sfc_home']:
        raise ValueError('Unexpected SFC component IDs')
    if set(a['dependencies']) & set(b['dependencies']):
        raise ValueError('Duplicate SFC dependency owner')
    text = home.decode('utf-8')
    merged = core.rstrip() + b'\n\n' + text[text.index('[[mods]]'):].encode('utf-8')
    parsed = tomllib.loads(merged.decode('utf-8'))
    expected = dict(a, mods=a['mods'] + b['mods'], dependencies=a['dependencies'] | b['dependencies'])
    if parsed != expected:
        raise ValueError('SFC metadata merge lost fields')
    return merged


def merge_sfc(core: Path, home: Path, output: Path) -> Path:
    a, b = archive_entries(core), archive_entries(home)
    for entries, owner in ((a, 'sfcarcade'), (b, 'sfchome')):
        classes = [name for name in entries if name.endswith('.class')]
        if not classes or any(not name.startswith('cn/piq/' + owner + '/') for name in classes):
            raise ValueError('Foreign or missing SFC classes')
    shared = (set(a) & set(b)) - {META, MANIFEST}
    if any(name.endswith('.class') or a[name] != b[name] for name in shared):
        raise ValueError('Conflicting SFC resource/class ownership')
    combined = a | b
    combined[META] = combine_metadata(a[META], b[META])
    version = tomllib.loads(b[META].decode('utf-8'))['mods'][0]['version']
    combined[MANIFEST] = ('Manifest-Version: 1.0\nImplementation-Title: Game Console SFC\n'
                          f'Implementation-Version: {version}\n\n').encode()
    for needed in ('assets/piq_sfc_arcade/core/piq_sfc_wasm.wasm',
                   'core/sfc-libretro/windows-x64/mesen-s_libretro.dll', 'META-INF/accesstransformer.cfg'):
        if needed not in combined:
            raise ValueError('SFC complete bundle missing ' + needed)
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'x', zipfile.ZIP_DEFLATED) as jar:
        for name, raw in sorted(combined.items()):
            item = zipfile.ZipInfo(name, (2026, 10, 7, 0, 0, 0))
            item.compress_type = zipfile.ZIP_DEFLATED
            jar.writestr(item, raw)
    if archive_entries(output) != combined:
        raise ValueError('SFC merged output verification failed')
    inspect(output)
    return output


def version(module: str) -> str:
    values = (ROOT / module / 'gradle.properties').read_text(encoding='utf-8-sig')
    match = re.search(r'^mod_version=(.+)$', values, re.M)
    if not match:
        raise ValueError('Module has no explicit mod_version: ' + module)
    result = match.group(1).strip()
    if not re.fullmatch(r'[0-9][A-Za-z0-9.+_-]{0,95}', result):
        raise ValueError('Unsafe module version')
    return result


def stage_outputs(paths: list[Path], output: Path, manifest: dict) -> dict:
    if len(paths) != 7 or output.exists():
        raise ValueError('Exactly seven new JARs and a new distribution directory are required')
    metadata = [inspect(path) for path in paths]
    ids, classes = set(), set()
    for path, item in zip(paths, metadata):
        if ids & set(item['versions']):
            raise ValueError('Duplicate MOD IDs')
        ids.update(item['versions'])
        own = {name for name in archive_entries(path) if name.startswith('cn/piq/') and name.endswith('.class')}
        if not own or classes & own:
            raise ValueError('Missing or duplicate public Java class ownership')
        classes.update(own)
    if ids != EXPECTED_IDS:
        raise ValueError('Incomplete seven-MOD/eight-ID suite')
    output.mkdir(parents=True, exist_ok=False)
    assets = []
    for path, item in zip(paths, metadata):
        destination = output / item['fileName']
        with path.open('rb') as source, destination.open('xb') as sink:
            shutil.copyfileobj(source, sink)
        if sha256(destination) != item['sha256']:
            raise ValueError('Output copy verification failed')
        assets.append(dict(name=destination.name, bytes=item['bytes'], sha256=item['sha256'],
                           modIds=sorted(item['versions']), versions=item['versions']))
    manifest = dict(manifest, schema=1, artifacts=assets)
    (output / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    hashes = [f"{item['sha256']}  {item['name']}" for item in assets]
    hashes.append(f"{sha256(output / 'manifest.json')}  manifest.json")
    (output / 'SHA256SUMS.txt').write_text('\n'.join(hashes) + '\n', encoding='utf-8')
    return manifest


def stamp_snapshot(path: Path, output: Path, provenance: bytes) -> Path:
    """Add one identical identity receipt without rewriting MOD compatibility versions."""
    entries = archive_entries(path)
    name = 'META-INF/game-console/snapshot-cores.json'
    if name in entries:
        raise ValueError('Build input already contains a snapshot stamp')
    document = json.loads(provenance)
    commit = document['commit']
    if not re.fullmatch('[0-9a-f]{40}', commit):
        raise ValueError('Invalid snapshot source identity')
    entries[name] = provenance
    original = entries.get(MANIFEST, b'Manifest-Version: 1.0\n\n').decode('utf-8')
    if 'Game-Console-Snapshot-Commit:' in original:
        raise ValueError('Build input already has a snapshot manifest identity')
    entries[MANIFEST] = (original.rstrip() + '\nGame-Console-Snapshot-Commit: ' + commit + '\n\n').encode()
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, 'x', zipfile.ZIP_DEFLATED) as jar:
        for key, value in sorted(entries.items()):
            entry = zipfile.ZipInfo(key, (2026, 10, 7, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            jar.writestr(entry, value)
    if archive_entries(output) != entries:
        raise ValueError('Snapshot provenance stamping failed')
    return output


def verify_arcade_helper(arcade: Path, fresh_helper: Path, catalog: Path) -> None:
    """Custom runtime identities remain reviewed pins, not ordinary nightly inputs."""
    with zipfile.ZipFile(arcade) as jar:
        helper = jar.read(HELPER)
        if helper != fresh_helper.read_bytes():
            raise ValueError('Current helper was not packaged')
        actual = hashlib.sha256(helper).hexdigest()
        if len(helper) != HELPER_BYTES or actual != HELPER_SHA256:
            raise ValueError('Fresh arcade helper changed its reviewed custom identity; profile review is required')
        manifest = json.loads(jar.read('native-runtime/win-x64-v1/manifest-native011.json'))
        pins = [entry for entry in manifest['artifacts'] if entry['resourcePath'] == HELPER]
        if len(pins) != 1 or pins[0]['size'] != len(helper) or pins[0]['sha256'].lower() != actual:
            raise ValueError('Fresh arcade helper does not match its Native runtime manifest')
    source = catalog.read_text(encoding='utf-8')
    pins = re.findall(r'file\(\s*"piq-native-arcade/runtime"\s*,\s*"piq-native-helper-v4.jar"\s*,\s*(\d+)\s*,\s*"([0-9A-Fa-f]{64})"\s*\)', source)
    if len(pins) != 1 or int(pins[0][0]) != len(helper) or pins[0][1].lower() != actual:
        raise ValueError('Fresh arcade helper does not match the FC runtime catalog')


def prepare_gba_probe_classpath(dependencies: Path, minecraft: Path, receipt: Path) -> tuple[Path, Path]:
    """The Windows java launcher decodes @argfiles using its native code page.

    GBA's checker already stages its two MOD JARs, but its other classpath
    entries need the same treatment when the checkout path contains Unicode.
    Keep the owned copies and hashes as private diagnostics, not release assets.
    """
    root = Path(tempfile.mkdtemp(prefix='gc-snapshot-gba-deps-'))
    if not str(root).isascii():
        raise ValueError('GBA probe requires an ASCII TEMP directory for Windows Java argfiles')
    deps = root / 'dependencies'
    deps.mkdir()
    sources = sorted(dependencies.glob('*.jar'))
    if not sources:
        raise ValueError('GBA probe has no exported compile dependencies')
    records = []
    for index, source in enumerate([*sources, minecraft]):
        no_links(source.absolute())
        target = root / 'minecraft-client.jar' if index == len(sources) else deps / f'dependency-{index:03d}.jar'
        digest = sha256(source)
        with source.open('rb') as stream, target.open('xb') as sink:
            shutil.copyfileobj(stream, sink)
        if sha256(target) != digest:
            raise ValueError('GBA probe classpath copy changed bytes')
        records.append(dict(source=str(source.absolute()), copy=str(target), sha256=digest, bytes=target.stat().st_size))
    receipt.write_text(json.dumps(dict(schema=1, ownedDirectory=str(root), files=records), indent=2), encoding='utf-8')
    return deps, root / 'minecraft-client.jar'


def build(work: Path, output: Path) -> dict:
    global ROOT
    work, output = work.absolute(), output.absolute()
    no_links(work)
    no_links(output)
    if output.exists() or (work / 'build-started.json').exists():
        raise ValueError('Use a new work/output directory; a failed snapshot is retained for inspection')
    inputs = verify_inputs(work / 'inputs')
    commit = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if not re.fullmatch('[0-9a-f]{40}', commit):
        raise ValueError('Unable to bind the build to a Git commit')
    expected_commit = os.environ.get('GITHUB_SHA')
    if expected_commit and commit != expected_commit:
        raise ValueError('Checkout is not the requested Actions commit')
    source_dirty = bool(subprocess.check_output(['git', 'status', '--porcelain', '--untracked-files=all'], cwd=ROOT))
    if os.environ.get('GITHUB_ACTIONS') == 'true' and source_dirty:
        raise ValueError('Actions snapshot must compile an unmodified, fully committed checkout')
    jdk = Path(os.environ.get('JAVA_HOME', '')) / 'bin'
    if not (jdk / 'javac.exe').is_file():
        raise ValueError('This seven-MOD build currently requires Windows and JAVA_HOME pointing to JDK 21')
    work.mkdir(parents=True, exist_ok=True)
    (work / 'build-started.json').write_text(json.dumps({'commit': commit}), encoding='utf-8')
    logs = work / 'logs'
    logs.mkdir()
    commands = []

    def run(label: str, command: list[str]) -> None:
        print('Building/verifying ' + label, flush=True)
        commands.append(label)
        with (logs / (label + '.log')).open('xb') as log:
            result = subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, timeout=2700)
        if result.returncode:
            # The complete log remains private to the CI artifact, never in the release.
            print((logs / (label + '.log')).read_text(encoding='utf-8', errors='replace')[-12000:])
            raise RuntimeError(label + ' failed; no snapshot may be published')

    def gradle(module: str, tasks: list[str], properties: dict | None = None, resources: str | None = None) -> None:
        props = dict(properties or {})
        if resources:
            props.update(snapshotResourcesDir=str(input_root / 'resources' / resources),
                         snapshotReceipt=str(inputs['receipt_path']), snapshotPython=sys.executable)
        cmd = [str(ROOT / 'piq-fc-arcade/gradlew.bat'), '-p', str(ROOT / module),
               '--no-daemon', '--max-workers=2', '--console=plain', *tasks]
        cmd.extend('-P' + key + '=' + str(value) for key, value in props.items())
        run(module, cmd)

    run('snapshot-profiles', [sys.executable, str(ROOT / 'source-control/snapshot_profiles.py'),
                             '--root', str(ROOT), '--inputs', str(work / 'inputs'),
                             '--output', str(work / 'source'), '--jdk', str(jdk)])
    profile_root = work / 'source'
    profile = json.loads((profile_root / 'snapshot-profile-receipt.json').read_text(encoding='utf-8'))
    if profile.get('schema') != 1:
        raise ValueError('Unknown snapshot profile receipt')
    input_root = profile_root / safe_name(profile['inputRoot'])
    runtime_base = profile_root / safe_name(profile['runtimeBase'])
    inputs = verify_inputs(input_root)
    ROOT = profile_root
    deps = work / 'compile-dependencies'
    gradle('piq-fc-arcade', ['clean', 'check', 'jar', 'exportSnapshotCompileDependencies'],
           dict(piqInputsDir=str(input_root / 'fc-cache'), snapshotDependenciesDir=str(deps)))
    fc = ROOT / 'piq-fc-arcade/build/libs' / ('piq_fc_arcade-' + version('piq-fc-arcade') + '.jar')
    worker = profile['fcWorker']
    with zipfile.ZipFile(fc) as jar:
        raw_worker = jar.read('core/libretro/worker.jar')
        if len(raw_worker) != worker['bytes'] or hashlib.sha256(raw_worker).hexdigest() != worker['sha256']:
            raise ValueError('FC worker identity drifted between profile preparation and final build')
    common = dict(gameConsoleJar=str(fc))
    gradle('piq-sfc-arcade', ['clean', 'check', 'jar'], common, 'sfc-core')
    sfc_core = ROOT / 'piq-sfc-arcade/build/libs' / ('piq_sfc_arcade-' + version('piq-sfc-arcade') + '.jar')
    gradle('piq-sfc-home', ['clean', 'check', 'jar'], dict(common, sfcCoreJar=str(sfc_core)), 'sfc-home')
    sfc_home = ROOT / 'piq-sfc-home/build/libs' / ('piq_sfc_home-' + version('piq-sfc-home') + '.jar')
    sfc = merge_sfc(sfc_core, sfc_home, work / 'merged/sfc-complete.jar')
    gradle('piq-md-home', ['clean', 'check', 'jar'], common, 'md')
    md = ROOT / 'piq-md-home/build/libs' / ('game_console_md-' + version('piq-md-home') + '.jar')
    gradle('piq-native-arcade', ['clean', 'check', 'jar'], dict(common, embeddedRuntimePython=sys.executable), 'arcade')
    arcade = ROOT / 'piq-native-arcade/build/libs' / ('piq_native_arcade-' + version('piq-native-arcade') + '.jar')
    gradle('piq-pvz-addon', ['clean', 'check', 'jar'], common, 'pvz')
    pvz = ROOT / 'piq-pvz-addon/build/libs/game_console_pvz-0.1.0-prototype.12.jar'
    gradle('piq-computer', ['clean', 'check', 'jar'], dict(common, pvzJar=str(pvz)))
    computer = ROOT / 'piq-computer/build/libs/game_console_computer-0.1.0-prototype.12.jar'
    run('piq-gba', [sys.executable, str(ROOT / 'piq-gba/tools/build_current.py'), '--fc', str(fc),
                    '--runtime-base', str(runtime_base), '--dependencies', str(deps),
                    '--jdk', str(jdk), '--output', str(work / 'gba')])
    gba_outputs = list((work / 'gba').glob('game-console-gba-*.jar'))
    if len(gba_outputs) != 1:
        raise ValueError('GBA did not produce exactly one fresh artifact')
    minecraft_resources = list((ROOT / 'piq-fc-arcade/build/moddev/artifacts').glob('*-client-extra-aka-minecraft-resources.jar'))
    if len(minecraft_resources) != 1:
        raise ValueError('FC build did not provide a unique Minecraft resource artifact for GBA verification')
    probe_deps, probe_minecraft = prepare_gba_probe_classpath(deps, minecraft_resources[0], work / 'gba-probe-inputs.json')
    run('piq-gba-probes', [sys.executable, str(ROOT / 'piq-gba/tools/check_current.py'), '--fc', str(fc),
                         '--gba', str(gba_outputs[0]), '--dependencies', str(probe_deps),
                         '--minecraft-client', str(probe_minecraft), '--jdk', str(jdk),
                         '--output', str(work / 'gba-probes')])
    # Re-read every external input to catch any mutation during the build.
    verify_inputs(input_root)
    verify_arcade_helper(arcade, ROOT / 'piq-native-arcade/build/helper48/piq-native-helper-v4.jar',
                         ROOT / 'piq-fc-arcade/src/main/java/cn/piq/fcarcade/runtime/RuntimeCatalog.java')
    receipt = inputs['receipt']
    provenance = (json.dumps(dict(schema=1, commit=commit, sourceDirty=source_dirty,
                                 inputReceiptSha256=sha256(inputs['receipt_path']),
                                 coreSources=receipt.get('nightly', []),
                                 profile=profile,
                                 customCores='Reviewed bootstrap identities retained; not replaced by nightly',
                                 minecraftTested=False), sort_keys=True, indent=2) + '\n').encode()
    stamped = [stamp_snapshot(path, work / 'stamped' / (str(index) + '.jar'), provenance)
               for index, path in enumerate([fc, sfc, md, gba_outputs[0], arcade, computer, pvz])]
    return stage_outputs(stamped, output,
                         dict(commit=commit, sourceDirty=source_dirty, inputReceiptSha256=sha256(inputs['receipt_path']),
                              bootstrap=[dict(id=item['id'], sha256=item['sha256']) for item in receipt['bootstrap']],
                              coreSources=receipt.get('nightly', []),
                              tests=dict(successfulSteps=commands, gba='full-source compilation and six final-JAR probes; original diagnostic ROM; no MC player test'),
                              minecraftTested=False, stable=False))


def main() -> None:
    # Windows terminals may otherwise reject a decoded diagnostic character and
    # obscure the actual Gradle failure with a UnicodeEncodeError.
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--work', type=Path, default=Path('.snapshot-build'))
    parser.add_argument('--output', type=Path, default=Path('snapshot-dist'))
    parser.add_argument('--verify-resources', type=Path)
    parser.add_argument('--receipt', type=Path)
    args = parser.parse_args()
    if args.verify_resources:
        if not args.receipt:
            parser.error('--verify-resources requires --receipt')
        verify_resources(args.verify_resources, args.receipt)
    else:
        report = build(args.work, args.output)
        print(json.dumps({'commit': report['commit'], 'artifacts': report['artifacts']}, indent=2))


if __name__ == '__main__':
    main()
