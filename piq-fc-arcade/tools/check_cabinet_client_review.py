"""Pure-Java client cabinet cleanup/layout/input regression plus honest static GUI wiring checks."""

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home, java_home

import argparse,hashlib,json,os,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=(java_home() / 'bin')
def main():
    a=argparse.ArgumentParser();a.add_argument('--report',type=Path,required=True);args=a.parse_args()
    if args.report.exists():raise ValueError('Do not overwrite a prior report')
    cache=(gradle_home() / 'caches/modules-2/files-2.1');deps=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:
        deps.extend(p for p in (cache/group).rglob('*.jar') if version in p.parts and '-sources' not in p.name and '-javadoc' not in p.name)
    production=[ROOT/'src/main/java/cn/piq/fcarcade'/n for n in ('cabinet/CabinetFrame.java','cabinet/CabinetEmulator.java',
                'client/cabinet/CabinetCleanup.java','client/cabinet/CabinetMenuLayout.java','client/cabinet/CabinetKeys.java')]
    tests=[ROOT/'src/test/java/cn/piq/fcarcade/client/cabinet'/n for n in ('CabinetCleanupTest.java','CabinetMenuLayoutTest.java','CabinetClientSafetyTest.java','CabinetKeysTest.java')]
    def run(cmd):
        p=subprocess.run(list(map(str,cmd)),cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        if p.returncode:raise AssertionError(p.stdout+'\n'+p.stderr)
        return p.stdout
    with tempfile.TemporaryDirectory(prefix='cabinet-client-review-') as t:
        temp=Path(t);classes=temp/'classes';classes.mkdir();empty=temp/'empty';empty.mkdir()
        cp=os.pathsep.join(map(str,[classes,*deps]))
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',classes,*production,*tests,ROOT/'tools/qa/CabinetClientReviewRunner.java'])
        output=run([JAVA/'java.exe','-cp',cp,'CabinetClientReviewRunner'])
    reviewed=[*production,*[ROOT/'src/main/java/cn/piq/fcarcade/client/cabinet'/n for n in ('CabinetClientBackends.java','CabinetMenuScreen.java','CabinetSetupScreen.java','CabinetPlayScreen.java','CabinetUi.java')]]
    report={'passed':True,'tests':16,'output':output,'source_sha256':{str(p):hashlib.sha256(p.read_bytes()).hexdigest().upper() for p in reviewed},
            'limits':['Cleanup/key/layout tests execute actual pure production classes.','Minecraft executor/UI lifecycle checks are source contracts, not an in-game or native GUI test.','No old FC input bindings, audio core or render geometry was edited.']}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
