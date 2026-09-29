"""Exact-JAR mouse cancellation / shared settings contracts; optional explicit source QA.
Never creates a Minecraft singleton, GUI window, device, native core or config file.
"""
import argparse,json,os,shutil,tempfile
from pathlib import Path
import verify_retro_alpha19 as q

ROOT=Path(__file__).resolve().parents[1]
NAMES=['KeyboardConfig','KeyboardConfigStore','KeyboardControlState','KeyboardRouting','KeyboardInput',
       'KeyboardMappingState','ControlSettingsScreen','GamepadInput','GamepadSettingsScreen']

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fc',type=Path,required=True);parser.add_argument('--gba',type=Path,required=True)
    parser.add_argument('--report',type=Path,required=True);parser.add_argument('--production-source',action='store_true')
    args=parser.parse_args();assert not args.report.exists(),'New evidence path required'
    jars={k:{'path':str(p.resolve()),'sha256':q.digest(p.read_bytes())}for k,p in [('fc',args.fc),('gba',args.gba)]}
    probe=ROOT/'tools/qa/ControlSettings34Probe.java'
    sources=([ROOT/'src/main/java/cn/piq/retro/client'/(n+'.java')for n in NAMES]+
             [ROOT.parent/'piq-gba/src/main/java/cn/piq/gba/client/GbaHandheldScreen.java'])if args.production_source else[]
    watched=[Path(__file__),probe,*sources];fence={str(p.resolve()):q.digest(p.read_bytes())for p in watched}
    with tempfile.TemporaryDirectory(prefix='piq-settings34-')as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir()
        fc=tmp/'fc.jar';gba=tmp/'gba.jar'
        for source,target,key in [(args.fc,fc,'fc'),(args.gba,gba,'gba')]:
            shutil.copyfile(source,target);assert q.digest(target.read_bytes())==jars[key]['sha256']
        preferred=[p for p in q.CACHE.glob('org.ow2.asm/*/9.8/*/*.jar')if '-sources'not in p.name and '-javadoc'not in p.name]
        cp=os.pathsep.join(map(str,[out,fc,gba,*preferred,q.MC,*q.dependencies()]))
        arg=tmp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        compile_log=q.run([q.JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources,probe],ROOT)
        run_log=q.run([q.JAVA/'java.exe','@'+str(arg),'ControlSettings34Probe',out if sources else fc,out if sources else gba],tmp)
        result=q.parse_last_json(run_log);assert result['ok']
        if not sources:assert not(out/'cn').exists(),'Production unexpectedly compiled'
    for key,p in [('fc',args.fc),('gba',args.gba)]:assert q.digest(p.read_bytes())==jars[key]['sha256']
    assert fence=={str(p.resolve()):q.digest(p.read_bytes())for p in watched},'Source changed during QA'
    report={'ok':True,'mode':'explicit-source-qa'if sources else'final-jar-only','production_compiled':bool(sources),
            'jars':jars,'source_sha256':fence,'probe':result,'logs':{'compile':compile_log,'run':run_log},
            'limits':['Canceled NeoForge mouse events and pure production state execute directly; GUI and uncanceled world interaction are bytecode contracts, not a game boot.',
                      'No options/config writes, physical input devices, render window, native core or remote Minecraft server is used.']}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as output:json.dump(report,output,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'mode':report['mode'],'assertions':result['assertions'],'report':str(args.report)}))

if __name__=='__main__':main()
