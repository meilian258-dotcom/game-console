"""Local matched delivery with offline runtime pack, corresponding source and a standalone HTML tutorial."""
import argparse, hashlib, json, os, re, shutil, sys, zipfile
from pathlib import Path
import build_audit34 as b
from build_runtime37 import ROOT, NAMES
from freeze_fc_core_alpha19 import digest, require, safe_path, read_jar

def file_sha(path):
    h=hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda:f.read(1024*1024),b''):h.update(chunk)
    return h.hexdigest().upper()

def archive(path, entries):
    require(not path.exists(),'Never overwrite artifact '+str(path))
    path.parent.mkdir(parents=True,exist_ok=True)
    with zipfile.ZipFile(path,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=6,allowZip64=False) as z:
        for name,source in sorted(entries.items()):
            require(not name.startswith(('/','\\')) and '\\' not in name and '..' not in name.split('/'),'Unsafe entry')
            info=zipfile.ZipInfo(name,(2026,9,13,0,0,0));info.external_attr=0o100644<<16
            info.compress_type=zipfile.ZIP_STORED if name.endswith(('.zip','.tar.gz','.jar')) else zipfile.ZIP_DEFLATED
            if isinstance(source,bytes):z.writestr(info,source)
            else:
                with Path(source).open('rb') as f,z.open(info,'w') as out:shutil.copyfileobj(f,out,1024*1024)
    with zipfile.ZipFile(path) as z:
        require(z.testzip() is None and len(z.namelist())==len(entries),'ZIP integrity')
    return dict(path=str(path),bytes=path.stat().st_size,sha256=file_sha(path),entries=len(entries))

def runtime(out):
    source=ROOT/'outputs/runtime37/provenance-existing-v1.json';raw=source.read_bytes();inventory=json.loads(raw)
    require(inventory['ok'],'No runtime provenance');files=inventory['runtime_files'];require(len(files)==9,'Nine fixed files')
    entries={}
    for name,item in files.items():
        path=safe_path(Path(item['path']),True)
        require(path.stat().st_size==item['bytes'] and file_sha(path)==item['sha256'],'Runtime source drift '+name)
        entries[name]=path
    out=safe_path(out);require(out.is_relative_to(ROOT/'piq-fc-arcade/build') and not out.exists(),'New runtime stage required')
    out.mkdir(parents=True)
    result=archive(out/'piq-runtime-pack-v1.zip',entries)
    with zipfile.ZipFile(result['path']) as z:
        for name,item in files.items():require(digest(z.read(name))==item['sha256'],'Runtime archive pin '+name)
    require(source.read_bytes()==raw,'Provenance changed')
    receipt=dict(schema='piq-runtime37-offline-pack-1',ok=True,pack=result,files=files,
        provenance_sha256=digest(raw),installed=False,native_core_started=False)
    with (out/'runtime-pack-witness.json').open('x',encoding='utf-8') as f:json.dump(receipt,f,ensure_ascii=False,indent=2)
    print(json.dumps(receipt['pack'],ensure_ascii=False))

def package(a):
    stage=safe_path(a.stage);witness=json.loads((stage/'build-witness.json').read_bytes())
    require(witness['ok'] and witness['schema']=='piq-runtime37-build-1' and witness['inputs']==b.inputs(),'Final source fence')
    runtime_stage=safe_path(a.runtime);rw=json.loads((runtime_stage/'runtime-pack-witness.json').read_bytes())
    pack=runtime_stage/'piq-runtime-pack-v1.zip';require(rw['ok'] and file_sha(pack)==rw['pack']['sha256'],'Runtime pack drift')
    entries={}
    for kind,name in NAMES.items():
        path=stage/name;require(read_jar(path)[0]==witness['mods'][kind]['sha256'],'Mod pin '+kind);entries['mods/'+name]=path
    tutorial=ROOT/'piq-fc-arcade/design/PIQ游戏机玩家教程-FC37.html'
    tutorial_check=ROOT/'outputs/runtime37/tutorial-final-v1.json'
    tq=json.loads(tutorial_check.read_bytes())
    require(tq.get('ok') and tq['sha256']==file_sha(tutorial),'Tutorial changed after validation')
    entries['玩家教程.html']=tutorial;entries['piq-runtime-packs/piq-runtime-pack-v1.zip']=pack
    entries['checks/tutorial-final-v1.json']=tutorial_check
    entries['安装说明.txt']='''PIQ FC37 配套测试版
FC37 + SFC21 + Native13 + GBA5，请客户端与服务器成套更新。保留旧数据和存档备份；mods 中不要同时放旧同名版本。
把 mods 放进实际游戏实例，piq-runtime-packs 与 mods 同级。退出世界，在 模组 > PIQ FC Arcade > 设置 > 运行环境 点击 一键补齐。
离线包不解压，不放mods内。没有联网下载；不含ROM/BIOS。仅补缺失、已有异版本不覆盖。NTFS/Windows x64 客户端使用。
对应核心源码与许可证随本目录另一个“源码”ZIP提供，请一并保留及转发；源码包不放游戏目录。
教程请单独打开 玩家教程.html，可离线阅读或打印。本次没有替换用户实例、启动游戏或发布正式服；真实多人请先在备份世界验收。
'''.encode('utf-8')
    entries['checks/build-witness.json']=stage/'build-witness.json';entries['checks/runtime-pack-witness.json']=runtime_stage/'runtime-pack-witness.json'
    schemas=set()
    for report in a.evidence:
        raw=report.read_bytes();r=json.loads(raw)
        require(r.get('ok') and r.get('mode')=='final-jar-only' and r.get('production_compiled') is False,'Need final JAR QA '+str(report))
        sha=r.get('jars',{}).get('fc',{}).get('sha256',r.get('sha256'))
        require(sha==witness['mods']['fc']['sha256'],'Wrong QA archive '+str(report));schemas.add(r.get('schema'))
        entries['checks/'+report.name]=raw
    required_schemas={'piq-runtime37-common-1','piq-runtime37-pack-1','piq-runtime37-screen-1',
        'piq-device-notices37-1','piq-furniture37-creative-1','piq-runtime37-addons-1',
        'piq-game-reuse35-final-1','piq-furniture36-common-1'}
    require(required_schemas<=schemas,'Missing final evidence: '+str(sorted(required_schemas-schemas)))
    inv=json.loads((ROOT/'outputs/runtime37/provenance-existing-v1.json').read_bytes())
    for path,pin in inv['license_notices'].items():
        p=Path(path);require(file_sha(p)==pin,'License pin')
        # Prefix retains duplicate LICENSE filenames from different components.
        name='licenses/'+('mgba/' if 'mgba-' in p.name else 'native/')+p.name
        require(name not in entries,'License name duplicate');entries[name]=p
    sources={};source_fence={}
    def add(path,name):
        require(name not in sources,'Duplicate source '+name);safe_path(path,True)
        sources[name]=path;source_fence[str(path)]=file_sha(path)
    excludes={'.git','.gradle','.toolchains','target','__pycache__','node_modules','build'}
    for project in ['piq-fc-arcade','piq-retro-platform','piq-native-arcade','piq-sfc-arcade','piq-sfc-home','piq-gba']:
        base=ROOT/project
        for sub in ['src','gradle','native','tools','helper/src']:
            for path in sorted((base/sub).rglob('*')):
                rel=path.relative_to(base)
                if path.is_file() and not any(part in excludes for part in rel.parts):
                    if sub=='tools' and path.suffix not in {'.py','.java','.md','.ps1','.bat','.json'}:continue
                    add(path,'source/'+project+'/'+rel.as_posix())
        for name in ['build.gradle','settings.gradle','gradle.properties','gradlew','gradlew.bat','LICENSE','README.md','THIRD_PARTY_NOTICES.md','ARCADEMOD_ASSET_NOTICE.md']:
            if (base/name).is_file():add(base/name,'source/'+project+'/'+name)
    # Preserve the full previously verified native corresponding source, not just an upstream URL.
    folders={
      'native-corresponding-source':Path(inv['source_delivery_reuse']['neogeo_modified'].split(' (retain')[0]),
      'gba-corresponding-source':ROOT/'piq-gba/build/handheld-v3-3/licenses-and-source'}
    for label,folder in folders.items():
        for path in sorted(folder.rglob('*')):
            if path.is_file():add(path,label+'/'+path.relative_to(folder).as_posix())
    for name,item in inv['source_archives'].items():require(file_sha(Path(item['path']))==item['sha256'],'Native source pin')
    for p in inv['snapshot_final_five_patches']:require(file_sha(Path(p['path']))==p['sha256'],'Native patch pin')
    for name,(draft,frozen) in __import__('freeze_fc_compact_alpha20').RESTORE.items():
        sources['release-assets/piq-fc-arcade/'+name]=read_jar(stage/NAMES['fc'])[2][name]
    sources['源码说明.txt']='''本次FC37/Native13/SFC21/GBA5对应源码及未改运行库的原始源码、补丁和构建说明。
native-corresponding-source 保留MAME原commit完整源、NEOGEO五个最终补丁、helper、前提和构建记录；通用MAME使用同一原源码但不套NEOGEO定制补丁。
gba-corresponding-source 保留mGBA原源及许可、helper源和获取校验记录。JNA沿用Apache-2.0选项。
不包含用户ROM、BIOS、游戏存档。未在本轮重建第三方DLL，不宣称二进制可重现构建。工具包含历史冻结包/本机环境依赖。
两张历史FC手柄草稿与发行资源不同，release-assets为冻结发行资源，不反向覆盖用户草稿。
'''.encode()
    sources.update({n:v for n,v in entries.items() if n.startswith(('licenses/','checks/'))});sources['玩家教程.html']=tutorial
    for path in [ROOT/'piq-fc-arcade/design/工作笔记-20260913-首装环境与安静交互.md',ROOT/'outputs/runtime37/provenance-existing-v1.json']:
        add(path,'design/'+path.name)
    require(witness['inputs']==b.inputs(),'Source fence before pack')
    name=a.name;require(re.fullmatch(r'[\w\u4e00-\u9fff-]{1,100}',name),'Safe name')
    target=ROOT/'制作Mod/03-街机模拟'
    source_result=archive(target/(name+'-源码.zip'),sources)
    test_result=archive(target/(name+'.zip'),entries)
    guide=target/('PIQ游戏机玩家教程-FC37.html');require(not guide.exists(),'New standalone guide required');shutil.copyfile(tutorial,guide)
    require(all(file_sha(Path(p))==pin for p,pin in source_fence.items()) and witness['inputs']==b.inputs(),'Inputs changed during packaging')
    result=dict(schema='piq-runtime37-delivery-1',ok=True,test_package=test_result,source_package=source_result,
        tutorial=dict(path=str(guide),sha256=file_sha(guide)),mods=witness['mods'],installed=False,published=False,minecraft_started=False,shutdown_scheduled=False)
    with (target/(name+'.verification.json')).open('x',encoding='utf-8') as f:json.dump(result,f,ensure_ascii=False,indent=2)
    print(json.dumps({k:v for k,v in result.items() if k!='mods'},ensure_ascii=False))

def main():
    sys.stdout.reconfigure(encoding='utf-8');p=argparse.ArgumentParser();sub=p.add_subparsers(dest='mode',required=True)
    r=sub.add_parser('runtime');r.add_argument('--output',required=True,type=Path)
    f=sub.add_parser('delivery');f.add_argument('--stage',required=True,type=Path);f.add_argument('--runtime',required=True,type=Path)
    f.add_argument('--evidence',action='append',required=True,type=Path);f.add_argument('--name',required=True)
    a=p.parse_args();runtime(a.output) if a.mode=='runtime' else package(a)

if __name__=='__main__':main()
