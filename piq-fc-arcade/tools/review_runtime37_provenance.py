"""Read-only runtime/source identity audit; writes only a new JSON report, never executes a core."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
RELEASE = ROOT / '制作Mod/03-街机模拟'
SNAP = RELEASE / '街机本地同步-FC33-Native11-测试包-20260912-v1'
SOURCE = Path(str(SNAP) + '-核心源码')
GBA = ROOT / 'piq-gba/build/handheld-v3-3'
JNA_SHA = '34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6'


def sha(path):
    with path.open('rb') as file:
        return hashlib.file_digest(file, 'sha256').hexdigest().upper()


def checked(path, size, digest):
    path = path.resolve(strict=True)
    actual = sha(path)
    assert path.stat().st_size == size and actual == digest, str(path)
    return {'path': str(path), 'bytes': size, 'sha256': actual}


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report', required=True, type=Path)
    args = parser.parse_args(); report = args.report.resolve()
    assert not report.exists(), 'Do not overwrite prior audit'
    targets = [
        ('piq-native-arcade/runtime/mame_libretro.dll', RELEASE / 'PIQ原生街机/0.1.0-alpha.1/piq-native-arcade/runtime/mame_libretro.dll', 372431360, '6172A988AB67FE68F4177A6FC8FBB82619EB2044C330930F0F572F7B1EDC2301'),
        ('piq-native-arcade/runtime/piq-native-helper.jar', RELEASE / '自动控制与街机数据线-alpha30-测试包-20260912/piq-native-arcade/runtime/piq-native-helper.jar', 18571, '20F6F3028D76DAEB01212D1808BE90E35BFB5429D1E06153B7D8B32DD73E943C'),
        ('piq-native-arcade/runtime/jna-5.14.0.jar', SNAP / 'piq-native-arcade/runtime-snapshot-v1/jna-5.14.0.jar', 1878533, JNA_SHA),
        ('piq-native-arcade/runtime-snapshot-v1/piqneogeo_libretro.dll', SNAP / 'piq-native-arcade/runtime-snapshot-v1/piqneogeo_libretro.dll', 56494080, 'E8F435903332AC80468769604779295A6965046DC25D4706A583D14D91C35201'),
        ('piq-native-arcade/runtime-snapshot-v1/piq-snapshot-helper.jar', SNAP / 'piq-native-arcade/runtime-snapshot-v1/piq-snapshot-helper.jar', 43610, 'F175B7CB60B37A95E1F5B2ED5FB48CE066FC1B6AE1ECD8089B8E6F957EF76C97'),
        ('piq-native-arcade/runtime-snapshot-v1/jna-5.14.0.jar', SNAP / 'piq-native-arcade/runtime-snapshot-v1/jna-5.14.0.jar', 1878533, JNA_SHA),
        ('piq-gba/runtime/mgba_libretro.dll', GBA / 'piq-gba/runtime/mgba_libretro.dll', 2955998, 'D1BA96BC1AF23997D5C8003A6F6F8BE7ACBA9D770D4D42D14557AAEB469FA16B'),
        ('piq-gba/runtime/piq-gba-helper.jar', GBA / 'piq-gba/runtime/piq-gba-helper.jar', 20182, 'AF687B20AFD470992F9C02C80173356E20E9D9E98FABDDCF3800979F11A4B28C'),
        ('piq-gba/runtime/jna-5.14.0.jar', GBA / 'piq-gba/runtime/jna-5.14.0.jar', 1878533, JNA_SHA),
    ]
    files = {target: checked(path, size, digest) for target, path, size, digest in targets}
    pin_sources = [ROOT / 'piq-native-arcade/src/main/java/cn/piq/nativearcade/bridge/BridgeProtocol.java',
                   ROOT / 'piq-native-arcade/src/main/java/cn/piq/nativearcade/bridge/NativeProcessSession.java',
                   ROOT / 'piq-native-arcade/src/main/java/cn/piq/nativearcade/NativeSnapshotProfile.java',
                   ROOT / 'piq-gba/src/main/java/cn/piq/gba/bridge/GbaProcessSession.java']
    pin_text = '\n'.join(path.read_text(encoding='utf-8') for path in pin_sources)
    gba_mod = ROOT / 'piq-fc-arcade/build/review-audit34-v1/piq_gba-0.1.0-alpha.4.jar'
    with zipfile.ZipFile(gba_mod) as archive:
        props = archive.read('piq-gba-runtime.properties').decode()
    for entry in files.values():
        assert entry['sha256'] in pin_text + props, entry['path']
    mame_source = checked(SOURCE / 'source/mame-4fc9a931-source.zip', 234551698, '72D9472975173C1CE43201945F19D3866F9DA3EA51F67131C1134D66D7C6B22D')
    mgba_source = checked(GBA / 'licenses-and-source/mgba-source.tar.gz', 16149683, '396D749CCE8FE3358B29CBB1DB479B1816A151BD688EE45B1D241503CBC40243')
    patch_specs = [
        ('rtc/0001-save-rtc-interface-registers.patch', '08AF02DD556948DD79E3B40BEE0F99B7808BBB562147482BA588D48B41F26038'),
        ('rtc/0002-save-machine-time-origin.patch', '00B9795F163200F0969C50A3250021B47A6A780CDFD3D6D6534E27335AC8C844'),
        ('audio/audio-state-v1.patch', 'E44611C4B545A0430516C1CAC7D9556A1A75DCC649D976F508DF10B22ABEBD63'),
        ('audio/schema-guard-v1.patch', '16EA68F67802A287B28C9E7B771EB9B8C052DEEC5B2591B020BB0A6F082085C4'),
        ('audio/lua-inactive-timer-v1.patch', '16494D2F7CD80744DEF6A02C9477835A97A3BBB1FDCDE2A55626EFE025424793'),
    ]
    patches = [checked(SOURCE / 'source/lab/patches' / name, (SOURCE / 'source/lab/patches' / name).stat().st_size, digest) for name, digest in patch_specs]
    # The delivered source archive and historical successful build identify the same helper inputs.
    gba_build = json.loads((ROOT / 'piq-gba/build/preview-v4/build-and-process-qa.json').read_text(encoding='utf-8'))
    gba_inputs = {}
    gba_zip = GBA / 'licenses-and-source/piq-gba-source.zip'
    with zipfile.ZipFile(gba_zip) as archive:
        for name, expected in gba_build['source_sha256'].items():
            name = name.replace('\\', '/')
            if name.startswith('helper/src/') or name == 'src/main/java/cn/piq/gba/bridge/GbaProtocol.java':
                actual = hashlib.sha256(archive.read(name)).hexdigest().upper()
                assert actual == expected == sha(ROOT / 'piq-gba' / name), name
                gba_inputs[name] = actual
    own_native_inputs = {}
    for name in ['helper/src/main/java/cn/piq/nativearcade/bridge/NativeCoreWorker.java',
                 'helper/src/main/java/cn/piq/nativearcade/bridge/NativeStepWorker.java',
                 *['src/main/java/cn/piq/nativearcade/bridge/' + cls + '.java' for cls in ['BridgeProtocol', 'NativeInputPorts', 'NativeArcadeButtons', 'NativeStepProtocol']]]:
        original = SOURCE / 'source/piq-native-arcade' / name
        assert sha(original) == sha(ROOT / 'piq-native-arcade' / name), name
        own_native_inputs[name] = sha(original)
    notices = [SOURCE / 'licenses/MAME/COPYING', ROOT / 'piq-native-arcade/docs/licenses/MAME-GPL-2.0.txt',
               ROOT / 'piq-native-arcade/docs/licenses/JNA-LICENSE.txt', ROOT / 'piq-native-arcade/docs/licenses/JNA-Apache-2.0.txt',
               ROOT / 'piq-native-arcade/LICENSE', GBA / 'licenses-and-source/mgba-LICENSE']
    for path in notices: assert path.is_file(), str(path)
    data = {
        'schema': 'piq-runtime37-existing-provenance-1', 'ok': True, 'runtime_files': files,
        'total_install_bytes_with_three_jna_copies': sum(entry['bytes'] for entry in files.values()),
        'pin_sources_sha256': {str(path): sha(path) for path in pin_sources},
        'gba_mod_sha256': sha(gba_mod), 'gba_runtime_properties': props.strip(),
        'source_archives': {'mame_original': mame_source, 'mgba_original': mgba_source},
        'native_helper_sources_same_as_delivered_fc33_source': own_native_inputs,
        'gba_helper_sources_same_as_successful_build_and_delivered_source': gba_inputs,
        'snapshot_final_five_patches': patches,
        'license_notices': {str(path): sha(path) for path in notices},
        'source_delivery_reuse': {
            'mame_unchanged': 'source/mame-4fc9a931-source.zip + MAME notices. Official binary unmodified, commit prefix verified by historical API provenance, not reproducible-build proof.',
            'neogeo_modified': str(SOURCE) + ' (retain full original source, five final patches, helper sources, prerequisite/helper build records and build-README.md; not just DLL or upstream ZIP).',
            'gba': str(GBA / 'licenses-and-source') + ' (includes MPL-2.0 source, acquisition/source-verification, own helper source and GPL/JNA notices).',
            'jna': 'Existing JAR unchanged; existing notice explicitly offers Apache-2.0 OR LGPL-2.1; package already selects Apache-2.0.',
        },
        'scope': {'runtime_executed': False, 'downloaded': False, 'installed': False, 'binary_packaged': False, 'input_files_modified': False},
        'limits': ['Source/license inventory only; not legal advice or a malware/reproducible-build audit.', 'Historical records identify original binaries; this audit did not rerun old emulator gameplay tests.', 'No user ROM, BIOS or save content is required or read.'],
    }
    # Recheck complete runtime fence after reading source metadata.
    assert all(sha(Path(entry['path'])) == entry['sha256'] for entry in files.values())
    report.parent.mkdir(parents=True, exist_ok=True)
    with report.open('x', encoding='utf-8') as file: json.dump(data, file, ensure_ascii=False, indent=2)
    print(json.dumps({'ok': True, 'runtime_targets': len(files), 'report': str(report)}, ensure_ascii=False))


if __name__ == '__main__': main()
