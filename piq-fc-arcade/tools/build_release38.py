"""FC38 resource-only freeze. Default is read-only planning; never compile/install implicitly.

Example (after a human-reviewed exact operation list has been frozen):
  python tools/build_release38.py --plan ../outputs/release38/operations-v1.json \
      --plan-sha256 <64 hex> --report ../outputs/release38/preflight-v1.json
  python tools/build_release38.py --plan ... --plan-sha256 ... --check-regressions \
      --freeze --output build/review-release38-v1

Plan schema: piq-release38-plan-1, operations=[], metadata_changes=[]. An operation
has kind, entry, action (replace/add/remove), category (texture/unused-resource/
license), source (workspace-relative except removals), before_sha256, after_sha256,
and reason. No glob or directory permissions exist. metadata_changes only permits
the exact reviewed top-level license field; versions are fixed below. Sources and
the operation list are fenced before and after; FC37 Java is never recompiled into
the deliverable. Its .class files and all non-approved resources remain unchanged.
"""
from __future__ import annotations

import argparse
import copy
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET
import zipfile

from freeze_fc_core_alpha19 import digest, require, read_jar, safe_path, META, MANIFEST
from prepare_cabinet_multiplayer21 import clean

ROOT = Path(__file__).resolve().parents[2]
BASE_DIR = ROOT / 'piq-fc-arcade/build/review-runtime37-v1'
BASE_WITNESS_SHA256 = 'C74F2B6C9456631A29DE5F378AD99C93FBC66FC39855052AC4785A98E32185CD'
JAVA = Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot')
BASE = {
    'fc': ('piq_fc_arcade-0.31.0-alpha.37.jar', '809A4FEE17B8805C642C4DF1A0272B5C369D02B179A98E41253262E268ACD406'),
    'native': ('piq_native_arcade-0.1.0-alpha.13.jar', '49177F23C429534580E9764BB9DD5C915342FA29063AE542137EB67DF70D0797'),
    'sfc': ('piq_sfc-0.1.0-alpha.21.jar', '62A53BEF68D9D181DFEC74E2926F6D431F61D6CD8B989C62DF36593771C94774'),
    'gba': ('piq_gba-0.1.0-alpha.5.jar', 'DCB0F05BF4CD3880430DB9985B6E37DA1B116509A4B7A0E84F59CCCAF679BDB1'),
}
NAMES = {'fc': 'piq_fc_arcade-0.31.0-alpha.38.jar', 'native': BASE['native'][0],
         'sfc': 'piq_sfc-0.1.0-alpha.22.jar', 'gba': 'piq_gba-0.1.0-alpha.6.jar'}
MODS = {'fc': 'piq_fc_arcade', 'native': 'piq_native_arcade', 'sfc': 'piq_sfc_home', 'gba': 'piq_gba'}
VERSIONS = {'piq_fc_arcade': '0.31.0-alpha.38', 'piq_native_arcade': '0.1.0-alpha.13',
            'piq_sfc_home': '0.1.0-alpha.22', 'piq_sfc_arcade': '0.2.0-alpha.7', 'piq_gba': '0.1.0-alpha.6'}
PROJECTS = ['piq-fc-arcade', 'piq-retro-platform', 'piq-native-arcade', 'piq-sfc-arcade', 'piq-sfc-home', 'piq-gba']
RESOURCE_PROJECTS = {'fc': ['piq-fc-arcade', 'piq-retro-platform'], 'native': ['piq-native-arcade'],
                     'sfc': ['piq-sfc-arcade', 'piq-sfc-home'], 'gba': ['piq-gba']}
SCHEMA = 'piq-release38-plan-1'
BUILD_SCHEMA = 'piq-release38-build-1'
HEX = re.compile(r'[0-9A-Fa-f]{64}')


def file_sha(path):
    h = hashlib.sha256()
    with safe_path(path, True).open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest().upper()


def entry_path(value):
    require(isinstance(value, str) and value and value == value.strip(), 'Invalid entry name')
    require('\\' not in value and ':' not in value and not value.startswith('/')
            and not any(c in value for c in '*?[]') and all(ord(c) >= 32 for c in value)
            and all(p not in ('', '.', '..') for p in value.split('/')), 'Unsafe/non-exact entry ' + value)
    return value


def workspace_file(value):
    entry_path(value)
    path = safe_path(ROOT / value, True)
    require(path.is_relative_to(ROOT), 'Source must stay in the workspace')
    require(not any(p.lower() in {'quarantine', 'incoming', '.git', '.gradle', 'node_modules'}
                    for p in path.relative_to(ROOT).parts), 'Unreviewed source location: ' + value)
    return path


def pin(value, optional=False):
    if optional and value is None:
        return None
    require(isinstance(value, str) and HEX.fullmatch(value), 'Expected exact SHA256')
    return value.upper()


def load_plan(path, expected_sha):
    path = safe_path(path, True)
    require(path.is_relative_to(ROOT), 'Plan outside workspace')
    raw = path.read_bytes()
    require(digest(raw) == pin(expected_sha), 'Approved plan SHA mismatch')
    plan = json.loads(raw)
    require(set(plan) == {'schema', 'operations', 'metadata_changes'} and plan['schema'] == SCHEMA,
            'Unexpected plan schema or fields')
    require(isinstance(plan['operations'], list) and isinstance(plan['metadata_changes'], list), 'Plan lists required')
    require(len(plan['operations']) <= 128 and len(plan['metadata_changes']) <= 4, 'Plan exceeds reviewed scope')
    seen = set()
    for op in plan['operations']:
        require(set(op) == {'kind', 'entry', 'action', 'category', 'source', 'before_sha256', 'after_sha256', 'reason'},
                'Exact operation fields required')
        kind, name, action = op['kind'], entry_path(op['entry']), op['action']
        require(kind in BASE and action in {'replace', 'add', 'remove'}, 'Unknown mod/action')
        require((kind, name) not in seen, 'Duplicate operation: ' + name)
        seen.add((kind, name))
        require(isinstance(op['reason'], str) and op['reason'].strip(), 'Review reason required')
        require(name not in {META, MANIFEST} and not name.endswith('.class'), 'Code/metadata is not a resource operation')
        require(not name.startswith(('data/', 'core/', 'native/')) and '/lang/' not in name
                and not name.lower().endswith(('.dll', '.wasm', '.jar', '.exe', '.properties', '.mixins.json')),
                'Protected runtime/data/language entry: ' + name)
        category = op['category']
        if category == 'texture':
            require(action in {'replace', 'add'} and name.startswith('assets/') and name.endswith('.png'),
                    'Texture operation must name an exact asset PNG')
        elif category == 'unused-resource':
            require(action == 'remove' and name.startswith('assets/')
                    and name.endswith(('.png', '.json', '.obj', '.mtl', '.bbmodel'))
                    and '/blockstates/' not in name, 'Only explicit unused model/texture removals are allowed')
        elif category == 'license':
            require(action in {'replace', 'add'} and re.fullmatch(
                r'(?:META-INF/)?(?:LICENSE[^/]*|NOTICE[^/]*|THIRD_PARTY[^/]*|ASSET[^/]*|licenses/[\w./-]+)', name),
                'License must be an exact notice path, never executable metadata')
            require(not name.lower().endswith(('.png', '.toml', '.mf', '.class', '.bin'))
                    and (not name.endswith('.json') or name.startswith('META-INF/licenses/')),
                    'Only structured provenance in META-INF/licenses may be JSON')
        else:
            raise ValueError('Unknown resource category ' + str(category))
        op['before_sha256'] = pin(op['before_sha256'], optional=action == 'add')
        op['after_sha256'] = pin(op['after_sha256'], optional=action == 'remove')
        require((op['before_sha256'] is None) == (action == 'add')
                and (op['after_sha256'] is None) == (action == 'remove'), 'Action/hash inconsistency')
        if action == 'remove':
            require(op['source'] is None, 'Removed resource cannot have a payload')
        else:
            source = workspace_file(op['source'])
            require(file_sha(source) == op['after_sha256'], 'Replacement payload changed: ' + str(source))
    seen.clear()
    for change in plan['metadata_changes']:
        require(set(change) == {'kind', 'field', 'before', 'after', 'reason'}, 'Exact metadata fields required')
        require(change['kind'] in BASE and change['field'] == 'license', 'Only the reviewed license field may change')
        require(change['kind'] not in seen, 'Duplicate metadata approval')
        seen.add(change['kind'])
        require(all(isinstance(change[k], str) and change[k].strip() for k in ('before', 'after', 'reason')),
                'Metadata change requires explicit values/reason')
        require(change['before'] != change['after'], 'No-op metadata approval')
    # Native13 must retain its old whole-archive identity. If it needs a real
    # notice/resource change, obtain a new version and revise this tool explicitly.
    require(not any(x['kind'] == 'native' for x in plan['operations'] + plan['metadata_changes']),
            'Native13 is immutable; a changed Native addon needs a separately approved version')
    return plan, raw


def png_size(raw):
    require(len(raw) >= 33 and raw[:8] == b'\x89PNG\r\n\x1a\n' and raw[12:16] == b'IHDR', 'Not a PNG')
    w, h = struct.unpack('>II', raw[16:24])
    require(0 < w <= 8192 and 0 < h <= 8192, 'Unreasonable PNG dimensions')
    return [w, h]


def metadata(kind, old, changes):
    expected = copy.deepcopy(tomllib.loads(old[META].decode('utf-8')))
    text = old[META].decode('utf-8')
    owner = MODS[kind]
    old_version = next(m['version'] for m in expected['mods'] if m['modId'] == owner)
    version = VERSIONS[owner]
    for mod in expected['mods']:
        mod['version'] = VERSIONS[mod['modId']]
    # Match only the version line in the correct mods table, not dependency ranges.
    matches = list(re.finditer(r'(?m)^\[\[mods\]\]', text))
    found = 0
    for m in reversed(matches):
        end = next((n.start() for n in matches if n.start() > m.start()), len(text))
        part = text[m.start():end]
        if re.search(r'(?m)^modId\s*=\s*"' + re.escape(owner) + r'"\s*$', part):
            part, count = re.subn(r'(?m)^(version\s*=\s*)"' + re.escape(old_version) + r'"(\s*)$',
                                 lambda x: x[1] + json.dumps(version) + x[2], part)
            require(count == 1, 'Exact mod version line missing ' + kind)
            text = text[:m.start()] + part + text[end:]
            found += 1
    require(found == 1, 'Owner metadata table missing ' + kind)
    for change in changes:
        if change['kind'] != kind:
            continue
        require(expected['license'] == change['before'], 'License baseline mismatch')
        expected['license'] = change['after']
        prefix, body = text.split('[[mods]]', 1)
        prefix, count = re.subn(r'(?m)^license[ \t]*=[ \t]*"[^"\r\n]*"[ \t]*$',
                                lambda _: 'license=' + json.dumps(change['after'], ensure_ascii=False), prefix)
        require(count == 1, 'License assignment must be unambiguous')
        text = prefix + '[[mods]]' + body
    actual = tomllib.loads(text)
    require(actual == expected, 'Unexpected metadata semantics ' + kind)
    require(actual.get('dependencies') == tomllib.loads(old[META].decode()).get('dependencies'),
            'Dependency ranges/side/order/security are frozen')
    manifest = old[MANIFEST]
    pattern = rb'(?m)^(Implementation-Version: )' + re.escape(old_version.encode()) + rb'(\r?\n)'
    manifest, count = re.subn(pattern, lambda m: m[1] + version.encode() + m[2], manifest)
    require(count == 1, 'Exact manifest version missing ' + kind)
    return text.encode('utf-8'), manifest


def source_snapshot(plan):
    """Fence all previous main/helper Java and raw resource inputs against FC37."""
    witness_path = safe_path(BASE_DIR / 'build-witness.json', True)
    require(file_sha(witness_path) == BASE_WITNESS_SHA256, 'Frozen FC37 source witness identity changed')
    witness = json.loads(witness_path.read_bytes())
    require(witness.get('schema') == 'piq-runtime37-build-1' and witness.get('ok'), 'Wrong FC37 source witness')
    for kind, (_, expected) in BASE.items():
        require(witness['mods'][kind]['sha256'] == expected, 'Witness baseline identity ' + kind)
    baseline = witness['inputs']
    java = {p: h for p, h in baseline.items() if p.endswith('.java') and
            ('/src/main/java/' in p or '/helper/src/' in p)}
    actual_java = {}
    for project in PROJECTS:
        for sub in ['src/main/java', 'helper/src']:
            for path in sorted((ROOT / project / sub).rglob('*.java')):
                actual_java[path.relative_to(ROOT).as_posix()] = file_sha(path)
    require(java == actual_java, 'Production Java drift since FC37: ' + repr(sorted(
        n for n in set(java) | set(actual_java) if java.get(n) != actual_java.get(n))))
    allowed = set()
    for op in plan['operations']:
        for project in RESOURCE_PROJECTS[op['kind']]:
            allowed.add(project + '/src/main/resources/' + op['entry'])
        if op['source'] is not None:
            allowed.add(op['source'])
    # Text templates can carry literal addon versions / reviewed license values;
    # final metadata semantics are independently constrained by metadata().
    allowed.update(project + '/src/main/resources/' + META for project in PROJECTS)
    old_res = {p: h for p, h in baseline.items() if '/src/main/resources/' in p}
    actual_res = {}
    for project in PROJECTS:
        for path in sorted((ROOT / project / 'src/main/resources').rglob('*')):
            if path.is_file():
                actual_res[path.relative_to(ROOT).as_posix()] = file_sha(path)
    drift = sorted(n for n in set(old_res) | set(actual_res)
                   if old_res.get(n) != actual_res.get(n) and n not in allowed)
    require(not drift, 'Raw resource source outside the approved operations: ' + repr(drift))
    # Hash build inputs too: changes during a check/freeze are never accepted.
    files = dict(actual_java, **actual_res)
    for project in PROJECTS:
        for path in sorted((ROOT / project / 'src').rglob('*')):
            if path.is_file():
                files[path.relative_to(ROOT).as_posix()] = file_sha(path)
        for path in sorted((ROOT / project / 'native').rglob('*')):
            if path.is_file() and path.suffix.lower() in {'.c', '.h', '.cc', '.cpp', '.hpp', '.py', '.patch', '.cmake', '.txt'}:
                files[path.relative_to(ROOT).as_posix()] = file_sha(path)
        for name in ['build.gradle', 'settings.gradle', 'gradle.properties', 'LICENSE']:
            path = ROOT / project / name
            if path.is_file():
                files[path.relative_to(ROOT).as_posix()] = file_sha(path)
    for op in plan['operations']:
        if op['source'] is not None:
            files[op['source']] = file_sha(workspace_file(op['source']))
    for name in ['tools/build_release38.py', 'tools/check_release38.py']:
        files['piq-fc-arcade/' + name] = file_sha(ROOT / 'piq-fc-arcade' / name)
    return dict(files=files, baseline_witness_sha256=file_sha(witness_path),
                unchanged_production_java=len(actual_java), approved_raw_resource_changes=sorted(
                    n for n in set(old_res) | set(actual_res) if old_res.get(n) != actual_res.get(n)))


def removed_reference_hits(kind, name, archives):
    """Conservative direct resource-reference guard, not a dynamic Java proof."""
    segments = name.split('/')
    namespace = segments[1]
    relative = '/'.join(segments[2:])
    candidates = {name, namespace + ':' + relative, relative}
    if relative.startswith(('textures/', 'models/')):
        resource = relative.split('/', 1)[1]
        candidates.add(namespace + ':' + resource.rsplit('.', 1)[0])
    needles = {value.encode('utf-8') for value in candidates}
    hits = []
    for owner, entries in archives.items():
        for entry, raw in entries.items():
            if entry == name and owner == kind:
                continue
            if entry.endswith(('.class', '.json', '.obj', '.mtl', '.properties', '.toml')) and any(n in raw for n in needles):
                hits.append(owner + ':' + entry)
    return sorted(hits)


def make_plan(plan_path, plan_sha):
    plan, raw = load_plan(plan_path, plan_sha)
    source = source_snapshot(plan)
    archives, baselines, report = {}, {}, {}
    for kind, (name, expected_sha) in BASE.items():
        path = BASE_DIR / name
        actual_sha, _, old = read_jar(path)
        require(actual_sha == expected_sha, 'FC37 baseline changed: ' + kind)
        old = clean(old)
        new = dict(old)
        new[META], new[MANIFEST] = metadata(kind, old, plan['metadata_changes'])
        changes = []
        for op in plan['operations']:
            if op['kind'] != kind:
                continue
            name, action = op['entry'], op['action']
            require((name in old) == (action != 'add'), 'Resource existence differs from approval ' + name)
            if name in old:
                require(digest(old[name]) == op['before_sha256'], 'Old resource identity mismatch ' + name)
            item = dict(op)
            if action == 'remove':
                del new[name]
            else:
                value = workspace_file(op['source']).read_bytes()
                require(digest(value) == op['after_sha256'], 'Payload drift ' + name)
                require(name not in old or old[name] != value, 'No-op resource replacement ' + name)
                if op['category'] == 'texture':
                    item['dimensions'] = png_size(value)
                    if name in old:
                        require(png_size(old[name]) == item['dimensions'], 'Texture dimensions alter existing UV ' + name)
                if op['category'] == 'license':
                    value.decode('utf-8')
                    require(len(value) <= 1024 * 1024 and b'\0' not in value, 'Invalid license text')
                    if name.endswith('.json'):
                        require(isinstance(json.loads(value), dict), 'Provenance JSON must be an object')
                new[name] = value
            changes.append(item)
        old_classes = {n: v for n, v in old.items() if n.endswith('.class')}
        new_classes = {n: v for n, v in new.items() if n.endswith('.class')}
        require(old_classes == new_classes, 'Production class bytes/ownership changed ' + kind)
        approved = {op['entry'] for op in plan['operations'] if op['kind'] == kind} | {META, MANIFEST}
        delta = sorted(n for n in set(old) | set(new) if old.get(n) != new.get(n))
        require(set(delta) <= approved, 'Non-approved archive delta ' + kind)
        protected = [n for n in old if n not in approved]
        require(all(n in new and new[n] == old[n] for n in protected), 'Protected bytes changed ' + kind)
        if kind == 'native':
            require(new == old, 'Native13 must be byte-identical')
        report[kind] = dict(name=NAMES[kind], baseline_sha256=actual_sha, class_files_unchanged=len(old_classes),
                            changed=[n for n in delta if n in old and n in new],
                            removed=[n for n in delta if n not in new], added=[n for n in delta if n not in old],
                            approved_operations=changes, protected_unchanged=len(protected),
                            unchanged_core_runtime={n: digest(old[n]) for n in sorted(protected)
                                if n.startswith(('core/', 'native/')) or n.endswith(('.wasm', '.dll', '.so', '.dylib', '.exe', '.jar'))},
                            native_original_whole_jar=kind == 'native')
        archives[kind], baselines[kind] = new, old
    for op in plan['operations']:
        if op['action'] == 'remove':
            hits = removed_reference_hits(op['kind'], op['entry'], archives)
            require(not hits, 'Removed asset still has direct packaged references: ' + op['entry'] + ': ' + repr(hits))
    owners, mods = {}, {}
    for kind, entries in archives.items():
        for name in entries:
            if name.endswith('.class'):
                require(name not in owners, 'Duplicate matched class: ' + name)
                owners[name] = kind
        for mod in tomllib.loads(entries[META].decode())['mods']:
            require(mod['modId'] not in mods, 'Duplicate mod ID')
            mods[mod['modId']] = mod['version']
    require(mods == VERSIONS, 'Matched mod versions differ')
    witness = dict(schema=BUILD_SCHEMA, ok=True, mode='resource-only-plan', plan_path=str(safe_path(plan_path, True)),
                   plan_sha256=digest(raw), source_fence=source, mods=report, mod_ids=mods,
                   duplicate_classes=0, production_compiled=False, compiled_outputs_used=False,
                   regression_compilation_may_run=True,
                   installed=False, published=False, minecraft_started=False, real_network_tested=False,
                   restore_legacy_pngs=False, regression_checks=None,
                   limits=['Exact byte/resource/metadata audit, not a visual or legal clearance opinion.',
                           'Removed-resource scan checks direct encoded references; no running-game dynamic resource lookup was exercised.',
                           'No Minecraft/GPU/game-controller/ROM/live multiplayer playtest was run.'])
    return plan, raw, archives, witness


def jar_bytes(entries):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for name, value in sorted(entries.items()):
            entry_path(name)
            info = zipfile.ZipInfo(name, (2026, 9, 14, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_DEFLATED
            z.writestr(info, value)
    return out.getvalue()


def regression_checks():
    results, logs = {}, {}
    for project, minimum, max_skipped in [('piq-fc-arcade', 1537, 8), ('piq-native-arcade', 80, 0),
                                         ('piq-sfc-home', 338, 0)]:
        print('Running existing offline check (outputs will not be shipped): ' + project, flush=True)
        result = subprocess.run(['cmd.exe', '/d', '/c', 'gradlew.bat', 'check', '--offline', '--console=plain'],
                                cwd=ROOT / project, env=dict(os.environ, JAVA_HOME=str(JAVA)),
                                capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=900)
        logs[project] = result.stdout + '\n' + result.stderr
        print(logs[project][-5000:], flush=True)
        require(result.returncode == 0, 'Existing regression check failed: ' + project)
        totals = {key: 0 for key in ('tests', 'failures', 'errors', 'skipped')}
        xml_hashes = {}
        for path in sorted((ROOT / project / 'build/test-results/test').glob('TEST-*.xml')):
            raw = path.read_bytes()
            doc = ET.fromstring(raw)
            xml_hashes[path.relative_to(ROOT).as_posix()] = digest(raw)
            for key in totals:
                totals[key] += int(doc.attrib[key])
        require(totals['tests'] >= minimum and totals['errors'] == totals['failures'] == 0
                and totals['skipped'] <= max_skipped, 'Regression evidence failed: ' + project + repr(totals))
        results[project] = dict(**totals, test_xml=xml_hashes, compiled_classes=check_compiled_classes(project))
    return results, logs


def check_compiled_classes(project):
    """Read-only cross-check: check outputs are evidence, never release inputs."""
    kind, prefixes = {
        'piq-fc-arcade': ('fc', ('cn/piq/fcarcade/', 'cn/piq/retro/')),
        'piq-native-arcade': ('native', ('cn/piq/nativearcade/',)),
        'piq-sfc-home': ('sfc', ('cn/piq/sfchome/',)),
    }[project]
    _, _, baseline = read_jar(BASE_DIR / BASE[kind][0])
    expected = {n: digest(v) for n, v in baseline.items() if n.endswith('.class') and n.startswith(prefixes)}
    folder = ROOT / project / 'build/classes/java/main'
    actual = {p.relative_to(folder).as_posix(): file_sha(p) for p in folder.rglob('*.class')}
    require(actual == expected, 'Regression compiler class bytes differ from frozen FC37: ' + project + ': ' + repr(
        sorted(n for n in set(expected) | set(actual) if expected.get(n) != actual.get(n))))
    return dict(compared=len(actual), changed=0, added=0, removed=0, used_as_release_input=False)


def exclusive_json(path, value):
    path = safe_path(path)
    require(path.is_relative_to(ROOT) and not path.exists(), 'Report must be a new workspace path')
    path.parent.mkdir(parents=True, exist_ok=True)
    safe_path(path.parent)
    with path.open('x', encoding='utf-8') as f:
        json.dump(value, f, ensure_ascii=False, indent=2)


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--plan', type=Path, required=True)
    p.add_argument('--plan-sha256', required=True)
    p.add_argument('--freeze', action='store_true')
    p.add_argument('--output', type=Path)
    p.add_argument('--report', type=Path)
    p.add_argument('--check-regressions', action='store_true')
    a = p.parse_args()
    require(not a.freeze or a.output is not None, '--freeze needs a new --output')
    require(a.freeze or a.output is None, '--output is only for explicitly requested freezing')
    require(not a.freeze or a.check_regressions, 'Release freezing requires this run\'s existing check regression')
    if a.output:
        out = safe_path(a.output)
        require(out.is_relative_to(ROOT / 'piq-fc-arcade/build') and not out.exists(), 'New build stage required')
    if a.report:
        require(not safe_path(a.report).exists(), 'Never overwrite previous QA report')
    plan, plan_raw, archives, witness = make_plan(a.plan, a.plan_sha256)
    logs = {}
    if a.check_regressions:
        witness['regression_checks'], logs = regression_checks()
    require(source_snapshot(plan) == witness['source_fence'], 'Source fence changed during preparation/check')
    require(safe_path(a.plan, True).read_bytes() == plan_raw, 'Approval plan changed during preparation/check')
    raw_archives = {kind: (BASE_DIR / BASE[kind][0]).read_bytes() if kind == 'native' else jar_bytes(entries)
                    for kind, entries in archives.items()}
    for kind, raw in raw_archives.items():
        witness['mods'][kind].update(bytes=len(raw), sha256=digest(raw))
    if a.freeze:
        out.mkdir(parents=True)
        for kind, raw in raw_archives.items():
            target = out / NAMES[kind]
            with target.open('xb') as f:
                f.write(raw)
            actual_sha, _, actual = read_jar(target)
            require(actual_sha == digest(raw) and clean(actual) == archives[kind], 'Frozen archive readback failed ' + kind)
        for project, log in logs.items():
            with (out / (project + '-check.log')).open('x', encoding='utf-8') as f:
                f.write(log)
        for kind, (name, expected) in BASE.items():
            require(file_sha(BASE_DIR / name) == expected, 'Historical baseline changed before commit')
        require(source_snapshot(plan) == witness['source_fence'] and a.plan.read_bytes() == plan_raw,
                'Input changed before witness commit')
        witness['mode'] = 'resource-only-freeze'
        witness['created_utc'] = datetime.now(timezone.utc).isoformat()
        with (out / 'approved-plan.json').open('xb') as f:
            f.write(plan_raw)
        exclusive_json(out / 'build-witness.json', witness)
    if a.report:
        exclusive_json(a.report, witness)
    print(json.dumps({k: witness[k] for k in ('ok', 'mode', 'plan_sha256', 'mods', 'production_compiled')}, ensure_ascii=False))


if __name__ == '__main__':
    main()
