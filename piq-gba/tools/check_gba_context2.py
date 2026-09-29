"""Final-JAR-only GBA2 save scope and actual Connection startup-gate probes."""
import argparse, json, os, sys, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT.parent/'piq-fc-arcade/tools'))
import verify_retro_alpha19 as q

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for name in ('fc','gba','report'):p.add_argument('--'+name,type=Path,required=True)
    a=p.parse_args();fc=a.fc.resolve(strict=True);gba=a.gba.resolve(strict=True);report=a.report.resolve()
    if report.exists():raise ValueError('No report overwrite')
    hashes={name:q.digest(path.read_bytes()) for name,path in [('fc',fc),('gba',gba)]}
    probes=[ROOT/'tools/qa/GbaServerScopeProbe.java',ROOT/'tools/qa/GbaLaunchContextProbe.java']
    source_hashes={str(x.relative_to(ROOT)):q.digest(x.read_bytes()) for x in probes}
    resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
    with tempfile.TemporaryDirectory(prefix='piq-gba-context2-final-') as folder:
        work=Path(folder);local_fc=work/'fc.jar';local_gba=work/'gba.jar'
        local_fc.write_bytes(fc.read_bytes());local_gba.write_bytes(gba.read_bytes())
        classes=work/'classes';classes.mkdir();empty=work/'empty';empty.mkdir()
        cp=os.pathsep.join(map(str,[classes,local_gba,local_fc,q.MC,resources.resolve(strict=True),*q.dependencies()]))
        argfile=work/'java.args';argfile.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        q.run([q.JAVA/'javac.exe','@'+str(argfile),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',classes,*probes],work)
        expected={'GbaServerScopeProbe.class','GbaLaunchContextProbe.class'}
        if {x.name for x in classes.rglob('*.class')}!=expected:raise AssertionError('Production or unexpected source compiled')
        scope=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(argfile),'cn.piq.gba.bridge.GbaServerScopeProbe',work/'owned-saves',local_gba],work))
        launch=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(argfile),'cn.piq.gba.client.GbaLaunchContextProbe',local_gba],work))
        if not all(x.get('ok') and x.get('production_origin')=='final-jar-only' for x in (scope,launch)):raise AssertionError('Probe failed')
        for name,path in [('fc',local_fc),('gba',local_gba)]:
            if q.digest(path.read_bytes())!=hashes[name]:raise AssertionError('Temporary final bytes changed')
    for name,path in [('fc',fc),('gba',gba)]:
        if q.digest(path.read_bytes())!=hashes[name]:raise AssertionError('Final changed')
    if source_hashes!={str(x.relative_to(ROOT)):q.digest(x.read_bytes()) for x in probes}:raise AssertionError('Probe source changed')
    result={'schema':'piq-gba-server-context-2','ok':True,'mode':'final-jar-only','jars':{name:{'path':str(path),'sha256':hashes[name]} for name,path in [('fc',fc),('gba',gba)]},
            'scope':scope,'launch':launch,'source_sha256':source_hashes,'compiled_only_probes_and_tests':True,'production_compiled':False,
            'limitations':['No Minecraft world/client instance, socket or native core started.','Actual prepared context acquisition needs a live client world; this probe tests the resulting immutable boundary and actual stale-connection rejection, not a fabricated world.']}
    report.parent.mkdir(parents=True,exist_ok=True)
    with report.open('x',encoding='utf-8')as output:json.dump(result,output,ensure_ascii=False,indent=2);output.write('\n')
    print(json.dumps({'ok':True,'report':str(report),'sha256':q.digest(report.read_bytes()),'scope_assertions':scope['assertions'],'launch_assertions':launch['assertions']},ensure_ascii=False))
if __name__=='__main__':main()
