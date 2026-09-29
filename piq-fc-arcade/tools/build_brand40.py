"""Brand-only freeze on pinned sync39. Reuse its audited compiler/archive checks.

Never installs, publishes, runs Gradle, or rewrites a previous witness. Capture
inputs before root's actual check/jar tasks; --freeze then requires fresh tests.
"""
from __future__ import annotations
import argparse
import copy
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import sys
import time

HERE = Path(__file__).resolve()
UTIL = HERE.with_name('build_sync39.py')
assert hashlib.sha256(UTIL.read_bytes()).hexdigest().upper() == '7F9E8809360BBEFDDCE9D317146EC4943DDB1A71A09472A68F065370DA6E0F4A'
spec = importlib.util.spec_from_file_location('brand40_build_util', UTIL)
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
b.BASE_DIR = b.BUILD / 'review-sync39-v1'
b.BASE_WITNESS_SHA = 'B383A90CC98B803E6F9EA877D5B2AE7056501D53AFE2936C1DB4EB8E7D5BFD53'
b.BASE = {
    'fc': ('piq_fc_arcade-0.31.0-alpha.39.jar', '5A62F72A226216CBE396B5E26B371C17F133CF21263E2EB312556EDF14B633D2'),
    'native': ('piq_native_arcade-0.1.0-alpha.14.jar', 'BA22AE12CEF793A3346DC9FA700FA547027EEEBA1B5C95D8F7D97DD6CCE06651'),
    'sfc': ('piq_sfc-0.1.0-alpha.23.jar', '70E205483F707A9801DC0D8F9D1D8398C14678D824BB82DDA010E3ECD22429EC'),
    'gba': ('piq_gba-0.1.0-alpha.7.jar', 'B2A440F3F80685E3BBB52DEEE63064B26F5F9910BE2DC9EDE5118209E9D2F6AD'),
}
b.VERSIONS = {'piq_fc_arcade': '0.31.0-alpha.40', 'piq_native_arcade': '0.1.0-alpha.15',
              'piq_sfc_home': '0.1.0-alpha.24', 'piq_sfc_arcade': '0.2.0-alpha.8', 'piq_gba': '0.1.0-alpha.8'}
FILENAMES = {'fc': 'block_arcade', 'native': 'block_arcade_cabinets', 'sfc': 'block_arcade_sfc', 'gba': 'block_arcade_gba'}
b.NAMES = {k: FILENAMES[k] + '-' + b.VERSIONS[v] + '.jar' for k, v in b.OWNERS.items()}
b.TEST_LIMITS['piq-fc-arcade'] = (1640, 8)
b.TEST_NOTES = b.ROOT / 'piq-fc-arcade/design/方块电玩-首次安装与更新说明-20260914.md'
LANG_ONLY = copy.deepcopy(b.LANG)
EXTRA = {
    'fc': {
        'pack.mcmeta': 'piq-fc-arcade/src/main/resources/pack.mcmeta',
        **{f'assets/piq_fc_arcade/textures/{p}': f'piq-fc-arcade/src/main/resources/assets/piq_fc_arcade/textures/{p}' for p in
           ('block/home_famicom_console.png', 'block/home_subor_sb926.png', 'item/zapper/skin.png', 'block/famicom_brand.png')},
    },
    'native': {'pack.mcmeta': 'piq-native-arcade/src/main/resources/pack.mcmeta'},
    'sfc': {'pack.mcmeta': 'piq-sfc-arcade/src/main/resources/pack.mcmeta',
            'assets/piq_sfc_home/textures/block/user_sfc_20260911.png': 'piq-sfc-home/src/main/resources/assets/piq_sfc_home/textures/block/user_sfc_20260911.png'},
    'gba': {'assets/piq_gba/textures/item/handheld.png': 'piq-gba/src/main/resources/assets/piq_gba/textures/item/handheld.png'},
}
for kind in b.LANG:
    b.LANG[kind].update(EXTRA[kind])  # Shared allowlist, not a claim PNGs are language.
CLASS_ALLOW = {
    'fc': ['cn/piq/fcarcade/client/cabinet/CabinetMenuScreen', 'cn/piq/fcarcade/client/cabinet/CabinetPlayScreen',
           'cn/piq/fcarcade/client/cabinet/CabinetSetupScreen', 'cn/piq/fcarcade/client/runtime/RuntimeEnvironmentScreen',
           'cn/piq/fcarcade/client/ScoreCalibrationSession', 'cn/piq/fcarcade/home/HomeSyncSettings'],
    'native': ['cn/piq/nativearcade/client/NativeArcadeSetupScreen'], 'sfc': [], 'gba': [],
}
TITLES = {'fc': '方块电玩', 'native': 'Block Arcade: Cabinets', 'sfc': 'Block Arcade: SFC', 'gba': 'Block Arcade: GBA'}


def read_baselines():
    b.require(b.file_sha(b.BASE_DIR / 'build-witness.json') == b.BASE_WITNESS_SHA, 'Pinned sync39 witness changed')
    witness = json.loads((b.BASE_DIR / 'build-witness.json').read_bytes())
    b.require(witness['ok'] and witness['schema'] == 'piq-sync39-build-1', 'Wrong sync39 witness')
    result = {}
    for kind, (name, pin) in b.BASE.items():
        actual, entries = b.load(b.BASE_DIR / name)
        b.require(actual == pin, 'Frozen sync39 JAR changed: ' + kind)
        result[kind] = entries
    return result, witness['inputs']


def overlay(kind, old):
    out = {}
    for name, source in LANG_ONLY[kind].items():
        raw = b.safe(b.ROOT / source, True).read_bytes()
        before, after = b.language(old[name], name), b.language(raw, name)
        b.require(list(before) == list(after), 'Language keys/order changed: ' + name)
        for key in before:
            b.require(re.findall(r'%(?:[0-9]+\$)?[sd%]', before[key]) == re.findall(r'%(?:[0-9]+\$)?[sd%]', after[key]), 'Placeholder changed: ' + key)
            b.require(not re.search(r'\bPIQ\b', after[key], re.I), 'Visible old brand: ' + key)
        out[name] = raw
    for name, source in EXTRA[kind].items():
        b.require(name in old, 'Unreviewed added resource: ' + name)
        raw = b.safe(b.ROOT / source, True).read_bytes()
        if name == 'pack.mcmeta':
            before, after = json.loads(old[name]), json.loads(raw)
            before['pack']['description'] = after['pack']['description']
            b.require(before == after and 'PIQ' not in after['pack']['description'], 'Resource pack semantics changed')
        out[name] = raw
    return out


def manifest(kind, old):
    raw = old[b.MANIFEST]
    for key, value in [('Implementation-Version', b.VERSIONS[b.OWNERS[kind]]), ('Implementation-Title', TITLES[kind])]:
        raw, n = re.subn(rb'(?m)^' + key.encode() + rb': [^\r\n]*(?:\r?\n [^\r\n]*)*\r?\n',
                         lambda m: key.encode() + b': ' + value.encode('utf-8') + b'\r\n', raw)
        b.require(n == 1, 'Ambiguous manifest field: ' + key)
    return raw


b.read_baselines = read_baselines
b.language_overlay = overlay
b.version_manifest = manifest
ORIGINAL_SNAPSHOT = b.snapshot


def snapshot():
    result = ORIGINAL_SNAPSHOT()
    for path in (HERE, HERE.with_name('restore_device_brand40.py'), HERE.with_name('generate_famicom_textures.py'), HERE.with_name('refresh_legacy_plate40.py')):
        result[path.relative_to(b.ROOT).as_posix()] = b.file_sha(path)
    return result


b.snapshot = snapshot


def main():
    sys.stdout.reconfigure(encoding='utf-8'); sys.stderr.reconfigure(encoding='utf-8')
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--capture-inputs', type=Path)
    ap.add_argument('--source-witness', type=Path)
    ap.add_argument('--source-witness-sha256')
    ap.add_argument('--report', type=Path)
    ap.add_argument('--freeze', action='store_true')
    ap.add_argument('--output', type=Path, default=b.BUILD / 'review-brand40-v1')
    args = ap.parse_args()
    started = time.time_ns(); before = snapshot()
    if args.capture_inputs:
        b.require(not args.freeze and not args.report and before == snapshot(), 'Capture must be separate and stable')
        witness = dict(schema='block-arcade-brand40-source-1', captured_ns=started, inputs=before, versions=b.VERSIONS)
        b.exclusive_json(args.capture_inputs, witness)
        print(json.dumps(dict(witness=str(args.capture_inputs), sha256=b.file_sha(args.capture_inputs)))); return
    b.require(args.report or args.freeze, 'Choose report or explicit freeze')
    witness = None
    if args.freeze:
        b.require(args.source_witness and b.file_sha(args.source_witness) == args.source_witness_sha256, 'Pinned source witness required')
        witness = json.loads(args.source_witness.read_bytes())
        b.require(witness['schema'] == 'block-arcade-brand40-source-1' and witness['inputs'] == before and witness['versions'] == b.VERSIONS, 'Source/version drift')
        b.require(not args.output.exists() and b.safe(args.output).is_relative_to(b.BUILD) and args.output != b.BUILD, 'New build child required')
        counts, xml, _ = b.tests(witness['captured_ns'])
    report, staged, proposed = b.build_plan(before, args.freeze, b.JAVA)
    report['schema'] = 'block-arcade-brand40-build-1'
    baselines, _ = read_baselines()
    if args.freeze:
        for kind, new in proposed.items():
            old = baselines[kind]
            b.require(new.keys() == old.keys(), 'Branding must not add/remove JAR entries: ' + kind)
            changed_classes = [n for n in old if n.endswith('.class') and old[n] != new[n]]
            b.require(all(any(n == p + '.class' or n.startswith(p + '$') for p in CLASS_ALLOW[kind]) for n in changed_classes), 'Unapproved business class change: ' + repr(changed_classes))
            report['mods'][kind]['changed_brand_classes'] = changed_classes
        b.require(report['ok'] and before == snapshot(), 'Invalid freeze or source drift')
        b.require(xml == {p: b.file_sha(b.ROOT / p) for p in xml}, 'Test outputs changed during freeze')
        b.require(b.file_sha(args.source_witness) == args.source_witness_sha256, 'Source witness changed during freeze')
        for details in report['mods'].values():
            for part in details['compiled']:
                b.require(b.file_sha(b.ROOT / part['path']) == part['sha256'], 'Compiled JAR changed during freeze')
        report.update(tests=counts, test_xml=xml, inputs=before, source_witness=witness,
                      source_witness_sha256=args.source_witness_sha256, created_utc=datetime.now(timezone.utc).isoformat())
        args.output.mkdir(parents=True)
        for kind, raw in staged.items():
            path = args.output / b.NAMES[kind]
            with path.open('xb') as stream: stream.write(raw)
            pin, entries = b.load(path)
            b.require(pin == b.sha(raw) and entries == proposed[kind], 'Final JAR readback mismatch')
            report['mods'][kind].update(filename=path.name, sha256=pin, bytes=len(raw))
        b.exclusive_json(args.output / 'source-witness.json', witness)
        with (args.output / b.TEST_NOTES.name).open('xb') as stream: stream.write(b.TEST_NOTES.read_bytes())
        b.require(before == snapshot(), 'Source changed on final write')
        b.require(b.file_sha(args.source_witness) == args.source_witness_sha256, 'Source witness changed on final write')
        for details in report['mods'].values():
            for part in details['compiled']:
                b.require(b.file_sha(b.ROOT / part['path']) == part['sha256'], 'Compiled JAR changed on final write')
        b.require(xml == {p: b.file_sha(b.ROOT / p) for p in xml}, 'Test outputs changed on final write')
        for kind, (name, pin) in b.BASE.items():
            b.require(b.file_sha(b.BASE_DIR / name) == pin, 'Baseline changed on final write')
        b.exclusive_json(args.output / 'build-witness.json', report)
    if args.report: b.exclusive_json(args.report, report)
    print(json.dumps(dict(ok=report['ok'], output=str(args.output) if args.freeze else None,
                         issues=report['validation_errors'], resources=report['blocking_resources'],
                         jars={k: v.get('sha256') for k, v in report['mods'].items()}), ensure_ascii=False))


if __name__ == '__main__':
    main()
