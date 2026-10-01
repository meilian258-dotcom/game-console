"""Stage pinned FBNeo state-v3 and its separate license; offline, no ROMs."""
from pathlib import Path
import argparse
import hashlib
import json
import shutil

ROOT = Path(__file__).resolve().parents[1]
VENDOR = ROOT / 'design/fbneo-study/jni-state-v3'
CORE_SHA = '3E0AB5DB5898E9E75E1704CAA703D6F4E4CB269C4F971AB498B434F22D18F885'
PINS = {
    'fbneo_libretro.dll': (61380608, CORE_SHA),
    'state-v3.patch': (None, '63A5094AA41231CA9D9FF37E5133B25DB43DD948AC8C13BB9EE4B750B57E5908'),
    'FBNeo-a251c76-jni-state-v3-source.zip': (29615611, '3C8A61CF1519E1F27DF3776822A1BE54573D640B671E6885088BBD60F41100DC'),
}


def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest().upper()


def validate(vendor=VENDOR):
    for name, (size, sha) in PINS.items():
        path = vendor / name
        if (size is not None and path.stat().st_size != size) or digest(path) != sha:
            raise ValueError('FBNeo fixed input mismatch: ' + name)
    receipt = json.loads((vendor / 'build.json').read_text('utf8'))
    for key, filename in [('coreSha256', 'fbneo_libretro.dll'), ('patchSha256', 'state-v3.patch'),
                          ('sourceBundleSha256', 'FBNeo-a251c76-jni-state-v3-source.zip')]:
        if receipt.get(key, '').upper() != PINS[filename][1]:
            raise ValueError('FBNeo build provenance mismatch: ' + key)
    if receipt.get('exitCode') != 0 or receipt.get('cleanBuild') is not True or receipt.get('noROMs') is not True:
        raise ValueError('FBNeo clean build receipt required')
    return receipt


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    receipt = validate()
    license_path = ROOT / 'design/fbneo-study/vendor/license.txt'
    if digest(license_path) != 'BB2369F1B75F42242968A78191B47EE90F85682224D3AD1EF63044E244B3E202':
        raise ValueError('FBNeo license identity mismatch')
    out = args.output.resolve()
    if not out.is_relative_to((ROOT / 'build').resolve()):
        raise ValueError('Output must remain within this project build directory')
    target = out / 'native-runtime/win-x64-fbneo-state-v3'
    notice = out / 'META-INF/piq-native/fbneo'
    target.mkdir(parents=True, exist_ok=True)
    notice.mkdir(parents=True, exist_ok=True)
    shutil.copy2(VENDOR / 'fbneo_libretro.dll', target / 'fbneo_libretro.dll')
    shutil.copy2(license_path, notice / 'LICENSE.txt')
    for name in ('state-v3.patch', 'build.json', 'README.md'):
        shutil.copy2(VENDOR / name, notice / name)
    receipt.update(source='https://github.com/libretro/FBNeo/tree/a251c76229f1637e433b93e29845039752771b6d',
                   modified=True, sourceBundle='FBNeo-a251c76-jni-state-v3-source.zip',
                   notice='Separate FBNeo non-commercial license; NOT GPL relicensing. Development candidate. '
                          'Distribute complete patched source alongside artifact. No ROM/BIOS. Old saves retained in old identity.')
    (notice / 'provenance.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf8')
    if digest(target / 'fbneo_libretro.dll') != CORE_SHA:
        raise ValueError('Staged core identity mismatch')


if __name__ == '__main__':
    main()
