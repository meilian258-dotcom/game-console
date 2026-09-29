"""Freeze FC41/SFC25 first-install candidates; no install, download or publication.

Reuses pinned archive/compiler verification. Actual Gradle check/jar is run by
the operator between --capture and --freeze. Native/core binary bytes must remain
identical to brand40; corresponding native sources are inventoried separately.
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
import tomllib

HERE = Path(__file__).resolve()
UTIL = HERE.with_name('build_sync39.py')
assert hashlib.sha256(UTIL.read_bytes()).hexdigest().upper() == '7F9E8809360BBEFDDCE9D317146EC4943DDB1A71A09472A68F065370DA6E0F4A'
spec = importlib.util.spec_from_file_location('public41_util', UTIL)
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
b.PROJECTS = ['piq-fc-arcade', 'piq-retro-platform', 'piq-sfc-arcade', 'piq-sfc-home']
b.VERSIONS = {'piq_fc_arcade': '0.31.0-alpha.41', 'piq_sfc_arcade': '0.2.0-alpha.9', 'piq_sfc_home': '0.1.0-alpha.25'}
b.TEST_LIMITS = {'piq-fc-arcade': (1640, 8), 'piq-sfc-home': (338, 0)}
BASE = b.BUILD / 'review-brand40-v1'
PINS = {
    'fc': ('block_arcade-0.31.0-alpha.40.jar', 'C862A07ED080532DFA9B22DDD3B18925744AEBDA9F7B6AB65F5751C44F8E1068'),
    'sfc': ('block_arcade_sfc-0.1.0-alpha.24.jar', 'AB31D714A90CD3DCFCE4074B97B4A39A3B03B8C3D78BF63097587473B110C9EA'),
}
NAMES = {'fc': 'game_console-0.31.0-alpha.41.jar', 'sfc': 'game_console_sfc-0.1.0-alpha.25.jar'}
SCORE = 'cn/piq/fcarcade/client/ScoreCalibrationSession.class'


def snapshot():
    result = b.snapshot()
    result[str(HERE.relative_to(b.ROOT))] = b.file_sha(HERE)
    return result


def rename(text):
    return text.replace('Block Arcade', 'Game Console')


def manifest(kind, old):
    result = old[b.MANIFEST]
    owner = 'piq_fc_arcade' if kind == 'fc' else 'piq_sfc_home'
    changes = {'Implementation-Version': b.VERSIONS[owner]}
    if kind == 'sfc':
        changes['Implementation-Title'] = 'Game Console: SFC'
    for key, value in changes.items():
        result, count = re.subn(rb'(?m)^' + key.encode() + rb': [^\r\n]*(?:\r?\n [^\r\n]*)*\r?\n',
                                lambda m: key.encode() + b': ' + value.encode() + b'\r\n', result)
        b.require(count == 1, 'Ambiguous manifest field ' + key)
    return result


def metadata(kind, old, raw):
    expected = copy.deepcopy(tomllib.loads(old[b.META].decode('utf-8')))
    for mod in expected['mods']:
        mod['version'] = b.VERSIONS[mod['modId']]
        if 'description' in mod:
            mod['description'] = rename(mod['description'])
    for deps in expected.get('dependencies', {}).values():
        for dep in deps:
            if dep['modId'] == 'piq_fc_arcade':
                dep['versionRange'] = '[0.31.0-alpha.41,0.32.0)'
            if dep['modId'] == 'piq_sfc_arcade':
                dep['versionRange'] = '[0.2.0-alpha.9,0.3.0)'
    b.require(tomllib.loads(raw.decode('utf-8')) == expected, 'Unexpected metadata semantics ' + kind)


def overlay(kind, old, compiled):
    result = {}
    for name, source in b.LANG[kind].items():
        raw = b.safe(b.ROOT / source, True).read_bytes()
        before, after = b.language(old[name], name), b.language(raw, name)
        expected = {key: rename(value) if '/en_us.' in name else value for key, value in before.items()}
        b.require(list(before) == list(after) and expected == after, 'Not an exact English name change ' + name)
        b.require(raw == compiled[name], 'Compiled language mismatch ' + name)
        if raw != old[name]:
            result[name] = raw
    raw = compiled['pack.mcmeta']
    expected = json.loads(old['pack.mcmeta'])
    expected['pack']['description'] = rename(expected['pack']['description'])
    b.require(json.loads(raw) == expected, 'Unexpected resource pack metadata')
    result['pack.mcmeta'] = raw
    return result


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--capture', type=Path)
    ap.add_argument('--amend-capture', type=Path)
    ap.add_argument('--amend-sha256')
    ap.add_argument('--freeze', action='store_true')
    ap.add_argument('--witness', type=Path)
    ap.add_argument('--witness-sha256')
    ap.add_argument('--output', type=Path, default=b.BUILD / 'review-public41-v1')
    args = ap.parse_args()
    now = snapshot()
    if args.capture:
        b.require(not args.freeze and now == snapshot(), 'Capture source drift')
        capture_ns = time.time_ns()
        extra = {}
        if args.amend_capture:
            b.require(b.file_sha(args.amend_capture) == args.amend_sha256, 'Amended capture pin mismatch')
            prior = json.loads(args.amend_capture.read_bytes())
            changed = sorted(p for p in prior['inputs'].keys() | now.keys() if prior['inputs'].get(p) != now.get(p))
            b.require(prior['schema'] == 'game-console-public41-inputs-1' and prior['versions'] == b.VERSIONS
                      and changed == [str(HERE.relative_to(b.ROOT))], 'Amendment may only change this freeze verifier, never production inputs')
            extra = dict(supersedes_sha256=args.amend_sha256, amendment_only_files=changed,
                         tests_not_before_ns=prior.get('tests_not_before_ns', prior['captured_ns']))
        b.exclusive_json(args.capture, dict(schema='game-console-public41-inputs-1', captured_ns=capture_ns, inputs=now, versions=b.VERSIONS,
            scope='Java/resources/Gradle inputs; native corresponding source inventory is a separate candidate artifact.', **extra))
        print(json.dumps(dict(path=str(args.capture), sha256=b.file_sha(args.capture)))); return
    b.require(args.freeze and args.witness and b.file_sha(args.witness) == args.witness_sha256, 'Pinned capture required')
    witness = json.loads(args.witness.read_bytes())
    b.require(witness['schema'] == 'game-console-public41-inputs-1' and witness['inputs'] == now and witness['versions'] == b.VERSIONS, 'Input/version drift')
    b.require(b.file_sha(BASE / 'build-witness.json') == '53FCD93A9C8658C29C2E63A1871D50479B7499D9EEF1CB7BB8A4FECDAA9BC27C', 'Baseline witness changed')
    base_inputs = json.loads((BASE / 'build-witness.json').read_bytes())['inputs']
    counts, xml, _ = b.tests(witness.get('tests_not_before_ns', witness['captured_ns']))
    proposed, staged, details = {}, {}, {}
    for kind, (filename, pin) in PINS.items():
        actual, old = b.load(BASE / filename)
        b.require(actual == pin, 'Baseline changed ' + kind)
        parts, evidence = [], []
        for project, mod, prefixes in b.PARTS[kind]:
            entries, item = b.compiled_part(project, mod, prefixes, True)
            parts.append(entries); evidence.append(item)
        compiled = parts[0] if kind == 'fc' else b.merge_sfc(parts)
        metadata(kind, old, compiled[b.META])
        approved = overlay(kind, old, compiled)
        old_classes = {n: raw for n, raw in old.items() if n.endswith('.class')}
        new_classes = {n: raw for n, raw in compiled.items() if n.endswith('.class')}
        b.require(old_classes.keys() == new_classes.keys(), 'Class additions/removals ' + kind)
        changed_classes = sorted(n for n in old_classes if old_classes[n] != new_classes[n])
        b.require(changed_classes == ([SCORE] if kind == 'fc' else []), 'Unapproved class change ' + repr(changed_classes))
        if kind == 'fc':
            before_literal = b'Block Arcade FC score calibration report\n'
            after_literal = b'Game Console FC score calibration report\n'
            b.require(len(before_literal) == len(after_literal) and old[SCORE].count(before_literal) == 1
                      and new_classes[SCORE] == old[SCORE].replace(before_literal, after_literal),
                      'Score class is not the exact single constant-pool literal change')
            source_path = 'piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/ScoreCalibrationSession.java'
            source_raw = (b.ROOT / source_path).read_bytes()
            old_text, new_text = before_literal[:-1], after_literal[:-1]
            b.require(source_raw.count(new_text) == 1 and b.sha(source_raw.replace(new_text, old_text)) == base_inputs[source_path],
                      'Score source has changes beyond the approved literal')
        differences = []
        for name in sorted(old.keys() | compiled.keys()):
            if name in (b.META, b.MANIFEST) or name in approved or name.endswith('.class') or old.get(name) == compiled.get(name):
                continue
            matches = [project + '/src/main/resources/' + name for project, _, _ in b.PARTS[kind]
                       if name in compiled and base_inputs.get(project + '/src/main/resources/' + name) == b.sha(compiled[name])]
            b.require(matches, 'Unrecognized resource drift ' + kind + ':' + name)
            differences.append(dict(entry=name, action='preserve_frozen_resource_or_omission', baseline_sources=matches))
        final = dict(old)
        final.update(approved)
        final.update({n: new_classes[n] for n in changed_classes})
        final[b.META] = compiled[b.META]
        final[b.MANIFEST] = manifest(kind, old)
        allowed = set(approved) | {b.META, b.MANIFEST} | set(changed_classes)
        b.require(final.keys() == old.keys() and all(final[n] == old[n] for n in old if n not in allowed), 'Protected final entry changed')
        raw = b.jar_bytes(final)
        b.require(b.archive(raw) == final, 'Generated JAR verification failed')
        staged[kind] = raw; proposed[kind] = final
        details[kind] = dict(filename=NAMES[kind], bytes=len(raw), sha256=b.sha(raw), baseline_sha256=pin,
            compiled=evidence, changed_entries=[n for n in sorted(old) if final[n] != old[n]], changed_classes=changed_classes,
            known_source_resource_drift=differences, unchanged_entries=sum(final[n] == old[n] for n in old))
    graph = b.identity_graph(proposed)
    b.require(now == snapshot() and xml == {p: b.file_sha(b.ROOT / p) for p in xml}, 'Source/tests changed during freeze')
    for data in details.values():
        for part in data['compiled']:
            b.require(part['sha256'] == b.file_sha(b.ROOT / part['path']), 'Compiled input changed')
    b.require(b.file_sha(args.witness) == args.witness_sha256, 'Capture changed')
    output = b.safe(args.output)
    b.require(output.is_relative_to(b.BUILD) and output != b.BUILD and not output.exists(), 'New build child directory required')
    output.mkdir()
    for kind, raw in staged.items():
        with (output / NAMES[kind]).open('xb') as stream:
            stream.write(raw)
        b.require(b.file_sha(output / NAMES[kind]) == b.sha(raw), 'Output file SHA mismatch')
    b.require(now == snapshot() and xml == {p: b.file_sha(b.ROOT / p) for p in xml}, 'Source/tests changed during output write')
    b.require(b.file_sha(args.witness) == args.witness_sha256
              and b.file_sha(BASE / 'build-witness.json') == '53FCD93A9C8658C29C2E63A1871D50479B7499D9EEF1CB7BB8A4FECDAA9BC27C',
              'Witness changed during output write')
    for kind, (filename, pin) in PINS.items():
        b.require(b.file_sha(BASE / filename) == pin, 'Baseline changed during freeze')
    for data in details.values():
        for part in data['compiled']:
            b.require(part['sha256'] == b.file_sha(b.ROOT / part['path']), 'Compiled input changed during output write')
    report = dict(schema='game-console-public41-build-1', ok=True, created_utc=datetime.now(timezone.utc).isoformat(),
        source_witness_sha256=args.witness_sha256, source_witness=witness, inputs=now, mods=details, identity=graph, tests=counts, test_xml=xml,
        status='local first-install release-form candidate; not cleared for public redistribution',
        limitations=['No real Minecraft/fresh-machine/two-client/server/controller acceptance in this turn.',
            'SPC700 boot ROM rights, complete prebuilt-native dependency provenance and asset redistribution rights remain unresolved.',
            'Native and GBA JARs are not part of this FC/SFC candidate. Their English source changes are not new released artifacts.'],
        installed=False, published=False, minecraft_started=False, real_multiplayer_tested=False)
    b.exclusive_json(output / 'build-witness.json', report)
    print(json.dumps(dict(output=str(output), mods={k: {field: v[field] for field in ('filename', 'bytes', 'sha256', 'changed_classes')} for k, v in details.items()}, tests=counts), ensure_ascii=False))


if __name__ == '__main__':
    main()
