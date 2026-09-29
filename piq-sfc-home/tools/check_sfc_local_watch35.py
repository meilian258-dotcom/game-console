"""Execute the production SFC local-observer worker/codecs with real core9 and an original diagnostic ROM."""
import argparse,json,os,shutil,tempfile,time
from pathlib import Path
import run_sfc_playback_multiplayer_probe as q

ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ('fc','sfc','core','report'):p.add_argument('--'+key,type=Path,required=True)
    a=p.parse_args()
    if a.report.exists():raise ValueError('Use a new evidence path')
    jars={key:getattr(a,key).resolve(strict=True) for key in ('fc','sfc','core')};before={key:q.sha(path) for key,path in jars.items()}
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1')
    manifest=json.loads((q.MC.parent.parent/'artifacts/minecraft_1.21.1_version_manifest.json').read_text())
    deps=[]
    for library in manifest['libraries']:
        parts=library['name'].split(':')
        if len(parts)==3:deps.extend((cache/parts[0]/parts[1]/parts[2]).rglob(parts[1]+'-'+parts[2]+'.jar'))
    deps += [f for f in cache.rglob('*.jar') if not any(s in f.name for s in ('-sources','-javadoc','-userdev'))]
    probes=[ROOT/'tools/qa/SfcLocalWatchWorkerProbe.java',ROOT.parent/'piq-fc-arcade/tools/qa/SfcTwoPortInputProbe.java',ROOT.parent/'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/core/SfcLegalTestRom.java']
    sources={str(f.relative_to(ROOT.parent)):q.sha(f) for f in probes+[Path(__file__).resolve(),Path(q.__file__).resolve()]}
    started=time.monotonic()
    with tempfile.TemporaryDirectory(prefix='sfc35-watch-') as folder:
        tmp=Path(folder);classes=tmp/'classes';classes.mkdir();empty=tmp/'empty';empty.mkdir();copies={}
        for key,path in jars.items():copies[key]=tmp/(key+'.jar');shutil.copyfile(path,copies[key]);assert q.sha(copies[key])==before[key]
        artifacts=ROOT/'build/moddev/artifacts'
        minecraft=tmp/'minecraft.jar';resources=tmp/'resources.jar'
        shutil.copyfile(artifacts/'neoforge-21.1.236-merged.jar',minecraft)
        shutil.copyfile(artifacts/'neoforge-21.1.236-client-extra-aka-minecraft-resources.jar',resources)
        cp=os.pathsep.join(map(str,[classes,minecraft,resources,copies['sfc'],copies['core'],copies['fc'],*deps]))
        arg=tmp/'classpath.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        compilation=q.run([q.JAVA/'javac.exe','@'+str(arg),'-proc:none','-encoding','UTF-8','-sourcepath',empty,'-d',classes,*probes],tmp)
        core_origin=copies['sfc'] if jars['sfc']==jars['core'] else copies['core']
        actual=q.run([q.JAVA/'java.exe','-Xmx2G','-Djava.awt.headless=true','-Dfile.encoding=UTF-8','@'+str(arg),'cn.piq.sfchome.client.SfcLocalWatchWorkerProbe',copies['sfc'],core_origin],tmp)
        marker=[line.removeprefix('PIQ_WATCH_QA ') for line in actual.splitlines() if line.startswith('PIQ_WATCH_QA ')]
        if len(marker)!=1:raise AssertionError('Missing unique worker result')
        result=json.loads(marker[0]);assert result['ok']
        assert all(q.sha(path)==before[key] and q.sha(copies[key])==before[key] for key,path in jars.items())
    assert all(q.sha(ROOT.parent/name)==digest for name,digest in sources.items())
    report={'ok':True,'mode':'actual-jar-codecs-and-core9','jars':{key:{'path':str(path),'sha256':before[key]} for key,path in jars.items()},'source_sha256':sources,'actual':result,'compile_log':compilation,'run_log':actual,'elapsed_seconds':round(time.monotonic()-started,3),'limitations':['No Minecraft instance, rendering/GPU/shaders, sound device or real network socket launched.','Validates worker/codecs/checkpoint pin and input replay; server player lifecycle and GUI/range need in-game acceptance.','Original generated diagnostic ROM only; no commercial ROM or saved game modified.']}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(a.report),'actual':result,'elapsed_seconds':report['elapsed_seconds']},ensure_ascii=False))

if __name__=='__main__':main()
