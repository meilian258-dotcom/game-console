"""Actual final default method and compiled lifecycle guards; never compiles production."""
import argparse,json,os,shutil,tempfile,zipfile
from pathlib import Path
import verify_retro_alpha19 as q
ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('--fc',type=Path,required=True);p.add_argument('--gba',type=Path);p.add_argument('--report',type=Path,required=True)
    a=p.parse_args()
    if a.report.exists():raise ValueError('Existing evidence must not be overwritten')
    inputs={'fc':a.fc.resolve(strict=True)}
    if a.gba:inputs['gba']=a.gba.resolve(strict=True)
    fence={k:q.digest(v.read_bytes())for k,v in inputs.items()}
    probe=ROOT/'tools/qa/PreparedCabinetFactory31Probe.java'
    with tempfile.TemporaryDirectory(prefix='piq-prepared-factory31-')as folder:
        tmp=Path(folder);out=tmp/'probe';out.mkdir();empty=tmp/'empty';empty.mkdir();jars={}
        for key,path in inputs.items():
            jars[key]=tmp/(key+'.jar');shutil.copyfile(path,jars[key]);assert q.digest(jars[key].read_bytes())==fence[key]
        cp=os.pathsep.join(map(str,[out,*jars.values(),q.MC,*q.dependencies()]))
        arg=tmp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        compiled=q.run([q.JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,probe],ROOT)
        result=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(arg),'-Djava.awt.headless=true','PreparedCabinetFactory31Probe',*jars.values()],tmp))
        assert result['ok']and result['production_origin']=='final-jar-only'
        production=set()
        for jar in jars.values():
            with zipfile.ZipFile(jar)as z:production.update(n for n in z.namelist()if n.endswith('.class'))
        assert not any(f.relative_to(out).as_posix()in production for f in out.rglob('*.class'))
        assert all(q.digest(path.read_bytes())==fence[k]and q.digest(jars[k].read_bytes())==fence[k]for k,path in inputs.items())
    report={'schema':'piq-prepared-factory31-1','ok':True,'mode':'final-jar-only','production_compiled':False,
            'jars':{k:{'path':str(v),'sha256':fence[k]}for k,v in inputs.items()},'probe':result,'compile_log':compiled,
            'tools_sha256':{str(f.relative_to(ROOT)):q.digest(f.read_bytes())for f in [probe,Path(__file__)]},
            'minecraft_world_or_socket_started':False,'native_core_started':False,'user_rom_or_save_accessed':False,
            'limitations':['Default interface method executes from the supplied FC JAR.',
                'Post-hook scenarios interpret the actual bytecode guard in a bounded test harness; they do not execute a live Minecraft session or addon callback.',
                'Worker cancellation/capability cleanup and optional GBA capture are bytecode contracts; live thread races and native save persistence are tested separately.']}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(a.report),'assertions':result['assertions'],'guard_paths':result['post_prepare_bytecode_paths']}))
if __name__=='__main__':main()
