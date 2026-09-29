"""Actual MC API compilation and pure tests for SFC workbench UI only; no Gradle/game."""
import argparse,hashlib,json,os,re,sys,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT.parent/'piq-fc-arcade/tools'))
from verify_retro_alpha19 import JAVA,MC,dependencies,run,parse_last_json
from verify_device_ui_alpha18 import assert_methods_unchanged
FROZEN=ROOT.parent/'制作Mod/03-街机模拟'
FC=FROZEN/'PIQ-FC街机/alpha19-fc-core-addons/piq_fc_arcade-0.31.0-alpha.19.jar'
SFC=FROZEN/'PIQ-SFC家用/0.1.0-alpha.6/piq_sfc-0.1.0-alpha.6.jar'
def main():
    sys.stdout.reconfigure(encoding='utf-8');parser=argparse.ArgumentParser();parser.add_argument('--report',required=True,type=Path);args=parser.parse_args()
    if args.report.exists():raise ValueError('Prior reports are immutable')
    source=ROOT/'src/main/java/cn/piq/sfchome/client';tests=ROOT/'src/test/java/cn/piq/sfchome/client'
    main=[source/(n+'.java')for n in ['SfcCardEditorScreen','SfcCardLibrary','SfcWorkbenchDisplay','SfcEditorWork','SfcUploadTitle','SfcCardEditorLayout']]
    shared=ROOT.parent/'piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/ui'
    main.extend(shared/(n+'.java')for n in ['DeviceUi','DeviceLayout','CartridgeWorkbenchLayout','DeviceScreen'])
    test=[tests/(n+'Test.java')for n in ['SfcCardLibrary','SfcEditorWork','SfcUploadTitle','SfcCardEditorLayout','SfcCardEditorSource','SfcWorkbenchDisplay']]
    with tempfile.TemporaryDirectory(prefix='sfc-workbench-ui-')as folder:
        tmp=Path(folder);classes=tmp/'classes';classes.mkdir();empty=tmp/'empty';empty.mkdir();copied=[]
        for name,p in [('fc',FC),('sfc',SFC)]:
            target=tmp/(name+'.jar');raw=p.read_bytes()
            with target.open('xb')as out:out.write(raw)
            assert target.read_bytes()==raw;copied.append(target)
        cp=os.pathsep.join(map(str,[classes,MC,*copied,*dependencies()]));argfile=tmp/'classpath.args';argfile.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        run([JAVA/'javac.exe','@'+str(argfile),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',classes,*main,*test,ROOT/'tools/qa/SfcWorkbenchTestRunner.java'],ROOT)
        actual=parse_last_json(run([JAVA/'java.exe','@'+str(argfile),'SfcWorkbenchTestRunner'],ROOT))
        allowed=['SfcCardEditorScreen(', ' init()', 'rebuildRows(', 'controls(', 'scanLocal(', 'render(', 'action(', 'tab(', 'shownDirectory(', 'displayName(', 'visibleSelection(', 'fit(', 'lambda$init$', 'lambda$rebuildRows$', 'lambda$scanLocal$']
        protected=assert_methods_unchanged(copied[1],classes,JAVA/'javap.exe','cn.piq.sfchome.client.SfcCardEditorScreen',allowed)
    report={'passed':True,'pure_tests':actual,'actual_mc_api_compilation':True,'unchanged_non_ui_screen_methods':protected,
            'source_sha256':{str(p.relative_to(ROOT.parent)):hashlib.sha256(p.read_bytes()).hexdigest().upper()for p in main},
            'limits':['No Minecraft GUI rendering, live uploads, filesystem dialog, client/server or native core executed.','Displayed aliases do not change Row identity, original filename, hash, path or wire protocol.']}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as out:json.dump(report,out,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
