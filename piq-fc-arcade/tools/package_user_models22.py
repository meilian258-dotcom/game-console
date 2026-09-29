"""Deliver exact model22 audited artifacts, with no installation or existing-file overwrite."""
import argparse,io,json,xml.etree.ElementTree as ET,zipfile
from pathlib import Path
from freeze_fc_core_alpha19 import safe_path,digest,require
from package_fc_core_alpha19 import snapshot,json_document,PackagePlan,verify_archive,revalidate_inputs
import prepare_user_models22 as stage

ROOT=stage.ROOT
STAGE=ROOT/'piq-fc-arcade/build/review-user-models22-v1'
OUT=ROOT/'制作Mod/03-街机模拟/用户模型替换-alpha22-测试包-20260911'
REPORTS={
 'stage':'piq-fc-arcade/build/review-user-models22-v1/stage-verification.json',
 'final-audit':'piq-fc-arcade/design/user-models-20260911/final-models22-audit.json',
 'source-audit':'piq-fc-arcade/design/user-models-20260911/independent-incoming-audit.json',
 'dual-geometry':'piq-fc-arcade/design/user-models-20260911/dual-integration-qa-v3/verification.json',
 'sfc-conversion':'piq-sfc-home/design/user-sfc-20260911/conversion-audit.json',
 'sfc-pose':'piq-sfc-home/design/user-sfc-20260911/java-pose-animation-audit.json',
 'sfc-final-jar':'piq-sfc-home/design/user-sfc-20260911/final-jar-model-audit.json'}
PREVIEWS={
 '预览/双人街机.png':'piq-fc-arcade/design/user-models-20260911/dual-integration-qa-v3/contact-sheet.png',
 '预览/SFC总览.png':'piq-sfc-home/design/user-sfc-20260911/overview.png',
 '预览/SFC握姿与按键.png':'piq-sfc-home/design/user-sfc-20260911/grip-detail-1296.png',
 '预览/SFC到电视AV线.png':'piq-sfc-home/design/user-sfc-20260911/multi-out-av.png'}

def plan():
    reports={k:snapshot(ROOT/v,8*1024*1024) for k,v in REPORTS.items()}
    parsed={k:json_document(v.raw) for k,v in reports.items()}
    for key,r in parsed.items():require(r.get('ok') is True,'Missing passing report: '+key)
    final=parsed['final-audit'];require(final['schema']=='piq-user-models22-independent-1' and final['production_compiled'] is False,'Wrong final audit')
    require(final['ownership']['duplicate_classes']==0 and final['frozen_sfc6_entries_preserved']>=60,'Runtime/owner regression')
    require(len(final['negative_controls'])>=6 and final['probes'].get('geometry') and final['probes'].get('fml_and_outer_codec'),'Final probes missing')
    require(final['installed'] is False and parsed['stage']['installed'] is False,'Unexpected install claim')
    jars={k:snapshot(STAGE/name) for k,name in stage.NAMES.items()}
    for kind,artifact in jars.items():
        require(artifact.sha256==parsed['stage']['files'][stage.NAMES[kind]]['sha256']==final['jars'][kind]['sha256'],'JAR not exactly audited '+kind)
        require(safe_path(final['jars'][kind]['path'],True)==safe_path(artifact.path,True),'Audited path differs '+kind)
    sfc_test=parsed['sfc-final-jar']
    require(sfc_test['final_jar_sha256']==jars['sfc'].sha256 and sfc_test['junit_tests']==30
            and sfc_test['production_source_compiled'] is False and sfc_test['production_origin']=='final-jar-only',
            'SFC final model tests were not run against delivery JAR')
    helper=snapshot(STAGE/'piq-native-arcade/runtime/piq-native-helper.jar')
    require(jars['native'].sha256==stage.BASELINES['native'][1] and helper.sha256==stage.HELPER_SHA,'Native/helper unexpectedly changed')
    inputs=[*reports.values(),*jars.values(),helper]
    builds={}
    for kind,path in stage.BUILDS.items():
        built=snapshot(path);inputs.append(built)
        require(built.sha256==parsed['stage']['mods'][kind]['source_sha256'],'Tested build changed '+kind)
    for project,expected in [('piq-fc-arcade',(928,7)),('piq-sfc-home',(219,0))]:
        counts=dict(tests=0,failures=0,errors=0,skipped=0);hashes={}
        for path in sorted((ROOT/project/'build/test-results/test').glob('TEST-*.xml')):
            s=snapshot(path,8*1024*1024);inputs.append(s);hashes[path.name]=s.sha256;x=ET.fromstring(s.raw)
            for key in counts:counts[key]+=int(x.attrib[key])
        require((counts['tests'],counts['skipped'])==expected and counts['failures']==counts['errors']==0,'Unpassed full test run '+project)
        builds[project]={**counts,'test_xml_sha256':hashes}
    doc=snapshot(ROOT/'piq-fc-arcade/design/用户模型替换-alpha22-使用说明.md');inputs.append(doc)
    payloads={'mods/'+s.path.name:s.raw for s in jars.values()}
    payloads['piq-native-arcade/runtime/piq-native-helper.jar']=helper.raw
    payloads['先看这里.md']=doc.raw
    payloads.update({'checks/'+key+'.json':s.raw for key,s in reports.items()})
    for name,path in PREVIEWS.items():
        s=snapshot(ROOT/path,16*1024*1024);inputs.append(s);require(s.raw.startswith(b'\x89PNG\r\n\x1a\n'),'Invalid preview PNG');payloads[name]=s.raw
    payloads['checks/full-build.json']=json.dumps({'ok':True,'projects':builds,
        'unchanged_native_not_rebuilt':jars['native'].sha256,'unchanged_helper':helper.sha256,
        'minecraft_started':False,'installed':False},ensure_ascii=False,indent=2).encode('utf-8')
    payloads['SHA256.txt']=''.join(digest(raw)+'  '+name+'\n' for name,raw in sorted(payloads.items())).encode('utf-8')
    summary={'schema':'piq-user-models22-delivery-1','final_jars':{k:v.sha256 for k,v in jars.items()},
        'helper_sha256':helper.sha256,'files':{n:{'bytes':len(v),'sha256':digest(v)} for n,v in payloads.items()},
        'installed':False,'minecraft_started':False,'live_multiplayer_tested_this_turn':False}
    return PackagePlan(payloads,tuple(inputs),summary)

def build(plan,check_only=False):
    folder=safe_path(OUT);archive_path=safe_path(OUT.with_suffix('.zip'));report_path=safe_path(OUT.with_suffix('.verification.json'))
    require(folder.is_relative_to(ROOT) and all(not x.exists() for x in (folder,archive_path,report_path)),'Refusing existing/outside delivery')
    revalidate_inputs(plan);result=dict(plan.summary,ok=True,path=str(archive_path),check_only=check_only)
    if check_only:return result
    buffer=io.BytesIO()
    with zipfile.ZipFile(buffer,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        for name,raw in sorted(plan.payloads.items()):
            info=zipfile.ZipInfo(name,(2026,9,11,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,raw)
    raw=buffer.getvalue();verify_archive(raw,plan);revalidate_inputs(plan);folder.mkdir(parents=True)
    for name,value in plan.payloads.items():
        path=safe_path(folder/name);path.parent.mkdir(parents=True,exist_ok=True)
        with path.open('xb') as f:f.write(value)
        require(snapshot(path).raw==value,'Readback failed '+name)
    with archive_path.open('xb') as f:f.write(raw)
    final=snapshot(archive_path);verify_archive(final.raw,plan);require(final.raw==raw,'ZIP readback differs');revalidate_inputs(plan)
    result.update(bytes=len(raw),sha256=final.sha256)
    with report_path.open('x',encoding='utf-8') as f:json.dump(result,f,ensure_ascii=False,indent=2)
    return result

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--check-only',action='store_true');a=p.parse_args()
    print(json.dumps(build(plan(),a.check_only),ensure_ascii=True))
