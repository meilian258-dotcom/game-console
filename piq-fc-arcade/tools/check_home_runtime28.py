"""Final-JAR-only FC appliance authority and two real WASM worker scenarios."""
import argparse,json,os,shutil,tempfile
from pathlib import Path
import verify_retro_alpha19 as q
ROOT=Path(__file__).resolve().parents[1]
def main():
    p=argparse.ArgumentParser();p.add_argument('--fc',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    if a.report.exists():raise ValueError('Refuse evidence overwrite')
    jar=a.fc.resolve(strict=True);before,entries=q.archive(jar)
    report={'ok':True,'schema':'piq-home-runtime28-1','jars':{'fc':{'path':str(jar),'sha256':before}},'production_compiled':False,'minecraft_started':False,'installed':False}
    with tempfile.TemporaryDirectory(prefix='piq-home-runtime28-')as directory:
        tmp=Path(directory);out=tmp/'tests';out.mkdir();empty=tmp/'empty';empty.mkdir();copy=tmp/'fc.jar';shutil.copyfile(jar,copy)
        if q.digest(copy.read_bytes())!=before:raise AssertionError('Copy differs')
        deps=q.dependencies();deps=[d for d in deps if not ('org.junit.platform'in d.parts and '1.13.4'not in d.parts)and not ('org.junit.jupiter'in d.parts and '5.13.4'not in d.parts)]
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[out,copy,q.MC,resources,*deps]));args=tmp/'cp.args';args.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        sources=[ROOT/'tools/qa/ZapperCoreProbe.java',ROOT/'tools/qa/HomeRuntime28Probe.java',ROOT/'tools/qa/ZapperWire26Probe.java',ROOT/'tools/qa/HomeRuntimeWire28Probe.java',ROOT/'src/test/java/cn/piq/fcarcade/home/HomeRuntimeAuthorityTest.java']
        q.run([q.JAVA/'javac.exe','@'+str(args),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources],tmp)
        for file in out.rglob('*.class'):
            if file.relative_to(out).as_posix()in entries:raise AssertionError('Production accidentally compiled '+str(file))
        # Inspect the actual submitted class, not current source: a distant Host
        # must return through the same-world/current-connection lane before any
        # client display-chunk lookup. World authority remains on the server.
        bytecode=q.run([q.JAVA/'javap.exe','-classpath',copy,'-c','-p','cn.piq.fcarcade.client.ClientArcadeSession'],tmp)
        start=bytecode.index('private boolean isWorldAndBlockValid();');end=bytecode.index('private boolean isActiveAt(',start);gate=bytecode[start:end]
        if not (gate.index('Field dimension:')<gate.index('Field computeHost:Z')<gate.index('Method connected:()Z')<gate.index('getBlockState:')):raise AssertionError('Background Host depends on local display chunk or bypasses the world/connection gate')
        report['lifecycle_bytecode']={'ok':True,'production_origin':'final-jar-only','same_world_then_current_host_before_local_display_chunk':True}
        report['worker']=q.parse_last_json(q.run([q.JAVA/'java.exe','-Djava.awt.headless=true','@'+str(args),'cn.piq.fcarcade.client.HomeRuntime28Probe',copy],tmp))
        if not report['worker'].get('ok'):raise AssertionError('Probe failed')
        report['wire']=q.parse_last_json(q.run([q.JAVA/'java.exe','-Djava.awt.headless=true','@'+str(args),'HomeRuntimeWire28Probe',copy],tmp))
        if not report['wire'].get('ok'):raise AssertionError('Wire failed')
        if q.digest(copy.read_bytes())!=before or q.digest(jar.read_bytes())!=before:raise AssertionError('Final JAR changed')
        report['test_source_sha256']={str(f.relative_to(ROOT)):q.digest(f.read_bytes())for f in sources}
    report['limits']=['Real production workers and original diagnostic firmware on both unchanged WASM modules; no commercial ROM.','The coordinator uses actual authority/roster/lockstep classes but is not a running Minecraft world, server or socket test.','GUI/focus and physical inventory admission require additional game-world acceptance.']
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=True))
if __name__=='__main__':main()
