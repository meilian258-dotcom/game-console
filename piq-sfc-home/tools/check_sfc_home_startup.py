"""Run pure startup policy/timing regression and source contracts; no Gradle, game or commercial ROM."""
import argparse,hashlib,json,os,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
def run(cmd):
    p=subprocess.run(list(map(str,cmd)),cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
    if p.returncode:raise AssertionError(p.stdout+'\n'+p.stderr)
    return p.stdout
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--report',required=True,type=Path);args=parser.parse_args()
    if args.report.exists():raise ValueError('Never overwrite earlier evidence')
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1');deps=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:deps.extend(p for p in (cache/group).rglob('*.jar') if version in p.parts and '-sources' not in p.name and '-javadoc' not in p.name)
    names=['server/SfcHomeStartPolicy','server/SfcInputTimeline','client/SfcStartupProgress'];tests=['server/SfcHomeStartPolicyTest','client/SfcStartupProgressTest','server/SfcHomeStartupSourceContractTest']
    production=[ROOT/'src/main/java/cn/piq/sfchome'/(n+'.java') for n in names]
    with tempfile.TemporaryDirectory(prefix='sfc-home-startup-') as folder:
        classes=Path(folder)/'classes';classes.mkdir();empty=Path(folder)/'empty';empty.mkdir();cp=os.pathsep.join(map(str,[classes,*deps]))
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',classes,*production,*[ROOT/'src/test/java/cn/piq/sfchome'/(n+'.java') for n in tests],ROOT/'tools/qa/SfcHomeStartupTestRunner.java'])
        output=run([JAVA/'java.exe','-cp',cp,'SfcHomeStartupTestRunner'])
    touched=production+[ROOT/'src/main/java/cn/piq/sfchome'/(n+'.java') for n in ['server/SfcHomeServer','client/SfcHomeClient','client/SfcPlayback']]
    report={'passed':True,'tests':21,'output':output,'production_sha256':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest().upper() for p in touched},'limits':['No full Minecraft interaction or actual two-client multiplayer test.','Pure-Java executable policy/gate/timing/input-clear tests plus source-level lifecycle wiring contracts.','Separate original-ROM core timing probe measures actual cold-core cost; not an end-to-end gameplay timing.']}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
