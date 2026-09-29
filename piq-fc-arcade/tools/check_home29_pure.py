import argparse,json,os,tempfile
from pathlib import Path
import verify_retro_alpha19 as q
ROOT=Path(__file__).resolve().parents[1]
TESTS=['HomeRuntimeAuthorityTest','HomeGunController29Test','HomeIdleController29Test','HomeSaveIntent29Test','HomeControllerLedgerTest','HomeControllerInventoryTest','HomeControllerRecyclingTest']
def main():
    p=argparse.ArgumentParser();p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    if a.report.exists():raise ValueError('No overwrite')
    with tempfile.TemporaryDirectory(prefix='piq-home29-pure-')as td:
        tmp=Path(td);out=tmp/'classes';out.mkdir();args=tmp/'args';deps=q.dependencies()
        cp=os.pathsep.join(map(str,[out,*deps]));args.write_text('-cp\n"'+cp.replace('\\','/')+'"',encoding='utf-8')
        names=['home/HomeRuntimeAuthority','home/HomeControllerLedger','home/HomeControllerInventory','home/HomeSaveIntent','session/LockstepState','session/ControllerInputTransitions','session/ControllerDepartureInputs','session/ZapperInput','session/ZapperInputQueue','session/SessionRoster','session/ArcadeRole']
        sources=[ROOT/'src/main/java/cn/piq/fcarcade'/f'{n}.java'for n in names]+[ROOT/'src/test/java/cn/piq/fcarcade/home'/f'{n}.java'for n in TESTS]+[ROOT/'tools/qa/Home29PureProbe.java']
        q.run([q.JAVA/'javac.exe','@'+str(args),'-encoding','UTF-8','-proc:none','-d',out,*sources],tmp)
        report=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(args),'Home29PureProbe'],tmp));report['source_sha256']={str(f.relative_to(ROOT)):q.digest(f.read_bytes())for f in sources}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(report,f,indent=2)
    print(json.dumps(report))
if __name__=='__main__':main()
