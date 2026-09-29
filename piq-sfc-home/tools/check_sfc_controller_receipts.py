"""Actual registered SFC ItemStack/Slot/NBT probe. Compiles probe only, no production or game."""
import argparse,json,os,shutil,tempfile,sys
from pathlib import Path
from run_sfc_playback_multiplayer_probe import ROOT,JAVA,MC,run,sha

def main():
    sys.stdout.reconfigure(encoding='utf-8');parser=argparse.ArgumentParser();parser.add_argument('--fc',type=Path,required=True);parser.add_argument('--sfc',type=Path,required=True);parser.add_argument('--report',type=Path,required=True);args=parser.parse_args()
    if args.report.exists():raise ValueError('Reports are immutable')
    jars={key:{'path':str(path.resolve()),'sha256':sha(path)}for key,path in [('fc',args.fc),('sfc',args.sfc)]}
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1');deps=[]
    manifest=json.loads((MC.parent.parent/'artifacts/minecraft_1.21.1_version_manifest.json').read_text())
    for lib in manifest['libraries']:
        parts=lib['name'].split(':')
        if len(parts)==3:deps.extend((cache/parts[0]/parts[1]/parts[2]).rglob(parts[1]+'-'+parts[2]+'.jar'))
    deps.extend(p for p in cache.rglob('*.jar')if not any(x in p.name for x in ('-sources','-javadoc','-userdev')))
    with tempfile.TemporaryDirectory(prefix='sfc-receipts29-')as folder:
        tmp=Path(folder);classes=tmp/'classes';classes.mkdir();empty=tmp/'empty';empty.mkdir()
        for key,data in jars.items():shutil.copyfile(data['path'],tmp/(key+'.jar'))
        resources=MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[classes,tmp/'sfc.jar',tmp/'fc.jar',MC,resources,*deps]));arg=tmp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        run([JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',classes,ROOT/'tools/qa/SfcControllerReceiptProbe.java'],tmp)
        output=run([JAVA/'java.exe','-Djava.awt.headless=true','@'+str(arg),'cn.piq.sfchome.world.SfcControllerReceiptProbe',tmp/'sfc.jar'],tmp)
        result=json.loads(next(line for line in reversed(output.splitlines())if line.startswith('{')))
        for key,data in jars.items():
            if sha(Path(data['path']))!=data['sha256']or sha(tmp/(key+'.jar'))!=data['sha256']:raise AssertionError('Input JAR changed')
    result.update(mode='final-jar-only',production_compiled=False,jars=jars,output=output,production_sources_compiled=False,scope='Real NeoForge registration and real production ItemStacks/Slot alias/cursor-copy revocation/BE updateTag codecs, not a Minecraft player/world/server lifecycle test.')
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as stream:json.dump(result,stream,ensure_ascii=False,indent=2)
    print(json.dumps(result,ensure_ascii=False))
if __name__=='__main__':main()
