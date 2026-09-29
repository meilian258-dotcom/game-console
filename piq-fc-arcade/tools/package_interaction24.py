"""Deliver only audited FC24/SFC13/Native8 files. Never install, launch Minecraft or shut down.

All report paths and exact full-test counts are explicit command arguments.
The packet/input report may cover FC alone; SFC worker reports must name FC+SFC.
Every supplied report must pass and bind the actual final JAR(s) it claims.
"""
from __future__ import annotations
import argparse, io, json, re, xml.etree.ElementTree as ET, zipfile
from pathlib import Path
from freeze_fc_core_alpha19 import META, MANIFEST, checked_zip, digest, require, safe_path
from package_fc_core_alpha19 import PackagePlan, check_ownership, json_document, snapshot, verify_archive, revalidate_inputs
import prepare_interaction24 as stage

ROOT=stage.ROOT
STAGE=ROOT/'piq-fc-arcade/build/review-interaction24-v1'
OUT=ROOT/'制作Mod/03-街机模拟/FC统一操作-alpha24-测试包-20260911'
HELPER='piq-native-arcade/runtime/piq-native-helper.jar'
PROJECTS={'fc':'piq-fc-arcade','sfc':'piq-sfc-home','native':'piq-native-arcade'}
EXPECTED_MODS={'fc':{'piq_fc_arcade':'0.31.0-alpha.24'},
 'sfc':{'piq_sfc_arcade':'0.2.0-alpha.6','piq_sfc_home':'0.1.0-alpha.13'},
 'native':{'piq_native_arcade':'0.1.0-alpha.8'}}

def passing(report):
    require(report.get('ok') is True or report.get('passed') is True,'Report did not pass')
    for key in ('ok','passed'):
        if key in report:require(report[key] is True,'Conflicting/nonboolean report outcome')
    require(report.get('fixture_only') is not True and report.get('not_a_final_release_validation') is not True,
            'Fixture is not final evidence')

def bind(record,item,label):
    require(record.get('sha256')==item.sha256,'Report SHA differs: '+label)
    require(safe_path(record.get('path',''),True)==item.path,'Report artifact path differs: '+label)

def report_bindings(report,jars):
    passing(report)
    if isinstance(report.get('jars'),dict):
        require(report['jars'] and set(report['jars'])<=set(jars),'Unexpected report JAR owners')
        for kind,record in report['jars'].items():bind(record,jars[kind],kind)
        return set(report['jars'])
    require(isinstance(report.get('jar'),str),'Report has no final-JAR binding')
    path=safe_path(report['jar'],True)
    kinds=[kind for kind,item in jars.items() if path==item.path]
    require(len(kinds)==1,'Report points to a non-delivery JAR')
    bind({'path':report['jar'],'sha256':report.get('sha256')},jars[kinds[0]],kinds[0])
    return set(kinds)

def check_audit(report,jars):
    require(report_bindings(report,jars)=={'fc'},'FC consent/input audit must identify exactly final FC24')
    p=report['probe']
    require(p.get('ok') is True and p['pure_tests']>=37 and p['assertions']>0,
            'Missing final-JAR consent/input tests')
    require(p['actual_production_registration'] is True and p['actual_neoforge_outer_codec'] is True,
            'Missing real registered outer codecs')
    require(p['production_origin']=='final-jar-only' and p['production_compiled'] is False
            and report['compiled_only_probes_and_tests'] is True,'Production was substituted in final audit')

def check_worker(report,jars):
    require(report_bindings(report,jars)=={'fc','sfc'},'SFC worker must bind final FC24 and SFC13')
    require(report['mode']=='final-jar-only' and report['production_compiled'] is False,'Worker used substitute production')
    p=report['actual']
    require(p.get('ok') is True and p['assertions']>=38 and p['actual_playback_core_started'] is True,
            'Missing actual SFC playback/core regression')
    require(p['production_origin']=='final-jar-only' and p['production_compiled'] is False
            and p['host_frames_run_after_callback_failure']>0 and p['p2_media_calls']==0
            and p['suspend_resume_media_sequence_monotonic'] is True,'Worker media lifecycle regression')
    require(all(p[k] is False for k in ('minecraft_started','network_socket_opened','audio_device_opened','commercial_rom_used')),
            'Unexpected worker test scope')

def check_multiplayer(report,jars):
    require(report_bindings(report,jars)=={'fc','sfc'},'SFC multiplayer must bind final FC24 and SFC13')
    require(report['mode']=='final-jar-only' and report['production_compiled'] is False,'Multiplayer used substitute production')
    p=report['actual']
    require(p.get('passed') is True and p['actual_playback_worker_jvms']==2
            and p['coordinator_assertions']>=782 and p['worker_assertions']>=796
            and p['actual_codec_roundtrips']>=315 and p['matched_video_and_pcm_frames']>=184,
            'Missing real two-worker codec/frame/audio checks')
    require(p['queued_ready_suppressed_after_close'] is True and p['host_worker_restart_count']==0
            and p['actual_button_port_cases']>=24 and len(p['joins'])>=2,'Missing join/rejoin/input lifecycle checks')
    require(all(report[k] is False for k in ('minecraft_started','network_socket_opened','audio_device_opened','commercial_roms','installed')),
            'Unexpected multiplayer test scope')

def parse_counts(values):
    counts={}
    for value in values:
        match=re.fullmatch(r'(fc|sfc|native)=([0-9]+)/([0-9]+)',value)
        require(match is not None,'Expected --tests fc=TOTAL/SKIPPED (and sfc/native)')
        kind,total,skip=match.groups();total=int(total);skip=int(skip)
        require(kind not in counts and total>0 and 0<=skip<total,'Invalid/duplicate full-test expectation')
        counts[kind]=(total,skip)
    require(set(counts)==set(PROJECTS),'All three exact full-test expectations required')
    return counts

def test_xml(kind,expected,inputs):
    counts=dict(tests=0,failures=0,errors=0,skipped=0);hashes={}
    paths=sorted((ROOT/PROJECTS[kind]/'build/test-results/test').glob('TEST-*.xml'))
    require(paths,'Missing full test XML '+kind)
    for path in paths:
        item=snapshot(path,8*1024*1024);inputs.append(item);hashes[path.name]=item.sha256
        xml=ET.fromstring(item.raw);require(xml.tag=='testsuite','Unexpected test XML root')
        for key in counts:
            value=int(xml.attrib[key]);require(value>=0,'Negative test count');counts[key]+=value
    require((counts['tests'],counts['skipped'])==tuple(expected)
            and counts['failures']==counts['errors']==0,'Full-test result differs/fails '+kind)
    return {**counts,'test_xml_sha256':hashes}

def plan(stage_dir,audit,worker,multiplayer,guide,counts,evidence=()):
    require(set(counts)==set(PROJECTS),'Three build count expectations required')
    stage_dir=safe_path(stage_dir);require(stage_dir.is_relative_to(ROOT),'Stage must stay in workspace')
    report_paths={'stage':stage_dir/'stage-verification.json','final-fc-consent-input-codec':Path(audit),
                  'final-sfc-worker':Path(worker),'final-sfc-multiplayer':Path(multiplayer)}
    for spec in evidence:
        label,separator,path=spec.partition('=')
        require(separator and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_-]{0,47}',label)
                and label not in report_paths and len(report_paths)<12,'Invalid/duplicate/too many evidence labels')
        report_paths[label]=Path(path)
    reports={k:snapshot(p,8*1024*1024) for k,p in report_paths.items()}
    parsed={k:json_document(s.raw) for k,s in reports.items()}
    staged=parsed['stage'];passing(staged)
    require(staged['schema']=='piq-interaction24-stage-1' and staged['installed'] is False
            and staged['minecraft_started'] is False,'Wrong stage provenance')
    jars={k:snapshot(stage_dir/name) for k,name in stage.NAMES.items()}
    baseline={k:snapshot(stage.BASE/name) for k,(name,_) in stage.PINNED.items()}
    inputs=[*reports.values(),*jars.values(),*baseline.values()]
    helper=snapshot(stage_dir/HELPER);inputs.append(helper);require(helper.sha256==stage.HELPER_SHA,'Helper changed')
    files={stage.NAMES[k]:{'bytes':len(v.raw),'sha256':v.sha256} for k,v in jars.items()}
    files[HELPER]={'bytes':len(helper.raw),'sha256':helper.sha256}
    require(staged['files']==files and set(staged['mods'])==set(jars),'Stage inventory/SHA differs')
    entries={k:stage.clean(checked_zip(s.raw)[1]) for k,s in jars.items()};scopes={};mod_owners={}
    for kind,old in baseline.items():
        require(old.sha256==stage.PINNED[kind][1]==staged['mods'][kind]['baseline_sha256'],'Wrong frozen baseline '+kind)
        before=stage.clean(checked_zip(old.raw)[1]);after=entries[kind]
        stage.metadata(kind,before,after);scopes[kind]=stage.classify(kind,before,after)
        require(scopes[kind]==staged['mods'][kind]['scope'],'Stage delta not reproduced '+kind)
        old_version={'fc':'0.31.0-alpha.23','sfc':'0.1.0-alpha.12','native':'0.1.0-alpha.7'}[kind]
        require(after[MANIFEST]==before[MANIFEST].replace(old_version.encode(),stage.VERSIONS[kind].encode()),'Unexpected manifest delta '+kind)
        import tomllib
        metadata=tomllib.loads(after[META].decode('utf-8'));mods=metadata['mods']
        actual={m['modId']:m['version'] for m in mods}
        require(actual==EXPECTED_MODS[kind] and len(actual)==len(mods),'Wrong/duplicate legacy mod IDs '+kind)
        for mod in actual:require(mod not in mod_owners,'Duplicate mod ID');mod_owners[mod]=kind
    ownership=check_ownership(entries);require(ownership['classes']==staged['unique_classes'],'Stage class count differs')
    check_audit(parsed['final-fc-consent-input-codec'],jars)
    check_worker(parsed['final-sfc-worker'],jars);check_multiplayer(parsed['final-sfc-multiplayer'],jars)
    for key,report in parsed.items():
        if key not in ('stage','final-fc-consent-input-codec','final-sfc-worker','final-sfc-multiplayer'):report_bindings(report,jars)
    builds={}
    for kind,path in stage.BUILDS.items():
        built=snapshot(path);inputs.append(built);declared=staged['mods'][kind]['source_sha256']
        require(built.sha256==declared,'Build changed since staging '+kind)
        builds[PROJECTS[kind]]={**test_xml(kind,counts[kind],inputs),'build_jar':str(built.path),
            'source_jar_sha256':declared,'build_jar_sha256':built.sha256,'final_jar_sha256':jars[kind].sha256}
    document=snapshot(guide,1024*1024);inputs.append(document)
    require(document.raw.decode('utf-8-sig').strip(),'Empty interaction guide')
    payloads={'mods/'+s.path.name:s.raw for s in jars.values()};payloads[HELPER]=helper.raw
    payloads['先看这里.md']=document.raw;payloads.update({'checks/'+k+'.json':s.raw for k,s in reports.items()})
    payloads['checks/full-build.json']=json.dumps({'ok':True,'projects':builds,'helper_sha256':helper.sha256,
        'minecraft_started':False,'installed':False},ensure_ascii=False,indent=2).encode('utf-8')
    payloads['SHA256.txt']=''.join(digest(raw)+'  '+name+'\n' for name,raw in sorted(payloads.items())).encode('utf-8')
    require(sum(n.startswith('mods/') for n in payloads)==3 and sum(n.endswith('.jar') for n in payloads)==4,
            'Exactly three MODs and one non-mod helper required')
    summary={'schema':'piq-interaction24-delivery-1','final_jars':{k:v.sha256 for k,v in jars.items()},
        'helper_sha256':helper.sha256,'mod_owners':mod_owners,'ownership':ownership,'strict_stage_scopes':scopes,
        'files':{n:{'bytes':len(raw),'sha256':digest(raw)} for n,raw in payloads.items()},
        'installed':False,'minecraft_started':False,'shutdown_requested':False,'live_multiplayer_tested_this_turn':False,
        'scope':'Interaction lifecycle and host consent alignment only; not identical fixed key maps, saves, or host handoff capabilities.'}
    return PackagePlan(payloads,tuple(inputs),summary)

def build(package,output=OUT,check_only=False):
    folder=safe_path(output);archive_path=safe_path(folder.with_suffix('.zip'));report_path=safe_path(folder.with_suffix('.verification.json'))
    require(folder.is_relative_to(ROOT) and folder!=ROOT and all(not p.exists() for p in (folder,archive_path,report_path)),
            'Refusing existing/outside delivery')
    revalidate_inputs(package);result=dict(package.summary,ok=True,path=str(archive_path),check_only=check_only)
    if check_only:return result
    buffer=io.BytesIO()
    with zipfile.ZipFile(buffer,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9)as archive:
        for name,raw in sorted(package.payloads.items()):
            info=zipfile.ZipInfo(name,(2026,9,11,0,0,0));info.create_system=3;info.external_attr=0o100644<<16
            info.compress_type=zipfile.ZIP_DEFLATED;archive.writestr(info,raw)
    raw=buffer.getvalue();verify_archive(raw,package);revalidate_inputs(package);folder.mkdir(parents=True)
    for name,value in package.payloads.items():
        path=safe_path(folder/name);require(path.is_relative_to(folder),'Output escaped delivery');path.parent.mkdir(parents=True,exist_ok=True)
        with path.open('xb')as stream:stream.write(value)
        require(snapshot(path).raw==value,'Readback failed '+name)
    with archive_path.open('xb')as stream:stream.write(raw)
    final=snapshot(archive_path);verify_archive(final.raw,package);require(final.raw==raw,'ZIP readback mismatch');revalidate_inputs(package)
    result.update(bytes=len(raw),sha256=final.sha256)
    with report_path.open('x',encoding='utf-8')as stream:json.dump(result,stream,ensure_ascii=False,indent=2)
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--stage',type=Path,default=STAGE);parser.add_argument('--out',type=Path,default=OUT)
    for name in ('audit','worker','multiplayer','guide'):parser.add_argument('--'+name,type=Path,required=True)
    parser.add_argument('--tests',action='append',required=True);parser.add_argument('--evidence',action='append',default=[])
    parser.add_argument('--check-only',action='store_true');args=parser.parse_args()
    package=plan(args.stage,args.audit,args.worker,args.multiplayer,args.guide,parse_counts(args.tests),args.evidence)
    print(json.dumps(build(package,args.out,args.check_only),ensure_ascii=True))

if __name__=='__main__':main()
