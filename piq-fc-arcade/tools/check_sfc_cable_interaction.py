"""Actual MC API and production-class AV regression; no Minecraft world/gameplay claim."""
import argparse
import hashlib
import json
import os
import shutil
from pathlib import Path
import subprocess
import tempfile
import sys

ROOT=Path(__file__).resolve().parents[1]
SFC=ROOT.parent/'piq-sfc-home'
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
MC=Path('C:/Users/13498/.gradle/caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_e75ff7a3db3c8d7760682f321018318019b04f3c_output.jar')
FC_OLD=ROOT.parent/'制作Mod/03-街机模拟/PIQ-FC街机/piq_fc_arcade-0.31.0-alpha.14.jar'
SFC_OLD=ROOT.parent/'制作Mod/03-街机模拟/PIQ-SFC家用/piq_sfc_home-0.1.0-alpha.1.jar'
PROBE=ROOT/'tools/qa/ActualSfcCableDispatchProbe.java'

def invoke(command,temp):
    result=subprocess.run([str(x) for x in command],cwd=temp,capture_output=True,timeout=60)
    text=result.stdout.decode('utf-8','replace')+'\n'+result.stderr.decode('utf-8','replace')
    if result.returncode: raise AssertionError(text)
    return text

def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser=argparse.ArgumentParser();parser.add_argument('--report',type=Path);args=parser.parse_args()
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1')
    manifest=json.loads(Path('C:/Users/13498/.gradle/caches/neoformruntime/artifacts/minecraft_1.21.1_version_manifest.json').read_text())
    vanilla=[]
    for lib in manifest['libraries']:
        pieces=lib['name'].split(':')
        if len(pieces)==3:
            vanilla.extend((cache/pieces[0]/pieces[1]/pieces[2]).rglob(pieces[1]+'-'+pieces[2]+'.jar'))
    dependencies=vanilla+[p for p in cache.rglob('*.jar')
                  if not any(s in p.name for s in ('-sources','-javadoc','-userdev'))]
    with tempfile.TemporaryDirectory(prefix='piq-cable-qa-') as td:
        temp=Path(td);out=temp/'classes';out.mkdir();empty=temp/'empty';empty.mkdir()
        resources=MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        fc=temp/'fc.jar';sfc=temp/'sfc.jar';shutil.copyfile(FC_OLD,fc);shutil.copyfile(SFC_OLD,sfc)
        cp=os.pathsep.join(map(str,[out,MC,resources,fc,sfc,*dependencies]))
        argfile=temp/'classpath.args';argfile.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        sources=[ROOT/'src/main/java/cn/piq/fcarcade/home/AvCableItem.java',SFC/'src/main/java/cn/piq/sfchome/world/SfcHomeConsoleBlock.java',PROBE]
        compile_text=invoke([JAVA/'javac.exe','-J-Duser.language=en','@'+str(argfile),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources],temp)
        output=invoke([JAVA/'java.exe','@'+str(argfile),'cn.piq.fcarcade.home.ActualSfcCableDispatchProbe'],temp)
        report={'status':'passed','production_source_sha256':{str(s):hashlib.sha256(s.read_bytes()).hexdigest() for s in sources[:-1]},'frozen_fc_dependency_sha256':hashlib.sha256(FC_OLD.read_bytes()).hexdigest(),'frozen_sfc_dependency_sha256':hashlib.sha256(SFC_OLD.read_bytes()).hexdigest(),'stdout':output,'compile_stdout':compile_text,'boundary':'Actual production AvCableItem, SfcHomeConsoleBlock, HomeHardware, real endpoint/structure classes and original ledgers; actual MC API. Unsafe only allocates controlled world/player/BE fixtures; no methods are replaced in production classes. Fixtures use in-memory SavedData, inventory STICK bound as cable, and unregistered test blocks. 36 FC/four-cell Subor/SFC × six TV layouts × both orders; 18 permission/loading/identity/incomplete/wrong-item negatives. No Minecraft world, real protection-plugin or gameplay claim.'}
        if args.report:
            args.report.parent.mkdir(parents=True,exist_ok=True)
            with args.report.open('x',encoding='utf-8') as stream:json.dump(report,stream,ensure_ascii=False,indent=2)
        print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__': main()
