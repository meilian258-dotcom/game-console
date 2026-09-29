"""Create a new local preview folder; no installation, network access, or replacement."""
from pathlib import Path
import hashlib,json,shutil,zipfile,xml.etree.ElementTree as ET
from build_native_helper import build,JNA,ROOT
from make_diagnostic_rom import make as diagnostic_rom

WORK=ROOT.parent
OUT=WORK/'制作Mod/03-街机模拟/PIQ原生街机/0.1.0-alpha.1'
def sha(p):
    h=hashlib.sha256()
    with p.open('rb') as f:
        for data in iter(lambda:f.read(1024*1024),b''):h.update(data)
    return h.hexdigest().upper()
def copy(source,target):
    assert source.is_file(),source
    assert not target.exists(),target
    target.parent.mkdir(parents=True,exist_ok=True);shutil.copyfile(source,target);assert sha(source)==sha(target)
def main():
    assert not OUT.exists(),'Refusing to overwrite a prior local delivery'
    jar=ROOT/'build/libs/piq_native_arcade-0.1.0-alpha.1.jar'
    fc=WORK/'制作Mod/03-街机模拟/PIQ-FC街机/piq_fc_arcade-0.31.0-alpha.14.jar'
    core=WORK/'piq-native-arcade-poc/vendor/mame_libretro.dll'
    assert sha(fc)=='BC17E1B483FAD115DAE156C6ECB56D3911915BC31B3C44058D56F3D61002E68A'
    assert sha(core)=='6172A988AB67FE68F4177A6FC8FBB82619EB2044C330930F0F572F7B1EDC2301'
    assert sha(JNA)=='34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6'
    suites=[ET.parse(p).getroot() for p in (ROOT/'build/test-results/test').glob('TEST-*.xml')]
    count=sum(int(s.attrib['tests']) for s in suites);assert count>=30
    assert all(int(s.attrib.get(k,0))==0 for s in suites for k in ('errors','failures','skipped'))
    licenses=list((ROOT/'docs/licenses').glob('*'));assert len([p for p in licenses if p.is_file()])>=3,'Missing notices'
    with zipfile.ZipFile(jar) as z:
        assert z.testzip() is None and len(z.namelist())==len(set(z.namelist()))
        assert 'version="0.1.0-alpha.1"' in z.read('META-INF/neoforge.mods.toml').decode()
        assert not any(n.endswith(('.dll','.zip','.wasm')) or n.startswith('com/sun/jna/') for n in z.namelist())
        assert 'cn/piq/nativearcade/bridge/NativeProcessSession.class' in z.namelist()
        assert 'cn/piq/nativearcade/bridge/NativeCoreWorker.class' not in z.namelist()
        for n in z.namelist():
            if n.endswith('.class'):assert int.from_bytes(z.read(n)[6:8],'big')==65
    OUT.mkdir(parents=True)
    copy(jar,OUT/'mods'/jar.name);copy(fc,OUT/'mods'/fc.name)
    runtime=OUT/'piq-native-arcade/runtime';runtime.mkdir(parents=True)
    copy(core,runtime/'mame_libretro.dll');copy(JNA,runtime/'jna-5.14.0.jar')
    build(runtime/'piq-native-helper.jar')
    diagnostic=OUT/'piq-native-arcade/diagnostic/invaders.zip';diagnostic.parent.mkdir(parents=True);diagnostic_rom(diagnostic)
    copy(ROOT/'README.md',OUT/'README.md');copy(ROOT/'LICENSE',OUT/'LICENSES/PIQ-GPL-3.0.txt')
    for p in licenses:
        if p.is_file():copy(p,OUT/'LICENSES'/p.name)
    copy(ROOT/'tools/make_diagnostic_rom.py',OUT/'diagnostic_source.py')
    source=OUT/'piq-native-arcade-0.1.0-alpha.1-source.zip'
    with zipfile.ZipFile(source,'x',zipfile.ZIP_DEFLATED) as z:
        for p in ROOT.rglob('*'):
            if not p.is_file():continue
            rel=p.relative_to(ROOT)
            if any(part in ('build','.gradle','__pycache__') for part in rel.parts):continue
            if rel.parts[0] not in ('src','helper','tools','docs','gradle') and len(rel.parts)!=1:continue
            z.write(p,'piq-native-arcade/'+rel.as_posix())
    report={'status':'local-preview-packaged-awaiting-final-jar-smoke','junit_tests':count,
        'files':{p.relative_to(OUT).as_posix():{'bytes':p.stat().st_size,'sha256':sha(p)} for p in OUT.rglob('*') if p.is_file()},
        'not_tested':['Minecraft gameplay','commercial ROM compatibility','remote multiplayer','real claim plugin combinations']}
    with (OUT/'CHECKSUMS.json').open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=True,indent=2))
if __name__=='__main__':main()
