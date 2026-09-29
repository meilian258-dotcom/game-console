"""Isolated real MAME IPC evidence. Source ROM/BIOS read-only; no install or netplay qualification."""
import argparse,hashlib,json,os,shutil,subprocess,tempfile
from pathlib import Path
from build_native_step_helper import ROOT,JDK,JNA,JNA_SHA,sha

CORE=ROOT.parent/'piq-native-arcade-poc/vendor/mame_libretro.dll'
CORE_SHA='6172A988AB67FE68F4177A6FC8FBB82619EB2044C330930F0F572F7B1EDC2301'

def fingerprint(path):
    s=path.stat();return {'sha256':sha(path),'bytes':s.st_size,'mtime_ns':s.st_mtime_ns}

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--helper',required=True,type=Path);p.add_argument('--helper-sha',required=True)
    p.add_argument('--parent',type=Path,help='Optional final private parent JAR; compile only the QA entry point.')
    p.add_argument('--rom',required=True,type=Path);p.add_argument('--bios',required=True,type=Path)
    p.add_argument('--report',required=True,type=Path);a=p.parse_args()
    if a.report.exists():raise FileExistsError(a.report)
    assert a.rom.name in ('kof97.zip','mslug2.zip') and a.bios.name=='neogeo.zip'
    assert sha(a.helper)==a.helper_sha and sha(CORE)==CORE_SHA and sha(JNA)==JNA_SHA
    base=ROOT/'src/main/java/cn/piq/nativearcade/bridge'
    sources=[base/(n+'.java')for n in ('NativeStepSession','NativeProcessSession','NativeRomStaging')]
    sources.append(ROOT/'tools/qa/NativeStepMameProbe.java')
    compile_sources=[sources[-1]] if a.parent else sources
    fence={str(x):fingerprint(x)for x in [*compile_sources,*([a.parent]if a.parent else []),a.rom,a.bios,a.helper,CORE,JNA,Path(__file__).resolve()]}
    with tempfile.TemporaryDirectory(prefix='piq-step-mame-qa-owned-')as folder:
        tmp=Path(folder);classes=tmp/'classes';classes.mkdir();roms=tmp/'roms';roms.mkdir()
        for original in (a.rom,a.bios):
            destination=roms/original.name;shutil.copyfile(original,destination)
            assert sha(destination)==fence[str(original)]['sha256']
        cp=os.pathsep.join(map(str,[classes,(a.parent or a.helper).resolve()]))
        compile=subprocess.run(list(map(str,[JDK/'javac.exe','-encoding','UTF-8','--release','21','-proc:none','-implicit:none',
            '-cp',cp,'-d',classes,*compile_sources])),capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=40)
        assert compile.returncode==0,compile.stdout+compile.stderr
        command=[JDK/'java.exe','-Xmx256m','-cp',cp,'NativeStepMameProbe',a.helper.resolve(),a.helper_sha,CORE,JNA,JNA_SHA,roms/a.rom.name]
        run=subprocess.run(list(map(str,command)),capture_output=True,text=True,encoding='utf-8',errors='replace',cwd=tmp,timeout=90,
            creationflags=getattr(subprocess,'BELOW_NORMAL_PRIORITY_CLASS',0))
        assert run.returncode==0,run.stdout+run.stderr
        evidence=json.loads(next(x for x in reversed(run.stdout.splitlines())if x.startswith('{')))
    assert all(fingerprint(Path(path))==identity for path,identity in fence.items()),'Input/source changed during probe'
    report={'ok':True,'driver':a.rom.stem,'mode':'final-private-JAR-only'if a.parent else 'parent-production-source/helper-final-JAR','production_compiled':not bool(a.parent),
        'evidence':evidence,'sources_unchanged':True,'source_fingerprints':fence,'helper_sha256':a.helper_sha,
        'limitations':['Real fixed MAME + actual parent IPC, not Minecraft/world/server/socket tests.',
            'IPC correct does not mean emulator snapshot state or PCM restores deterministically.',
            'No mod registration, runtime installation, user file edits or unrelated process termination.']}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as out:json.dump(report,out,ensure_ascii=False,indent=2)
    print(json.dumps({'driver':a.rom.stem,'ok':True,'evidence':evidence}))

if __name__=='__main__':main()
