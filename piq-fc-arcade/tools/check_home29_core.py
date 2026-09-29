"""Read-only proof that the existing gun module supports P1 plus physical-port-two gun."""
import argparse,json,os,shutil,tempfile
from pathlib import Path
import verify_retro_alpha19 as q
ROOT=Path(__file__).resolve().parents[1]
def main():
    p=argparse.ArgumentParser();p.add_argument('--fc',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    if a.report.exists():raise ValueError('Refuse evidence overwrite')
    jar=a.fc.resolve(strict=True);sha,entries=q.archive(jar)
    with tempfile.TemporaryDirectory(prefix='piq-home29-core-')as directory:
        tmp=Path(directory);out=tmp/'tests';out.mkdir();empty=tmp/'empty';empty.mkdir();copy=tmp/'fc.jar';shutil.copyfile(jar,copy)
        if q.digest(copy.read_bytes())!=sha:raise AssertionError('Copy differs')
        cp=os.pathsep.join(map(str,[out,copy,*q.dependencies()]));args=tmp/'cp.args';args.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        sources=[ROOT/'tools/qa/ZapperCoreProbe.java',ROOT/'tools/qa/HomeGunController29Probe.java']
        q.run([q.JAVA/'javac.exe','@'+str(args),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources],tmp)
        for file in out.rglob('*.class'):
            if file.relative_to(out).as_posix()in entries:raise AssertionError('Production compiled')
        probe=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(args),'cn.piq.fcarcade.core.wasm.HomeGunController29Probe',copy],tmp))
        if not probe.get('ok')or q.digest(copy.read_bytes())!=sha or q.digest(jar.read_bytes())!=sha:raise AssertionError('Proof or source integrity failed')
    report={'ok':True,'mode':'final-jar-only','production_compiled':False,'jars':{'fc':{'path':str(jar),'sha256':sha}},'fc':{'path':str(jar),'sha256':sha},'probe':probe,'unchanged_module_sha256':q.digest(entries['core/nes_zapper_v1.wasm']),'limits':['Original in-memory diagnostic firmware; not user game, Minecraft or socket acceptance.']}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=True))
if __name__=='__main__':main()
