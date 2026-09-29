"""Compile the actual changed FC UI against real MC APIs; execute pure/source regression tests only."""
import argparse, hashlib, json, os, shutil, subprocess, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
MC=Path('C:/Users/13498/.gradle/caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_e75ff7a3db3c8d7760682f321018318019b04f3c_output.jar')
UI=ROOT/'src/main/java/cn/piq/fcarcade/client'
NAMES=['ui/DeviceUi','ui/DeviceLayout','ui/DeviceConfirmScreen','FcMenuLayout','FcMenuState','ClientCartridgeEditor','RomLibraryScreen','RomRenameScreen','FcRomDeleteScreen','rom/LocalRomPickerLayout','rom/LocalRomPickerScreen','cabinet/CabinetMenuLayout','cabinet/CabinetMenuScreen']
TESTS=['ui.DeviceLayoutTest','rom.LocalRomPickerLayoutTest','rom.LocalRomPickerSafetyTest','cabinet.CabinetMenuLayoutTest','cabinet.CabinetClientSafetyTest','FcMenuLayoutTest']
NAMES+=['ui/DeviceFormLayout','ArcadeSettingsScreen','LeaderboardPanelScreen','ArcadeSaveSlotsScreen','ArcadeSaveCatalogScreen','SkinLibraryScreen']
TESTS+=['ui.DeviceSecondaryUiTest']
def run(command,cwd):
    result=subprocess.run([str(v) for v in command],cwd=cwd,capture_output=True,timeout=60)
    output=(result.stdout+result.stderr).decode('utf-8','replace')
    if result.returncode:raise RuntimeError(output)
    return output
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--report',type=Path);args=parser.parse_args()
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1')
    manifest=json.loads(Path('C:/Users/13498/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_version_manifest.json').read_text())
    dependencies=[]
    for lib in manifest['libraries']:
        p=lib['name'].split(':')
        if len(p)==3:dependencies.extend((cache/p[0]/p[1]/p[2]).rglob(p[1]+'-'+p[2]+'.jar'))
    dependencies += [p for p in cache.rglob('*.jar') if not any(s in p.name for s in ('-sources','-javadoc','-userdev'))]
    sources=[UI/(n+'.java') for n in NAMES]
    tests=[ROOT/'src/test/java/cn/piq/fcarcade/client'/(n.replace('.','/')+'.java') for n in TESTS]
    with tempfile.TemporaryDirectory(prefix='piq-device-ui-') as td:
        temp=Path(td);out=temp/'classes';out.mkdir();empty=temp/'empty';empty.mkdir()
        frozen=ROOT.parent/'制作Mod/03-街机模拟/PIQ-FC街机/alpha17-immersive/piq_fc_arcade-0.31.0-alpha.17.jar'
        dependency=temp/'fc-dependency.jar';shutil.copyfile(frozen,dependency)
        cp=os.pathsep.join(map(str,[out,dependency,MC,*dependencies]))
        argfile=temp/'classpath.args';argfile.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        compiled=run([JAVA/'javac.exe','@'+str(argfile),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources,*tests,ROOT/'tools/qa/DeviceUiTestRunner.java'],ROOT)
        output=run([JAVA/'java.exe','@'+str(argfile),'DeviceUiTestRunner',*['cn.piq.fcarcade.client.'+t for t in TESTS]],ROOT)
        report={'status':'passed','boundary':'Actual current UI Java source compiled against cached real MC APIs. Pure production geometry and source safety tests only. No Gradle, Minecraft, emulator or native core run.','compile_output':compiled,'tests':json.loads(output),'source_sha256':{str(s.relative_to(ROOT)):hashlib.sha256(s.read_bytes()).hexdigest() for s in sources+tests}}
        if args.report:
            args.report.parent.mkdir(parents=True,exist_ok=True);args.report.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
        print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
