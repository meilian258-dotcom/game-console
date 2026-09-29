"""Freeze FC19 / thin SFC-home6 / Native6 without changing sources or any old delivery.

Only inherited FC artwork may be restored, from the SHA-frozen FC18 archive.
Every class/core/runtime and every other entry comes byte-for-byte from the build.
This is a freezing guard, not a substitute for the final production-code audit.
"""
from __future__ import annotations
import argparse
from dataclasses import dataclass
from datetime import datetime, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import stat
import tempfile
import tomllib
import zipfile

ROOT=Path(__file__).resolve().parents[2]
DELIVERY=ROOT/'制作Mod/03-街机模拟'
META='META-INF/neoforge.mods.toml'
MANIFEST='META-INF/MANIFEST.MF'
MAX_RAW=128*1024*1024
MAX_EXPANDED=256*1024*1024
MAX_ENTRIES=8192
BASELINES={
    'fc':(DELIVERY/'PIQ-FC街机/alpha18-device-ui-v2/piq_fc_arcade-0.31.0-alpha.18.jar','E4A9FC00A492A1E8C7FC4E89D9534A1FBE6A389B64438C393E52D6163AACEC47'),
    'sfc':(DELIVERY/'PIQ-SFC家用/0.1.0-alpha.5/piq_sfc_home-0.1.0-alpha.5.jar','82578C8DEF9567B1408D8B7F384E8DCC92D388498091ADC0EC308D56AF5D811C'),
    'native':(DELIVERY/'PIQ原生街机/0.1.0-alpha.5/piq_native_arcade-0.1.0-alpha.5.jar','11B8420C09BD44E942F555C7F01BCE14CF8666F8D455046B532875A9240FFA87'),
}
TARGETS={
    'fc':('piq-fc-arcade','piq_fc_arcade','0.31.0-alpha.19','PIQ-FC街机/alpha19-fc-core-addons'),
    'sfc':('piq-sfc-home','piq_sfc_home','0.1.0-alpha.6','PIQ-SFC家用/0.1.0-alpha.6'),
    'native':('piq-native-arcade','piq_native_arcade','0.1.0-alpha.6','PIQ原生街机/0.1.0-alpha.6'),
}

def require(condition,message):
    if not condition:raise ValueError(message)

def digest(data):return hashlib.sha256(data).hexdigest().upper()

def safe_path(path,existing=False):
    path=Path(os.path.abspath(path))
    for parent in (path,*path.parents):
        try:attributes=parent.lstat()
        except FileNotFoundError:continue
        require(not stat.S_ISLNK(attributes.st_mode) and not (getattr(attributes,'st_file_attributes',0)&0x400),'Symlink/reparse path rejected: '+str(parent))
    if existing:require(path.is_file(),'Not a regular input file: '+str(path))
    return path

def checked_zip(raw):
    require(len(raw)<=MAX_RAW,'Archive exceeds raw byte budget')
    entries={};infos=[];total=0
    with zipfile.ZipFile(io.BytesIO(raw)) as archive:
        require(len(archive.infolist())<=MAX_ENTRIES,'Archive has too many entries')
        for info in archive.infolist():
            name=info.orig_filename
            require(name and name==info.filename and not name.startswith(('/', '\\')) and '\\' not in name and ':' not in name
                    and not any(ord(c)<32 or ord(c)==127 for c in name)
                    and all(part not in ('','.','..') for part in name.rstrip('/').split('/')),'Unsafe ZIP path')
            require(name not in entries,'Duplicate ZIP entry: '+name)
            require(not info.flag_bits&1,'Encrypted ZIP entry')
            require(not stat.S_ISLNK(info.external_attr>>16),'ZIP symlink entry')
            require(not re.match(r'META-INF/[^/]+\.(SF|RSA|DSA|EC)$',name,re.I),'Signed source cannot be repacked')
            total+=info.file_size;require(total<=MAX_EXPANDED,'Archive exceeds expanded byte budget')
            data=archive.read(info)
            require(not info.is_dir() or not data,'Nonempty ZIP directory')
            entries[name]=data;infos.append(info)
        require(archive.testzip() is None,'ZIP CRC mismatch')
    return infos,entries

def read_jar(path):
    path=safe_path(path,True);require(path.stat().st_size<=MAX_RAW,'Archive exceeds raw byte budget')
    raw=path.read_bytes();infos,entries=checked_zip(raw)
    return digest(raw),infos,entries

def metadata(entries,key):
    _,mod_id,version,_=TARGETS[key]
    require(META in entries and MANIFEST in entries,'Missing mod metadata or manifest')
    value=tomllib.loads(entries[META].decode('utf-8'))
    require(value.get('modLoader')=='javafml' and value.get('loaderVersion')=='[4,)','Unexpected mod loader')
    require(len(value.get('mods',[]))==1 and value['mods'][0].get('modId')==mod_id
            and value['mods'][0].get('version')==version,'Wrong mod id/version, or a merged/internal mod was supplied as thin input')
    require('piq_retro_platform' not in value.get('dependencies',{}),'Standalone platform dependency is forbidden')
    for dependencies in value.get('dependencies',{}).values():
        require(not any(d.get('modId')=='piq_retro_platform' for d in dependencies),'Standalone platform dependency is forbidden')
    if key!='fc':
        deps=value.get('dependencies',{}).get(mod_id,[])
        fc=[d for d in deps if d.get('modId')=='piq_fc_arcade']
        require(len(fc)==1 and all(fc[0].get(k)==v for k,v in {'versionRange':'[0.31.0-alpha.19,0.32.0)','type':'required','side':'BOTH','ordering':'AFTER'}.items()),'Addon must require FC19 on both sides')
        if key=='sfc':
            core=[d for d in deps if d.get('modId')=='piq_sfc_arcade']
            require(len(core)==1 and core[0].get('versionRange')=='[0.2.0-alpha.6,0.3.0)' and core[0].get('type')=='required' and core[0].get('side')=='BOTH','Thin SFC must retain the frozen core dependency')
    manifest=entries[MANIFEST].decode('utf-8').replace('\r','')
    require(manifest.count('Implementation-Version: '+version+'\n')==1,'Wrong manifest version')
    return value

@dataclass
class Plan:
    key:str
    source:Path
    source_sha:str
    baseline:Path
    baseline_sha:str
    infos:list
    source_entries:dict
    final_entries:dict
    restored:dict

def plan(key,source_sha=None):
    project,mod_id,version,_=TARGETS[key]
    source=ROOT/project/'build/libs'/(mod_id+'-'+version+'.jar')
    source_hash,infos,built=read_jar(source)
    if source_sha is not None:require(re.fullmatch('[0-9A-Fa-f]{64}',source_sha) is not None and source_hash==source_sha.upper(),'Source SHA mismatch')
    baseline,expected=BASELINES[key];old_sha,_,old=read_jar(baseline)
    require(old_sha==expected,'SHA-frozen baseline changed: '+key)
    metadata(built,key)
    for name in built:
        require(not name.startswith(('private-qa/','qa/','tests/')) and '/test/' not in name,'Private/test entry in build')
        if name not in old:
            require(not name.endswith(('Test.class','Probe.class','TestRunner.class')),'New test/probe class in production build')
        require(not name.lower().endswith(('.nes','.sfc','.smc','.7z','.exe')),'ROM or unapproved executable in build')
        require(not name.lower().endswith('.jar'),'Nested platform/core/runtime JAR is forbidden')
        if key in ('sfc','native') and name.endswith('.class'):
            owner='sfchome' if key=='sfc' else 'nativearcade'
            require(name.startswith('cn/piq/'+owner+'/'),'Thin addon embeds a foreign class: '+name)
    artwork={n:data for n,data in old.items() if n.startswith('assets/') and not n.endswith('/')}
    current_artwork={n for n in built if n.startswith('assets/') and not n.endswith('/')}
    require(current_artwork==set(artwork),'Source asset inventory differs from frozen baseline')
    if key=='fc':require(len(artwork)==113,'FC18 asset inventory must contain exactly 113 files')
    else:require(all(built[n]==data for n,data in artwork.items()),'Addon artwork changed in this non-artwork release')
    final=dict(built);restored={}
    if key=='fc':
        for name,data in artwork.items():
            if built[name]!=data:restored[name]={'source_sha256':digest(built[name]),'frozen_sha256':digest(data)}
            final[name]=data
    require(set(final)==set(built),'Freezer attempted to inject/delete entries')
    require(all(final[n]==data for n,data in built.items() if n not in artwork),'Freezer attempted to alter class/core/runtime or non-artwork bytes')
    return Plan(key,source,source_hash,baseline,old_sha,infos,built,final,restored)

def verify(path,p):
    final_sha,_,actual=read_jar(path)
    require(set(actual)==set(p.final_entries),'Frozen entry inventory mismatch')
    require(all(actual[name]==data for name,data in p.final_entries.items()),'Frozen entry bytes mismatch')
    return final_sha

def freeze(p,check_only=False):
    _,mod_id,version,directory=TARGETS[p.key]
    out=safe_path(DELIVERY/directory/(mod_id+'-'+version+'.jar'))
    report_path=safe_path(out.with_suffix('.freeze.json'))
    require(not out.exists() and not report_path.exists(),'Refusing to overwrite a frozen artifact or report')
    result={'ok':True,'component':p.key,'thin_sfc_not_for_installation':p.key=='sfc','checked_at_utc':datetime.now(timezone.utc).isoformat(),
            'source':str(p.source),'source_sha256':p.source_sha,'baseline':str(p.baseline),'baseline_sha256':p.baseline_sha,
            'path':str(out),'source_and_final_entry_inventory_equal':True,'inherited_assets':sum(n.startswith('assets/') and not n.endswith('/') for n in p.final_entries),
            'restored_artwork':p.restored,'class_core_runtime_entries_injected_or_modified_by_freezer':0,
            'installed':False,'minecraft_started':False,'check_only':check_only}
    if check_only:return result
    out.parent.mkdir(parents=True,exist_ok=True);safe_path(out.parent)
    handle,name=tempfile.mkstemp(prefix='.alpha19-freeze-',suffix='.jar',dir=out.parent);os.close(handle);staged=Path(name)
    try:
        with zipfile.ZipFile(staged,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as archive:
            for info in p.infos:archive.writestr(info,p.final_entries[info.filename])
        staged_sha=verify(staged,p)
        require(read_jar(p.source)[0]==p.source_sha and read_jar(p.baseline)[0]==p.baseline_sha,'Input changed during freeze')
        with out.open('xb') as target,staged.open('rb') as stream:shutil.copyfileobj(stream,target)
        final_sha=verify(out,p);require(final_sha==staged_sha,'Frozen copy SHA mismatch')
        result.update(sha256=final_sha,bytes=out.stat().st_size,entries=len(p.final_entries))
        with report_path.open('x',encoding='utf-8') as stream:json.dump(result,stream,ensure_ascii=False,indent=2);stream.write('\n')
        return result
    finally:staged.unlink(missing_ok=True) # only this invocation's exact mkstemp file

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('component',choices=TARGETS)
    parser.add_argument('--source-sha256');parser.add_argument('--check-only',action='store_true');args=parser.parse_args()
    print(json.dumps(freeze(plan(args.component,args.source_sha256),args.check_only),ensure_ascii=True))
if __name__=='__main__':main()
