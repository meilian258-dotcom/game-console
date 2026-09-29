"""Freeze reviewed GC-018 FC45/SFC26 outputs; never build, install or publish.

Workflow: draft --plan-template; root reviews and approves it after ALL editors finish;
--capture; root runs FC then SFC `check jar --offline --rerun-tasks` into separate
UTF-8 logs; --freeze. A capture before the last edit is deliberately invalidated.
Only two new JARs are made. Native 0.1.0 and GBA8 remain pinned external references.
"""
from pathlib import Path
import argparse
import copy
import hashlib
import importlib.util
import json
import re
import stat
import sys
import time
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[2]
PRIOR_TOOL = ROOT / 'piq-fc-arcade/tools/build_arcade_release44.py'
PRIOR_SHA = 'B68DF7995633810841FD9D1FBC143D4F208532F8F09345EF43115F10AB8BB422'
if hashlib.sha256(PRIOR_TOOL.read_bytes()).hexdigest().upper() != PRIOR_SHA:
    raise ValueError('Pinned archive/freshness helper changed')
_spec = importlib.util.spec_from_file_location('gc018_fixed_helpers', PRIOR_TOOL)
a = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(a)
b = a.b
b.PROJECTS = ['piq-fc-arcade', 'piq-retro-platform', 'piq-sfc-home', 'piq-sfc-arcade']
b.VERSIONS = {'piq_fc_arcade': '0.31.0-alpha.45', 'piq_sfc_home': '0.1.0-alpha.26',
              'piq_sfc_arcade': '0.2.0-alpha.9', 'piq_native_arcade': '0.1.0', 'piq_gba': '0.1.0-alpha.8'}
b.TEST_LIMITS = {'piq-fc-arcade': (1721, 8), 'piq-sfc-home': (338, 0)}
PROJECT = {'fc': 'piq-fc-arcade', 'sfc': 'piq-sfc-home'}
OWNER = {'fc': 'piq_fc_arcade', 'sfc': 'piq_sfc_home'}
PREFIXES = {'fc': ('cn/piq/fcarcade/', 'cn/piq/retro/'), 'sfc': ('cn/piq/sfchome/',)}
a.PROJECT, a.PREFIXES = PROJECT, PREFIXES
OUT = ROOT / 'piq-fc-arcade/build/review-gc018-v1'
NAMES = {'fc': 'game_console-0.31.0-alpha.45.jar', 'sfc': 'game_console_sfc-0.1.0-alpha.26.jar'}
PLAN = ROOT / 'outputs/gc018/gc018-45-changes.json'
SOURCE_BEFORE = ROOT / 'outputs/gc018/source-before.json'
SOURCE_BEFORE_SHA = 'B3D58BF44145AEA4CF26DE7B8F593044B235F644331A9A34A093559059682B7C'
BASE = {
    'fc': (ROOT / 'piq-fc-arcade/build/review-arcade44-v1/game_console-0.31.0-alpha.44.jar', '7E9EA75C03D8FAECFB7D48EDDEB6D05C87C5681D445CE2A4D56662EB3DD0EE35'),
    'sfc': (ROOT / 'piq-fc-arcade/build/review-public41-v1/game_console_sfc-0.1.0-alpha.25.jar', 'C0B15FEE1A05C914296DFC04E8B37B7FC45881F7102F6F6BAE3E82FDE3C49C78'),
}
BASE_WITNESSES = {
    'fc': (BASE['fc'][0].parent / 'build-witness.json', '1FE6119059E1B900B9BDC8A3D1D01DAAC3C42AD95F581CAA8A7253FAFA540B72'),
    'sfc': (BASE['sfc'][0].parent / 'build-witness.json', '4D4A619D2197739C0EB9FCF13FBDBAEAC14EBF1CBBBC817864785B9CB467FEB7'),
}
CORE = (ROOT / 'piq-sfc-arcade/build/libs/piq_sfc_arcade-0.2.0-alpha.9.jar',
        'D562770C8FD05D2D0837E4614259B315825D354DF2178AE7B8931265DEC050C5')
REFERENCES = {
    'native': (ROOT / 'piq-fc-arcade/build/review-arcade44-v1/game_console_arcade-0.1.0.jar', 'C43F42E65752311F496FD164614525213B80C1FAE9CD0CE0E243D5C7782FB1AA'),
    'gba': (ROOT / 'piq-fc-arcade/build/review-brand40-v1/block_arcade_gba-0.1.0-alpha.8.jar', 'D04C20F16085DCAE558626966C16254815FDF02B8A7CD91F06D8ED412B0C44BD'),
}
COMPANIONS = [ROOT / 'outputs/gc018/verify_gc018_45.py', ROOT / 'outputs/gc018/test_build_gc018_45.py']
file_hash, unique_json = a.file_hash, a.unique_json


def rel(path):
    return b.safe(path).relative_to(ROOT).as_posix()


def member_name(name):
    b.require(isinstance(name, str) and name and not name.startswith('/') and '\\' not in name and ':' not in name
              and not any(ord(c) < 32 or ord(c) == 127 for c in name)
              and all(p not in ('', '.', '..') for p in name.rstrip('/').split('/')), 'Unsafe archive member: ' + repr(name))


def allowed_class(kind, name, plan):
    return name.endswith('.class') and any(name == root + '.class' or name.startswith(root + '$')
        for root in plan['class_roots'][kind])


def read_plan(path=PLAN):
    plan = unique_json(b.safe(path, True).read_bytes())
    b.require(plan.get('schema') == 'gc018-45-reviewed-changes-1' and plan.get('approved') is True,
              'Root must review and approve the complete change plan before capture')
    for field in ('class_roots', 'removed_classes', 'language_changes', 'resource_changes'):
        b.require(set(plan.get(field, {})) == set(PROJECT), 'Wrong plan kinds: ' + field)
    for kind in PROJECT:
        roots = plan['class_roots'][kind]
        b.require(isinstance(roots, list) and len(roots) == len(set(roots)), 'Invalid/duplicate class roots')
        for root in roots:
            b.require(isinstance(root, str) and re.fullmatch(r'[A-Za-z_][A-Za-z0-9_/]*', root)
                      and root.startswith(PREFIXES[kind]) and not root.endswith('/'), 'Invalid class root: ' + repr(root))
        removed = plan['removed_classes'][kind]
        b.require(isinstance(removed, list) and len(removed) == len(set(removed)), 'Invalid removal list')
        for name in removed:
            member_name(name)
            b.require(allowed_class(kind, name, plan), 'Removed class root not approved')
        for name, keys in plan['language_changes'][kind].items():
            b.require(re.fullmatch('assets/' + OWNER[kind] + r'/lang/(zh_cn|en_us)\.json', name)
                      and isinstance(keys, dict) and keys, 'Unexpected language path/map')
            for key, change in keys.items():
                b.require(isinstance(key, str) and isinstance(change, dict) and set(change) == {'old', 'new'}
                          and all(v is None or isinstance(v, str) for v in change.values())
                          and change['old'] != change['new'], 'Invalid exact language change')
        for name, change in plan['resource_changes'][kind].items():
            member_name(name)
            b.require(name.startswith('assets/' + OWNER[kind] + '/') and not name.endswith('.class')
                      and not '/lang/' in name and name not in a.DRAFTS
                      and name.endswith(('.json', '.png', '.ogg', '.wav')), 'Resource path/type not authorized: ' + name)
            b.require(isinstance(change, dict) and set(change) == {'old_sha256', 'new_sha256'}
                      and (change['old_sha256'] is None or re.fullmatch(r'[A-F0-9]{64}', change['old_sha256']))
                      and re.fullmatch(r'[A-F0-9]{64}', change['new_sha256'])
                      and change['old_sha256'] != change['new_sha256'], 'Resource needs exact distinct old/new hashes')
    suites = plan.get('required_test_suites', {})
    b.require(set(suites) == set(b.TEST_LIMITS), 'Both projects need required tests')
    for project, names in suites.items():
        b.require(isinstance(names, list) and names and len(names) == len(set(names)), 'Missing/duplicate required tests: ' + project)
        b.require(all(isinstance(n, str) and re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_.$]*', n) for n in names), 'Invalid suite name')
    return plan


def inputs(plan_path):
    result = b.snapshot()
    pinned = [*BASE.values(), *BASE_WITNESSES.values(), CORE, *REFERENCES.values(),
              (SOURCE_BEFORE, SOURCE_BEFORE_SHA), (PRIOR_TOOL, PRIOR_SHA), (a.UTIL, a.UTIL_SHA)]
    for path, wanted in pinned:
        actual = file_hash(path)
        b.require(actual == wanted, 'Frozen input identity changed: ' + str(path))
        result[rel(path)] = actual
    for path in [Path(__file__), plan_path, *COMPANIONS]:
        result[rel(path)] = file_hash(path)
    for name, pin in a.CORE_PINS.items():
        b.require(file_hash(ROOT / 'piq-fc-arcade/src/main/resources' / name) == pin, 'Source WASM changed: ' + name)
    return result


def metadata(kind, old, compiled):
    expected = copy.deepcopy(tomllib.loads(old[b.META].decode('utf-8')))
    mods = {m['modId']: m for m in expected['mods']}
    b.require(set(mods) == ({OWNER[kind]} if kind == 'fc' else {'piq_sfc_arcade', 'piq_sfc_home'}), 'Wrong baseline mod ownership')
    mods[OWNER[kind]]['version'] = b.VERSIONS[OWNER[kind]]
    if kind == 'sfc':
        dependencies = expected['dependencies']['piq_sfc_home']
        matching = [d for d in dependencies if d['modId'] == 'piq_fc_arcade']
        b.require(len(matching) == 1, 'Expected one SFC home FC dependency')
        matching[0]['versionRange'] = '[0.31.0-alpha.45,0.32.0)'
    b.require(tomllib.loads(compiled[b.META].decode('utf-8')) == expected, 'Unapproved metadata semantics: ' + kind)
    manifest, count = re.subn(rb'(?m)^(Implementation-Version: )[^\r\n]+',
        lambda m: m[1] + b.VERSIONS[OWNER[kind]].encode(), old[b.MANIFEST])
    b.require(count == 1 and compiled[b.MANIFEST] == manifest, 'Unapproved manifest delta: ' + kind)


def overlay(kind, old, compiled, plan):
    metadata(kind, old, compiled)
    final, preserved, seen_resources = dict(compiled), [], set()
    for name in old.keys() | compiled.keys():
        if old.get(name) == compiled.get(name): continue
        if name in (b.META, b.MANIFEST): continue
        if name.endswith('.class'):
            b.require(allowed_class(kind, name, plan), 'Unapproved class delta: ' + name)
            if name not in compiled:
                b.require(name in plan['removed_classes'][kind], 'Unapproved class removal: ' + name)
        elif kind == 'fc' and name in a.DRAFTS:
            b.require(name in old and name in compiled and b.sha(compiled[name]) == a.DRAFTS[name], 'Unexpected controller draft identity')
            final[name] = old[name]
            preserved.append(name)
        elif name in plan['language_changes'][kind]:
            b.require(name in old and name in compiled, 'Language file added/removed')
            a.language(kind, name, old[name], compiled[name], plan)
        else:
            change = plan['resource_changes'][kind].get(name)
            b.require(change is not None and name in compiled, 'Unapproved resource delta: ' + name)
            actual = {'old_sha256': b.sha(old[name]) if name in old else None, 'new_sha256': b.sha(compiled[name])}
            b.require(actual == change, 'Resource SHA differs from reviewed plan: ' + name)
            seen_resources.add(name)
    b.require(seen_resources == set(plan['resource_changes'][kind]), 'Reviewed resource change missing from compiled output')
    for name in plan['language_changes'][kind]:
        b.require(name in old and name in compiled, 'Reviewed language file missing')
        a.language(kind, name, old[name], compiled[name], plan)
    b.require(set(old) - set(final) == set(plan['removed_classes'][kind]), 'Actual removals differ from review')
    if kind == 'fc':
        for name, wanted in a.CORE_PINS.items():
            b.require(final.get(name) == old.get(name) and b.sha(final.get(name, b'')) == wanted, 'FC core changed')
        for name in a.DRAFTS:
            b.require(final.get(name) == old.get(name), 'Source-only controller draft leaked')
    return final, sorted(preserved)


def safe_archive(path):
    pin, entries = b.load(path)
    b.require(len({name.casefold() for name in entries}) == len(entries), 'Case-colliding archive entries')
    return pin, entries


def reference_archive(path, pin):
    """Stream every CRC without retaining the 400+ MiB of pinned Native binaries."""
    b.require(file_hash(path) == pin, 'Reference JAR changed')
    entries, hashes, seen, total = {}, {}, set(), 0
    with zipfile.ZipFile(b.safe(path, True)) as archive:
        b.require(len(archive.infolist()) <= 2000, 'Reference entry budget')
        for info in archive.infolist():
            name = info.orig_filename
            member_name(name)
            b.require(name == info.filename and name.casefold() not in seen and not info.flag_bits & 1
                      and not stat.S_ISLNK(info.external_attr >> 16), 'Unsafe/duplicate reference entry')
            seen.add(name.casefold())
            if info.is_dir(): continue
            total += info.file_size
            b.require(total <= 600 * 1024 * 1024, 'Reference expanded budget')
            digest, count = hashlib.sha256(), 0
            keep = name.endswith('.class') or name in (b.META, b.MANIFEST)
            chunks = []
            with archive.open(info) as stream:
                while chunk := stream.read(1024 * 1024):
                    digest.update(chunk); count += len(chunk)
                    b.require(count <= info.file_size, 'Reference entry exceeds declared size')
                    if keep: chunks.append(chunk)
            b.require(count == info.file_size, 'Reference truncated')
            hashes[name] = digest.hexdigest().upper()
            # Empty non-class values retain all member names for identity/safety scanning.
            entries[name] = b''.join(chunks) if keep else b''
    b.require(file_hash(path) == pin, 'Reference changed during read')
    return entries, {'path': rel(path), 'sha256': pin, 'bytes': path.stat().st_size,
                     'entry_hashes': hashes, 'rebuilt': False, 'copied_to_output': False}


def reject_private_assets(entries):
    forbidden = ('.bbmodel', '.blend', '.psd', '.kra', '.pem', '.pfx', '.p12', '.key', '.keystore')
    for name in entries:
        low = name.lower()
        b.require(not low.endswith(forbidden) and not re.search(r'(^|/)(private[_-]?keys?|license[_-]?keys?|secrets?)/', low),
                  'Model source/private-key entry: ' + name)


def evidence(witness_path, witness_sha, plan_path, log_paths):
    b.require(isinstance(witness_sha, str) and re.fullmatch(r'[A-Fa-f0-9]{64}', witness_sha), 'Pinned capture SHA required')
    witness_sha = witness_sha.upper()
    b.require(file_hash(witness_path) == witness_sha, 'Capture identity changed')
    witness = unique_json(b.safe(witness_path, True).read_bytes())
    before = inputs(plan_path)
    plan = read_plan(plan_path)
    b.require(witness.get('schema') == 'gc018-45-inputs-1' and witness.get('versions') == b.VERSIONS
              and witness.get('plan_path') == rel(plan_path) and witness.get('inputs') == before,
              'Source/plan changed since capture; recapture only after edits finish, then fully rebuild')
    captured = witness['captured_ns']
    counts, xml, _ = b.tests(captured)
    xml = {p.replace('\\', '/'): h for p, h in xml.items()}
    for kind, (old_path, _) in BASE_WITNESSES.items():
        old = unique_json(old_path.read_bytes())
        prior = {p.replace('\\', '/') for p in old['test_xml'] if p.replace('\\', '/').startswith(PROJECT[kind] + '/')}
        b.require(prior and prior <= set(xml), 'Previously tested suites disappeared: ' + kind)
    for project, suites in plan['required_test_suites'].items():
        for suite in suites:
            path = project + '/build/test-results/test/TEST-' + suite + '.xml'
            b.require(path in xml, 'Required regression suite missing: ' + suite)
            doc = b.ET.fromstring((ROOT / path).read_bytes())
            b.require(int(doc.attrib['tests']) > 0 and int(doc.attrib['skipped']) == 0, 'Required suite empty/skipped')
    b.require(set(log_paths) == set(PROJECT), 'FC and SFC complete Gradle logs required')
    logs = {kind: {'path': rel(path), 'sha256': a.gradle_log(path, captured)} for kind, path in log_paths.items()}
    b.require(len({log['path'] for log in logs.values()}) == 2, 'Separate FC and SFC logs required')
    compiled, compiler, raw_pins = {}, {}, {}
    for kind in PROJECT:
        entries, info = b.compiled_part(PROJECT[kind], OWNER[kind], PREFIXES[kind], True)
        compiled[kind] = entries
        path = ROOT / info['path']
        b.require(path.stat().st_mtime_ns >= captured, 'Compiled JAR predates capture')
        raw_pins[rel(path)] = info['sha256']
        compiler[kind] = a.fresh_compiler(kind, entries, captured)
    old = {kind: safe_archive(path)[1] for kind, (path, _) in BASE.items()}
    core = safe_archive(CORE[0])[1]
    for name, raw in core.items():
        if name not in (b.META, b.MANIFEST):
            b.require(old['sfc'].get(name) == raw, 'Frozen core9 differs from SFC25 baseline: ' + name)
    home_manifest = compiled['sfc'][b.MANIFEST]
    compiled['sfc'] = b.merge_sfc([core, compiled['sfc']])
    compiled['sfc'][b.MANIFEST] = home_manifest
    proposed, preserved = {}, {}
    for kind in PROJECT:
        proposed[kind], preserved[kind] = overlay(kind, old[kind], compiled[kind], plan)
    for name, raw in core.items():
        if name not in (b.META, b.MANIFEST):
            b.require(proposed['sfc'].get(name) == raw, 'Core9 byte changed')
    refs, refs_evidence = {}, {}
    for kind, (path, pin) in REFERENCES.items():
        refs[kind], refs_evidence[kind] = reference_archive(path, pin)
    for entries in [*proposed.values(), *refs.values()]: reject_private_assets(entries)
    graph = b.identity_graph(proposed | refs)

    def fence():
        b.require(inputs(plan_path) == before and file_hash(witness_path) == witness_sha, 'Inputs changed during validation')
        for identities in (xml, raw_pins, {v['path']: v['sha256'] for v in logs.values()}):
            b.require(identities == {p: file_hash(ROOT / p) for p in identities}, 'Build/test/log evidence drift')
        for kind in PROJECT:
            b.require(a.fresh_compiler(kind, {n: raw for n, raw in proposed[kind].items()
                if not n.endswith('.class') or n.startswith(PREFIXES[kind])}, captured) == compiler[kind], 'Compiler class drift')
    fence()
    report = {'schema': 'gc018-45-build-1', 'ok': True, 'source_witness': witness,
        'source_witness_sha256': witness_sha, 'reviewed_plan': plan,
        'compiled': raw_pins, 'class_hashes': compiler, 'tests': counts, 'test_xml': xml,
        'gradle_logs': logs, 'identity': graph, 'references': refs_evidence,
        'preserved_source_drafts': preserved, 'three_fc_wasm_unchanged': True, 'sfc_core9_unchanged': True,
        'gradle_invoked_by_this_tool': False, 'installed': False, 'published': False,
        'native_core_executed': False, 'minecraft_or_real_network_tested_by_this_tool': False}
    return proposed, old, report, fence


def classify_source_changes(before, current):
    """An incomplete pre-edit inventory is not proof that old build inputs are new."""
    changes, uncovered = [], []
    for path in sorted(before.keys() | current.keys()):
        if not any(path.startswith(project + '/') for project in b.PROJECTS): continue
        if path in before:
            if before[path] != current.get(path): changes.append(path)
        elif any(path.startswith(project + '/' + source + '/')
                 for project in b.PROJECTS for source in ('src/main', 'src/test')):
            changes.append(path)
        else:
            uncovered.append(path)
    return changes, uncovered


def plan_template(path):
    b.require(file_hash(SOURCE_BEFORE) == SOURCE_BEFORE_SHA, 'Pre-edit snapshot changed')
    before = unique_json(SOURCE_BEFORE.read_bytes())['files']
    current = b.snapshot()
    changes, uncovered = classify_source_changes(before, current)
    plan = {'schema': 'gc018-45-reviewed-changes-1', 'approved': False,
            'class_roots': {}, 'removed_classes': {'fc': [], 'sfc': []}, 'language_changes': {},
            'resource_changes': {}, 'required_test_suites': {}, 'observed_source_changes': changes,
            'uncovered_build_inputs': uncovered,
            'notes': 'DRAFT ONLY. Review every class/resource/language delta after all edits finish. '
                     'Do not approve/capture until final editors have stopped. Core9, Native and GBA are frozen; '
                     'two source controller draft PNGs must not be distributed. Tests/builds still required.'}
    for kind, project in PROJECT.items():
        projects = [project] + (['piq-retro-platform'] if kind == 'fc' else [])
        roots, suites = [], []
        for name in changes:
            for p in projects:
                prefix = p + '/src/main/java/'
                if name.startswith(prefix) and name.endswith('.java'):
                    roots.append(name[len(prefix):-5])
                prefix = p + '/src/test/java/'
                if name.startswith(prefix) and name.endswith('Test.java') and name in current:
                    suites.append(name[len(prefix):-5].replace('/', '.'))
        plan['class_roots'][kind] = sorted(set(roots))
        plan['required_test_suites'][project] = sorted(set(suites))
        plan['language_changes'][kind], plan['resource_changes'][kind] = {}, {}
        old = safe_archive(BASE[kind][0])[1]
        resources = ROOT / project / 'src/main/resources'
        for source in sorted(resources.rglob('*')):
            if not source.is_file(): continue
            name, raw = source.relative_to(resources).as_posix(), source.read_bytes()
            if name in (b.META, b.MANIFEST) or old.get(name) == raw: continue
            if kind == 'fc' and name in a.DRAFTS:
                b.require(b.sha(raw) == a.DRAFTS[name], 'Unknown source controller draft'); continue
            if re.fullmatch('assets/' + OWNER[kind] + r'/lang/(zh_cn|en_us)\.json', name):
                initial, final = b.language(old[name], name), b.language(raw, name)
                delta = {k: {'old': initial.get(k), 'new': final.get(k)} for k in sorted(initial.keys() | final.keys())
                         if initial.get(k) != final.get(k)}
                if delta: plan['language_changes'][kind][name] = delta
            else:
                plan['resource_changes'][kind][name] = {'old_sha256': b.sha(old[name]) if name in old else None, 'new_sha256': b.sha(raw)}
    b.exclusive_json(path, plan)
    print(json.dumps({'ok': True, 'approved': False, 'draft': rel(path),
                      'classes': {k: len(v) for k, v in plan['class_roots'].items()},
                      'resources': {k: list(v) for k, v in plan['resource_changes'].items()}}, ensure_ascii=False))


def freeze(args):
    proposed, old, report, fence = evidence(args.witness, args.witness_sha256, args.plan,
        {'fc': args.fc_gradle_log, 'sfc': args.sfc_gradle_log})
    out = b.safe(args.output)
    b.require(out.is_relative_to(ROOT / 'piq-fc-arcade/build') and not out.exists(), 'Output must be a NEW build directory')
    fence(); out.mkdir()
    for kind, entries in proposed.items():
        with (out / NAMES[kind]).open('xb') as stream: stream.write(b.jar_bytes(entries))
        b.require(safe_archive(out / NAMES[kind])[1] == entries, 'Final CRC/entry readback mismatch')
    fence()
    report['mods'] = {kind: {'filename': NAMES[kind], 'bytes': (out / NAMES[kind]).stat().st_size,
        'sha256': file_hash(out / NAMES[kind]), 'baseline_sha256': BASE[kind][1],
        'changed_entries': sorted(n for n in old[kind].keys() | proposed[kind].keys() if old[kind].get(n) != proposed[kind].get(n))}
        for kind in PROJECT}
    b.exclusive_json(out / 'source-witness.json', report['source_witness'])
    b.exclusive_json(out / 'build-witness.json', report)
    with (out / 'SHA256SUMS.txt').open('x', encoding='utf-8') as stream:
        for kind in PROJECT: stream.write(report['mods'][kind]['sha256'] + '  ' + NAMES[kind] + '\n')
    print(json.dumps({'ok': True, 'output': str(out), 'mods': report['mods'], 'tests': report['tests'],
                      'build_witness_sha256': file_hash(out / 'build-witness.json'),
                      'references_only': {k: v['path'] for k, v in report['references'].items()}}, ensure_ascii=False))


def verify_output(out, expected_witness_sha):
    out = b.safe(out)
    b.require(out.is_relative_to(ROOT), 'Output outside workspace')
    report_path = out / 'build-witness.json'
    b.require(isinstance(expected_witness_sha, str) and re.fullmatch(r'[A-Fa-f0-9]{64}', expected_witness_sha)
              and file_hash(report_path) == expected_witness_sha.upper(), 'Expected final witness SHA does not match')
    recorded = unique_json(report_path.read_bytes())
    b.require(recorded.get('schema') == 'gc018-45-build-1' and recorded.get('ok') is True, 'Wrong final witness')
    capture_path = out / 'source-witness.json'
    proposed, old, expected, fence = evidence(capture_path, recorded['source_witness_sha256'],
        ROOT / recorded['source_witness']['plan_path'], {k: ROOT / v['path'] for k, v in recorded['gradle_logs'].items()})
    for key, value in expected.items(): b.require(recorded.get(key) == value, 'Final witness differs from current verified evidence: ' + key)
    mods = {}
    for kind in PROJECT:
        path = out / NAMES[kind]
        b.require(safe_archive(path)[1] == proposed[kind], 'Output differs from freshly audited approved archive: ' + kind)
        mods[kind] = {'filename': NAMES[kind], 'bytes': path.stat().st_size, 'sha256': file_hash(path),
            'baseline_sha256': BASE[kind][1], 'changed_entries': sorted(n for n in old[kind].keys() | proposed[kind].keys()
                if old[kind].get(n) != proposed[kind].get(n))}
    b.require(recorded.get('mods') == mods, 'Final JAR identities differ from witness')
    expected_names = {*NAMES.values(), 'source-witness.json', 'build-witness.json', 'SHA256SUMS.txt'}
    b.require({p.name for p in out.iterdir()} == expected_names, 'Unexpected files in frozen output')
    sums = ''.join(mods[k]['sha256'] + '  ' + NAMES[k] + '\n' for k in PROJECT)
    b.require((out / 'SHA256SUMS.txt').read_text(encoding='utf-8') == sums, 'SHA256SUMS mismatch')
    fence()
    b.require(file_hash(report_path) == expected_witness_sha.upper(), 'Final witness changed during audit')
    return {'ok': True, 'output': str(out), 'mods': mods, 'tests': expected['tests'], 'read_only': True}


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--plan-template', type=Path)
    group.add_argument('--capture', type=Path)
    group.add_argument('--freeze', action='store_true')
    parser.add_argument('--plan', type=Path, default=PLAN)
    parser.add_argument('--witness', type=Path)
    parser.add_argument('--witness-sha256')
    parser.add_argument('--fc-gradle-log', type=Path)
    parser.add_argument('--sfc-gradle-log', type=Path)
    parser.add_argument('--output', type=Path, default=OUT)
    args = parser.parse_args()
    if args.plan_template: plan_template(args.plan_template); return
    read_plan(args.plan)
    if args.capture:
        b.require(not any((args.witness, args.witness_sha256, args.fc_gradle_log, args.sfc_gradle_log)), 'Capture must precede test/build evidence')
        started, before = time.time_ns(), inputs(args.plan)
        b.require(before == inputs(args.plan), 'Inputs changed while capturing')
        b.exclusive_json(args.capture, {'schema': 'gc018-45-inputs-1', 'captured_ns': started,
            'versions': b.VERSIONS, 'plan_path': rel(args.plan), 'inputs': before,
            'required_build': 'After this capture, run FC then SFC: check jar --offline --rerun-tasks; retain separate full UTF-8 logs.'})
        print(json.dumps({'ok': True, 'capture': str(args.capture), 'sha256': file_hash(args.capture)}, ensure_ascii=False))
        return
    b.require(all((args.witness, args.witness_sha256, args.fc_gradle_log, args.sfc_gradle_log)), 'Capture and two complete build logs required')
    freeze(args)


if __name__ == '__main__':
    main()
