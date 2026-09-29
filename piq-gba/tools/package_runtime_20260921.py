"""Repackage pinned GBA8 runtime bytes only; no build, install, core execution or network."""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import io
import json
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
HELPER = ROOT / 'piq-fc-arcade/tools/package_brand40.py'
HELPER_SHA = '15322FE8D8C4A1BC99BE80D1B0D7808911C2DF77DA412FAC444AA563535D9EF4'
if hashlib.sha256(HELPER.read_bytes()).hexdigest().upper() != HELPER_SHA:
    raise ValueError('Existing packaging helper identity changed')
spec = importlib.util.spec_from_file_location('verified_runtime_pack_helpers', HELPER)
p = importlib.util.module_from_spec(spec)
spec.loader.exec_module(p)
SOURCE = ROOT / '制作Mod/03-街机模拟/方块电玩-全模组配套包-20260916-v1.zip'
SOURCE_PIN = dict(bytes=159636619, sha256='CDAF4CE7FCD7F9BE009452D25405BCE56D22B23AB1FE223254BE7A500382522A')
DOCS = ROOT / 'outputs/gba-runtime-20260921'
TARGET = ROOT / '制作Mod/03-街机模拟/方块电玩-GBA运行库-Windows-x64-20260921.zip'
RECEIPT = DOCS / 'package-verification.json'
RUNTIME = {
    'piq-gba/runtime/mgba_libretro.dll': dict(bytes=2955998, sha256='D1BA96BC1AF23997D5C8003A6F6F8BE7ACBA9D770D4D42D14557AAEB469FA16B'),
    'piq-gba/runtime/piq-gba-helper.jar': dict(bytes=20182, sha256='AF687B20AFD470992F9C02C80173356E20E9D9E98FABDDCF3800979F11A4B28C'),
    'piq-gba/runtime/jna-5.14.0.jar': dict(bytes=1878533, sha256='34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6'),
}
LICENSES = {
    'licenses/gba-helper/PIQ-GPL-3.0.txt',
    'licenses/runtime/mgba/mgba-LICENSE',
    'licenses/runtime/native/JNA-LICENSE.txt',
    'licenses/runtime/native/JNA-Apache-2.0.txt',
    'licenses/runtime/native/COPYING',
}


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--write', action='store_true')
    args = parser.parse_args()
    if args.write:
        p.require(not p.safe(TARGET, False).exists() and not p.safe(RECEIPT, False).exists(),
                  'Never overwrite an existing archive or receipt')
    inputs = p.Inputs()
    inputs.remember(Path(__file__))
    inputs.remember(HELPER, dict(bytes=HELPER.stat().st_size, sha256=HELPER_SHA))
    inputs.remember(SOURCE, SOURCE_PIN)
    source_inventory = p.inventory(SOURCE, 256 * 1024**2)
    p.require({name: source_inventory[name] for name in RUNTIME} == RUNTIME, 'Runtime source mismatch')
    entries = {}
    with zipfile.ZipFile(SOURCE) as source:
        for name in sorted(set(RUNTIME) | LICENSES):
            raw = source.read(name)
            p.require(inputs.pin(raw) == source_inventory[name], 'Source entry changed: ' + name)
            if name.endswith('.jar'):
                with zipfile.ZipFile(io.BytesIO(raw)) as jar:
                    p.require(jar.testzip() is None, 'Nested runtime JAR CRC mismatch: ' + name)
            p.add(entries, name, raw)
    p.add(entries, '安装说明.md', inputs.remember(DOCS / '安装说明.md'))
    manifest = dict(schema='gba8-runtime-only-1', compatible_gba='0.1.0-alpha.8',
                    platform='Windows x64', java=21, install_root='instance root, alongside mods',
                    source_archive=dict(path=SOURCE.relative_to(ROOT).as_posix(), **SOURCE_PIN),
                    files={name: inputs.pin(value) for name, value in sorted(entries.items())},
                    limitations=['Existing runtime bytes, not a newly compiled core.',
                                 'No ROM, BIOS, MOD JAR, configuration or save files.',
                                 'FC64 combination and Minecraft runtime not tested in this task.'])
    p.add(entries, '文件清单.json', p.encoded(manifest))
    p.add(entries, 'SHA256SUMS.txt', ''.join(
        f'{inputs.pin(value)["sha256"]}  {name}\n' for name, value in sorted(entries.items())).encode('utf-8'))
    p.require(len(entries) == 11 and {name for name in entries if name.startswith('piq-gba/')} == set(RUNTIME),
              'Unexpected output files')
    inputs.unchanged()
    result = dict(schema='gba8-runtime-only-verification-1', ok=True, entries=len(entries),
                  runtime_files=RUNTIME, license_count=len(LICENSES), nested_jars_crc_verified=True,
                  compiled=False, installed=False, published=False, native_core_started=False,
                  minecraft_started=False)
    if args.write:
        result['package'] = p.archive(TARGET, entries, inputs)
        inputs.unchanged()
        result['inputs_unchanged'] = True
        result['inputs'] = {path.relative_to(ROOT).as_posix(): pin for path, pin in inputs.pins.items()}
        receipt = p.encoded(result)
        with p.safe(RECEIPT, False).open('xb') as output:
            output.write(receipt)
        p.require(RECEIPT.read_bytes() == receipt, 'Receipt readback failed')
    summary = {key: value for key, value in result.items() if key not in ('package', 'inputs', 'runtime_files')}
    if args.write:
        summary['package'] = {key: value for key, value in result['package'].items() if key != 'entries'}
    print(json.dumps(summary, ensure_ascii=False))


if __name__ == '__main__':
    main()
