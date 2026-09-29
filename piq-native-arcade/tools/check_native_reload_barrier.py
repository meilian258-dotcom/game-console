"""Pinned MAME same-state reload cohort: only owned low-priority QA JVMs and temporary ROM copies."""
import argparse,concurrent.futures,json,os,shutil,subprocess,tempfile,time
from pathlib import Path
import check_native_determinism as old

def difference(left,right):
    if len(left)!=len(right):return {'equal':False,'bytes_a':len(left),'bytes_b':len(right),'different_sizes':True}
    offsets=[i for i,(a,b)in enumerate(zip(left,right))if a!=b];ranges=[]
    for index in offsets:
        if ranges and index==ranges[-1][1]+1:ranges[-1][1]=index
        else:ranges.append([index,index])
    # Offsets and a bounded set of short scalar values, never serialized game RAM blobs.
    scalars=[]
    for offset in sorted({i//8*8 for i in offsets})[:24]:
        scalars.append({'offset':offset,'a_u64_le':int.from_bytes(left[offset:offset+8],'little'),'b_u64_le':int.from_bytes(right[offset:offset+8],'little')})
    return {'equal':not offsets,'bytes':len(left),'different_bytes':len(offsets),'range_count':len(ranges),'first_ranges':ranges[:64],'first_scalar_words':scalars,'a_sha256':__import__('hashlib').sha256(left).hexdigest().upper(),'b_sha256':__import__('hashlib').sha256(right).hexdigest().upper()}
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--rom',required=True,type=Path);p.add_argument('--bios',required=True,type=Path);p.add_argument('--report',required=True,type=Path);p.add_argument('--snapshot-frame',type=int,default=2200);p.add_argument('--replay-frames',type=int,default=600);a=p.parse_args()
    if a.report.exists():raise FileExistsError(a.report)
    files=[a.rom.resolve(strict=True),a.bios.resolve(strict=True)];rom=files[0]
    for f in files:
        if not f.is_file()or f.is_symlink()or not f.name.isascii()or f.suffix!='.zip'or not 22<=f.stat().st_size<=64*1024*1024:raise ValueError('Exact bounded ordinary user ROM/BIOS ZIP required')
    before={str(f):old.identity(f)for f in files};runtime={str(f):old.identity(f)for f in old.LOCKS}
    for f,pin in old.LOCKS.items():
        if runtime[str(f)]['sha256']!=pin:raise ValueError('Runtime hash changed')
    result={'schema':'piq-native-reload-barrier-1','ok':False,'same_reload_qualified':False,'production_modified':False,'production_compiled':False,'game':rom.stem,'source_roms_read_only':True,'inputs':before,'runtime':runtime,'snapshot_frame':a.snapshot_frame,'suffix_steps':a.replay_frames}
    began=time.monotonic()
    with tempfile.TemporaryDirectory(prefix='piq-mame-barrier-')as folder:
        temp=Path(folder);classes=temp/'classes';classes.mkdir();cp=os.pathsep.join(map(str,[classes,old.HELPER,old.JNA]));qa=old.ROOT/'tools/qa'
        probes=[qa/'NativeDeterminismProbe.java',qa/'NativeReloadBarrierProbe.java'];fence={str(f.relative_to(old.ROOT)):old.sha(f)for f in [*probes,Path(__file__).resolve()]};run=subprocess.run(list(map(str,[old.JDK/'javac.exe','-encoding','UTF-8','--release','21','-cp',cp,'-d',classes,*probes])),capture_output=True,text=True,timeout=40)
        if run.returncode:raise RuntimeError(run.stdout+run.stderr)
        for name in ('a','b','cold','neutral'):
            work=temp/name;work.mkdir()
            for source in files:shutil.copyfile(source,work/source.name);assert old.sha(work/source.name)==before[str(source)]['sha256']
        def child(name):
            work=temp/name;peer='-'if name=='a'else temp/'a/output/own-before.bin'
            command=list(map(str,[old.JDK/'java.exe','-Xmx256m','-Djna.nosys=true','-cp',cp,'cn.piq.nativearcade.bridge.NativeReloadBarrierProbe',old.DLL,work/rom.name,old.HELPER,work/'output',a.snapshot_frame,a.replay_frames,peer,name if name in ('cold','neutral')else'warm']))
            with(work/'stdout.log').open('xb')as stdout,(work/'stderr.log').open('xb')as stderr:
                process=subprocess.Popen(command,cwd=work,stdout=stdout,stderr=stderr,creationflags=subprocess.CREATE_NO_WINDOW|subprocess.BELOW_NORMAL_PRIORITY_CLASS)
                try:code=process.wait(timeout=240)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10);code='timeout'
            log=(work/'stderr.log').read_text(encoding='utf-8',errors='replace');r={'exit_code':code,'native_teardown_completed':'NATIVE_TEARDOWN_COMPLETED'in log}
            if(work/'output/result.json').is_file():r['result']=json.loads((work/'output/result.json').read_text())
            if code!=0:r['failure_tail']=log[-2500:]
            return name,r
        with concurrent.futures.ThreadPoolExecutor(max_workers=2)as pool:result['children']=dict(pool.map(child,('a','b')))
        with concurrent.futures.ThreadPoolExecutor(max_workers=2)as pool:result['children'].update(dict(pool.map(child,('cold','neutral'))))
        if all(v['exit_code']==0 and v['native_teardown_completed']and v.get('result',{}).get('ok')for v in result['children'].values()):
            def trace(w,n):return(temp/w/'output'/(n+'.csv')).read_text().splitlines()
            def state(w,n):return(temp/w/'output'/(n+'.bin')).read_bytes()
            pairs={'independent_initial':(('a','initial'),('b','initial')),'independent_baseline':(('a','baseline'),('b','baseline')),'both_warm_same_reload':(('a','barrier1'),('b','barrier1')),'warm_vs_cold_same_reload':(('a','barrier1'),('cold','barrier1')),'warm_repeat_reload':(('a','barrier1'),('a','barrier2')),'cold_repeat_reload':(('cold','barrier1'),('cold','barrier2')),'baseline_vs_reload':(('a','baseline'),('a','barrier1'))}
            result['comparisons']={key:old.compare(trace(*one),trace(*two))for key,(one,two)in pairs.items()}
            result['comparisons'].update({key:old.compare(trace(*one),trace(*two))for key,(one,two)in {'both_warm_second_reload':(('a','barrier2'),('b','barrier2')),'warm_vs_equal_count_neutral_reload':(('a','barrier1'),('neutral','barrier1')),'warm_vs_neutral_second_reload':(('a','barrier2'),('neutral','barrier2'))}.items()})
            spairs={'independent_before':(('a','own-before'),('b','own-before')),'load_save_changes':(('a','own-before'),('a','barrier1-before')),'both_warm_postload':(('a','barrier1-before'),('b','barrier1-before')),'warm_cold_postload':(('a','barrier1-before'),('cold','barrier1-before')),'both_warm_end':(('a','barrier1-end'),('b','barrier1-end')),'warm_cold_end':(('a','barrier1-end'),('cold','barrier1-end')),'warm_repeat_end':(('a','barrier1-end'),('a','barrier2-end'))}
            result['state_differences']={key:difference(state(*one),state(*two))for key,(one,two)in spairs.items()}
            result['state_differences'].update({key:difference(state(*one),state(*two))for key,(one,two)in {'both_warm_second_end':(('a','barrier2-end'),('b','barrier2-end')),'warm_neutral_postload':(('a','barrier1-before'),('neutral','barrier1-before')),'warm_neutral_end':(('a','barrier1-end'),('neutral','barrier1-end'))}.items()})
            result['options_equal']=len({old.sha(temp/n/'output/options.txt')for n in ('a','b','cold','neutral')})==1
            result['effective_options']=(temp/'a/output/options.txt').read_text();result['options_basis']='Observed successful production RETRO_ENVIRONMENT_GET_VARIABLE replies, with unchanged pinned helper profile.'
            result['ok']=True;result['same_reload_qualified']=result['options_equal']and all(result['comparisons'][k]['equal']for k in ('both_warm_same_reload','warm_vs_cold_same_reload','warm_repeat_reload','cold_repeat_reload'))and all(result['state_differences'][k]['equal']for k in ('both_warm_postload','warm_cold_postload','both_warm_end','warm_cold_end','warm_repeat_end'))
        result['source_roms_unchanged']=before=={str(f):old.identity(f)for f in files}
        if not result['source_roms_unchanged']:raise AssertionError('User source changed')
        result['probe_sources_sha256']={str(f.relative_to(old.ROOT)):old.sha(f)for f in [*probes,Path(__file__).resolve()]}
        if fence!=result['probe_sources_sha256']:raise AssertionError('Probe sources changed during experiment')
    result['seconds']=round(time.monotonic()-began,3);result['limitations']=['No Minecraft or socket; only actual pinned native core in separately owned QA JVMs.','No ignoring audio or unidentified state bytes; qualification includes every frame and complete serialized state.','Warm and cold postload histories differ deliberately; source ROMs/BIOS never written.']
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as out:json.dump(result,out,ensure_ascii=False,indent=2)
    print(json.dumps({'report':str(a.report),'ok':result['ok'],'same_reload_qualified':result['same_reload_qualified'],'comparisons':result.get('comparisons'),'state_differences':{k:{q:v.get(q)for q in ('equal','different_bytes','range_count','first_ranges')}for k,v in result.get('state_differences',{}).items()},'seconds':result['seconds']},ensure_ascii=True))
if __name__=='__main__':main()
