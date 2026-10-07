"""Compile selected production sources against real MC API; or --jar compiles probe ONLY. No Gradle/game."""

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home, java_home

import argparse,hashlib,json,os,shutil,subprocess,tempfile,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];JAVA=(java_home() / 'bin')
MC=(gradle_home() / 'caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_e75ff7a3db3c8d7760682f321018318019b04f3c_output.jar')
NAMES=['net/SfcHomeNetwork','net/SfcJoinNetwork','server/SfcJoinGate','server/SfcHomeServer','server/SfcHomeStartPolicy','client/SfcHomeClient','client/SfcPlayback','client/SfcJoinClient','client/SfcJoinScreen','client/SfcCardEditorScreen']
def run(args,cwd):
    p=subprocess.run(list(map(str,args)),cwd=cwd,capture_output=True,timeout=120);out=p.stdout.decode('utf-8','replace')+'\n'+p.stderr.decode('utf-8','replace')
    if p.returncode:raise AssertionError(out)
    return out
def main():
    sys.stdout.reconfigure(encoding='utf-8');parser=argparse.ArgumentParser();parser.add_argument('--report',required=True,type=Path);parser.add_argument('--jar',type=Path);parser.add_argument('--fc',type=Path);args=parser.parse_args()
    if args.report.exists():raise ValueError('Old reports must stay frozen')
    cache=(gradle_home() / 'caches/modules-2/files-2.1');manifest=json.loads((MC.parent.parent/'artifacts/minecraft_1.21.1_version_manifest.json').read_text());vanilla=[]
    for lib in manifest['libraries']:
        parts=lib['name'].split(':')
        if len(parts)==3:vanilla.extend((cache/parts[0]/parts[1]/parts[2]).rglob(parts[1]+'-'+parts[2]+'.jar'))
    deps=vanilla+[p for p in cache.rglob('*.jar')if not any(x in p.name for x in ['-sources','-javadoc','-userdev'])]
    files=[ROOT/'src/main/java/cn/piq/sfchome'/(n+'.java')for n in NAMES]+[ROOT.parent/'piq-fc-arcade/src/main/java/cn/piq/fcarcade/client/ui'/(n+'.java')for n in ['DeviceUi','DeviceLayout']]
    with tempfile.TemporaryDirectory(prefix='sfc-join-packets-')as folder:
        tmp=Path(folder);classes=tmp/'classes';classes.mkdir();empty=tmp/'empty';empty.mkdir();copies=[]
        for name,p in [('fc',args.fc or ROOT.parent/'制作Mod/03-街机模拟/PIQ-FC街机/alpha17-immersive/piq_fc_arcade-0.31.0-alpha.17.jar'),('home',args.jar or ROOT.parent/'制作Mod/03-街机模拟/PIQ-SFC家用/0.1.0-alpha.4/piq_sfc_home-0.1.0-alpha.4.jar'),('core',ROOT.parent/'制作Mod/03-街机模拟/PIQ-SFC街机/piq_sfc_arcade-0.2.0-alpha.6.jar')]:
            target=tmp/(name+'.jar');shutil.copyfile(p,target);copies.append(target)
        resources=MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[classes,MC,resources,*copies,*deps]));argfile=tmp/'classpath.args';argfile.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        compile=run([JAVA/'javac.exe','@'+str(argfile),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',classes,*([]if args.jar else files),ROOT/'tools/qa/SfcJoinPacketProbe.java'],tmp)
        output=run([JAVA/'java.exe','-Djava.awt.headless=true','@'+str(argfile),'cn.piq.sfchome.net.SfcJoinPacketProbe'],tmp)
    report={'passed':True,'mode':'built-jar'if args.jar else 'production-source','jar_sha256':hashlib.sha256(args.jar.read_bytes()).hexdigest()if args.jar else None,'output':output,'compile':compile,'scope':'Actual production new codecs, real NeoForge outer serverbound codec and maximum upload size. No Minecraft world or network socket opened.'}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
