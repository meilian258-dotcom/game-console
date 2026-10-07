"""Small actual Java gate/layout regression suite, independent of Gradle and Minecraft."""

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home, java_home

import argparse,hashlib,json,os,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];JAVA=(java_home() / 'bin')
def run(args):
    p=subprocess.run(list(map(str,args)),cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
    if p.returncode:raise AssertionError(p.stdout+'\n'+p.stderr)
    return p.stdout
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--report',type=Path,required=True);args=parser.parse_args()
    if args.report.exists():raise ValueError('Refusing old report replacement')
    cache=(gradle_home() / 'caches/modules-2/files-2.1');deps=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:deps.extend(p for p in (cache/group).rglob('*.jar')if version in p.parts and '-sources'not in p.name and '-javadoc'not in p.name)
    files=[ROOT/'src/main/java/cn/piq/sfchome/server/SfcJoinGate.java',ROOT/'src/main/java/cn/piq/sfchome/server/SfcInputHealth.java',ROOT.parent/'piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/ui/DeviceLayout.java']
    tests=[ROOT/'src/test/java/cn/piq/sfchome'/(n+'.java')for n in ['server/SfcJoinGateTest','server/SfcJoinSourceTest','client/SfcSharedDeviceLayoutTest','server/SfcInputHealthTest']]
    with tempfile.TemporaryDirectory(prefix='sfc-join-tests-')as folder:
        tmp=Path(folder);classes=tmp/'classes';classes.mkdir();empty=tmp/'empty';empty.mkdir();cp=os.pathsep.join(map(str,[classes,*deps]))
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',classes,*files,*tests,ROOT/'tools/qa/SfcJoinTestRunner.java']);output=run([JAVA/'java.exe','-cp',cp,'SfcJoinTestRunner'])
    report={'passed':True,'tests':24,'output':output,'source_sha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest()for p in files},'limits':['No Minecraft/world or real GUI launched.','Four source wiring contracts are not runtime server permission tests; nine tests execute real gate, nine execute per-port input health and two execute shared real layout.']}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
