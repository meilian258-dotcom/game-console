"""Compile changed production classes against the real MC API, execute metadata/packet/PNG/geometry probes. No Gradle/game."""

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home, java_home

import argparse,hashlib,json,os,shutil,subprocess,tempfile,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=(java_home() / 'bin')
MC=(gradle_home() / 'caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_e75ff7a3db3c8d7760682f321018318019b04f3c_output.jar')
NAMES=['data/SfcCartridgeData','net/SfcHomeNetwork','server/SfcCoverStore','server/SfcCoverService','server/SfcCartridgeEditorService','client/SfcCardEditorLayout','client/SfcCardEditorScreen','client/SfcCartridgeCovers','client/SfcCoverGeometry','client/SfcCartridgeRenderer','client/SfcHardwareRenderer','item/SfcCartridgeItem']
def invoke(command,cwd):
    p=subprocess.run(list(map(str,command)),cwd=cwd,capture_output=True,timeout=90)
    text=p.stdout.decode('utf-8','replace')+'\n'+p.stderr.decode('utf-8','replace')
    if p.returncode:raise AssertionError(text)
    return text
def main():
    sys.stdout.reconfigure(encoding='utf-8');parser=argparse.ArgumentParser();parser.add_argument('--report',type=Path,required=True);parser.add_argument('--jar',type=Path,help='Use this built SFC home JAR; compile probe only, no production source');args=parser.parse_args()
    if args.report.exists():raise ValueError('Refusing to overwrite prior report')
    cache=(gradle_home() / 'caches/modules-2/files-2.1');manifest=json.loads((gradle_home() / 'caches/neoformruntime/artifacts/minecraft_1.21.1_version_manifest.json').read_text());vanilla=[]
    for lib in manifest['libraries']:
        pieces=lib['name'].split(':')
        if len(pieces)==3:vanilla.extend((cache/pieces[0]/pieces[1]/pieces[2]).rglob(pieces[1]+'-'+pieces[2]+'.jar'))
    deps=vanilla+[p for p in cache.rglob('*.jar') if not any(x in p.name for x in ('-sources','-javadoc','-userdev'))]
    source=[ROOT/'src/main/java/cn/piq/sfchome'/(n+'.java') for n in NAMES]
    with tempfile.TemporaryDirectory(prefix='piq-sfc-metadata-') as folder:
        temp=Path(folder);classes=temp/'classes';classes.mkdir();empty=temp/'empty';empty.mkdir()
        copies=[]
        for name,path in [('fc',ROOT.parent/'piq-fc-arcade/build/libs/piq_fc_arcade-0.31.0-alpha.17.jar'),('home',args.jar.resolve() if args.jar else ROOT/'build/libs/piq_sfc_home-0.1.0-alpha.3.jar'),('core',ROOT.parent/'制作Mod/03-街机模拟/PIQ-SFC街机/piq_sfc_arcade-0.2.0-alpha.6.jar')]:
            target=temp/(name+'.jar');shutil.copyfile(path,target);copies.append(target)
        resources=MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[classes,MC,resources,*copies,*deps]));argfile=temp/'classpath.args';argfile.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        compile_output=invoke([JAVA/'javac.exe','@'+str(argfile),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',classes,*([] if args.jar else source),ROOT/'tools/qa/SfcCardMetadataProbe.java'],temp)
        output=invoke([JAVA/'java.exe','-Djava.awt.headless=true','@'+str(argfile),'cn.piq.sfchome.client.SfcCardMetadataProbe',temp],temp)
    report={'passed':True,'mode':'built-jar' if args.jar else 'production-source','jar_sha256':hashlib.sha256(args.jar.read_bytes()).hexdigest() if args.jar else None,'output':output,'compile_output':compile_output,'source_sha256':{n:hashlib.sha256(p.read_bytes()).hexdigest() for n,p in zip(NAMES,source)},'scope':'Real MC ItemStack, NBT persistence, packet codecs, production metadata, FC PNG validator + SFC cover repository, real item-model wrapper and pure label geometry. No networked Minecraft session, UI screenshot, live upload permission or in-world visual verification.'}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
