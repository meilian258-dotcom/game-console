"""Isolated production room-ledger tests and real-Minecraft network API probe; never starts a game."""
from __future__ import annotations
import argparse,hashlib,json,os,subprocess,tempfile,sys,shutil
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
MC=Path('C:/Users/13498/.gradle/caches/neoformruntime/intermediate_results/sourcesAndCompiledWithNeoForge_e75ff7a3db3c8d7760682f321018318019b04f3c_output.jar')
FC=ROOT.parent/'制作Mod/03-街机模拟/PIQ-FC街机/alpha20-compact-vanilla-ui/piq_fc_arcade-0.31.0-alpha.20.jar'
def run(args):
    p=subprocess.run(list(map(str,args)),cwd=ROOT,capture_output=True,encoding='utf-8',errors='replace',timeout=60)
    if p.returncode:raise AssertionError(p.stdout+p.stderr)
    return p.stdout
def main():
    sys.stdout.reconfigure(encoding='utf-8');p=argparse.ArgumentParser();p.add_argument('--pure',action='store_true');p.add_argument('--report',required=True,type=Path);a=p.parse_args()
    if a.report.exists():raise ValueError('Refuse to overwrite prior evidence')
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1');junit=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:
        junit.extend(f for f in (cache/group).rglob('*.jar')if version in f.parts and '-sources'not in f.name and '-javadoc'not in f.name)
    src=ROOT/'src/main/java/cn/piq/fcarcade/cabinet';test=ROOT/'src/test/java/cn/piq/fcarcade/cabinet'
    pure=[src/'CabinetRoomLedger.java',src/'CabinetRoomMedia.java',src/'CabinetSendWindow.java']
    tests=[test/'CabinetRoomLedgerTest.java',test/'CabinetRoomMediaTest.java',test/'CabinetSendWindowTest.java']
    evidence={'ok':True,'minecraft_started':False,'native_core_started':False,'network_socket_opened':False,'gradle_started':False}
    with tempfile.TemporaryDirectory(prefix='cabinet-room-')as folder:
        temp=Path(folder);out=temp/'classes';out.mkdir();empty=temp/'empty';empty.mkdir()
        jcp=os.pathsep.join(map(str,[out,*junit]))
        run([JAVA/'javac.exe','-cp',jcp,'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*pure,*tests,ROOT/'tools/qa/CabinetRoomTestRunner.java'])
        result=json.loads(run([JAVA/'java.exe','-cp',jcp,'CabinetRoomTestRunner','cn.piq.fcarcade.cabinet.CabinetRoomLedgerTest','cn.piq.fcarcade.cabinet.CabinetRoomMediaTest','cn.piq.fcarcade.cabinet.CabinetSendWindowTest']))
        if result['passed_tests']!=42:raise AssertionError('Missing production room tests')
        evidence['pure_production_tests']=result['passed_tests'];sources=pure+tests
        if not a.pure:
            deps=[];manifest=json.loads((MC.parent.parent/'artifacts/minecraft_1.21.1_version_manifest.json').read_text())
            for lib in manifest['libraries']:
                parts=lib['name'].split(':')
                if len(parts)==3:deps.extend((cache/parts[0]/parts[1]/parts[2]).rglob(parts[1]+'-'+parts[2]+'.jar'))
            deps.extend(f for f in cache.rglob('*.jar')if not any(s in f.name for s in ('-sources','-javadoc','-userdev')))
            fc=temp/'fc.jar';shutil.copyfile(FC,fc)
            resources=MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
            cp=os.pathsep.join(map(str,[out,MC,resources,fc,*deps]));arg=temp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
            production=[src/(n+'.java')for n in ['CabinetBackends','CabinetNetwork','CabinetRoomNetwork','ServerCabinets','CabinetRooms','CabinetLinks','CabinetLinkLedger','CabinetMediaPacket','CabinetMediaSender']]
            # Link ledger spelling is explicit and updated only if the authoritative implementation differs.
            missing=[str(f)for f in production if not f.exists()]
            if missing:raise FileNotFoundError('Wait for peer source freeze: '+', '.join(missing))
            probe=ROOT/'tools/qa/CabinetRoomIngressProbe.java'
            backpressure=ROOT/'tools/qa/CabinetSendBackpressureProbe.java'
            savedata=ROOT/'tools/qa/CabinetLinksDataProbe.java'
            run([JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*production,probe,backpressure,savedata])
            evidence['real_api_ingress_and_codecs']=json.loads(run([JAVA/'java.exe','-Djava.awt.headless=true','@'+str(arg),'cn.piq.fcarcade.cabinet.CabinetRoomIngressProbe']))
            transport=run([JAVA/'java.exe','-Djava.awt.headless=true','@'+str(arg),'cn.piq.fcarcade.cabinet.CabinetSendBackpressureProbe'])
            evidence['real_transport_backpressure']=json.loads(next(line for line in reversed(transport.splitlines())if line.startswith('{')))
            evidence['real_link_saved_data']=json.loads(run([JAVA/'java.exe','-Djava.awt.headless=true','@'+str(arg),'cn.piq.fcarcade.cabinet.CabinetLinksDataProbe']))
            for file in (out/'cn/piq/fcarcade/cabinet').glob('*.class'):
                if b'net/minecraft/client/'in file.read_bytes():raise AssertionError('Server/common class links client implementation: '+file.name)
            evidence['common_client_isolation']=True;sources+=production+[probe,backpressure,savedata]
        evidence['sources']={str(f.relative_to(ROOT)):hashlib.sha256(f.read_bytes()).hexdigest().upper()for f in sources}
    evidence['scope']='Executable production state machines and codec/ingress APIs; not a real Minecraft multiplayer playtest.'
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as stream:json.dump(evidence,stream,ensure_ascii=False,indent=2)
    print(json.dumps(evidence,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
