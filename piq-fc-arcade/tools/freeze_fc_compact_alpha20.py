"""Freeze an FC20 build, restoring ONLY two SHA-pinned inherited controller PNGs."""
import argparse,json,os,shutil,tempfile,zipfile
from pathlib import Path
import verify_compact_ui_alpha20 as audit
from freeze_fc_core_alpha19 import read_jar,safe_path,digest,require,checked_zip

RESTORE={
 'assets/piq_fc_arcade/textures/block/famicom_controller_1.png':('D181733715925047CA30A4198009D601E424052E9EAB9A02BBF63CD7A65F384D','211985C1D40273160E1E21852D98BC816B7946A02D16E6B8D404A366F9A6D64E'),
 'assets/piq_fc_arcade/textures/block/famicom_controller_2.png':('46826134D4E35D290657817B8AFFB6C04DE0EFC9BF98255B6ED44F2D3117064D','A5D33839B300BEB8BEF26908244F83343FF36CBBFA2CB91B5825C6FA127FF6D3')}

def plan(source,expected_sha):
    source=safe_path(source,True);source_sha,infos,built=read_jar(source)
    require(source_sha==expected_sha.upper(),'Source SHA mismatch')
    baseline,expected=audit.BASELINES['fc'];old_sha,_,old=read_jar(baseline);require(old_sha==expected,'Frozen FC19 SHA mismatch')
    final=dict(built);restored={}
    for name,(source_png,frozen_png)in RESTORE.items():
        require(name in final and name in old and digest(old[name])==frozen_png,'Pinned inherited controller asset missing/changed')
        if final[name]!=old[name]:
            require(digest(final[name])==source_png,'Unexpected new controller artwork requires review')
            restored[name]={'source_sha256':source_png,'frozen_sha256':frozen_png};final[name]=old[name]
    categories=audit.classify('fc',{n:v for n,v in old.items()if not n.endswith('/')},{n:v for n,v in final.items()if not n.endswith('/')})
    require(set(final)==set(built)and all(final[n]==raw for n,raw in built.items()if n not in restored),'Freezer attempted unauthorized byte changes')
    return {'source':source,'source_sha':source_sha,'baseline':baseline,'baseline_sha':old_sha,'infos':infos,'entries':final,'restored':restored,'classification':categories}

def freeze(p,output,report,check_only=False):
    output=safe_path(output);report=safe_path(report)
    require(output not in (p['source'],p['baseline'])and report not in (p['source'],p['baseline'],output),'Input/output/report must differ')
    require(not output.exists()and not report.exists(),'Refusing to overwrite frozen output/report')
    require(read_jar(p['source'])[0]==p['source_sha']and read_jar(p['baseline'])[0]==p['baseline_sha'],'Input changed during freeze')
    result={'ok':True,'schema':'piq-alpha20-fc-freeze-1','source':str(p['source']),'source_sha256':p['source_sha'],'path':str(output),
            'baseline_sha256':p['baseline_sha'],'restored_artwork':p['restored'],'classification':p['classification'],
            'class_core_runtime_bytes_changed_by_freezer':0,'check_only':check_only,'installed':False,'minecraft_started':False}
    if check_only:return result
    output.parent.mkdir(parents=True,exist_ok=True);safe_path(output.parent)
    handle,name=tempfile.mkstemp(prefix='.alpha20-freeze-',suffix='.jar',dir=output.parent);os.close(handle);staged=Path(name)
    try:
        with zipfile.ZipFile(staged,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9)as jar:
            for info in p['infos']:jar.writestr(info,p['entries'][info.filename])
        stage_sha,_,entries=read_jar(staged);require(entries==p['entries'],'Staged freeze bytes mismatch')
        require(read_jar(p['source'])[0]==p['source_sha'],'Build changed before exclusive freeze')
        with output.open('xb')as target,staged.open('rb')as origin:shutil.copyfileobj(origin,target)
        final_sha,_,entries=read_jar(output);require(entries==p['entries']and final_sha==stage_sha,'Final freeze bytes mismatch')
        require(read_jar(p['source'])[0]==p['source_sha']and read_jar(p['baseline'])[0]==p['baseline_sha'],'Input changed during freeze')
        result.update(sha256=final_sha,bytes=output.stat().st_size)
        report.parent.mkdir(parents=True,exist_ok=True);safe_path(report.parent)
        with report.open('x',encoding='utf-8')as stream:json.dump(result,stream,ensure_ascii=False,indent=2)
        return result
    finally:staged.unlink(missing_ok=True) # exact self-created stage only

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for key in ('source','output','report'):parser.add_argument('--'+key,type=Path,required=True)
    parser.add_argument('--source-sha256',required=True);parser.add_argument('--check-only',action='store_true');args=parser.parse_args()
    result=freeze(plan(args.source,args.source_sha256),args.output,args.report,args.check_only)
    print(json.dumps({k:v for k,v in result.items()if k!='classification'},ensure_ascii=True))
if __name__=='__main__':main()
