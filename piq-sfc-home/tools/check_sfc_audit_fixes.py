"""SFC audit-fix regression: actual Playback/WASM + exact-file download gate/IO. No Minecraft game."""
import argparse,json,os,shutil,sys,tempfile,time
from pathlib import Path
import run_sfc_playback_multiplayer_probe as q
ROOT=Path(__file__).resolve().parents[2]

def main():
    sys.stdout.reconfigure(encoding='utf-8');p=argparse.ArgumentParser(description=__doc__)
    for n in ('fc','sfc','report'):p.add_argument('--'+n,type=Path,required=True)
    p.add_argument('--production-source',action='store_true');a=p.parse_args()
    if a.report.exists():raise ValueError('Use new immutable report path')
    paths={k:getattr(a,k).resolve(strict=True)for k in ('fc','sfc')};before={k:q.sha(v)for k,v in paths.items()}
    source_names=['piq-sfc-home/src/main/java/cn/piq/sfchome/client/SfcPlayback.java','piq-sfc-home/src/main/java/cn/piq/sfchome/client/SfcRepairProgress.java','piq-sfc-home/src/main/java/cn/piq/sfchome/server/SfcRepairLedger.java','piq-sfc-home/src/main/java/cn/piq/sfchome/net/SfcRepairNetwork.java','piq-sfc-arcade/src/main/java/cn/piq/sfcarcade/server/SfcDownloadGate.java','piq-sfc-arcade/src/main/java/cn/piq/sfcarcade/server/SfcServerManager.java','piq-sfc-arcade/src/main/java/cn/piq/sfcarcade/net/SfcNetwork.java','piq-sfc-arcade/src/main/java/cn/piq/sfcarcade/rom/SfcRomRepository.java']
    production=[ROOT/n for n in source_names]if a.production_source else []
    tests=[ROOT/'piq-sfc-home/tools/qa/SfcRepairBacklogFixedProbe.java',ROOT/'piq-sfc-home/tools/qa/SfcDownloadSendProbe.java',ROOT/'piq-fc-arcade/tools/qa/SfcTwoPortInputProbe.java',ROOT/'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/core/SfcLegalTestRom.java',ROOT/'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/server/SfcDownloadGateSelfTest.java']
    fence={str(f.relative_to(ROOT)):q.sha(f)for f in production+tests+[Path(__file__),Path(q.__file__)]}
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1');manifest=json.loads((q.MC.parent.parent/'artifacts/minecraft_1.21.1_version_manifest.json').read_text());deps=[]
    for lib in manifest['libraries']:
        parts=lib['name'].split(':')
        if len(parts)==3:deps.extend((cache/parts[0]/parts[1]/parts[2]).rglob(parts[1]+'-'+parts[2]+'.jar'))
    deps += [f for f in cache.rglob('*.jar')if not any(x in f.name for x in ('-sources','-javadoc','-userdev'))]
    began=time.monotonic()
    with tempfile.TemporaryDirectory(prefix='piq-sfc-audit-fix-')as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir();jars={}
        for k,v in paths.items():jars[k]=tmp/(k+'.jar');shutil.copyfile(v,jars[k]);assert q.sha(jars[k])==before[k]
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[out,q.MC,resources,jars['sfc'],jars['fc'],*deps]));arg=tmp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        compilation=q.run([q.JAVA/'javac.exe','@'+str(arg),'-proc:none','-encoding','UTF-8','-sourcepath',empty,'-d',out,*production,*tests],tmp)
        origin=out if production else jars['sfc'];base=[q.JAVA/'java.exe','-Xmx2G','-Djava.awt.headless=true','-Dfile.encoding=UTF-8','@'+str(arg)]
        playback=q.run([*base,'cn.piq.sfchome.client.SfcRepairBacklogFixedProbe',origin,jars['sfc']],tmp)
        repair=json.loads(next(l[10:]for l in playback.splitlines()if l.startswith('PIQ_AUDIT ')))
        downloads=q.run([*base,'cn.piq.sfcarcade.server.SfcDownloadGateSelfTest',origin],tmp)
        download=json.loads(next(l for l in downloads.splitlines()if l.startswith('{')))
        sending=q.run([*base,'cn.piq.sfcarcade.server.SfcDownloadSendProbe',origin],tmp)
        send_guard=json.loads(next(l for l in sending.splitlines()if l.startswith('{')))
        assert all(q.sha(v)==before[k]and q.sha(jars[k])==before[k]for k,v in paths.items())
    assert all(q.sha(ROOT/name)==digest for name,digest in fence.items())
    report={'ok':repair['fixed_backlog_gate']and download['ok']and send_guard['ok'],'mode':'production-source'if production else'final-jar-only','production_compiled':bool(production),'jars':{k:{'path':str(v),'sha256':before[k]}for k,v in paths.items()},'repair':repair,'downloads':download,'download_sending':send_guard,'source_sha256':fence,'compile_log':compilation,'elapsed_seconds':round(time.monotonic()-began,3),'limits':['Actual WASM/core worker with controlled host callbacks, not a real Minecraft server/socket.','Actual download admission/send guard and exact-file repository IO; ASM validates compiled send wiring, but session/world/protection callbacks are not constructed by this probe.','Only original diagnostic ROM fixtures; no user files or sound device.']}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({k:v for k,v in report.items()if k!='source_sha256'},ensure_ascii=False))
if __name__=='__main__':main()
