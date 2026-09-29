"""Final FC19 + merged SFC6 only; two frozen WASM cores, original test ROM."""
import argparse,hashlib,json,os,subprocess,tempfile,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')

def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest().upper()
def run(command,cwd,timeout=180):
    p=subprocess.run(list(map(str,command)),cwd=cwd,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
    if p.returncode:raise AssertionError(p.stdout+'\n'+p.stderr)
    return p.stdout

def main():
    sys.stdout.reconfigure(encoding='utf-8');parser=argparse.ArgumentParser()
    for name in ['fc','sfc','report']:parser.add_argument('--'+name,type=Path,required=True)
    args=parser.parse_args()
    if args.report.exists():raise ValueError('Old evidence is immutable')
    paths={key:getattr(args,key).resolve(strict=True)for key in ['fc','sfc']};before={key:sha(path)for key,path in paths.items()}
    with tempfile.TemporaryDirectory(prefix='piq-alpha19-sfc-cores-')as folder:
        tmp=Path(folder);classes=tmp/'classes';classes.mkdir();empty=tmp/'empty';empty.mkdir();copies={}
        for key,path in paths.items():
            target=tmp/(key+'.jar')
            with target.open('xb')as out:out.write(path.read_bytes())
            assert sha(target)==before[key] and sha(path)==before[key];copies[key]=target
        cp=os.pathsep.join(map(str,[classes,copies['sfc'],copies['fc']]))
        # Explicit test/probe sources only. Gate and the actual core come from
        # the final merged JAR, never production source or old split core JAR.
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',classes,
             ROOT/'tools/qa/SfcJoinCoreProbe.java',ROOT/'tools/qa/Alpha19SfcCoreOriginProbe.java',
             ROOT.parent/'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/core/SfcLegalTestRom.java'],tmp)
        output=run([JAVA/'java.exe','-Xmx2G','-cp',cp,'cn.piq.sfcarcade.core.Alpha19SfcCoreOriginProbe',copies['fc'],copies['sfc']],tmp)
        actual=json.loads(next(line for line in reversed(output.splitlines())if line.startswith('{')))
        assert actual['passed'] and actual['live_core_instances']==2 and actual['synchronized_following_frames']==240
        assert all(sha(paths[key])==before[key] and sha(copies[key])==before[key]for key in paths)
    report={'passed':True,'mode':'final-jar-only','actual':actual,'jars':{key:{'path':str(path),'sha256':before[key]}for key,path in paths.items()},
            'production_compiled':False,'old_separate_core_on_classpath':False,'origin_checks':'WasmSfcCore, SfcRomImage and SfcJoinGate belong to SHA-checked final merged SFC; ai.tegmentum.wasmtime4j.Engine belongs to SHA-checked final FC.',
            'scope':'Two real final-JAR WASM cores, original diagnostic ROM, actual save/load then equal video/audio/state; final production consent gate.',
            'limitations':['No Minecraft clients, network socket, protection plugins or actual controller exercised.','Diagnostic ROM only, not a claim every commercial game allows midgame simultaneous two-player.','Production SfcPlayback thread and live packet dispatch are not executed.'],
            'commercial_roms':False,'minecraft_started':False,'installed':False}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as out:json.dump(report,out,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
