"""Real original-ARM SRAM shutdown regression, plus explicitly controlled lifecycle races.

No user ROM/save, Minecraft, deployment or physical audio. Final mode compiles
only this probe; --source compiles the two owned changed production classes.
"""
from pathlib import Path
import argparse,hashlib,json,os,subprocess,sys,time
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'piq-fc-arcade/tools'))
import verify_retro_alpha19 as q
from diagnostic_rom import create
CORE='D1BA96BC1AF23997D5C8003A6F6F8BE7ACBA9D770D4D42D14557AAEB469FA16B'
JNA='34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6'
HELPER='AF687B20AFD470992F9C02C80173356E20E9D9E98FABDDCF3800979F11A4B28C'
SOURCES=[ROOT/'piq-gba/src/main/java/cn/piq/gba'/p for p in ['bridge/GbaProcessSession.java','client/GbaHandheldClient.java']]
PROBE=ROOT/'piq-gba/tools/qa/GbaShutdownRegression.java'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest().upper()
def main():
    sys.stdout.reconfigure(encoding='utf8');parser=argparse.ArgumentParser();parser.add_argument('--gba',type=Path,required=True);parser.add_argument('--fc',type=Path,required=True);parser.add_argument('--runtime',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--source',action='store_true');a=parser.parse_args()
    gba=a.gba.resolve();fc=a.fc.resolve();runtime=a.runtime.resolve();out=a.output.resolve();out.mkdir(parents=True,exist_ok=False)
    snapshot={str(p):sha(p)for p in [gba,fc,*SOURCES,PROBE,Path(__file__).resolve(),Path(__file__).with_name('diagnostic_rom.py')]}
    files={name:runtime/name for name in ['mgba_libretro.dll','jna-5.14.0.jar','piq-gba-helper.jar']}
    assert {n:sha(p)for n,p in files.items()}=={'mgba_libretro.dll':CORE,'jna-5.14.0.jar':JNA,'piq-gba-helper.jar':HELPER}
    classes=out/'probe';classes.mkdir();empty=out/'empty';empty.mkdir();production=out/'production'if a.source else gba
    if a.source:production.mkdir()
    deps=q.dependencies();cp=[classes,*([production]if a.source else []),gba,fc,q.MC,*deps]
    args=out/'classpath.args';args.write_text('-cp\n"'+os.pathsep.join(os.path.relpath(p,out)if p.is_relative_to(ROOT)else str(p)for p in cp).replace('\\','/')+'"\n',encoding='utf8')
    if a.source:q.run([q.JAVA/'javac.exe','@'+str(args),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',production,*SOURCES],out)
    q.run([q.JAVA/'javac.exe','@'+str(args),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',classes,PROBE],out)
    rom=out/'original-arm-sram-diagnostic.gba';rom.write_bytes(create());rom_sha=sha(rom)
    results=[]
    for i,mode in enumerate(['inactive','await','immediate','immediate','hook-only','spawn-close','spawn-shutdown','constructor-failure','timeout']):
        saves=out/f'saves-{i}-{mode}';start=time.monotonic()
        done=subprocess.run([str(q.JAVA/'java.exe'),'-Djava.awt.headless=true','@'+str(args),'cn.piq.gba.bridge.GbaShutdownRegression',str(production),mode,str(runtime),str(rom),str(saves),HELPER],cwd=out,stdout=subprocess.PIPE,stderr=subprocess.PIPE,text=True,encoding='utf8',errors='replace',timeout=22)
        item={'scenario':mode,'elapsed_seconds':time.monotonic()-start,'exit_code':done.returncode,'stdout':done.stdout,'stderr':done.stderr};results.append(item)
        assert done.returncode==0,item
        item['probe']=q.parse_last_json(done.stdout);assert item['probe']['ok']
        bins=list(saves.rglob('sram.bin'));item['saves']=[{'path':str(p),'sha256':sha(p),'bytes':p.stat().st_size,'first_byte':p.read_bytes()[0]}for p in bins]
        if mode in ['await','immediate','hook-only']:assert len(bins)==1 and bins[0].read_bytes()[0]==3,item
        pid=item['probe']['owned_pid']
        if pid>0:
            # Java ProcessHandle was checked inside lifecycle modes; ensure no exact helper survives System.exit too.
            check=subprocess.run(['powershell','-NoProfile','-Command',f'if (Get-Process -Id {pid} -ErrorAction SilentlyContinue) {{ exit 1 }}'],capture_output=True,timeout=5)
            assert check.returncode==0,('Owned diagnostic child survived',pid)
        item['owned_child_dead_after_jvm_exit']=True
    assert all(sha(Path(p))==h for p,h in snapshot.items());assert sha(rom)==rom_sha
    assert {n:sha(p)for n,p in files.items()}=={'mgba_libretro.dll':CORE,'jna-5.14.0.jar':JNA,'piq-gba-helper.jar':HELPER}
    report={'schema':'piq-gba-shutdown-1','ok':True,'mode':'isolated-source'if a.source else 'final-jar-only','production_compiled':a.source,
            'jars':{n:{'path':str(p),'sha256':sha(p)}for n,p in [('fc',fc),('gba',gba)]},'runtime':{n:{'path':str(p),'sha256':sha(p)}for n,p in files.items()},
            'source_sha256':snapshot,'original_rom':{'path':str(rom),'sha256':rom_sha,'provenance':'tools/diagnostic_rom.py original ARM instructions; no user/commercial ROM'},'results':results,
            'limits':['Normal System.exit and production shutdown hook tested in independent JVMs, not a live Minecraft shutdown.',
                      'Spawn delay and timeout use a labelled ChildLauncher seam. Timeout Process is controlled, not a claim that the real core hung.',
                      'The six-second budget bounds Java waiting. A pathological OS process-start/termination or filesystem call cannot be given an absolute OS-level deadline. Late returned children remain exact-owned.'],
            'minecraft_started':False,'user_rom_or_save_used':False,'assertions':sum(r['probe']['assertions']for r in results)}
    target=out/'report.json';target.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf8');print(json.dumps({'ok':True,'report':str(target),'sha256':sha(target),'scenarios':len(results),'assertions':report['assertions']},ensure_ascii=False))
if __name__=='__main__':main()
