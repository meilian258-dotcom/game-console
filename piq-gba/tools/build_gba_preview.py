"""Independent Java21 build; no Gradle, no edits to existing FC/Native projects."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,sys,tempfile,zipfile
ROOT=Path(__file__).resolve().parents[1]
VERSION='0.1.0-alpha.2'
sys.path.insert(0,str(ROOT.parent/'piq-fc-arcade/tools'))
import verify_retro_alpha19 as q
from check_gba_core import JDK,JNA,CORE,SHA,digest
from diagnostic_rom import create

def jar(path,entries):
    with zipfile.ZipFile(path,'x',compression=zipfile.ZIP_DEFLATED) as z:
        for name,data in sorted(entries.items()):
            info=zipfile.ZipInfo(name,(2026,9,11,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,data)

def main():
    p=argparse.ArgumentParser();p.add_argument('--fc',required=True);p.add_argument('--output',required=True);a=p.parse_args()
    fc=Path(a.fc).resolve();output=Path(a.output).resolve()
    if output.exists() or not output.is_relative_to(ROOT):raise ValueError('New output inside GBA required')
    if digest(CORE)!=SHA:raise ValueError('core hash')
    source_paths=sorted((ROOT/'helper/src/main/java').rglob('*.java'))+sorted((ROOT/'src/main/java').rglob('*.java'))
    source_paths+=sorted(p for p in (ROOT/'src/main/resources').rglob('*') if p.is_file())
    source_paths+=sorted(p for p in (ROOT/'tools').rglob('*') if p.is_file() and p.suffix in ('.py','.java'))+[ROOT/'README.md']
    fence={str(x.relative_to(ROOT)):digest(x) for x in source_paths}
    with tempfile.TemporaryDirectory(prefix='piq-gba-build-') as temp:
        d=Path(temp);h=d/'helper';h.mkdir();m=d/'main';m.mkdir();qa=d/'qa';qa.mkdir();rt=d/'runtime';rt.mkdir()
        local_fc=d/'fc.jar';shutil.copyfile(fc,local_fc)
        def run(cmd,limit=60):
            command=list(map(str,cmd))
            if sum(map(len,command))>20000:
                argsfile=d/'java-args.txt'
                argsfile.write_text('\n'.join('"'+arg.replace('\\','\\\\').replace('"','\\"')+'"' for arg in command[1:]),encoding='utf-8')
                command=[command[0],'@'+str(argsfile)]
            result=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',errors='replace',cwd=d,timeout=limit)
            if result.returncode:raise RuntimeError(result.stdout+'\n'+result.stderr)
            return result.stdout
        helper=list((ROOT/'helper/src/main/java').rglob('*.java'))+[ROOT/'src/main/java/cn/piq/gba/bridge/GbaProtocol.java']
        run([JDK/'javac.exe','-encoding','UTF-8','--release','21','-cp',JNA,'-d',h,*helper])
        jar(rt/'piq-gba-helper.jar',{str(x.relative_to(h)).replace('\\','/'):x.read_bytes() for x in h.rglob('*.class')})
        helper_sha=digest(rt/'piq-gba-helper.jar');shutil.copyfile(CORE,rt/'mgba_libretro.dll');shutil.copyfile(JNA,rt/'jna-5.14.0.jar')
        classpath=os.pathsep.join(map(str,[local_fc,q.MC,*q.dependencies()]))
        main=list((ROOT/'src/main/java').rglob('*.java'))
        run([JDK/'javac.exe','-encoding','UTF-8','--release','21','-cp',classpath,'-d',m,*main])
        run([JDK/'javac.exe','-encoding','UTF-8','--release','21','-cp',m,'-d',qa,ROOT/'tools/qa/GbaProcessProbe.java'])
        diagnostic=d/'diagnostic.gba';diagnostic.write_bytes(create())
        text=run([JDK/'java.exe','-Xmx256m','-cp',str(qa)+os.pathsep+str(m),'cn.piq.gba.bridge.GbaProcessProbe',rt,diagnostic,d/'owned-saves',helper_sha],60)
        probe=json.loads(next(line for line in reversed(text.splitlines()) if line.startswith('{"ok"')))
        if not probe.get('ok') or fence!={str(x.relative_to(ROOT)):digest(x) for x in source_paths}:raise ValueError('probe/fence')
        entries={str(x.relative_to(m)).replace('\\','/'):x.read_bytes() for x in m.rglob('*.class')}
        for file in (ROOT/'src/main/resources').rglob('*'):
            if file.is_file():entries[str(file.relative_to(ROOT/'src/main/resources')).replace('\\','/')]=file.read_bytes()
        entries['piq-gba-runtime.properties']=('helper.sha256='+helper_sha+'\n').encode()
        entries['META-INF/MANIFEST.MF']=('Manifest-Version: 1.0\nImplementation-Title: PIQ GBA server preview\nImplementation-Version: '+VERSION+'\n\n').encode()
        entries['META-INF/LICENSE']=(ROOT.parent/'piq-native-arcade/LICENSE').read_bytes()
        output.mkdir(parents=True)
        mod=output/('piq_gba-'+VERSION+'.jar')
        jar(mod,entries);shutil.copytree(rt,output/'piq-gba/runtime')
        licenses=output/'licenses-and-source';licenses.mkdir()
        vendor=ROOT/'vendor/mgba-e31759b24e7a4e3899285ff720d7b573ac328ae7'
        for name in ('source.tar.gz','LICENSE','source-verification.json'):
            shutil.copyfile(vendor/name,licenses/('mgba-'+name))
        shutil.copyfile(ROOT/'runtime/incoming-mgba-20260911-v1/acquisition.json',licenses/'mgba-official-acquisition.json')
        for name in ('JNA-LICENSE.txt','JNA-Apache-2.0.txt'):
            shutil.copyfile(ROOT.parent/'piq-native-arcade/docs/licenses'/name,licenses/name)
        shutil.copyfile(ROOT.parent/'piq-native-arcade/LICENSE',licenses/'PIQ-GPL-3.0.txt')
        jar(licenses/'piq-gba-source.zip',{str(x.relative_to(ROOT)).replace('\\','/'):x.read_bytes() for x in source_paths})
        shutil.copyfile(ROOT/'README.md',output/'README.md')
        report={'schema':'piq-gba-preview-build-1','ok':True,'fc':{'path':str(fc),'sha256':digest(fc)},'gba':{'path':str(mod),'sha256':digest(mod)},
                'runtime':{x.name:{'bytes':x.stat().st_size,'sha256':digest(x)} for x in (output/'piq-gba/runtime').iterdir()},'probe':probe,'source_sha256':fence,
                'minecraft_started':False,'source_production_compiled':True,'scope':'New GBA add-on only; compiled against actual supplied FC and Minecraft/NeoForge API. No existing project changed.'}
        (output/'build-and-process-qa.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps(report['probe']))
if __name__=='__main__':main()
