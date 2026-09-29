"""Freeze a display-only Arcade16 preview against pinned Native15 and FC41.

Run --capture before real Gradle check/jar, then --freeze with its SHA. No native
core execution, installation, publication, downloads or historical overwrites.
"""
import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys
import time
import tomllib

ROOT = Path(__file__).resolve().parents[2]
UTIL = ROOT / 'piq-fc-arcade/tools/build_sync39.py'
assert hashlib.sha256(UTIL.read_bytes()).hexdigest().upper() == '7F9E8809360BBEFDDCE9D317146EC4943DDB1A71A09472A68F065370DA6E0F4A'
spec = importlib.util.spec_from_file_location('arcade16_freeze_util', UTIL)
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
b.PROJECTS = ['piq-native-arcade']
b.VERSIONS = {'piq_fc_arcade': '0.31.0-alpha.41', 'piq_native_arcade': '0.1.0-alpha.16'}
b.TEST_LIMITS = {'piq-native-arcade': (80, 0)}
OLD = ROOT / 'piq-fc-arcade/build/review-brand40-v1/block_arcade_cabinets-0.1.0-alpha.15.jar'
OLD_PIN = 'C84D42D3455646B94B7CF0920E201871F68C5E061ADF1C883B764DC80646981A'
FC = ROOT / 'piq-fc-arcade/build/review-public41-v1/game_console-0.31.0-alpha.41.jar'
FC_PIN = '27DA245957D64D88BE105F8AC3911C059A01FC60CAA2548776AC2A376390674D'
FC_COMPILE = ROOT / 'piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.41.jar'
OUTPUT = ROOT / 'piq-fc-arcade/build/review-arcade16-v1'
NAME = 'game_console_arcade-0.1.0-alpha.16.jar'


def inputs():
    result = b.snapshot()
    for path in (Path(__file__).resolve(), OLD, FC, FC_COMPILE):
        result[path.relative_to(ROOT).as_posix()] = b.file_sha(path)
    b.require(b.file_sha(OLD) == OLD_PIN and b.file_sha(FC) == FC_PIN, 'Frozen baseline/FC changed')
    return result


def renamed(text):
    return text.replace('Block Arcade: Cabinets', 'Game Console: Arcade').replace('Block Arcade: Cabinet Resources', 'Game Console: Arcade Resources').replace('Block Arcade', 'Game Console')


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--capture', type=Path)
    ap.add_argument('--freeze', action='store_true')
    ap.add_argument('--witness', type=Path)
    ap.add_argument('--witness-sha256')
    args = ap.parse_args()
    initial = inputs()
    if args.capture:
        b.require(not args.freeze and initial == inputs(), 'Capture drift')
        b.exclusive_json(args.capture, dict(schema='game-console-arcade16-inputs-1', captured_ns=time.time_ns(), inputs=initial, versions=b.VERSIONS))
        print(json.dumps(dict(path=str(args.capture), sha256=b.file_sha(args.capture)))); return
    b.require(args.freeze and args.witness and b.file_sha(args.witness) == args.witness_sha256, 'Pinned capture required')
    witness = json.loads(args.witness.read_bytes())
    b.require(witness['schema'] == 'game-console-arcade16-inputs-1' and witness['inputs'] == initial and witness['versions'] == b.VERSIONS, 'Witness/source drift')
    counts, xml, _ = b.tests(witness['captured_ns'])
    _, old = b.load(OLD)
    _, fc = b.load(FC)
    _, fc_compile = b.load(FC_COMPILE)
    b.require({k: v for k, v in fc.items() if k.endswith('.class')} == {k: v for k, v in fc_compile.items() if k.endswith('.class')}, 'FC compile classpath is not final FC41 classes')
    compiled, evidence = b.compiled_part('piq-native-arcade', 'piq_native_arcade', ('cn/piq/nativearcade/',), True)
    b.require(set(compiled) == set(old), 'Native JAR entry additions/removals')
    expected_meta = copy.deepcopy(tomllib.loads(old[b.META].decode('utf-8')))
    expected_meta['mods'][0]['version'] = b.VERSIONS['piq_native_arcade']
    expected_meta['mods'][0]['description'] = renamed(expected_meta['mods'][0]['description'])
    for dep in expected_meta['dependencies']['piq_native_arcade']:
        if dep['modId'] == 'piq_fc_arcade':
            dep['versionRange'] = '[0.31.0-alpha.41,0.32.0)'
    b.require(tomllib.loads(compiled[b.META].decode('utf-8')) == expected_meta, 'Unexpected metadata/permission change')
    for locale in ('en_us', 'zh_cn'):
        name = 'assets/piq_native_arcade/lang/' + locale + '.json'
        before, after = b.language(old[name], name), b.language(compiled[name], name)
        expected = {k: renamed(v) if locale == 'en_us' else v for k, v in before.items()}
        b.require(list(before) == list(after) and after == expected, 'Language key/value drift')
        b.require(compiled[name] == (ROOT / 'piq-native-arcade/src/main/resources' / name).read_bytes(), 'Language source mismatch')
    pack = json.loads(old['pack.mcmeta'])
    pack['pack']['description'] = renamed(pack['pack']['description'])
    b.require(json.loads(compiled['pack.mcmeta']) == pack, 'Pack metadata changed beyond name')
    expected_manifest = old[b.MANIFEST]
    for field, value in [('Implementation-Version', '0.1.0-alpha.16'), ('Implementation-Title', 'Game Console: Arcade')]:
        expected_manifest, n = re.subn(rb'(?m)^' + field.encode() + rb': [^\r\n]*(?:\r?\n [^\r\n]*)*\r?\n',
                                      lambda m: field.encode() + b': ' + value.encode() + b'\r\n', expected_manifest)
        b.require(n == 1, 'Ambiguous manifest field')
    b.require(compiled[b.MANIFEST] == expected_manifest, 'Unapproved manifest change')
    allowed = {b.META, b.MANIFEST, 'pack.mcmeta', 'assets/piq_native_arcade/lang/en_us.json'}
    changes = sorted(n for n in old if old[n] != compiled[n])
    b.require(set(changes) <= allowed and all(compiled[n] == old[n] for n in old if n.endswith('.class')), 'Business class or protected asset changed')
    graph = b.identity_graph({'fc': fc, 'native': compiled})
    raw = b.jar_bytes(compiled)
    b.require(b.archive(raw) == compiled and initial == inputs(), 'Roundtrip/source fence failed')
    b.require(xml == {p: b.file_sha(ROOT / p) for p in xml}, 'Test output drift')
    b.require(not OUTPUT.exists(), 'Output exists, never overwrite frozen preview')
    OUTPUT.mkdir()
    with (OUTPUT / NAME).open('xb') as stream:
        stream.write(raw)
    b.require(b.file_sha(OUTPUT / NAME) == b.sha(raw) and initial == inputs(), 'Final output/source fence failed')
    b.require(evidence['sha256'] == b.file_sha(ROOT / evidence['path'])
              and b.file_sha(args.witness) == args.witness_sha256 and xml == {p: b.file_sha(ROOT / p) for p in xml}, 'Final evidence fence failed')
    result = dict(schema='game-console-arcade16-build-1', ok=True, filename=NAME, bytes=len(raw), sha256=b.sha(raw),
        baseline_sha256=OLD_PIN, fc_sha256=FC_PIN, source_witness_sha256=args.witness_sha256, source_witness=witness,
        compiled=evidence, changed_entries=changes, changed_classes=[], identity=graph, tests=counts, test_xml=xml,
        scope='standalone add-on preview; external native runtime is NOT bundled into this JAR',
        installed=False, published=False, minecraft_started=False, native_core_executed=False, real_multiplayer_tested=False)
    b.exclusive_json(OUTPUT / 'build-witness.json', result)
    print(json.dumps({k: result[k] for k in ('ok', 'filename', 'bytes', 'sha256', 'changed_entries', 'changed_classes', 'tests')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
