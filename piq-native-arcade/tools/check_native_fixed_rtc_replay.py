"""One bounded fixed-RTC replay pair. The generated .cmd is parsed by libretro, never by a Windows shell."""
import argparse,json,os,shutil,subprocess,tempfile,time
from pathlib import Path
import check_native_determinism as old
from check_native_reload_barrier import difference

RTC='20000101000000'
FRAMES=12000

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--rom',type=Path,required=True);p.add_argument('--bios',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    if a.report.exists():raise FileExistsError(a.report)
    files=[a.rom.resolve(strict=True),a.bios.resolve(strict=True)];game=files[0].stem
    if game not in ('kof97','mslug2')or files[1].name!='neogeo.zip':raise ValueError('Only the two authorized existing game identities and BIOS')
    for f in files:
        if not f.is_file()or f.is_symlink()or not f.name.isascii()or f.suffix!='.zip'or not 22<=f.stat().st_size<=64*1024*1024:raise ValueError('Bounded ordinary ZIP required')
    before={str(f):old.identity(f)for f in files};runtime={str(f):old.identity(f)for f in old.LOCKS}
    for f,pin in old.LOCKS.items():
        if runtime[str(f)]['sha256']!=pin:raise ValueError('Pinned runtime changed')
    probes=[old.ROOT/'tools/qa/NativeDeterminismProbe.java',old.ROOT/'tools/qa/NativeInitialReplayProbe.java'];sources=[*probes,Path(__file__).resolve(),old.ROOT/'tools/check_native_determinism.py',old.ROOT/'tools/check_native_reload_barrier.py'];fence={str(f.relative_to(old.ROOT)):old.sha(f)for f in sources}
    result={'schema':'piq-native-fixed-rtc-replay-1','ok':False,'initial_replay_qualified':False,'production_modified':False,'production_compiled':False,'game':game,'inputs':before,'runtime':runtime,'frames':FRAMES,'source_roms_read_only':True,'mode':'sequential-independent-owned-JVMs-fixed-rtc','fixed_rtc':RTC,'command_template':f'mame -rtc {RTC} -verbose -rp "<isolated-work-directory>" {game}','shell_invoked':False};begin=time.monotonic()
    with tempfile.TemporaryDirectory(prefix='piq-mame-fixed-rtc-')as folder:
        temp=Path(folder);classes=temp/'classes';classes.mkdir();cp=os.pathsep.join(map(str,[classes,old.HELPER,old.JNA]));compile=subprocess.run(list(map(str,[old.JDK/'javac.exe','-encoding','UTF-8','--release','21','-cp',cp,'-d',classes,*probes])),capture_output=True,text=True,timeout=40)
        if compile.returncode:raise RuntimeError(compile.stdout+compile.stderr)
        children={}
        for name in ('original','rebuilt'):
            work=temp/name;work.mkdir()
            for source in files:shutil.copyfile(source,work/source.name);assert old.sha(work/source.name)==before[str(source)]['sha256']
            # Only our own fixed arguments and ASCII temporary path; no user command text or config loaded.
            path=work.as_posix()
            if not path.isascii()or any(c in path for c in '\r\n";'):raise ValueError('Unsafe temporary command path')
            content=f'mame -rtc {RTC} -verbose -rp "{path}" {game}\n'
            command_file=work/'fixed-rtc.cmd'
            with command_file.open('x',encoding='ascii',newline='\n')as stream:stream.write(content)
            initial='-'if name=='original'else temp/'original/output/initial.bin'
            command=list(map(str,[old.JDK/'java.exe','-Xmx256m','-Djna.nosys=true','-cp',cp,'cn.piq.nativearcade.bridge.NativeInitialReplayProbe',old.DLL,command_file,old.HELPER,work/'output',initial,FRAMES,600]))
            with(work/'stdout.log').open('xb')as stdout,(work/'stderr.log').open('xb')as stderr:
                process=subprocess.Popen(command,cwd=work,stdout=stdout,stderr=stderr,creationflags=subprocess.CREATE_NO_WINDOW|subprocess.BELOW_NORMAL_PRIORITY_CLASS)
                try:code=process.wait(timeout=300)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10);code='timeout'
            logs=[]
            for stream in ('stdout.log','stderr.log'):
                log_file=work/stream
                if log_file.stat().st_size>16*1024*1024:raise ValueError('Unexpected native log expansion')
                logs.append(log_file.read_text(encoding='utf-8',errors='replace'))
            log='\n'.join(logs);rtc_lines=[line for line in log.splitlines()if'RTC Override'in line]
            verified=f"RTC Override: Parsed '{RTC}' successfully."in log and 'RTC Override Success:'in log
            r={'exit_code':code,'native_teardown_completed':'NATIVE_TEARDOWN_COMPLETED'in log,'fixed_rtc_confirmed_by_native_log':verified,'rtc_log_lines':rtc_lines,'command_file_sha256':old.sha(command_file)}
            if(work/'output/result.json').is_file():r['result']=json.loads((work/'output/result.json').read_text())
            if code!=0 or not verified:r['failure_tail']=log[-4000:]
            children[name]=r
            if code!=0 or not verified:break
        result['children']=children
        if len(children)==2 and all(c['exit_code']==0 and c['native_teardown_completed']and c['fixed_rtc_confirmed_by_native_log']and c.get('result',{}).get('ok')for c in children.values()):
            one=temp/'original/output';two=temp/'rebuilt/output';result['frames_comparison']=old.compare((one/'frames.csv').read_text().splitlines(),(two/'frames.csv').read_text().splitlines())
            result['checkpoints']={str(i):difference((one/f'checkpoint-{i}.bin').read_bytes(),(two/f'checkpoint-{i}.bin').read_bytes())for i in range(0,FRAMES+1,600)}
            result['effective_options']=(one/'options.txt').read_text();result['effective_options_equal']=old.sha(one/'options.txt')==old.sha(two/'options.txt')
            result['ok']=True;result['initial_replay_qualified']=result['frames_comparison']['equal']and result['effective_options_equal']and all(c['equal']for c in result['checkpoints'].values())
            rebuilt=children['rebuilt']['result'];speed=rebuilt['steps_per_second'];fps=rebuilt['fps'];result['cost']={'replay_seconds':rebuilt['replay_nanoseconds_including_full_hashes_checkpoints_io']/1e9,'simulated_seconds':FRAMES/fps,'measured_speed_ratio':speed/fps,'packed_input_history_bytes':FRAMES*8,'input_plus_initial_state_bytes':FRAMES*8+rebuilt['initial_snapshot_bytes'],'one_hour_replay_seconds_linear_estimate_not_tested':3600*fps/speed,'one_hour_input_history_bytes':int(3600*fps)*8,'catchup_seconds_with_host_running_linear_estimate_not_tested':FRAMES/(speed-fps)if speed>fps else None}
        result['source_roms_unchanged']=before=={str(f):old.identity(f)for f in files}
        if not result['source_roms_unchanged']:raise AssertionError('User source changed')
        if fence!={str(f.relative_to(old.ROOT)):old.sha(f)for f in sources}:raise AssertionError('QA source changed during run')
    result['probe_sources_sha256']=fence;result['seconds']=round(time.monotonic()-begin,3);result['limitations']=['No Minecraft, network or production helper loop; unchanged actual core and callback implementation.','Only fixed RTC, bootstrap32 and complete input replay tested; not arbitrary current-state restore or general MAME support.','Same machine/platform/locale; not cross-architecture or cross-timezone proof.','Every RGB/PCM frame and every complete checkpoint byte compared, no excluded bytes or muted audio.','Temporary command is generated from a fixed schema and passed only as libretro content; read_config remains disabled.']
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as out:json.dump(result,out,ensure_ascii=False,indent=2)
    print(json.dumps({'report':str(a.report),'ok':result['ok'],'initial_replay_qualified':result['initial_replay_qualified'],'frames':result.get('frames_comparison'),'checkpoint_differences':{k:v['different_bytes']for k,v in result.get('checkpoints',{}).items()},'cost':result.get('cost'),'rtc_confirmed':{k:v['fixed_rtc_confirmed_by_native_log']for k,v in children.items()},'seconds':result['seconds']},ensure_ascii=True))
if __name__=='__main__':main()
