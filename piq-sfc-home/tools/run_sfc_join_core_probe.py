"""Real two-instance save/load check against frozen SFC6 plus current pure consent gate; no core rebuild."""
import argparse,hashlib,json,os,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
CORE=ROOT.parent/'制作Mod/03-街机模拟/PIQ-SFC街机/piq_sfc_arcade-0.2.0-alpha.6.jar'
FC=ROOT.parent/'制作Mod/03-街机模拟/PIQ-FC街机/alpha17-immersive/piq_fc_arcade-0.31.0-alpha.17.jar'
def run(args,seconds=120):
    p=subprocess.run(list(map(str,args)),cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=seconds)
    if p.returncode:raise AssertionError(p.stdout+'\n'+p.stderr)
    return p.stdout
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--report',type=Path,required=True);args=parser.parse_args()
    if args.report.exists():raise ValueError('Old evidence is immutable')
    before={str(p):hashlib.sha256(p.read_bytes()).hexdigest().upper() for p in [CORE,FC]}
    assert before[str(CORE)]=='38FA46C5D283EAD9E1F6666D01398F495E2E1A3260A1517BE8EE959710963363'
    with tempfile.TemporaryDirectory(prefix='sfc-join-core-')as folder:
        temp=Path(folder);classes=temp/'classes';classes.mkdir();empty=temp/'empty';empty.mkdir();cp=os.pathsep.join(map(str,[classes,CORE,FC]))
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',classes,ROOT/'src/main/java/cn/piq/sfchome/server/SfcJoinGate.java',ROOT/'tools/qa/SfcJoinCoreProbe.java',ROOT.parent/'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/core/SfcLegalTestRom.java'])
        actual=json.loads(run([JAVA/'java.exe','-Xmx2G','-cp',cp,'cn.piq.sfcarcade.core.SfcJoinCoreProbe'],180).strip())
    assert before=={str(p):hashlib.sha256(p.read_bytes()).hexdigest().upper()for p in [CORE,FC]}
    report={'passed':actual['passed'],'actual':actual,'frozen_jars_sha256':before,'scope':'Two real frozen WASM core instances, original legal diagnostic ROM, actual save/load and subsequent matching video/audio/state. Current pure approval gate used for ordered transfer/commit.','limitations':['No Minecraft clients or live network/protection plugins exercised.','Diagnostic ROM only; not a claim that every commercial game enables simultaneous two-player midgame.','No production SfcPlayback thread/packet dispatch execution; separate root compilation and integration audit required.']}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
