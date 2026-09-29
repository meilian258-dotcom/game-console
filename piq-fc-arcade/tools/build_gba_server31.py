"""Build and freeze FC31 against FC30. No install or live game/server access."""
import argparse,json,os,subprocess,tomllib,xml.etree.ElementTree as ET
from pathlib import Path
from freeze_fc_core_alpha19 import read_jar,safe_path,digest,require,META,MANIFEST
from freeze_fc_compact_alpha20 import RESTORE
from prepare_cabinet_multiplayer21 import clean,jar_bytes

ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'piq-fc-arcade/build/review-controls30-v1'
FC='piq_fc_arcade-0.31.0-alpha.31.jar'
BASE_FC=BASE/'piq_fc_arcade-0.31.0-alpha.30.jar'
BASE_SHA='72C330529A194582DD726921F12DBF37EBBCF6BB69DC328AF38DC213A6262821'
STEMS={'cn/piq/fcarcade/'+n for n in (
 'cabinet/CabinetBackends','cabinet/CabinetSeats','cabinet/CabinetRoomLedger','cabinet/CabinetRooms',
 'cabinet/CabinetRoomNetwork','client/cabinet/CabinetClientBackends','client/cabinet/CabinetMenuScreen',
 'client/cabinet/CabinetBackend')}
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot')

def inputs():
    paths=[]
    for project in ('piq-fc-arcade','piq-retro-platform'):
        p=ROOT/project;paths+=list((p/'src').rglob('*'))
        paths+=list((p/'gradle').rglob('*'))
        paths += [p/n for n in ('gradle.properties','build.gradle','settings.gradle','gradlew.bat')]
    return {p.relative_to(ROOT).as_posix():digest(p.read_bytes()) for p in sorted(set(paths)) if p.is_file()}

def main():
    p=argparse.ArgumentParser();p.add_argument('--output',required=True,type=Path);a=p.parse_args()
    out=safe_path(a.output);require(out.is_relative_to(ROOT/'piq-fc-arcade/build') and not out.exists(),'New FC build stage required')
    base_sha,_,old=read_jar(BASE_FC);old=clean(old);require(base_sha==BASE_SHA,'FC30 baseline changed')
    before=inputs()
    subprocess.run(['cmd.exe','/d','/c','gradlew.bat','check','jar','--offline'],cwd=ROOT/'piq-fc-arcade',env=dict(os.environ,JAVA_HOME=str(JAVA)),check=True)
    require(before==inputs(),'Source changed during build')
    source_sha,_,new=read_jar(ROOT/'piq-fc-arcade/build/libs'/FC);new=clean(new)
    for name,(source,frozen) in RESTORE.items():
        require(digest(old[name])==frozen and digest(new[name]) in (source,frozen),'Unexpected historical texture');new[name]=old[name]
    removed=set(old)-set(new);require(not removed,'No old entries may be removed')
    changed={n for n in old.keys()&new.keys() if old[n]!=new[n]};added=set(new)-set(old)
    for name in changed|added:
        require(name in (META,MANIFEST) or (name.endswith('.class') and name[:-6].split('$')[0] in STEMS),'Unreviewed change '+name)
    before_meta=tomllib.loads(old[META].decode());after_meta=tomllib.loads(new[META].decode())
    before_meta['mods'][0]['version']='0.31.0-alpha.31';require(before_meta==after_meta,'Unexpected FC metadata')
    require(new[MANIFEST]==old[MANIFEST].replace(b'0.31.0-alpha.30',b'0.31.0-alpha.31'),'Unexpected manifest')
    xml={p.relative_to(ROOT).as_posix():digest(p.read_bytes()) for p in (ROOT/'piq-fc-arcade/build/test-results/test').glob('TEST-*.xml')}
    counts={k:0 for k in ('tests','failures','errors','skipped')}
    for path in xml:
        root=ET.fromstring((ROOT/path).read_bytes())
        for key in counts:counts[key]+=int(root.attrib[key])
    require(counts['tests']>=1290 and counts['failures']==counts['errors']==0 and counts['skipped']==7,'Full FC regression failed')
    raw=jar_bytes(new);out.mkdir(parents=True)
    with (out/FC).open('xb') as f:f.write(raw)
    require((out/FC).read_bytes()==raw,'Stage readback failed')
    report={'schema':'piq-gba-server31-fc-build-1','ok':True,'inputs':before,'tests':counts,'test_xml':xml,
      'fc':{'path':str(out/FC),'sha256':digest(raw)},'compiled_sha256':source_sha,'baseline_sha256':base_sha,
      'changed':sorted(changed),'added':sorted(added),'removed':[],'protected_unchanged':sum(n.startswith(('assets/','data/','core/')) and old[n]==new[n] for n in old),
      'installed':False,'minecraft_started':False}
    with (out/'build-witness.json').open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'fc':report['fc'],'tests':counts,'changed':sorted(changed)}))
if __name__=='__main__':main()
