"""Read-only preflight, or --write two exclusively NEW local brand40 test ZIPs.

Python 3.11+. No build, install, network, native execution or publication. Requires
the successful final freeze, not merely a stage directory. Historical source
archives are verified/referenced, never copied, rewritten or treated as new source.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[2]
STAGE = ROOT / 'piq-fc-arcade/build/review-brand40-v1'
GUIDE = ROOT / 'piq-fc-arcade/design/方块电玩-首次安装与更新说明-20260914.md'
NAME = '方块电玩-配套测试包-20260914-v1'
VERSIONS = {'piq_fc_arcade': '0.31.0-alpha.40', 'piq_native_arcade': '0.1.0-alpha.15',
            'piq_sfc_home': '0.1.0-alpha.24', 'piq_sfc_arcade': '0.2.0-alpha.8', 'piq_gba': '0.1.0-alpha.8'}
NAMES = {'fc': 'block_arcade-0.31.0-alpha.40.jar',
         'native': 'block_arcade_cabinets-0.1.0-alpha.15.jar',
         'sfc': 'block_arcade_sfc-0.1.0-alpha.24.jar', 'gba': 'block_arcade_gba-0.1.0-alpha.8.jar'}
OWNERS = {'fc': {'piq_fc_arcade'}, 'native': {'piq_native_arcade'},
          'sfc': {'piq_sfc_home', 'piq_sfc_arcade'}, 'gba': {'piq_gba'}}
PACK = ROOT / 'piq-fc-arcade/build/runtime-pack37-v1/piq-runtime-pack-v1.zip'
PACK_PIN = {'bytes': 127026327, 'sha256': '681FDAB15CCF7BD3739B74598D1724E637415E6EB60C505DEEF0EFDAED0617AA'}
LEGACY = ROOT / '制作Mod/03-街机模拟/发行资源清理-FC38-配套测试包-20260914-v1.zip'
LEGACY_PIN = {'bytes': 159505800, 'sha256': '8CACEDC34945E1D7AB77344B1AF132785869A2953B315D5AE722041B26051924'}
OLD_SOURCE = LEGACY.with_name(LEGACY.stem + '-源码.zip')
SOURCE_PIN = {'bytes': 259669517, 'sha256': 'DEBDE6268F14561EB4AA574A5698B2C1CA6FA1E42EAA24DDF4E9FBFC3FA0FEE6'}
LICENSE_NAMES = set('''licenses/fc/THIRD_PARTY_NOTICES.md
licenses/fc/nes-rust-LICENSE.txt
licenses/fc/wasmtime-LICENSE.txt
licenses/fc/wasmtime-provenance.json
licenses/fc/wasmtime4j-LICENSE.txt
licenses/piq-native-arcade/LICENSE
licenses/piq-sfc-arcade/LICENSE
licenses/piq-sfc-home/LICENSE
licenses/runtime/mgba/mgba-LICENSE
licenses/runtime/native/COPYING
licenses/runtime/native/JNA-Apache-2.0.txt
licenses/runtime/native/JNA-LICENSE.txt
licenses/runtime/native/LICENSE
licenses/runtime/native/MAME-GPL-2.0.txt'''.splitlines())
LIMITS = [
    'Local matched TEST files only; not installed, published, or multiplayer/native-runtime acceptance.',
    'The old source ZIP is referenced by exact path/SHA, not included and not an installation JAR.',
    'Old FC38 module source is NOT corresponding source for new FC40/Native15/SFC24/core8/GBA8 changes.',
    'No current full corresponding-source delivery or public-distribution legal clearance is claimed.',
    'Existing notices remain verbatim; complete transitive Wasmtime attribution was not established by the historical audit.',
    'Historical FC38 artwork/legal findings are not blanket clearance for restored brand40 art or embedded SFC startup firmware.',
]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def digest(raw):
    return hashlib.sha256(raw).hexdigest().upper()


def stream_pin(stream):
    total, sha = 0, hashlib.sha256()
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        total += len(chunk)
        sha.update(chunk)
    return {'bytes': total, 'sha256': sha.hexdigest().upper()}


def safe(path, existing=True):
    path = Path(os.path.abspath(path))
    require(path.is_relative_to(ROOT), 'Path outside workspace: ' + str(path))
    for part in (path, *path.parents):
        try:
            attrs = part.lstat()
        except FileNotFoundError:
            continue
        require(not stat.S_ISLNK(attrs.st_mode) and not getattr(attrs, 'st_file_attributes', 0) & 0x400,
                'Link/reparse path rejected: ' + str(part))
    if existing:
        require(path.is_file() and stat.S_ISREG(path.stat().st_mode), 'Missing regular input: ' + str(path))
    return path


def member(name):
    require(name and '\\' not in name and ':' not in name and not name.startswith('/')
            and not any(ord(c) < 32 or ord(c) == 127 for c in name)
            and all(p not in ('', '.', '..') for p in name.rstrip('/').split('/')), 'Unsafe ZIP member: ' + repr(name))
    return name


class Inputs:
    def __init__(self):
        self.pins = {}

    def remember(self, path, expected=None):
        path = safe(path)
        with path.open('rb') as stream:
            actual = stream_pin(stream)
        require(expected is None or actual == expected, 'Input identity mismatch: ' + str(path))
        require(path not in self.pins or self.pins[path] == actual, 'Input changed: ' + str(path))
        self.pins[path] = actual
        return path

    def pin(self, source):
        return {'bytes': len(source), 'sha256': digest(source)} if isinstance(source, bytes) else self.pins[source]

    def unchanged(self):
        for path, expected in list(self.pins.items()):
            self.remember(path, expected)


def inventory(path, maximum=2 * 1024**3):
    """Read each entry to EOF: ZipExtFile verifies CRC, and we also retain SHA."""
    result, folded, total = {}, set(), 0
    with zipfile.ZipFile(safe(path)) as z:
        require(len(z.infolist()) <= 30000, 'ZIP entry budget exceeded')
        for info in z.infolist():
            name = member(info.orig_filename)
            require(name == info.filename and name.casefold() not in folded, 'Duplicate/ambiguous ZIP member: ' + name)
            folded.add(name.casefold())
            mode = stat.S_IFMT(info.external_attr >> 16)
            require(not info.flag_bits & 1 and mode in (0, stat.S_IFREG, stat.S_IFDIR)
                    and not info.external_attr & 0x400, 'Encrypted/special ZIP member: ' + name)
            total += info.file_size
            require(total <= maximum, 'ZIP expansion budget exceeded')
            with z.open(info) as stream:
                pin = stream_pin(stream)
            require(pin['bytes'] == info.file_size, 'ZIP length mismatch: ' + name)
            if not info.is_dir():
                result[name] = pin
    return result


def check_jar(path, kind, class_owners):
    entries = inventory(path, 256 * 1024**2)
    require('META-INF/neoforge.mods.toml' in entries and 'META-INF/MANIFEST.MF' in entries, 'Missing JAR identity')
    for name in entries:
        low = name.lower()
        require(not low.endswith(('.nes', '.sfc', '.smc', '.gba', '.gb', '.gbc', '.rom', '.bin', '.zip', '.7z', '.rar'))
                and not re.search(r'(^|/)(roms|bios)/', low), 'Game/BIOS payload in mod: ' + name)
        require(not name.startswith(('META-INF/jarjar/', 'META-INF/versions/')), 'Unexpected nested/multi-release runtime')
        if name.endswith('.class'):
            require(name not in class_owners, 'Duplicate class across mods: ' + name)
            class_owners[name] = kind
    with zipfile.ZipFile(path) as z:
        meta = tomllib.loads(z.read('META-INF/neoforge.mods.toml').decode('utf-8'))
        mods = meta.get('mods', [])
        actual = {m['modId']: m['version'] for m in mods}
        require(len(actual) == len(mods) and set(actual) == OWNERS[kind]
                and actual == {k: VERSIONS[k] for k in OWNERS[kind]}, 'Wrong mod IDs/versions: ' + kind)
        for owner, deps in meta.get('dependencies', {}).items():
            require(owner in actual and len({d['modId'] for d in deps}) == len(deps), 'Invalid dependency graph')
            require(not any(d['modId'] == 'piq_retro_platform' for d in deps), 'Unexpected standalone platform dependency')
            if owner != 'piq_fc_arcade':
                fc = [d for d in deps if d['modId'] == 'piq_fc_arcade']
                require(len(fc) == 1 and fc[0].get('type') == 'required'
                        and fc[0].get('side') == 'BOTH'
                        and fc[0].get('versionRange', '').startswith('[0.31.0-alpha.40,'), 'Wrong required FC40 dependency')
        require(set(meta.get('dependencies', {})) == set(actual), 'Missing dependency declarations')
        manifest = z.read('META-INF/MANIFEST.MF').replace(b'\r\n ', b'').decode('utf-8')
        owner = 'piq_sfc_home' if kind == 'sfc' else next(iter(OWNERS[kind]))
        require(re.findall(r'(?m)^Implementation-Version: ([^\r\n]+)', manifest) == [VERSIONS[owner]], 'Wrong manifest version')
    return entries


def freeze(inputs, guide):
    raw = inputs.remember(STAGE / 'build-witness.json').read_bytes()
    witness = json.loads(raw)
    require(witness.get('schema') == 'block-arcade-brand40-build-1' and witness.get('ok') is True
            and witness.get('mode') == 'freeze', 'Successful final brand40 freeze required')
    require(witness.get('validation_errors') == [] and witness.get('blocking_resources') == []
            and witness.get('mod_ids') == VERSIONS and set(witness.get('mods', {})) == set(NAMES), 'Incomplete or mismatched freeze')
    require(witness.get('installed') is False and witness.get('published') is False, 'Expected local-only freeze')
    source_path = inputs.remember(STAGE / 'source-witness.json')
    source = json.loads(source_path.read_bytes())
    require(inputs.pin(source_path)['sha256'] == witness.get('source_witness_sha256')
            and source == witness.get('source_witness')
            and source.get('schema') == 'block-arcade-brand40-source-1'
            and source.get('versions') == VERSIONS
            and source.get('inputs') == witness.get('inputs') and bool(source.get('inputs')), 'Source witness mismatch')
    for rel, sha in source['inputs'].items():
        member(rel)
        path = inputs.remember(ROOT / PurePosixPath(rel))
        require(inputs.pin(path)['sha256'] == sha, 'Frozen build source drift: ' + rel)
    require(witness.get('tests') and all(t.get('failures') == 0 and t.get('errors') == 0 for t in witness['tests'].values()),
            'Successful test evidence required')
    guide = inputs.remember(guide)
    require(guide == GUIDE and guide.relative_to(ROOT).as_posix() in source['inputs'], 'Only the frozen installation guide is accepted')
    frozen_guide = inputs.remember(STAGE / guide.name)
    require(inputs.pin(guide) == inputs.pin(frozen_guide), 'Installation guide differs from frozen copy')
    classes, jars, inventories = {}, {}, {}
    require({p.name for p in safe(STAGE, False).glob('*.jar')} == set(NAMES.values()), 'Stage contains missing/extra JARs')
    for kind, name in NAMES.items():
        details = witness['mods'][kind]
        require(details.get('filename') == name, 'Unexpected frozen JAR filename: ' + kind)
        path = inputs.remember(STAGE / name, {'bytes': details['bytes'], 'sha256': details['sha256']})
        inventories[kind] = check_jar(path, kind, classes)
        jars[kind] = path
    return witness, jars, inventories, frozen_guide


def add(entries, name, source):
    member(name)
    require(not name.endswith('/') and name.casefold() not in {n.casefold() for n in entries}, 'Duplicate output: ' + name)
    entries[name] = source


def collect(guide):
    inputs = Inputs()
    inputs.remember(Path(__file__))
    witness, jars, jar_entries, frozen_guide = freeze(inputs, guide)
    full, small = {}, {}
    for entries in (full, small):
        for path in jars.values():
            add(entries, 'mods/' + path.name, path)
        add(entries, frozen_guide.name, frozen_guide)
        add(entries, 'checks/build-witness.json', STAGE / 'build-witness.json')
        add(entries, 'checks/source-witness.json', STAGE / 'source-witness.json')
    legacy = inputs.remember(LEGACY, LEGACY_PIN)
    legacy_entries = inventory(legacy)
    require({n for n in legacy_entries if n.startswith('licenses/')} == LICENSE_NAMES, 'Legacy license set changed')
    with zipfile.ZipFile(legacy) as z:
        provenance_raw = z.read('checks/runtime-provenance-existing-v1.json')
        provenance = json.loads(provenance_raw)
        rw_raw = z.read('checks/runtime-pack-witness.json')
        rw = json.loads(rw_raw)
        require(provenance.get('schema') == 'piq-runtime37-existing-provenance-1' and provenance.get('ok') is True
                and rw.get('schema') == 'piq-runtime37-offline-pack-1' and rw.get('ok') is True
                and rw.get('provenance_sha256') == digest(provenance_raw), 'Historical runtime evidence mismatch')
        require({k: rw['pack'][k] for k in PACK_PIN} == PACK_PIN, 'Wrong historical runtime pack')
        for entries in (full, small):
            for name in sorted(LICENSE_NAMES):
                add(entries, name, z.read(name))  # Original notices, no rebranding.
            for name in ('runtime-provenance-existing-v1.json', 'runtime-pack-witness.json', 'source-selection.json', 'release38-licenses.json'):
                add(entries, 'checks/legacy38/' + name, z.read('checks/' + name))
    # Keep current embedded notices as well; do not relabel old source as new source.
    for kind, path in jars.items():
        with zipfile.ZipFile(path) as z:
            if kind == 'fc':
                for old_name in sorted(n for n in LICENSE_NAMES if n.startswith('licenses/fc/')):
                    leaf = old_name.removeprefix('licenses/fc/')
                    current = leaf if leaf == 'THIRD_PARTY_NOTICES.md' else 'META-INF/licenses/' + leaf
                    require(digest(z.read(current)) == legacy_entries[old_name]['sha256'], 'Frozen FC notice differs from retained original: ' + current)
            for name in jar_entries[kind]:
                if name.startswith('META-INF/licenses/') or PurePosixPath(name).name in ('LICENSE', 'LICENSE.txt', 'THIRD_PARTY_NOTICES.md'):
                    data = z.read(name)
                    for entries in (full, small):
                        add(entries, 'licenses/current/' + kind + '/' + name, data)
    pack = inputs.remember(PACK, PACK_PIN)
    runtime_entries = inventory(pack, 440 * 1024**2)
    expected_runtime = {n: {k: row[k] for k in ('bytes', 'sha256')} for n, row in provenance['runtime_files'].items()}
    require(len(runtime_entries) == 9 and runtime_entries == expected_runtime, 'Runtime nine-file SHA/CRC mismatch')
    require(legacy_entries['piq-runtime-packs/piq-runtime-pack-v1.zip'] == PACK_PIN, 'Legacy/runtime identity mismatch')
    add(full, 'piq-runtime-packs/piq-runtime-pack-v1.zip', pack)
    old_source = inputs.remember(OLD_SOURCE, SOURCE_PIN)
    source_entries = inventory(old_source)
    require(any(n.startswith('native-corresponding-source/') for n in source_entries)
            and any(n.startswith('gba-corresponding-source/') for n in source_entries), 'Missing corresponding runtime source families')
    with zipfile.ZipFile(old_source) as z:
        gba_license = z.read('gba-corresponding-source/PIQ-GPL-3.0.txt')
        for entries in (full, small):
            add(entries, 'licenses/gba-helper/PIQ-GPL-3.0.txt', gba_license)
    references = {'schema': 'block-arcade-brand40-source-references-1', 'archive': old_source.relative_to(ROOT).as_posix(),
                  **SOURCE_PIN, 'included_in_this_zip': False, 'original_archive_modified': False,
                  'entries': source_entries, 'limitations': LIMITS}
    notice = ('# 本地测试交付与源码引用\n\n此包不是安装/发布动作，不代表联机或原生库实机验收。\n\n'
              '完整版含离线运行库；mods-only 小包仅供已具备所需运行库的实例升级，缺库不会联网下载。\n\n'
              '原许可原文保留在 licenses/，旧核验资料在 checks/legacy38/，其版本与限制没有改写。\n\n'
              f'沿用运行库对应源码的独立历史包：`{OLD_SOURCE.relative_to(ROOT).as_posix()}`；'
              f'{SOURCE_PIN["bytes"]} 字节；SHA-256 `{SOURCE_PIN["sha256"]}`。\n\n'
              '历史源码 ZIP 本次不附带、不修改；它不是需要安装的旧 JAR。内部原路径/逐文件 SHA 见 checks/source-references.json；'
              '取得源码应向交付者索取该精确原包，而不是把源码 ZIP 放进 mods。此引用不保证接收者已经取得源码。\n\n'
              '该历史包能说明未改运行库的来源，但其中旧模组源码不能冒充 FC40/Native15/SFC24/core8/GBA8 对应源码。'
              '本次没有打包新版本完整对应源，不宣称已完成公开分发义务或全部传递依赖许可核验。'
              '新版本冻结源码路径/哈希记录在 checks/source-witness.json；公开转发前仍需准备相应源和未决许可资料。\n').encode('utf-8')
    for entries in (full, small):
        add(entries, '本地测试与源码引用.md', notice)
        add(entries, 'checks/source-references.json', encoded(references))
    inputs.unchanged()
    return inputs, full, small, witness


def archive(path, entries, inputs):
    path = safe(path, False)
    require(not path.exists(), 'Never overwrite an existing output: ' + str(path))
    with zipfile.ZipFile(path, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as z:
        for name, source in sorted(entries.items()):
            info = zipfile.ZipInfo(member(name), (2026, 9, 14, 0, 0, 0))
            info.create_system = 3
            info.external_attr = 0o100644 << 16
            info.compress_type = zipfile.ZIP_STORED if name.endswith(('.jar', '.zip')) else zipfile.ZIP_DEFLATED
            if isinstance(source, bytes):
                z.writestr(info, source)
            else:
                with safe(source).open('rb') as src, z.open(info, 'w') as dest:
                    shutil.copyfileobj(src, dest, 1024 * 1024)
    actual = inventory(path)
    expected = {name: inputs.pin(source) for name, source in entries.items()}
    require(actual == expected, 'Final ZIP SHA/CRC/readback mismatch: ' + str(path))
    with path.open('rb') as stream:
        pin = stream_pin(stream)
    return {'path': str(path), **pin, 'entries': actual, 'all_entries_crc_and_sha256_verified': True}


def main():
    if hasattr(sys.stdout, 'reconfigure'):
        sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--install-guide', type=Path, default=GUIDE)
    parser.add_argument('--write', action='store_true', help='Explicitly create new full ZIP, mods-only ZIP and success receipt')
    args = parser.parse_args()
    targets = [STAGE / (NAME + suffix) for suffix in ('.zip', '-mods-only.zip', '.verification.json')]
    for path in targets:
        require(not safe(path, False).exists(), 'Existing output is never reused or overwritten: ' + str(path))
    inputs, full, small, witness = collect(args.install_guide)
    result = {'schema': 'block-arcade-brand40-delivery-1', 'ok': True,
              'mode': 'exclusive-new-local-test-archives' if args.write else 'read-only-preflight',
              'build_witness_sha256': inputs.pin(STAGE / 'build-witness.json')['sha256'],
              'source_witness_sha256': witness['source_witness_sha256'], 'mods': {k: inputs.pin(STAGE / n) for k, n in NAMES.items()},
              'runtime_pack': PACK_PIN, 'legacy_source_archive': {'path': str(OLD_SOURCE), **SOURCE_PIN},
              'full_entries': len(full), 'mods_only_entries': len(small), 'input_files': len(inputs.pins),
              'limits': LIMITS, 'installed': False, 'published': False, 'native_core_started': False,
              'minecraft_started': False, 'compiled': False, 'source_archives_copied': False}
    if args.write:
        # Any failure preserves only newly created outputs for diagnosis; no success
        # receipt is emitted. Never delete, overwrite, or adopt partial archives.
        result['full_package'] = archive(targets[0], full, inputs)
        result['mods_only_package'] = archive(targets[1], small, inputs)
        inputs.unchanged()
        result['inputs_unchanged_after_packaging'] = True
        receipt = encoded(result)
        with safe(targets[2], False).open('xb') as stream:
            stream.write(receipt)
        require(targets[2].read_bytes() == receipt, 'Success receipt readback mismatch')
    print(json.dumps({k: v for k, v in result.items() if k not in ('full_package', 'mods_only_package')}, ensure_ascii=False, indent=2))
    if args.write:
        print(json.dumps({'outputs': [str(p) for p in targets]}, ensure_ascii=False))


if __name__ == '__main__':
    main()
