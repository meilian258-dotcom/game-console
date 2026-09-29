"""Only compile QA/test code; all production implementations come from frozen JARs."""
import argparse,json,os,shutil,tempfile,zipfile
from pathlib import Path
import verify_retro_alpha19 as q
ROOT=Path(__file__).resolve().parents[1]
TESTS={
 'cabinet':['CabinetSyncTimelineTest','CabinetSyncStateTest','CabinetSyncGateTest'],
 'client/cabinet':['CabinetSyncWorkerTest','CabinetMediaPolicyTest','CabinetMediaStreamTest']}
def main():
    p=argparse.ArgumentParser();p.add_argument('--fc',type=Path,required=True);p.add_argument('--gba',type=Path,required=True);p.add_argument('--sfc',type=Path,required=True);p.add_argument('--native',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    if a.report.exists():raise FileExistsError(a.report)
    originals={k:getattr(a,k).resolve(strict=True) for k in ('fc','gba','sfc','native')};fence={k:q.digest(v.read_bytes()) for k,v in originals.items()}
    tests=[ROOT/'src/test/java/cn/piq/fcarcade'/folder/(name+'.java') for folder,names in TESTS.items() for name in names]
    tests+=list((ROOT/'src/test/java/cn/piq/fcarcade/cabinet').glob('CabinetGame*Test.java'))
    probes=[ROOT/'tools/qa'/(n+'.java') for n in ('Sync32WireProbe','Sync32TestRunner','GbaServer31CommonProbe')]
    sources=tests+probes;source_fence={str(s.relative_to(ROOT)):q.digest(s.read_bytes()) for s in sources}
    with tempfile.TemporaryDirectory(prefix='piq-sync32-final-') as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir();jars={}
        for kind,path in originals.items():jars[kind]=tmp/(kind+'.jar');shutil.copyfile(path,jars[kind])
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        junit_deps=[]
        for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:
            junit_deps.extend(f for f in (q.CACHE/group).rglob('*.jar') if version in f.parts and '-sources' not in f.name and '-javadoc' not in f.name)
        cp=os.pathsep.join(map(str,[out,*jars.values(),*junit_deps,q.MC,resources,*q.dependencies()]));arg=tmp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        compile_log=q.run([q.JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources],ROOT)
        command=[q.JAVA/'java.exe','@'+str(arg),'-Djava.awt.headless=true','--add-opens=java.base/java.lang.invoke=ALL-UNNAMED']
        wire=q.parse_last_json(q.run([*command,'cn.piq.fcarcade.cabinet.Sync32WireProbe',jars['fc']],tmp))
        junit=q.parse_last_json(q.run([*command,'Sync32TestRunner',*[str(t.relative_to(ROOT/'src/test/java')).replace('\\','/').removesuffix('.java').replace('/','.') for t in tests]],tmp))
        common=q.parse_last_json(q.run([*command,'GbaServer31CommonProbe',*[jars[k] for k in ('fc','gba','sfc','native')]],tmp))
        production=set()
        for path in jars.values():
            with zipfile.ZipFile(path) as z:production.update(n for n in z.namelist() if n.endswith('.class'))
        assert not any(f.relative_to(out).as_posix() in production for f in out.rglob('*.class')),'Production recompiled by probe'
        assert all(q.digest(path.read_bytes())==fence[k]==q.digest(jars[k].read_bytes()) for k,path in originals.items())
        assert all(q.digest((ROOT/name).read_bytes())==sha for name,sha in source_fence.items())
        result={'ok':True,'schema':'piq-sync32-final-1','mode':'final-jar-only','production_compiled':False,'jars':{k:{'path':str(v),'sha256':fence[k]} for k,v in originals.items()},'wire':wire,'tests':junit,'common':common,'sources':source_fence,'compile_log':compile_log,'live_network_tested':False,'installed':False}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8') as f:json.dump(result,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(a.report),'tests':junit,'wire':wire},ensure_ascii=False))
if __name__=='__main__':main()
