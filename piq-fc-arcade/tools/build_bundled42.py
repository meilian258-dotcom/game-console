"""Capture, then freeze FC42/Arcade17 after real Gradle tests; never install/run cores."""
from pathlib import Path
import argparse
import copy
import hashlib
import importlib.util
import json
import re
import shutil
import stat
import sys
import time
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[2]
UTIL = ROOT / 'piq-fc-arcade/tools/build_sync39.py'
assert hashlib.sha256(UTIL.read_bytes()).hexdigest().upper() == '7F9E8809360BBEFDDCE9D317146EC4943DDB1A71A09472A68F065370DA6E0F4A'
spec = importlib.util.spec_from_file_location('bundle42_util', UTIL)
b = importlib.util.module_from_spec(spec); spec.loader.exec_module(b)
b.PROJECTS = ['piq-fc-arcade', 'piq-retro-platform', 'piq-native-arcade']
b.VERSIONS = {'piq_fc_arcade': '0.31.0-alpha.42', 'piq_native_arcade': '0.1.0-alpha.17'}
b.TEST_LIMITS = {'piq-fc-arcade': (1688, 8), 'piq-native-arcade': (86, 0)}
OUT = ROOT / 'piq-fc-arcade/build/review-bundled42-v2'
PREP = ROOT / 'piq-native-arcade/tools/prepare_embedded_runtime17.py'
BASE = {
    'fc': (ROOT / 'piq-fc-arcade/build/review-public41-v1/game_console-0.31.0-alpha.41.jar', '27DA245957D64D88BE105F8AC3911C059A01FC60CAA2548776AC2A376390674D'),
    'native': (ROOT / 'piq-fc-arcade/build/review-arcade16-v1/game_console_arcade-0.1.0-alpha.16.jar', '70DD0A32D460DBD8D8144DEA4F0E236066FD71F343427801E8BDD433C86E0AC8'),
}
RUNTIME = ROOT / 'piq-fc-arcade/build/runtime-pack37-v1/piq-runtime-pack-v1.zip'
RUNTIME_SHA = '681FDAB15CCF7BD3739B74598D1724E637415E6EB60C505DEEF0EFDAED0617AA'
PRIOR_WITNESS = ROOT / 'piq-fc-arcade/build/review-public41-v1/build-witness.json'
NAMES = {'fc': 'game_console-0.31.0-alpha.42.jar', 'native': 'game_console_arcade-0.1.0-alpha.17.jar'}


def file_hash(path):
    h = hashlib.sha256()
    with b.safe(path, True).open('rb') as source:
        while chunk := source.read(1024 * 1024): h.update(chunk)
    return h.hexdigest().upper()


def inputs():
    result = b.snapshot()
    extra = [Path(__file__).resolve(), PREP, RUNTIME, PRIOR_WITNESS, *[p for p, _ in BASE.values()],
             ROOT / 'piq-native-arcade/tools/test_prepare_embedded_runtime17.py',
             ROOT / 'piq-native-arcade/docs/embedded-runtime17-packaging.md']
    for folder in ('docs/licenses', 'tools/tests'):
        extra.extend(p for p in (ROOT / 'piq-native-arcade' / folder).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
    for path in extra: result[path.relative_to(ROOT).as_posix()] = file_hash(path)
    b.require(file_hash(RUNTIME) == RUNTIME_SHA, 'Runtime original changed')
    b.require(file_hash(PRIOR_WITNESS) == '4D4A619D2197739C0EB9FCF13FBDBAEAC14EBF1CBBBC817864785B9CB467FEB7', 'FC41 witness changed')
    for path, pin in BASE.values(): b.require(file_hash(path) == pin, 'Baseline changed')
    return result


def prepare_module():
    spec = importlib.util.spec_from_file_location('bundle42_prep', PREP)
    module = importlib.util.module_from_spec(spec); sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    b.require(module.PROFILE.archive_sha256 == RUNTIME_SHA and module.PROFILE.archive_size == 127026327, 'Wrong embedded runtime profile')
    return module


def native_archive(path, expected):
    """Stream only the six large fixed payloads; all other entries retain the small-JAR budget."""
    entries, payloads, seen, small_size = {}, {}, set(), 0
    b.require(b.safe(path, True).stat().st_size <= 140 * 1024 * 1024, 'Native compressed size limit')
    with zipfile.ZipFile(path) as z:
        b.require(len(z.infolist()) < 400, 'Native entry count limit')
        for info in z.infolist():
            name = info.orig_filename
            b.require(name == info.filename and name not in seen and name and not name.startswith('/') and '\\' not in name and ':' not in name
                      and all(p not in ('', '.', '..') for p in name.rstrip('/').split('/'))
                      and not info.flag_bits & 1 and not stat.S_ISLNK(info.external_attr >> 16), 'Unsafe Native entry: ' + name)
            seen.add(name)
            if info.is_dir(): continue
            if name in expected:
                h = hashlib.sha256(); size = 0
                with z.open(info) as stream:
                    while chunk := stream.read(1024 * 1024):
                        size += len(chunk); b.require(size <= expected[name][0], 'Embedded size limit')
                        h.update(chunk)
                identity = (size, h.hexdigest().upper())
                b.require(identity == expected[name], 'Embedded identity mismatch: ' + name)
                payloads[name] = {'bytes': size, 'sha256': identity[1]}
            else:
                small_size += info.file_size
                b.require(small_size < 16 * 1024 * 1024, 'Unexpected large Native entry')
                entries[name] = z.read(info)
    b.require(payloads.keys() == expected.keys(), 'Missing embedded resource')
    return entries, payloads


def allowed_class(kind, name):
    bases = ('cn/piq/fcarcade/runtime/RuntimeInstaller', 'cn/piq/fcarcade/runtime/RuntimeStartupState',
             'cn/piq/fcarcade/client/runtime/RuntimeEnvironmentClient', 'cn/piq/fcarcade/client/runtime/RuntimeEnvironmentScreen') if kind == 'fc' else (
             'cn/piq/nativearcade/NativeArcadeMod', 'cn/piq/nativearcade/NativeBundledRuntime')
    return any(name == base + '.class' or name.startswith(base + '$') and name.endswith('.class') for base in bases)


def metadata(kind, old, compiled):
    expected = copy.deepcopy(tomllib.loads(old[b.META].decode()))
    expected['mods'][0]['version'] = b.VERSIONS['piq_fc_arcade' if kind == 'fc' else 'piq_native_arcade']
    if kind == 'native':
        expected['mods'][0]['description'] = 'Windows x64 host-native arcade with four input ports and bundled fixed-version arcade runtime resources. Generic cabinet networking and runtime extraction are provided by Game Console; the legacy cabinet remains local-only. No commercial ROMs or GBA runtime included.'
        for dep in expected['dependencies']['piq_native_arcade']:
            if dep['modId'] == 'piq_fc_arcade': dep['versionRange'] = '[0.31.0-alpha.42,0.32.0)'
    b.require(tomllib.loads(compiled[b.META].decode()) == expected, 'Metadata changed beyond approved version/dependency/description')


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture', type=Path)
    parser.add_argument('--freeze', action='store_true')
    parser.add_argument('--witness', type=Path)
    parser.add_argument('--witness-sha256')
    args = parser.parse_args(); before = inputs()
    if args.capture:
        b.require(before == inputs(), 'Capture drift')
        b.exclusive_json(args.capture, dict(schema='game-console-bundled42-inputs-1', captured_ns=time.time_ns(), versions=b.VERSIONS, inputs=before))
        print(json.dumps(dict(path=str(args.capture), sha256=file_hash(args.capture)))); return
    b.require(args.freeze and args.witness and file_hash(args.witness) == args.witness_sha256, 'Pinned pre-build capture required')
    witness = json.loads(args.witness.read_bytes())
    b.require(witness['schema'] == 'game-console-bundled42-inputs-1' and witness['inputs'] == before and witness['versions'] == b.VERSIONS, 'Source capture drift')
    counts, xml, _ = b.tests(witness['captured_ns'])
    prep = prepare_module(); expected = prep.expected_output(prep.PROFILE)
    prep.verify_output(prep.DEFAULT_OUTPUT, prep.PROFILE)
    old = {kind: b.load(path)[1] for kind, (path, _) in BASE.items()}
    fc, fc_evidence = b.compiled_part('piq-fc-arcade', 'piq_fc_arcade', ('cn/piq/fcarcade/', 'cn/piq/retro/'), True)
    native_path = ROOT / 'piq-native-arcade/build/libs/piq_native_arcade-0.1.0-alpha.17.jar'
    native_hash = file_hash(native_path)
    native, payloads = native_archive(native_path, expected)
    classes_root = ROOT / 'piq-native-arcade/build/classes/java/main'
    classes = {p.relative_to(classes_root).as_posix(): file_hash(p) for p in classes_root.rglob('*.class')}
    b.require(classes == {n: b.sha(v) for n, v in native.items() if n.endswith('.class')}, 'Native compiler/JAR class mismatch')
    for name in classes:
        source = ROOT / 'piq-native-arcade/src/main/java' / (name[:-6].split('$')[0] + '.java')
        b.require(source.is_file() and (classes_root / name).stat().st_mtime_ns >= source.stat().st_mtime_ns, 'Native source/class age mismatch')
    proposed, changes = {}, {}
    prior_inputs = json.loads(PRIOR_WITNESS.read_bytes())['inputs']
    preserved = []
    for kind, compiled in [('fc', fc), ('native', native)]:
        metadata(kind, old[kind], compiled)
        final = dict(compiled)
        change = []
        for name in old[kind].keys() | compiled.keys():
            if old[kind].get(name) == compiled.get(name): continue
            change.append(name)
            if name in (b.META, b.MANIFEST): continue
            if name.endswith('.class'):
                b.require(allowed_class(kind, name), 'Unexpected business class change: ' + name); continue
            # Preserve the two known FC source-draft controller PNGs from the last verified release.
            path = 'piq-fc-arcade/src/main/resources/' + name
            b.require(kind == 'fc' and name in old[kind] and name.endswith('.png') and name in compiled
                      and prior_inputs.get(path) == b.sha(compiled[name]), 'Unapproved non-code resource change: ' + name)
            final[name] = old[kind][name]; preserved.append(name)
        expected_manifest = re.sub(rb'(?m)^(Implementation-Version: )[^\r\n]+',
            lambda m: m[1] + b.VERSIONS['piq_fc_arcade' if kind == 'fc' else 'piq_native_arcade'].encode(), old[kind][b.MANIFEST])
        b.require(compiled[b.MANIFEST] == expected_manifest, 'Unexpected manifest difference')
        changes[kind] = sorted(n for n in old[kind].keys() | final.keys() if old[kind].get(n) != final.get(n))
        proposed[kind] = final
    b.require(len(preserved) <= 2, 'Unexpected FC source asset drift')
    graph = b.identity_graph(proposed)
    graph['embedded_runtime_resources'] = payloads
    compiled_pins = {fc_evidence['path']: fc_evidence['sha256'], native_path.relative_to(ROOT).as_posix(): native_hash}
    def fence():
        b.require(inputs() == before and file_hash(args.witness) == args.witness_sha256, 'Input drift')
        b.require(xml == {p: file_hash(ROOT / p) for p in xml}, 'XML drift')
        b.require(compiled_pins == {p: file_hash(ROOT / p) for p in compiled_pins}, 'Compiler output drift')
        prep.verify_output(prep.DEFAULT_OUTPUT, prep.PROFILE)
    fence(); b.require(not OUT.exists(), 'Output directory already exists'); OUT.mkdir()
    fc_raw = b.jar_bytes(proposed['fc'])
    with (OUT / NAMES['fc']).open('xb') as stream: stream.write(fc_raw)
    with native_path.open('rb') as source, (OUT / NAMES['native']).open('xb') as dest: shutil.copyfileobj(source, dest, 1024 * 1024)
    b.require(b.load(OUT / NAMES['fc'])[1] == proposed['fc'], 'Final FC mismatch')
    final_native, final_payloads = native_archive(OUT / NAMES['native'], expected)
    b.require(final_native == proposed['native'] and final_payloads == payloads, 'Final Native mismatch')
    b.require(file_hash(OUT / NAMES['native']) == native_hash, 'Native copy mismatch')
    fence()
    mods = {kind: dict(filename=name, bytes=(OUT / name).stat().st_size, sha256=file_hash(OUT / name), changed_entries=changes[kind]) for kind, name in NAMES.items()}
    report = dict(schema='game-console-bundled42-build-1', ok=True, source_witness_sha256=args.witness_sha256,
        source_witness=witness, mods=mods, compiled=compiled_pins, native_class_hashes=classes,
        identity=graph, tests=counts, test_xml=xml, preserved_frozen_assets=preserved,
        embedded_payload_count=6, embedded_payload_bytes=432744687,
        runtime_original_sha256=RUNTIME_SHA, installed=False, published=False, native_core_executed=False,
        real_minecraft_or_multiplayer_tested=False)
    b.exclusive_json(OUT / 'build-witness.json', report)
    print(json.dumps(dict(ok=True, output=str(OUT), mods=mods, tests=counts), ensure_ascii=False))


if __name__ == '__main__': main()
