"""Stage FC23/SFC11 spectator candidates against frozen model22. No install or world access."""
import argparse,json,tomllib
from pathlib import Path
from freeze_fc_core_alpha19 import read_jar,safe_path,digest,require,META,MANIFEST
from freeze_fc_compact_alpha20 import RESTORE
from prepare_cabinet_multiplayer21 import clean,jar_bytes,merged_sfc,HELPER_SHA

ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'piq-fc-arcade/build/review-user-models22-v1'
NAMES={'fc':'piq_fc_arcade-0.31.0-alpha.23.jar','sfc':'piq_sfc-0.1.0-alpha.11.jar','native':'piq_native_arcade-0.1.0-alpha.7.jar'}
BASELINES={
 'fc':('piq_fc_arcade-0.31.0-alpha.22.jar','ECC559B11E21F389BB95AC8113A2D885DC8DE7B91F01DE5C25156AD51AEB5645'),
 'sfc':('piq_sfc-0.1.0-alpha.10.jar','A5892700D043D51A890E59D5EC57F3E34F9F828A49EED92BE1DA759C5782F882'),
 'native':(NAMES['native'],'8BE543D920BB3D49627F440CBE02EBD66AA2BB4370EA249202EF1F1066E1608A')}
BUILDS={'fc':ROOT/'piq-fc-arcade/build/libs'/NAMES['fc'],
        'sfc':ROOT/'piq-sfc-home/build/libs/piq_sfc_home-0.1.0-alpha.11.jar'}
OLD_CLASSES={
 'fc':{'cn/piq/retro/input/InputOwnership','cn/piq/fcarcade/cabinet/CabinetNetwork',
       'cn/piq/fcarcade/cabinet/CabinetRooms','cn/piq/fcarcade/cabinet/CabinetMediaSender',
       'cn/piq/fcarcade/client/cabinet/CabinetClientBackends'},
 'sfc':{'cn/piq/sfchome/SfcHomeMod','cn/piq/sfchome/server/SfcHomeServer','cn/piq/sfchome/client/SfcPlayback'}}
NEW_PREFIXES={
 'fc':('cn/piq/fcarcade/cabinet/Watch','cn/piq/fcarcade/client/watch/',
       'cn/piq/fcarcade/client/cabinet/WatchMediaStream','cn/piq/fcarcade/client/cabinet/WatchAudio'),
 'sfc':('cn/piq/sfchome/client/SfcWatch','cn/piq/sfchome/server/SfcWatch')}

def classify(kind,old,new):
    require(not set(old)-set(new),'Removed baseline entries')
    changed={n for n in new if n not in old or new[n]!=old[n]}
    for n in changed:
        if n in (META,MANIFEST):continue
        stem=n.removesuffix('.class').split('$')[0]
        require(n.endswith('.class') and (stem in OLD_CLASSES[kind]
                or (n not in old and n.startswith(NEW_PREFIXES[kind]))),'Unexpected spectator change '+kind+': '+n)
    assets=[n for n in old if n.startswith(('assets/','data/'))]
    require(all(new[n]==old[n] for n in assets),'Model/resource changed')
    return {'added':sorted(set(new)-set(old)),
            'changed':{n:{'before':digest(old[n]),'after':digest(new[n])} for n in sorted(changed&set(old))},
            'removed':[],'unchanged_entries':len(old)-len(changed&set(old)),
            'model_and_data_entries_unchanged':len(assets)}

def metadata(kind,entries):
    doc=tomllib.loads(entries[META].decode('utf-8'))
    actual={m['modId']:m['version'] for m in doc['mods']}
    expected={'fc':{'piq_fc_arcade':'0.31.0-alpha.23'},'sfc':{'piq_sfc_arcade':'0.2.0-alpha.6','piq_sfc_home':'0.1.0-alpha.11'}}[kind]
    require(actual==expected and len(doc['mods'])==len(expected),'Wrong mod identity/version')
    if kind=='sfc':
        dep=[d for d in doc['dependencies']['piq_sfc_home'] if d['modId']=='piq_fc_arcade']
        require(len(dep)==1 and all(dep[0].get(k)==v for k,v in {'versionRange':'[0.31.0-alpha.23,0.32.0)',
                'type':'required','side':'BOTH','ordering':'AFTER'}.items()),'SFC11 must require FC23 on both sides')
    return actual

def plan():
    files={};entries={};report={'schema':'piq-auto-watch23-stage-1','mods':{},'installed':False,'minecraft_started':False}
    for kind,path in BUILDS.items():
        built_sha,_,built=read_jar(path);built=clean(built)
        name,expected=BASELINES[kind];old_sha,_,old=read_jar(BASE/name);old=clean(old)
        require(old_sha==expected,'Frozen model22 changed '+kind)
        if kind=='fc':
            for n,(source_sha,frozen_sha) in RESTORE.items():
                require(digest(old[n])==frozen_sha and digest(built[n]) in (source_sha,frozen_sha),'Unexpected FC texture')
                built[n]=old[n]
        else:
            built=merged_sfc(built)
            built[MANIFEST]=b'Manifest-Version: 1.0\r\nImplementation-Title: PIQ SFC\r\nImplementation-Version: 0.1.0-alpha.11\r\n\r\n'
        report['mods'][kind]={'source_sha256':built_sha,'baseline_sha256':old_sha,'versions':metadata(kind,built),'scope':classify(kind,old,built)}
        entries[kind]=built;files[NAMES[kind]]=jar_bytes(built)
    n,expected=BASELINES['native'];path=BASE/n
    native_sha,_,native=read_jar(path);require(native_sha==expected,'Native7 changed')
    entries['native']=clean(native);files[n]=path.read_bytes()
    helper=safe_path(BASE/'piq-native-arcade/runtime/piq-native-helper.jar',True).read_bytes()
    require(digest(helper)==HELPER_SHA,'Helper changed');files['piq-native-arcade/runtime/piq-native-helper.jar']=helper
    owners={}
    for kind,values in entries.items():
        for n in values:
            if n.endswith('.class'):
                require(n not in owners,'Duplicate class '+n);owners[n]=kind
    report.update(unique_classes=len(owners),files={n:{'bytes':len(v),'sha256':digest(v)} for n,v in files.items()},ok=True)
    return files,report

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--check-only',action='store_true');a=p.parse_args()
    output=safe_path(a.output);require(output.is_relative_to(ROOT) and not output.exists(),'Refuse existing/outside output')
    files,report=plan()
    if not a.check_only:
        output.mkdir(parents=True)
        for n,raw in files.items():
            path=safe_path(output/n);path.parent.mkdir(parents=True,exist_ok=True)
            with path.open('xb') as s:s.write(raw)
            require(path.read_bytes()==raw,'Stage readback failed')
        with (output/'stage-verification.json').open('x',encoding='utf-8') as s:json.dump(report,s,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'files':report['files'],'unique_classes':report['unique_classes'],'output':str(output)}))
if __name__=='__main__':main()
