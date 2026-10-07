"""Actual native parent lifecycle, isolated fake child: no MAME/ROM/game execution."""

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home, java_home

import argparse,concurrent.futures,hashlib,json,os,subprocess,sys,tempfile,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JDK=(java_home() / 'bin')
CORE=ROOT.parent/'piq-native-arcade-poc/vendor/mame_libretro.dll'
JNA=(gradle_home() / 'caches/modules-2/files-2.1/net.java.dev.jna/jna/5.14.0/67bf3eaea4f0718cb376a181a629e5f88fa1c9dd/jna-5.14.0.jar')

def sha(path):
    value=hashlib.sha256()
    with path.open('rb')as inp:
        for part in iter(lambda:inp.read(131072),b''):value.update(part)
    return value.hexdigest().upper()

def run(command,cwd,timeout=40):
    result=subprocess.run(list(map(str,command)),cwd=cwd,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
    if result.returncode:raise AssertionError(result.stdout+'\n'+result.stderr)
    return result.stdout

def main():
    sys.stdout.reconfigure(encoding='utf-8');p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--mod',type=Path,help='Optional final native MOD or explicitly marked private parent prototype JAR; only probes/fake helper compiled.')
    p.add_argument('--report',required=True,type=Path);a=p.parse_args()
    if a.report.exists():raise FileExistsError(a.report)
    assert sha(CORE)=='6172A988AB67FE68F4177A6FC8FBB82619EB2044C330930F0F572F7B1EDC2301'
    assert sha(JNA)=='34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6'
    names=['NativeStepSession','NativeProcessSession','NativeStepProtocol','NativeRomStaging','BridgeProtocol','NativeInputPorts','NativeArcadeButtons']
    sources=[ROOT/f'src/main/java/cn/piq/nativearcade/bridge/{name}.java'for name in names]
    probe=ROOT/'tools/qa/NativeStepLifecycleProbe.java';fake=ROOT/'tools/qa/fake-step/cn/piq/nativearcade/bridge/NativeStepWorker.java'
    inputs=[probe,fake,Path(__file__).resolve(),CORE,JNA,*([a.mod.resolve()]if a.mod else sources)]
    before={str(x):sha(x)for x in inputs};began=time.monotonic();results=[]
    with tempfile.TemporaryDirectory(prefix='piq-native-lifecycle-')as folder:
        tmp=Path(folder);classes=tmp/'classes';classes.mkdir();fakeclasses=tmp/'fake';fakeclasses.mkdir()
        actual=a.mod.resolve()if a.mod else classes
        compile_inputs=[probe]if a.mod else sources+[probe]
        run([JDK/'javac.exe','--release','21','-encoding','UTF-8','-proc:none','-cp',actual,'-d',classes,*compile_inputs],tmp)
        cp=os.pathsep.join(map(str,[classes,*([actual]if a.mod else [])]))
        run([JDK/'javac.exe','--release','21','-encoding','UTF-8','-proc:none','-cp',actual,'-d',fakeclasses,fake],tmp)
        # Copy only the real immutable wire classes; the fake worker has no production engine code.
        import zipfile
        helper=tmp/'qa-fake-step-helper.jar'
        with zipfile.ZipFile(helper,'x',compression=zipfile.ZIP_DEFLATED)as out:
            for file in fakeclasses.rglob('*.class'):out.writestr(file.relative_to(fakeclasses).as_posix(),file.read_bytes())
            prefix='cn/piq/nativearcade/bridge/NativeStepProtocol'
            if a.mod:
                with zipfile.ZipFile(actual)as jar:
                    for name in jar.namelist():
                        if name.startswith(prefix)and name.endswith('.class'):out.writestr(name,jar.read(name))
            else:
                for file in (classes/'cn/piq/nativearcade/bridge').glob('NativeStepProtocol*.class'):out.writestr(file.relative_to(classes).as_posix(),file.read_bytes())
        helper_sha=sha(helper)
        def one(mode):
            work=tmp/mode;work.mkdir()
            raw=run([JDK/'java.exe','-cp',cp,'cn.piq.nativearcade.bridge.NativeStepLifecycleProbe',mode,actual,work,helper,helper_sha,CORE,before[str(CORE)],JNA,before[str(JNA)]],work,40)
            return json.loads(next(line for line in reversed(raw.splitlines())if line.startswith('{')))
        # Real 15 s deadlines are not shortened or mocked; four independent parent JVMs overlap.
        with concurrent.futures.ThreadPoolExecutor(max_workers=4)as pool:
            pending={pool.submit(one,mode):mode for mode in ['nohello','deadline','idle','cancel','normal','badhash','badid','badframe','eof']}
            for future in concurrent.futures.as_completed(pending):
                result=future.result();results.append(result);print(json.dumps(result),flush=True)
    assert all(sha(Path(path))==digest for path,digest in before.items()),'Input changed during lifecycle QA'
    report={'ok':True,'mode':'final-jar-only'if a.mod else 'production-source','production_compiled':not bool(a.mod),'assertions':sum(x['assertions']for x in results),'elapsed_seconds':round(time.monotonic()-began,3),'cases':sorted(results,key=lambda x:x['case']),'fake_helper_sha256':helper_sha,'input_sha256':before,'limitations':['Actual NativeStepSession and shared NativeProcessSession lease; fake child only, no native DLL loaded or game started.','This does not prove MAME determinism or unload/deinit behavior. Normal parent close currently uses exact-child termination.','Real DLL/JNA files are only read for identity checks; generated test ZIPs contain no ROM.']}
    if a.mod:
        report['jars']={'native':{'path':str(a.mod.resolve()),'sha256':before[str(a.mod.resolve())]}}
        with zipfile.ZipFile(a.mod)as actual_jar:
            manifest=actual_jar.read('META-INF/MANIFEST.MF').decode('utf-8')if 'META-INF/MANIFEST.MF'in actual_jar.namelist()else''
            prototype='PIQ-Artifact-Kind: private-step-parent-prototype-not-a-mod'in manifest
            if prototype:assert 'META-INF/neoforge.mods.toml'not in actual_jar.namelist(),'Prototype must not declare a MOD'
        report['artifact_kind']='private-step-parent-prototype-not-a-mod'if prototype else'native-mod-jar'
        report['production_delivery']=not prototype
        if prototype:report['limitations'].append('The --mod argument points to a private pure-Java parent prototype, not an installable Minecraft MOD. Final-jar-only describes class origin, not production-release status.')
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as out:json.dump(report,out,ensure_ascii=False,indent=2)
    print(json.dumps({k:v for k,v in report.items()if k not in ('input_sha256','cases')},ensure_ascii=False))
if __name__=='__main__':main()
