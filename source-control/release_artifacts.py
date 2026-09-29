"""Create byte-identical branded JAR copies. Not a builder, installer or release approval."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import shutil
import tomllib
import zipfile

META = 'META-INF/neoforge.mods.toml'
# Exact registered IDs, not filenames or a global replacement of compatibility identifiers.
PROFILES = {
    frozenset({'piq_fc_arcade'}): ('game-console', 'piq_fc_arcade', False),
    frozenset({'piq_sfc_home', 'piq_sfc_arcade'}): ('game-console-sfc', 'piq_sfc_home', False),
    frozenset({'piq_sfc_home'}): ('game-console-sfc-home-dev', 'piq_sfc_home', True),
    frozenset({'piq_sfc_arcade'}): ('game-console-sfc-core-dev', 'piq_sfc_arcade', True),
    frozenset({'piq_native_arcade'}): ('game-console-arcade', 'piq_native_arcade', False),
    frozenset({'piq_gba'}): ('game-console-gba', 'piq_gba', False),
    frozenset({'piq_md_home'}): ('game-console-md', 'piq_md_home', False),
    frozenset({'piq_computer'}): ('game-console-computer', 'piq_computer', False),
    frozenset({'piq_pvz'}): ('game-console-pvz', 'piq_pvz', False),
    frozenset({'piq_flash_box'}): ('game-console-flash', 'piq_flash_box', False),
    frozenset({'piq_j2me_arcade'}): ('game-console-j2me', 'piq_j2me_arcade', False),
}


def sha256(path: Path) -> str:
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def inspect(path: Path, allow_development: bool = False) -> dict:
    if path.is_symlink() or not path.is_file() or path.suffix.lower() != '.jar':
        raise ValueError('Input must be a regular JAR: ' + path.name)
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(set(names)) != len(names):
            raise ValueError('Duplicate ZIP entries: ' + path.name)
        if META not in names or archive.getinfo(META).file_size > 1024 * 1024:
            raise ValueError('Missing or oversized mod metadata: ' + path.name)
        mods = tomllib.loads(archive.read(META).decode('utf-8-sig')).get('mods', [])
    versions = {}
    for mod in mods:
        mod_id, version = mod.get('modId'), mod.get('version')
        if not isinstance(mod_id, str) or mod_id in versions:
            raise ValueError('Missing or duplicate mod ID: ' + path.name)
        if not isinstance(version, str) or not re.fullmatch(r'[0-9][A-Za-z0-9.+_-]{0,95}', version):
            raise ValueError('Unresolved or unsafe mod version: ' + path.name)
        versions[mod_id] = version
    profile = PROFILES.get(frozenset(versions))
    if profile is None:
        raise ValueError('Unrecognized mod combination: ' + path.name)
    prefix, version_id, development = profile
    if development and not allow_development:
        raise ValueError('SFC thin/core JAR is not a complete player package; use --allow-development for dev copies')
    return dict(sourceName=path.name, fileName=f'{prefix}-{versions[version_id]}.jar',
                versions=versions, developmentOnly=development,
                bytes=path.stat().st_size, sha256=sha256(path))


def stage(paths: list[Path], output: Path, allow_development: bool = False) -> dict:
    if not paths:
        raise ValueError('At least one JAR is required')
    if output.exists() or output.is_symlink():
        raise ValueError('Output must be a new directory; historical artifacts are immutable')
    entries = [inspect(p, allow_development) for p in paths]
    seen = set()
    for entry in entries:
        ids = set(entry['versions'])
        if seen & ids:
            raise ValueError('Duplicate mod IDs across input JARs')
        seen.update(ids)
    # Read-only preflight finishes before any copies; exclusive creation prevents overwrites.
    output.mkdir(parents=True, exist_ok=False)
    for source, entry in zip(paths, entries):
        destination = output / entry['fileName']
        with source.open('rb') as src, destination.open('xb') as dst:
            shutil.copyfileobj(src, dst)
        if sha256(destination) != entry['sha256'] or sha256(source) != entry['sha256']:
            raise ValueError('Source changed or copy verification failed; keep partial output for inspection')
    receipt = dict(brand='Game Console', operation='byte-identical-named-copies', files=entries,
                   rebuilt=False, installed=False, published=False, compatibilityVerified=False)
    with (output / 'release-manifest.json').open('x', encoding='utf8') as stream:
        json.dump(receipt, stream, ensure_ascii=False, indent=2)
    return receipt


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, action='append', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--allow-development', action='store_true')
    args = parser.parse_args()
    print(json.dumps(stage(args.jar, args.output, args.allow_development), ensure_ascii=True, indent=2))


if __name__ == '__main__':
    main()
