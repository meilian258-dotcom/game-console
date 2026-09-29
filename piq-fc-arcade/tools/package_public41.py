"""Create two first-install previews from a pinned successful public41 freeze.

No installation, publication, runtime download, user-data traversal or overwrite.
Every ZIP has an exact allowlist, CRC/SHA round-trip and fenced inputs.
"""
from __future__ import annotations
import argparse
import hashlib
import io
import json
from pathlib import Path
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
DOCS = ROOT / 'outputs/public41/docs'
BUILD = ROOT / 'piq-fc-arcade/build'


def sha(raw):
    return hashlib.sha256(raw).hexdigest().upper()


def require(ok, reason):
    if not ok:
        raise ValueError(reason)


def zip_bytes(entries):
    out = io.BytesIO()
    with zipfile.ZipFile(out, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
        for name, raw in sorted(entries.items()):
            require(not name.startswith('/') and '\\' not in name and ':' not in name and all(p not in ('', '.', '..') for p in name.split('/')), 'Unsafe member')
            info = zipfile.ZipInfo(name, (2026, 9, 14, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(info, raw)
    return out.getvalue()


def verify(raw, entries):
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        require(len(archive.namelist()) == len(set(archive.namelist())) and set(archive.namelist()) == set(entries), 'ZIP member set changed')
        for name, data in entries.items():
            require(archive.read(name) == data, 'ZIP roundtrip failed: ' + name)
        require(archive.testzip() is None, 'ZIP CRC failed')


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--freeze', type=Path, required=True)
    ap.add_argument('--witness-sha256', required=True)
    ap.add_argument('--write', action='store_true')
    args = ap.parse_args()
    base = args.freeze.resolve(strict=True)
    require(base.is_relative_to(BUILD.resolve()) and base != BUILD.resolve(), 'Expected frozen build child')
    witness_path = base / 'build-witness.json'
    witness_raw = witness_path.read_bytes()
    require(sha(witness_raw) == args.witness_sha256, 'Pinned build witness mismatch')
    witness = json.loads(witness_raw)
    require(witness['ok'] and witness['schema'] == 'game-console-public41-build-1' and not witness['published'], 'Not successful local candidate')
    inputs = {witness_path: witness_raw, Path(__file__).resolve(): Path(__file__).read_bytes()}
    jars = {}
    for kind in ('fc', 'sfc'):
        item = witness['mods'][kind]
        path = base / item['filename']
        require(path.parent == base and path.suffix == '.jar', 'Unexpected filename')
        raw = path.read_bytes()
        require(sha(raw) == item['sha256'] and len(raw) == item['bytes'], 'Frozen JAR mismatch')
        inputs[path] = raw
        jars[kind] = (path.name, raw)
    documents = {}
    for name in ('01-安装与更新.md', '04-发布边界与未决项.md'):
        path = DOCS / name
        documents[name] = inputs[path] = path.read_bytes()
    require(all(jars[k][0].encode() in documents['01-安装与更新.md'] for k in jars), 'Install guide names differ')
    candidates = []
    for label, selected in [('FC', ('fc',)), ('FC+SFC', ('fc', 'sfc'))]:
        entries = dict(documents)
        entries.update({'mods/' + jars[k][0]: jars[k][1] for k in selected})
        sums = ''.join(sha(raw) + '  ' + name + '\n' for name, raw in sorted(entries.items()))
        entries['SHA256SUMS.txt'] = sums.encode('utf-8')
        raw = zip_bytes(entries); verify(raw, entries)
        candidates.append((base / ('Game-Console-' + label + '-first-install-alpha41.zip'), raw, entries))
    receipt_path = base / 'first-install-verification.json'
    require(all(not p.exists() for p, _, _ in candidates) and not receipt_path.exists(), 'Output already exists; do not overwrite')
    require(all(p.read_bytes() == raw for p, raw in inputs.items()), 'Packaging input drift')
    receipt = dict(schema='game-console-public41-install-1', ok=True, written=args.write,
        build_witness_sha256=args.witness_sha256, candidate_only=True, published=False, installed=False,
        inputs={str(p.relative_to(ROOT)): sha(raw) for p, raw in inputs.items()},
        archives=[dict(filename=p.name, bytes=len(raw), sha256=sha(raw), entries={n: sha(v) for n, v in entries.items()}, mod_jars=sum(n.startswith('mods/') for n in entries)) for p, raw, entries in candidates])
    if args.write:
        for path, raw, entries in candidates:
            with path.open('xb') as stream:
                stream.write(raw)
            verify(path.read_bytes(), entries)
            require(sha(path.read_bytes()) == sha(raw), 'Final ZIP SHA mismatch')
        require(all(p.read_bytes() == raw for p, raw in inputs.items()), 'Packaging input drift after write')
        with receipt_path.open('x', encoding='utf-8') as stream:
            json.dump(receipt, stream, ensure_ascii=False, indent=2)
    print(json.dumps(receipt, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
