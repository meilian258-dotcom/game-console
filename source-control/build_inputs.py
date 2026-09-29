"""Import/check pinned offline build inputs. No downloads, execution or overwrite.

python source-control/build_inputs.py check
python source-control/build_inputs.py import --from-root PATH
python source-control/build_inputs.py import --id ID --file PATH
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import shutil
import stat
import sys

ROOT = Path(__file__).resolve().parents[1]
LOCK = Path(__file__).with_name('build-inputs.json')


def no_links(path: Path):
    path = path.absolute()
    for part in (path, *path.parents):
        if part.exists() or part.is_symlink():
            info = part.lstat()
            if stat.S_ISLNK(info.st_mode) or getattr(info, 'st_file_attributes', 0) & 0x400:
                raise ValueError('Symlink/reparse point refused: ' + str(part))
    return path


def relative(value):
    if (not isinstance(value, str) or not value or '\\' in value or ':' in value
            or value.startswith('/') or any(p in ('', '.', '..') for p in value.split('/'))):
        raise ValueError('Unsafe input path')
    return PurePosixPath(value)


def load_lock(path=LOCK):
    obj = json.loads(path.read_text('utf8'))
    if obj.get('schemaVersion') != 1 or not isinstance(obj.get('inputs'), dict) or not obj['inputs']:
        raise ValueError('Invalid build input manifest')
    for ident, entry in obj['inputs'].items():
        if not re.fullmatch('[a-z0-9][a-z0-9-]{0,63}', ident): raise ValueError('Invalid input ID')
        name = relative(entry['filename'])
        if len(name.parts) != 1 or not re.fullmatch('[A-Za-z0-9_.-]+', entry['filename']):
            raise ValueError('Input filename must be a simple name')
        if not re.fullmatch('[0-9a-f]{64}', entry['sha256']): raise ValueError('Invalid SHA256')
        if type(entry['bytes']) is not int or not 0 < entry['bytes'] <= 512*1024*1024:
            raise ValueError('Invalid input size')
        relative(entry['legacyPath'])
    return obj['inputs']


def cache_path(cache, entry):
    return no_links(cache / entry['sha256'] / entry['filename'])


def verify(path, entry):
    path = no_links(path)
    if not path.is_file(): raise ValueError('Missing input: ' + str(path))
    before = path.stat()
    if before.st_size != entry['bytes']: raise ValueError('Wrong size: ' + str(path))
    with path.open('rb') as f: actual = hashlib.file_digest(f, 'sha256').hexdigest()
    after = path.stat()
    if actual != entry['sha256'] or (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
        raise ValueError('SHA256 mismatch or changed input: ' + str(path))


def import_inputs(entries, sources, cache):
    # Verify every selected source and existing destination before writing any bytes.
    for ident, entry in entries.items():
        verify(sources[ident], entry)
        dest = cache_path(cache, entry)
        if dest.exists(): verify(dest, entry)
    added = []; retained = []
    for ident, entry in entries.items():
        dest = cache_path(cache, entry)
        if dest.exists(): retained.append(ident); continue
        dest.parent.mkdir(parents=True, exist_ok=True)
        no_links(dest)
        # Exclusive creation, never truncate another import or an existing bad artifact.
        with sources[ident].open('rb') as src, dest.open('xb') as out:
            shutil.copyfileobj(src, out, length=1024*1024)
        verify(dest, entry)
        added.append(ident)
    return dict(added=added, retained=retained)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('action', choices=('check', 'import'))
    p.add_argument('--cache', type=Path, default=ROOT/'.build-inputs')
    p.add_argument('--id')
    p.add_argument('--from-root', type=Path)
    p.add_argument('--file', type=Path)
    args = p.parse_args()
    try:
        entries = load_lock()
        if args.id:
            if args.id not in entries: raise ValueError('Unknown input ID')
            entries = {args.id: entries[args.id]}
        cache = no_links(args.cache)
        if args.action == 'import':
            if bool(args.from_root) == bool(args.file) or (args.file and not args.id):
                raise ValueError('Import requires --from-root OR --id with --file')
            sources = {ident: args.file if args.file else args.from_root / entry['legacyPath']
                       for ident, entry in entries.items()}
            result = import_inputs(entries, sources, cache)
        else:
            if args.from_root or args.file: raise ValueError('check does not import files')
            errors=[]
            for ident, entry in entries.items():
                try: verify(cache_path(cache, entry), entry)
                except (ValueError, OSError) as exc: errors.append(dict(id=ident,error=str(exc)))
            if errors:
                print(json.dumps(dict(ok=False,errors=errors),ensure_ascii=True,indent=2)); return 1
            result = dict(checked=list(entries))
        print(json.dumps(dict(ok=True,cache=str(cache),**result),ensure_ascii=True,indent=2))
        return 0
    except (ValueError, OSError, KeyError) as exc:
        print('Build inputs: ' + str(exc), file=sys.stderr); return 1


if __name__ == '__main__': sys.exit(main())
