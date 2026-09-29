"""Package only the three audited alpha19 delivery JARs; never install or run them.

Fixed input allowlist, immutable-byte hashing and no-overwrite output are intentional.
The thin SFC home build, separate SFC6 core and internal platform library are NOT
package inputs. This guard supplements, never substitutes for, the final JAR audit.
"""
from __future__ import annotations
import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import io
import json
import os
from pathlib import Path
import re
import shutil
import tempfile
import tomllib
import zipfile
from freeze_fc_core_alpha19 import BASELINES as RELEASE_BASELINES, META, MANIFEST, checked_zip, digest, require, safe_path

ROOT=Path(__file__).resolve().parents[2]
DELIVERY=ROOT/'制作Mod/03-街机模拟'
BASELINES=dict(RELEASE_BASELINES)
BASELINES['core']=(DELIVERY/'PIQ-SFC街机/piq_sfc_arcade-0.2.0-alpha.6.jar','38FA46C5D283EAD9E1F6666D01398F495E2E1A3260A1517BE8EE959710963363')
JARS={
    'fc':'PIQ-FC街机/alpha19-fc-core-addons/piq_fc_arcade-0.31.0-alpha.19.jar',
    'sfc':'PIQ-SFC家用/0.1.0-alpha.6/piq_sfc-0.1.0-alpha.6.jar',
    'native':'PIQ原生街机/0.1.0-alpha.6/piq_native_arcade-0.1.0-alpha.6.jar',
}
DOCUMENTS={
    '安装说明.md':'核心内置FC与附属-alpha19-安装说明.md',
    '成品独立检查.json':'PIQ-FC街机/alpha19-fc-core-addons/final-independent-audit.json',
    '构建测试汇总.json':'PIQ-FC街机/alpha19-fc-core-addons/full-build-tests.json',
}
OUTPUT_NAME='FC内置核心与可选附属-alpha19-安装包-20260910.zip'
EXPECTED_MODS={
    'fc':{'piq_fc_arcade':'0.31.0-alpha.19'},
    'sfc':{'piq_sfc_arcade':'0.2.0-alpha.6','piq_sfc_home':'0.1.0-alpha.6'},
    'native':{'piq_native_arcade':'0.1.0-alpha.6'},
}
PROJECTS=frozenset({'piq-fc-arcade','piq-sfc-home','piq-native-arcade'})
SFC_WASM='assets/piq_sfc_arcade/core/piq_sfc_wasm.wasm'
DATE=(2026,9,10,0,0,0)

@dataclass(frozen=True)
class Snapshot:
    path:Path
    raw:bytes
    sha256:str

def snapshot(path,limit=128*1024*1024):
    path=safe_path(path,True)
    require(path.stat().st_size<=limit,'Input exceeds byte budget: '+str(path))
    raw=path.read_bytes();require(len(raw)<=limit,'Input grew beyond byte budget')
    return Snapshot(path,raw,digest(raw))

def json_document(raw):
    def object_pairs(pairs):
        value={}
        for key,item in pairs:
            require(key not in value,'Duplicate JSON key: '+key);value[key]=item
        return value
    def invalid_constant(value):raise ValueError('Non-finite JSON value: '+value)
    result=json.loads(raw.decode('utf-8-sig'),object_pairs_hook=object_pairs,parse_constant=invalid_constant)
    require(isinstance(result,dict),'Expected JSON object')
    return result

def check_audit(report,jars):
    require(report.get('ok') is True and report.get('schema')=='piq-retro-alpha19-compat-1','Final audit did not pass or wrong audit schema')
    require(report.get('fixture_only') is not True and report.get('not_a_final_release_validation') is not True,'Development fixture is not final audit')
    require(set(report.get('jars',{}))==set(JARS),'Audit must identify exactly three final JARs')
    for key,item in jars.items():
        record=report['jars'][key]
        require(isinstance(record,dict) and isinstance(record.get('path'),str),'Missing audited JAR path')
        require(safe_path(record['path'],True)==item.path,'Audited JAR path differs from fixed final delivery: '+key)
        claimed=record.get('sha256')
        require(isinstance(claimed,str) and re.fullmatch('[0-9a-fA-F]{64}',claimed) is not None and claimed.upper()==item.sha256,'Audited JAR SHA mismatch: '+key)
    probes=report.get('probes',{})
    require(probes.get('production_compiled') is False and probes.get('old_separate_core_on_classpath') is False,'Audit must use final JARs, not recompiled production or separate old core')
    discovery=probes.get('fml_discovery',{})
    require(discovery.get('ok') is True and discovery.get('production_origin')=='final-jar-only','Missing final-JAR FML discovery probe')
    packet=probes.get('sfc_real_neoforge_outer_packet_codec',{})
    require(packet.get('passed') is True and packet.get('assertions')==37
            and type(packet.get('max_upload_outer_packet_bytes')) is int and 0<packet['max_upload_outer_packet_bytes']<32767,'Missing bounded real network codec probe')
    require(report.get('installed') is False and report.get('minecraft_or_native_core_started') is False,'Unexpected audit installation/game execution status')

def check_build(report):
    require(report.get('ok') is True,'Full build/test report did not pass')
    projects=report.get('projects',{});require(set(projects)==PROJECTS,'Build report must cover exactly three projects')
    for name,counts in projects.items():
        require(isinstance(counts,dict),'Invalid build counts')
        for key in ('tests','failures','errors','skipped'):
            require(type(counts.get(key)) is int and counts[key]>=0,'Missing/non-integral build count: '+name+'/'+key)
        require(counts['tests']>0 and counts['failures']==0 and counts['errors']==0 and counts['skipped']<counts['tests'],'Missing or failed full tests: '+name)
    require(report.get('minecraft_started') is False and report.get('installed') is False,'Unexpected build installation/game execution status')

def check_metadata(jars):
    owners={};parsed={}
    for key,entries in jars.items():
        require(META in entries and MANIFEST in entries,'Missing metadata: '+key)
        data=tomllib.loads(entries[META].decode('utf-8'));parsed[key]=data
        mods=data.get('mods',[]);actual={m.get('modId'):m.get('version') for m in mods}
        require(actual==EXPECTED_MODS[key] and len(mods)==len(actual),'Wrong mod IDs or versions; thin/core/platform JAR is not a final addon: '+key)
        require(data.get('modLoader')=='javafml' and data.get('loaderVersion')=='[4,)','Unexpected loader')
        require(set(data.get('dependencies',{}))==set(actual),'Unexpected dependency owner')
        for mod in actual:
            require(mod not in owners,'Duplicate mod ID');owners[mod]=key
            deps=data['dependencies'][mod];by_id={d.get('modId'):d for d in deps}
            require(len(by_id)==len(deps),'Duplicate dependency')
            expected={'neoforge','minecraft'}
            if mod=='piq_fc_arcade':expected.add('waterframes')
            else:expected.add('piq_fc_arcade')
            if mod=='piq_sfc_home':expected.add('piq_sfc_arcade')
            require(set(by_id)==expected,'Foreign/missing dependency, including forbidden platform dependency')
            for dep in deps:
                optional=mod=='piq_fc_arcade' and dep['modId']=='waterframes'
                require(dep.get('type')==('optional' if optional else 'required') and dep.get('side')=='BOTH','Dependency authority weakened')
            if mod in ('piq_sfc_home','piq_native_arcade'):
                dep=by_id['piq_fc_arcade'];require(dep.get('versionRange')=='[0.31.0-alpha.19,0.32.0)' and dep.get('ordering')=='AFTER','Addon must require FC19')
            if mod=='piq_sfc_home':require(by_id['piq_sfc_arcade'].get('versionRange')=='[0.2.0-alpha.6,0.3.0)','Wrong bundled-core requirement')
        version='0.31.0-alpha.19' if key=='fc' else '0.1.0-alpha.6'
        require(entries[MANIFEST].decode('utf-8').replace('\r','').count('Implementation-Version: '+version+'\n')==1,'Wrong manifest version')
    require(len(owners)==4,'Exactly four legacy-compatible mod IDs required')
    return parsed,owners

def check_ownership(jars):
    classes={};runtimes={};runtime_hashes={}
    for key,entries in jars.items():
        for name,raw in entries.items():
            require(not name.lower().endswith(('.jar','.nes','.sfc','.smc','.7z','.zip','.exe')),'Nested JAR/ROM/archive/program must not enter delivery: '+name)
            require(not name.startswith(('META-INF/jarjar/','META-INF/versions/','tests/','qa/','private-qa/')) and '/test/' not in name,'Private/test or unreviewed multi-release content')
            if name.endswith('.class'):
                require(name not in classes,'Duplicate production class: '+name);classes[name]=key
                require(not re.search(r'(?:Test|TestRunner|Probe)(?:\$[^/]*)?\.class$',name),'Test/probe class in delivery')
                if key=='sfc':require(name.startswith(('cn/piq/sfchome/','cn/piq/sfcarcade/')),'SFC embeds a foreign class')
                if key=='native':require(name.startswith('cn/piq/nativearcade/'),'Native embeds a foreign class')
                if key=='fc':require(not name.startswith(('cn/piq/sfchome/','cn/piq/sfcarcade/','cn/piq/nativearcade/')),'Main embeds addon implementation')
            if name.lower().endswith(('.wasm','.dll','.so','.dylib')):
                require(name not in runtimes,'Duplicate runtime path')
                require(key=='fc' or key=='sfc' and name==SFC_WASM,'Unexpected addon runtime')
                value=digest(raw);require(value not in runtime_hashes or runtime_hashes[value]==key,'Same runtime copied across JARs')
                runtime_hashes[value]=key;runtimes[name]=key
    for required in ('RetroEmulator','RetroFrame','RetroBackendRegistry','RetroFactoryRegistry'):
        require(classes.get('cn/piq/retro/api/'+required+'.class')=='fc','Shared API must be embedded in FC main')
    require(classes.get('cn/piq/retro/client/GamepadInput.class')=='fc','Physical gamepad owner must be FC')
    require(runtimes.get(SFC_WASM)=='sfc','Combined SFC lacks its single bundled core')
    return {'classes':len(classes),'duplicate_classes':0,'runtime_owners':runtimes,'standalone_platform_jar':False}

def check_frozen(jars,parsed):
    originals={};inputs=[]
    for key,(path,expected) in BASELINES.items():
        item=snapshot(path);require(item.sha256==expected,'Frozen baseline SHA mismatch: '+key)
        _,originals[key]=checked_zip(item.raw);inputs.append(item)
    counts={}
    for key in JARS:
        inherited={name:data for name,data in originals[key].items() if name.startswith('assets/') and not name.endswith('/')}
        if key=='sfc':
            extra={name:data for name,data in originals['core'].items() if name.startswith('assets/') and not name.endswith('/')}
            require(not set(inherited)&set(extra),'Unexpected old asset overlap');inherited.update(extra)
        actual={name:data for name,data in jars[key].items() if name.startswith('assets/') and not name.endswith('/')}
        require(actual==inherited,'Frozen model/texture/core asset inventory or bytes changed: '+key);counts[key]=len(actual)
    require(counts['fc']==113,'FC must retain exactly 113 frozen assets')
    core=originals['core']
    for name,raw in core.items():
        if name not in (META,MANIFEST) and not name.endswith('/'):
            require(jars['sfc'].get(name)==raw,'Frozen SFC6 core entry changed: '+name)
    old=tomllib.loads(core[META].decode('utf-8'));combined=parsed['sfc']
    require([m for m in combined['mods'] if m['modId']=='piq_sfc_arcade']==old['mods'],'Old SFC core registration metadata changed')
    require(combined['dependencies']['piq_sfc_arcade']==old['dependencies']['piq_sfc_arcade'],'Old SFC core dependencies changed')
    return inputs,counts

@dataclass(frozen=True)
class PackagePlan:
    payloads:dict[str,bytes]
    inputs:tuple[Snapshot,...]
    summary:dict

def plan():
    jar_files={key:snapshot(DELIVERY/relative) for key,relative in JARS.items()}
    require(len({item.path for item in jar_files.values()})==3,'Three distinct final JARs required')
    docs={name:snapshot(DELIVERY/relative,8*1024*1024) for name,relative in DOCUMENTS.items()}
    require(docs['安装说明.md'].raw.decode('utf-8-sig').strip(),'Empty installation instructions')
    check_audit(json_document(docs['成品独立检查.json'].raw),jar_files)
    check_build(json_document(docs['构建测试汇总.json'].raw))
    jars={key:checked_zip(item.raw)[1] for key,item in jar_files.items()}
    parsed,mod_owners=check_metadata(jars);ownership=check_ownership(jars)
    baselines,asset_counts=check_frozen(jars,parsed)
    payloads={item.path.name:item.raw for item in jar_files.values()}
    require(len(payloads)==3,'Duplicate delivery filenames');payloads.update({name:item.raw for name,item in docs.items()})
    require(sum(name.endswith('.jar') for name in payloads)==3 and len(payloads)==6,'Unexpected package input')
    payloads['SHA256.txt']=(''.join(digest(raw)+'  '+name+'\n' for name,raw in sorted(payloads.items()))).encode('utf-8')
    summary={'schema':'piq-fc-core-alpha19-package-1','jars':{key:{'path':str(item.path),'sha256':item.sha256} for key,item in jar_files.items()},
             'mod_owners':mod_owners,'ownership':ownership,'inherited_asset_counts':asset_counts,
             'files':{name:{'sha256':digest(raw),'bytes':len(raw)} for name,raw in payloads.items()}}
    return PackagePlan(payloads,tuple([*jar_files.values(),*docs.values(),*baselines]),summary)

def verify_archive(raw,p):
    _,actual=checked_zip(raw)
    require(actual==p.payloads,'Package inventory or bytes differ from exact allowlist')

def revalidate_inputs(p):
    for item in p.inputs:require(snapshot(item.path).sha256==item.sha256,'Input changed during packaging: '+str(item.path))

def build(p,check_only=False):
    output=safe_path(DELIVERY/OUTPUT_NAME);report=safe_path(output.with_suffix('.verification.json'))
    require(not output.exists() and not report.exists(),'Refusing to overwrite existing package or report')
    revalidate_inputs(p)
    result=dict(p.summary,ok=True,path=str(output),checked_at_utc=datetime.now(timezone.utc).isoformat(),
                installed=False,minecraft_started=False,check_only=check_only)
    if check_only:return result
    output.parent.mkdir(parents=True,exist_ok=True);safe_path(output.parent)
    handle,name=tempfile.mkstemp(prefix='.alpha19-package-',suffix='.zip',dir=output.parent);os.close(handle);staged=Path(name)
    try:
        with zipfile.ZipFile(staged,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
            for entry,raw in sorted(p.payloads.items()):
                info=zipfile.ZipInfo(entry,DATE);info.create_system=3;info.external_attr=0o100644<<16
                archive.writestr(info,raw,compress_type=zipfile.ZIP_DEFLATED,compresslevel=9)
        staged_bytes=staged.read_bytes();verify_archive(staged_bytes,p);revalidate_inputs(p)
        with output.open('xb') as target,staged.open('rb') as source:shutil.copyfileobj(source,target)
        final=snapshot(output);verify_archive(final.raw,p);require(final.sha256==digest(staged_bytes),'Final package SHA mismatch')
        revalidate_inputs(p);result.update(sha256=final.sha256,bytes=len(final.raw))
        with report.open('x',encoding='utf-8') as stream:json.dump(result,stream,ensure_ascii=False,indent=2);stream.write('\n')
        return result
    finally:staged.unlink(missing_ok=True) # only this invocation's exact temporary file

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-only',action='store_true');args=parser.parse_args()
    print(json.dumps(build(plan(),args.check_only),ensure_ascii=True))
if __name__=='__main__':main()
