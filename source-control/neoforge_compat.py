"""Shared NeoForge 1.21.1 policy and read-only final seven-JAR metadata checks.

These checks do not establish binary/runtime compatibility or certify game dependency
ranges. Use real loader startup and the existing Maven range probes as separate gates.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import tomllib
import zipfile

POLICY = Path(__file__).with_name('neoforge-compat.properties')
META = 'META-INF/neoforge.mods.toml'
PLAYER_IDS = frozenset({'piq_fc_arcade', 'piq_sfc_home', 'piq_sfc_arcade',
                        'piq_md_home', 'piq_native_arcade', 'piq_gba',
                        'piq_computer', 'piq_pvz'})


def revision(value: str) -> int:
    if not re.fullmatch(r'21\.1\.(0|[1-9][0-9]*)', value):
        raise ValueError('Only NeoForge 21.1 numeric releases are supported: ' + value)
    return int(value.rsplit('.', 1)[1])


def load_policy(path: Path = POLICY) -> dict[str, str]:
    values = {}
    for raw in path.read_text('utf8').splitlines():
        line = raw.strip()
        if not line or line.startswith('#'):
            continue
        key, separator, value = line.partition('=')
        key, value = key.strip(), value.strip()
        if not separator or key in values:
            raise ValueError('Malformed or duplicate policy entry: ' + key)
        values[key] = value
    if set(values) != {'minecraft_version', 'neo_version', 'neo_min_version'}:
        raise ValueError('Unexpected NeoForge compatibility policy keys')
    if values['minecraft_version'] != '1.21.1':
        raise ValueError('This compatibility policy only supports Minecraft 1.21.1')
    validate_compile_version(values, values['neo_version'])
    return values


def validate_compile_version(policy: dict[str, str], version: str) -> str:
    if revision(version) < revision(policy['neo_min_version']):
        raise ValueError('Compile/runtime test target is below the reviewed support floor')
    return version


def render_metadata(raw: bytes, policy: dict[str, str]) -> bytes:
    """GBA's custom build expands only the common floor, never its compile target."""
    text = raw.decode('utf-8-sig')
    if text.count('${neo_min_version}') != 1:
        raise ValueError('Expected exactly one common NeoForge floor placeholder')
    rendered = text.replace('${neo_min_version}', policy['neo_min_version'])
    if '${' in rendered:
        raise ValueError('Unexpanded metadata placeholder')
    tomllib.loads(rendered)
    return rendered.encode('utf8')


def read_metadata(path: Path) -> dict:
    if not path.is_file() or path.is_symlink() or path.suffix.lower() != '.jar':
        raise ValueError('Expected a regular final JAR: ' + str(path))
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)) or META not in names:
            raise ValueError('Duplicate ZIP entries or missing metadata: ' + path.name)
        if archive.getinfo(META).file_size > 1024 * 1024:
            raise ValueError('Oversized metadata: ' + path.name)
        text = archive.read(META).decode('utf-8-sig')
    if '${' in text:
        raise ValueError('Unexpanded metadata placeholder: ' + path.name)
    return tomllib.loads(text)


def validate_bundle_metadata(metadata: list[dict], policy: dict[str, str]) -> dict:
    """Check all eight dependency owners, including the frozen core9 in full SFC."""
    if len(metadata) != 7:
        raise ValueError('Expected exactly seven final player JARs')
    versions, floors = {}, {}
    for document in metadata:
        mods = document.get('mods', [])
        local = {mod['modId'] for mod in mods}
        if not local or len(local) != len(mods) or local & versions.keys():
            raise ValueError('Missing or duplicate mod IDs')
        if len(local) > 1 and local != {'piq_sfc_home', 'piq_sfc_arcade'}:
            raise ValueError('Only the complete SFC may contain two mod IDs')
        dependencies = document.get('dependencies', {})
        if set(dependencies) != local:
            raise ValueError('Missing or foreign dependency owner')
        for mod in mods:
            owner = mod['modId']
            versions[owner] = mod['version']
            deps = dependencies[owner]
            if len({dep['modId'] for dep in deps}) != len(deps):
                raise ValueError('Duplicate dependency: ' + owner)
            by_id = {dep['modId']: dep for dep in deps}
            neo = by_id.get('neoforge', {})
            minecraft = by_id.get('minecraft', {})
            minimum = policy['neo_min_version']
            allowed = {f'[{minimum},22)'}
            if owner in {'piq_sfc_arcade', 'piq_gba'}:
                allowed.add(f'[{minimum},)')  # Preserve these historical upper bounds.
            if neo.get('type') != 'required' or neo.get('side') != 'BOTH' or neo.get('versionRange') not in allowed:
                raise ValueError('Incorrect NeoForge support floor/authority: ' + owner)
            mc_ranges = {'[1.21.1,1.21.2)'}
            if owner == 'piq_sfc_arcade':
                mc_ranges.add('[1.21.1,1.22)')  # Complete SFC home's range remains narrower.
            if minecraft.get('type') != 'required' or minecraft.get('versionRange') not in mc_ranges:
                raise ValueError('Minecraft version scope changed: ' + owner)
            floors[owner] = neo['versionRange']
    if versions.keys() != PLAYER_IDS:
        raise ValueError('Expected the current eight player mod IDs')
    return dict(ok=True, jarCount=7, modIdCount=8, versions=versions, neoForgeRanges=floors,
                minecraft='1.21.1', runtimeCompatibilityVerified=False)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, action='append', required=True)
    parser.add_argument('--neo-version', help='Validate a 21.1 runtime test target, not change the floor')
    args = parser.parse_args()
    policy = load_policy()
    if args.neo_version:
        validate_compile_version(policy, args.neo_version)
    print(json.dumps(validate_bundle_metadata([read_metadata(p) for p in args.jar], policy),
                     ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
