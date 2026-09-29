"""Freeze final local delivery metadata without replacing any tested executable or prior ZIP."""
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import shutil
import zipfile

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT.parent / '制作Mod/03-街机模拟/PIQ原生街机/0.1.0-alpha.1'
PINS = {
    'mods/piq_native_arcade-0.1.0-alpha.1.jar': 'CEE8B775B57C34781EA5695D4C4991004AA4D4431CA583FB8166C310C188F4BC',
    'mods/piq_fc_arcade-0.31.0-alpha.14.jar': 'BC17E1B483FAD115DAE156C6ECB56D3911915BC31B3C44058D56F3D61002E68A',
    'piq-native-arcade/runtime/mame_libretro.dll': '6172A988AB67FE68F4177A6FC8FBB82619EB2044C330930F0F572F7B1EDC2301',
    'piq-native-arcade/runtime/jna-5.14.0.jar': '34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6',
    'piq-native-arcade/runtime/piq-native-helper.jar': 'D1A360A8C300FDBC402A1CEFCF63314A045E0C1D9183E7BABA6E40EF9B33F75D',
    'piq-native-arcade/diagnostic/invaders.zip': '10E62BBACBF6E79EFB6DB6F7A65EA1EDFB65653EAB60B41E80DBCB4673A5276C',
    'piq-native-arcade-0.1.0-alpha.1-source.zip': '0F69EF49D3FE32E6AA585304D7E062A3D1B85384A8481525B2CB41FE3288612A',
    'final-independent-audit.json': '475D0E28F79984CFD9A60B1E59DF1F2199EDF8BEFAA1CDB1207EE776EF06680B',
}

def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for data in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(data)
    return h.hexdigest().upper()

def copy_new(source, target):
    if target.exists():
        raise FileExistsError(target)
    target.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(source, target)
    assert sha(source) == sha(target)

def main():
    final = OUT / 'FINAL_CHECKSUMS.json'
    source_zip = OUT / 'piq-native-arcade-0.1.0-alpha.1-source-v2.zip'
    assert not final.exists() and not source_zip.exists(), 'No frozen output overwrite'
    for name, expected in PINS.items():
        assert sha(OUT / name) == expected, name
    independent = json.loads((OUT / 'final-independent-audit.json').read_text(encoding='utf-8'))
    smoke = json.loads((OUT / 'final-delivered-runtime-smoke.json').read_text(encoding='utf-8'))
    lifecycle = json.loads((OUT / 'final-lifecycle-test/audit.json').read_text(encoding='utf-8'))
    assert independent['ok'] is True
    assert smoke['status'] == 'passed-actual-delivered-parent-and-helper'
    assert 'PASS=24 ' in smoke['functional'] and smoke['exact_child_no_longer_alive'] is True
    assert lifecycle['parent_jar_sha256'] == PINS['mods/piq_native_arcade-0.1.0-alpha.1.jar']
    assert lifecycle['helper']['sha256'] == PINS['piq-native-arcade/runtime/piq-native-helper.jar']
    assert 'PASS=24 ' in lifecycle['functional_24_checks']['stdout']
    assert lifecycle['actual_parent_jvm_shutdown']['exact_child_exited'] is True
    assert 'TIMEOUT_REAP_PASS' in lifecycle['15_second_stall_reap']['stdout']
    assert sha(ROOT / 'README.md') == sha(OUT / 'README.md')
    copy_new(ROOT / 'docs/licenses/JNA-Apache-2.0.txt', OUT / 'LICENSES/JNA-Apache-2.0.txt')
    copy_new(ROOT / 'docs/runtime-provenance.md', OUT / 'runtime-provenance.md')
    with zipfile.ZipFile(source_zip, 'x', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(ROOT.rglob('*')):
            if not path.is_file() or path.is_symlink():
                continue
            rel = path.relative_to(ROOT)
            if any(part in ('build', '.gradle', '__pycache__') for part in rel.parts):
                continue
            if rel.parts[0] not in ('src', 'helper', 'tools', 'docs', 'gradle') and len(rel.parts) != 1:
                continue
            archive.write(path, 'piq-native-arcade/' + rel.as_posix())
    with zipfile.ZipFile(source_zip) as archive:
        assert archive.testzip() is None
        for name in ('tools/verify_native_delivery.py', 'tools/native-diagnostic-original-manifest.json',
                     'docs/licenses/JNA-Apache-2.0.txt', 'docs/runtime-provenance.md'):
            assert 'piq-native-arcade/' + name in archive.namelist(), name
    for name, expected in PINS.items():
        assert sha(OUT / name) == expected, name
    report = {
        'status': 'local-technical-preview-final-audits-passed-not-minecraft-gameplay-tested',
        'generated_at_utc': datetime.now(timezone.utc).isoformat(),
        'frozen_executables_unchanged': True,
        'junit': {'tests': 30, 'failures': 0, 'skipped': 0},
        'python_tests': 33,
        'independent_final_jar_assertions': 118,
        'real_final_pair_tests': ['24 actual MAME checks', 'orderly parent shutdown child reaping', '15-second no-frame timeout reaping'],
        'latest_own_source': source_zip.name,
        'historical_receipt': 'CHECKSUMS.json is preliminary and retained, not the final documentation checksum set.',
        'upstream_source_boundary': 'Own mod/helper source included; full corresponding MAME source is not bundled. Local preview only, not a completed public redistribution package.',
        'not_performed': ['Minecraft gameplay', 'commercial ROM compatibility', 'HMCL installation', 'server upload or publish', 'network multiplayer'],
        'files': {p.relative_to(OUT).as_posix(): {'bytes': p.stat().st_size, 'sha256': sha(p)}
                  for p in sorted(OUT.rglob('*')) if p.is_file()},
    }
    with final.open('x', encoding='utf-8') as output:
        json.dump(report, output, ensure_ascii=False, indent=2)
        output.write('\n')
    print(json.dumps({'status': report['status'], 'files': len(report['files']),
                      'final_manifest_sha256': sha(final), 'latest_source_sha256': sha(source_zip),
                      'latest_source_bytes': source_zip.stat().st_size}, ensure_ascii=False, indent=2))

if __name__ == '__main__':
    main()
