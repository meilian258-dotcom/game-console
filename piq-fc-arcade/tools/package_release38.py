"""Preflight / exclusively write the local FC38 matched TEST delivery.

Default is read-only. --write is explicit and never overwrites an old artifact.
No Gradle, compiler, native core, game, installer, network or publishing is run.
Historical scripts remain source material, not a reproducible-build guarantee.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import sys
import zipfile

import build_release38 as b

ROOT = b.ROOT
NAME = '发行资源清理-FC38-配套测试包-20260914-v1'
TARGET = ROOT / '制作Mod/03-街机模拟'
PACK = ROOT / 'piq-fc-arcade/build/runtime-pack37-v1/piq-runtime-pack-v1.zip'
PACK_SHA = '681FDAB15CCF7BD3739B74598D1724E637415E6EB60C505DEEF0EFDAED0617AA'
PACK_BYTES = 127026327
PROVENANCE = ROOT / 'outputs/runtime37/provenance-existing-v1.json'
EXCLUDED_PARTS = {'.git', '.gradle', '.toolchains', 'target', '__pycache__',
                  'node_modules', 'build', 'design', 'quarantine'}
USER_SUFFIXES = {'.nes', '.sfc', '.smc', '.gba', '.gb', '.gbc', '.bios', '.rom',
                 '.sav', '.srm', '.state', '.bbmodel'}
LEGACY_ASSETS = {'legacy_generic_machine.obj', 'legacy_generic_machine.mtl',
                 'legacy_generic_machine.png'}
TOOL_SUFFIXES = {'.py', '.java', '.md', '.ps1', '.bat', '.json'}
CONTROLLERS = tuple('assets/piq_fc_arcade/textures/block/famicom_controller_%d.png' % n
                    for n in (1, 2))


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def member(name):
    b.entry_path(name)
    b.require(not name.endswith('/'), 'Only regular archive members: ' + name)
    return name


def regular(path):
    path = b.safe_path(path, True)
    b.require(path.is_relative_to(ROOT), 'Input must stay in this workspace: ' + str(path))
    b.require(stat.S_ISREG(path.stat().st_mode), 'Regular input required: ' + str(path))
    return path


def stream_sha(stream):
    h = hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        h.update(chunk)
    return h.hexdigest().upper()


def inventory_zip(path, pins):
    with zipfile.ZipFile(regular(path)) as z:
        infos = z.infolist()
        b.require(len(infos) == len(pins) and {x.filename for x in infos} == set(pins),
                  'Unexpected runtime ZIP member set')
        for info in infos:
            member(info.filename)
            b.require(not info.is_dir() and not info.flag_bits & 1, 'Invalid runtime ZIP entry')
            pin = pins[info.filename]
            b.require(info.file_size == pin['bytes'], 'Runtime size: ' + info.filename)
            with z.open(info) as f:
                b.require(stream_sha(f) == pin['sha256'].upper(), 'Runtime SHA: ' + info.filename)


class Inputs:
    """Fence paths at collection and verify the actual bytes written to each ZIP."""
    def __init__(self):
        self.paths = {}

    def remember(self, path):
        path = regular(path)
        value = dict(bytes=path.stat().st_size, sha256=b.file_sha(path))
        if path in self.paths:
            b.require(self.paths[path] == value, 'Input changed: ' + str(path))
        self.paths[path] = value
        return path

    def add(self, entries, name, source):
        member(name)
        b.require(name.casefold() not in {n.casefold() for n in entries}, 'Duplicate ZIP member: ' + name)
        entries[name] = source if isinstance(source, bytes) else self.remember(source)

    def pin(self, source):
        return dict(bytes=len(source), sha256=b.digest(source)) if isinstance(source, bytes) else self.paths[source]

    def unchanged(self):
        for path, expected in self.paths.items():
            regular(path)
            b.require(path.stat().st_size == expected['bytes'] and b.file_sha(path) == expected['sha256'],
                      'Input changed during packaging: ' + str(path))


def source_allowed(name):
    p = Path(name)
    b.require(p.suffix.lower() not in USER_SUFFIXES, 'Game/model/save payload rejected: ' + name)
    b.require(not any(part.casefold() in {'roms', 'bios', 'saves', 'quarantine', 'design'} for part in p.parts),
              'Private/game directory rejected: ' + name)
    b.require(p.name not in LEGACY_ASSETS and 'uv-context-before-after' not in p.name,
              'Removed or before-image asset rejected: ' + name)


def final_reports(a, inputs):
    stage = b.safe_path(a.stage)
    b.require(stage == ROOT / 'piq-fc-arcade/build/review-release38-v1', 'Only the reviewed FC38 stage')
    raw = inputs.remember(stage / 'build-witness.json').read_bytes()
    witness = json.loads(raw)
    b.require(witness.get('schema') == 'piq-release38-build-1' and witness.get('ok') is True
              and witness.get('mode') == 'resource-only-freeze'
              and witness.get('production_compiled') is False
              and witness.get('compiled_outputs_used') is False, 'Complete resource-only build witness required')
    plan_path = inputs.remember(stage / 'approved-plan.json')
    plan, plan_raw = b.load_plan(plan_path, witness['plan_sha256'])
    b.require(b.source_snapshot(plan) == witness['source_fence'], 'Source fence drift')
    checks = {}
    for label, path, schema in [('final-audit', a.audit, 'piq-release38-final-1'),
                               ('licenses', a.licenses, 'piq-release38-licenses-1'),
                               ('removed-assets', a.removed_assets, 'piq-release38-removed-assets-v1')]:
        data = inputs.remember(path).read_bytes()
        report = json.loads(data)
        b.require(report.get('schema') == schema and report.get('ok') is True, 'Failed or wrong ' + label)
        fc_sha = report.get('artifact', {}).get('sha256') if label == 'removed-assets' else report.get('sha256')
        b.require(isinstance(fc_sha, str) and fc_sha.upper() == witness['mods']['fc']['sha256'],
                  'Evidence is not for the supplied final FC JAR: ' + label)
        if label != 'removed-assets':
            b.require(report.get('mode') == 'final-jar-only' and report.get('production_compiled') is False,
                      'Final-only report required: ' + label)
        else:
            b.require(report.get('checks') and all(x.get('ok') is True for x in report['checks']),
                      'Removed-assets checks failed')
        checks[label] = (data, report)
    audit = checks['final-audit'][1]
    b.require(audit['build_witness_sha256'] == b.digest(raw)
              and audit['approved_plan_sha256'] == b.digest(plan_raw), 'Audit witness/plan identity mismatch')
    b.require(set(audit['jars']) == set(b.NAMES), 'All four final archives must be audited')
    for kind, name in b.NAMES.items():
        path = inputs.remember(stage / name)
        expected = witness['mods'][kind]
        b.require(inputs.pin(path)['sha256'] == expected['sha256'] == audit['jars'][kind]['sha256']
                  and path.stat().st_size == expected['bytes'] == audit['jars'][kind]['bytes'],
                  'Final archive identity mismatch: ' + kind)
    b.require(witness['mods']['native']['sha256'] == b.BASE['native'][1], 'Native13 must remain byte-identical')
    return stage, witness, plan, checks


def collect(a):
    inputs = Inputs()
    stage, witness, plan, reports = final_reports(a, inputs)
    test, source, overrides, omitted = {}, {}, [], []
    add = inputs.add
    for kind, name in b.NAMES.items():
        add(test, 'mods/' + name, stage / name)
    inv_raw = inputs.remember(PROVENANCE).read_bytes()
    inv = json.loads(inv_raw)
    b.require(inv.get('schema') == 'piq-runtime37-existing-provenance-1' and inv.get('ok') is True,
              'Runtime provenance missing')
    pack = inputs.remember(PACK)
    b.require(inputs.pin(pack) == dict(bytes=PACK_BYTES, sha256=PACK_SHA), 'The original offline pack changed')
    b.require(len(inv['runtime_files']) == 9, 'Nine pinned runtime files required')
    inventory_zip(pack, inv['runtime_files'])
    runtime_witness = inputs.remember(PACK.parent / 'runtime-pack-witness.json')
    rw = json.loads(runtime_witness.read_bytes())
    b.require(rw.get('schema') == 'piq-runtime37-offline-pack-1' and rw.get('ok') is True
              and rw['pack']['sha256'] == PACK_SHA and rw['pack']['bytes'] == PACK_BYTES
              and rw['provenance_sha256'] == b.digest(inv_raw), 'Runtime witness/provenance mismatch')
    add(test, 'piq-runtime-packs/piq-runtime-pack-v1.zip', pack)
    for entries in (test, source):
        add(entries, '安装说明-FC38.md', a.guide)
        add(entries, '发行清理说明-FC38.md', a.notes)
        add(entries, 'checks/build-witness.json', stage / 'build-witness.json')
        add(entries, 'checks/approved-plan.json', stage / 'approved-plan.json')
        add(entries, 'checks/runtime-pack-witness.json', runtime_witness)
        add(entries, 'checks/runtime-provenance-existing-v1.json', inv_raw)
        for label, (raw, _) in reports.items():
            add(entries, 'checks/release38-' + label + '.json', raw)
    for p, sha in inv['license_notices'].items():
        path = inputs.remember(Path(p))
        b.require(inputs.pin(path)['sha256'] == sha, 'Runtime license source changed: ' + str(path))
        name = 'licenses/runtime/' + ('mgba/' if 'mgba-' in path.name else 'native/') + path.name
        for entries in (test, source):
            add(entries, name, path)
    # These notices are exact bytes from the checked final FC archive, not a draft.
    with zipfile.ZipFile(stage / b.NAMES['fc']) as fc:
        for name in fc.namelist():
            if name.startswith('META-INF/licenses/') and not name.endswith('/'):
                for entries in (test, source):
                    add(entries, 'licenses/fc/' + name.removeprefix('META-INF/licenses/'), fc.read(name))
        for entries in (test, source):
            add(entries, 'licenses/fc/THIRD_PARTY_NOTICES.md', fc.read('THIRD_PARTY_NOTICES.md'))
        final_controllers = {name: fc.read(name) for name in CONTROLLERS}
    for project in b.PROJECTS:
        base = ROOT / project
        for sub in ('src', 'gradle', 'native', 'tools', 'helper/src', 'docs/licenses'):
            folder = base / sub
            if not folder.exists():
                continue
            for current, dirs, files in os.walk(b.safe_path(folder), followlinks=False):
                dirs[:] = sorted(d for d in dirs if d not in EXCLUDED_PARTS)
                for d in dirs:
                    b.safe_path(Path(current) / d)
                for filename in sorted(files):
                    path = Path(current) / filename
                    if sub == 'tools' and path.suffix.lower() not in TOOL_SUFFIXES:
                        continue
                    rel = path.relative_to(base).as_posix()
                    name = 'source/' + project + '/' + rel
                    source_allowed(name)
                    if project == 'piq-fc-arcade' and rel.removeprefix('src/main/resources/') in final_controllers:
                        resource = rel.removeprefix('src/main/resources/')
                        original = inputs.remember(path)
                        data = final_controllers[resource]
                        add(source, name, data)
                        overrides.append(dict(member=name, workspace_sha256=inputs.pin(original)['sha256'],
                                              release_sha256=b.digest(data), source='final FC38 JAR',
                                              workspace_modified=False))
                    else:
                        add(source, name, path)
        for filename in ('build.gradle', 'settings.gradle', 'gradle.properties', 'gradlew', 'gradlew.bat',
                         'LICENSE', 'README.md', 'THIRD_PARTY_NOTICES.md', 'ARCADEMOD_ASSET_NOTICE.md'):
            path = base / filename
            if path.is_file():
                add(source, 'source/' + project + '/' + filename, path)
                if filename == 'LICENSE':
                    add(test, 'licenses/' + project + '/LICENSE', path)
    b.require(len(overrides) == 2, 'Both original controller resource paths must use final PNGs')
    add(source, 'source/tools/check_release38_removed_assets.py', ROOT / 'tools/check_release38_removed_assets.py')
    # Retain the complete native-core/helper corresponding source and its records,
    # but not an unrelated historical FC mod copy that reintroduces removed art.
    native = b.safe_path(Path(inv['source_delivery_reuse']['neogeo_modified'].split(' (retain')[0]))
    gba = ROOT / 'piq-gba/build/handheld-v3-3/licenses-and-source'
    for label, folder in (('native-corresponding-source', native), ('gba-corresponding-source', gba)):
        for path in sorted(folder.rglob('*')):
            if not path.is_file():
                continue
            rel = path.relative_to(folder).as_posix()
            if (label == 'native-corresponding-source' and rel.startswith('source/piq-fc-arcade/')) \
                    or (label == 'gba-corresponding-source' and rel == 'piq-gba-source.zip'):
                omitted.append(dict(path=label + '/' + rel, sha256=inputs.pin(inputs.remember(path))['sha256'],
                                    reason='Redundant historical mod/art; current module source included separately'))
                continue
            source_allowed(rel)
            add(source, label + '/' + rel, path)
    # Historical GBA helper correspondence is preserved member-for-member without
    # carrying its old gameplay textures or rewriting the original source ZIP.
    old_gba = inputs.remember(gba / 'piq-gba-source.zip')
    with zipfile.ZipFile(old_gba) as z:
        for rel, pin in inv['gba_helper_sources_same_as_successful_build_and_delivered_source'].items():
            data = z.read(rel)
            b.require(b.digest(data) == pin and b.file_sha(ROOT / 'piq-gba' / rel) == pin,
                      'GBA corresponding helper source changed: ' + rel)
            add(source, 'gba-corresponding-source/helper-source/' + rel, data)
    for rel, pin in inv['native_helper_sources_same_as_delivered_fc33_source'].items():
        b.require(b.file_sha(native / 'source/piq-native-arcade' / rel) == pin,
                  'Native helper correspondence changed: ' + rel)
    for pin in list(inv['source_archives'].values()) + inv['snapshot_final_five_patches']:
        path = inputs.remember(Path(pin['path']))
        b.require(inputs.pin(path) == dict(bytes=pin['bytes'], sha256=pin['sha256']), 'Corresponding source pin changed')
    record = dict(schema='piq-release38-source-selection-1', controller_overrides=overrides,
                  omitted_redundant_historical_mod_files=omitted,
                  original_runtime_pack_sha256=PACK_SHA,
                  limits=['No ROM, BIOS, save, design archive, quarantine or before-preview is selected.',
                          'Pinned upstream source archives are retained verbatim; source names/strings are not ROM payloads.',
                          'Historical tools may require private baselines/toolchains not included here.',
                          'Complete transitive Rust/native attribution for prebuilt Wasmtime is not established.',
                          'This TEST package is not a full legal or reproducible-build clearance.'])
    for entries in (test, source):
        add(entries, 'checks/source-selection.json', encoded(record))
    inputs.unchanged()
    b.require(b.source_snapshot(plan) == witness['source_fence'], 'Source changed during preflight')
    return inputs, test, source, witness, plan, record


def archive(path, entries, inputs):
    path = b.safe_path(path)
    b.require(path.parent == TARGET and not path.exists(), 'Never overwrite or redirect a delivery: ' + str(path))
    with zipfile.ZipFile(path, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=6, allowZip64=True) as z:
        for name, source in sorted(entries.items()):
            member(name)
            info = zipfile.ZipInfo(name, (2026, 9, 14, 0, 0, 0))
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_STORED if name.endswith(('.zip', '.tar.gz', '.jar')) else zipfile.ZIP_DEFLATED
            if isinstance(source, bytes):
                z.writestr(info, source)
            else:
                regular(source)
                with source.open('rb') as src, z.open(info, 'w') as dest:
                    shutil.copyfileobj(src, dest, 1024 * 1024)
    with zipfile.ZipFile(path) as z:
        b.require(len(z.namelist()) == len(entries) and set(z.namelist()) == set(entries), 'ZIP member set changed')
        for name, source in entries.items():
            expected = inputs.pin(source)
            b.require(z.getinfo(name).file_size == expected['bytes'], 'Packed size changed: ' + name)
            with z.open(name) as f:
                b.require(stream_sha(f) == expected['sha256'], 'Packed bytes changed: ' + name)
    return dict(path=str(path), bytes=path.stat().st_size, sha256=b.file_sha(path), entries=len(entries))


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--stage', type=Path, required=True)
    p.add_argument('--audit', type=Path, required=True)
    p.add_argument('--licenses', type=Path, required=True)
    p.add_argument('--removed-assets', type=Path, required=True)
    p.add_argument('--guide', type=Path, default=ROOT / 'outputs/release38/安装说明-FC38.md')
    p.add_argument('--notes', type=Path, default=ROOT / 'outputs/release38/发行清理说明-FC38.md')
    p.add_argument('--write', action='store_true', help='Explicitly create the two new ZIPs and receipt after preflight')
    a = p.parse_args()
    targets = [TARGET / (NAME + suffix) for suffix in ('.zip', '-源码.zip', '.verification.json')]
    b.require(b.safe_path(TARGET).is_dir(), 'Existing intended delivery directory required')
    for path in targets:
        b.require(not b.safe_path(path).exists(), 'Never overwrite prior delivery: ' + str(path))
    inputs, test, source, witness, plan, selection = collect(a)
    result = dict(schema='piq-release38-delivery-1', ok=True,
                  mode='exclusive-new-archives' if a.write else 'read-only-preflight',
                  mods=witness['mods'], runtime_pack_sha256=PACK_SHA,
                  input_files=len(inputs.paths), test_entries=len(test), source_entries=len(source),
                  controller_overrides=selection['controller_overrides'], limits=selection['limits'],
                  installed=False, published=False, minecraft_started=False, native_core_started=False,
                  production_compiled=False, shutdown_scheduled=False)
    if a.write:
        # Failure leaves only newly-created artifacts for inspection. Never delete
        # or overwrite a file after an error; no success receipt is issued.
        result['source_package'] = archive(targets[1], source, inputs)
        result['test_package'] = archive(targets[0], test, inputs)
        inputs.unchanged()
        b.require(b.source_snapshot(plan) == witness['source_fence'], 'Final source fence changed')
        with targets[2].open('xb') as out:
            out.write(encoded(result))
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
