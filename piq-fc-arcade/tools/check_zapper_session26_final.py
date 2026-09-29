"""Final-JAR-only real Zapper workers, registered codecs and connection dispatch."""
import argparse,json,os,shutil,tempfile
from pathlib import Path
import verify_retro_alpha19 as q
ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser();p.add_argument('--fc',required=True,type=Path);p.add_argument('--report',required=True,type=Path);a=p.parse_args()
    if a.report.exists():raise ValueError('Refuse previous evidence overwrite')
    jar=a.fc.resolve(strict=True);before,entries=q.archive(jar)
    report={'ok':True,'schema':'piq-zapper-session26-final-1','jars':{'fc':{'path':str(jar),'sha256':before}},'production_compiled':False,'minecraft_started':False,'installed':False}
    with tempfile.TemporaryDirectory(prefix='piq-zapper-session26-')as directory:
        temp=Path(directory);out=temp/'tests';out.mkdir();empty=temp/'empty';empty.mkdir();copy=temp/'fc.jar';shutil.copyfile(jar,copy)
        if q.digest(copy.read_bytes())!=before:raise AssertionError('Copy differs')
        deps=q.dependencies();deps=[d for d in deps if not ('org.junit.platform'in d.parts and '1.13.4'not in d.parts)and not ('org.junit.jupiter'in d.parts and '5.13.4'not in d.parts)]
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[out,copy,q.MC,resources,*deps]));arg=temp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        sources=[ROOT/'tools/qa'/n for n in ('ZapperCoreProbe.java','ZapperSession26Probe.java','ZapperWire26Probe.java')]+[ROOT/'src/test/java/cn/piq/fcarcade/session/ZapperLockstepTest.java']
        q.run([q.JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources],temp)
        for f in out.rglob('*.class'):
            if f.relative_to(out).as_posix()in entries:raise AssertionError('Production accidentally compiled '+str(f))
        report['worker']=q.parse_last_json(q.run([q.JAVA/'java.exe','-Djava.awt.headless=true','@'+str(arg),'cn.piq.fcarcade.client.ZapperSession26Probe',copy],temp))
        report['wire']=q.parse_last_json(q.run([q.JAVA/'java.exe','-Djava.awt.headless=true','@'+str(arg),'ZapperWire26Probe',copy],temp))
        if not all(report[k].get('ok')and report[k].get('production_origin')=='final-jar-only'for k in ('worker','wire')):raise AssertionError('Probe failed')
        if q.digest(copy.read_bytes())!=before or q.digest(jar.read_bytes())!=before:raise AssertionError('Final jar changed')
        report['test_source_sha256']={str(f.relative_to(ROOT)):q.digest(f.read_bytes())for f in sources}
    report['limits']=['Actual two production workers and independent WASM core, original diagnostic ROM only.','Real registered outer codecs and queued Connection handler; no Minecraft world/player construction, live socket, physical gun or gameplay acceptance.','Service authority additionally has source contracts; not a complete live server test.']
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=True))
if __name__=='__main__':main()
