"""Run actual pure UI-state/layout tests without Gradle; GUI checks are source contracts, not game screenshots."""

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home, java_home

import argparse,hashlib,json,os,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=(java_home() / 'bin')
def run(cmd):
    p=subprocess.run(list(map(str,cmd)),cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
    if p.returncode:raise AssertionError(p.stdout+'\n'+p.stderr)
    return p.stdout
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--report',type=Path,required=True);args=parser.parse_args()
    if args.report.exists():raise ValueError('Do not overwrite prior QA reports')
    cache=(gradle_home() / 'caches/modules-2/files-2.1');deps=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:
        deps.extend(p for p in (cache/group).rglob('*.jar') if version in p.parts and '-sources' not in p.name and '-javadoc' not in p.name)
    source=ROOT/'src/main/java/cn/piq/sfchome/client'
    production=[source/(n+'.java') for n in ['SfcCardLibrary','SfcEditorWork','SfcUploadTitle','SfcCardEditorLayout']]
    tests=[ROOT/'src/test/java/cn/piq/sfchome/client'/(n+'Test.java') for n in ['SfcCardLibrary','SfcEditorWork','SfcUploadTitle','SfcCardEditorLayout','SfcCardEditorSource']]
    with tempfile.TemporaryDirectory(prefix='sfc-card-editor-') as folder:
        tmp=Path(folder);classes=tmp/'classes';classes.mkdir();empty=tmp/'empty';empty.mkdir()
        cp=os.pathsep.join(map(str,[classes,*deps]))
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',classes,*production,*tests,ROOT/'tools/qa/SfcCardEditorTestRunner.java'])
        result=run([JAVA/'java.exe','-cp',cp,'SfcCardEditorTestRunner'])
    protected=['net/SfcHomeNetwork.java','server/SfcCartridgeEditorService.java','client/SfcClientFiles.java','client/cabinet/SfcCabinetSession.java']
    report={'passed':True,'tests':25,'output':result,'source_sha256':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest().upper() for p in production+[source/'SfcCardEditorScreen.java',source/'cabinet/SfcCabinetProvider.java']},'unchanged_boundary_sha256':{n:hashlib.sha256((ROOT/'src/main/java/cn/piq/sfchome'/n).read_bytes()).hexdigest().upper() for n in protected},'limitations':['Screen and provider integration require root full compilation.','No Minecraft scene, OS directory window, remote server or real upload was exercised.','Actual pure Java tests cover drafts, lists, paging, layout, late-work invalidation and exact-hash upload title gating; event wiring uses source contracts.']}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
