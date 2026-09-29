"""Freeze FC43 only from a tested compilation; retain all unrelated FC42 assets.

Capture after the Mapper19 WASM is finalized, before Gradle check --rerun-tasks.
No deployment, game launch, downloads, commercial ROMs or companion rebuilding.
"""
import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import time
import tomllib

ROOT=Path(__file__).resolve().parents[2]
UTIL=ROOT/'piq-fc-arcade/tools/build_sync39.py'
assert hashlib.sha256(UTIL.read_bytes()).hexdigest().upper()=='7F9E8809360BBEFDDCE9D317146EC4943DDB1A71A09472A68F065370DA6E0F4A'
spec=importlib.util.spec_from_file_location('mapper43_util',UTIL)
b=importlib.util.module_from_spec(spec);spec.loader.exec_module(b)
b.PROJECTS=['piq-fc-arcade','piq-retro-platform']
b.VERSIONS={'piq_fc_arcade':'0.31.0-alpha.43'}
b.TEST_LIMITS={'piq-fc-arcade':(1691,8)}
BASE=ROOT/'piq-fc-arcade/build/review-bundled42-v2/game_console-0.31.0-alpha.42.jar'
BASE_SHA='349CF80D69B1EA9BC20B2EB99870EFD9A72ADCE339BD34EADDFA3C5EEB69CEA0'
PRIOR=BASE.parent/'build-witness.json'
PRIOR_SHA='6561956EDB115B4B092B8ED5A426FD7061ED56C159E0D5CF4D229B8445F4FA9F'
MODULE='core/nes_mapper19_v1.wasm'
OUT=ROOT/'piq-fc-arcade/build/review-mapper43-v1'
ALLOWED={
    'FcNetwork','ArcadeSessionPayload','rom/NesCompatibility','session/NesCoreVariant',
    'core/NesCores','core/wasm/NamcoWasmNesCore','core/wasm/WasmNesCore','core/wasm/ZapperWasmNesCore',
    'client/ClientArcadeSession','server/ServerArcadeSessions','server/hosted/NesServerCoreFactory',
}

def inputs():
    result=b.snapshot()
    extra=[Path(__file__).resolve(),BASE,PRIOR]
    native=ROOT/'piq-fc-arcade/native/nes-rust'
    extra.extend(p for p in native.rglob('*') if p.is_file() and 'target' not in p.parts and (p.suffix in ('.rs','.toml','.lock') or p.name=='LICENSE'))
    for p in extra:result[p.relative_to(ROOT).as_posix()]=b.file_sha(p)
    b.require(b.file_sha(BASE)==BASE_SHA and b.file_sha(PRIOR)==PRIOR_SHA,'Frozen base changed')
    return result

def allowed(name):
    prefix='cn/piq/fcarcade/'
    return name.startswith(prefix) and name.endswith('.class') and name[len(prefix):-6].split('$')[0] in ALLOWED

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--capture',type=Path);ap.add_argument('--witness',type=Path);ap.add_argument('--witness-sha256')
    a=ap.parse_args();before=inputs()
    if a.capture:
        b.exclusive_json(a.capture,{'schema':'mapper43-inputs-1','captured_ns':time.time_ns(),'inputs':before});print(b.file_sha(a.capture));return
    b.require(a.witness and b.file_sha(a.witness)==a.witness_sha256,'Pinned source witness required')
    w=json.loads(a.witness.read_bytes());b.require(w['schema']=='mapper43-inputs-1' and w['inputs']==before,'Source changed since capture')
    counts,xml,_=b.tests(w['captured_ns'])
    compiled,evidence=b.compiled_part('piq-fc-arcade','piq_fc_arcade',('cn/piq/fcarcade/','cn/piq/retro/'),True)
    old=b.load(BASE)[1];new=dict(compiled);preserved=[]
    expected=copy.deepcopy(tomllib.loads(old[b.META].decode()))
    expected['mods'][0]['version']='0.31.0-alpha.43'
    b.require(tomllib.loads(compiled[b.META].decode())==expected,'Unexpected metadata change')
    expected_manifest=re.sub(rb'(?m)^(Implementation-Version: )[^\r\n]+',lambda m:m[1]+b'0.31.0-alpha.43',old[b.MANIFEST])
    b.require(compiled[b.MANIFEST]==expected_manifest,'Unexpected manifest change')
    prior_inputs=json.loads(PRIOR.read_bytes())['source_witness']['inputs']
    for n in old.keys()|compiled.keys():
        if old.get(n)==compiled.get(n):continue
        if n in (b.META,b.MANIFEST):continue
        if n==MODULE:
            b.require(n not in old and compiled[n]==(ROOT/'piq-fc-arcade/src/main/resources'/n).read_bytes(),'Wrong new module');continue
        if n.endswith('.class'):
            b.require(allowed(n),'Unapproved class delta: '+n);continue
        source='piq-fc-arcade/src/main/resources/'+n
        b.require(n in old and n in compiled and n.endswith('.png') and prior_inputs.get(source)==b.sha(compiled[n]),'Unapproved resource delta: '+n)
        new[n]=old[n];preserved.append(n)
    b.require(MODULE in new and len(preserved)<=2,'Missing module or excess asset drift')
    core_source=(ROOT/'piq-fc-arcade/src/main/java/cn/piq/fcarcade/core/wasm/NamcoWasmNesCore.java').read_text(encoding='utf-8')
    b.require('MODULE_SHA256="'+b.sha(new[MODULE]).lower()+'"' in core_source,'Module constant mismatch')
    graph=b.identity_graph({'fc':new})
    def fence():
        b.require(inputs()==before and b.file_sha(a.witness)==a.witness_sha256,'Input drift')
        b.require(all(b.file_sha(ROOT/p)==h for p,h in xml.items()),'Test evidence drift')
        b.require(b.file_sha(ROOT/evidence['path'])==evidence['sha256'],'Compiled JAR drift')
    fence();b.require(not OUT.exists(),'Refuse to overwrite output');OUT.mkdir()
    dest=OUT/'game_console-0.31.0-alpha.43.jar'
    with dest.open('xb') as f:f.write(b.jar_bytes(new))
    b.require(b.load(dest)[1]==new,'Readback CRC/content mismatch');fence()
    report={'schema':'mapper43-build-1','ok':True,'source_witness':w,'source_witness_sha256':a.witness_sha256,
        'artifact':str(dest.relative_to(ROOT)),'bytes':dest.stat().st_size,'sha256':b.file_sha(dest),'compiled':evidence,
        'tests':counts,'test_xml':xml,'identity':graph,'preserved_source_drafts':preserved,
        'changed_entries':sorted(n for n in old.keys()|new.keys() if old.get(n)!=new.get(n)),
        'legacy_modules_unchanged':all(new[n]==old[n] for n in ('core/nes_rust_wasm_bg.wasm','core/nes_zapper_v1.wasm')),
        'installed':False,'published':False,'minecraft_or_real_network_tested':False}
    b.exclusive_json(OUT/'build-witness.json',report)
    print(json.dumps({k:report[k] for k in ('ok','artifact','bytes','sha256','tests','changed_entries')},ensure_ascii=False))

if __name__=='__main__':main()
