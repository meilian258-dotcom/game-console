"""Fixed official FBNeo two-process, all-video/all-PCM snapshot research. No production integration."""
import argparse,concurrent.futures,hashlib,json,os,shutil,subprocess,sys,tempfile,time
from pathlib import Path
STUDY=Path(__file__).resolve().parents[1];ROOT=STUDY.parents[1]
sys.path.insert(0,str(ROOT/'tools'));from check_native_determinism import sha,identity,compare,JDK,JNA
def main():
    p=argparse.ArgumentParser();p.add_argument('--game',choices=['kof97','mslug2'],required=True);p.add_argument('--context',choices=['normal','netplay'],required=True);p.add_argument('--report',required=True,type=Path);a=p.parse_args();assert not a.report.exists()
    old=json.loads((ROOT/'design/native-sync-study'/('kof97-v3.json'if a.game=='kof97'else'mslug2-v1.json')).read_text(encoding='utf-8'))
    sources=[Path(s)for s in old['inputs']];before={str(s):identity(s)for s in sources}
    for s in sources:assert before[str(s)]['sha256']==old['inputs'][str(s)]['sha256']
    meta=json.loads((STUDY/'official-artifacts.json').read_text(encoding='utf-8'));dll=STUDY/'vendor/fbneo_libretro.dll';assert sha(dll)==meta['dll']['sha256']
    assert sha(JNA)=='34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6'
    result={'schema':'piq-fbneo-isolated-study-1','game':a.game,'ok':False,'sync_qualified':False,'official_artifacts':meta,'inputs':before,'production_modified':False,'production_compiled':False,'minecraft_started':False,'commercial_rom_loaded':True,'source_roms_read_only':True,'snapshot_frame':2200,'following_frames':600,'limitations':['Independent experimental core, not integrated into FC or Native production.','Finite traces for the provided existing ROM set only; not a universal FBNeo compatibility claim.','Entire canonical video and all signed stereo PCM samples are hashed per frame; no sound samples ignored.','Snapshot contents and ROM/BIOS copies exist only in owned temporary directories and are not report artifacts.']};started=time.time()
    with tempfile.TemporaryDirectory(prefix='piq-fbneo-study-')as folder:
        tmp=Path(folder);classes=tmp/'classes';classes.mkdir();binary=tmp/'fbneo_libretro.dll';shutil.copyfile(dll,binary);assert sha(binary)==meta['dll']['sha256'];cp=os.pathsep.join(map(str,[classes,JNA]))
        built=subprocess.run(list(map(str,[JDK/'javac.exe','-encoding','UTF-8','--release','21','-cp',cp,'-d',classes,STUDY/'tools/FbneoStudy.java'])),capture_output=True,text=True,timeout=40)
        if built.returncode:raise RuntimeError(built.stdout+built.stderr)
        jobs=[]
        for name in ['a','b']:
            work=tmp/name;work.mkdir()
            for source in sources:shutil.copyfile(source,work/source.name);assert sha(work/source.name)==before[str(source)]['sha256']
            jobs.append((name,work))
        def run(job):
            name,work=job;peer=tmp/'a/output/snapshot.bin'if name=='b'else'-';cmd=list(map(str,[JDK/'java.exe','-Xmx256m','-Djna.nosys=true','-cp',cp,'FbneoStudy',binary,work/(a.game+'.zip'),work/'output',2200,600,peer,0 if a.context=='normal'else 3]))
            with(work/'stdout.log').open('xb')as stdout,(work/'stderr.log').open('xb')as stderr:
                child=subprocess.Popen(cmd,cwd=work,stdout=stdout,stderr=stderr,creationflags=subprocess.CREATE_NO_WINDOW|subprocess.BELOW_NORMAL_PRIORITY_CLASS)
                try:code=child.wait(timeout=180)
                except subprocess.TimeoutExpired:child.kill();child.wait(timeout=10);code='timeout'
            tail=(work/'stderr.log').read_text(encoding='utf-8',errors='replace')[-18000:];out={'exit_code':code,'stderr_tail':tail,'stdout_tail':(work/'stdout.log').read_text(encoding='utf-8',errors='replace')[-8000:],'native_teardown_completed':'NATIVE_TEARDOWN_COMPLETED'in tail}
            if(work/'output/result.json').exists():out['result']=json.loads((work/'output/result.json').read_text())
            return name,out
        with concurrent.futures.ThreadPoolExecutor(max_workers=2)as pool:result['children']=dict(pool.map(run,jobs))
        if all(c['exit_code']==0 and c['native_teardown_completed']and c.get('result',{}).get('ok')for c in result['children'].values()):
            def lines(w,n):return(tmp/w/'output'/n).read_text().splitlines()
            av,bv=lines('a','baseline.csv'),lines('b','baseline.csv');result['comparisons']={'independent_start':compare(av,bv),'a_local_restore':compare(av[2200:],lines('a','restored.csv')),'b_local_restore':compare(bv[2200:],lines('b','restored.csv')),'a_snapshot_on_b':compare(av[2200:],lines('b','cross.csv'))}
            ra=result['children']['a']['result'];rb=result['children']['b']['result'];result['state_hashes']={'initial_equal':ra['initial_sha256']==rb['initial_sha256'],'independent_snapshot_equal':ra['snapshot_sha256']==rb['snapshot_sha256'],'a_immediate_repeat_equal':ra['snapshot_sha256']==ra['immediate_repeat_save_sha256'],'b_immediate_repeat_equal':rb['snapshot_sha256']==rb['immediate_repeat_save_sha256'],'a_load_resave_equal':ra['snapshot_sha256']==ra['restored_before_step_sha256'],'b_load_resave_equal':rb['snapshot_sha256']==rb['restored_before_step_sha256'],'a_final_equal':ra['ending_state_sha256']==ra['restored_ending_state_sha256'],'b_final_equal':rb['ending_state_sha256']==rb['restored_ending_state_sha256'],'cross_final_equal':ra['ending_state_sha256']==rb['cross_ending_state_sha256']}
            result['options']=(tmp/'a/output/options.txt').read_text();result['options_equal']=result['options']==(tmp/'b/output/options.txt').read_text();result['ok']=True
            result['actual_sample_rate']=ra['sample_rate'];result['requested_sample_rate']=48000
            result['savestate_context']=a.context;result['pcm_signed_shorts_per_frame']=sorted(set(int(line.split(',')[5])for line in av));result['frames_per_comparison']={k:2800 if k=='independent_start'else 600 for k in result['comparisons']}
            result['timing_equal']=ra['sample_rate']==rb['sample_rate']and ra['fps']==rb['fps']
            result['determinism_qualified']=all(v['equal']for v in result['comparisons'].values())and all(result['state_hashes'].values())and result['options_equal']and result['timing_equal']
            result['direct_48k_compatible']=ra['sample_rate']==rb['sample_rate']==48000
            result['sync_qualified']=result['determinism_qualified']and result['direct_48k_compatible']
            result['limitations'].append('Actual sample rate is recorded, not relabeled or resampled. A production 48 kHz adapter needs a deterministic resampler whose pending samples/history are part of snapshots.')
        result['source_roms_unchanged']=before=={str(s):identity(s)for s in sources};assert result['source_roms_unchanged']
    result['seconds']=round(time.time()-started,3);result['qa_source_sha256']={str(f.relative_to(STUDY)):sha(f)for f in [Path(__file__),STUDY/'tools/FbneoStudy.java']};a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(result,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':result['ok'],'sync_qualified':result['sync_qualified'],'determinism_qualified':result.get('determinism_qualified'),'actual_sample_rate':result.get('actual_sample_rate'),'report':str(a.report),'comparisons':result.get('comparisons'),'state_hashes':result.get('state_hashes'),'seconds':result['seconds']},ensure_ascii=True))
if __name__=='__main__':main()
