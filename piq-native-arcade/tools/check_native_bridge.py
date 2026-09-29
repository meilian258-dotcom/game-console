"""Actual bridge + private native child. Never load the MAME DLL into Minecraft or this Python process."""
from pathlib import Path
import argparse,ctypes,hashlib,json,os,re,shutil,subprocess,tempfile,time
from build_native_helper import build,JDK,JNA,ROOT
from make_diagnostic_rom import make
POC=ROOT.parent/'piq-native-arcade-poc'
def capture(command,timeout=40):
    child=subprocess.Popen(list(map(str,command)),stdout=subprocess.PIPE,stderr=subprocess.PIPE)
    try:out,err=child.communicate(timeout=timeout)
    except subprocess.TimeoutExpired:
        # Exact QA parent and its descendants only; never a name-wide Java kill.
        subprocess.run(['taskkill','/PID',str(child.pid),'/T','/F'],capture_output=True,timeout=10)
        child.communicate(timeout=10)
        raise RuntimeError('QA process timed out; terminated only its exact owned process tree')
    stdout=out.decode('utf-8',errors='replace');stderr=err.decode('utf-8',errors='replace')
    if child.returncode:raise RuntimeError(f'Bridge exit {child.returncode}\n{stdout}\n{stderr}')
    return {'stdout':stdout,'stderr':stderr}
def child_exited(pid):
    kernel=ctypes.WinDLL('kernel32',use_last_error=True)
    kernel.OpenProcess.argtypes=[ctypes.c_ulong,ctypes.c_int,ctypes.c_ulong];kernel.OpenProcess.restype=ctypes.c_void_p
    kernel.WaitForSingleObject.argtypes=[ctypes.c_void_p,ctypes.c_ulong];kernel.WaitForSingleObject.restype=ctypes.c_ulong
    kernel.CloseHandle.argtypes=[ctypes.c_void_p]
    handle=kernel.OpenProcess(0x100000,0,pid)
    if not handle:
        if ctypes.get_last_error()==87:return True
        raise OSError(ctypes.get_last_error(),'Cannot independently inspect exact native child')
    try:return kernel.WaitForSingleObject(handle,2000)==0
    finally:kernel.CloseHandle(handle)
def run(report_dir,jar=None,jar_sha=None,runtime=None):
    report_dir=Path(report_dir).resolve()
    if report_dir.exists():raise FileExistsError(report_dir)
    if jar:
        jar=Path(jar).resolve()
        if not jar_sha or hashlib.sha256(jar.read_bytes()).hexdigest().upper()!=jar_sha.upper():raise ValueError('Final JAR SHA required/mismatched')
    with tempfile.TemporaryDirectory(prefix='piq-bridge-check-') as temp:
        base=Path(temp)/'中文 测试 runtime';base.mkdir()
        if runtime:
            runtime=Path(runtime).resolve()
            for name in ('piq-native-helper.jar','jna-5.14.0.jar','mame_libretro.dll'):
                try:os.link(runtime/name,base/name)
                except OSError:shutil.copyfile(runtime/name,base/name)
            helper={'file':str(runtime/'piq-native-helper.jar'),'sha256':hashlib.sha256((runtime/'piq-native-helper.jar').read_bytes()).hexdigest().upper(),'bytes':(runtime/'piq-native-helper.jar').stat().st_size}
        else:
            helper=build(base/'piq-native-helper.jar')
            shutil.copyfile(JNA,base/'jna-5.14.0.jar')
            try:os.link(POC/'vendor/mame_libretro.dll',base/'mame_libretro.dll')
            except OSError:shutil.copyfile(POC/'vendor/mame_libretro.dll',base/'mame_libretro.dll')
        rom=base/'invaders.zip';firmware=make(rom)
        classes=Path(temp)/'classes';classes.mkdir()
        probes=[ROOT/'tools/qa/NativeBridgeProbe.java',ROOT/'tools/qa/NativeBridgeTerminationProbe.java']
        source=probes if jar else list((ROOT/'src/main/java/cn/piq/nativearcade/bridge').glob('*.java'))+probes
        compile=[JDK/'javac.exe','--release','21','-encoding','UTF-8','-d',classes]
        if jar:compile+=['-cp',jar]
        subprocess.run(list(map(str,compile+source)),check=True,timeout=30)
        cp=(str(jar)+';' if jar else '')+str(classes)
        java=[JDK/'java.exe','-Xmx256m','-cp',cp]
        functional=capture(java+['cn.piq.nativearcade.bridge.NativeBridgeProbe',base,rom])
        shutdown=capture(java+['cn.piq.nativearcade.bridge.NativeBridgeTerminationProbe',base,rom,'shutdown'])
        pid=int(re.search(r'CHILD_PID=(\d+)',shutdown['stdout']).group(1))
        if not child_exited(pid):raise AssertionError('Native child survived parent JVM shutdown')
        shutdown['exact_child_exited']=True
        # Replace only a private QA helper with a deliberate no-frame fixture, never release artifacts.
        stalled=Path(temp)/'stalled';stalled.mkdir()
        subprocess.run(list(map(str,[JDK/'javac.exe','--release','21','-d',stalled,ROOT/'tools/qa/StallWorker.java'])),check=True,timeout=30)
        (base/'piq-native-helper.jar').unlink()
        subprocess.run(list(map(str,[JDK/'jar.exe','--create','--file',base/'piq-native-helper.jar','-C',stalled,'.'])),check=True,timeout=30)
        timeout=capture(java+['cn.piq.nativearcade.bridge.NativeBridgeTerminationProbe',base,rom,'timeout'],timeout=25)
        report={'actual_bridge':True,'parent_jar':str(jar) if jar else None,'parent_jar_sha256':jar_sha,
            'unicode_runtime_classpath':True,'helper':helper,'firmware':firmware,
            'functional_24_checks':functional,'actual_parent_jvm_shutdown':shutdown,'15_second_stall_reap':timeout,
            'source_sha256':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest().upper()
                for p in list((ROOT/'src/main/java/cn/piq/nativearcade/bridge').glob('*.java'))
                +list((ROOT/'helper/src/main/java/cn/piq/nativearcade/bridge').glob('*.java'))},
            'boundary':'Original test firmware only. No commercial game compatibility assertion. Process boundary is not an OS security sandbox. A forced OS kill/crash of the parent JVM is not covered by the orderly-shutdown hook.'}
        report_dir.mkdir(parents=True);(report_dir/'audit.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--report-dir',required=True);p.add_argument('--jar');p.add_argument('--jar-sha256');p.add_argument('--runtime')
    args=p.parse_args();run(args.report_dir,args.jar,args.jar_sha256,args.runtime)
