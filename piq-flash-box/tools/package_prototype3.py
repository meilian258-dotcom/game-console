"""Freeze only this addon, its pinned runtime and corresponding sources. No game files."""
import hashlib
import json
import shutil
import xml.etree.ElementTree as ET
import zipfile
from datetime import datetime
from pathlib import Path

PROJECT = Path(__file__).resolve().parents[1]
ROOT = PROJECT.parent
OUT = ROOT / 'outputs/flash-box-prototype3'
NAME = '方块电玩-Flash播放盒-原型3-20260918'
DEST = ROOT / '制作Mod/03-街机模拟' / NAME
ARCHIVE = DEST.with_suffix('.zip')
JAR = PROJECT / 'build/libs/game_console_flash_box-0.1.0-prototype.3.jar'
RUNTIME = PROJECT / 'runtime/publish-0.1.2'

def sha(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest().upper()

def main():
    if DEST.exists() or ARCHIVE.exists():
        raise RuntimeError('Refuse to overwrite an existing delivery')
    manifest = json.loads((RUNTIME / 'runtime-manifest.json').read_text(encoding='utf-8'))
    assert manifest['version'] == '0.1.2'
    with zipfile.ZipFile(JAR) as jar:
        assert jar.testzip() is None
        expected = ''.join(f"{f['sha256']}  {f['path']}\n" for f in manifest['files'])
        assert jar.read('flash-runtime.sha256').decode('utf-8') == expected
        assert b'0.1.0-prototype.3' in jar.read('META-INF/neoforge.mods.toml')
        assert not any(n.lower().endswith(('.swf','.nes','.sfc','.smc','.gba')) for n in jar.namelist())
    for f in manifest['files']:
        path = RUNTIME / f['path']
        assert path.stat().st_size == f['size'] and sha(path) == f['sha256']
    tests = [ET.parse(p).getroot() for p in (PROJECT / 'build/test-results/test').glob('TEST-*.xml')]
    test_count = sum(int(t.get('tests')) for t in tests)
    assert test_count >= 32
    assert all(int(t.get('failures')) == 0 and int(t.get('errors')) == 0 and int(t.get('skipped')) == 0 for t in tests)
    java = json.loads((OUT / 'java-smoke/report.json').read_text(encoding='utf-8'))
    assert java['ok'] is True
    assert java['receipt']['jarSha256'] == sha(JAR)
    assert java['receipt']['runtimeManifestSha256'] == sha(RUNTIME / 'runtime-manifest.json')
    performance = json.loads((OUT / 'runtime-performance.json').read_text(encoding='utf-8'))
    assert performance['ok'] is True
    assert performance['helperVersion'] == manifest['version']
    assert performance['runtime']['manifestSha256'] == sha(RUNTIME / 'runtime-manifest.json')
    assert performance['runtime']['helperDllSha256'] == sha(RUNTIME / 'FlashBox.Helper.dll')
    verification = {
        'ok': True, 'version': '0.1.0-prototype.3', 'at': datetime.now().astimezone().isoformat(),
        'jarSha256': sha(JAR), 'runtimeManifestSha256': sha(RUNTIME / 'runtime-manifest.json'),
        'unitTests': {'tests': test_count, 'failed': 0, 'skipped': 0}, 'javaHelperSmoke': java,
        'helperPerformance': performance,
        'userFeedbackOnPrototype2': 'Menu and preview now clear; TV displays game; user reports stutter, screenshot shows receiving 8 FPS; requested world-view control.',
        'notVerified': ['prototype3 Minecraft world control, key/mouse isolation, ESC release and focus/menu safety', 'audio listening',
            'complete playthrough', 'cross-computer multiplayer', 'deterministic lockstep', 'save/restore'],
        'installed': False, 'published': False, 'stable': False,
        'limitations': ['desktop test layout preferred; flush wall vertically below may have no cable mesh',
            'system audio, no Minecraft volume/distance', 'no saved Flash progress',
            'Windows x64/.NET Desktop 6/WebView2 prototype', 'capture throughput is measured separately from simulation/network frame rate']
    }
    DEST.mkdir(parents=True)
    (DEST / 'mods').mkdir()
    shutil.copy2(JAR, DEST / 'mods' / JAR.name)
    shutil.copytree(RUNTIME, DEST / 'piq-flash-box/runtime')
    shutil.copy2(PROJECT / 'README.md', DEST / '先读我-安装与边界.md')
    source = DEST / 'source/piq-flash-box'
    source.mkdir(parents=True)
    for name in ['README.md','LICENSE.md','COPYING','build.gradle','settings.gradle','gradle.properties']:
        shutil.copy2(PROJECT / name, source / name)
    shutil.copytree(PROJECT / 'src', source / 'src')
    shutil.copytree(PROJECT / 'tools', source / 'tools', ignore=shutil.ignore_patterns('__pycache__'))
    (source / 'runtime').mkdir()
    for name in ['Program.cs','FlashBox.Helper.csproj','README.md','package_runtime.py','protocol_smoke.py','smoke.py','performance_smoke.py','protocol_performance_smoke.py','summarize_performance.py','summarize_performance3.py']:
        shutil.copy2(PROJECT / 'runtime' / name, source / 'runtime' / name)
    shutil.copytree(PROJECT / 'runtime/web', source / 'runtime/web')
    # Runtime vendor bytes and licenses are already distributed above; never take private-test/bin recursively.
    (source / 'runtime/VENDOR-LOCATION.txt').write_text(
        'Pinned vendor files and licenses are in ../../../piq-flash-box/runtime/vendor.\n'
        'Copy that vendor directory here for a source rebuild. Java build also needs matching FC56 and Gradle9.2.1.\n',encoding='utf-8')
    (DEST / 'verification.json').write_text(json.dumps(verification,ensure_ascii=False,indent=2),encoding='utf-8')
    files = sorted(p for p in DEST.rglob('*') if p.is_file())
    for path in files:
        rel=path.relative_to(DEST).as_posix()
        assert not any(x in rel.lower() for x in ['private-test','the-forest-temple.swf','ebwebview'])
        assert path.suffix.lower() not in {'.swf','.nes','.sfc','.smc','.gba','.rom'}
    sums={p.relative_to(DEST).as_posix():sha(p) for p in files}
    (DEST / 'SHA256SUMS.txt').write_text(''.join(f'{h}  {p}\n' for p,h in sums.items()),encoding='utf-8')
    with zipfile.ZipFile(ARCHIVE,'x',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        for p in sorted(DEST.rglob('*')):
            if p.is_file(): z.write(p,p.relative_to(DEST).as_posix())
    with zipfile.ZipFile(ARCHIVE) as z:
        assert z.testzip() is None
        assert len(z.namelist()) == len(sums)+1
        for path,digest in sums.items():
            assert hashlib.sha256(z.read(path)).hexdigest().upper() == digest
    receipt={'ok':True,'archive':str(ARCHIVE),'archiveSha256':sha(ARCHIVE),'archiveBytes':ARCHIVE.stat().st_size,
        'directory':str(DEST),'jar':str(DEST/'mods'/JAR.name),'jarSha256':sha(JAR),
        'entries':len(sums)+1,'runtimeFiles':len(manifest['files']),'sourceIncluded':True,
        'gameFilesIncluded':False,'installed':False,'published':False}
    (OUT/'delivery.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps(receipt,ensure_ascii=False,indent=2))

if __name__ == '__main__':
    main()
