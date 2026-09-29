"""FC33 final archives only; compile QA, never replace or compile production classes."""
import argparse,json,os,shutil,tempfile,zipfile
from pathlib import Path
import verify_retro_alpha19 as q
ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser()
    for n in ['fc','native','sfc','gba','report']:p.add_argument('--'+n,type=Path,required=True)
    a=p.parse_args();assert not a.report.exists(),'New report required'
    originals={k:getattr(a,k).resolve(strict=True) for k in ['fc','native','sfc','gba']}
    fence={k:q.digest(v.read_bytes()) for k,v in originals.items()}
    tests=[]
    for folder,pattern in [('cabinet','CabinetSync*Test.java'),('cabinet','CabinetGame*Test.java'),('client/cabinet','CabinetSync*Test.java')]:
        tests+=list((ROOT/'src/test/java/cn/piq/fcarcade'/folder).glob(pattern))
    tests=sorted(set(tests))
    probes=[ROOT/'tools/qa'/n for n in ['Sync32TestRunner.java','GbaServer31CommonProbe.java','NativeSync33CommonProbe.java']]
    template=ROOT/'tools/qa/Sync32WireProbe.java'
    sources=tests+probes+[template];source_fence={str(s.relative_to(ROOT)):q.digest(s.read_bytes()) for s in sources}
    with tempfile.TemporaryDirectory(prefix='piq-sync33-final-')as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir();jars={}
        for k,path in originals.items():jars[k]=tmp/(k+'.jar');shutil.copyfile(path,jars[k])
        # A version-specific QA copy preserves the historical protocol-1 witness and its source.
        wire_source=tmp/'Sync33WireProbe.java'
        wire_source.write_text(template.read_text(encoding='utf-8').replace('Sync32WireProbe','Sync33WireProbe').replace('cabinet-sync-1','cabinet-sync-2'),encoding='utf-8')
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        junit=[]
        for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:
            junit += [f for f in (q.CACHE/group).rglob('*.jar') if version in f.parts and '-sources'not in f.name and '-javadoc'not in f.name]
        preferred=[f for f in q.CACHE.glob('org.ow2.asm/*/9.8/*/*.jar') if '-sources'not in f.name and '-javadoc'not in f.name]
        cp=os.pathsep.join(map(str,[out,*jars.values(),*preferred,*junit,q.MC,resources,*q.dependencies()]))
        arg=tmp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        compile_log=q.run([q.JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*tests,*probes,wire_source],ROOT)
        command=[q.JAVA/'java.exe','@'+str(arg),'-Djava.awt.headless=true','--add-opens=java.base/java.lang.invoke=ALL-UNNAMED']
        wire=q.parse_last_json(q.run([*command,'cn.piq.fcarcade.cabinet.Sync33WireProbe',jars['fc']],tmp))
        behavior=q.parse_last_json(q.run([*command,'Sync32TestRunner',*[str(t.relative_to(ROOT/'src/test/java')).replace('\\','/').removesuffix('.java').replace('/','.') for t in tests]],tmp))
        common=q.parse_last_json(q.run([*command,'GbaServer31CommonProbe',*[jars[k] for k in ['fc','gba','sfc','native']]],tmp))
        native=q.parse_last_json(q.run([*command,'NativeSync33CommonProbe',jars['fc'],jars['native']],tmp))
        production=set()
        for file in jars.values():
            with zipfile.ZipFile(file)as z:production.update(n for n in z.namelist() if n.endswith('.class'))
        assert not any(f.relative_to(out).as_posix()in production for f in out.rglob('*.class')),'Production class compiled by QA'
        assert all(q.digest(v.read_bytes())==fence[k]==q.digest(jars[k].read_bytes()) for k,v in originals.items())
        assert all(q.digest((ROOT/n).read_bytes())==sha for n,sha in source_fence.items())
        result={'schema':'piq-sync33-final-1','ok':True,'mode':'final-jar-only','production_compiled':False,
                'jars':{k:{'path':str(v),'sha256':fence[k]}for k,v in originals.items()},'tests':behavior,'wire':wire,'common':common,'native_common':native,
                'sources':source_fence,'compile_log':compile_log,'live_network_tested':False,'installed':False}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(result,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(a.report),'tests':behavior,'wire':wire,'native_common':native},ensure_ascii=False))
if __name__=='__main__':main()
