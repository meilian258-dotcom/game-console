"""Execute exactly the delivered parent JAR and helper/runtime, compiling QA code only."""
from pathlib import Path
import hashlib,json,os,subprocess,tempfile

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT.parent/'制作Mod/03-街机模拟/PIQ原生街机/0.1.0-alpha.1'
JDK=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest().upper()
def call(args,timeout=40):
    r=subprocess.run(list(map(str,args)),capture_output=True,timeout=timeout)
    text=r.stdout.decode('utf-8',errors='replace');err=r.stderr.decode('utf-8',errors='replace')
    if r.returncode:raise AssertionError(text+'\n'+err)
    return text
def main():
    jar=OUT/'mods/piq_native_arcade-0.1.0-alpha.1.jar';runtime=OUT/'piq-native-arcade/runtime';rom=OUT/'piq-native-arcade/diagnostic/invaders.zip'
    report=OUT/'final-delivered-runtime-smoke.json';assert not report.exists()
    files=[jar,rom,*runtime.glob('*')];before={str(p.relative_to(OUT)):sha(p) for p in files}
    assert before[str(jar.relative_to(OUT))]=='CEE8B775B57C34781EA5695D4C4991004AA4D4431CA583FB8166C310C188F4BC'
    with tempfile.TemporaryDirectory(prefix='piq-final-native-qa-') as temp:
        temp=Path(temp);empty=temp/'empty';empty.mkdir()
        sources=[ROOT/'tools/qa'/n for n in ('NativeBridgeProbe.java','NativeBridgeTerminationProbe.java')]
        call([JDK/'javac.exe','--release','21','-encoding','UTF-8','-sourcepath',empty,'-cp',jar,'-d',temp,*sources])
        assert not (temp/'cn/piq/nativearcade/bridge/NativeProcessSession.class').exists()
        java=[JDK/'java.exe','-cp',str(jar)+os.pathsep+str(temp)]
        functional=call(java+['cn.piq.nativearcade.bridge.NativeBridgeProbe',runtime,rom])
        assert 'PASS=24' in functional
        shutdown=call(java+['cn.piq.nativearcade.bridge.NativeBridgeTerminationProbe',runtime,rom,'shutdown'])
        pid=int(next(line.split('=')[1] for line in shutdown.splitlines() if line.startswith('CHILD_PID=')))
        # Read-only exact PID check; never terminate anything here.
        check=subprocess.run(['powershell.exe','-NoProfile','-Command',f'if(Get-Process -Id {pid} -ErrorAction SilentlyContinue){{exit 3}}'],capture_output=True,timeout=10)
        assert check.returncode==0,'Final helper survived parent orderly shutdown'
    assert before=={str(p.relative_to(OUT)):sha(p) for p in files}
    result={'status':'passed-actual-delivered-parent-and-helper','sha256':before,'functional':functional,
        'parent_orderly_shutdown':shutdown,'exact_child_no_longer_alive':True,
        'boundary':'No Minecraft world/gameplay or commercial ROM was loaded. Real MAME process, original firmware, PCM, input and process cleanup.'}
    with report.open('x',encoding='utf-8') as f:json.dump(result,f,ensure_ascii=False,indent=2)
    print(json.dumps(result,ensure_ascii=True,indent=2))
if __name__=='__main__':main()
