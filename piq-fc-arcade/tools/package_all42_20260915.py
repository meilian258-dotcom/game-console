"""Combine exact existing releases; no compilation, installation, core execution or network."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json
import re
import sys
import zipfile
import tomllib

ROOT = Path(__file__).resolve().parents[2]
UTILITY = ROOT / 'piq-fc-arcade/tools/package_brand40.py'
UTILITY_SHA = '15322FE8D8C4A1BC99BE80D1B0D7808911C2DF77DA412FAC444AA563535D9EF4'
if hashlib.sha256(UTILITY.read_bytes()).hexdigest().upper() != UTILITY_SHA:
    raise RuntimeError('Packaging helper changed')
spec = importlib.util.spec_from_file_location('all42_package_helpers', UTILITY)
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
DOCS = ROOT / 'outputs/all42-20260915'
TARGET = ROOT / '制作Mod/03-街机模拟/方块电玩-全模组配套包-20260915-v1.zip'
RECEIPT = DOCS / 'package-verification.json'
BASE = ROOT / 'piq-fc-arcade/build'
MODS = {
    'fc': ('review-bundled42-v2/game_console-0.31.0-alpha.42.jar', 30756487,
           '349CF80D69B1EA9BC20B2EB99870EFD9A72ADCE339BD34EADDFA3C5EEB69CEA0'),
    'native': ('review-bundled42-v2/game_console_arcade-0.1.0-alpha.17.jar', 124725131,
               '875CE76B8B987E533A50EF34612116FD06455CFFF4A9BE787BCFE0720F650B05'),
    'sfc': ('review-public41-v1/game_console_sfc-0.1.0-alpha.25.jar', 1349951,
            'C0B15FEE1A05C914296DFC04E8B37B7FC45881F7102F6F6BAE3E82FDE3C49C78'),
    'gba': ('review-brand40-v1/block_arcade_gba-0.1.0-alpha.8.jar', 233090,
            'D04C20F16085DCAE558626966C16254815FDF02B8A7CD91F06D8ED412B0C44BD'),
}
WITNESSES = {
    'review-bundled42-v2': '6561956EDB115B4B092B8ED5A426FD7061ED56C159E0D5CF4D229B8445F4FA9F',
    'review-public41-v1': '4D4A619D2197739C0EB9FCF13FBDBAEAC14EBF1CBBBC817864785B9CB467FEB7',
    'review-brand40-v1': '53FCD93A9C8658C29C2E63A1871D50479B7499D9EEF1CB7BB8A4FECDAA9BC27C',
}
VERSIONS = {'piq_fc_arcade': '0.31.0-alpha.42', 'piq_native_arcade': '0.1.0-alpha.17',
            'piq_sfc_home': '0.1.0-alpha.25', 'piq_sfc_arcade': '0.2.0-alpha.9', 'piq_gba': '0.1.0-alpha.8'}
OWNERS = {'fc': {'piq_fc_arcade'}, 'native': {'piq_native_arcade'},
          'sfc': {'piq_sfc_home', 'piq_sfc_arcade'}, 'gba': {'piq_gba'}}
GBA = {
    'piq-gba/runtime/mgba_libretro.dll': (2955998, 'D1BA96BC1AF23997D5C8003A6F6F8BE7ACBA9D770D4D42D14557AAEB469FA16B'),
    'piq-gba/runtime/piq-gba-helper.jar': (20182, 'AF687B20AFD470992F9C02C80173356E20E9D9E98FABDDCF3800979F11A4B28C'),
    'piq-gba/runtime/jna-5.14.0.jar': (1878533, '34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6'),
}
LICENSE_ZIP = BASE / 'review-brand40-v1/方块电玩-配套测试包-20260914-v1-mods-only.zip'
LICENSE_PIN = {'bytes': 33174722, 'sha256': 'C7013B33DC3392EA3F6D5A2FA8B67D50C5B547596BA03FBFA3E3E37A1E2EF46F'}


def version(value):
    match = re.fullmatch(r'(\d+)(?:\.(\d+))?(?:\.(\d+))?(?:-alpha\.(\d+))?', value)
    p.require(match is not None, 'Unrecognised version: ' + value)
    major, minor, patch, alpha = match.groups()
    return (int(major), int(minor or 0), int(patch or 0), 0 if alpha is not None else 1, int(alpha or 0))


def accepts(value, requirement):
    p.require(requirement and requirement[0] in '[(' and requirement[-1] in ')]', 'Unsupported version range')
    bounds = requirement[1:-1].split(',')
    if len(bounds) == 1:
        return requirement[0] == '[' and requirement[-1] == ']' and version(value) == version(bounds[0])
    p.require(len(bounds) == 2, 'Disjoint version range unsupported')
    low, high = bounds
    actual = version(value)
    return (not low or actual > version(low) or requirement[0] == '[' and actual == version(low)) and (
        not high or actual < version(high) or requirement[-1] == ']' and actual == version(high))


def collect():
    inputs = p.Inputs()
    inputs.remember(Path(__file__))
    inputs.remember(UTILITY)
    witnesses = {}
    for folder, pin in WITNESSES.items():
        path = inputs.remember(BASE / folder / 'build-witness.json')
        p.require(inputs.pin(path)['sha256'] == pin, 'Frozen witness changed')
        witnesses[folder] = json.loads(path.read_bytes())
        p.require(witnesses[folder].get('ok') is True, 'Unsuccessful historical freeze')
    entries, inventories, metadatas, classes = {}, {}, {}, {}
    for kind, (relative, size, sha) in MODS.items():
        path = inputs.remember(BASE / relative, {'bytes': size, 'sha256': sha})
        folder = Path(relative).parts[0]
        frozen = witnesses[folder]['mods'][kind]
        p.require((frozen['filename'], frozen['bytes'], frozen['sha256']) == (path.name, size, sha), 'Freeze/artifact mismatch')
        inventory = p.inventory(path, 600 * 1024**2)
        inventories[kind] = inventory
        for name in inventory:
            if name.endswith('.class'):
                p.require(name not in classes, 'Duplicate outer class: ' + name)
                classes[name] = kind
            p.require(not name.startswith('META-INF/jarjar/'), 'Unexpected JarJar entry')
            p.require(not name.lower().endswith(('.nes', '.sfc', '.smc', '.gba', '.gb', '.gbc', '.rom', '.zip', '.7z')),
                      'Unexpected independent game/archive payload: ' + name)
        with zipfile.ZipFile(path) as jar:
            metadata = tomllib.loads(jar.read('META-INF/neoforge.mods.toml').decode('utf-8'))
        actual = {m['modId']: m['version'] for m in metadata['mods']}
        p.require(len(actual) == len(metadata['mods']) and actual == {k: VERSIONS[k] for k in OWNERS[kind]}, 'Wrong mod identity')
        metadatas[kind] = metadata
        p.add(entries, 'mods/' + path.name, path)
    providers = dict(VERSIONS, minecraft='1.21.1', neoforge='21.1.236')
    dependency_checks = []
    for kind, metadata in metadatas.items():
        for owner, dependencies in metadata.get('dependencies', {}).items():
            p.require(owner in OWNERS[kind], 'Unknown dependency owner')
            for dependency in dependencies:
                name = dependency['modId']
                if dependency.get('type') != 'required':
                    dependency_checks.append(dict(owner=owner, dependency=dependency, result='optional-not-supplied'))
                    continue
                p.require(name in providers and accepts(providers[name], dependency['versionRange']), 'Unsatisfied dependency: ' + owner + ' -> ' + name)
                dependency_checks.append(dict(owner=owner, dependency=dependency, actual=providers[name], result='satisfied'))
    runtime = inputs.remember(p.PACK, p.PACK_PIN)
    nine = p.inventory(runtime, 440 * 1024**2)
    prefix = 'native-runtime/win-x64-v1/'
    expected = {name[len(prefix):]: pin for name, pin in inventories['native'].items()
                if name.startswith(prefix) and name.endswith(('.dll', '.jar'))}
    p.require(len(expected) == 6, 'Native must contain exactly six fixed runtime files')
    expected.update({name: {'bytes': size, 'sha256': sha} for name, (size, sha) in GBA.items()})
    p.require(nine == expected, 'Fixed original runtime nine-file inventory mismatch')
    with zipfile.ZipFile(runtime) as archive:
        for name in sorted(GBA):
            raw = archive.read(name)
            p.require(inputs.pin(raw) == expected[name], 'GBA source changed')
            p.add(entries, name, raw)
    license_zip = inputs.remember(LICENSE_ZIP, LICENSE_PIN)
    old_inventory = p.inventory(license_zip)
    wanted = p.LICENSE_NAMES | {'licenses/gba-helper/PIQ-GPL-3.0.txt'}
    with zipfile.ZipFile(license_zip) as archive:
        for name in sorted(wanted):
            raw = archive.read(name)
            p.require(inputs.pin(raw) == old_inventory[name], 'Original license changed')
            p.add(entries, name, raw)
    for name in ('安装说明.md', '许可与来源说明.md'):
        p.add(entries, name, inputs.remember(DOCS / name))
    manifest = dict(schema='game-console-all42-files-1', date='2026-09-15', test_preview=True,
                    versions=VERSIONS, minecraft='1.21.1', neoforge='21.1.236', java=21,
                    gba_runtime_separate=True, arcade_runtime_embedded=True,
                    files={name: inputs.pin(source) for name, source in sorted(entries.items())},
                    limitations=['Existing frozen bytes only; not a new compilation or real multiplayer acceptance.',
                                 'No current complete corresponding-source delivery or public-release legal clearance.'])
    p.add(entries, '文件清单.json', p.encoded(manifest))
    sums = ''.join(f'{inputs.pin(source)["sha256"]}  {name}\n' for name, source in sorted(entries.items()))
    p.add(entries, 'SHA256SUMS.txt', sums.encode('utf-8'))
    p.require(len(entries) == 26 and sum(n.startswith('mods/') for n in entries) == 4
              and {n for n in entries if n.startswith('piq-gba/')} == set(GBA)
              and not any(n.startswith(('piq-native-arcade/', 'piq-runtime-packs/')) for n in entries), 'Unexpected output layout')
    inputs.unchanged()
    return inputs, entries, dict(mod_versions=VERSIONS, dependencies=dependency_checks, outer_class_count=len(classes),
                                 gba_runtime_bytes=sum(size for size, _ in GBA.values()), original_runtime=p.PACK_PIN)


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    inputs, entries, checks = collect()
    result = dict(schema='game-console-all42-package-1', ok=True, checks=checks,
                  compiled=False, installed=False, published=False, native_core_started=False,
                  minecraft_started=False, source_archives_copied=False, entries=len(entries))
    if args.write:
        p.require(not p.safe(TARGET, False).exists() and not p.safe(RECEIPT, False).exists(), 'Output already exists; never overwrite')
        result['package'] = p.archive(TARGET, entries, inputs)
        inputs.unchanged()
        result['inputs_unchanged'] = True
        result['inputs'] = {path.relative_to(ROOT).as_posix(): pin for path, pin in inputs.pins.items()}
        raw = p.encoded(result)
        with RECEIPT.open('xb') as stream:
            stream.write(raw)
        p.require(RECEIPT.read_bytes() == raw, 'Receipt changed after write')
    print(json.dumps({k: v for k, v in result.items() if k not in ('checks', 'inputs', 'package')}, ensure_ascii=False))
    if args.write:
        print(json.dumps({k: v for k, v in result['package'].items() if k != 'entries'}, ensure_ascii=False))


if __name__ == '__main__':
    main()
