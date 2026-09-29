"""Final FC31/GBA2 only: single-seat server authority, observation and side-safe common registration."""
import argparse,json,os,shutil,tempfile,zipfile
from pathlib import Path
import verify_retro_alpha19 as q
ROOT=Path(__file__).resolve().parents[1]
PROBES=['GbaServer31Probe','GbaServer31CommonProbe']

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--fc',type=Path,required=True);p.add_argument('--gba',type=Path,required=True);p.add_argument('--sfc',type=Path);p.add_argument('--native',type=Path);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    if a.report.exists():raise ValueError('New evidence path required')
    if bool(a.sfc)!=bool(a.native):raise ValueError('Supply both frozen SFC and Native, or neither')
    inputs={'fc':a.fc,'gba':a.gba}
    if a.sfc:inputs.update(sfc=a.sfc,native=a.native)
    originals={k:v.resolve(strict=True)for k,v in inputs.items()};fence={k:q.digest(v.read_bytes())for k,v in originals.items()}
    if a.sfc:
        assert fence['sfc']=='7BDF3B5BAB4722B35D85303CD2F18C9A7BEA640B3DB7B00C3E9CAFF24E1B49DB','Not frozen SFC18'
        assert fence['native']=='F4011CBDE8DC3F3F3FA44FD03077638421C7D3334B33A55AFFB0E78C740C2453','Not frozen Native10'
    with tempfile.TemporaryDirectory(prefix='piq-gba-server31-')as folder:
        tmp=Path(folder);out=tmp/'probes';out.mkdir();empty=tmp/'empty';empty.mkdir();jars={}
        for key,path in originals.items():jars[key]=tmp/(key+'.jar');shutil.copyfile(path,jars[key]);assert q.digest(jars[key].read_bytes())==fence[key]
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[out,*jars.values(),q.MC,resources,*q.dependencies()]));arg=tmp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        probes=[ROOT/'tools/qa'/(name+'.java')for name in [*PROBES,*(['GbaServer31LegacyProbe']if a.sfc else[])]]
        compiled=q.run([q.JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*probes],ROOT)
        common=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(arg),'-Djava.awt.headless=true','--add-opens=java.base/java.lang.invoke=ALL-UNNAMED','GbaServer31CommonProbe',*jars.values()],tmp))
        authority=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(arg),'-Djava.awt.headless=true','cn.piq.fcarcade.cabinet.GbaServer31Probe',jars['fc'],jars['gba']],tmp))
        legacy=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(arg),'-Djava.awt.headless=true','GbaServer31LegacyProbe',jars['fc'],jars['sfc'],jars['native']],tmp))if a.sfc else None
        if legacy:assert legacy['ok']and legacy['production_origin']=='final-jar-only'
        assert common['ok']and authority['ok']and common['production_origin']==authority['production_origin']=='final-jar-only'
        production_names=set()
        for jar in jars.values():
            with zipfile.ZipFile(jar)as z:production_names.update(n for n in z.namelist()if n.endswith('.class'))
        assert not any(str(f.relative_to(out)).replace('\\','/')in production_names for f in out.rglob('*.class')),'Production class was compiled'
        assert all(q.digest(path.read_bytes())==fence[key]and q.digest(jars[key].read_bytes())==fence[key]for key,path in originals.items())
        result={'schema':'piq-gba-server31-final-1','ok':True,'mode':'final-jar-only','production_compiled':False,'compiled_only_probes':True,
                'jars':{k:{'path':str(v),'sha256':fence[k]}for k,v in originals.items()},'common':common,'authority':authority,'legacy_binary_compatibility':legacy,
                'source_sha256':{str(f.relative_to(ROOT)):q.digest(f.read_bytes())for f in [*probes,Path(__file__)]},'compile_log':compiled,
                'minecraft_world_or_socket_started':False,'native_core_started':False,'user_rom_or_save_accessed':False,
                'limitations':['Actual FML archive discovery and isolated common registration; not a dedicated-server or ModLauncher boot.',
                               'Actual production ledgers, registered outer packet codecs and bytecode control gates; no live player/world/protection-plugin or remote gameplay execution.',
                               'GBA single-seat on servers plus read-only observation, not GBA link-cable multiplayer. Native runtime tested separately by its owning component.']}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(result,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(a.report),'common_assertions':common['assertions'],'authority_assertions':authority['assertions']}))
if __name__=='__main__':main()
