"""Old Native cabinet compatibility: exact FC21+Native7 oracle against source or a final native JAR."""
import argparse,json,os,sys,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'piq-fc-arcade/tools'))
import verify_retro_alpha19 as q
OLD=ROOT/'piq-fc-arcade/build/review-linked21-v1'
OLD_FILES={'piq_fc_arcade-0.31.0-alpha.21.jar':'0147D49C542E82DDF2DD37CDAFFBDB135CDA20E11907B4060B471A2FBC0D3F93','piq_native_arcade-0.1.0-alpha.7.jar':'8BE543D920BB3D49627F440CBE02EBD66AA2BB4370EA249202EF1F1066E1608A'}

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--fc',type=Path,required=True);parser.add_argument('--native',type=Path);parser.add_argument('--report',type=Path,required=True);args=parser.parse_args()
    if args.report.exists():raise ValueError('Refusing to replace report')
    for name,digest in OLD_FILES.items():
        if q.digest((OLD/name).read_bytes())!=digest:raise AssertionError('Frozen oracle changed')
    fc=args.fc.resolve(strict=True);native=args.native.resolve(strict=True)if args.native else OLD/'piq_native_arcade-0.1.0-alpha.7.jar'
    artifacts={str(p):q.digest(p.read_bytes())for p in [fc,native]};deps=q.dependencies()
    mode='final-jar-only'if args.native else'compiled-workspace-production'
    source=ROOT/'piq-native-arcade/src/main/java/cn/piq/nativearcade/layout/NativeCabinetLayout.java'
    tests=[ROOT/'piq-native-arcade/src/test/java/cn/piq/nativearcade/layout'/name for name in ('NativeCabinetLayoutTest.java','NativeVideoIntegrationTest.java')]
    probe=ROOT/'piq-native-arcade/tools/qa/NativeLegacyGeometryProbe.java'
    with tempfile.TemporaryDirectory(prefix='piq-native-legacy24-')as folder:
        tmp=Path(folder);out=tmp/'classes';out.mkdir();empty=tmp/'empty';empty.mkdir();f=tmp/'fc.jar';n=tmp/'native.jar';f.write_bytes(fc.read_bytes());n.write_bytes(native.read_bytes())
        oldFc=tmp/'old-fc.jar';oldNative=tmp/'old-native.jar'
        oldFc.write_bytes((OLD/'piq_fc_arcade-0.31.0-alpha.21.jar').read_bytes());oldNative.write_bytes((OLD/'piq_native_arcade-0.1.0-alpha.7.jar').read_bytes())
        cp=os.pathsep.join(map(str,[out,n,f,*deps]));arg=tmp/'compile.args';arg.write_text('-cp\n"'+cp.replace('\\','/')+'"\n',encoding='utf-8')
        sources=[probe,*tests]+([]if args.native else[source])
        q.run([q.JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',out,*sources],tmp)
        if args.native and any(p.name=='NativeCabinetLayout.class'for p in out.rglob('*.class')):raise AssertionError('Production recompiled')
        result=q.parse_last_json(q.run([q.JAVA/'java.exe','@'+str(arg),'cn.piq.nativearcade.layout.NativeLegacyGeometryProbe',oldFc,oldNative,n if args.native else out,mode],tmp))
        if not result.get('ok'):raise AssertionError('Probe failed')
    if any(q.digest(Path(p).read_bytes())!=digest for p,digest in artifacts.items()):raise AssertionError('Input JAR changed')
    report={'ok':True,'mode':mode,'jars':{'fc':{'path':str(fc),'sha256':artifacts[str(fc)]},'native':{'path':str(native),'sha256':artifacts[str(native)]}},'frozen_oracles':OLD_FILES,'probe':result,'production_compiled':not bool(args.native),'source_sha256':q.digest(source.read_bytes()),'limits':['No Minecraft renderer/window or emulator launch; exact final/pure geometry and original video tests only.']}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as stream:json.dump(report,stream,ensure_ascii=False,indent=2);stream.write('\n')
    print(json.dumps({'ok':True,'report':str(args.report),'sha256':q.digest(args.report.read_bytes()),'assertions':result['assertions']},ensure_ascii=False))
if __name__=='__main__':main()
