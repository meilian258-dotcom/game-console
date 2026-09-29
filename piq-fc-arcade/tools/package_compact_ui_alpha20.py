"""Package only audited FC20/SFC7 and the unchanged Native6; never install or launch."""
import argparse,json,os,shutil,tempfile,zipfile
from pathlib import Path
from dataclasses import dataclass
import verify_compact_ui_alpha20 as audit
from freeze_fc_core_alpha19 import safe_path,checked_zip,digest,require
from package_fc_core_alpha19 import snapshot,json_document,PackagePlan,verify_archive,revalidate_inputs

DELIVERY=audit.DELIVERY
JARS={'fc':'PIQ-FC街机/alpha20-compact-vanilla-ui/piq_fc_arcade-0.31.0-alpha.20.jar',
      'sfc':'PIQ-SFC家用/0.1.0-alpha.7/piq_sfc-0.1.0-alpha.7.jar',
      'native':'PIQ原生街机/0.1.0-alpha.6/piq_native_arcade-0.1.0-alpha.6.jar'}
DOCS={'安装说明.md':'紧凑原版界面-alpha20-安装说明.md',
      '成品独立检查.json':'PIQ-FC街机/alpha20-compact-vanilla-ui/final-independent-audit.json',
      '构建测试汇总.json':'PIQ-FC街机/alpha20-compact-vanilla-ui/full-build-tests.json'}
OUTPUT='紧凑原版界面与SFC外形-alpha20-安装包-20260910.zip'

def check_audit(report,jars):
    require(report.get('ok')is True and report.get('schema')=='piq-compact-alpha20-final-1','Wrong or failed final alpha20 audit')
    require(report.get('fixture_only')is not True and report.get('not_a_final_release_validation')is not True,'Fixture is not final delivery validation')
    require(set(report.get('jars',{}))==set(JARS),'Audit must cover exact three final jars')
    for key,item in jars.items():
        claimed=report['jars'][key]
        require(safe_path(claimed['path'],True)==item.path and claimed['sha256'].upper()==item.sha256,'Audited final JAR identity mismatch: '+key)
    require(report.get('native_unchanged')is True and jars['native'].sha256==audit.BASELINES['native'][1],'Native6 was rebuilt or changed')
    probes=report.get('probes',{});fml=probes.get('fml_discovery',{});packet=probes.get('sfc_real_neoforge_outer_packet_codec',{});ui=probes.get('compact_workbench',{})
    require(probes.get('production_compiled')is False and probes.get('old_separate_core_on_classpath')is False,'Final probes may not compile production or add old core')
    require(fml.get('ok')is True and fml.get('production_origin')=='final-jar-only'and fml.get('assertions')==28,'Real final FML discovery missing')
    require(packet.get('passed')is True and packet.get('assertions')==37 and type(packet.get('max_upload_outer_packet_bytes'))is int and 0<packet['max_upload_outer_packet_bytes']<32767,'Real bounded outer codec missing')
    require(ui.get('ok')is True and ui.get('production_origin')=='final-jar-only'and ui.get('layouts')==108 and ui.get('assertions',0)>1000,'Final compact UI probe missing')
    require(report.get('sfc_mesh_sha256')==audit.MESH_SHA,'Reviewed mesh missing')
    require(report.get('installed')is False and report.get('minecraft_or_native_core_started')is False,'Unexpected installation/game side effect')

def check_build(report,jars):
    require(report.get('ok')is True and report.get('schema')=='piq-compact-alpha20-build-1','Wrong or failed alpha20 build report')
    projects=report.get('projects',{});require(set(projects)=={'piq-fc-arcade','piq-sfc-home'},'Only FC/SFC are new builds; do not claim new Native build')
    for name,counts in projects.items():
        require(all(type(counts.get(k))is int and counts[k]>=0 for k in ('tests','failures','errors','skipped')),'Invalid test counts')
        require(counts['tests']>counts['skipped'] and counts['failures']==0 and counts['errors']==0,'Full build tests failed: '+name)
    unchanged=report.get('unchanged',{});require(set(unchanged)=={'piq-native-arcade'},'Explicit unchanged Native6 record required')
    native=unchanged['piq-native-arcade'];require(native.get('rebuilt')is False and native.get('sha256')==jars['native'].sha256 and safe_path(native['path'],True)==jars['native'].path,'Native unchanged identity mismatch')
    require(set(report.get('jars',{}))==set(JARS),'Build summary final JAR inventory')
    for key,item in jars.items():require(report['jars'][key].get('final_sha256')==item.sha256,'Build summary final SHA mismatch: '+key)
    require(report.get('minecraft_started')is False and report.get('installed')is False,'Unexpected build side effects')

def plan():
    jars={key:snapshot(DELIVERY/name)for key,name in JARS.items()};docs={name:snapshot(DELIVERY/path,8*1024*1024)for name,path in DOCS.items()}
    check_audit(json_document(docs['成品独立检查.json'].raw),jars);check_build(json_document(docs['构建测试汇总.json'].raw),jars)
    require(docs['安装说明.md'].raw.decode('utf-8-sig').strip(),'Empty installation instructions')
    baselines=audit.originals();categories={}
    for key,item in jars.items():
        entries={n:v for n,v in checked_zip(item.raw)[1].items()if not n.endswith('/')};categories[key]=audit.classify(key,baselines[key],entries)
    payloads={item.path.name:item.raw for item in jars.values()};require(len(payloads)==3,'Duplicate jar filenames')
    payloads.update({name:item.raw for name,item in docs.items()})
    payloads['SHA256.txt']=''.join(digest(raw)+'  '+name+'\n'for name,raw in sorted(payloads.items())).encode('utf-8')
    require(len(payloads)==7 and sum(name.endswith('.jar')for name in payloads)==3,'Exact 7-member package required')
    inputs=tuple([*jars.values(),*docs.values(),*(snapshot(path)for path,_ in audit.BASELINES.values())])
    summary={'schema':'piq-compact-alpha20-package-1','jars':{key:{'path':str(item.path),'sha256':item.sha256}for key,item in jars.items()},
             'native_rebuilt':False,'files':{name:{'sha256':digest(raw),'bytes':len(raw)}for name,raw in payloads.items()}}
    return PackagePlan(payloads,inputs,summary)

def build(p,check_only=False):
    output=safe_path(DELIVERY/OUTPUT);report=safe_path(output.with_suffix('.verification.json'))
    require(not output.exists()and not report.exists(),'Refusing to overwrite package/report');revalidate_inputs(p)
    result=dict(p.summary,ok=True,path=str(output),check_only=check_only,installed=False,minecraft_started=False)
    if check_only:return result
    output.parent.mkdir(parents=True,exist_ok=True);safe_path(output.parent)
    handle,name=tempfile.mkstemp(prefix='.alpha20-package-',suffix='.zip',dir=output.parent);os.close(handle);staged=Path(name)
    try:
        with zipfile.ZipFile(staged,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9)as archive:
            for name,raw in sorted(p.payloads.items()):
                info=zipfile.ZipInfo(name,(2026,9,10,0,0,0));info.create_system=3;info.external_attr=0o100644<<16;archive.writestr(info,raw,compress_type=zipfile.ZIP_DEFLATED,compresslevel=9)
        raw=staged.read_bytes();verify_archive(raw,p);revalidate_inputs(p)
        with output.open('xb')as target,staged.open('rb')as source:shutil.copyfileobj(source,target)
        final=snapshot(output);verify_archive(final.raw,p);require(final.sha256==digest(raw),'Final package SHA mismatch');revalidate_inputs(p)
        result.update(sha256=final.sha256,bytes=len(final.raw))
        with report.open('x',encoding='utf-8')as stream:json.dump(result,stream,ensure_ascii=False,indent=2)
        return result
    finally:staged.unlink(missing_ok=True) # exact own stage only
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check-only',action='store_true');args=parser.parse_args();print(json.dumps(build(plan(),args.check_only),ensure_ascii=True))
if __name__=='__main__':main()
