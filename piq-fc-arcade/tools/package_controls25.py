"""Package only frozen, final-JAR-tested unified controls files. Never install or launch a game."""
import argparse,json
from pathlib import Path
import prepare_controls25 as stage
from freeze_fc_core_alpha19 import digest,require,safe_path
from package_fc_core_alpha19 import PackagePlan,snapshot,json_document,check_ownership
from prepare_cabinet_multiplayer21 import clean
from freeze_fc_core_alpha19 import checked_zip
from package_interaction24 import (passing,report_bindings,check_audit,check_worker,check_multiplayer,
                                  parse_counts,test_xml,build,bind)

ROOT=stage.ROOT
DEFAULT_STAGE=ROOT/'piq-fc-arcade/build/review-controls25-v1'
OUT=ROOT/'制作Mod/03-街机模拟/统一键位与手柄-alpha25-测试包-20260911'
HELPER='piq-native-arcade/runtime/piq-native-helper.jar'

def check_controls(report,jars):
    require(report_bindings(report,jars)==set(jars),'Controls must bind all final mods')
    require(report['schema']=='piq-controls25-final-1' and report['production_compiled'] is False,'Wrong controls provenance')
    require(set(report['tests'])=={'keyboard','gamepad_and_layout','geometry'},'Incomplete controls groups')
    for name,total in (('keyboard',47),('gamepad_and_layout',19),('geometry',38)):
        t=report['tests'][name];passing(t)
        require(t['production_origin']=='final-jar-only' and t['production_compiled'] is False and t['origin_classes']>=79,'Missing real final class origins')
        aborted=t['tests_aborted'];require(aborted in ((0,1)if name=='gamepad_and_layout'else(0,)),'Unexpected abort count')
        require(t['tests_found']==total and t['tests_succeeded']==total-aborted and t['tests_failed']==t['tests_skipped']==0,'Incomplete/failed behavior tests')
        require(t['skipped_names']==[] and t['aborted_names']==(['symlinkConfigCannotReadOrOverwriteAnotherFile()']if aborted else[]),'Unexpected omitted test')
    m=report['mixin'];require(all(m[k] is True for k in ('client_only','registered_exactly_once','head_cancellable')),'Missing final Mixin registration')
    c=report['compatibility'];require(c['production_compiled'] is False and c['old_separate_core_on_classpath'] is False,'Compatibility used substitute owners')
    f=c['fml_discovery'];passing(f);require(f['assertions']>=28,'Missing actual FML discovery')
    codec=c['sfc_real_neoforge_outer_packet_codec'];passing(codec);require(codec['assertions']>=37,'Missing actual outer codec')
    require(set(c['hash_checked_temporary_copies'])==set(jars),'Incomplete compatibility origins')
    for kind,item in jars.items():
        copy=c['hash_checked_temporary_copies'][kind]
        require(copy['sha256']==item.sha256 and safe_path(copy['original'],True)==item.path and copy['byte_identical_before'] is True and copy['byte_identical_after'] is True,'Compatibility binding changed')

def check_mixin(report,jars):
    passing(report);bind(report['input'],jars['fc'],'mixin')
    require(report['schema']=='piq-real-keyboard-mixin-1' and report['mode']=='final-jar-only' and report['production_compiled'] is False,'Wrong Mixin provenance')
    p=report['probe'];passing(p)
    require(p['assertions']>=40 and p['prefix_instructions']==18 and p['original_instructions_preserved']==382 and p['gui_key_gates']==3,'Incomplete transformation evidence')
    require(all(p[k] is True for k in ('actual_mixin_transformer','router_before_keymapping_click','vanilla_gui_uses_consumed_clicks','cancel_return_before_vanilla')),'Missing real transform/order checks')
    require(p['minecraft_classes_defined']==0 and report['minecraft_started'] is False,'Unexpected Mixin test scope')
    entries=clean(checked_zip(jars['fc'].raw)[1])
    require(p['mixin_sha256']==report['input']['mixin_class_sha256']==digest(entries['cn/piq/fcarcade/mixin/KeyboardHandlerMixin.class']),'Mixin class binding differs')
    require(report['input']['mixin_config_sha256']==digest(entries['piq_fc_keyboard.mixins.json']),'Mixin config binding differs')

def check_zapper(report,jars):
    require(report_bindings(report,jars)=={'fc'} and report['mode']=='final-jar-only' and report['production_compiled'] is False,'Wrong gun-core provenance')
    origin=report['origin'];diagnostic=report['diagnostic'];passing(origin);passing(diagnostic)
    require(origin['production_origin']=='final-jar-only' and origin['production_compiled'] is False and origin['origin_assertions']==4,'Missing actual core origin')
    require(diagnostic['assertions']==61 and diagnostic['actual_wasm_core'] is True and report['commercial_rom_used'] is False and report['minecraft_started'] is False,'Missing actual isolated core diagnostics')
    entries=clean(checked_zip(jars['fc'].raw)[1])
    require(report['legacy_module_sha256']==digest(entries['core/nes_rust_wasm_bg.wasm']) and report['zapper_module_sha256']==digest(entries['core/nes_zapper_v1.wasm']),'Core resource bindings differ')

def plan(directory,reports,guide,counts):
    directory=safe_path(directory);require(directory.is_relative_to(ROOT) and directory.is_dir(),'Stage outside workspace or missing')
    required={'audit','worker','multiplayer','controls','mixin','zapper'}
    require(set(reports)==required,'Six independent final-artifact reports required')
    raw_reports={k:snapshot(p,8*1024*1024) for k,p in reports.items()}
    parsed={k:json_document(v.raw) for k,v in raw_reports.items()}
    staged=snapshot(directory/'stage-verification.json',8*1024*1024)
    frozen=json_document(staged.raw)
    expected_files,reproduced=stage.plan()
    require(frozen==reproduced,'Stage no longer matches exact source/baseline inputs')
    jars={k:snapshot(directory/name) for k,name in stage.NAMES.items()}
    helper=snapshot(directory/HELPER)
    for k,item in jars.items():require(item.raw==expected_files[stage.NAMES[k]],'Final JAR differs from reproduced stage '+k)
    require(helper.raw==expected_files[HELPER] and helper.sha256==stage.HELPER_SHA,'Native helper changed')
    ownership=check_ownership({k:clean(checked_zip(v.raw)[1])for k,v in jars.items()})
    require(ownership['classes']==frozen['unique_classes'],'Class ownership differs')
    check_audit(parsed['audit'],jars);check_worker(parsed['worker'],jars);check_multiplayer(parsed['multiplayer'],jars)
    check_controls(parsed['controls'],jars);check_mixin(parsed['mixin'],jars);check_zapper(parsed['zapper'],jars)
    inputs=[*raw_reports.values(),staged,*jars.values(),helper]
    transformed=snapshot(parsed['mixin']['transformed_artifact']['path'])
    require(transformed.sha256==parsed['mixin']['transformed_artifact']['sha256']==parsed['mixin']['probe']['transformed_sha256'],'Transformed bytecode evidence differs')
    inputs.append(transformed)
    for kind,(name,sha) in stage.PINNED.items():
        item=snapshot(stage.BASE/name);require(item.sha256==sha,'Baseline changed');inputs.append(item)
    inputs.append(snapshot(stage.BASE/HELPER))
    tests={}
    for kind,path in stage.BUILDS.items():
        item=snapshot(path);inputs.append(item)
        require(item.sha256==frozen['mods'][kind]['source_sha256'],'Build changed '+kind)
        tests[kind]={**test_xml(kind,counts[kind],inputs),'source_jar_sha256':item.sha256,'final_jar_sha256':jars[kind].sha256}
    document=snapshot(guide,1024*1024);inputs.append(document);require(document.raw.decode('utf-8-sig').strip(),'Missing guide')
    payloads={'mods/'+v.path.name:v.raw for v in jars.values()}
    payloads[HELPER]=helper.raw;payloads['先看这里.md']=document.raw
    payloads['checks/stage.json']=staged.raw
    payloads.update({'checks/'+k+'.json':v.raw for k,v in raw_reports.items()})
    payloads['checks/full-build.json']=json.dumps({'ok':True,'projects':tests,'installed':False,'minecraft_started':False},ensure_ascii=False,indent=2).encode('utf-8')
    payloads['SHA256.txt']=''.join(digest(raw)+'  '+n+'\n'for n,raw in sorted(payloads.items())).encode('utf-8')
    require(sum(n.startswith('mods/')for n in payloads)==3 and sum(n.endswith('.jar')for n in payloads)==4,'Wrong MOD/helper inventory')
    require(all(n.startswith(('mods/','checks/'))or n in (HELPER,'先看这里.md','SHA256.txt')for n in payloads),'Unexpected delivery file')
    summary={'schema':'piq-controls25-delivery-1','final_jars':{k:v.sha256 for k,v in jars.items()},'ownership':ownership,
     'files':{n:{'bytes':len(v),'sha256':digest(v)}for n,v in payloads.items()},'strict_stage_scopes':frozen['mods'],
     'installed':False,'minecraft_started':False,'live_multiplayer_tested_this_turn':False,
     'scope':'Unified local keyboard/gamepad controls, revised dual cabinet and isolated opt-in Zapper core prototype; no in-world lightgun session or GBA emulator.',
     'private_roms_bios_frames_or_states_included':False}
    return PackagePlan(payloads,tuple(inputs),summary)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--stage',type=Path,default=DEFAULT_STAGE);p.add_argument('--out',type=Path,default=OUT)
    for k in ('audit','worker','multiplayer','controls','mixin','zapper','guide'):p.add_argument('--'+k,type=Path,required=True)
    p.add_argument('--tests',action='append',required=True);p.add_argument('--check-only',action='store_true');a=p.parse_args()
    reports={k:getattr(a,k)for k in ('audit','worker','multiplayer','controls','mixin','zapper')}
    result=build(plan(a.stage,reports,a.guide,parse_counts(a.tests)),a.out,a.check_only)
    print(json.dumps(result,ensure_ascii=True))
if __name__=='__main__':main()
