"""FC29 submitted-JAR-only real workers, original diagnostic core and registered packet codecs."""
import argparse,json,os,shutil,tempfile
from pathlib import Path
import verify_retro_alpha19 as q
from check_home29_pure import TESTS
ROOT=Path(__file__).resolve().parents[1]
def main():
    p=argparse.ArgumentParser();p.add_argument('--fc',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    if a.report.exists():raise ValueError('Refuse report overwrite')
    jar=a.fc.resolve(strict=True);sha,entries=q.archive(jar)
    report={'ok':True,'schema':'piq-home-runtime29-1','mode':'final-jar-only','jars':{'fc':{'path':str(jar),'sha256':sha}},'production_compiled':False,'minecraft_started':False,'installed':False}
    with tempfile.TemporaryDirectory(prefix='piq-home29-final-')as td:
        tmp=Path(td);out=tmp/'tests';out.mkdir();empty=tmp/'empty';empty.mkdir();copy=tmp/'fc.jar';shutil.copyfile(jar,copy)
        if q.digest(copy.read_bytes())!=sha:raise AssertionError('Staged copy mismatch')
        deps=q.dependencies();deps=[d for d in deps if not('org.junit.platform'in d.parts and '1.13.4'not in d.parts)and not('org.junit.jupiter'in d.parts and '5.13.4'not in d.parts)]
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[out,copy,q.MC,resources,*deps]));arg=tmp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        names=['ZapperCoreProbe','HomeGunController29Probe','HomeRuntime28Probe','HomeRuntime29Probe','ZapperWire26Probe','HomeRuntimeWire29Probe','Home29PureProbe','HomeSaveStore29Probe']
        sources=[ROOT/'tools/qa'/f'{n}.java'for n in names]+[ROOT/'src/test/java/cn/piq/fcarcade/home'/f'{n}.java'for n in TESTS]
        sources.append(ROOT/'tools/qa/HomeSaveStore29Test.java')
        q.run([q.JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources],tmp)
        for file in out.rglob('*.class'):
            if file.relative_to(out).as_posix()in entries:raise AssertionError('Production accidentally compiled '+str(file))
        bytecode=q.run([q.JAVA/'javap.exe','-classpath',copy,'-c','-p','cn.piq.fcarcade.client.ClientArcadeSession'],tmp)
        gate=bytecode[bytecode.index('private boolean isWorldAndBlockValid();'):bytecode.index('private boolean isActiveAt(',bytecode.index('private boolean isWorldAndBlockValid();'))]
        if not(gate.index('Field dimension:')<gate.index('Field computeHost:Z')<gate.index('Method connected:()Z')<gate.index('getBlockState:')):raise AssertionError('Host depends on local display chunk')
        for key,main in [('pure','Home29PureProbe'),('worker','cn.piq.fcarcade.client.HomeRuntime29Probe'),('core','cn.piq.fcarcade.core.wasm.HomeGunController29Probe'),('wire','HomeRuntimeWire29Probe'),('save_store','cn.piq.fcarcade.server.HomeSaveStore29Probe')]:
            result=q.parse_last_json(q.run([q.JAVA/'java.exe','-Djava.awt.headless=true','@'+str(arg),main,copy],tmp,timeout=90));report[key]=result
            if not result.get('ok'):raise AssertionError('Failed '+key)
        report['lifecycle_bytecode']={'ok':True,'production_origin':'final-jar-only','host_world_connection_before_client_display_chunk':True,'controller_range':'console-center <=6'}
        if q.digest(copy.read_bytes())!=sha or q.digest(jar.read_bytes())!=sha:raise AssertionError('Final bytes changed')
        report['test_source_sha256']={str(f.relative_to(ROOT)):q.digest(f.read_bytes())for f in sources}
    report['limits']=['Actual original diagnostic firmware and production WASM/worker/authority classes; no commercial ROM.','Coordinator is a test driver, not a Minecraft server world, sockets or physical multiplayer run.','Physical inventory/protection/GUI still require in-game acceptance; packet codec and pure transaction validation do not claim that.']
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=True))
if __name__=='__main__':main()
