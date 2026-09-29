"""Capture then freeze FC44/Arcade0.1.0 from fresh, fully tested Gradle outputs.

This tool NEVER runs Gradle, installs, publishes, launches a game, or executes a native core.
Root must capture inputs, run both projects' real `check jar --offline --rerun-tasks`
with complete logs, then explicitly freeze. Only exact reviewed class roots, language
keys, versions and the native description may differ from the pinned FC43/Native17.
"""
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
UTIL_SHA = '7F9E8809360BBEFDDCE9D317146EC4943DDB1A71A09472A68F065370DA6E0F4A'
if hashlib.sha256(UTIL.read_bytes()).hexdigest().upper() != UTIL_SHA:
    raise ValueError('Frozen shared build helper identity changed')
spec = importlib.util.spec_from_file_location('arcade44_shared', UTIL)
b = importlib.util.module_from_spec(spec)
spec.loader.exec_module(b)
b.PROJECTS = ['piq-fc-arcade', 'piq-retro-platform', 'piq-native-arcade']
b.VERSIONS = {'piq_fc_arcade': '0.31.0-alpha.44', 'piq_native_arcade': '0.1.0'}
b.TEST_LIMITS = {'piq-fc-arcade': (1720, 8), 'piq-native-arcade': (95, 0)}
OUT = ROOT / 'piq-fc-arcade/build/review-arcade44-v1'
PLAN = ROOT / 'piq-fc-arcade/tools/arcade_release44_changes.json'
PREP = ROOT / 'piq-native-arcade/tools/prepare_embedded_runtime17.py'
PREP_SHA = '8049CEF7FCFD5F26B2D62E5A77CE55A46EBD8B1A306DE194BDCEC3AED52F6D6A'
RUNTIME = ROOT / 'piq-fc-arcade/build/runtime-pack37-v1/piq-runtime-pack-v1.zip'
RUNTIME_SHA = '681FDAB15CCF7BD3739B74598D1724E637415E6EB60C505DEEF0EFDAED0617AA'
BASE = {
    'fc': (ROOT / 'piq-fc-arcade/build/review-mapper43-v1/game_console-0.31.0-alpha.43.jar', '108DEDB98B2BE0A1D8682868D32DA5698357900B9BBB85FE2CCF4198C55AA372'),
    'native': (ROOT / 'piq-fc-arcade/build/review-bundled42-v2/game_console_arcade-0.1.0-alpha.17.jar', '875CE76B8B987E533A50EF34612116FD06455CFFF4A9BE787BCFE0720F650B05'),
}
BASE_WITNESSES = {
    'fc': (BASE['fc'][0].parent / 'build-witness.json', 'DA5962C8783A6496DCF55AD94BD1FCF187236180713681CC68477D5A47C612F2'),
    'native': (BASE['native'][0].parent / 'build-witness.json', '6561956EDB115B4B092B8ED5A426FD7061ED56C159E0D5CF4D229B8445F4FA9F'),
}
NAMES = {'fc': 'game_console-0.31.0-alpha.44.jar', 'native': 'game_console_arcade-0.1.0.jar'}
OWNER = {'fc': 'piq_fc_arcade', 'native': 'piq_native_arcade'}
PROJECT = {'fc': 'piq-fc-arcade', 'native': 'piq-native-arcade'}
PREFIXES = {'fc': ('cn/piq/fcarcade/', 'cn/piq/retro/'), 'native': ('cn/piq/nativearcade/',)}
CORE_PINS = {
    'core/nes_rust_wasm_bg.wasm': '110711E30B64444414A8BE2D0A3B1AB45A442CC9B9452AC7D74DAAB508C933FF',
    'core/nes_zapper_v1.wasm': 'C8D8824E5CAF727678C642E6B0539DEAA7C0084F33524D96779D90C7B5DA79EF',
    'core/nes_mapper19_v1.wasm': '900467682864994B1E80D8D288EF0864DA60927EE6213B19310576379B330985',
}
DRAFTS = {
    'assets/piq_fc_arcade/textures/block/famicom_controller_1.png': 'D181733715925047CA30A4198009D601E424052E9EAB9A02BBF63CD7A65F384D',
    'assets/piq_fc_arcade/textures/block/famicom_controller_2.png': '46826134D4E35D290657817B8AFFB6C04DE0EFC9BF98255B6ED44F2D3117064D',
}


def file_hash(path):
    h = hashlib.sha256()
    with b.safe(path, True).open('rb') as stream:
        while chunk := stream.read(1024 * 1024):
            h.update(chunk)
    return h.hexdigest().upper()


def unique_json(raw):
    def unique(pairs):
        result = {}
        for key, value in pairs:
            b.require(key not in result, 'Duplicate JSON key: ' + key)
            result[key] = value
        return result
    return json.loads(raw, object_pairs_hook=unique)


def read_plan(path=PLAN):
    plan = unique_json(b.safe(path, True).read_bytes())
    b.require(plan.get('schema') == 'arcade44-reviewed-changes-1' and plan.get('approved') is True, 'Change plan is not approved')
    for field in ('class_roots', 'removed_classes', 'language_changes'):
        b.require(set(plan.get(field, {})) == {'fc', 'native'}, 'Wrong reviewed plan kinds: ' + field)
    for kind in PROJECT:
        roots = plan['class_roots'][kind]
        b.require(isinstance(roots, list) and len(roots) == len(set(roots)), 'Duplicate/invalid class roots')
        for root in roots:
            b.require(isinstance(root, str) and re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_$/]*', root)
                      and root.startswith(PREFIXES[kind]) and '$' not in root and not root.endswith('/'), 'Invalid class root: ' + repr(root))
        removed = plan['removed_classes'][kind]
        b.require(isinstance(removed, list) and len(removed) == len(set(removed)), 'Invalid removed class list')
        for name in removed:
            b.require(isinstance(name, str) and name.endswith('.class') and allowed_class(kind, name, plan), 'Unapproved removed class root')
        changes = plan['language_changes'][kind]
        b.require(isinstance(changes, dict), 'Language changes must be a map')
        for path, keys in changes.items():
            b.require(re.fullmatch(r'assets/' + OWNER[kind] + r'/lang/(zh_cn|en_us)\.json', path) is not None and isinstance(keys, dict) and keys, 'Unreviewed language file')
            for key, change in keys.items():
                b.require(isinstance(key, str) and isinstance(change, dict) and set(change) == {'old', 'new'}, 'Language key needs explicit old/new')
                b.require(all(v is None or isinstance(v, str) for v in change.values()) and change['old'] != change['new'], 'Invalid language old/new')
    b.require(isinstance(plan.get('native_description'), str) and plan['native_description'].strip(), 'Exact native description required')
    suites = plan.get('required_test_suites', {})
    b.require(set(suites) == set(b.TEST_LIMITS), 'Both projects need a required-suite list')
    for project, names in suites.items():
        b.require(isinstance(names, list) and names and len(names) == len(set(names)), 'At least one exact required suite is needed: ' + project)
        b.require(all(isinstance(n, str) and re.fullmatch(r'[A-Za-z_$][A-Za-z0-9_.$]*', n) for n in names), 'Invalid test suite name')
    return plan


def inputs():
    result = b.snapshot()
    extra = [Path(__file__).resolve(), PLAN, PREP, RUNTIME, *[path for path, _ in BASE.values()],
             *[path for path, _ in BASE_WITNESSES.values()]]
    test_tool = ROOT / 'piq-fc-arcade/tools/test_build_arcade_release44.py'
    if test_tool.is_file(): extra.append(test_tool)
    build_runner = ROOT / 'piq-fc-arcade/tools/run_verified_build44.py'
    if build_runner.is_file(): extra.append(build_runner)
    for path in (ROOT / 'piq-native-arcade/docs/licenses').rglob('*'):
        if path.is_file(): extra.append(path)
    # Capture original core source provenance without rebuilding any WASM in this task.
    for path in (ROOT / 'piq-fc-arcade/native/nes-rust').rglob('*'):
        if path.is_file() and 'target' not in path.parts and path.suffix in ('.rs', '.toml', '.lock'):
            extra.append(path)
    for path in extra:
        result[path.relative_to(ROOT).as_posix()] = file_hash(path)
    b.require(file_hash(PREP) == PREP_SHA and file_hash(RUNTIME) == RUNTIME_SHA, 'Runtime provenance input changed')
    for path, pin in BASE.values():
        b.require(file_hash(path) == pin, 'Frozen baseline changed: ' + str(path))
    for path, pin in BASE_WITNESSES.values():
        b.require(file_hash(path) == pin, 'Frozen baseline witness changed: ' + str(path))
    for name, pin in CORE_PINS.items():
        b.require(file_hash(ROOT / 'piq-fc-arcade/src/main/resources' / name) == pin, 'Existing WASM source resource changed: ' + name)
    return result


def prepare_profile():
    b.require(file_hash(PREP) == PREP_SHA, 'Unreviewed runtime generator')
    spec = importlib.util.spec_from_file_location('arcade44_fixed_runtime_profile', PREP)
    prep = importlib.util.module_from_spec(spec); sys.modules[spec.name] = prep; spec.loader.exec_module(prep)
    b.require(prep.PROFILE.archive_sha256 == RUNTIME_SHA and prep.PROFILE.archive_size == 127026327, 'Wrong runtime profile')
    return prep


def native_archive(path, expected):
    """CRC/SHA-stream the 12 fixed runtime/provenance entries; bound all other content."""
    b.require(b.safe(path, True).stat().st_size <= 140 * 1024 * 1024, 'Native compressed budget')
    entries, resources, seen, folded, small = {}, {}, set(), set(), 0
    with zipfile.ZipFile(path) as archive:
        b.require(len(archive.infolist()) <= 500, 'Native entry budget')
        for info in archive.infolist():
            name = info.orig_filename
            b.require(name == info.filename and name and not name.startswith('/') and '\\' not in name and ':' not in name
                      and not any(ord(c) < 32 or ord(c) == 127 for c in name)
                      and all(p not in ('', '.', '..') for p in name.rstrip('/').split('/')),
                      'Unsafe Native member: ' + repr(name))
            b.require(name not in seen and name.casefold() not in folded and not info.flag_bits & 1
                      and not stat.S_ISLNK(info.external_attr >> 16), 'Duplicate/encrypted/link Native member')
            b.require(not re.fullmatch(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)', name, re.I), 'Signed JAR cannot be frozen this way')
            seen.add(name); folded.add(name.casefold())
            if info.is_dir(): continue
            if name in expected:
                wanted_size, wanted_hash = expected[name]
                b.require(info.file_size == wanted_size, 'Embedded member length mismatch: ' + name)
                h, size = hashlib.sha256(), 0
                with archive.open(info) as stream:
                    while chunk := stream.read(1024 * 1024):
                        size += len(chunk); b.require(size <= wanted_size, 'Expanded runtime exceeds fixed budget'); h.update(chunk)
                identity = (size, h.hexdigest().upper())
                b.require(identity == expected[name], 'Embedded member identity mismatch: ' + name)
                resources[name] = {'bytes': size, 'sha256': wanted_hash}
            else:
                small += info.file_size; b.require(small <= 16 * 1024 * 1024, 'Unexpected Native resource expansion')
                entries[name] = archive.read(info)
    b.require(set(resources) == set(expected) and b.META in entries and b.MANIFEST in entries, 'Missing Native resources/metadata')
    return entries, resources


def allowed_class(kind, name, plan):
    return name.endswith('.class') and any(name == root + '.class' or name.startswith(root + '$')
        for root in plan['class_roots'][kind])


def metadata(kind, old, compiled, plan):
    expected = copy.deepcopy(tomllib.loads(old[b.META].decode('utf-8')))
    b.require(len(expected['mods']) == 1 and expected['mods'][0]['modId'] == OWNER[kind], 'Unexpected baseline owner')
    expected['mods'][0]['version'] = b.VERSIONS[OWNER[kind]]
    if kind == 'native':
        expected['mods'][0]['description'] = plan['native_description']
        found = 0
        for dep in expected['dependencies']['piq_native_arcade']:
            if dep['modId'] == 'piq_fc_arcade':
                dep['versionRange'] = '[0.31.0-alpha.44,0.32.0)'; found += 1
        b.require(found == 1, 'Expected one FC dependency')
    b.require(tomllib.loads(compiled[b.META].decode('utf-8')) == expected, 'Unapproved metadata change: ' + kind)
    manifest = re.sub(rb'(?m)^(Implementation-Version: )[^\r\n]+',
        lambda m: m[1] + b.VERSIONS[OWNER[kind]].encode(), old[b.MANIFEST])
    b.require(compiled[b.MANIFEST] == manifest, 'Unapproved manifest change: ' + kind)


def language(kind, path, old, compiled, plan):
    before, after = b.language(old, path), b.language(compiled, path)
    expected = dict(before)
    for key, change in plan['language_changes'][kind][path].items():
        b.require(expected.get(key) == change['old'], 'Language baseline mismatch: ' + key)
        if change['new'] is None:
            b.require(key in expected, 'Cannot remove missing language key'); del expected[key]
        else:
            expected[key] = change['new']
        if change['old'] is not None and change['new'] is not None:
            pattern = r'%(?:[0-9]+\$)?[sd%]'
            b.require(re.findall(pattern, change['old']) == re.findall(pattern, change['new']), 'Language format argument drift: ' + key)
    b.require(after == expected, 'Unapproved language changes: ' + path)


def overlay(kind, old, compiled, plan):
    metadata(kind, old, compiled, plan)
    final, preserved = dict(compiled), []
    for name in old.keys() | compiled.keys():
        if old.get(name) == compiled.get(name): continue
        if name in (b.META, b.MANIFEST): continue
        if name.endswith('.class'):
            b.require(allowed_class(kind, name, plan), 'Unapproved class delta: ' + name)
            if name not in compiled:
                b.require(name in plan['removed_classes'][kind], 'Unapproved removed class: ' + name)
            continue
        b.require(name in old and name in compiled, 'Non-class resource added/removed: ' + name)
        if name in plan['language_changes'][kind]:
            language(kind, name, old[name], compiled[name], plan); continue
        b.require(kind == 'fc' and name in DRAFTS and b.sha(compiled[name]) == DRAFTS[name], 'Unapproved resource delta: ' + name)
        final[name] = old[name]; preserved.append(name)
    for name in plan['language_changes'][kind]:
        b.require(name in old and name in compiled, 'Reviewed language file missing')
        language(kind, name, old[name], compiled[name], plan)
    actual_removed = set(old) - set(final)
    b.require(actual_removed == set(plan['removed_classes'][kind]), 'Reviewed class removals do not match output')
    if kind == 'fc':
        for name, pin in CORE_PINS.items():
            b.require(b.sha(final.get(name, b'')) == pin and final[name] == old[name], 'Frozen FC module changed: ' + name)
    return final, sorted(preserved)


def fresh_compiler(kind, entries, captured_ns):
    project = PROJECT[kind]
    classes = ROOT / project / 'build/classes/java/main'
    actual = {p.relative_to(classes).as_posix(): file_hash(p) for p in classes.rglob('*.class')}
    expected = {name: b.sha(raw) for name, raw in entries.items() if name.endswith('.class') and name.startswith(PREFIXES[kind])}
    b.require(actual and actual == expected, 'Actual compiler output differs from JAR: ' + kind)
    roots = [ROOT / project / 'src/main/java'] + ([ROOT / 'piq-retro-platform/src/main/java'] if kind == 'fc' else [])
    for name in actual:
        source_name = name[:-6].split('$')[0] + '.java'
        owners = [root / source_name for root in roots if (root / source_name).is_file()]
        b.require(len(owners) == 1, 'Class lacks one exact source owner: ' + name)
        b.require(b.safe(classes / name, True).stat().st_mtime_ns >= max(captured_ns, owners[0].stat().st_mtime_ns),
                  'Class not fully rebuilt after source capture: ' + name)
    return actual


def gradle_log(path, captured_ns):
    path = b.safe(path, True)
    b.require(path.is_relative_to(ROOT) and path.stat().st_mtime_ns >= captured_ns, 'Gradle log predates capture or lies outside workspace')
    raw = path.read_bytes(); b.require(len(raw) <= 32 * 1024 * 1024, 'Gradle log size limit')
    text = raw.decode('utf-8', errors='replace')
    b.require('BUILD SUCCESSFUL' in text and 'BUILD FAILED' not in text, 'Gradle build was not successful')
    for task in ('compileJava', 'test', 'jar', 'check'):
        b.require(re.search(r'(?m)^> Task :' + task + r'\s*$', text) is not None, 'Missing actual non-UP-TO-DATE Gradle task: ' + task)
    return file_hash(path)


def freeze(args, before, plan):
    b.require(args.witness and re.fullmatch(r'[0-9a-fA-F]{64}', args.witness_sha256 or ''), 'Pinned source capture required')
    witness_hash = args.witness_sha256.upper()
    b.require(file_hash(args.witness) == witness_hash, 'Source capture identity changed')
    witness = unique_json(b.safe(args.witness, True).read_bytes())
    b.require(witness.get('schema') == 'arcade44-inputs-1' and witness.get('versions') == b.VERSIONS and witness.get('inputs') == before,
              'Source input drift since capture; recapture and fully rebuild')
    captured = witness['captured_ns']
    counts, xml, _ = b.tests(captured)
    xml = {path.replace('\\', '/'): identity for path, identity in xml.items()}
    for kind, (old_witness, _) in BASE_WITNESSES.items():
        previous = unique_json(old_witness.read_bytes())
        previous_suites = {path.replace('\\', '/') for path in previous['test_xml']
                           if path.replace('\\', '/').startswith(PROJECT[kind] + '/')}
        b.require(previous_suites and previous_suites <= set(xml), 'Previously tested suites disappeared: ' + kind)
    for project, suites in plan['required_test_suites'].items():
        for suite in suites:
            relative = project + '/build/test-results/test/TEST-' + suite + '.xml'
            b.require(relative in xml, 'Missing required new regression suite: ' + suite)
            doc = b.ET.fromstring((ROOT / relative).read_bytes())
            b.require(int(doc.attrib['tests']) > 0 and int(doc.attrib['skipped']) == 0, 'Required new suite is empty or skipped: ' + suite)
    b.require(args.fc_gradle_log and args.native_gradle_log, 'Both complete Gradle logs required')
    logs = {str(b.safe(p, True).relative_to(ROOT)): gradle_log(p, captured) for p in (args.fc_gradle_log, args.native_gradle_log)}
    b.require(len(logs) == 2, 'FC and Native require separate build logs')
    prep = prepare_profile(); expected = prep.expected_output(prep.PROFILE)
    prep.verify_output(prep.DEFAULT_OUTPUT, prep.PROFILE)
    b.require(len(expected) == 12, 'Fixed embedded provenance must contain exactly 12 entries')
    old_fc = b.load(BASE['fc'][0])[1]
    old_native, old_runtime = native_archive(BASE['native'][0], expected)
    fc, fc_evidence = b.compiled_part('piq-fc-arcade', 'piq_fc_arcade', PREFIXES['fc'], True)
    native_path = ROOT / 'piq-native-arcade/build/libs/piq_native_arcade-0.1.0.jar'
    native, runtime = native_archive(native_path, expected)
    compiled_pins = {fc_evidence['path']: fc_evidence['sha256'], native_path.relative_to(ROOT).as_posix(): file_hash(native_path)}
    for relative in compiled_pins:
        b.require((ROOT / relative).stat().st_mtime_ns >= captured, 'Compiled JAR predates capture')
    classes = {kind: fresh_compiler(kind, entries, captured) for kind, entries in (('fc', fc), ('native', native))}
    proposed, preserved = {}, {}
    for kind, old, compiled in (('fc', old_fc, fc), ('native', old_native, native)):
        proposed[kind], preserved[kind] = overlay(kind, old, compiled, plan)
    b.require(proposed['native'] == native and runtime == old_runtime, 'Native resources must be byte-identical to frozen baseline except approved compiled deltas')
    graph = b.identity_graph(proposed)
    graph['embedded_runtime_resources'] = runtime
    for name in runtime:
        if name.endswith(('.dll', '.jar')):
            b.require(name not in graph['runtime_owners'], 'Duplicate runtime ownership')
            graph['runtime_owners'][name] = 'native'
    def fence():
        b.require(inputs() == before and file_hash(args.witness) == witness_hash, 'Source/plan/baseline changed during freeze')
        for identities in (xml, logs, compiled_pins):
            b.require(identities == {path: file_hash(ROOT / path) for path in identities}, 'Build or test evidence changed during freeze')
        prep.verify_output(prep.DEFAULT_OUTPUT, prep.PROFILE)
    fence(); b.require(not b.safe(OUT).exists(), 'Refusing to overwrite frozen output'); OUT.mkdir()
    fc_path, final_native_path = OUT / NAMES['fc'], OUT / NAMES['native']
    with fc_path.open('xb') as target: target.write(b.jar_bytes(proposed['fc']))
    with native_path.open('rb') as source, final_native_path.open('xb') as target: shutil.copyfileobj(source, target, 1024 * 1024)
    b.require(b.load(fc_path)[1] == proposed['fc'], 'Final FC CRC/entry readback mismatch')
    actual_native, actual_runtime = native_archive(final_native_path, expected)
    b.require(actual_native == proposed['native'] and actual_runtime == runtime and file_hash(final_native_path) == file_hash(native_path), 'Final Native CRC/entry/copy readback mismatch')
    fence()
    old_by_kind = {'fc': old_fc, 'native': old_native}
    mods = {kind: {'filename': NAMES[kind], 'bytes': (OUT / NAMES[kind]).stat().st_size,
        'sha256': file_hash(OUT / NAMES[kind]), 'baseline_sha256': BASE[kind][1],
        'changed_entries': sorted(name for name in old_by_kind[kind].keys() | proposed[kind].keys() if old_by_kind[kind].get(name) != proposed[kind].get(name))}
        for kind in PROJECT}
    report = {'schema': 'arcade44-build-1', 'ok': True, 'source_witness': witness, 'source_witness_sha256': witness_hash,
        'reviewed_plan': plan, 'mods': mods, 'compiled': compiled_pins, 'class_hashes': classes,
        'tests': counts, 'test_xml': xml, 'gradle_logs': logs, 'identity': graph,
        'preserved_source_drafts': preserved, 'three_fc_wasm_unchanged': True,
        'native_embedded_all_unchanged': True, 'runtime_original_sha256': RUNTIME_SHA,
        'gradle_invoked_by_this_tool': False, 'installed': False, 'published': False,
        'native_core_executed': False, 'minecraft_or_real_network_tested_by_this_tool': False}
    b.exclusive_json(OUT / 'source-witness.json', witness)
    b.exclusive_json(OUT / 'build-witness.json', report)
    with (OUT / 'SHA256SUMS.txt').open('x', encoding='utf-8') as stream:
        for kind in PROJECT: stream.write(mods[kind]['sha256'] + '  ' + NAMES[kind] + '\n')
    print(json.dumps({'ok': True, 'output': str(OUT), 'mods': mods, 'tests': counts}, ensure_ascii=False))


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument('--capture', type=Path)
    group.add_argument('--freeze', action='store_true')
    parser.add_argument('--witness', type=Path)
    parser.add_argument('--witness-sha256')
    parser.add_argument('--fc-gradle-log', type=Path)
    parser.add_argument('--native-gradle-log', type=Path)
    args = parser.parse_args(); plan = read_plan(); started = time.time_ns(); before = inputs()
    if args.capture:
        b.require(not any((args.witness, args.witness_sha256, args.fc_gradle_log, args.native_gradle_log)), 'Capture must precede all build/test evidence')
        b.require(before == inputs(), 'Inputs changed while capturing')
        b.exclusive_json(args.capture, {'schema': 'arcade44-inputs-1', 'captured_ns': started, 'versions': b.VERSIONS, 'inputs': before,
            'required_build': 'Run each project: check jar --offline --rerun-tasks; retain complete logs. This capture did not run a build.'})
        print(json.dumps({'ok': True, 'capture': str(args.capture), 'sha256': file_hash(args.capture)}, ensure_ascii=False)); return
    freeze(args, before, plan)


if __name__ == '__main__':
    main()
