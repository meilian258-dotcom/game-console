"""Pinned MAME initial-state plus bounded complete-input replay; no production changes, no retained ROM/state binaries."""
import argparse,json,os,shutil,subprocess,tempfile,time
from pathlib import Path
import check_native_determinism as old
from check_native_reload_barrier import difference

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--rom',type=Path,required=True);p.add_argument('--bios',type=Path,required=True);p.add_argument('--report',type=Path,required=True);p.add_argument('--frames',type=int,default=12000);a=p.parse_args()
    if a.report.exists():raise FileExistsError(a.report)
    if not 600<=a.frames<=36000 or a.frames%600:raise ValueError('Bounded 600-frame blocks')
    files=[a.rom.resolve(strict=True),a.bios.resolve(strict=True)]
    for f in files:
        if not f.is_file()or f.is_symlink()or not f.name.isascii()or f.suffix!='.zip'or not 22<=f.stat().st_size<=64*1024*1024:raise ValueError('Bounded ordinary ZIP required')
    before={str(f):old.identity(f)for f in files};runtime={str(f):old.identity(f)for f in old.LOCKS}
    for f,pin in old.LOCKS.items():
        if runtime[str(f)]['sha256']!=pin:raise ValueError('Pinned runtime changed')
    probes=[old.ROOT/'tools/qa/NativeDeterminismProbe.java',old.ROOT/'tools/qa/NativeInitialReplayProbe.java'];sources=[*probes,Path(__file__).resolve(),old.ROOT/'tools/check_native_reload_barrier.py'];fence={str(f.relative_to(old.ROOT)):old.sha(f)for f in sources}
    result={'schema':'piq-native-initial-replay-1','ok':False,'initial_replay_qualified':False,'production_modified':False,'production_compiled':False,'game':files[0].stem,'inputs':before,'runtime':runtime,'frames':a.frames,'source_roms_read_only':True,'mode':'sequential-independent-owned-JVMs'};begin=time.monotonic()
    with tempfile.TemporaryDirectory(prefix='piq-mame-replay-')as folder:
        temp=Path(folder);classes=temp/'classes';classes.mkdir();cp=os.pathsep.join(map(str,[classes,old.HELPER,old.JNA]));compile=subprocess.run(list(map(str,[old.JDK/'javac.exe','-encoding','UTF-8','--release','21','-cp',cp,'-d',classes,*probes])),capture_output=True,text=True,timeout=40)
        if compile.returncode:raise RuntimeError(compile.stdout+compile.stderr)
        children={}
        for name in ('original','rebuilt'):
            work=temp/name;work.mkdir()
            for source in files:shutil.copyfile(source,work/source.name);assert old.sha(work/source.name)==before[str(source)]['sha256']
            initial='-'if name=='original'else temp/'original/output/initial.bin'
            command=list(map(str,[old.JDK/'java.exe','-Xmx256m','-Djna.nosys=true','-cp',cp,'cn.piq.nativearcade.bridge.NativeInitialReplayProbe',old.DLL,work/files[0].name,old.HELPER,work/'output',initial,a.frames,600]))
            with(work/'stdout.log').open('xb')as stdout,(work/'stderr.log').open('xb')as stderr:
                process=subprocess.Popen(command,cwd=work,stdout=stdout,stderr=stderr,creationflags=subprocess.CREATE_NO_WINDOW|subprocess.BELOW_NORMAL_PRIORITY_CLASS)
                try:code=process.wait(timeout=300)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10);code='timeout'
            log=(work/'stderr.log').read_text(encoding='utf-8',errors='replace');r={'exit_code':code,'native_teardown_completed':'NATIVE_TEARDOWN_COMPLETED'in log}
            if(work/'output/result.json').is_file():r['result']=json.loads((work/'output/result.json').read_text())
            if code!=0:r['failure_tail']=log[-2500:]
            children[name]=r
            if code!=0:break
        result['children']=children
        if len(children)==2 and all(c['exit_code']==0 and c['native_teardown_completed']and c.get('result',{}).get('ok')for c in children.values()):
            one=temp/'original/output';two=temp/'rebuilt/output';result['frames_comparison']=old.compare((one/'frames.csv').read_text().splitlines(),(two/'frames.csv').read_text().splitlines())
            result['checkpoints']={str(i):difference((one/f'checkpoint-{i}.bin').read_bytes(),(two/f'checkpoint-{i}.bin').read_bytes())for i in range(0,a.frames+1,600)}
            result['effective_options']=(one/'options.txt').read_text();result['effective_options_equal']=old.sha(one/'options.txt')==old.sha(two/'options.txt')
            result['ok']=True;result['initial_replay_qualified']=result['frames_comparison']['equal']and result['effective_options_equal']and all(c['equal']for c in result['checkpoints'].values())
            rebuilt=children['rebuilt']['result'];speed=rebuilt['steps_per_second'];fps=rebuilt['fps'];result['cost']={'replay_seconds':rebuilt['replay_nanoseconds_including_full_hashes_checkpoints_io']/1e9,'simulated_seconds':a.frames/fps,'measured_speed_ratio':speed/fps,'packed_input_history_bytes':a.frames*8,'input_plus_initial_state_bytes':a.frames*8+rebuilt['initial_snapshot_bytes'],'one_hour_replay_seconds_linear_estimate_not_tested':3600*fps/speed,'one_hour_input_history_bytes':int(3600*fps)*8,'catchup_seconds_with_host_running_linear_estimate_not_tested':a.frames/(speed-fps)if speed>fps else None}
        result['source_roms_unchanged']=before=={str(f):old.identity(f)for f in files}
        if not result['source_roms_unchanged']:raise AssertionError('User source changed')
        if fence!={str(f.relative_to(old.ROOT)):old.sha(f)for f in sources}:raise AssertionError('QA source changed during run')
    result['probe_sources_sha256']=fence;result['seconds']=round(time.monotonic()-begin,3);result['limitations']=['No Minecraft, network or helper production loop; actual fixed core callbacks and complete RGB/PCM/state comparison.','Every future frame, input, reset, option and reload event must be reproduced; this probe tests fixed profile and no mid-run reload.','History and reconstruction time grow with session length. Only the tested bounded history is evidence, not unlimited restoration.','Independent empty work directories; user NVRAM, save and running processes untouched. Complete state binaries are temporary and removed.']
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as out:json.dump(result,out,ensure_ascii=False,indent=2)
    print(json.dumps({'report':str(a.report),'ok':result['ok'],'initial_replay_qualified':result['initial_replay_qualified'],'frames':result.get('frames_comparison'),'checkpoint_differences':{k:v['different_bytes']for k,v in result.get('checkpoints',{}).items()},'cost':result.get('cost'),'seconds':result['seconds']},ensure_ascii=True))
if __name__=='__main__':main()
