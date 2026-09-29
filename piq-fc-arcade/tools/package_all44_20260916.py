"""Package exact frozen FC44/Arcade0.1.0/SFC25/GBA8, without installing/building."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import json

ROOT = Path(__file__).resolve().parents[2]
LEGACY = ROOT / 'piq-fc-arcade/tools/package_all42_20260915.py'
LEGACY_SHA = 'CBAE1B43ABF15816FD50F8463A5EC22C2BE8D284DF940CB2DA4412BBA463EA76'
if hashlib.sha256(LEGACY.read_bytes()).hexdigest().upper() != LEGACY_SHA:
    raise ValueError('Verified packaging helper changed')
spec = importlib.util.spec_from_file_location('verified_all42', LEGACY)
old = importlib.util.module_from_spec(spec)
spec.loader.exec_module(old)
p = old.p
DOCS = ROOT / 'outputs/all44-20260916'
TARGET = ROOT / '制作Mod/03-街机模拟/方块电玩-全模组配套包-20260916-v1.zip'
RECEIPT = DOCS / 'package-verification.json'

# Reuse the audited checks and archive writer; no modification to older tools/artifacts.
old.DOCS = DOCS
old.MODS['fc'] = ('review-arcade44-v1/game_console-0.31.0-alpha.44.jar', 30796916,
    '7E9EA75C03D8FAECFB7D48EDDEB6D05C87C5681D445CE2A4D56662EB3DD0EE35')
old.MODS['native'] = ('review-arcade44-v1/game_console_arcade-0.1.0.jar', 124725245,
    'C43F42E65752311F496FD164614525213B80C1FAE9CD0CE0E243D5C7782FB1AA')
del old.WITNESSES['review-bundled42-v2']
old.WITNESSES['review-arcade44-v1'] = '1FE6119059E1B900B9BDC8A3D1D01DAAC3C42AD95F581CAA8A7253FAFA540B72'
old.VERSIONS.update(piq_fc_arcade='0.31.0-alpha.44', piq_native_arcade='0.1.0')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    if args.write:
        p.require(not p.safe(TARGET, False).exists() and not p.safe(RECEIPT, False).exists(),
                  'Output exists; never overwrite')
    inputs, entries, checks = old.collect()
    inputs.remember(Path(__file__))
    inputs.remember(LEGACY, {'bytes': LEGACY.stat().st_size, 'sha256': LEGACY_SHA})
    manifest = json.loads(entries['文件清单.json'])
    manifest.update(schema='game-console-all44-files-1', date='2026-09-16')
    entries['文件清单.json'] = p.encoded(manifest)
    entries['SHA256SUMS.txt'] = ''.join(
        f'{inputs.pin(source)["sha256"]}  {name}\n'
        for name, source in sorted(entries.items()) if name != 'SHA256SUMS.txt').encode('utf-8')
    inputs.unchanged()
    result = dict(schema='game-console-all44-package-1', ok=True, checks=checks, entries=len(entries),
                  compiled=False, installed=False, published=False, minecraft_started=False,
                  native_core_started=False, source_archives_copied=False)
    if args.write:
        result['package'] = p.archive(TARGET, entries, inputs)
        inputs.unchanged()
        result['inputs_unchanged'] = True
        result['inputs'] = {path.relative_to(ROOT).as_posix(): pin for path, pin in inputs.pins.items()}
        raw = p.encoded(result)
        with RECEIPT.open('xb') as out:
            out.write(raw)
        p.require(RECEIPT.read_bytes() == raw, 'Receipt readback failed')
    summary = {key: value for key, value in result.items() if key not in ('checks', 'package', 'inputs')}
    if args.write:
        summary['package'] = {key: value for key, value in result['package'].items() if key != 'entries'}
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == '__main__':
    main()
