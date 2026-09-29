"""Narrow real-Minecraft-API compile, never starts Minecraft or a graphics context."""
import argparse,json,os,tempfile
from pathlib import Path
import verify_retro_alpha19 as q
ROOT=Path(__file__).resolve().parents[1]

def main():
    p=argparse.ArgumentParser();p.add_argument('--report',type=Path,required=True);args=p.parse_args()
    assert not args.report.exists(),'Do not replace evidence'
    baseline=ROOT/'build/review-interaction24-v1/piq_fc_arcade-0.31.0-alpha.24.jar'
    assert q.digest(baseline.read_bytes())=='AE3185660442BA3BB38344165F3D60F736DB5DA3E63F2E4FE9D7DCC79C1F170B'
    relative=['layout/ScreenSurfaceGeometry.java','layout/ScreenRayMapping.java','layout/DualCabinetGeometry.java',
              'client/ArcadeBlockScreenRenderer.java','client/HomeVideoDisplay.java']
    source=[ROOT/'src/main/java/cn/piq/fcarcade'/s for s in relative]
    with tempfile.TemporaryDirectory(prefix='piq-screen25-api-')as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir()
        cp=os.pathsep.join(map(str,[out,baseline,q.MC,*q.dependencies()]))
        argfile=tmp/'compile.args';argfile.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        log=q.run([q.JAVA/'javac.exe','@'+str(argfile),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*source],tmp)
        classes=sorted(str(p.relative_to(out)).replace('\\','/')for p in out.rglob('*.class'))
    report={'ok':True,'actual_minecraft_api_compile':True,'minecraft_started':False,'gradle_started':False,
        'compiled_production_sources':relative,'compiled_classes':classes,'compiler_output':log,
        'baseline_fc':{'path':str(baseline),'sha256':q.digest(baseline.read_bytes())},
        'source_sha256':{str(p.relative_to(ROOT)):q.digest(p.read_bytes())for p in source},
        'limits':['API compilation only; not a real game/GL/render thread test.']}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'classes':len(classes),'report':str(args.report)}))
if __name__=='__main__':main()
