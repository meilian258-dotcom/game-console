"""Actual v3 helper/core-option/four-port/bridge tests using only original diagnostics."""
import argparse,hashlib,json,os,shutil,subprocess,sys,tempfile
from pathlib import Path
from build_native_helper import ROOT,JDK,JNA
from check_native_bridge import capture
from make_diagnostic_rom import make
sys.path.insert(0,str(ROOT.parent/'piq-fc-arcade/tools'))
import verify_retro_alpha19 as q
HELPER_SHA='20F6F3028D76DAEB01212D1808BE90E35BFB5429D1E06153B7D8B32DD73E943C'
DLL=ROOT.parent/'piq-native-arcade-poc/vendor/mame_libretro.dll'
OLD_HELPER=ROOT.parent/'piq-fc-arcade/build/review-controls25-v1/piq-native-arcade/runtime/piq-native-helper.jar'

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest().upper()
def capture_core(command,cwd,timeout=40):
    # Match production's isolated working directory. Do not let an in-process
    # native QA probe consult or create files in the repository working directory.
    stdout_path=Path(cwd)/'core.stdout.log';stderr_path=Path(cwd)/'core.stderr.log';timed_out=False
    # Native C stdio and Java logs go to files here, not Python reader pipes.
    # A timeout must preserve phase evidence and always close the exact owned JVM.
    with stdout_path.open('xb')as out,stderr_path.open('xb')as err:
        child=subprocess.Popen(list(map(str,command)),cwd=cwd,stdout=out,stderr=err)
        try:child.wait(timeout=timeout)
        except subprocess.TimeoutExpired:timed_out=True
        finally:
            if child.poll()is None:child.kill();child.wait(timeout=10)
    stdout=stdout_path.read_text(encoding='utf-8',errors='replace');stderr=stderr_path.read_text(encoding='utf-8',errors='replace')
    if timed_out:raise RuntimeError('Core QA timed out; exact owned JVM terminated.\n'+stdout[-5000:]+'\n'+stderr[-7000:])
    if child.returncode:raise RuntimeError('Core QA exit '+str(child.returncode)+'\n'+stdout+'\n'+stderr)
    return {'stdout':stdout,'stderr':stderr,'isolated_working_directory':str(cwd)}
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--helper',required=True,type=Path);parser.add_argument('--jar',type=Path);parser.add_argument('--report',required=True,type=Path);args=parser.parse_args()
    assert not args.report.exists(),'New report path required'
    assert sha(args.helper)==HELPER_SHA
    assert sha(DLL)=='6172A988AB67FE68F4177A6FC8FBB82619EB2044C330930F0F572F7B1EDC2301'
    with tempfile.TemporaryDirectory(prefix='piq-native-buttons26-')as folder:
        tmp=Path(folder);runtime=tmp/'runtime';runtime.mkdir();out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir()
        for source,name in [(args.helper,'piq-native-helper.jar'),(DLL,'mame_libretro.dll'),(JNA,'jna-5.14.0.jar')]:shutil.copyfile(source,runtime/name);assert sha(source)==sha(runtime/name)
        rom=tmp/'invaders.zip';firmware=make(rom)
        deps=[out];source=[]
        if args.jar:
            parent=tmp/'parent.jar';shutil.copyfile(args.jar,parent);assert sha(parent)==sha(args.jar);deps.append(parent)
        else:
            source=list((ROOT/'src/main/java/cn/piq/nativearcade/bridge').glob('*.java'));parent=out
        deps.extend([runtime/'piq-native-helper.jar',runtime/'jna-5.14.0.jar',*q.dependencies()])
        cp=os.pathsep.join(map(str,deps));arg=tmp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        probes=[ROOT/'tools/qa'/(name+'.java')for name in ['NativeFourPortHelper26Probe','NativeCoreOptions26Probe','NativeBridgeProbe','NativeFourPortBridgeProbe','NativeHelperIdentity26Probe']]
        tests=[ROOT/'src/test/java/cn/piq/nativearcade/bridge'/name for name in ['NativeArcadeButtonsTest.java','NativeInputPortsTest.java']]
        runner=ROOT.parent/'piq-fc-arcade/tools/qa/CabinetRoomTestRunner.java'
        compile_log=capture([JDK/'javac.exe','@'+str(arg),'-encoding','UTF-8','--release','21','-proc:none','-sourcepath',empty,'-d',out,*source,*probes,*tests,runner])
        behavior=capture([JDK/'java.exe','@'+str(arg),'CabinetRoomTestRunner','cn.piq.nativearcade.bridge.NativeArcadeButtonsTest','cn.piq.nativearcade.bridge.NativeInputPortsTest'])
        helper=capture([JDK/'java.exe','@'+str(arg),'cn.piq.nativearcade.bridge.NativeFourPortHelper26Probe',runtime/'piq-native-helper.jar',parent])
        core=capture_core([JDK/'java.exe','@'+str(arg),'cn.piq.nativearcade.bridge.NativeCoreOptions26Probe',runtime/'mame_libretro.dll',rom,runtime/'piq-native-helper.jar'],tmp,timeout=40)
        bridge=capture([JDK/'java.exe','@'+str(arg),'cn.piq.nativearcade.bridge.NativeFourPortBridgeProbe',runtime,rom,parent],timeout=40)
        assert sha(OLD_HELPER)=='229268989AD4E263277FDF0BD5D49E59F69EEB3E980B948DD1D3628182437120'
        oldruntime=tmp/'old-runtime';oldruntime.mkdir();shutil.copyfile(OLD_HELPER,oldruntime/'piq-native-helper.jar')
        for name in ['mame_libretro.dll','jna-5.14.0.jar']:os.link(runtime/name,oldruntime/name)
        rejection=capture([JDK/'java.exe','@'+str(arg),'cn.piq.nativearcade.bridge.NativeHelperIdentity26Probe',oldruntime,rom,parent])
        result={'ok':True,'schema':'piq-native-buttons26-1','production_compiled':not bool(args.jar),'production_origin':'final-jar-only'if args.jar else'explicit-parent-source-and-final-helper',
                'helper':{'path':str(args.helper.resolve()),'sha256':sha(args.helper)},'parent':{'path':str(args.jar.resolve()),'sha256':sha(args.jar)}if args.jar else None,
                'runtime_dll_sha256':sha(DLL),'private_pipe_version':3,'network_formats_changed':False,'mame_dll_modified':False,'minecraft_started':False,'instance_modified':False,
                'behavior':json.loads(behavior['stdout'].strip().splitlines()[-1]),'helper_parser_callback':json.loads(helper['stdout'].strip().splitlines()[-1]),
                'actual_core_options':json.loads(core['stdout'].strip().splitlines()[-1]),'actual_bridge':json.loads(bridge['stdout'].strip().splitlines()[-1]),'firmware':firmware,
                'old_helper_rejection':json.loads(rejection['stdout'].strip().splitlines()[-1]),'old_helper_sha256':sha(OLD_HELPER),
                'logs':{'compile':compile_log,'behavior':behavior,'helper':helper,'core':core,'bridge':bridge,'rejection':rejection},
                'limits':['Numbered host buttons convert at the helper callback for every port and input source. Game-specific profiles are explicitly disabled; the old pinned default was already disabled.',
                          'Direct-DLL probe natural JVM exit is not guaranteed: after all 251 assertions and successful unload/deinit/result flush, its dedicated QA JVM explicitly halts. Failure paths cannot report success or reach that halt. Production parent/child lifecycle is separately validated by the actual bridge test.',
                          'Actual original invaders diagnostic exercises its supported hardware inputs only, not KOF97/Dino gameplay or six-button game action effects.',
                          'No commercial ROM, Minecraft, network player, user save, user config or user runtime was loaded/modified.']}
        assert sha(args.helper)==HELPER_SHA and (not args.jar or sha(args.jar)==sha(parent))
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as f:json.dump(result,f,ensure_ascii=False,indent=2)
    print(json.dumps({k:result[k]for k in ['ok','helper','behavior','helper_parser_callback','actual_core_options','actual_bridge']},ensure_ascii=True))
if __name__=='__main__':main()
