"""Final-JAR-only button geometry, TV state/color bars and physical routing regression."""
import argparse,json,os,shutil,tempfile
from pathlib import Path
import verify_retro_alpha19 as q
from freeze_fc_core_alpha19 import require
ROOT=Path(__file__).resolve().parents[2]
FC=ROOT/'piq-fc-arcade';SFC=ROOT/'piq-sfc-home'

def main():
    p=argparse.ArgumentParser()
    for n in ('fc','sfc','report'):p.add_argument('--'+n,type=Path,required=True)
    a=p.parse_args();require(not a.report.exists(),'Do not overwrite evidence')
    jars={'fc':a.fc.resolve(strict=True),'sfc':a.sfc.resolve(strict=True)}
    original={k:q.archive(v)for k,v in jars.items()}
    test_paths=[FC/'src/test/java/cn/piq/fcarcade/home/ApplianceControlsTest.java',FC/'src/test/java/cn/piq/fcarcade/home/TelevisionStateTest.java',SFC/'src/test/java/cn/piq/sfchome/layout/SfcApplianceControlsTest.java']
    tests=['cn.piq.fcarcade.home.ApplianceControlsTest','cn.piq.fcarcade.home.TelevisionStateTest','cn.piq.sfchome.layout.SfcApplianceControlsTest']
    families={'fc':{'cn/piq/fcarcade/home/'+n for n in ('ApplianceRay','ApplianceControls','TelevisionState','HomeApplianceControl')}|{'cn/piq/fcarcade/client/NoSignalPattern'},'sfc':{'cn/piq/sfchome/layout/'+n for n in ('SfcApplianceControls','SfcConsoleScale')}}
    with tempfile.TemporaryDirectory(prefix='piq-appliance28-final-')as folder:
        tmp=Path(folder);out=tmp/'tests';out.mkdir();empty=tmp/'empty';empty.mkdir();copies={}
        for kind,jar in jars.items():copies[kind]=tmp/(kind+'.jar');shutil.copyfile(jar,copies[kind])
        deps=q.dependencies();deps=[d for d in deps if not('org.junit.platform'in d.parts and '1.13.4'not in d.parts)and not('org.junit.jupiter'in d.parts and '5.13.4'not in d.parts)]
        cp=os.pathsep.join(map(str,[out,*copies.values(),q.MC,*deps]));args=tmp/'cp.args';args.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        origins=tmp/'origins.tsv';origins.write_text('\n'.join(str(copies[kind])+'\t'+name[:-6].replace('/','.')for kind,(_,entries)in original.items()for name in entries if name.endswith('.class')and name[:-6].split('$')[0]in families[kind]),encoding='utf-8')
        probe=FC/'tools/qa/Controls26FinalProbe.java'
        q.run([q.JAVA/'javac.exe','@'+str(args),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*test_paths,probe],tmp)
        require(not any(f.relative_to(out).as_posix()in entries for f in out.rglob('*.class')for _,entries in original.values()),'QA compiled production')
        behavior=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(args),'Controls26FinalProbe',origins,*tests],tmp))
        require(behavior['tests_succeeded']==17 and behavior['tests_found']==17 and behavior['tests_skipped']==behavior['tests_aborted']==0,'Incomplete appliance tests')
        routing={}
        for stem in ('world.FamicomConsoleBlock','home.RetroTvBlock','home.HomeTvPartBlock','home.LargeLcdTvPartBlock','home.WideLcdTvPartBlock','home.SuborPartBlock'):
            raw=q.run([q.JAVA/'javap.exe','-p','-c','-classpath',copies['fc'],'cn.piq.fcarcade.'+stem],tmp)
            require('HomeApplianceService.tryItemButton:'in raw,'Missing held-item physical route '+stem);routing[stem]=q.digest(raw.encode())
        raw=q.run([q.JAVA/'javap.exe','-p','-c','-classpath',copies['sfc'],'cn.piq.sfchome.world.SfcHomeConsoleBlock'],tmp)
        require('HomeApplianceService.tryButton:'in raw and 'connectionTool:'in raw,'SFC must retain tool priority');routing['sfc.world.SfcHomeConsoleBlock']=q.digest(raw.encode())
        for kind in jars:require(q.digest(copies[kind].read_bytes())==original[kind][0]==q.digest(jars[kind].read_bytes()),'Final JAR changed')
    report={'ok':True,'mode':'final-jar-only','production_compiled':False,'minecraft_started':False,'installed':False,
        'jars':{k:{'path':str(v),'sha256':original[k][0]}for k,v in jars.items()},'behavior':behavior,'physical_routing_bytecode':routing,
        'test_sources':{str(f.relative_to(ROOT)):q.digest(f.read_bytes())for f in test_paths+[probe]},
        'limits':['Actual packaged state/geometry/color-bar code and block routing; not a running Minecraft world, protection plugin or visual acceptance test.']}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=True))
if __name__=='__main__':main()
