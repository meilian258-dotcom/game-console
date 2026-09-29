"""Two actual SFC cores: checkpoint/state repair while the healthy host advances; original ROM only."""
import argparse,json,os,shutil,sys,tempfile,time
from pathlib import Path
import run_sfc_playback_multiplayer_probe as q
ROOT=Path(__file__).resolve().parents[1]

def main():
    sys.stdout.reconfigure(encoding='utf-8')
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('fc','sfc','report'):p.add_argument('--'+name,type=Path,required=True)
    p.add_argument('--production-source',action='store_true');a=p.parse_args()
    if a.report.exists():raise ValueError('Use a new immutable evidence path')
    paths={k:getattr(a,k).resolve(strict=True)for k in ('fc','sfc')};before={k:q.sha(v)for k,v in paths.items()}
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1');deps=[f for f in cache.rglob('*.jar')if not any(x in f.name for x in ('-sources','-javadoc','-userdev'))]
    probes=[ROOT/'tools/qa/SfcRepairCoreProbe.java',ROOT.parent/'piq-fc-arcade/tools/qa/SfcTwoPortInputProbe.java',ROOT.parent/'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/core/SfcLegalTestRom.java']
    production=[ROOT/'src/main/java/cn/piq/sfchome'/s for s in ('client/SfcExecutionCore.java','client/SfcCheckpoints.java','server/SfcRepairLedger.java')]if a.production_source else []
    source={str(f.relative_to(ROOT.parent)):q.sha(f)for f in probes+production+[Path(__file__).resolve(),Path(q.__file__).resolve()]};began=time.monotonic()
    with tempfile.TemporaryDirectory(prefix='piq-sfc-repair-core-')as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir();jars={}
        for k,v in paths.items():jars[k]=tmp/(k+'.jar');shutil.copyfile(v,jars[k]);assert q.sha(jars[k])==before[k]
        cp=os.pathsep.join(map(str,[out,jars['sfc'],jars['fc'],q.MC,*deps]));arg=tmp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        compilation=q.run([q.JAVA/'javac.exe','@'+str(arg),'-proc:none','-encoding','UTF-8','-sourcepath',empty,'-d',out,*production,*probes],tmp)
        expected=out if production else jars['sfc'];output=q.run([q.JAVA/'java.exe','-Xmx2G','-Djava.awt.headless=true','@'+str(arg),'cn.piq.sfchome.client.SfcRepairCoreProbe',expected],tmp)
        actual=json.loads(next(line for line in reversed(output.splitlines())if line.startswith('{')));assert actual['ok']
        assert all(q.sha(v)==before[k]and q.sha(jars[k])==before[k]for k,v in paths.items())
    assert all(q.sha(ROOT.parent/name)==digest for name,digest in source.items())
    result={'ok':True,'mode':'production-source'if production else'final-jar-only','production_compiled':bool(production),'jars':{k:{'path':str(v),'sha256':before[k]}for k,v in paths.items()},'actual':actual,'source_sha256':source,'compile_log':compilation,'elapsed_seconds':round(time.monotonic()-began,3),'limitations':['Actual two WASM cores and canonical execution/state wrapper; not the SfcPlayback network coordinator.','Original 65816 ROM; no commercial ROM, Minecraft process, socket, sound device or user save accessed.','Permission and real Connection backpressure are validated separately.']}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(result,f,ensure_ascii=False,indent=2)
    print(json.dumps(result,ensure_ascii=False))
if __name__=='__main__':main()
