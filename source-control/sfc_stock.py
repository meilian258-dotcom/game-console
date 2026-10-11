"""Prepare the fixed, unmodified Snes9x local candidate. No network or execution.

Explicit inputs or the shared content-addressed .build-inputs cache only. The
reference source ZIP is a required, separate companion, not embedded in the JAR.
This command verifies provenance facts; it does not grant publication approval.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import re
import stat
import sys
import zipfile

from build_inputs import ROOT, cache_path, import_inputs, load_lock, no_links, relative, verify

LOCK = Path(__file__).with_name('sfc-stock-inputs.json')
INPUTS = load_lock(LOCK)
PREFIX = 'fixed/sfc-snes9x-v1/'
RESOURCE = 'core/sfc-snes9x/'
CORE_RESOURCE = RESOURCE + 'windows-x64/snes9x_libretro.dll'
SOURCE_ROOT = 'snes9x-fae2fea08f74180759ef540ee94259213f503480/'
LEGACY_RESOURCE = 'core/sfc-libretro/windows-x64/mesen-s_libretro.dll'
LEGACY = dict(bytes=3896832, sha256='8aca17e76efbd7a70b0c247b42aaba04d0c1c90f693213bd1e76573986670b42')
PUBLICATION_BLOCK = ('Snes9x is a local candidate, not approved for public snapshots: '
                     'review exact Buildbot source/build provenance, LGPL corresponding-source '
                     'and relinking obligations, historical JMA/xBRZ applicability and '
                     'non-commercial distribution conditions; see core/sfc-snes9x/NOTICE.txt')


def inspect_pair(core=None, source=None, cache=None):
    if (core is None) != (source is None):
        raise ValueError('Snes9x requires both --core and --source, or neither with a populated fixed cache')
    if core is None:
        cache = ROOT / '.build-inputs' if cache is None else cache
        sources = {role: cache_path(cache, pin) for role, pin in INPUTS.items()}
    else:
        sources = dict(core=no_links(core), source=no_links(source))
    for role, pin in INPUTS.items():
        try:
            verify(sources[role], pin)
        except (OSError, ValueError) as exc:
            raise ValueError('Fixed Snes9x ' + role + ' required; supply matching bytes, not current latest: ' + str(exc)) from exc
    return sources


def put(path, data):
    path = no_links(path)
    if path.exists():
        if path.read_bytes() != data:
            raise ValueError('Refusing to overwrite different prepared Snes9x resource: ' + path.name)
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('xb') as stream:
        stream.write(data)


def license_resources(source):
    """Keep verbatim original license bytes and attributed inline notices.

    The full pinned source companion remains authoritative. This is a mechanical
    notice inventory, not a claim that each source file was linked into the DLL.
    """
    verify(source, INPUTS['source'])
    with zipfile.ZipFile(source) as archive:
        entries = archive.infolist()
        if len(entries) > 2000 or sum(e.file_size for e in entries) > 16 * 1024 * 1024:
            raise ValueError('Snes9x source archive exceeds budget')
        seen = set()
        texts = {}
        for entry in entries:
            name = entry.filename.rstrip('/')
            relative(name)
            if (entry.orig_filename != entry.filename or name.casefold() in seen
                    or entry.flag_bits & 1 or stat.S_ISLNK(entry.external_attr >> 16)
                    or not name.startswith(SOURCE_ROOT.rstrip('/') + '/') and name != SOURCE_ROOT.rstrip('/')):
                raise ValueError('Unsafe Snes9x source archive member')
            seen.add(name.casefold())
            if not entry.is_dir():
                texts[name[len(SOURCE_ROOT):]] = archive.read(entry)
        required = ('LICENSE', 'filter/snes_ntsc-license.txt', 'libretro/Makefile.common',
                    'apu/bapu/dsp/SPC_DSP.cpp', 'libretro/libretro.h')
        if any(name not in texts for name in required):
            raise ValueError('Missing original Snes9x license/source materials')
        comments = []
        # Include whole original comment blocks, never paraphrase their terms.
        pattern = rb'/\*[\s\S]*?\*/|(?m:^[ \t]*//[^\r\n]*(?:\r?\n[ \t]*//[^\r\n]*)*)'
        for name, raw in sorted(texts.items()):
            if not name.endswith(('.c', '.cpp', '.h', '.hpp')):
                continue
            for match in re.finditer(pattern, raw):
                block = match.group()
                if re.search(rb'copyright|permission is hereby|licensed? under|general public license', block, re.I):
                    comments.append(b'\n--- ' + name.encode('utf8') + b' ---\n' + block + b'\n')
        if not comments:
            raise ValueError('Source has no copyright/permission inventory')
        header = (b'Verbatim copyright/license comments from the pinned reference source.\n'
                  b'File inclusion here does not establish incorporation in the Buildbot DLL.\n'
                  b'Full original source is supplied separately; see upstream.json and NOTICE.txt.\n')
        return {
            RESOURCE + 'licenses/LICENSE-Snes9x.txt': texts['LICENSE'],
            RESOURCE + 'licenses/LICENSE-LGPL-2.1.txt': texts['filter/snes_ntsc-license.txt'],
            RESOURCE + 'licenses/UPSTREAM-COMMENTS.txt': header + b''.join(comments),
        }


def prepare(output, sources):
    sources = inspect_pair(**sources)
    before = {role: path.stat().st_mtime_ns for role, path in sources.items()}
    resources = license_resources(sources['source'])
    resources[CORE_RESOURCE] = sources['core'].read_bytes()
    if (len(resources[CORE_RESOURCE]) != INPUTS['core']['bytes']
            or hashlib.sha256(resources[CORE_RESOURCE]).hexdigest() != INPUTS['core']['sha256']):
        raise ValueError('Snes9x core changed during read')
    manifest = json.loads(LOCK.read_text('utf8'))
    resources[RESOURCE + 'upstream.json'] = (json.dumps(manifest, indent=2, sort_keys=True) + '\n').encode()
    output = no_links(output)
    # Detect an accidental stale/foreign resource rather than packaging it.
    if output.exists():
        actual = set()
        for path in output.rglob('*'):
            no_links(path)
            if path.is_file():
                name = path.relative_to(output).as_posix()
                if name not in resources or path.read_bytes() != resources[name]:
                    raise ValueError('Foreign or changed generated Snes9x resource; use a clean output')
                actual.add(name)
    for name, raw in resources.items():
        put(output / name, raw)
    for role, path in sources.items():
        verify(path, INPUTS[role])
        if path.stat().st_mtime_ns != before[role]:
            raise ValueError('Snes9x input changed during preparation')
    return dict(status='local-candidate-only', files=len(resources), sourceCompanion=INPUTS['source']['filename'])


def import_pair(output, sources):
    sources = inspect_pair(**sources)
    for role, pin in INPUTS.items():
        put(output / PREFIX / pin['filename'], sources[role].read_bytes())
    verify_receipt(output, {PREFIX + pin['filename']: dict(path=PREFIX + pin['filename'], bytes=pin['bytes'], sha256=pin['sha256'])
                            for pin in INPUTS.values()})


def verify_receipt(root, records):
    expected = {PREFIX + pin['filename'] for pin in INPUTS.values()}
    if {name for name in records if name.startswith(PREFIX)} != expected:
        raise ValueError('Snapshot receipt requires the exact fixed Snes9x core/reference-source pair')
    paths = {}
    for role, pin in INPUTS.items():
        name = PREFIX + pin['filename']
        if any(records[name].get(k) != v for k, v in dict(path=name, bytes=pin['bytes'], sha256=pin['sha256']).items()):
            raise ValueError('Snapshot receipt cannot change a fixed Snes9x identity')
        paths[role] = root / name
        verify(paths[role], pin)
    return paths


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=('check', 'import', 'prepare'))
    p.add_argument('--core', type=Path)
    p.add_argument('--source', type=Path)
    p.add_argument('--cache', type=Path, default=ROOT / '.build-inputs')
    p.add_argument('--output', type=Path)
    args = p.parse_args()
    try:
        sources = inspect_pair(args.core, args.source, args.cache)
        if args.action == 'import':
            result = import_inputs(INPUTS, sources, args.cache)
        elif args.action == 'prepare':
            if args.output is None:
                raise ValueError('prepare requires --output')
            result = prepare(args.output, sources)
        else:
            license_resources(sources['source'])
            result = dict(checked=list(sources))
        print(json.dumps(dict(ok=True, **result), indent=2))
        return 0
    except (OSError, ValueError, KeyError, zipfile.BadZipFile) as exc:
        print('SFC fixed input preparation: ' + str(exc), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main())
