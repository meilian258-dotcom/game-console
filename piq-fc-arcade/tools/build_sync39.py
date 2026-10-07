"""Freeze the reviewed FC39/Native14/SFC23(core7)/GBA7 set; never install or run Gradle.

1. --capture-inputs NEW.json: capture source hashes immediately BEFORE the Gradle check/jar.
2. --report NEW.json: read-only resource/class/metadata preflight; no javac or output JARs.
3. --freeze --source-witness NEW.json --source-witness-sha256 HASH [--output NEW_DIR]
   checks that witness, current compiler outputs and successful JUnit XML, compiles GBA
   with real Java 21, and creates a NEW four-JAR directory. No implicit install/publish.

All baseline non-owned-class resources are immutable, except the ten exact language
files and version/display metadata. Existing source-vs-release38 differences are
reported, never written back. New or changed unapproved source resources fail closed.
"""
from __future__ import annotations

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home, java_home


import argparse
import copy
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import tempfile
import time
import tomllib
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / 'piq-fc-arcade/build'
BASE_DIR = BUILD / 'review-release38-v1'
BASE_WITNESS_SHA = '0E772400143BD3DD5C734194F6070569853BB806124E109BEAEFF28E4FDACFF9'
BASE = {
    'fc': ('piq_fc_arcade-0.31.0-alpha.38.jar', '1030CF6143CE939B35D50BD7CC6C6E4BEA0C52F3EAAD9F250625991116A183B9'),
    'native': ('piq_native_arcade-0.1.0-alpha.13.jar', '49177F23C429534580E9764BB9DD5C915342FA29063AE542137EB67DF70D0797'),
    'sfc': ('piq_sfc-0.1.0-alpha.22.jar', '43C38F1382ABEA358B18FD518390826A69C554968FF6B817047DA443A57BC8E2'),
    'gba': ('piq_gba-0.1.0-alpha.6.jar', '2B3528DFA3795376A6596964FBE0DBEDA4CECDDCA79208D0C01B23A322D8DC17'),
}
VERSIONS = {'piq_fc_arcade': '0.31.0-alpha.39', 'piq_native_arcade': '0.1.0-alpha.14',
            'piq_sfc_home': '0.1.0-alpha.23', 'piq_sfc_arcade': '0.2.0-alpha.7', 'piq_gba': '0.1.0-alpha.7'}
OWNERS = {'fc': 'piq_fc_arcade', 'native': 'piq_native_arcade', 'sfc': 'piq_sfc_home', 'gba': 'piq_gba'}
NAMES = {kind: ('piq_sfc' if kind == 'sfc' else owner) + '-' + VERSIONS[owner] + '.jar' for kind, owner in OWNERS.items()}
PROJECTS = ['piq-fc-arcade', 'piq-retro-platform', 'piq-native-arcade', 'piq-sfc-arcade', 'piq-sfc-home', 'piq-gba']
PARTS = {'fc': [('piq-fc-arcade', 'piq_fc_arcade', ('cn/piq/fcarcade/', 'cn/piq/retro/'))],
         'native': [('piq-native-arcade', 'piq_native_arcade', ('cn/piq/nativearcade/',))],
         'sfc': [('piq-sfc-arcade', 'piq_sfc_arcade', ('cn/piq/sfcarcade/',)),
                 ('piq-sfc-home', 'piq_sfc_home', ('cn/piq/sfchome/',))],
         'gba': [('piq-gba', 'piq_gba', ('cn/piq/gba/',))]}
META, MANIFEST = 'META-INF/neoforge.mods.toml', 'META-INF/MANIFEST.MF'
LANG = {kind: {f'assets/{mod}/lang/{locale}.json': project + f'/src/main/resources/assets/{mod}/lang/{locale}.json'
               for project, mod, _ in parts for locale in ('zh_cn', 'en_us')} for kind, parts in PARTS.items()}
JAVA = (java_home())
CACHE = (gradle_home() / 'caches/modules-2/files-2.1')
MC = (gradle_home() / 'caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_e75ff7a3db3c8d7760682f321018318019b04f3c_output.jar')
TEST_LIMITS = {'piq-fc-arcade': (1537, 8), 'piq-native-arcade': (80, 0), 'piq-sfc-home': (338, 0)}
TEST_NOTES = ROOT / 'piq-fc-arcade/design/测试说明-同步与N锁定-20260914.md'
LIMITS = [
    'These are local test files, not an installed or published game release.',
    'Home FC/SFC player-hosted MEDIA control is not implemented; do not enable its support bit.',
    'Legacy FC arcade cabinets retain their existing LOCAL_SYNC-only physical route; a registered server core alone does not enable another mode.',
    'GBA handheld remains local single-player, not multiplayer LOCAL_SYNC or server/player streaming; GBA link emulation is not implemented.',
    'Server-hosted cabinet/home availability remains backend-, runtime-, permission- and server-config-dependent. Never advertise all modes on all devices.',
    'Java/JUnit/archive checks do not constitute Minecraft, real ROM, dedicated-server, two-client, controller, performance or network acceptance.',
]


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def sha(raw):
    return hashlib.sha256(raw).hexdigest().upper()


def safe(path, existing=False):
    path = Path(os.path.abspath(path))
    for item in (path, *path.parents):
        try:
            attrs = item.lstat()
        except FileNotFoundError:
            continue
        require(not stat.S_ISLNK(attrs.st_mode) and not getattr(attrs, 'st_file_attributes', 0) & 0x400,
                'Symlink/reparse path rejected: ' + str(item))
    if existing:
        require(path.is_file(), 'Missing regular file: ' + str(path))
    return path


def file_sha(path):
    return sha(safe(path, True).read_bytes())


def exclusive_json(path, data):
    path = safe(path)
    require(path.is_relative_to(ROOT) and not path.exists(), 'Report must be a new workspace file: ' + str(path))
    path.parent.mkdir(parents=True, exist_ok=True)
    safe(path.parent)
    with path.open('x', encoding='utf-8') as stream:
        json.dump(data, stream, ensure_ascii=False, indent=2)


def snapshot():
    paths = {Path(__file__).resolve()}
    if TEST_NOTES.is_file():
        paths.add(TEST_NOTES)
    for project in PROJECTS:
        base = ROOT / project
        for folder in ('src', 'gradle', 'helper/src'):
            for path in (base / folder).rglob('*'):
                safe(path)
                if path.is_file():
                    paths.add(path)
        for name in ('build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew.bat', 'LICENSE'):
            if (base / name).is_file():
                paths.add(base / name)
    return {p.relative_to(ROOT).as_posix(): file_sha(p) for p in sorted(paths)}


def archive(raw):
    require(len(raw) <= 128 * 1024 * 1024, 'JAR exceeds compressed budget')
    result, seen, total = {}, set(), 0
    with zipfile.ZipFile(io.BytesIO(raw)) as jar:
        require(len(jar.infolist()) <= 20000, 'JAR entry limit')
        for info in jar.infolist():
            name = info.orig_filename
            require(name == info.filename and name and not name.startswith('/') and '\\' not in name and ':' not in name
                    and not any(ord(c) < 32 or ord(c) == 127 for c in name)
                    and all(p not in ('', '.', '..') for p in name.rstrip('/').split('/')), 'Unsafe JAR entry ' + name)
            require(name not in seen and not info.flag_bits & 1 and not stat.S_ISLNK(info.external_attr >> 16), 'Duplicate/encrypted/link JAR entry ' + name)
            require(not re.fullmatch(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)', name, re.I), 'Signed JAR cannot be repacked')
            seen.add(name)
            total += info.file_size
            require(total <= 256 * 1024 * 1024, 'JAR expanded budget')
            if not info.is_dir():
                result[name] = jar.read(info)
        require(jar.testzip() is None, 'JAR CRC failure')
    require(META in result and MANIFEST in result, 'Missing mod metadata/manifest')
    return result


def load(path):
    raw = safe(path, True).read_bytes()
    return sha(raw), archive(raw)


def jar_bytes(entries):
    output = io.BytesIO()
    with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as jar:
        for name, raw in sorted(entries.items()):
            require(name and not name.startswith('/') and '\\' not in name and ':' not in name
                    and not any(ord(c) < 32 or ord(c) == 127 for c in name)
                    and all(p not in ('', '.', '..') for p in name.split('/')), 'Unsafe output JAR entry ' + name)
            info = zipfile.ZipInfo(name, (2026, 9, 14, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            jar.writestr(info, raw)
    return output.getvalue()


def read_baselines():
    require(file_sha(BASE_DIR / 'build-witness.json') == BASE_WITNESS_SHA, 'Release38 source witness changed')
    witness = json.loads((BASE_DIR / 'build-witness.json').read_bytes())
    require(witness.get('ok') and witness.get('schema') == 'piq-release38-build-1', 'Wrong release38 witness')
    result = {}
    for kind, (name, pin) in BASE.items():
        actual, entries = load(BASE_DIR / name)
        require(actual == pin, 'Frozen release38 JAR changed: ' + kind)
        result[kind] = entries
    return result, witness['source_fence']['files']


def own_class(kind, name):
    return name.endswith('.class') and any(name.startswith(prefixes) for _, _, prefixes in PARTS[kind])


def language(raw, name):
    def unique(pairs):
        out = {}
        for key, value in pairs:
            require(key not in out, 'Duplicate language key ' + key)
            out[key] = value
        return out
    value = json.loads(raw.decode('utf-8'), object_pairs_hook=unique)
    require(isinstance(value, dict) and all(isinstance(k, str) and isinstance(v, str) for k, v in value.items()), 'Invalid language ' + name)
    return value


def language_overlay(kind, old):
    result = {}
    for name, source in LANG[kind].items():
        require(name in old, 'New language path is not authorized: ' + name)
        raw = safe(ROOT / source, True).read_bytes()
        before, after = language(old[name], name), language(raw, name)
        require(before.keys() == after.keys(), 'Language IDs added/removed: ' + name)
        for key in before:
            require(re.findall(r'%(?:[0-9]+\$)?[sd%]', before[key]) == re.findall(r'%(?:[0-9]+\$)?[sd%]', after[key]), 'Language format placeholders changed: ' + key)
            require(not re.search(r'\bPIQ\b', after[key]), 'Unreviewed display brand: ' + key)
        result[name] = raw
    return result


def protected_source_drift(before38, now):
    allowed = {p for mapping in LANG.values() for p in mapping.values()}
    old = {p: h for p, h in before38.items() if '/src/main/resources/' in p}
    new = {p: h for p, h in now.items() if '/src/main/resources/' in p}
    return [{'source': p, 'release38_source_sha256': old.get(p), 'current_sha256': new.get(p)}
            for p in sorted(old.keys() | new.keys()) if old.get(p) != new.get(p)
            and p not in allowed and not p.endswith('/' + META)]


def resource_differences(kind, old, compiled, baseline_sources):
    rows = []
    for name in sorted(old.keys() | compiled.keys()):
        if name in (META, MANIFEST) or name in LANG[kind] or own_class(kind, name) or old.get(name) == compiled.get(name):
            continue
        source_matches = [project + '/src/main/resources/' + name for project, _, _ in PARTS[kind]
                          if baseline_sources.get(project + '/src/main/resources/' + name) == sha(compiled[name])] if name in compiled else []
        # A historically omitted source resource is not a newly authorized resource:
        # it stays omitted, but its release38 source identity proves known drift.
        rows.append({'entry': name, 'baseline_sha256': sha(old[name]) if name in old else None,
                     'compiled_sha256': sha(compiled[name]) if name in compiled else None,
                     'action': 'preserve_frozen_baseline' if name in old else 'omit_historical_source_only_entry',
                     'known_release38_source': source_matches,
                     'blocked': name not in old and not source_matches})
    return rows


def metadata(kind, old, supplied):
    expected = copy.deepcopy(tomllib.loads(old[META].decode('utf-8')))
    actual = tomllib.loads(supplied.decode('utf-8'))
    require(len(actual['mods']) == len(expected['mods']), 'Wrong mod-table count: ' + kind)
    actual_mods = {m['modId']: m for m in actual['mods']}
    require(len(actual_mods) == len(actual['mods']), 'Duplicate mod ID inside ' + kind)
    changes = []
    for mod in expected['mods']:
        identity = mod['modId']
        require(identity in actual_mods, 'Mod ID removed: ' + identity)
        mod['version'] = VERSIONS[identity]
        for field in ('displayName', 'description'):
            if field in actual_mods[identity] and actual_mods[identity][field] != mod.get(field):
                changes.append({'mod_id': identity, 'field': field, 'before': mod.get(field), 'after': actual_mods[identity][field]})
                mod[field] = actual_mods[identity][field]
    for owner, deps in expected.get('dependencies', {}).items():
        for dep in deps:
            if dep['modId'] == 'piq_fc_arcade':
                dep['versionRange'] = '[' + VERSIONS['piq_fc_arcade'] + (',0.32)' if owner == 'piq_gba' else ',0.32.0)')
            if dep['modId'] == 'piq_sfc_arcade':
                dep['versionRange'] = '[' + VERSIONS['piq_sfc_arcade'] + ',0.3.0)'
    require(actual == expected, 'Unexpected metadata semantics (only versions/display text allowed): ' + kind)
    return changes


def version_manifest(kind, old):
    owner = OWNERS[kind]
    old_version = next(m['version'] for m in tomllib.loads(old[META].decode('utf-8'))['mods'] if m['modId'] == owner)
    result, count = re.subn(rb'(?m)^(Implementation-Version: )' + re.escape(old_version.encode()) + rb'(\r?\n)',
                            lambda m: m[1] + VERSIONS[owner].encode() + m[2], old[MANIFEST])
    require(count == 1, 'Ambiguous baseline manifest version: ' + kind)
    return result


def compiled_part(project, mod, prefixes, fresh):
    path = ROOT / project / 'build/libs' / (mod + '-' + VERSIONS[mod] + '.jar')
    pin, entries = load(path)
    parsed = tomllib.loads(entries[META].decode('utf-8'))
    issues = []
    def check(ok, message):
        if not ok:
            issues.append(message)
            if fresh:
                require(False, message)
    check([(m['modId'], m['version']) for m in parsed['mods']] == [(mod, VERSIONS[mod])], 'Compiled mod/version mismatch: ' + project)
    classes = ROOT / project / 'build/classes/java/main'
    actual = {p.relative_to(classes).as_posix(): file_sha(p) for p in classes.rglob('*.class')}
    packaged = {n: sha(raw) for n, raw in entries.items() if n.endswith('.class') and n.startswith(prefixes)}
    check(actual and actual == packaged, 'Compiled JAR/classes directory mismatch: ' + project)
    class_differences = sorted(n for n in actual.keys() | packaged.keys() if actual.get(n) != packaged.get(n))
    stale = []
    roots = [ROOT / project / 'src/main/java'] + ([ROOT / 'piq-retro-platform/src/main/java'] if mod == 'piq_fc_arcade' else [])
    for name in actual:
        source_name = name[:-6].split('$')[0] + '.java'
        sources = [r / source_name for r in roots if (r / source_name).is_file()]
        check(len(sources) == 1, 'Owned class has no exact source owner: ' + project + ':' + name)
        if len(sources) != 1:
            continue
        if safe(classes / name, True).stat().st_mtime_ns < safe(sources[0], True).stat().st_mtime_ns:
            stale.append(name)
    check(not stale, 'Class output predates its source: ' + project + ': ' + repr(stale))
    return entries, {'path': str(path.relative_to(ROOT)), 'sha256': pin, 'class_count': len(actual), 'stale_classes': stale,
                     'class_hashes': actual, 'class_differences': class_differences, 'validation_errors': issues}


def merge_sfc(parts):
    core, home = parts
    require(set(core) & set(home) == {META, MANIFEST}, 'SFC thin JAR ownership collision')
    c, h = [tomllib.loads(p[META].decode('utf-8')) for p in parts]
    require({k: v for k, v in c.items() if k not in ('mods', 'dependencies')} ==
            {k: v for k, v in h.items() if k not in ('mods', 'dependencies')}, 'SFC header metadata mismatch')
    def body(raw):
        text = raw.decode('utf-8').replace('\r\n', '\n')
        require(text.count('[[mods]]') == 1, 'Ambiguous SFC mod table')
        return text[text.index('[[mods]]'):].rstrip() + '\n'
    prefix = core[META].decode('utf-8').split('[[mods]]', 1)[0]
    merged = {name: raw for part in parts for name, raw in part.items() if name not in (META, MANIFEST)}
    merged[META] = (prefix + body(core[META]) + '\n' + body(home[META])).encode('utf-8')
    require(tomllib.loads(merged[META].decode('utf-8')) == dict(c, mods=c['mods'] + h['mods'], dependencies=c['dependencies'] | h['dependencies']), 'SFC metadata lost semantics')
    return merged


def dependencies():
    result = []
    for group, artifact, version in [('net.neoforged.fancymodloader', 'loader', '4.0.43'), ('cpw.mods', 'securejarhandler', '3.0.8'),
                                     ('com.electronwill.night-config', 'core', '3.8.3'), ('com.electronwill.night-config', 'toml', '3.8.3')]:
        found = list((CACHE / group / artifact / version).rglob(artifact + '-' + version + '.jar'))
        require(len(found) == 1, 'Required real dependency missing: ' + artifact)
        result.extend(found)
    manifest = json.loads(safe(MC.parent.parent / 'artifacts/minecraft_1.21.1_version_manifest.json', True).read_bytes())
    for library in manifest['libraries']:
        parts = library['name'].split(':')
        if len(parts) == 3:
            result.extend((CACHE / parts[0] / parts[1] / parts[2]).rglob(parts[1] + '-' + parts[2] + '.jar'))
    result.extend(p for p in CACHE.rglob('*.jar') if not any(x in p.name for x in ('-sources', '-javadoc', '-userdev')))
    return list(dict.fromkeys(result))


def compile_gba(fc, java):
    # Real javac, no copied old GBA classes and no native helper/runtime rebuild.
    with tempfile.TemporaryDirectory(prefix='piq-sync39-gba-') as folder:
        tmp = Path(folder); classes = tmp / 'classes'; classes.mkdir(); empty = tmp / 'empty'; empty.mkdir()
        local_fc = tmp / 'fc.jar'; local_fc.write_bytes(fc)
        require(file_sha(local_fc) == sha(fc), 'Temporary FC compiler dependency changed')
        deps = [MC, *dependencies()]
        pins = {str(p): file_sha(p) for p in deps}
        args = tmp / 'javac.args'
        args.write_text('-cp\n"' + os.pathsep.join(str(p).replace('\\', '/') for p in [local_fc, *deps]) + '"\n', encoding='utf-8')
        sources = sorted((ROOT / 'piq-gba/src/main/java').rglob('*.java'))
        require(sources, 'No GBA production sources')
        command = [java / 'bin/javac.exe', '@' + str(args), '--release', '21', '-encoding', 'UTF-8', '-proc:none',
                   '-sourcepath', empty, '-d', classes, *sources]
        result = subprocess.run(list(map(str, command)), cwd=tmp, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=180)
        require(result.returncode == 0, 'GBA javac failed:\n' + result.stdout + '\n' + result.stderr)
        require(pins == {str(p): file_sha(p) for p in deps} and file_sha(local_fc) == sha(fc), 'GBA compiler dependencies changed')
        produced = {p.relative_to(classes).as_posix(): p.read_bytes() for p in classes.rglob('*.class')}
        require(produced and all(own_class('gba', n) for n in produced), 'Unexpected GBA compiler class owner')
        return produced, {'javac_stdout': result.stdout, 'javac_stderr': result.stderr, 'sources': [str(p.relative_to(ROOT)) for p in sources],
                          'class_hashes': {n: sha(v) for n, v in produced.items()}, 'dependencies': pins, 'fc_dependency_sha256': sha(fc),
                          'helper_compiled': False, 'runtime_installed': False}


def tests(captured_ns, strict=True):
    result, hashes, issues = {}, {}, []
    for project, (minimum, max_skipped) in TEST_LIMITS.items():
        files = sorted((ROOT / project / 'build/test-results/test').glob('TEST-*.xml'))
        if not files:
            issues.append('Missing JUnit XML: ' + project)
        counts = dict(tests=0, failures=0, errors=0, skipped=0)
        for path in files:
            try:
                raw = safe(path, True).read_bytes(); doc = ET.fromstring(raw)
                hashes[str(path.relative_to(ROOT))] = sha(raw)
                if path.stat().st_mtime_ns < captured_ns:
                    issues.append('JUnit XML predates source capture; rerun test task: ' + str(path))
                if doc.tag != 'testsuite' or doc.findall('.//failure') or doc.findall('.//error'):
                    issues.append('JUnit failure/error: ' + str(path))
                for key in counts:
                    counts[key] += int(doc.attrib[key])
            except (OSError, ValueError, KeyError, ET.ParseError) as failure:
                issues.append('Unreadable JUnit XML: ' + str(path) + ': ' + str(failure))
        if not (counts['tests'] >= minimum and counts['failures'] == counts['errors'] == 0 and counts['skipped'] <= max_skipped):
            issues.append('JUnit totals outside reviewed baseline: ' + project + ' ' + repr(counts))
        result[project] = counts
    # GBA has no src/test Java suite/Gradle project; do not invent a JUnit pass.
    require(not strict or not issues, '\n'.join(issues))
    return result, hashes, issues


def identity_graph(archives):
    ids, classes, runtime = {}, {}, {}
    banned = ('.nes', '.sfc', '.smc', '.gba', '.gb', '.gbc', '.rom', '.bin', '.zip', '.7z', '.rar')
    for kind, entries in archives.items():
        for name, raw in entries.items():
            low = name.lower()
            require(not low.endswith(banned) and not re.search(r'(^|/)(roms|bios)/', low), 'ROM/BIOS/archive entry: ' + name)
            require(not name.startswith(('META-INF/jarjar/', 'META-INF/versions/')), 'Nested/multi-release runtime: ' + name)
            if name.endswith('.class'):
                require(name not in classes, 'Duplicate class across mods: ' + name); classes[name] = kind
                if name.startswith('cn/piq/retro/'):
                    require(kind == 'fc', 'Public platform class is not owned by FC')
            if low.endswith(('.wasm', '.dll', '.so', '.dylib', '.exe', '.jar')):
                require(name not in runtime, 'Duplicate runtime entry: ' + name); runtime[name] = kind
        data = tomllib.loads(entries[META].decode('utf-8'))
        for mod in data['mods']:
            require(mod['modId'] not in ids, 'Duplicate mod ID across matched set'); ids[mod['modId']] = mod['version']
        for owner, deps in data.get('dependencies', {}).items():
            require(len({d['modId'] for d in deps}) == len(deps), 'Duplicate dependency for ' + owner)
            require(all(d['modId'] != 'piq_retro_platform' for d in deps), 'Standalone public platform mod dependency')
    require(ids == VERSIONS, 'Unexpected matched mod IDs/versions')
    return {'mod_ids': ids, 'classes': len(classes), 'duplicate_classes': 0, 'duplicate_mod_ids': 0, 'rom_bios_entries': 0, 'runtime_owners': runtime}


def build_plan(before, freeze, java):
    baselines, sources38 = read_baselines()
    report = {'schema': 'piq-sync39-build-1', 'ok': False, 'mode': 'freeze' if freeze else 'preflight', 'mods': {}, 'validation_errors': [],
              'source_resource_drift': protected_source_drift(sources38, before), 'limits': LIMITS,
              'installed': False, 'published': False, 'minecraft_started': False, 'real_network_tested': False,
              'gradle_invoked': False, 'gba_javac_invoked': freeze}
    proposed, staged = {}, {}
    for kind, old in baselines.items():
        details = {'baseline_sha256': BASE[kind][1], 'compiled': []}
        report['mods'][kind] = details
        if kind == 'gba':
            resources = ROOT / 'piq-gba/src/main/resources'
            compiled = {p.relative_to(resources).as_posix(): safe(p, True).read_bytes() for p in resources.rglob('*') if p.is_file()}
            if freeze:
                classes, log = compile_gba(staged['fc'], java); compiled.update(classes); details['javac'] = log
            else:
                details['compile_pending'] = 'Actual GBA javac runs only in explicit --freeze; no old GBA classes are claimed as compiled.'
        else:
            parts = []
            for project, mod, prefixes in PARTS[kind]:
                try:
                    entries, evidence = compiled_part(project, mod, prefixes, freeze)
                    parts.append(entries); details['compiled'].append(evidence)
                    report['validation_errors'].extend(evidence['validation_errors'])
                except Exception as failure:
                    if freeze:
                        raise
                    report['validation_errors'].append('Compiled input unavailable: ' + project + ': ' + str(failure))
            if len(parts) != len(PARTS[kind]):
                continue
            try:
                compiled = merge_sfc(parts) if kind == 'sfc' else parts[0]
            except Exception as failure:
                if freeze:
                    raise
                report['validation_errors'].append('SFC merge validation: ' + str(failure))
                continue
        differences = resource_differences(kind, old, compiled, sources38)
        details['preserved_resource_differences'] = differences
        try:
            require(META in compiled, 'Missing current metadata: ' + kind)
            details['current_metadata'] = tomllib.loads(compiled[META].decode('utf-8'))
            details['metadata_display_changes'] = metadata(kind, old, compiled[META])
        except Exception as failure:
            if freeze:
                raise
            report['validation_errors'].append('Metadata validation: ' + kind + ': ' + str(failure))
        if META not in compiled:
            continue
        new = {n: raw for n, raw in old.items() if not own_class(kind, n)}
        new.update({n: raw for n, raw in compiled.items() if own_class(kind, n)})
        try:
            new.update(language_overlay(kind, old))
        except Exception as failure:
            if freeze:
                raise
            report['validation_errors'].append('Language validation: ' + kind + ': ' + str(failure))
        new[META], new[MANIFEST] = compiled[META], version_manifest(kind, old)
        if kind in ('fc', 'native'):
            if compiled.get(MANIFEST) != new[MANIFEST]:
                require(not freeze, 'Compiled manifest drift: ' + kind)
                report['validation_errors'].append('Compiled manifest drift: ' + kind)
        protected = [n for n in old if not own_class(kind, n) and n not in (META, MANIFEST) and n not in LANG[kind]]
        require(all(new[n] == old[n] for n in protected), 'Protected byte changed: ' + kind)
        details.update(protected_unchanged=len(protected), protected_sha256={n: sha(old[n]) for n in protected},
                       changed=sorted(n for n in old.keys() & new.keys() if old[n] != new[n]),
                       added=sorted(new.keys() - old.keys()), removed=sorted(old.keys() - new.keys()))
        require(all(own_class(kind, n) for n in details['removed']), 'Non-class entry removed: ' + kind)
        report['mods'][kind] = details; proposed[kind] = new
        if freeze:
            staged[kind] = jar_bytes(new)
    report['blocking_resources'] = report['source_resource_drift'] + [dict(kind=k, **row) for k, d in report['mods'].items()
                                                                    for row in d.get('preserved_resource_differences', []) if row['blocked']]
    if freeze:
        require(not report['blocking_resources'], 'Unapproved resource drift; run --report and review: ' + repr(report['blocking_resources']))
        report.update(identity_graph(proposed))
    report['ok'] = not report['blocking_resources'] and not report['validation_errors']
    return report, staged, proposed


def main():
    sys.stdout.reconfigure(encoding='utf-8'); sys.stderr.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--capture-inputs', type=Path)
    parser.add_argument('--report', type=Path)
    parser.add_argument('--freeze', action='store_true')
    parser.add_argument('--output', type=Path, default=BUILD / 'review-sync39-v1')
    parser.add_argument('--source-witness', type=Path)
    parser.add_argument('--source-witness-sha256')
    parser.add_argument('--java-home', type=Path, default=JAVA)
    args = parser.parse_args()
    require(not args.capture_inputs or not (args.freeze or args.report or args.source_witness), 'Capture is a separate pre-Gradle operation')
    if args.capture_inputs:
        started = time.time_ns(); before = snapshot(); require(before == snapshot(), 'Source changed while capturing')
        data = {'schema': 'piq-sync39-source-1', 'captured_ns': started, 'created_utc': datetime.now(timezone.utc).isoformat(), 'inputs': before,
                'expected_versions': VERSIONS, 'instructions': 'Run actual Gradle check/jar now; no Gradle outputs were captured or compiled here.'}
        exclusive_json(args.capture_inputs, data)
        print(json.dumps({'ok': True, 'source_witness': str(args.capture_inputs), 'sha256': file_sha(args.capture_inputs)}, ensure_ascii=False)); return
    require(args.freeze or args.report, 'Use --report NEW.json for non-compiling preflight, or explicit --freeze')
    if args.report:
        require(not safe(args.report).exists(), 'Do not overwrite a previous report')
    before = snapshot(); witness = None
    if args.freeze:
        require(args.source_witness and re.fullmatch(r'[0-9A-Fa-f]{64}', args.source_witness_sha256 or ''), 'Freeze needs pinned pre-Gradle source witness')
        require(file_sha(args.source_witness) == args.source_witness_sha256.upper(), 'Source witness hash changed')
        witness = json.loads(safe(args.source_witness, True).read_bytes())
        require(witness.get('schema') == 'piq-sync39-source-1' and witness.get('expected_versions') == VERSIONS and witness.get('inputs') == before,
                'Source differs from pre-Gradle witness; recapture and rebuild')
        out = safe(args.output)
        require(out.is_relative_to(BUILD) and out != BUILD and not out.exists(), 'Freeze requires a new child of FC build; no overwrite')
        junit, xml_hashes, _ = tests(witness['captured_ns'])
    report, staged, proposed = build_plan(before, args.freeze, args.java_home)
    after = snapshot()
    changed_sources = sorted(p for p in before.keys() | after.keys() if before.get(p) != after.get(p))
    require(not args.freeze or not changed_sources, 'Source changed during GBA compile/freeze')
    report['inputs'] = before; report['source_before_after_equal'] = not changed_sources
    report['sources_changed_during_run'] = changed_sources
    if not args.freeze:
        junit, xml_hashes, test_issues = tests(0, strict=False)
        report.update(tests=junit, test_xml=xml_hashes, junit_freshness_checked=False)
        report['validation_errors'].extend(test_issues)
        if changed_sources:
            report['validation_errors'].append('Source changed during preflight: ' + repr(changed_sources))
        report['ok'] = report['ok'] and not report['validation_errors']
    if args.freeze:
        require(xml_hashes == {p: file_sha(ROOT / p) for p in xml_hashes}, 'JUnit XML changed during packaging')
        require(file_sha(args.source_witness) == args.source_witness_sha256.upper(), 'Source witness changed during packaging')
        for kind, (filename, expected_sha) in BASE.items():
            require(file_sha(BASE_DIR / filename) == expected_sha, 'Frozen baseline changed during packaging: ' + kind)
        for details in report['mods'].values():
            for item in details['compiled']:
                require(file_sha(ROOT / item['path']) == item['sha256'], 'Compiled JAR changed during packaging')
        report.update(tests=junit, test_xml=xml_hashes, source_witness_sha256=args.source_witness_sha256.upper(), source_witness=witness,
                      created_utc=datetime.now(timezone.utc).isoformat(), ok=True)
        out.mkdir(parents=True)
        for kind, raw in staged.items():
            path = out / NAMES[kind]
            with path.open('xb') as stream:
                stream.write(raw)
            actual, readback = load(path)
            require(actual == sha(raw) and readback == proposed[kind], 'Final JAR hash/entry readback mismatch: ' + kind)
            report['mods'][kind].update(sha256=actual, bytes=len(raw), filename=NAMES[kind])
        require(snapshot() == before, 'Source changed during final write; outputs are NOT accepted')
        exclusive_json(out / 'source-witness.json', witness)
        exclusive_json(out / 'build-witness.json', report)
        if TEST_NOTES.is_file():
            notes = safe(TEST_NOTES, True).read_bytes()
            require(sha(notes) == before[TEST_NOTES.relative_to(ROOT).as_posix()], 'Reviewed testing notes changed')
            with (out / TEST_NOTES.name).open('xb') as stream:
                stream.write(notes)
        with (out / 'TESTING-AND-LIMITS.txt').open('x', encoding='utf-8') as stream:
            stream.write('FC39 / Native14 / SFC23 (bundled core7) / GBA7 — test package only\n\n')
            stream.write('\n'.join('- ' + value for value in LIMITS) + '\n\n')
            stream.write('Source capture SHA256: ' + args.source_witness_sha256.upper() + '\n')
            stream.write('JUnit evidence: ' + json.dumps(junit, ensure_ascii=False) + '\n')
            stream.write('Acceptance still needed: each supported physical-device mode, fresh/returning players, host departure/P1 replacement, permission denial, controller release, watch continuity, runtime missing/busy, startup timeout, save/reopen, low-bandwidth behavior.\n')
        print(json.dumps({'ok': True, 'output': str(out), 'jars': {k: d['sha256'] for k, d in report['mods'].items()}, 'tests': junit}, ensure_ascii=False))
    if args.report:
        exclusive_json(args.report, report)
        print(json.dumps({'ok': report['ok'], 'report': str(args.report), 'blocking_resources': report['blocking_resources'], 'freeze_executed': args.freeze}, ensure_ascii=False))


if __name__ == '__main__':
    main()
