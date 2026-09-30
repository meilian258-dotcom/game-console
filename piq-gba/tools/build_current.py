"""Compile the complete addon against explicit FC/NeoForge inputs; retain frozen native assets.
Runtime-base must be an approved existing GBA distribution (not an arbitrary DLL).
No downloads, installation, or native execution. Output directory must be new.
"""
import argparse,hashlib,json,os,subprocess,tempfile,tomllib,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    p=argparse.ArgumentParser();p.add_argument('--fc',type=Path,required=True);p.add_argument('--runtime-base',type=Path,required=True);p.add_argument('--dependencies',type=Path,required=True);p.add_argument('--jdk',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    a.output.mkdir(parents=True,exist_ok=False)
    sources=sorted((ROOT/'src/main/java').rglob('*.java'))+[ROOT/'helper/src/main/java/cn/piq/gba/bridge/PcmResampler.java']
    resources=sorted(p for p in (ROOT/'src/main/resources').rglob('*') if p.is_file())
    inputs={str(p):sha(p) for p in [a.fc,a.runtime_base,*sources,*resources]}
    with zipfile.ZipFile(a.runtime_base) as z:
        names=z.namelist();assert len(names)==len(set(names));assert z.testzip() is None
        core='native-runtime/win-x64-v1/piq-gba/runtime/mgba_libretro.dll'
        assert hashlib.sha256(z.read(core)).hexdigest()=='d1ba96bc1af23997d5c8003a6f6f8be7acba9d770d4d42d14557aaeb469fa16b'
        entries={n:z.read(n) for n in names if not n.endswith('/') and (n.startswith('native-runtime/') or n.startswith('META-INF/LICENSE') or n in ['piq-gba-runtime.properties','META-INF/THIRD_PARTY_NOTICES'])}
    with tempfile.TemporaryDirectory(prefix='gba13-build-') as folder:
        temp=Path(folder);classes=temp/'classes';classes.mkdir();empty=temp/'empty';empty.mkdir()
        deps=[p for p in a.dependencies.glob('*.jar') if not p.name.startswith(('piq_','game-console'))]
        cp=os.pathsep.join(map(str,[a.fc,*deps]));args=temp/'compile.args';args.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf8')
        with (a.output/'compile.log').open('xb') as log:
            result=subprocess.run([str(a.jdk/'javac.exe'),'@'+str(args),'-encoding','UTF-8','--release','21','-proc:none','-sourcepath',str(empty),'-d',str(classes),*map(str,sources)],stdout=log,stderr=subprocess.STDOUT,timeout=180)
        if result.returncode:raise RuntimeError('Compile failed: '+str(a.output/'compile.log'))
        for f in classes.rglob('*.class'):entries[f.relative_to(classes).as_posix()]=f.read_bytes()
        for f in resources:entries[f.relative_to(ROOT/'src/main/resources').as_posix()]=f.read_bytes()
    meta=tomllib.loads(entries['META-INF/neoforge.mods.toml'].decode());version=meta['mods'][0]['version']
    entries['META-INF/MANIFEST.MF']=f'Manifest-Version: 1.0\nImplementation-Title: Game Console GBA\nImplementation-Version: {version}\n\n'.encode()
    assert all(sha(Path(n))==v for n,v in inputs.items())
    assert not any(n.startswith('cn/piq/retro/') for n in entries)
    jar=a.output/f'game-console-gba-{version}.jar'
    with zipfile.ZipFile(jar,'x',zipfile.ZIP_DEFLATED) as z:
        for n,b in sorted(entries.items()):
            info=zipfile.ZipInfo(n,(2026,9,30,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,b)
    report=dict(ok=True,jar=str(jar),sha256=sha(jar),inputs=inputs,nativeRebuilt=False,minecraftTested=False)
    (a.output/'build.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf8')
    print(json.dumps({k:v for k,v in report.items() if k!='inputs'},ensure_ascii=False))
if __name__=='__main__':main()
