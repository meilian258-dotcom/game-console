"""Bounded single/dual cabinet link QA. --fc loads final production bytes and compiles tests/probes only."""
import argparse,json,os,shutil,tempfile
from pathlib import Path
import verify_retro_alpha19 as q
ROOT=Path(__file__).resolve().parents[1]
NAMES=['cabinet/CabinetSeats','cabinet/CabinetLinkLedger','cabinet/CabinetLinks','cabinet/CabinetRooms','cabinet/CabinetRoomNetwork','world/LegacyFcArcadeBlockEntity','client/CabinetDataCableRenderer','layout/CabinetDataCableGeometry','client/LegacyArcadeSkinRenderer','client/DualCabinetRenderer','client/cabinet/CabinetPeerInputs','client/cabinet/CabinetClientBackends']
TESTS=['cabinet/CabinetSeatsTest','cabinet/CabinetLinkLedgerTest','cabinet/CabinetRoomLedgerTest','cabinet/CabinetLinksSourceContractTest','client/cabinet/CabinetLinkedClientContractTest','client/cabinet/CabinetPeerInputsTest','layout/CabinetDataCableGeometryTest']
def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--fc',type=Path);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    assert not a.report.exists(),'New evidence only'
    original=(a.fc or ROOT/'build/review-appliance29-v1/piq_fc_arcade-0.31.0-alpha.29.jar').resolve(strict=True);sha=q.digest(original.read_bytes())
    with tempfile.TemporaryDirectory(prefix='piq-link30-')as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir();jar=tmp/'fc.jar';shutil.copyfile(original,jar)
        resources=q.MC.parent/'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp=os.pathsep.join(map(str,[out,jar,q.MC,resources,*q.dependencies()]));arg=tmp/'cp.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        sources=[]if a.fc else[ROOT/'src/main/java/cn/piq/fcarcade'/(name+'.java')for name in NAMES]
        tests=[ROOT/'src/test/java/cn/piq/fcarcade'/(name+'.java')for name in TESTS];probes=[ROOT/'tools/qa'/(name+'.java')for name in ['CabinetRoomTestRunner','CabinetLinks30Probe']]
        compile=q.run([q.JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources,*tests,*probes],ROOT)
        behavior=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(arg),'CabinetRoomTestRunner',*['cn.piq.fcarcade.'+name.replace('/','.')for name in TESTS]],ROOT))
        mode='final-jar-only'if a.fc else'explicit-source-qa'
        actual=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(arg),'cn.piq.fcarcade.cabinet.CabinetLinks30Probe',jar if a.fc else out,mode],ROOT))
        assert actual['ok']and actual['production_origin']==mode
        if a.fc:assert all(not(out/'cn/piq/fcarcade'/(name+'.class')).exists()for name in NAMES)
        assert q.digest(jar.read_bytes())==sha
        result={'ok':True,'mode':mode,'production_compiled':not bool(a.fc),'jars':{'fc':{'path':str(original),'sha256':sha}},'behavior':behavior,'actual':actual,'source_sha256':{str(f.relative_to(ROOT)):q.digest(f.read_bytes())for f in [*sources,*tests,*probes]},'minecraft_or_server_started':False,'compile_log':compile,'limitations':['Actual cached MC/NeoForge codecs and BE serialization, not live world/chunk/protection-plugin or socket gameplay.','Pure cable paths avoid the two cabinets; arbitrary terrain/other blocks are not scanned or modified.']}
    assert q.digest(original.read_bytes())==sha
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(result,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'report':str(a.report),'tests':behavior['passed_tests'],'actual_assertions':actual['assertions']}))
if __name__=='__main__':main()
