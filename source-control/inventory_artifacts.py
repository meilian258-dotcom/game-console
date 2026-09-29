"""Record selected legacy build inputs without copying or distributing binaries.

Run at migration/review time with --output NEW.json; never downloads anything.
This is a key-input inventory, not a complete dependency resolver.
"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
INPUTS = {
    'piq-fc-arcade/native/libretro/jna-5.14.0.jar': 'FC libretro worker classpath; native/libretro provenance',
    'piq-fc-arcade/native/libretro/windows-x64/mesen_libretro.dll': 'FC process core; native/libretro provenance',
    'piq-fc-arcade/native/libretro/linux-x64/mesen_libretro.so': 'FC Linux process core; native/libretro provenance',
    'piq-fc-arcade/native/libretro-jni/dist/piq-libretro-jni.dll': 'Common JNI bridge; native/libretro-jni/README.md and build_native.py',
    'outputs/jni-unified-20260929/crc-audit/fixed-native/piq-retroarch.exe': 'FC build.gradle prepareNetplayRuntime; historical output dependency',
    'piq-fc-arcade/src/main/resources/core/libretro-jni-netplay/windows-x64/mesen_piq_jni_netplay_r1.dll': 'FC76.24 fixed JNI Netplay core; corresponding source/build instructions required',
    'piq-fc-arcade/src/main/resources/core/nes_zapper_v1.wasm': 'Legacy WASM core retained; not a game ROM',
    'piq-fc-arcade/src/main/resources/core/nes_rust_wasm_bg.wasm': 'Legacy WASM core retained; not a game ROM',
    'piq-fc-arcade/src/main/resources/core/nes_mapper19_v1.wasm': 'Legacy WASM core retained; not a game ROM',
    'piq-native-arcade/design/fbneo-study/pgm-cycle75/fbneo_libretro.dll': 'FBNeo core; adjacent patch/build.json and vendor/license.txt',
    'piq-fc-arcade/build/runtime-pack37-v1/piq-runtime-pack-v1.zip': 'Native arcade embeddedRuntimeArchive; override supported by build.gradle',
    'piq-sfc-home/src/main/resources/core/sfc-libretro/windows-x64/mesen-s_libretro.dll': 'SFC libretro core; component provenance/license files',
    'piq-gba/runtime/incoming-mgba-20260911-v1/mgba_libretro.dll': 'GBA fixed core; component runtime provenance',
    'piq-md-home/src/main/resources/core/windows-x64/blastem_libretro.dll': 'MD core; component tools and third-party license/provenance',
    'piq-pvz-addon/vendor/dist/cores/pvz_libretro.dll': 'PvZ core only; game content/main.pak is excluded',
    'piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.76.22.jar': 'Legacy compile input for SFC/native arcade/computer/PvZ; not an approved release claim',
    'piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.76.24.jar': 'MD default compile input; -PgameConsoleJar can override',
    'piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.61.jar': 'Historical Flash compile input; verify component build.gradle before use',
    'piq-sfc-arcade/build/libs/piq_sfc_arcade-0.2.0-alpha.9.jar': 'SFC home legacy core dependency',
}


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--output', type=Path, required=True)
    args = p.parse_args()
    rows = []
    for name, purpose in INPUTS.items():
        path = ROOT / name
        row = dict(path=name, purpose=purpose, exists=path.is_file())
        if path.is_file():
            before = path.stat()
            with path.open('rb') as f: row['sha256'] = hashlib.file_digest(f, 'sha256').hexdigest()
            after = path.stat()
            if (before.st_size, before.st_mtime_ns) != (after.st_size, after.st_mtime_ns):
                raise RuntimeError('Artifact changed during inventory: ' + name)
            row['bytes'] = before.st_size
        rows.append(row)
    result = dict(schemaVersion=1, scope='selected legacy build inputs; not a complete clean-build lockfile',
                  binariesIncludedInGit=False, entries=rows)
    with args.output.open('x', encoding='utf8') as f:
        json.dump(result, f, ensure_ascii=False, indent=2); f.write('\n')
    print(json.dumps(dict(entries=len(rows), present=sum(r['exists'] for r in rows), output=str(args.output))))


if __name__ == '__main__': main()
