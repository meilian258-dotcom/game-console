"""Final-bundle-only original-ROM/native process/FML checks. Never recompiles production."""
from pathlib import Path, PurePosixPath
import argparse, hashlib, json, os, shutil, subprocess, sys, tempfile, zipfile
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT.parent/'piq-fc-arcade/tools'))
import verify_retro_alpha19 as q
from diagnostic_rom import create
from check_gba_core import JDK, SHA, digest
from build_gba_preview import VERSION
JNA_SHA='34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6'
SOURCE_SHA='396D749CCE8FE3358B29CBB1DB479B1816A151BD688EE45B1D241503CBC40243'

def require(value,message):
    if not value:raise ValueError(message)

def archive(path):
    with zipfile.ZipFile(path) as z:
        names=z.namelist();require(len(names)==len(set(names)),'Duplicate ZIP member')
        for n in names:
            p=PurePosixPath(n);require(not p.is_absolute() and '..' not in p.parts and '\\' not in n and ':' not in n,'Unsafe archive path')
        require(z.testzip() is None,'CRC failure')
        return {n:z.read(n) for n in names if not n.endswith('/')}

def main():
    p=argparse.ArgumentParser();p.add_argument('--fc',required=True,type=Path);p.add_argument('--bundle',required=True,type=Path);p.add_argument('--report',required=True,type=Path);a=p.parse_args()
    fc=a.fc.resolve(strict=True);bundle=a.bundle.resolve(strict=True);report=a.report.resolve()
    require(report.is_relative_to(ROOT) and not report.exists(),'New report inside piq-gba required')
    mod=bundle/('piq_gba-'+VERSION+'.jar');rt=bundle/'piq-gba/runtime';helper=rt/'piq-gba-helper.jar';jna=rt/'jna-5.14.0.jar';dll=rt/'mgba_libretro.dll'
    require(set(x.name for x in rt.iterdir())=={'piq-gba-helper.jar','jna-5.14.0.jar','mgba_libretro.dll'},'Exact three runtime files')
    require(digest(dll)==SHA and digest(jna)==JNA_SHA,'Pinned official runtime')
    entries=archive(mod);hentries=archive(helper);archive(jna);helper_sha=digest(helper)
    require(set(entries)=={'META-INF/LICENSE','META-INF/MANIFEST.MF','META-INF/neoforge.mods.toml','piq-gba-runtime.properties',*('cn/piq/gba/'+n for n in ('GbaMod.class','bridge/GbaProcessSession$Frame.class','bridge/GbaProcessSession.class','bridge/GbaProtocol.class','bridge/GbaSaveStore.class','bridge/GbaSaveScope.class','client/GbaCabinetBackend$Launch.class','client/GbaCabinetBackend$1.class','client/GbaCabinetBackend$Setup.class','client/GbaCabinetBackend.class'))},'Exact addon class and metadata inventory')
    require(entries['piq-gba-runtime.properties'].decode()=='helper.sha256='+helper_sha+'\n','Exact helper lock in final addon')
    require(all(n.startswith(('cn/piq/gba/','META-INF/')) or n=='piq-gba-runtime.properties' for n in entries),'Only own addon namespace')
    require(not any(n.endswith(('.gba','.sav','.dll')) or '/GbaCore' in n or '/GbaWorker' in n or 'Probe' in n for n in entries),'No ROM, DLL, worker, or tests in addon')
    require(set(hentries)=={'cn/piq/gba/bridge/'+n for n in ('GbaCore.class','GbaCore$Retro.class','GbaCore$Environment.class','GbaCore$Video.class','GbaCore$Audio.class','GbaCore$Batch.class','GbaCore$Poll.class','GbaCore$Input.class','GbaCore$Info.class','GbaCore$Game.class','GbaCore$Geometry.class','GbaCore$Timing.class','GbaCore$Av.class','GbaCore$Frame.class','PcmResampler.class','GbaProtocol.class','GbaWorker.class','GbaWorker$Kernel.class','GbaWorker$Crt.class','GbaWorker$Binary.class')},'Exact private helper class inventory')
    source=bundle/'licenses-and-source/mgba-source.tar.gz';require(digest(source)==SOURCE_SHA,'Full corresponding official source bundled')
    source_record=json.loads((bundle/'licenses-and-source/mgba-source-verification.json').read_text(encoding='utf-8'))
    require(source_record['ok'] and source_record['source_sha256']==SOURCE_SHA and source_record['core_sha256']==SHA,'Source identity binding')
    require(source_record['embedded_commit']=='e31759b24e7a4e3899285ff720d7b573ac328ae7','Exact embedded commit')
    for name in ('mgba-LICENSE','JNA-LICENSE.txt','JNA-Apache-2.0.txt','PIQ-GPL-3.0.txt','piq-gba-source.zip'):
        require((bundle/'licenses-and-source'/name).is_file(),'Missing required source/license '+name)
    source_entries=archive(bundle/'licenses-and-source/piq-gba-source.zip')
    require('tools/build_gba_preview.py' in source_entries and 'src/main/resources/META-INF/neoforge.mods.toml' in source_entries,'Own build source and metadata included')
    paths=[fc,*[f for f in bundle.rglob('*') if f.is_file()]];fence={str(f):digest(f) for f in paths}
    with tempfile.TemporaryDirectory(prefix='piq-gba-final-') as temp:
        d=Path(temp);staged={}
        for key,path in {'fc':fc,'gba':mod,'helper':helper,'jna':jna,'core':dll}.items():
            target=d/(key+path.suffix);shutil.copyfile(path,target);require(digest(target)==digest(path),'Snapshot SHA');staged[key]=target
        runtime=d/'runtime';runtime.mkdir()
        for path in (helper,jna,dll):shutil.copyfile(path,runtime/path.name)
        rom=d/'diagnostic.gba';rom.write_bytes(create());classes=d/'probes';classes.mkdir();empty=d/'empty';empty.mkdir()
        def run(args,timeout=90):
            command=list(map(str,args));argfile=d/'args.txt'
            if sum(map(len,command))>20000:
                argfile.write_text('\n'.join('"'+s.replace('\\','/').replace('"','\\"')+'"' for s in command[1:]),encoding='utf-8');command=[command[0],'@'+str(argfile)]
            done=subprocess.run(command,cwd=d,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
            require(done.returncode==0,'Probe failed: '+done.stdout[-2500:]+'\n'+done.stderr[-4000:]);return done.stdout
        def result(text):return json.loads(next(line for line in reversed(text.splitlines()) if line.startswith('{')))
        # Compile ONLY probes. Production origins are checked inside each real native/process probe.
        core_cp=os.pathsep.join(map(str,[classes,staged['helper'],staged['jna']]))
        run([JDK/'javac.exe','-encoding','UTF-8','--release','21','-sourcepath',empty,'-cp',core_cp,'-d',classes,ROOT/'tools/qa/GbaCoreProbe.java'])
        core=result(run([JDK/'java.exe','-Xmx256m','-cp',core_cp,'cn.piq.gba.bridge.GbaCoreProbe',staged['core'],rom,d,staged['helper']],60))
        process_cp=os.pathsep.join(map(str,[classes,staged['gba']]))
        run([JDK/'javac.exe','-encoding','UTF-8','--release','21','-sourcepath',empty,'-cp',process_cp,'-d',classes,ROOT/'tools/qa/GbaProcessProbe.java'])
        process=result(run([JDK/'java.exe','-Xmx256m','-cp',process_cp,'cn.piq.gba.bridge.GbaProcessProbe',runtime,rom,d/'owned-saves',helper_sha,staged['gba']],60))
        full_cp=os.pathsep.join(map(str,[classes,q.MC,staged['fc'],staged['gba'],*q.dependencies()]))
        run([JDK/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',full_cp,'-d',classes,ROOT/'tools/qa/GbaAddonDiscoveryProbe.java'])
        fml=result(run([JDK/'java.exe','-Djava.awt.headless=true','--add-opens=java.base/java.lang.invoke=ALL-UNNAMED','-cp',full_cp,'GbaAddonDiscoveryProbe',staged['fc'],staged['gba']]))
        require(core['ok'] and core['actual_rgb565'] and core['assertions']>=98430 and core['production_origin']=='final-jar-only','Actual final core probe')
        require(process['ok'] and process['assertions']>=23 and process['production_origin']=='final-jar-only','Actual final process and recovery probe')
        require(fml['ok'] and fml['actual_fml_reader'] and fml['actual_common_registration'] and fml['production_origin']=='final-jar-only','Actual FML discovery and FC registry')
        require(fence=={str(f):digest(f) for f in paths},'Candidate changed during audit')
        out={'schema':'piq-gba-final-bundle-1','ok':True,'production_compiled':False,'compiled_only_probes':True,
             'fc':{'path':str(fc),'sha256':digest(fc)},'gba':{'path':str(mod),'sha256':digest(mod)},
             'runtime':{x.name:{'bytes':x.stat().st_size,'sha256':digest(x)} for x in (helper,jna,dll)},
             'core':core,'process':process,'fml':fml,'source':source_record,'files_sha256':fence,
             'minecraft_started':False,'user_rom_or_save_used':False,'diagnostic_rom_sha256':digest(rom),
             'scope':'Original ARM ROM and actual owned native helper; no game instance, handheld item or commercial compatibility test. Server-room authority is verified by a separate final-JAR report.'}
    report.parent.mkdir(parents=True,exist_ok=True);report.write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({'ok':True,'core_assertions':core['assertions'],'process_assertions':process['assertions'],'fml_assertions':fml['assertions'],'report':str(report)}))
if __name__=='__main__':main()
