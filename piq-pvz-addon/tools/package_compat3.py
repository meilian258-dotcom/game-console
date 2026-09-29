"""Freeze the metadata-only FC70/71 compatibility patch, keeping historical artifacts intact."""
import hashlib
import json
import pathlib
import shutil
import tomllib
import xml.etree.ElementTree as ET
import zipfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
WORK = ROOT.parent
OUT = WORK / 'outputs/pvz-prototype3'
BASE = WORK / '制作Mod/03-街机模拟'
NAME = '方块电玩-PvZ兼容修复-prototype3-20260924'
DEST = BASE / NAME
JAR = 'game_console_pvz-0.1.0-prototype.3.jar'
old = BASE / '方块电玩-PvZ性能测试附属-prototype2-20260924/game_console_pvz-0.1.0-prototype.2.jar'
new = ROOT / 'build/libs' / JAR
fc70 = BASE / '方块电玩-通用街机Netplay-FC70-街机013-20260924/game_console-0.31.0-alpha.70.jar'
fc71 = BASE / '方块电玩-FC71-SFC放置崩溃修复-20260924/game_console-0.31.0-alpha.71.jar'
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()
assert not DEST.exists() and not DEST.with_suffix('.zip').exists(), 'Never overwrite frozen artifacts'
assert sha(old) == 'E33160D22C67190CB9FDAE381C104875D69BB4C05617F930EDCA05136FF79BEE'
assert sha(fc70) == '5C6065AE57F0168B6119759D88C592591257E9C10A346A430D8BC5741C5A9124'
assert sha(fc71) == '962F00D02C1EF22133CC5E7838229A06BA420A4B5F28CF357EE4CF05CD831FE7'
metadata = 'META-INF/neoforge.mods.toml'
with zipfile.ZipFile(old) as a, zipfile.ZipFile(new) as b:
    assert a.testzip() is None and b.testzip() is None
    assert set(a.namelist()) == set(b.namelist())
    changes = [n for n in a.namelist() if a.read(n) != b.read(n)]
    assert changes == [metadata], changes
    expected = a.read(metadata).replace(b'0.1.0-prototype.2', b'0.1.0-prototype.3').replace(
        b'[0.31.0-alpha.70,0.31.0-alpha.71)', b'[0.31.0-alpha.70,0.31.0-alpha.72)')
    assert b.read(metadata) == expected
    assert b.read(metadata) == (ROOT / 'build/resources/main' / metadata).read_bytes()
    info = tomllib.loads(b.read(metadata).decode())
    assert info['mods'][0]['version'] == '0.1.0-prototype.3'
    ranges = {d['modId']: d['versionRange'] for d in info['dependencies']['piq_pvz']}
    assert ranges['piq_fc_arcade'] == '[0.31.0-alpha.70,0.31.0-alpha.72)'
    assert not any(n.lower().endswith(('.pak', '.nes', '.sfc', '.gba', '.swf')) for n in b.namelist())
    assert not any(n.startswith('cn/piq/fcarcade/') for n in b.namelist())
    runtime = {n: hashlib.sha256(b.read(n)).hexdigest().upper() for n in (
        'core/pvz/piq-pvz-host.exe', 'core/pvz/pvz_libretro.dll')}
with zipfile.ZipFile(fc70) as a, zipfile.ZipFile(fc71) as b:
    assert set(a.namelist()) == set(b.namelist())
    fc_changes = [n for n in a.namelist() if a.read(n) != b.read(n)]
    assert set(fc_changes) == {'META-INF/MANIFEST.MF', metadata,
                             'cn/piq/fcarcade/config/GameConsoleAdminPolicy.class'}
    # In particular all addon-facing APIs and both Mixin targets are byte-identical.
tests = [ET.parse(p).getroot() for p in (ROOT/'build/test-results/test').glob('TEST-*.xml')]
assert sum(int(t.attrib['tests']) for t in tests) == 28
assert all(t.attrib[k] == '0' for t in tests for k in ('failures', 'errors', 'skipped'))
red = ET.parse(OUT/'red-original/TEST-cn.piq.pvz.MetadataCompatibilityTest.xml').getroot()
assert red.attrib['tests'] == '3' and red.attrib['failures'] == '1'
assert red.find('testcase[failure]').attrib['name'] == 'acceptsPlacementHotfixFc71()'
DEST.mkdir()
shutil.copy2(new, DEST/JAR)
for doc in ('README.md', 'THIRD_PARTY.md', 'VERIFICATION.md', 'LICENSE'):
    shutil.copy2(ROOT/doc, DEST/doc)
verification = dict(version='0.1.0-prototype.3', junitTests=28, failures=0, errors=0,
                    redRegression='FC71 rejected by original metadata', jarChangedEntries=changes,
                    dependencies=ranges, fc71ChangedEntries=fc_changes, runtime=runtime,
                    runtimeAndAssetsIdenticalToPrototype2=True,
                    performanceMeasurements='Historical v2 results only; no new FPS claim',
                    minecraftAcceptance='pending', installed=False)
(DEST/'verification.json').write_text(json.dumps(verification,ensure_ascii=False,indent=2),encoding='utf8')
source = DEST/'piq-pvz-addon-source.zip'
with zipfile.ZipFile(source, 'x', zipfile.ZIP_DEFLATED) as z:
    files = [ROOT/f for f in ('settings.gradle','gradle.properties','build.gradle','LICENSE','README.md','THIRD_PARTY.md','VERIFICATION.md')]
    files += [f for folder in ('src','native','tools') for f in (ROOT/folder).rglob('*')
              if f.is_file() and f.suffix not in ('.dll','.exe','.pyc') and '__pycache__' not in f.parts]
    files += [ROOT/'vendor/PvZ-Portable/src/SexyAppFramework/platform/libretro/libretro.h']
    for f in files:
        z.write(f, 'piq-pvz-addon/'+f.relative_to(ROOT).as_posix())
with zipfile.ZipFile(source) as z:
    assert z.testzip() is None
files = [dict(path=str(f),size=f.stat().st_size,sha256=sha(f)) for f in sorted(DEST.iterdir())]
(DEST/'SHA256SUMS.txt').write_text(''.join(f"{r['sha256']}  {pathlib.Path(r['path']).name}\n" for r in files),encoding='utf8')
archive = DEST.with_suffix('.zip')
with zipfile.ZipFile(archive, 'x', zipfile.ZIP_DEFLATED) as z:
    for f in sorted(DEST.iterdir()):
        z.write(f, NAME+'/'+f.name)
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    for f in DEST.iterdir():
        assert z.read(NAME+'/'+f.name) == f.read_bytes()
receipt = dict(verification=verification,files=files,
               zip=dict(path=str(archive),size=archive.stat().st_size,sha256=sha(archive)))
with (OUT/'delivery.json').open('x',encoding='utf8') as f:
    json.dump(receipt,f,ensure_ascii=False,indent=2)
print(json.dumps({'ok':True,'jar':str(DEST/JAR),'sha256':sha(DEST/JAR),'changed':changes,'tests':28},ensure_ascii=True))
