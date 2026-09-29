"""Package the fixed independent GBA preview, audited against final FC30. Never installs."""
from pathlib import Path
from datetime import datetime, timezone
import argparse, io, json, os, sys, tempfile, zipfile, tomllib
ROOT=Path(__file__).resolve().parents[1]
WORKSPACE=ROOT.parent
sys.path.insert(0,str(WORKSPACE/'piq-fc-arcade/tools'))
from freeze_fc_core_alpha19 import checked_zip, digest, require, safe_path
from package_fc_core_alpha19 import snapshot, json_document, PackagePlan, verify_archive, revalidate_inputs

BUNDLE=ROOT/'build/preview-v4'
DELIVERY=WORKSPACE/'制作Mod/03-街机模拟'
MOD='piq_gba-0.1.0-alpha.1.jar'
PINNED={MOD:'331202E4DEA6BF0EB4C2C1BA8752A2F62886BF2007F7F3BB99902BE0D70B0A3E',
 'piq-gba/runtime/piq-gba-helper.jar':'AF687B20AFD470992F9C02C80173356E20E9D9E98FABDDCF3800979F11A4B28C',
 'piq-gba/runtime/jna-5.14.0.jar':'34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6',
 'piq-gba/runtime/mgba_libretro.dll':'D1BA96BC1AF23997D5C8003A6F6F8BE7ACBA9D770D4D42D14557AAEB469FA16B',
 'licenses-and-source/mgba-source.tar.gz':'396D749CCE8FE3358B29CBB1DB479B1816A151BD688EE45B1D241503CBC40243'}
LICENSES=('JNA-Apache-2.0.txt','JNA-LICENSE.txt','mgba-LICENSE','mgba-official-acquisition.json','mgba-source-verification.json','mgba-source.tar.gz','piq-gba-source.zip','PIQ-GPL-3.0.txt')

def bind(record,item,label):
    require(record.get('sha256')==item.sha256,'SHA mismatch '+label)
    require(safe_path(record.get('path',''),True)==item.path,'Path mismatch '+label)

def validate_audit(a,fc,mod,runtime):
    require(a.get('schema')=='piq-gba-final-bundle-1' and a.get('ok') is True,'Passing final GBA audit required')
    require(a.get('fixture_only') is not True and a.get('not_a_final_release_validation') is not True,'Fixture is not final evidence')
    require(a.get('production_compiled') is False and a.get('compiled_only_probes') is True,'Final production must not be recompiled')
    require(a.get('minecraft_started') is False and a.get('user_rom_or_save_used') is False,'Wrong test scope')
    bind(a['fc'],fc,'FC30');bind(a['gba'],mod,'GBA')
    require(set(a['runtime'])==set(runtime),'Exact audited runtime inventory')
    for name,item in runtime.items():require(a['runtime'][name]['sha256']==item.sha256 and a['runtime'][name]['bytes']==len(item.raw),'Runtime evidence differs '+name)
    core=a['core'];process=a['process'];fml=a['fml']
    for report in (core,process,fml):require(report.get('ok') is True and report.get('production_origin')=='final-jar-only','Non-final probe')
    require(core['assertions']>=98434 and core['core_version']=='0.11-219-e31759b' and core['actual_rgb565'] is True
            and core['native_sample_rate']==65536 and core['save_ram_bytes']==32768 and core['nonzero_samples']>1000
            and core['gameplay_compatibility_claimed'] is False and core['minecraft_started'] is False,'Core proof incomplete')
    require(process['assertions']>=27 and process['actual_child_process_protocol'] is True and process['actual_save_restart'] is True
            and process['minecraft_started'] is False and process['user_rom_or_save_used'] is False,'Process/save proof incomplete')
    require(fml['assertions']>=28 and fml['actual_fml_reader'] is True and fml['actual_annotation_scan'] is True
            and fml['actual_common_registration'] is True and fml['mod_entry_points_executed'] is False
            and fml['minecraft_or_native_core_started'] is False,'Actual FML/registration proof incomplete')

def check_source_zip(raw,build):
    _,source=checked_zip(raw)
    expected={name.replace('\\','/'):sha for name,sha in build['source_sha256'].items()}
    require(set(source)==set(expected),'Matching PIQ build source must be complete and exact')
    for name,data in source.items():require(digest(data)==expected[name],'Packaged source differs from compiled source '+name)
    require('tools/build_gba_preview.py' in source and 'src/main/resources/META-INF/neoforge.mods.toml' in source,'Build entry/metadata missing')
    return expected

def plan(fc_path,audit_path):
    fc=snapshot(fc_path);mod=snapshot(BUNDLE/MOD);audit=snapshot(audit_path,8*1024*1024)
    _,fc_entries=checked_zip(fc.raw)
    fc_meta=tomllib.loads(fc_entries['META-INF/neoforge.mods.toml'].decode('utf-8'))
    require({m['modId']:m['version'] for m in fc_meta['mods']}=={'piq_fc_arcade':'0.31.0-alpha.30'},'Exactly FC30 prerequisite required')
    selected={name:snapshot(BUNDLE/name,24*1024*1024) for name in PINNED}
    for name,sha in PINNED.items():require(selected[name].sha256==sha,'Frozen GBA v4/runtime/source changed '+name)
    runtime={Path(name).name:item for name,item in selected.items() if name.startswith('piq-gba/runtime/')}
    parsed=json_document(audit.raw);validate_audit(parsed,fc,mod,runtime)
    build=snapshot(BUNDLE/'build-and-process-qa.json');build_data=json_document(build.raw)
    require(build_data.get('ok') is True and build_data.get('source_production_compiled') is True,'Original new-addon build evidence missing')
    bind(build_data['gba'],mod,'compiled addon');require(build_data['runtime']==parsed['runtime'],'Built helper/runtime differs from final audit')
    licenses={name:snapshot(BUNDLE/'licenses-and-source'/name,24*1024*1024) for name in LICENSES}
    source_manifest=check_source_zip(licenses['piq-gba-source.zip'].raw,build_data)
    source_record=json_document(licenses['mgba-source-verification.json'].raw)
    require(source_record==parsed['source'] and source_record['ok'] is True
            and source_record['embedded_commit']=='e31759b24e7a4e3899285ff720d7b573ac328ae7'
            and source_record['source_sha256']==PINNED['licenses-and-source/mgba-source.tar.gz']
            and source_record['core_sha256']==runtime['mgba_libretro.dll'].sha256,'Corresponding official source identity missing')
    doc=snapshot(ROOT/'design/GBA单人街机试玩-使用说明.md');old_doc=snapshot(BUNDLE/'README.md')
    inputs=[fc,mod,audit,build,doc,old_doc,*selected.values(),*licenses.values()]
    for item in [fc,mod,build,old_doc,*selected.values(),*licenses.values()]:
        require(parsed['files_sha256'].get(str(item.path))==item.sha256,'Final audit did not snapshot exact input '+str(item.path))
    payloads={'mods/'+MOD:mod.raw,'先看这里.md':doc.raw,'docs/原型构建说明.md':old_doc.raw,
              'checks/final-gba-fc30.json':audit.raw,'checks/original-gba-build.json':build.raw}
    payloads.update({'piq-gba/runtime/'+name:item.raw for name,item in runtime.items()})
    payloads.update({'licenses-and-source/'+name:item.raw for name,item in licenses.items()})
    dependency={'required_mod':'piq_fc_arcade','tested_version':'0.31.0-alpha.30','sha256':fc.sha256,'main_mod_included':False,'instructions':'先另行安装配套FC30主包。本包仅新增GBA附属与独立运行库，不覆盖主包。'}
    payloads['checks/required-main-mod.json']=(json.dumps(dependency,ensure_ascii=False,indent=2)+'\n').encode('utf-8')
    payloads['SHA256.txt']=''.join(digest(raw)+'  '+name+'\n' for name,raw in sorted(payloads.items())).encode('utf-8')
    require(set(n for n in payloads if n.startswith('mods/'))=={'mods/'+MOD},'Only GBA addon belongs in mods')
    summary={'schema':'piq-gba-preview-delivery-1','gba_sha256':mod.sha256,'required_fc':dependency,
             'runtime_sha256':{n:v.sha256 for n,v in runtime.items()},'matched_source_sha256':source_manifest,
             'files':{n:{'bytes':len(v),'sha256':digest(v)} for n,v in payloads.items()},
             'included_corresponding_source':True,'installed':False,'minecraft_started':False,'handheld_implemented':False,'multiplayer_implemented':False}
    return PackagePlan(payloads,tuple(inputs),summary)

def build(p,output,check_only=False):
    output=safe_path(output);verification=safe_path(output.with_suffix('.verification.json'))
    require(output.parent==safe_path(DELIVERY) and output.suffix=='.zip','ZIP must be in the explicit delivery directory')
    require(not output.exists() and not verification.exists(),'Refusing to overwrite package or verification')
    revalidate_inputs(p);result=dict(p.summary,ok=True,path=str(output),check_only=check_only,checked_at_utc=datetime.now(timezone.utc).isoformat())
    if check_only:return result
    output.parent.mkdir(parents=True,exist_ok=True);safe_path(output.parent)
    with tempfile.TemporaryDirectory(prefix='piq-gba-package-') as temp:
        staged=Path(temp)/'candidate.zip'
        with zipfile.ZipFile(staged,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
            for name,raw in sorted(p.payloads.items()):
                info=zipfile.ZipInfo(name,(2026,9,11,0,0,0));info.create_system=3;info.external_attr=0o100644<<16
                z.writestr(info,raw,compress_type=zipfile.ZIP_DEFLATED,compresslevel=9)
        raw=staged.read_bytes();verify_archive(raw,p);revalidate_inputs(p)
        with output.open('xb') as target:target.write(raw)
    final=snapshot(output);verify_archive(final.raw,p);require(final.raw==raw,'Package readback changed');revalidate_inputs(p)
    result.update(bytes=len(final.raw),sha256=final.sha256)
    with verification.open('x',encoding='utf-8') as target:json.dump(result,target,ensure_ascii=False,indent=2);target.write('\n')
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--fc',required=True,type=Path);p.add_argument('--audit',required=True,type=Path)
    p.add_argument('--output',required=True,type=Path);p.add_argument('--check-only',action='store_true');args=p.parse_args()
    print(json.dumps(build(plan(args.fc,args.audit),args.output,args.check_only),ensure_ascii=True))
