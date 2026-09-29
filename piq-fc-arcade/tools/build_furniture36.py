"""Build a new FC36 furniture candidate, preserving FC35 gameplay and skin resources."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET

from build_audit34 import inputs, ROOT, JAVA
from build_reuse35 import BASE as ADDON_BASE, PINS
from freeze_fc_core_alpha19 import read_jar, safe_path, digest, require, META, MANIFEST
from freeze_fc_compact_alpha20 import RESTORE
from prepare_cabinet_multiplayer21 import clean, jar_bytes

BASE = ROOT / 'piq-fc-arcade/build/review-reuse35-v1/piq_fc_arcade-0.31.0-alpha.35.jar'
BASE_SHA = '18FAE484A46B38A4660353E7CAA5BE046B369E4D3657426F606DC664CF92B65C'
NAME = 'piq_fc_arcade-0.31.0-alpha.36.jar'
LANG = {'assets/piq_fc_arcade/lang/zh_cn.json', 'assets/piq_fc_arcade/lang/en_us.json'}


def main():
    sys.stdout.reconfigure(encoding='utf-8'); sys.stderr.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args(); out = safe_path(args.output)
    require(out.is_relative_to(ROOT / 'piq-fc-arcade/build') and not out.exists(), 'New build stage required')
    before = inputs(); pin, _, old = read_jar(BASE); old = clean(old)
    require(pin == BASE_SHA, 'FC35 baseline changed')
    result = subprocess.run(['cmd.exe', '/d', '/c', 'gradlew.bat', 'check', 'jar', '--offline'],
        cwd=ROOT / 'piq-fc-arcade', env=dict(os.environ, JAVA_HOME=str(JAVA)), capture_output=True,
        text=True, encoding='utf-8', errors='replace', timeout=900)
    log = result.stdout + '\n' + result.stderr; print(log[-12000:], flush=True)
    require(result.returncode == 0, 'FC36 check/jar failed')
    require(before == inputs(), 'Sources changed during build; repeat after stable handoff')
    compiled, _, new = read_jar(ROOT / 'piq-fc-arcade/build/libs' / NAME); new = clean(new)
    for name, (source_sha, release_sha) in RESTORE.items():
        require(digest(old[name]) == release_sha and digest(new[name]) in (source_sha, release_sha), 'Historical controller asset changed')
        new[name] = old[name]
    removed = sorted(set(old) - set(new)); require(not removed, 'Deleted historical entries')
    changed = sorted(n for n in old if new[n] != old[n]); added = sorted(set(new) - set(old))
    for name in changed:
        require(name in (META, MANIFEST, 'cn/piq/fcarcade/FcArcadeMod.class') or name in LANG,
                'Old gameplay/skin class or resource changed: ' + name)
    for name in LANG & set(changed):
        previous, current = json.loads(old[name]), json.loads(new[name])
        require(all(current.get(k) == v for k, v in previous.items()), 'Historical language key changed: ' + name)
        require(all('furniture' in k or 'bench' in k or 'stool' in k for k in current.keys() - previous.keys()), 'Unrelated new language key')
    for name in added:
        if name.endswith('.class'):
            require(name.startswith('cn/piq/fcarcade/furniture/'), 'New non-furniture class: ' + name)
        else:
            require(name.startswith(('assets/piq_fc_arcade/', 'data/piq_fc_arcade/', 'data/minecraft/tags/'))
                    and any(token in name for token in ('furniture', 'bench', 'stool')), 'Unexpected new resource: ' + name)
    expected = tomllib.loads(old[META].decode()); expected['mods'][0]['version'] = '0.31.0-alpha.36'
    require(tomllib.loads(new[META].decode()) == expected, 'Unexpected metadata change')
    require(new[MANIFEST] == old[MANIFEST].replace(b'0.31.0-alpha.35', b'0.31.0-alpha.36'), 'Unexpected JAR manifest')
    protected = [n for n in old if n.startswith(('assets/', 'data/', 'core/')) and n not in LANG]
    require(all(old[n] == new[n] for n in protected), 'Old model, skin, recipe or native core changed')
    counts = dict(tests=0, failures=0, errors=0, skipped=0); test_xml = {}
    for path in sorted((ROOT / 'piq-fc-arcade/build/test-results/test').glob('TEST-*.xml')):
        raw = path.read_bytes(); suite = ET.fromstring(raw); test_xml[path.relative_to(ROOT).as_posix()] = digest(raw)
        for key in counts: counts[key] += int(suite.attrib[key])
    require(counts['tests'] >= 1492 and counts['failures'] == counts['errors'] == 0 and counts['skipped'] == 7,
            'Regression failures/new skips: ' + str(counts))
    owners = {}; mod_ids = {}; unchanged = {}; archives = {'fc': new}
    for kind, (name, sha) in PINS.items():
        if kind == 'fc': continue
        actual, _, entries = read_jar(ADDON_BASE / name); require(actual == sha, 'Existing addon changed: ' + kind)
        archives[kind] = clean(entries); unchanged[kind] = {'name': name, 'sha256': sha}
    for kind, entries in archives.items():
        for name in entries:
            if name.endswith('.class'):
                require(name not in owners, 'Duplicate class: ' + name); owners[name] = kind
        for mod in tomllib.loads(entries[META].decode())['mods']:
            require(mod['modId'] not in mod_ids, 'Duplicate mod ID'); mod_ids[mod['modId']] = mod['version']
    raw = jar_bytes(new)
    witness = {'schema': 'piq-furniture36-build-1', 'ok': True, 'inputs': before,
        'fc': {'name': NAME, 'sha256': digest(raw), 'bytes': len(raw)}, 'compiled_sha256': compiled,
        'baseline_sha256': BASE_SHA, 'unchanged_addons': unchanged, 'changed': changed, 'added': added,
        'removed': removed, 'protected_unchanged': len(protected), 'mod_ids': mod_ids, 'duplicate_classes': 0,
        'tests': counts, 'test_xml': test_xml, 'installed': False, 'published': False,
        'minecraft_started': False, 'real_network_tested': False}
    require(before == inputs(), 'Input fence changed before freeze'); out.mkdir(parents=True)
    with (out / NAME).open('xb') as stream: stream.write(raw)
    require(read_jar(out / NAME)[0] == witness['fc']['sha256'], 'Final archive readback')
    with (out / 'build-witness.json').open('x', encoding='utf-8') as stream: json.dump(witness, stream, ensure_ascii=False, indent=2)
    with (out / 'fc-check.log').open('x', encoding='utf-8') as stream: stream.write(log)
    print(json.dumps({k: witness[k] for k in ('ok', 'fc', 'tests', 'changed', 'added', 'protected_unchanged')}, ensure_ascii=False))


if __name__ == '__main__': main()
