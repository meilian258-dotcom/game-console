"""Freeze only explicit addon artifacts; never package test data, screenshots or saves."""
import hashlib, json, pathlib, shutil, zipfile, xml.etree.ElementTree as ET
ROOT = pathlib.Path(__file__).resolve().parents[1]
WORK=ROOT.parent
OUT=WORK/'outputs/pvz-prototype2'
NAME='方块电玩-PvZ性能测试附属-prototype2-20260924'
DEST=WORK/'制作Mod/03-街机模拟'/NAME
JAR='game_console_pvz-0.1.0-prototype.2.jar'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest().upper()
assert not DEST.exists(),'Existing freeze is immutable; inspect before retry'
tests=[ET.parse(p).getroot() for p in (ROOT/'build/test-results/test').glob('TEST-*.xml')]
assert sum(int(t.attrib['tests']) for t in tests)==25
assert all(t.attrib[k]=='0' for t in tests for k in ('failures','errors','skipped'))
result=json.loads((OUT/'functional/restore.json').read_text())
assert result['exit']==0 and result['frames']>1000 and result['nonzero_pcm']>1000
perf=json.loads((OUT/'paired-performance.json').read_text())
assert perf['v2']['jarSha']==sha(ROOT/'build/libs'/JAR)
assert perf['v2']['metrics']['fps']>50 and perf['v2']['metrics']['fps']>1.5*perf['v1']['metrics']['fps']
base=WORK/'制作Mod/03-街机模拟/方块电玩-通用街机Netplay-FC70-街机013-20260924/game_console-0.31.0-alpha.70.jar'
assert sha(base)=='5C6065AE57F0168B6119759D88C592591257E9C10A346A430D8BC5741C5A9124'
with zipfile.ZipFile(ROOT/'build/libs'/JAR) as z:
    assert z.testzip() is None
    names=z.namelist()
    assert not any(n.lower().endswith(('.pak','.nes','.sfc','.gba','.swf')) for n in names)
    assert all(n in names for n in ('META-INF/LICENSE','META-INF/THIRD_PARTY.md','META-INF/pvz/LICENSE','META-INF/pvz/SexyAppFramework-LICENSE','core/pvz/piq-pvz-host.exe','core/pvz/pvz_libretro.dll'))
    assert not any(n.startswith('cn/piq/fcarcade/') for n in names)
DEST.mkdir()
shutil.copy2(ROOT/'build/libs'/JAR,DEST/JAR)
for doc in ('README.md','THIRD_PARTY.md','VERIFICATION.md','LICENSE'):shutil.copy2(ROOT/doc,DEST/doc)
source=DEST/'piq-pvz-addon-source.zip'
with zipfile.ZipFile(source,'x',zipfile.ZIP_DEFLATED) as z:
    files=[ROOT/f for f in ('settings.gradle','gradle.properties','build.gradle','LICENSE','README.md','THIRD_PARTY.md','VERIFICATION.md')]
    files+=[f for folder in ('src','native','tools') for f in (ROOT/folder).rglob('*') if f.is_file() and f.suffix not in ('.dll','.exe','.pyc') and '__pycache__' not in f.parts]
    files+=[ROOT/'vendor/PvZ-Portable/src/SexyAppFramework/platform/libretro/libretro.h']
    for f in files:z.write(f,'piq-pvz-addon/'+f.relative_to(ROOT).as_posix())
with zipfile.ZipFile(source) as z:assert z.testzip() is None
receipt={'status':'performance prototype; Minecraft v2 acceptance pending','fc70Unchanged':True,'junitTests':25,'performance':perf,'files':[{'path':str(f),'size':f.stat().st_size,'sha256':sha(f)} for f in sorted(DEST.iterdir())]}
(DEST/'SHA256SUMS.txt').write_text(''.join(f"{r['sha256']}  {pathlib.Path(r['path']).name}\n" for r in receipt['files']),encoding='utf-8')
archive=DEST.with_suffix('.zip')
with zipfile.ZipFile(archive,'x',zipfile.ZIP_DEFLATED) as z:
    for f in sorted(DEST.iterdir()):z.write(f,NAME+'/'+f.name)
with zipfile.ZipFile(archive) as z:
    assert z.testzip() is None
    for f in DEST.iterdir():assert z.read(NAME+'/'+f.name)==f.read_bytes()
receipt['zip']={'path':str(archive),'size':archive.stat().st_size,'sha256':sha(archive)}
(OUT/'delivery.json').write_text(json.dumps(receipt,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(receipt,ensure_ascii=True))
