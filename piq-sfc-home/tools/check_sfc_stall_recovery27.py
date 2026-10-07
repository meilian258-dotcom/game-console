"""Compile the two changed pacing/worker sources and run an original-ROM WASM stall regression."""

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home

import argparse,json,os,shutil,tempfile,time
from pathlib import Path
import run_sfc_playback_multiplayer_probe as q

ROOT=Path(__file__).resolve().parents[1]
def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ('fc','sfc','report'):p.add_argument('--'+key,type=Path,required=True)
    p.add_argument('--final-jar',action='store_true')
    a=p.parse_args()
    if a.report.exists():raise ValueError('Use a new evidence path')
    jars={key:getattr(a,key).resolve(strict=True) for key in ('fc','sfc')};before={key:q.sha(path) for key,path in jars.items()}
    cache=(gradle_home() / 'caches/modules-2/files-2.1')
    manifest=json.loads((q.MC.parent.parent/'artifacts/minecraft_1.21.1_version_manifest.json').read_text())
    deps=[]
    for library in manifest['libraries']:
        parts=library['name'].split(':')
        if len(parts)==3:deps.extend((cache/parts[0]/parts[1]/parts[2]).rglob(parts[1]+'-'+parts[2]+'.jar'))
    deps += [f for f in cache.rglob('*.jar') if not any(s in f.name for s in ('-sources','-javadoc','-userdev'))]
    probes=[ROOT/'tools/qa/SfcStallRecovery27Probe.java',ROOT.parent/'piq-fc-arcade/tools/qa/SfcTwoPortInputProbe.java',ROOT.parent/'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/core/SfcLegalTestRom.java']
    production=[] if a.final_jar else [ROOT/'src/main/java/cn/piq/sfchome/client/SfcPlayback.java',ROOT/'src/main/java/cn/piq/sfchome/client/SfcPlaybackPacing.java']
    sources={str(f.relative_to(ROOT.parent)):q.sha(f) for f in probes+production+[Path(__file__).resolve(),Path(q.__file__).resolve()]}
    started=time.monotonic()
    with tempfile.TemporaryDirectory(prefix='sfc27-stall-') as folder:
        tmp=Path(folder);classes=tmp/'classes';classes.mkdir();empty=tmp/'empty';empty.mkdir();copies={}
        for key,path in jars.items():copies[key]=tmp/(key+'.jar');shutil.copyfile(path,copies[key]);assert q.sha(copies[key])==before[key]
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[classes,q.MC,resources,copies['sfc'],copies['fc'],*deps]))
        arg=tmp/'classpath.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        compilation=q.run([q.JAVA/'javac.exe','@'+str(arg),'-proc:none','-encoding','UTF-8','-sourcepath',empty,'-d',classes,*production,*probes],tmp)
        actual=q.run([q.JAVA/'java.exe','-Xmx2G','-Djava.awt.headless=true','-Dfile.encoding=UTF-8','@'+str(arg),'cn.piq.sfchome.client.SfcStallRecovery27Probe',copies['sfc'] if a.final_jar else classes,copies['sfc']],tmp)
        marker=[line.removeprefix('PIQ_STALL_QA ') for line in actual.splitlines() if line.startswith('PIQ_STALL_QA ')]
        if len(marker)!=1:raise AssertionError('Missing unique worker result')
        result=json.loads(marker[0]);assert result['ok']
        assert all(q.sha(path)==before[key] and q.sha(copies[key])==before[key] for key,path in jars.items())
    assert all(q.sha(ROOT.parent/name)==digest for name,digest in sources.items())
    report={'ok':True,'mode':'final-jar-only' if a.final_jar else 'changed-production-source','jars':{key:{'path':str(path),'sha256':before[key]} for key,path in jars.items()},'source_sha256':sources,'actual':result,'compile_log':compilation,'run_log':actual,'elapsed_seconds':round(time.monotonic()-started,3),'limitations':['No Minecraft instance, GPU/shader pipeline, real sound device, server socket or third-party permission plugin was launched.','Uses original diagnostic ROM; delivery stalls are controlled, not a replay of the user shader reload.','Repair and hosted mode require their existing separate regression probes.']}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(a.report),'actual':result,'elapsed_seconds':report['elapsed_seconds']},ensure_ascii=False))
if __name__=='__main__':main()
