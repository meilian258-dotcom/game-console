"""Real house Playback repair + generic CabinetSyncWorker/openSync in two JVMs; not a Minecraft network test."""

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home

import argparse,json,os,shutil,sys,tempfile,time,uuid
from pathlib import Path
import run_sfc_playback_multiplayer_probe as q
ROOT=Path(__file__).resolve().parents[1]

def exercise(host,peer):
    assertions=0
    def check(ok,message):
        nonlocal assertions
        assertions+=1
        if not ok:raise AssertionError(message)
    def trace(first,count):return [((1<<((i//3)%12))if i%3==0 else 0,(1<<((i//5)%12))if i%5==0 else 0)for i in range(first,first+count)]
    def send(worker,first,values,generic=False,*,paced=True,generation=1):
        size=120 if generic else 4
        for at in range(0,len(values),size):
            chunk=values[at:at+size];worker.command('frames',frame=first+at,p1=[v[0]for v in chunk],p2=[v[1]for v in chunk])
            # Ordinary playback is fed within its normal 3-4 frame buffer. A 120-frame
            # instantaneous burst is catch-up, not a valid test of every-frame audio.
            if not generic and paced:worker.wait_event('frame',lambda e:e['frame']==first+at+len(chunk) and e['generation']==generation)
    def await_frame(worker,frame,generation=1):return worker.wait_event('frame',lambda e:e['frame']==frame and e['generation']==generation)
    host.command('start',host=True);peer.command('start',host=False)
    hready=host.wait_event('ready');pready=peer.wait_event('ready')
    check(hready['hash']==pready['hash'],'same real initial state');check(hready['fps']==pready['fps'],'same actual FPS')
    values=trace(0,600)
    for first in range(0,600,120):
        send(host,first,values[first:first+120]);other=list(values[first:first+120])
        if first==480:other[-1]=(4095,4095)
        send(peer,first,other);await_frame(host,first+120);await_frame(peer,first+120)
    hd=host.wait_event('digest',lambda e:e['frame']==600);pd=peer.wait_event('digest',lambda e:e['frame']==600)
    check(hd['sha']!=pd['sha'],'actual injected input divergence changes full state')
    host.command('checkpoint',frame=600);snap=host.wait_event('snapshot',lambda e:e['frame']==600)
    nonce=str(uuid.uuid4());peer.command('begin',frame=600,token=nonce)
    future=trace(600,120);send(host,600,future);await_frame(host,720)
    check(peer.command('poll')['observed']==600,'guest is isolated while host advanced 120 frames')
    peer.command('restore',frame=600,token=nonce,bytes=snap['bytes'],sha=snap['sha'])
    restored=peer.wait_event('restored');check(restored['success']and restored['sha']==snap['sha'],'real worker exact restore SHA')
    peer.command('replay',frame=600,token=nonce,p1=[v[0]for v in future],p2=[v[1]for v in future])
    peer.command('resume',frame=720,token=nonce);peer.wait_event('done',lambda e:e['frame']==720);await_frame(peer,720)
    hframes=host.frames(1);pframes=peer.frames(1)
    for frame in range(601,721):check((hframes[frame]['video'],hframes[frame]['audio'],hframes[frame]['pcm'])==(pframes[frame]['video'],pframes[frame]['audio'],pframes[frame]['pcm']),'replayed full RGBA/stereo PCM '+str(frame))
    check(peer.command('poll')['audio_calls']==600,'catch-up PCM was not replayed through speaker sink')
    peer.command('stale_restore',frame=600,token=str(uuid.uuid4()))
    for first in range(720,1200,120):
        v=trace(first,120);send(host,first,v);send(peer,first,v);await_frame(host,first+120);await_frame(peer,first+120)
    hd2=host.wait_event('digest',lambda e:e['frame']==1200);pd2=peer.wait_event('digest',lambda e:e['frame']==1200)
    check(hd2['sha']==pd2['sha'],'next periodic full-state digest matches after repair')
    peer.command('frames',frame=1201,p1=[0],p2=[0]);fault=peer.wait_event('fault');check(fault['frame']==1200,'frame gap requests isolated repair')
    peer.command('begin',frame=1200,token=str(uuid.uuid4()));peer.command('close')
    send(host,1200,trace(1200,8));await_frame(host,1208)
    hs=host.command('poll');check(hs['audio_calls']==1208 and hs['media_calls']==1208,'healthy host never paused/restarted and retained audio/media')
    host.command('close');check(not host.command('poll')['lease']and not peer.command('poll')['lease'],'house core leases release')
    # Real public generic factory -> worker -> SFC provider, no fake emulator.
    host.command('cabinet',host=True);peer.command('cabinet',host=False)
    hr=host.wait_event('ready',lambda e:e['generation']==2);pr=peer.wait_event('ready',lambda e:e['generation']==2)
    check(hr['hash']==pr['hash']==hready['hash'],'house and generic adapters share identical bootstrap state')
    check(hr['compatibility']==pr['compatibility'],'generic compatibility IDs identical')
    send(host,0,trace(0,300),True)
    snapshot=host.wait_event('snapshot',lambda e:e['generation']==2 and e['frame']==300)
    peer.command('cabinet_restore',frame=300,goal=600,token=str(uuid.uuid4()),bytes=snapshot['bytes'],sha=snapshot['sha'])
    send(host,300,trace(300,300),True);send(peer,300,trace(300,300),True)
    gh=host.wait_event('digest',lambda e:e['generation']==2 and e['frame']==600)
    gp=peer.wait_event('digest',lambda e:e['generation']==2 and e['frame']==600)
    peer.wait_event('done',lambda e:e['generation']==2 and e['frame']==600)
    check(gh['sha']==gp['sha']==hd['sha'],'actual generic checkpoint restore/replay matches house 600-frame state')
    host.command('close');peer.command('close');s1,s2=host.command('poll'),peer.command('poll')
    check(not s1['lease']and not s2['lease'],'generic close finally released actual single-core guard')
    # Separate ordinary-vs-burst scenario. Preserve the ordinary every-frame output
    # assertion above; catch-up must execute identical frames while suppressing old
    # speaker/video output, rather than simply weakening the existing assertion.
    host.command('start',host=True);peer.command('start',host=False)
    bh=host.wait_event('ready',lambda e:e['generation']==3);bp=peer.wait_event('ready',lambda e:e['generation']==3)
    check(bh['hash']==bp['hash'],'burst comparison starts at same real state')
    burst_values=trace(0,600)
    send(host,0,burst_values,generation=3);await_frame(host,600,3)
    for first in range(0,600,200):
        send(peer,first,burst_values[first:first+200],paced=False,generation=3);await_frame(peer,first+200,3)
    burst_h=host.wait_event('digest',lambda e:e['generation']==3 and e['frame']==600)
    burst_p=peer.wait_event('digest',lambda e:e['generation']==3 and e['frame']==600)
    check(burst_h['sha']==burst_p['sha'],'ordinary and burst delivery end at identical complete WASM state')
    hframes,pframes=host.frames(3),peer.frames(3)
    check(set(hframes)==set(pframes)==set(range(1,601)),'burst did not drop or repeat a simulated frame')
    for frame in range(1,601):check((hframes[frame]['video'],hframes[frame]['audio'],hframes[frame]['pcm'])==(pframes[frame]['video'],pframes[frame]['audio'],pframes[frame]['pcm']),'burst preserves full video/audio/input execution '+str(frame))
    normal,burst=host.command('poll'),peer.command('poll')
    check(normal['audio_calls']==600 and normal['media_calls']==600 and normal['audio_flushes']==0,'ordinary live playback still emits every frame')
    check(0<burst['audio_calls']<600 and burst['audio_flushes']>=1,'burst explicitly flushes old audio and does not replay its backlog')
    host.command('close');peer.command('close');s1,s2=host.command('poll'),peer.command('poll')
    check(not s1['lease']and not s2['lease'],'burst cancellation releases both real core guards')
    return {'ok':True,'coordinator_assertions':assertions,'worker_assertions':s1['assertions']+s2['assertions'],'actual_codec_roundtrips':s1['codecs']+s2['codecs'],'actual_worker_jvms':2,'house_host_frames_without_restart':1208,'isolated_host_advanced_frames':120,'compared_full_rgba_stereo_pcm_replay_frames':120,'next_periodic_state_equal':True,'generic_actual_factory_openSync':True,'generic_restore_exact_and_caught_up_frame':600,'state_bytes':snap['size'],'guest_gap_requests_repair':True,'cancelled_guest_did_not_stop_host':True,'ordinary_output_frames':600,'burst_compared_frames':600,'burst_state_equal':True,'burst_audio_frames':burst['audio_calls'],'burst_audio_flushes':burst['audio_flushes']}

def main():
    sys.stdout.reconfigure(encoding='utf-8')
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('fc','sfc','report'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--production-source',action='store_true');a=p.parse_args()
    if a.report.exists():raise ValueError('Use a new immutable evidence path')
    paths={k:getattr(a,k).resolve(strict=True)for k in ('fc','sfc')};before={k:q.sha(v)for k,v in paths.items()}
    cache=(gradle_home() / 'caches/modules-2/files-2.1');manifest=json.loads((q.MC.parent.parent/'artifacts/minecraft_1.21.1_version_manifest.json').read_text());deps=[]
    for lib in manifest['libraries']:
        parts=lib['name'].split(':')
        if len(parts)==3:deps.extend((cache/parts[0]/parts[1]/parts[2]).rglob(parts[1]+'-'+parts[2]+'.jar'))
    deps += [f for f in cache.rglob('*.jar')if not any(x in f.name for x in ('-sources','-javadoc','-userdev'))]
    probes=[ROOT/'tools/qa/SfcRepairWorkerProbe.java',ROOT.parent/'piq-fc-arcade/tools/qa/SfcTwoPortInputProbe.java',ROOT.parent/'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/core/SfcLegalTestRom.java']
    production=sorted((ROOT/'src/main/java').rglob('*.java'))if a.production_source else[]
    source={str(f.relative_to(ROOT.parent)):q.sha(f)for f in probes+production+[Path(__file__).resolve(),Path(q.__file__).resolve()]};began=time.monotonic()
    with tempfile.TemporaryDirectory(prefix='piq-sfc-repair-workers-')as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir();jars={}
        for k,v in paths.items():jars[k]=tmp/(k+'.jar');shutil.copyfile(v,jars[k]);assert q.sha(jars[k])==before[k]
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[out,q.MC,resources,jars['sfc'],jars['fc'],*deps]));arg=tmp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        compilation=q.run([q.JAVA/'javac.exe','@'+str(arg),'-proc:none','-encoding','UTF-8','-sourcepath',empty,'-d',out,*production,*probes],tmp)
        expected=out if production else jars['sfc'];cmd=[q.JAVA/'java.exe','-Xmx2G','-Djava.awt.headless=true','-Dfile.encoding=UTF-8','@'+str(arg),'cn.piq.sfchome.client.SfcRepairWorkerProbe',expected,jars['sfc'],jars['fc']]
        workers=[]
        try:
            workers.append(q.Worker(cmd,tmp,'Host'));workers.append(q.Worker(cmd,tmp,'Peer'));actual=exercise(*workers)
        finally:
            for w in workers:w.finish()
        assert all(q.sha(v)==before[k]and q.sha(jars[k])==before[k]for k,v in paths.items())
    assert all(q.sha(ROOT.parent/name)==digest for name,digest in source.items())
    report={'ok':True,'mode':'production-source'if production else'final-jar-only','production_compiled':bool(production),'jars':{k:{'path':str(v),'sha256':before[k]}for k,v in paths.items()},'actual':actual,'source_sha256':source,'compile_log':compilation,'elapsed_seconds':round(time.monotonic()-began,3),'limitations':['Actual SfcPlayback + CabinetSyncWorker + WASM cores and real record codecs, not a Minecraft server/client socket or permission-plugin integration.','Python coordinates frames/checkpoints; SfcHomeServer, SfcRepairClient and actual network backpressure are validated separately.','Original input-sensitive diagnostic 65816 ROM only; no commercial ROM, user save, sound device or game instance.']}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({k:v for k,v in report.items()if k!='source_sha256'},ensure_ascii=False))
if __name__=='__main__':main()
