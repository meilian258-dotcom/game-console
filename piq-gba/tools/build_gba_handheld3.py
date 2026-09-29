"""Build the handheld addon only. Keep the frozen FC33, native bridge and runtime byte-identical."""
import argparse,json,os,shutil,subprocess,sys,tempfile,tomllib,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT.parent/'piq-fc-arcade/tools'))
import verify_retro_alpha19 as q
from build_gba_preview import jar
from check_gba_core import JDK,digest
VERSION='0.1.0-alpha.3'
FC_SHA='F36169E46B13CF46868851447B8518BC38C10967A03994AF26E89CE7B1E4FE00'
BASE=ROOT/'build/server-v2-1'
OLD_SHA='7311F33C5DC8827EAA92672FF897295A4CAB62E9BD5BC0CC09372734D46D2F10'
RUNTIME={
 'piq-gba-helper.jar':'AF687B20AFD470992F9C02C80173356E20E9D9E98FABDDCF3800979F11A4B28C',
 'jna-5.14.0.jar':'34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6',
 'mgba_libretro.dll':'D1BA96BC1AF23997D5C8003A6F6F8BE7ACBA9D770D4D42D14557AAEB469FA16B'}

def sources():
    files=[]
    for sub in ['src','helper/src','tools']:
        files.extend(p for p in (ROOT/sub).rglob('*') if p.is_file() and '__pycache__'not in p.parts and (sub!='tools' or p.suffix in {'.py','.java','.md','.ps1'}))
    files.extend([ROOT/'README.md',ROOT/'design/GBA掌机-alpha3-使用说明.md'])
    return {p.relative_to(ROOT).as_posix():digest(p) for p in sorted(set(files))}

def main():
    p=argparse.ArgumentParser();p.add_argument('--fc',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
    fc=a.fc.resolve(strict=True);out=a.output.resolve()
    assert digest(fc)==FC_SHA,'Use exact frozen FC33; never recompile the main mod'
    assert out.is_relative_to(ROOT/'build') and not out.exists(),'New stage under GBA build required'
    old_path=BASE/'piq_gba-0.1.0-alpha.2.jar';assert digest(old_path)==OLD_SHA
    rt=BASE/'piq-gba/runtime'
    for n,pin in RUNTIME.items():assert digest(rt/n)==pin,'Frozen runtime changed'
    with zipfile.ZipFile(old_path)as z:old={n:z.read(n)for n in z.namelist()}
    before=sources()
    with tempfile.TemporaryDirectory(prefix='piq-gba-handheld-build-')as folder:
        tmp=Path(folder);classes=tmp/'classes';classes.mkdir();empty=tmp/'empty';empty.mkdir()
        local_fc=tmp/'fc.jar';shutil.copyfile(fc,local_fc)
        cp=os.pathsep.join(map(str,[local_fc,q.MC,*q.dependencies()]))
        args=tmp/'javac.args';args.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        command=[JDK/'javac.exe','@'+str(args),'-encoding','UTF-8','--release','21','-proc:none','-sourcepath',empty,'-d',classes,*sorted((ROOT/'src/main/java').rglob('*.java'))]
        result=subprocess.run(list(map(str,command)),cwd=tmp,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
        assert result.returncode==0,result.stdout+'\n'+result.stderr
        entries={f.relative_to(classes).as_posix():f.read_bytes()for f in classes.rglob('*.class')}
        for f in (ROOT/'src/main/resources').rglob('*'):
            if f.is_file():entries[f.relative_to(ROOT/'src/main/resources').as_posix()]=f.read_bytes()
        entries['piq-gba-runtime.properties']=old['piq-gba-runtime.properties']
        entries['META-INF/LICENSE']=old['META-INF/LICENSE']
        entries['META-INF/MANIFEST.MF']=('Manifest-Version: 1.0\nImplementation-Title: PIQ GBA handheld preview\nImplementation-Version: '+VERSION+'\n\n').encode()
        removed=set(old)-set(entries);assert not removed,removed
        added=set(entries)-set(old);changed={n for n in old if old[n]!=entries[n]}
        assert changed<={'cn/piq/gba/GbaMod.class','cn/piq/gba/client/GbaCabinetBackend.class','META-INF/neoforge.mods.toml','META-INF/MANIFEST.MF'},changed
        for n in added:
            assert n.startswith(('cn/piq/gba/client/GbaHandheld','cn/piq/gba/item/GbaHandheld','assets/piq_gba/')),n
        meta=tomllib.loads(entries['META-INF/neoforge.mods.toml'].decode())
        assert [(m['modId'],m['version'])for m in meta['mods']]==[('piq_gba',VERSION)]
        assert sources()==before and digest(fc)==digest(local_fc)==FC_SHA,'Input changed during compilation'
        out.mkdir(parents=True);mod=out/f'piq_gba-{VERSION}.jar';jar(mod,entries)
        shutil.copytree(rt,out/'piq-gba/runtime')
        licenses=out/'licenses-and-source';licenses.mkdir()
        for f in (BASE/'licenses-and-source').iterdir():
            if f.is_file() and f.name!='piq-gba-source.zip':shutil.copyfile(f,licenses/f.name)
        jar(licenses/'piq-gba-source.zip',{n:(ROOT/n).read_bytes()for n in before})
        shutil.copyfile(ROOT/'design/GBA掌机-alpha3-使用说明.md',out/'使用说明.md')
        for n,pin in RUNTIME.items():assert digest(out/'piq-gba/runtime'/n)==digest(rt/n)==pin
        assert before==sources() and digest(fc)==FC_SHA and digest(old_path)==OLD_SHA
        report={'schema':'piq-gba-handheld-build-3','ok':True,'source_production_compiled':True,'helper_compiled':False,
                'jars':{'fc':{'path':str(fc),'sha256':FC_SHA},'gba':{'path':str(mod),'sha256':digest(mod)}},
                'runtime':{n:{'sha256':pin,'bytes':(rt/n).stat().st_size}for n,pin in RUNTIME.items()},
                'baseline_sha256':OLD_SHA,'added':sorted(added),'changed':sorted(changed),'removed':[],
                'source_sha256':before,'compile_stdout':result.stdout,'compile_stderr':result.stderr,
                'installed':False,'minecraft_started':False,'old_bridge_byte_identical':True}
        with (out/'build-witness.json').open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'output':str(out),'jars':report['jars'],'added':len(added),'changed':sorted(changed)},ensure_ascii=False))
if __name__=='__main__':main()
