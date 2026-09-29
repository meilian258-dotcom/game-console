"""Freeze FC35 shared-game reuse; all unrelated classes/assets and add-ons stay at FC34 baseline."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET

from build_audit34 import inputs, ROOT, JAVA
from freeze_fc_core_alpha19 import read_jar, safe_path, digest, require, META, MANIFEST
from freeze_fc_compact_alpha20 import RESTORE
from prepare_cabinet_multiplayer21 import clean, jar_bytes

BASE = ROOT / 'piq-fc-arcade/build/review-audit34-v1'
NAME = 'piq_fc_arcade-0.31.0-alpha.35.jar'
OLD = 'piq_fc_arcade-0.31.0-alpha.34.jar'
PINS = {
    'fc': (OLD, '2C3462F6A971D52A15C835EB43A20E1F5075174E872AE0758707C6451E0DE63E'),
    'native': ('piq_native_arcade-0.1.0-alpha.12.jar', 'DC67BB2705EC9C8724F0300CC6CC1361D2C8FA93428974DDDDE858CF59F26867'),
    'sfc': ('piq_sfc-0.1.0-alpha.20.jar', '9A131723FCF5DF3FF2E807B345EE29C9E93BDA3D209F1BD3DED6681144C17004'),
    'gba': ('piq_gba-0.1.0-alpha.4.jar', '0D51438C93E0D365B5A19A86B22B7AC94A4316EA9D3F5A3C3B3E13311322EEA1')}
ALLOWED = {'cn/piq/fcarcade/' + n for n in (
    'cabinet/CabinetGameNetwork', 'cabinet/CabinetSharedGameService', 'cabinet/CabinetGameUploadPlan',
    'client/cabinet/CabinetSharedGames', 'client/cabinet/CabinetGameClientPlan')}


def main():
    sys.stdout.reconfigure(encoding='utf-8'); sys.stderr.reconfigure(encoding='utf-8')
    p = argparse.ArgumentParser(description=__doc__); p.add_argument('--output', type=Path, required=True); a = p.parse_args()
    out = safe_path(a.output)
    require(out.is_relative_to(ROOT / 'piq-fc-arcade/build') and not out.exists(), 'New build stage required')
    before = inputs(); pinned = {}
    for kind, (name, pin) in PINS.items():
        actual, _, entries = read_jar(BASE / name); require(actual == pin, 'Frozen input ' + kind)
        pinned[kind] = clean(entries)
    result = subprocess.run(['cmd.exe', '/d', '/c', 'gradlew.bat', 'check', 'jar', '--offline'],
        cwd=ROOT / 'piq-fc-arcade', env=dict(os.environ, JAVA_HOME=str(JAVA)),
        capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=600)
    log = result.stdout + '\n' + result.stderr; print(log[-9000:], flush=True)
    require(result.returncode == 0, 'FC35 check/jar failed')
    require(before == inputs(), 'Sources changed during build; retry stable sources')
    compiled, _, entries = read_jar(ROOT / 'piq-fc-arcade/build/libs' / NAME); new = clean(entries); old = pinned['fc']
    for name, (source_pin, frozen_pin) in RESTORE.items():
        require(digest(old[name]) == frozen_pin and digest(new[name]) in (source_pin, frozen_pin), 'Historical controller asset changed')
        new[name] = old[name]
    removed = sorted(set(old) - set(new)); require(not removed, 'Unexpected removed entries')
    changed = sorted(n for n in old if old[n] != new[n]); added = sorted(set(new) - set(old))
    for name in changed + added:
        require(name in (META, MANIFEST) or name.endswith('.class') and name[:-6].split('$')[0] in ALLOWED,
                'Unreviewed class/resource change ' + name)
    expected = tomllib.loads(old[META].decode()); expected['mods'][0]['version'] = '0.31.0-alpha.35'
    require(tomllib.loads(new[META].decode()) == expected, 'Unexpected metadata changes')
    require(new[MANIFEST] == old[MANIFEST].replace(b'0.31.0-alpha.34', b'0.31.0-alpha.35'), 'Unexpected manifest')
    protected = [n for n in old if n.startswith(('assets/', 'data/', 'core/'))]
    require(all(new[n] == old[n] for n in protected), 'Original model/core/data drift')
    counts = dict(tests=0, failures=0, errors=0, skipped=0); xml_pins = {}
    for path in sorted((ROOT / 'piq-fc-arcade/build/test-results/test').glob('TEST-*.xml')):
        raw = path.read_bytes(); xml = ET.fromstring(raw); xml_pins[path.relative_to(ROOT).as_posix()] = digest(raw)
        for k in counts: counts[k] += int(xml.attrib[k])
    require(counts['tests'] >= 1455 and counts['failures'] == counts['errors'] == 0 and counts['skipped'] == 7,
            'Regression failure ' + str(counts))
    pinned['fc'] = new; owners = {}; ids = {}
    for kind, jar in pinned.items():
        for name in jar:
            if name.endswith('.class'):
                require(name not in owners, 'Duplicate class ' + name); owners[name] = kind
        for mod in tomllib.loads(jar[META].decode())['mods']:
            require(mod['modId'] not in ids, 'Duplicate mod id'); ids[mod['modId']] = mod['version']
    raw = jar_bytes(new)
    report = {'schema': 'piq-reuse35-build-1', 'ok': True, 'inputs': before, 'compiled_sha256': compiled,
        'fc': {'name': NAME, 'sha256': digest(raw), 'bytes': len(raw)}, 'baseline_sha256': PINS['fc'][1],
        'unchanged_addons': {k: {'name': n, 'sha256': sha} for k, (n, sha) in PINS.items() if k != 'fc'},
        'changed': changed, 'added': added, 'removed': removed, 'protected_unchanged': len(protected),
        'mod_ids': ids, 'duplicate_classes': 0, 'tests': counts, 'test_xml': xml_pins,
        'installed': False, 'published': False, 'minecraft_started': False, 'real_network_tested': False}
    require(before == inputs(), 'Source fence changed before freeze'); out.mkdir(parents=True)
    with (out / NAME).open('xb') as f: f.write(raw)
    require(read_jar(out / NAME)[0] == report['fc']['sha256'], 'Freeze readback')
    with (out / 'build-witness.json').open('x', encoding='utf-8') as f: json.dump(report, f, ensure_ascii=False, indent=2)
    with (out / 'fc-check.log').open('x', encoding='utf-8') as f: f.write(log)
    print(json.dumps({k: report[k] for k in ('ok', 'fc', 'tests', 'changed', 'added', 'protected_unchanged')}, ensure_ascii=False))


if __name__ == '__main__': main()
