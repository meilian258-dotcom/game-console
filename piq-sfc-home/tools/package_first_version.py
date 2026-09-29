"""Freeze a new local addon deliverable; never installs or overwrites an old release."""
from pathlib import Path
import hashlib, json, zipfile, shutil, xml.etree.ElementTree as ET

ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT.parent
OUT=WORK/'制作Mod/03-街机模拟/PIQ-SFC家用'
NAME='piq_sfc_home-0.1.0-alpha.1.jar'
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest().upper()
def main():
    jar=ROOT/'build/libs'/NAME
    frozen_fc=WORK/'制作Mod/03-街机模拟/PIQ-FC街机/piq_fc_arcade-0.31.0-alpha.14.jar'
    frozen_sfc=WORK/'制作Mod/03-街机模拟/PIQ-SFC街机/piq_sfc_arcade-0.2.0-alpha.6.jar'
    assert sha(frozen_fc)=='BC17E1B483FAD115DAE156C6ECB56D3911915BC31B3C44058D56F3D61002E68A'
    assert sha(frozen_sfc)=='38FA46C5D283EAD9E1F6666D01398F495E2E1A3260A1517BE8EE959710963363'
    paths=list((ROOT/'build/test-results/test').glob('TEST-*.xml'))
    suites=[ET.parse(p).getroot() for p in paths]
    assert len(suites)==8 and sum(int(s.attrib['tests']) for s in suites)==53
    assert all(int(s.attrib.get(k,0))==0 for s in suites for k in ('failures','errors','skipped'))
    with zipfile.ZipFile(jar) as z:
        names=z.namelist();assert len(names)==len(set(names));assert z.testzip() is None
        meta=z.read('META-INF/neoforge.mods.toml').decode();assert 'version="0.1.0-alpha.1"' in meta
        assert '[0.31.0-alpha.14,0.32.0)' in meta and '[0.2.0-alpha.6,0.3.0)' in meta
        assert z.read('META-INF/LICENSE')== (ROOT/'LICENSE').read_bytes()
        resources={}
        for path in (ROOT/'src/main/resources').rglob('*'):
            if path.is_file():
                name=path.relative_to(ROOT/'src/main/resources').as_posix();assert z.read(name)==path.read_bytes(),name
                resources[name]=sha(path)
                if name.endswith('.json'):json.loads(z.read(name))
        classes=[n for n in names if n.endswith('.class')]
        assert len(classes)>30 and all(n.startswith('cn/piq/sfchome/') for n in classes)
        assert not any(n.endswith(('.dll','.wasm','.nes','.sfc','.smc')) for n in names)
        assert not any('jarjar' in n for n in names)
        assert all(int.from_bytes(z.read(n)[6:8],'big')==65 for n in classes)
    OUT.mkdir(parents=True,exist_ok=True)
    targets=[OUT/NAME,OUT/'SFC家用首版使用说明.md',OUT/'piq-sfc-home-0.1.0-alpha.1-source.zip',OUT/'first-version-audit.json']
    assert not any(p.exists() for p in targets),'Never overwrite a delivered artifact'
    shutil.copyfile(jar,targets[0]);shutil.copyfile(ROOT/'README.md',targets[1])
    with zipfile.ZipFile(targets[2],'x',zipfile.ZIP_DEFLATED) as z:
        for path in ROOT.rglob('*'):
            if not path.is_file():continue
            rel=path.relative_to(ROOT)
            if any(part in ('build','.gradle','__pycache__') for part in rel.parts):continue
            if rel.parts[0] not in ('src','tools','gradle') and len(rel.parts)!=1:continue
            z.write(path,'piq-sfc-home/'+rel.as_posix())
    report={'status':'local-frozen-test-build','jar':NAME,'bytes':jar.stat().st_size,'sha256':sha(jar),
        'entries':len(names),'java21_classes':len(classes),'junit_tests':53,'python_tests':18,
        'resources':resources,'dependencies':{p.name:sha(p) for p in (frozen_fc,frozen_sfc)},
        'source_zip_sha256':sha(targets[2]),'limitations':'No Minecraft gameplay, real two-player or third-party claims test. No automatic save resume or custom cover.'}
    with targets[3].open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=True,indent=2))
if __name__=='__main__':main()
