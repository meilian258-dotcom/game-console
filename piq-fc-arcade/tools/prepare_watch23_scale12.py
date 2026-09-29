"""Combine frozen FC23 with the SFC12 visual-only update. Never installs or overwrites."""
import argparse,json,tomllib
from pathlib import Path
from freeze_fc_core_alpha19 import read_jar,safe_path,digest,require,META,MANIFEST
from prepare_cabinet_multiplayer21 import clean,jar_bytes,merged_sfc,HELPER_SHA

ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'piq-fc-arcade/build/review-watch23-v1'
NAMES={'fc':'piq_fc_arcade-0.31.0-alpha.23.jar','sfc':'piq_sfc-0.1.0-alpha.12.jar','native':'piq_native_arcade-0.1.0-alpha.7.jar'}
PINNED={'fc':(NAMES['fc'],'B7BF04BEEA5F58E8E9C0DFA23E1BD8F2CAF6B11455085D843F568B42D9021686'),
 'sfc':('piq_sfc-0.1.0-alpha.11.jar','ADF7172C0997983A61C0566AAEE4AE6F19C7D574D6D5B1971490EB69F4B92393'),
 'native':(NAMES['native'],'8BE543D920BB3D49627F440CBE02EBD66AA2BB4370EA249202EF1F1066E1608A')}
BUILD=ROOT/'piq-sfc-home/build/libs/piq_sfc_home-0.1.0-alpha.12.jar'
VISUAL_CLASSES={
 'cn/piq/sfchome/client/SfcHardwareRenderer',
 'cn/piq/sfchome/client/SfcHardwareMesh',
 'cn/piq/sfchome/client/SfcAvCableGeometry',
 'cn/piq/sfchome/client/SfcAvCableRenderer',
 'cn/piq/sfchome/world/SfcHomeConsoleBlock',
}
NEW_CLASSES={
 'cn/piq/sfchome/layout/SfcConsoleScale.class',
 'cn/piq/sfchome/layout/SfcConsoleScale$Point.class',
 'cn/piq/sfchome/layout/SfcConsoleScale$Bounds.class',
}
CARD='assets/piq_sfc_home/models/item/cartridge.json'
CONSOLE='assets/piq_sfc_home/models/item/console.json'
CONSOLE_GUI={'rotation':[30,225,0],'translation':[.17252,3.57112,0],'scale':[.669155]*3}

def classify(old,new):
    require(not set(old)-set(new),'Removed baseline entries')
    changed={n for n in new if n not in old or new[n]!=old[n]}
    for n in changed:
        if n in (META,MANIFEST):continue
        if n==CARD:
            before=json.loads(old[n]);after=json.loads(new[n]);before['display']['gui']['scale']=[2.4,2.4,2.4]
            require(after==before,'Only cartridge GUI scale may change');continue
        if n==CONSOLE:
            before=json.loads(old[n]);after=json.loads(new[n]);before['display']['gui']=CONSOLE_GUI
            require(after==before,'Only exact fitted console GUI may change');continue
        stem=n.removesuffix('.class').split('$')[0]
        require(n.endswith('.class') and (stem in VISUAL_CLASSES if n in old else n in NEW_CLASSES),
                'Unexpected visual change: '+n)
    return {'changed':{n:{'before':digest(old[n]),'after':digest(new[n])} for n in sorted(changed&set(old))},
            'added':sorted(set(new)-set(old)),'removed':[],
            'unchanged_entries':sum(n in old and old[n]==v for n,v in new.items())}

def plan():
    frozen={};entries={};files={}
    for kind,(name,pin) in PINNED.items():
        path=BASE/name;sha,_,values=read_jar(path);require(sha==pin,'Frozen spectator artifact changed '+kind)
        frozen[kind]=clean(values)
        if kind!='sfc':files[NAMES[kind]]=path.read_bytes();entries[kind]=frozen[kind]
    source_sha,_,built=read_jar(BUILD);sfc=merged_sfc(clean(built))
    sfc[MANIFEST]=b'Manifest-Version: 1.0\r\nImplementation-Title: PIQ SFC\r\nImplementation-Version: 0.1.0-alpha.12\r\n\r\n'
    doc=tomllib.loads(sfc[META].decode());old_doc=tomllib.loads(frozen['sfc'][META].decode())
    for mod in old_doc['mods']:
        if mod['modId']=='piq_sfc_home':mod['version']='0.1.0-alpha.12'
    require(doc==old_doc,'Only home version may change in metadata')
    scope=classify(frozen['sfc'],sfc);files[NAMES['sfc']]=jar_bytes(sfc);entries['sfc']=sfc
    helper=safe_path(BASE/'piq-native-arcade/runtime/piq-native-helper.jar',True).read_bytes()
    require(digest(helper)==HELPER_SHA,'Helper changed');files['piq-native-arcade/runtime/piq-native-helper.jar']=helper
    owners={}
    for kind,values in entries.items():
        for name in values:
            if name.endswith('.class'):
                require(name not in owners,'Duplicate class '+name);owners[name]=kind
    return files,{'schema':'piq-watch23-scale12-stage-1','ok':True,'source_sfc_sha256':source_sha,
        'baseline_sha256':{k:v[1] for k,v in PINNED.items()},'sfc_scope':scope,
        'unique_classes':len(owners),'files':{n:{'bytes':len(v),'sha256':digest(v)} for n,v in files.items()},
        'installed':False,'minecraft_started':False}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--check-only',action='store_true');a=p.parse_args()
    output=safe_path(a.output);require(output.is_relative_to(ROOT) and not output.exists(),'Refuse existing/outside output')
    files,report=plan()
    if not a.check_only:
        output.mkdir(parents=True)
        for n,raw in files.items():
            path=safe_path(output/n);path.parent.mkdir(parents=True,exist_ok=True)
            with path.open('xb') as f:f.write(raw)
            require(path.read_bytes()==raw,'Readback mismatch')
        with (output/'stage-verification.json').open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=True))
