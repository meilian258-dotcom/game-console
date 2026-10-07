"""Final-JAR-only real SFC worker media failure/sequence test. Never compiles production."""

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home

import argparse, json, os, shutil, subprocess, sys, tempfile, time
from pathlib import Path
from run_sfc_playback_multiplayer_probe import ROOT,JAVA,MC,sha,run

def main():
    sys.stdout.reconfigure(encoding='utf-8');p=argparse.ArgumentParser()
    for n in ('fc','sfc','report'):p.add_argument('--'+n,required=True,type=Path)
    a=p.parse_args()
    if a.report.exists():raise ValueError('Choose a new evidence path')
    jars={n:getattr(a,n).resolve(strict=True) for n in ('fc','sfc')};hashes={n:sha(f)for n,f in jars.items()}
    cache=(gradle_home() / 'caches/modules-2/files-2.1');deps=[]
    manifest=json.loads((MC.parent.parent/'artifacts/minecraft_1.21.1_version_manifest.json').read_text())
    for lib in manifest['libraries']:
        bits=lib['name'].split(':')
        if len(bits)==3:deps.extend((cache/bits[0]/bits[1]/bits[2]).rglob(bits[1]+'-'+bits[2]+'.jar'))
    deps.extend(f for f in cache.rglob('*.jar')if not any(s in f.name for s in ('-sources','-javadoc','-userdev')))
    probes=[ROOT/'tools/qa/SfcWatchPlaybackProbe.java',ROOT.parent/'piq-fc-arcade/tools/qa/SfcTwoPortInputProbe.java',
            ROOT.parent/'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/core/SfcLegalTestRom.java']
    source_hashes={str(f.relative_to(ROOT.parent)):sha(f)for f in probes+[Path(__file__).resolve()]}
    started=time.monotonic()
    with tempfile.TemporaryDirectory(prefix='sfc-watch-final-worker-')as folder:
        temp=Path(folder);out=temp/'classes';out.mkdir();empty=temp/'empty';empty.mkdir();copies={}
        for n,f in jars.items():copies[n]=temp/(n+'.jar');shutil.copyfile(f,copies[n]);assert sha(copies[n])==hashes[n]
        resources=MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[out,MC,resources,copies['sfc'],copies['fc'],*deps]));arg=temp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        run([JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*probes],temp)
        output=run([JAVA/'java.exe','-Xmx2G','-Djava.awt.headless=true','@'+str(arg),'cn.piq.sfchome.client.SfcWatchPlaybackProbe',copies['sfc'],copies['fc']],temp)
        actual=json.loads(next(line for line in reversed(output.splitlines())if line.startswith('{"ok":')))
        assert all(sha(jars[n])==hashes[n]and sha(copies[n])==hashes[n]for n in jars)
    report={'ok':True,'mode':'final-jar-only','production_compiled':False,'actual':actual,'elapsed_seconds':round(time.monotonic()-started,3),
            'jars':{n:{'path':str(f),'sha256':hashes[n]}for n,f in jars.items()},'source_sha256':source_hashes,
            'limitations':['Real worker/core/media queue; test Host replaces scheduling/audio/backup.','No Minecraft world/server, socket, actual spectator dispatch or commercial ROM.']}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as stream:json.dump(report,stream,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
