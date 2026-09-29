"""Actual metadata disk roundtrip + save-mode wire codec. --source for development; default input-JAR-only."""
import argparse,hashlib,json,os,shutil,subprocess,tempfile,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
MC=Path('C:/Users/13498/.gradle/caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_e75ff7a3db3c8d7760682f321018318019b04f3c_output.jar')
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest().upper()
def run(command):
    p=subprocess.run(list(map(str,command)),cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
    if p.returncode:raise AssertionError(p.stdout+'\n'+p.stderr)
    return p.stdout
def main():
    sys.stdout.reconfigure(encoding='utf-8');parser=argparse.ArgumentParser();parser.add_argument('--fc',type=Path,required=True);parser.add_argument('--report',type=Path,required=True);parser.add_argument('--source',action='store_true');a=parser.parse_args()
    if a.report.exists():raise ValueError('Old reports stay frozen')
    before=sha(a.fc);cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1');deps=[];junit=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:junit.extend(p for p in(cache/group).rglob('*.jar')if version in p.parts and not any(x in p.name for x in ('-sources','-javadoc')))
    manifest=json.loads((MC.parent.parent/'artifacts/minecraft_1.21.1_version_manifest.json').read_text())
    for lib in manifest['libraries']:
        parts=lib['name'].split(':')
        if len(parts)==3:deps.extend((cache/parts[0]/parts[1]/parts[2]).rglob(parts[1]+'-'+parts[2]+'.jar'))
    deps.extend(p for p in cache.rglob('*.jar')if not any(x in p.name for x in ('-sources','-javadoc','-userdev'))and p not in junit and not any(x in p.parts for x in ('org.junit.platform','org.junit.jupiter')))
    source_names=['home/CartridgeNetwork','home/CartridgeComputerBinding','server/ServerRomLibrary','client/ui/DeviceLayout','client/ui/CartridgeWorkbenchLayout']
    sources=[ROOT/'src/main/java/cn/piq/fcarcade'/(n+'.java')for n in source_names]
    tests=[ROOT/'src/test/java/cn/piq/fcarcade'/(n+'.java')for n in ['home/CartridgeSaveModeSettingTest','server/ServerRomSaveModePolicyTest','client/ui/CartridgeWorkbenchLayoutTest']]
    with tempfile.TemporaryDirectory(prefix='fc-save29-')as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir();jar=tmp/'fc.jar';shutil.copyfile(a.fc,jar)
        resources=MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[out,jar,MC,resources,*junit,*deps]));arg=tmp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        run([JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*(sources if a.source else []),*tests,ROOT/'tools/qa/CartridgeSaveModeTestRunner.java',ROOT/'tools/qa/CartridgeSaveModeProbe.java'])
        junit_output=run([JAVA/'java.exe','@'+str(arg),'CartridgeSaveModeTestRunner'])
        codec_output=run([JAVA/'java.exe','@'+str(arg),'cn.piq.fcarcade.home.CartridgeSaveModeProbe',*([]if a.source else[jar])])
        if sha(a.fc)!=before or sha(jar)!=before:raise AssertionError('Input changed')
    covered=sources+tests+[ROOT/'src/main/java/cn/piq/fcarcade/client/ClientCartridgeEditor.java',ROOT/'src/main/java/cn/piq/fcarcade/server/ServerCartridgeService.java']
    result={'ok':True,'mode':'production-source' if a.source else 'final-jar-only','production_compiled':a.source,'jars':{'fc':{'path':str(a.fc.resolve()),'sha256':before}},'production_sources_compiled':a.source,'junit':json.loads(junit_output.strip().splitlines()[-1]),'codec':json.loads(codec_output.strip().splitlines()[-1]),'output':junit_output+codec_output,'source_sha256':{str(p.relative_to(ROOT)):sha(p)for p in covered},'limits':['Real metadata file writes only in JUnit temp directory. No user ROM, saves or Minecraft world opened.','UI/service integration verified by source contracts; not a live UI/player/permission plugin test.']}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as stream:json.dump(result,stream,ensure_ascii=False,indent=2)
    print(json.dumps(result,ensure_ascii=False))
if __name__=='__main__':main()
