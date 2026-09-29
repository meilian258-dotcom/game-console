"""Build and freeze the matched four-mod audit fixes; never install or alter old releases."""
import argparse
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import tomllib
import xml.etree.ElementTree as ET

from freeze_fc_core_alpha19 import read_jar, safe_path, digest, require, META, MANIFEST
from freeze_fc_compact_alpha20 import RESTORE
from prepare_cabinet_multiplayer21 import clean, jar_bytes
import verify_retro_alpha19 as q

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / 'piq-sfc-home/tools'))
from merge_sfc_addon import ownership, parsed_metadata, WASM, AT

JAVA = Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot')
PROJECTS = ['piq-fc-arcade', 'piq-sfc-arcade', 'piq-sfc-home', 'piq-native-arcade']
NAMES = {'fc': 'piq_fc_arcade-0.31.0-alpha.34.jar', 'native': 'piq_native_arcade-0.1.0-alpha.12.jar',
         'sfc': 'piq_sfc-0.1.0-alpha.20.jar', 'gba': 'piq_gba-0.1.0-alpha.4.jar'}
BASE = {
    'fc': ('piq-fc-arcade/build/review-sync33-v1/piq_fc_arcade-0.31.0-alpha.33.jar', 'F36169E46B13CF46868851447B8518BC38C10967A03994AF26E89CE7B1E4FE00'),
    'native': ('piq-fc-arcade/build/review-sync33-v1/piq_native_arcade-0.1.0-alpha.11.jar', 'F582314F64A2DC556FAC1704719C04EE9E5A05154C146FA1C49FCAD9615E2C5E'),
    'sfc': ('piq-fc-arcade/build/review-sync32-v1/piq_sfc-0.1.0-alpha.19.jar', 'DC50C9378C83E38907C259373CE92B06FAD403B3779D0E4B6772D94AA0343BC3'),
    'gba': ('piq-gba/build/handheld-v3-3/piq_gba-0.1.0-alpha.3.jar', '590C01BA8BF3B066C7EE8678A59B2A0387E2A9FB9DDB73FC1F7FE6524BD3ABDC')}
# Every changed/additional production class must have an explicitly reviewed owner.
STEMS = {
    'fc': set(('cn/piq/fcarcade/' + n) for n in '''server/ServerArcadeSessions server/PlayerSaveSlots
        home/HomeHardware server/InteractionTransaction client/cabinet/CabinetClientBackends client/cabinet/CabinetAudio'''.split())
        | set(('cn/piq/retro/client/' + n) for n in '''KeyboardInput KeyboardControlState KeyboardMappingState
        ControlSettingsScreen GamepadInput GamepadSettingsScreen'''.split()),
    'native': {'cn/piq/nativearcade/client/NativeArcadeClient'},
    'sfc': set(('cn/piq/sfchome/' + n) for n in '''client/SfcPlayback client/SfcRepairProgress
        server/SfcRepairLedger net/SfcRepairNetwork'''.split())
        | set(('cn/piq/sfcarcade/' + n) for n in '''server/SfcServerManager server/SfcDownloadGate
        net/SfcNetwork rom/SfcRomRepository'''.split()),
    'gba': set(('cn/piq/gba/' + n) for n in '''bridge/GbaProcessSession client/GbaHandheldClient
        client/GbaHandheldScreen'''.split())}
VERSIONS = {'piq_fc_arcade': '0.31.0-alpha.34', 'piq_native_arcade': '0.1.0-alpha.12',
            'piq_sfc_home': '0.1.0-alpha.20', 'piq_sfc_arcade': '0.2.0-alpha.7', 'piq_gba': '0.1.0-alpha.4'}


def inputs():
    paths = []
    for project in PROJECTS + ['piq-retro-platform', 'piq-gba']:
        base = ROOT / project
        for folder in ['src', 'gradle', 'helper/src']:
            paths += list((base / folder).rglob('*'))
        paths += [base / n for n in ['gradle.properties', 'build.gradle', 'settings.gradle', 'gradlew.bat', 'LICENSE']]
    return {p.relative_to(ROOT).as_posix(): digest(p.read_bytes()) for p in sorted(set(paths)) if p.is_file()}


def load(path):
    sha, _, entries = read_jar(path)
    return sha, clean(entries)


def merge_sfc():
    core_sha, core = load(ROOT / 'piq-sfc-arcade/build/libs/piq_sfc_arcade-0.2.0-alpha.7.jar')
    home_sha, home = load(ROOT / 'piq-sfc-home/build/libs/piq_sfc_home-0.1.0-alpha.20.jar')
    ownership(core, 'sfcarcade'); ownership(home, 'sfchome')
    require(set(core) & set(home) == {META, MANIFEST}, 'Unexpected SFC shared entry')
    require(core[AT] == b'public com.mojang.blaze3d.platform.NativeImage pixels\n' and WASM in core, 'SFC runtime ownership')
    c = parsed_metadata(core[META], 'piq_sfc_arcade')
    h = parsed_metadata(home[META], 'piq_sfc_home')
    def body(raw):
        text = raw.decode().replace('\r\n', '\n')
        require(text.count('[[mods]]') == 1, 'Ambiguous SFC mod table')
        return text[text.index('[[mods]]'):].rstrip() + '\n'
    meta = ('modLoader="javafml"\nloaderVersion="[4,)"\nlicense="GPL-3.0-or-later"\n\n'
            + body(core[META]) + '\n' + body(home[META])).encode()
    expected = dict(c, mods=c['mods'] + h['mods'], dependencies=c['dependencies'] | h['dependencies'])
    require(tomllib.loads(meta.decode()) == expected, 'SFC merge changed metadata semantics')
    entries = {n: b for source in [core, home] for n, b in source.items() if n not in (META, MANIFEST)}
    entries[META] = meta
    entries[MANIFEST] = ('Manifest-Version: 1.0\r\nImplementation-Title: PIQ SFC\r\n'
                         'Implementation-Version: 0.1.0-alpha.20\r\n\r\n').encode()
    return entries, {'core_compiled_sha256': core_sha, 'home_compiled_sha256': home_sha}


def compile_gba(fc, old):
    with tempfile.TemporaryDirectory(prefix='piq-audit34-gba-build-') as folder:
        tmp = Path(folder); classes = tmp / 'classes'; classes.mkdir(); empty = tmp / 'empty'; empty.mkdir()
        local_fc = tmp / 'fc.jar'; local_fc.write_bytes(fc)
        cp = os.pathsep.join(map(str, [local_fc, q.MC, *q.dependencies()]))
        args = tmp / 'javac.args'; args.write_text('-cp\n"' + cp.replace('\\', '/') + '"\n', encoding='utf-8')
        result = subprocess.run(list(map(str, [JAVA / 'bin/javac.exe', '@' + str(args), '-encoding', 'UTF-8',
            '--release', '21', '-proc:none', '-sourcepath', empty, '-d', classes,
            *sorted((ROOT / 'piq-gba/src/main/java').rglob('*.java'))])), cwd=tmp, capture_output=True,
            text=True, encoding='utf-8', errors='replace', timeout=120)
        require(result.returncode == 0, result.stdout + '\n' + result.stderr)
        entries = {p.relative_to(classes).as_posix(): p.read_bytes() for p in classes.rglob('*.class')}
        resources = ROOT / 'piq-gba/src/main/resources'
        entries.update({p.relative_to(resources).as_posix(): p.read_bytes() for p in resources.rglob('*') if p.is_file()})
        for name in ['piq-gba-runtime.properties', 'META-INF/LICENSE']:
            entries[name] = old[name]
        entries[MANIFEST] = old[MANIFEST].replace(b'0.1.0-alpha.3', b'0.1.0-alpha.4')
        return entries, {'javac_stdout': result.stdout, 'javac_stderr': result.stderr, 'helper_compiled': False}


def validate(kind, old, new):
    removed = set(old) - set(new)
    require(not removed, 'Unexpected removed entries ' + kind + ': ' + str(sorted(removed)))
    added = set(new) - set(old)
    changed = {n for n in old.keys() & new.keys() if old[n] != new[n]}
    for name in added | changed:
        require(name in (META, MANIFEST) or name.endswith('.class') and name[:-6].split('$')[0] in STEMS[kind],
                'Unreviewed ' + kind + ' entry ' + name)
    expected = tomllib.loads(old[META].decode()); actual = tomllib.loads(new[META].decode())
    for mod in expected['mods']:
        mod['version'] = VERSIONS[mod['modId']]
    if kind != 'fc':
        for deps in expected['dependencies'].values():
            for dep in deps:
                if dep['modId'] == 'piq_fc_arcade': dep['versionRange'] = '[0.31.0-alpha.34,0.32)' if kind == 'gba' else '[0.31.0-alpha.34,0.32.0)'
                if dep['modId'] == 'piq_sfc_arcade': dep['versionRange'] = '[0.2.0-alpha.7,0.3.0)'
    require(expected == actual, 'Unexpected metadata changes ' + kind + ': ' + str(actual))
    ov, nv = {'fc': ('0.31.0-alpha.33', '0.31.0-alpha.34'), 'native': ('0.1.0-alpha.11', '0.1.0-alpha.12'),
              'sfc': ('0.1.0-alpha.19', '0.1.0-alpha.20'), 'gba': ('0.1.0-alpha.3', '0.1.0-alpha.4')}[kind]
    require(new[MANIFEST] == old[MANIFEST].replace(ov.encode(), nv.encode()), 'Unexpected manifest ' + kind)
    protected = [n for n in old if n.startswith(('assets/', 'data/', 'core/'))]
    require(all(new[n] == old[n] for n in protected), 'Protected model/core/asset changed ' + kind)
    return {'added': sorted(added), 'changed': sorted(changed), 'removed': [], 'protected_unchanged': len(protected)}


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    sys.stderr.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--output', required=True, type=Path)
    args = parser.parse_args(); out = safe_path(args.output)
    require(out.is_relative_to(ROOT / 'piq-fc-arcade/build') and not out.exists(), 'New build stage required')
    before = inputs(); env = dict(os.environ, JAVA_HOME=str(JAVA))
    logs = {}
    for project in PROJECTS:
        print('Checking ' + project, flush=True)
        result = subprocess.run(['cmd.exe', '/d', '/c', 'gradlew.bat', 'check', 'jar', '--offline'],
            cwd=ROOT / project, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=600)
        logs[project] = result.stdout + '\n' + result.stderr
        require(result.returncode == 0, logs[project])
        print(logs[project][-1500:], flush=True)
    require(before == inputs(), 'Sources changed during build; retry a stable build')
    report = {'schema': 'piq-audit34-build-1', 'ok': True, 'inputs': before, 'mods': {}, 'tests': {}, 'test_xml': {},
              'installed': False, 'minecraft_started': False, 'real_network_tested': False}
    staged = {}; entries_by_kind = {}
    for kind in ['fc', 'native', 'sfc', 'gba']:
        path, pin = BASE[kind]; sha, old = load(ROOT / path)
        require(sha == pin, 'Frozen baseline identity ' + kind)
        details = {}
        if kind == 'sfc': new, details = merge_sfc()
        elif kind == 'gba': new, details = compile_gba(staged[NAMES['fc']], old)
        else:
            project = 'piq-fc-arcade' if kind == 'fc' else 'piq-native-arcade'
            compiled, new = load(ROOT / project / 'build/libs' / NAMES[kind]); details['compiled_sha256'] = compiled
        if kind == 'fc':
            for name, (source, frozen) in RESTORE.items():
                require(digest(old[name]) == frozen and digest(new[name]) in (source, frozen), 'Historical controller source changed')
                new[name] = old[name]
        details.update(validate(kind, old, new))
        raw = jar_bytes(new); staged[NAMES[kind]] = raw; entries_by_kind[kind] = new
        report['mods'][kind] = dict(details, sha256=digest(raw), bytes=len(raw), baseline_sha256=sha)
    # Cross-mod class ownership and dependency versions. Internal SFC core lives only in the merged SFC JAR.
    owners = {}; ids = {}
    for kind, entries in entries_by_kind.items():
        for name in entries:
            if name.endswith('.class'):
                require(name not in owners, 'Duplicate class across matched mods: ' + name); owners[name] = kind
        for mod in tomllib.loads(entries[META].decode())['mods']:
            require(mod['modId'] not in ids, 'Duplicate mod ID'); ids[mod['modId']] = mod['version']
    require(ids == VERSIONS, 'Matched mod set identity')
    report['mod_ids'] = ids; report['duplicate_classes'] = 0
    for kind, project, minimum, skipped in [('fc', 'piq-fc-arcade', 1416, 7), ('native', 'piq-native-arcade', 80, 0), ('sfc_home', 'piq-sfc-home', 311, 0)]:
        counts = {k: 0 for k in ['tests', 'failures', 'errors', 'skipped']}
        files = list((ROOT / project / 'build/test-results/test').glob('TEST-*.xml')); require(files, 'Missing JUnit evidence')
        for file in files:
            xml = ET.fromstring(file.read_bytes()); report['test_xml'][file.relative_to(ROOT).as_posix()] = digest(file.read_bytes())
            for name in counts: counts[name] += int(xml.attrib[name])
        require(counts['tests'] >= minimum and counts['failures'] == counts['errors'] == 0 and counts['skipped'] == skipped,
                'Regression counts ' + kind + ': ' + str(counts))
        report['tests'][kind] = counts
    require(before == inputs(), 'Sources changed during freeze')
    out.mkdir(parents=True)
    for name, raw in staged.items():
        with (out / name).open('xb') as f: f.write(raw)
        require(load(out / name)[0] == digest(raw), 'Frozen readback mismatch')
    for project, log in logs.items():
        with (out / (project + '-check.log')).open('x', encoding='utf-8') as f: f.write(log)
    with (out / 'build-witness.json').open('x', encoding='utf-8') as f: json.dump(report, f, ensure_ascii=False, indent=2)
    print(json.dumps({'ok': True, 'output': str(out), 'mods': report['mods'], 'tests': report['tests']}, ensure_ascii=False))


if __name__ == '__main__': main()
