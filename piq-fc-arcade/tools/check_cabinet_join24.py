"""Compile only the explicit new consent sources/tests against real cached MC APIs; no Gradle/game."""
import argparse, hashlib, json, os, shutil, tempfile
from pathlib import Path
import check_cabinet_rooms as common

ROOT=common.ROOT
FC=ROOT/'build/review-watch23-v1/piq_fc_arcade-0.31.0-alpha.23.jar'

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--report',type=Path,required=True)
    parser.add_argument('--pure',action='store_true');args=parser.parse_args()
    if args.report.exists():raise ValueError('Refuse existing evidence')
    cache=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1');junit=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:
        junit.extend(f for f in (cache/group).rglob('*.jar')if version in f.parts and '-sources'not in f.name and '-javadoc'not in f.name)
    src=ROOT/'src/main/java/cn/piq/fcarcade/cabinet';test=ROOT/'src/test/java/cn/piq/fcarcade/cabinet/CabinetJoinGateTest.java'
    contract=test.with_name('CabinetJoinSourceContractTest.java')
    pure=[src/'CabinetJoinGate.java',src/'CabinetRoomLedger.java'];sources=[*pure,test,contract]
    result={'ok':True,'mode':'explicit-production-source-against-real-mc','gradle_started':False,'minecraft_started':False,'network_socket_opened':False}
    with tempfile.TemporaryDirectory(prefix='cabinet-join24-')as directory:
        temp=Path(directory);out=temp/'classes';out.mkdir();empty=temp/'empty';empty.mkdir()
        cp=os.pathsep.join(map(str,[out,*junit]))
        common.run([common.JAVA/'javac.exe','-cp',cp,'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*pure,test,contract,ROOT/'tools/qa/CabinetRoomTestRunner.java'])
        result['pure']=json.loads(common.run([common.JAVA/'java.exe','-cp',cp,'CabinetRoomTestRunner','cn.piq.fcarcade.cabinet.CabinetJoinGateTest','cn.piq.fcarcade.cabinet.CabinetJoinSourceContractTest']))
        if result['pure']['passed_tests']!=22:raise AssertionError('Missing 18 actual Gate + 4 source wiring cases')
        result['pure']['behavior_tests']=18;result['pure']['source_wiring_tests']=4
        if not args.pure:
            deps=[]
            manifest=json.loads((common.MC.parent.parent/'artifacts/minecraft_1.21.1_version_manifest.json').read_text())
            for library in manifest['libraries']:
                parts=library['name'].split(':')
                if len(parts)==3:deps.extend((cache/parts[0]/parts[1]/parts[2]).rglob(parts[1]+'-'+parts[2]+'.jar'))
            deps.extend(f for f in cache.rglob('*.jar')if not any(s in f.name for s in ('-sources','-javadoc','-userdev')))
            frozen=temp/'fc23.jar';shutil.copyfile(FC,frozen)
            resources=common.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
            cp=os.pathsep.join(map(str,[out,common.MC,resources,frozen,*deps]));arg=temp/'cp.args'
            arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
            production=[src/(name+'.java')for name in ('CabinetJoinNetwork','CabinetRooms','CabinetNetwork')]
            probe=ROOT/'tools/qa/CabinetJoinCodecProbe.java';sources+=production+[probe]
            common.run([common.JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*production,probe])
            output=common.run([common.JAVA/'java.exe','-Djava.awt.headless=true','@'+str(arg),'cn.piq.fcarcade.cabinet.CabinetJoinCodecProbe'])
            result['real_api']=json.loads(next(line for line in reversed(output.splitlines())if line.startswith('{')))
            for path in (out/'cn/piq/fcarcade/cabinet').glob('*.class'):
                if b'net/minecraft/client/'in path.read_bytes():raise AssertionError('Common/client linkage '+path.name)
            result['common_client_isolation']=True
        result['source_sha256']={str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest().upper()for p in sources}
    result['limits']=['Pure consent/seat behavior and actual codecs/queued client Connection only; not a real player/world/protection-plugin integration test.']
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as stream:json.dump(result,stream,ensure_ascii=False,indent=2)
    print(json.dumps(result,ensure_ascii=True))

if __name__=='__main__':main()
