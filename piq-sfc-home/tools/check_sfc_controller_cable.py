"""Run real pure SFC range/revocation policies and source wiring guards without Gradle/MC."""

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home

import argparse,hashlib,json,os,tempfile
from pathlib import Path
from check_sfc_gamepad_network import ROOT,JAVA,execute

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--report',type=Path,required=True);args=parser.parse_args()
    if args.report.exists():raise ValueError('Refusing to overwrite an old report')
    cache=(gradle_home() / 'caches/modules-2/files-2.1');deps=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:
        deps.extend(p for p in(cache/group).rglob('*.jar')if version in p.parts and '-sources'not in p.name and '-javadoc'not in p.name)
    names=('SfcControllerAuthority','SfcControllerInventory','SfcInputHealth','SfcInputTimeline','SfcJoinGate')
    production=[ROOT/'src/main/java/cn/piq/sfchome/server'/(n+'.java')for n in names]
    test_names=('SfcControllerAuthorityTest','SfcControllerInventoryTest','SfcControllerCableTest','SfcApplianceSeparationSourceTest')
    tests=[ROOT/'src/test/java/cn/piq/sfchome/server'/(n+'.java')for n in test_names]
    with tempfile.TemporaryDirectory(prefix='sfc-cable29-')as folder:
        classes=Path(folder)/'classes';classes.mkdir();empty=Path(folder)/'empty';empty.mkdir();cp=os.pathsep.join(map(str,[classes,*deps]))
        execute([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',classes,*production,*tests,ROOT/'tools/qa/SfcControllerCableTestRunner.java'])
        output=execute([JAVA/'java.exe','-cp',cp,'SfcControllerCableTestRunner'])
    summary=json.loads(output.strip().splitlines()[-1]);covered=[*production,*tests,ROOT/'src/main/java/cn/piq/sfchome/server/SfcHomeServer.java',ROOT/'src/main/java/cn/piq/sfchome/client/SfcHomeClient.java',ROOT/'src/main/java/cn/piq/sfchome/world/SfcHomeConsoleBlockEntity.java']
    summary.update(output=output,source_sha256={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest()for p in covered},limits=['Executes the actual pure cable range, revocation and queue policies; wiring source checks supplement these tests.','No Minecraft server/player inventory, block-entity packet or real multiplayer client was started; production integration is compiled separately.'])
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as stream:json.dump(summary,stream,ensure_ascii=False,indent=2)
    print(json.dumps(summary,ensure_ascii=False))
if __name__=='__main__':main()
