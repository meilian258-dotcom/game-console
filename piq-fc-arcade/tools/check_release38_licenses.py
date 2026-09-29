"""Read-only final-JAR attribution check; writes only a new optional QA report."""
import argparse
import hashlib
import json
from pathlib import Path
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = '46.0.1-1.2.0'
PINS = {
    'META-INF/licenses/nes-rust-LICENSE.txt': '7D368C9793C495BD52DBE075D792C004A1AD3C073A1BE6D901FC138E32B888FA',
    'META-INF/licenses/wasmtime4j-LICENSE.txt': 'FA99513714F03ECD011265DA8A31C0D2B331A9D95B0F0AEF2F9C8543CE028346',
    'META-INF/licenses/wasmtime-LICENSE.txt': '268872B9816F90FD8E85DB5A28D33F8150EBB8DD016653FB39EF1F94F2686BC5',
}
PROVENANCE = 'META-INF/licenses/wasmtime-provenance.json'
NOTICES = 'THIRD_PARTY_NOTICES.md'


def sha(value):
    return hashlib.sha256(value).hexdigest().upper()


def require(condition, detail):
    if not condition:
        raise AssertionError(detail)


def check_documents(entries):
    for name, expected in PINS.items():
        require(name in entries, 'Missing full license: ' + name)
        require(sha(entries[name]) == expected, 'Changed/truncated upstream license: ' + name)
    require(NOTICES in entries and PROVENANCE in entries, 'Attribution and provenance must be in the JAR')
    notices = entries[NOTICES].decode('utf-8')
    require('Copyright 2025 Tegmentum AI' in notices and 'The Wasmtime Project Developers' in notices, 'Upstream ownership')
    require('flat merge, not NeoForge Jar-in-Jar' in notices, 'Actual packaging description')
    require('Apache-2.0 WITH LLVM-exception' in notices and 'not an exhaustive' in notices, 'Exception and audit boundary')
    provenance = json.loads(entries[PROVENANCE])
    require(provenance['schema'] == 'piq-fc38-wasmtime-attribution-1', 'Provenance schema')
    require(provenance['projects'][0]['version'] == VERSION, 'Wasmtime4j pinned version')
    require(provenance['projects'][1]['version'] == '46.0.1', 'Wasmtime pinned version')
    for project in provenance['projects']:
        require(project['license_sha256'] == PINS[project['license_path']], 'Source pin identity')
        require(project['license_bytes'] == len(entries[project['license_path']]), 'Exact source bytes')
        require(project['commit'] in project['license_url'], 'Immutable upstream URL')
        require(project['notice_files_in_checked_tree'] == [], 'No fabricated upstream NOTICE')
    return provenance


def self_test(entries):
    cases = []
    for name in PINS:
        missing = dict(entries); del missing[name]; cases.append(missing)
    truncated = dict(entries); truncated['META-INF/licenses/wasmtime-LICENSE.txt'] = entries['META-INF/licenses/wasmtime-LICENSE.txt'].split(b'--- LLVM Exceptions')[0]; cases.append(truncated)
    incorrect = dict(entries); p = json.loads(entries[PROVENANCE]); p['projects'][0]['version'] = '0.0.0'; incorrect[PROVENANCE] = json.dumps(p).encode(); cases.append(incorrect)
    hidden = dict(entries); del hidden[NOTICES]; cases.append(hidden)
    for index, altered in enumerate(cases):
        try:
            check_documents(altered)
        except (AssertionError, KeyError):
            continue
        raise AssertionError('Negative fixture incorrectly accepted: ' + str(index))
    return len(cases)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--jar', type=Path, required=True)
    p.add_argument('--baseline', type=Path, required=True, help='Frozen FC37, used only for unchanged vendor byte comparison')
    p.add_argument('--report', type=Path)
    a = p.parse_args(); jar = a.jar.resolve(strict=True); baseline = a.baseline.resolve(strict=True)
    report = a.report.resolve() if a.report else None
    require(not report or not report.exists(), 'New report path required')
    before = sha(jar.read_bytes()); old_hash = sha(baseline.read_bytes())
    require(old_hash == '809A4FEE17B8805C642C4DF1A0272B5C369D02B179A98E41253262E268ACD406', 'Exact FC37 baseline required')
    with zipfile.ZipFile(jar) as z, zipfile.ZipFile(baseline) as old:
        require(len(z.namelist()) == len(set(z.namelist())), 'No duplicate JAR entries')
        names = [*PINS, NOTICES, PROVENANCE]
        entries = {name: z.read(name) for name in names}
        provenance = check_documents(entries)
        negative = self_test(entries)
        for name in names:
            source = ROOT / name if name == NOTICES else ROOT / 'src/main/resources' / name
            require(entries[name] == source.read_bytes(), 'Packaged document differs from source: ' + name)
        vendor = [name for name in old.namelist() if not name.endswith('/') and (name.startswith('ai/tegmentum/') or name.startswith('natives/') or name.startswith('META-INF/maven/ai.tegmentum/') or name in PINS)]
        require(len(vendor) > 100, 'Meaningful vendor comparison')
        for name in vendor:
            require(z.read(name) == old.read(name), 'Upstream executable/attribution changed: ' + name)
        for module in ['wasmtime4j', 'wasmtime4j-jni', 'wasmtime4j-native-loader']:
            prefix = 'META-INF/maven/ai.tegmentum/' + module + '/'
            require(('version=' + VERSION).encode() in z.read(prefix + 'pom.properties'), 'Maven version retained')
            require(b'Apache License, Version 2.0' in z.read(prefix + 'pom.xml'), 'Maven attribution retained')
    require(before == sha(jar.read_bytes()) and old_hash == sha(baseline.read_bytes()), 'Inputs changed during read-only QA')
    result = {'schema': 'piq-release38-licenses-1', 'ok': True, 'mode': 'final-jar-only', 'production_compiled': False,
              'jar': str(jar), 'sha256': before, 'baseline_sha256': old_hash, 'documents': {name: sha(data) for name, data in entries.items()},
              'unchanged_vendor_entries': len(vendor), 'negative_fixtures': negative,
              'limits': ['Full selected upstream license texts, attribution and unchanged vendor bytes are checked, not complete legal compliance.',
                         'No native execution; transitive native dependency SBOM is not established.'], 'native_library_loaded': False}
    if report:
        report.parent.mkdir(parents=True, exist_ok=True)
        with report.open('x', encoding='utf-8') as stream:
            json.dump(result, stream, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=False))


if __name__ == '__main__':
    main()
