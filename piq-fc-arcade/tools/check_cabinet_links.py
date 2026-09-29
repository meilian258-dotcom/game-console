"""Pure link-ledger and source-wiring QA only; no game/server/Gradle launch or user-world IO."""
import argparse, hashlib, json, os, subprocess, tempfile, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest().upper()
def main():
    sys.stdout.reconfigure(encoding='utf-8');parser=argparse.ArgumentParser();parser.add_argument('--report',required=True,type=Path);args=parser.parse_args()
    if args.report.exists():raise ValueError('Evidence exists; choose a new report')
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1');deps=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:
        deps.extend(p for p in (cache/group).rglob('*.jar') if version in p.parts and '-sources' not in p.name and '-javadoc' not in p.name)
    production=ROOT/'src/main/java/cn/piq/fcarcade/cabinet/CabinetLinkLedger.java'
    links=production.with_name('CabinetLinks.java')
    tests=[ROOT/'src/test/java/cn/piq/fcarcade/cabinet'/n for n in ['CabinetLinkLedgerTest.java','CabinetLinksSourceContractTest.java']]
    runner=ROOT/'tools/qa/CabinetLinkTestRunner.java'
    hashes={str(p.relative_to(ROOT)):sha(p) for p in [production,links,*tests,runner,Path(__file__).resolve()]}
    def run(command):
        result=subprocess.run(list(map(str,command)),cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
        if result.returncode:raise AssertionError(result.stdout+'\n'+result.stderr)
        return result.stdout
    with tempfile.TemporaryDirectory(prefix='piq-cabinet-links-test-') as name:
        temp=Path(name);classes=temp/'classes';classes.mkdir();empty=temp/'empty';empty.mkdir();cp=os.pathsep.join(map(str,[classes,*deps]))
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',classes,production,*tests,runner])
        output=run([JAVA/'java.exe','-Xmx512M','-Dfile.encoding=UTF-8','-cp',cp,'CabinetLinkTestRunner'])
    assert all(sha(ROOT/name)==digest for name,digest in hashes.items())
    report={'passed':True,'mode':'pure-production-ledger-and-source-contract','unit_tests':json.loads(output.splitlines()[-1]),'output':output,'source_sha256':hashes,
            'limits':['Tests exercise the exact pure production ledger, not a Minecraft server.','Protection-event ordering, SavedData integration and pending expiry wiring are source contracts; no actual player/connection was synthesized.','No user instance, save, ROM, installed mod or remote server was read or changed.']}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8') as stream:json.dump(report,stream,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
