"""Copy verified local artifacts into an immutable delivery folder and small two-mod bundle."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[2]
BUILD = ROOT / 'piq-fc-arcade/build/review-arcade44-v1'
DEST = ROOT / '制作Mod/03-街机模拟/方块电玩-FC44-街机正式同步'
GUIDE = ROOT / 'piq-fc-arcade/design/方块电玩-FC44-街机正式同步与安装.md'
ZIP = DEST.parent / '方块电玩-FC44-街机0.1.0配套包.zip'

def sha(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest().upper()

def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--witness-sha256', required=True)
    args = ap.parse_args()
    witness = BUILD / 'build-witness.json'
    if sha(witness) != args.witness_sha256.upper():
        raise ValueError('Build evidence changed')
    report = json.loads(witness.read_text(encoding='utf-8'))
    if report['schema'] != 'arcade44-build-1' or report['ok'] is not True:
        raise ValueError('Missing successful build')
    if DEST.exists() or ZIP.exists():
        raise ValueError('Refuse to overwrite prior delivery')
    files = [GUIDE]
    for kind, name in [('fc', 'game_console-0.31.0-alpha.44.jar'), ('native', 'game_console_arcade-0.1.0.jar')]:
        entry = report['mods'][kind]
        source = BUILD / name
        if entry['filename'] != name or entry['sha256'] != sha(source) or entry['bytes'] != source.stat().st_size:
            raise ValueError('Unverified artifact: ' + name)
        files.append(source)
    DEST.mkdir()
    for source in files:
        target = DEST / source.name
        with source.open('rb') as src, target.open('xb') as out:
            shutil.copyfileobj(src, out)
        if sha(source) != sha(target):
            raise ValueError('Copy verification failed')
    record = {'schema': 'arcade44-delivery-1', 'mods': report['mods'], 'tests': report['tests'],
              'witness_sha256': sha(witness), 'three_fc_wasm_unchanged': report['three_fc_wasm_unchanged'],
              'native_embedded_all_unchanged': report['native_embedded_all_unchanged'],
              'user_reported_previous_local_sync_working': True,
              'new_occupancy_minecraft_tested': False, 'installed': False, 'published': False}
    with (DEST / '版本与验证.json').open('x', encoding='utf-8') as out:
        json.dump(record, out, ensure_ascii=False, indent=2)
    manifest = {p.name: sha(p) for p in sorted(DEST.iterdir())}
    with (DEST / 'SHA256SUMS.txt').open('x', encoding='utf-8') as out:
        for name, digest in manifest.items():
            out.write(digest + '  ' + name + '\n')
    entries = sorted(DEST.iterdir())
    with zipfile.ZipFile(ZIP, 'x', compression=zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
        for p in entries:
            bundle.write(p, p.name)
    with zipfile.ZipFile(ZIP) as bundle:
        if len(bundle.namelist()) != len(entries) or bundle.testzip() is not None:
            raise ValueError('Bundle readback failure')
        for p in entries:
            with bundle.open(p.name) as source:
                if hashlib.file_digest(source, 'sha256').hexdigest().upper() != sha(p):
                    raise ValueError('Bundle content mismatch')
    result = {'ok': True, 'directory': str(DEST), 'zip': str(ZIP),
              'zip_bytes': ZIP.stat().st_size, 'zip_sha256': sha(ZIP),
              'files': {p.name: sha(p) for p in DEST.iterdir()}}
    with (ROOT / 'outputs/arcade44/delivery-v1.json').open('x', encoding='utf-8') as out:
        json.dump(result, out, ensure_ascii=False, indent=2)
    print(json.dumps(result, ensure_ascii=True))

if __name__ == '__main__':
    main()
