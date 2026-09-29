"""Package exact audited linked-cabinet preview bytes. No installs or old-file overwrites."""
import argparse,io,json,xml.etree.ElementTree as ET,zipfile
from pathlib import Path
from freeze_fc_core_alpha19 import safe_path,digest,require
from package_fc_core_alpha19 import snapshot,json_document,PackagePlan,verify_archive,revalidate_inputs
import prepare_cabinet_multiplayer21 as stage

ROOT=stage.ROOT
STAGE=ROOT/'piq-fc-arcade/build/review-linked21-v1'
NAME='双街机四人联机-alpha21-测试包-20260911'
OUT=ROOT/'制作Mod/03-街机模拟'/NAME
PINS={'fc':'0147D49C542E82DDF2DD37CDAFFBDB135CDA20E11907B4060B471A2FBC0D3F93',
      'sfc':'F951146515D7F35257585A9DA7536453F1DFFA70285F51EDD612D65C771DF951',
      'native':'8BE543D920BB3D49627F440CBE02EBD66AA2BB4370EA249202EF1F1066E1608A'}
REPORTS={
 'stage':'piq-fc-arcade/build/review-linked21-v1/stage-verification.json',
 'audit':'piq-fc-arcade/design/final-linked21-audit.json',
 'native':'piq-native-arcade/design/final-native7-four-port-20260911.json',
 'sfc_playback':'piq-sfc-home/design/playback-multiplayer9-final-linked21-v1.json',
 'sfc_ports':'piq-sfc-home/design/cabinet-ports-final-linked21-sfc9-v1.json',
 'media':'piq-fc-arcade/design/final-fc21-media-fallback-20260911.json'}

def identities(report,keys):
    for key in keys:
        entry=report['jars'][key]
        require(entry['sha256']==PINS[key] and safe_path(entry['path'],True)==safe_path(STAGE/stage.NAMES[key],True),'Wrong audited identity '+key)

def check_evidence(r):
    a=r['audit'];require(a.get('ok')is True and a.get('schema')=='piq-final-linked21-independent-1' and a.get('production_compiled')is False,'Final audit required')
    identities(a,PINS);require(a['ownership']['duplicate_classes']==0 and a['native_helper_sha256']==stage.HELPER_SHA,'Class/helper mismatch')
    require(all(v['explicit_allowlist_enforced']is True and v['removed']==[] for v in a['strict_scope'].values()),'Strict scope missing')
    p=a['room_final_jar_probes'];require(p['production_compiled']is False and p['production_origin']['production_origin']=='final-jar-only','Final room classes required')
    for name,count in [('ingress',52),('backpressure',1018),('saved_data',920)]:
        require(p[name]['ok']is True and p[name]['assertions']>=count,'Missing room proof '+name)
    c=a['compatibility_final_jar_probes'];require(c['production_compiled']is False and c['old_separate_core_on_classpath']is False,'Final compatibility classpath required')
    require(c['fml_discovery']['ok']is True and c['fml_discovery']['assertions']==28 and c['sfc_real_neoforge_outer_packet_codec']['passed']is True,'FML/codec failed')
    n=r['native'];require(n['ok']is True and n['production_compiled']is False and n['production_origin']=='final-jar-only' and n['parent_jar_sha256']==PINS['native'],'Native final proof mismatch')
    require(n['runtime_sha256']['piq-native-helper.jar']==stage.HELPER_SHA and n['helper_parser']['ok']is True and n['native_bridge']['ok']is True,'Native helper proof missing')
    for key in ('sfc_playback','sfc_ports'):
        s=r[key];identities(s,('fc','sfc'));require(s['passed']is True and s['production_compiled']is False and s['mode']=='final-jar-only','SFC final proof missing')
    require(r['sfc_playback']['actual']['matched_video_and_pcm_frames']>=184 and r['sfc_playback']['actual']['host_worker_restart_count']==0,'SFC playback regression')
    require(r['sfc_ports']['home_network_core_and_all_assets_unchanged']is True and r['sfc_ports']['probe']['assertions']>=290,'SFC protected scope/port proof')
    m=r['media'];require(m['ok']is True and m['final_jar_sha256']==PINS['fc'] and m['exact_tested_source_class_match']is True and m['tests']['ok']is True,'Media final proof mismatch')
    require(m['executed_production_origin']['production_origin']=='final-jar-only','Media final classes required')
    require(r['stage']['ok']is True and r['stage']['installed']is False,'Stage failed')

def plan():
    reports={k:snapshot(ROOT/v,8*1024*1024) for k,v in REPORTS.items()};r={k:json_document(s.raw)for k,s in reports.items()};check_evidence(r)
    jars={k:snapshot(STAGE/name)for k,name in stage.NAMES.items()}
    for key,s in jars.items():require(s.sha256==PINS[key],'Frozen JAR altered '+key)
    helper=snapshot(STAGE/'piq-native-arcade/runtime/piq-native-helper.jar');require(helper.sha256==stage.HELPER_SHA,'Frozen helper altered')
    doc=snapshot(ROOT/'piq-fc-arcade/design/双街机四人联机-alpha21-使用说明.md');require(len(doc.raw)>1000,'Instructions missing')
    inputs=[*jars.values(),helper,doc,*reports.values()]
    projects={}
    for project,expected in [('piq-fc-arcade',(913,7)),('piq-sfc-home',(216,0)),('piq-native-arcade',(46,0))]:
        counts=dict(tests=0,failures=0,errors=0,skipped=0);xml_files={}
        for path in sorted((ROOT/project/'build/test-results/test').glob('TEST-*.xml')):
            s=snapshot(path,8*1024*1024);inputs.append(s);xml_files[path.name]=s.sha256;x=ET.fromstring(s.raw)
            for key in counts:counts[key]+=int(x.attrib[key])
        require((counts['tests'],counts['skipped'])==expected and counts['failures']==counts['errors']==0,'Full build failed/mismatched '+project)
        projects[project]={**counts,'xml_files':xml_files}
    for key,s in jars.items():
        built=snapshot(stage.BUILDS[key]);inputs.append(built)
        require(built.sha256==r['stage']['mods'][key]['source_sha256'],'Build no longer matches tested frozen source '+key)
    payloads={'mods/'+s.path.name:s.raw for s in jars.values()};payloads['piq-native-arcade/runtime/piq-native-helper.jar']=helper.raw
    payloads['先看这里.md']=doc.raw;payloads.update({'checks/'+k+'.json':s.raw for k,s in reports.items()})
    build={'ok':True,'schema':'piq-linked21-full-build-1','projects':projects,'final_jars':PINS,'stage_sha256':reports['stage'].sha256,'installed':False,'minecraft_started':False}
    payloads['checks/full-build.json']=json.dumps(build,ensure_ascii=False,indent=2).encode()
    payloads['SHA256.txt']=''.join(digest(raw)+'  '+name+'\n'for name,raw in sorted(payloads.items())).encode()
    require(len(payloads)==13,'Unexpected package entries')
    summary={'schema':'piq-linked21-delivery-1','final_jars':PINS,'helper_sha256':helper.sha256,'files':{n:{'bytes':len(v),'sha256':digest(v)}for n,v in payloads.items()},'installed':False,'minecraft_started':False,'four_real_clients_tested':False}
    return PackagePlan(payloads,tuple(inputs),summary)

def build(p,check_only=False):
    folder=safe_path(OUT);archive_path=safe_path(OUT.with_suffix('.zip'));report_path=safe_path(OUT.with_suffix('.verification.json'))
    require(folder.is_relative_to(ROOT) and all(not x.exists()for x in (folder,archive_path,report_path)),'Refusing overwrite/outside workspace')
    revalidate_inputs(p);result=dict(p.summary,ok=True,path=str(archive_path),check_only=check_only)
    if check_only:return result
    buffer=io.BytesIO()
    with zipfile.ZipFile(buffer,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9)as z:
        for name,raw in sorted(p.payloads.items()):
            info=zipfile.ZipInfo(name,(2026,9,11,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,raw)
    raw=buffer.getvalue();verify_archive(raw,p);revalidate_inputs(p)
    folder.mkdir(parents=True)
    for name,value in p.payloads.items():
        destination=safe_path(folder/name);destination.parent.mkdir(parents=True,exist_ok=True)
        with destination.open('xb')as stream:stream.write(value)
        require(snapshot(destination).raw==value,'Delivery readback mismatch '+name)
    with archive_path.open('xb')as stream:stream.write(raw)
    final=snapshot(archive_path);verify_archive(final.raw,p);require(final.raw==raw,'ZIP changed');revalidate_inputs(p)
    result.update(bytes=len(raw),sha256=final.sha256)
    with report_path.open('x',encoding='utf-8')as stream:json.dump(result,stream,ensure_ascii=False,indent=2)
    return result

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-only',action='store_true');args=parser.parse_args()
    print(json.dumps(build(plan(),args.check_only),ensure_ascii=True))
