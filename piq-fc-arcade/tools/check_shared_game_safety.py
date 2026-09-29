"""Scoped game store/pipeline/budget JUnit and actual Connection completion tests; no Gradle or game."""
from __future__ import annotations
import argparse,hashlib,json,os,shutil,sys,tempfile
from pathlib import Path
import verify_retro_alpha19 as q

ROOT=Path(__file__).resolve().parents[1]
STEMS=['CabinetGameManifest','CabinetGameStore','CabinetGameBudget','CabinetGameTransfer']
TESTS=['CabinetGameBudgetTest','CabinetGameTransferTest','CabinetGameStoreSafetyTest']
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest().upper()
def main():
    sys.stdout.reconfigure(encoding='utf-8');p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--fc',type=Path,required=True);p.add_argument('--source',action='store_true');p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    if a.report.exists():raise ValueError('Refuse to overwrite evidence')
    fc=a.fc.resolve(strict=True);before=sha(fc);src=ROOT/'src/main/java/cn/piq/fcarcade/cabinet';test=ROOT/'src/test/java/cn/piq/fcarcade/cabinet';qa=ROOT/'tools/qa'
    tests=[test/(s+'.java')for s in TESTS];sources=[src/(s+'.java')for s in STEMS+['CabinetGameNetwork','CabinetMediaSender']]
    junit=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:
        junit.extend(f for f in (q.CACHE/group).rglob('*.jar')if version in f.parts and '-sources'not in f.name and '-javadoc'not in f.name)
    with tempfile.TemporaryDirectory(prefix='piq-shared-game-')as folder:
        temp=Path(folder);classes=temp/'classes';classes.mkdir();empty=temp/'empty';empty.mkdir();staged=temp/'fc.jar';shutil.copyfile(fc,staged)
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[classes,staged,*junit,q.MC,resources,*q.dependencies()]))
        arg=temp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        compile_sources=sources if a.source else []
        q.run([q.JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',classes,*compile_sources,*tests,qa/'CabinetGameTestRunner.java',qa/'CabinetGameTransportProbe.java'],temp)
        unit=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(arg),'CabinetGameTestRunner',*['cn.piq.fcarcade.cabinet.'+s for s in TESTS]],temp))
        transport=q.parse_last_json(q.run([q.JAVA/'java.exe','-Djava.awt.headless=true','@'+str(arg),'cn.piq.fcarcade.cabinet.CabinetGameTransportProbe',*([staged]if not a.source else [])],temp))
        if sha(fc)!=before or sha(staged)!=before:raise ValueError('Input candidate changed')
    result={'ok':True,'mode':'source-against-real-api'if a.source else'final-jar-only','production_compiled':a.source,'jar':str(fc),'sha256':before,'unit':unit,'transport':transport,'sources':{str(f.relative_to(ROOT)):sha(f)for f in [*sources,*tests,qa/'CabinetGameTestRunner.java',qa/'CabinetGameTransportProbe.java']},'gradle_started':False,'installed':False,'limits':['No real server/player transfer playtest; actual JUnit filesystem and embedded Netty write-completion checks.']}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as out:json.dump(result,out,ensure_ascii=False,indent=2)
    print(json.dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
