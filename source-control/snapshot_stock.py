"""Fixed FBNeo stock inputs for snapshots: explicit local import, never download.

The core and corresponding source archive travel together through the existing
input receipt. Receipt edits cannot replace these reviewed identities. This is
not a source-to-binary reproducibility claim or permission to publish the inputs.
"""
from __future__ import annotations

import hashlib
import os
from pathlib import Path
import stat

PREFIX = 'fixed/fbneo-stock-v1/'
INPUTS = {
    'core': dict(path=PREFIX + 'fbneo_libretro.dll', bytes=61913629,
                 sha256='52343f65453c0e1fc06b3aa818b48ca4fed18361f7f336a684e9e3cffa5f5f3c'),
    'source': dict(path=PREFIX + 'FBNeo-a251c76229f1637e433b93e29845039752771b6d.zip', bytes=29731166,
                   sha256='55cc0f5bf305d8953fa0a20c3598164d39efc03ef3740c7b01e7ebf143cd4d7a'),
}
GENERATED_PREFIXES = ('native-runtime/win-x64-fbneo-stock-v1/', 'META-INF/piq-native/fbneo-stock-v1/')


def no_links(path: Path) -> Path:
    path = Path(os.path.abspath(path))
    for node in (path, *path.parents):
        try:
            info = node.lstat()
        except FileNotFoundError:
            continue
        if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
            raise ValueError('Linked fixed stock input/output path is not accepted')
    return path


def verify(path: Path, expected: dict) -> tuple:
    path = no_links(path)
    before = path.stat()
    if not stat.S_ISREG(before.st_mode) or before.st_size != expected['bytes']:
        raise ValueError('Fixed FBNeo stock input size/type mismatch: ' + path.name)
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
    after = path.stat()
    key = lambda info: (info.st_dev, info.st_ino, info.st_size, info.st_mtime_ns)
    if key(before) != key(after) or digest != expected['sha256']:
        raise ValueError('Fixed FBNeo stock input identity mismatch: ' + path.name)
    return key(after)


def inspect_pair(core: Path | None, source: Path | None) -> dict[str, Path]:
    if core is None or source is None:
        raise ValueError('Fixed FBNeo stock inputs require both --fbneo-stock-core and --fbneo-stock-source; no download or vendor fallback')
    sources = dict(core=no_links(core), source=no_links(source))
    for role, path in sources.items():
        verify(path, INPUTS[role])
    return sources


def import_pair(output: Path, sources: dict[str, Path]) -> None:
    """Bounded exclusive copies; never rewrite a source or a conflicting output."""
    output = no_links(output)
    before = {role: verify(sources[role], entry) for role, entry in INPUTS.items()}
    for role, entry in INPUTS.items():
        destination = no_links(output / entry['path'])
        if destination.exists():
            verify(destination, entry)
            continue
        destination.parent.mkdir(parents=True, exist_ok=True)
        with sources[role].open('rb') as original, destination.open('xb') as copied:
            remaining = entry['bytes']
            while remaining:
                chunk = original.read(min(1024 * 1024, remaining))
                if not chunk:
                    raise ValueError('Fixed stock source truncated during import')
                copied.write(chunk)
                remaining -= len(chunk)
            if original.read(1):
                raise ValueError('Fixed stock source grew during import')
        verify(destination, entry)
    for role, entry in INPUTS.items():
        if verify(sources[role], entry) != before[role]:
            raise ValueError('Fixed stock source changed during import')


def verify_receipt(input_root: Path, records: dict[str, dict]) -> dict[str, Path]:
    expected_paths = {entry['path'] for entry in INPUTS.values()}
    if {name for name in records if name.startswith(PREFIX)} != expected_paths:
        raise ValueError('Snapshot receipt requires the exact fixed FBNeo stock core/source pair')
    result = {}
    for role, expected in INPUTS.items():
        entry = records[expected['path']]
        if any(entry.get(key) != value for key, value in expected.items()):
            raise ValueError('Snapshot receipt cannot change a fixed FBNeo stock identity')
        path = no_links(input_root / expected['path'])
        verify(path, expected)
        result[role] = path
    return result


def generated_resource(name: str) -> bool:
    return any(name.startswith(prefix) for prefix in GENERATED_PREFIXES)
