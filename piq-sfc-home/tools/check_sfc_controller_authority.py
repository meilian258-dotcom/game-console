"""Compile actual pure server authorization/transaction/queue logic, not a simulated MC server."""
import argparse,hashlib,json,os,tempfile
from pathlib import Path
from check_sfc_gamepad_network import ROOT,JAVA,execute

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--report',type=Path,required=True);args=parser.parse_args()
    if args.report.exists():raise ValueError('Refusing to overwrite a report')
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1');deps=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:
        deps.extend(p for p in(cache/group).rglob('*.jar')if version in p.parts and '-sources'not in p.name and '-javadoc'not in p.name)
    names=('SfcControllerAuthority','SfcJoinGate','SfcInputHealth','SfcInputTimeline')
    production=[ROOT/'src/main/java/cn/piq/sfchome/server'/(n+'.java')for n in names]
    tests=[ROOT/'src/test/java/cn/piq/sfchome/server'/(n+'Test.java')for n in names]+[ROOT/'src/test/java/cn/piq/sfchome/server/SfcGamepadNetworkRegressionTest.java']
    with tempfile.TemporaryDirectory(prefix='sfc-authority-')as folder:
        classes=Path(folder)/'classes';classes.mkdir();empty=Path(folder)/'empty';empty.mkdir();cp=os.pathsep.join(map(str,[classes,*deps]))
        execute([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',classes,*production,*tests,ROOT/'tools/qa/SfcAuthorityTestRunner.java'])
        output=execute([JAVA/'java.exe','-cp',cp,'SfcAuthorityTestRunner'])
    summary=json.loads(output.strip().splitlines()[-1]);summary.update(output=output,production_sha256={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()for p in production},limits=['Actual pure authority, gate, queue, watchdog behaviors; not a live Minecraft multiplayer session.','Production SfcHomeServer integration is compiled/tested separately by root.'])
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as stream:json.dump(summary,stream,ensure_ascii=False,indent=2)
    print(json.dumps(summary,ensure_ascii=False))
if __name__=='__main__':main()
