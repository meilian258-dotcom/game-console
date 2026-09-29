"""Actual four-port helper parser/callback plus optional original-firmware native child checks."""
import argparse,hashlib,json,os,shutil,subprocess,tempfile
from pathlib import Path
from build_native_helper import build,JDK,JNA,ROOT
from check_native_bridge import capture
from make_diagnostic_rom import make

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest().upper()
def main():
    p=argparse.ArgumentParser();p.add_argument('--report',type=Path,required=True);p.add_argument('--runtime',type=Path);p.add_argument('--jar',type=Path);args=p.parse_args()
    if args.report.exists():raise FileExistsError(args.report)
    with tempfile.TemporaryDirectory(prefix='native-four-ports-')as folder:
        root=Path(folder);classes=root/'classes';classes.mkdir();empty=root/'empty';empty.mkdir();deps=[JNA]
        sources=list((ROOT/'src/main/java/cn/piq/nativearcade/bridge').glob('*.java'))+list((ROOT/'helper/src/main/java/cn/piq/nativearcade/bridge').glob('*.java'))
        if args.jar:
            if not args.runtime:raise ValueError('Final-jar mode requires exact runtime directory')
            for path in (args.jar,args.runtime/'piq-native-helper.jar'):
                target=root/path.name;shutil.copyfile(path,target)
                if sha(path)!=sha(target):raise ValueError('Staged JAR mismatch')
                deps.append(target)
            sources=[]
        probes=[ROOT/'tools/qa'/name for name in ('NativeFourPortHelperProbe.java','NativeBridgeProbe.java','NativeFourPortBridgeProbe.java')]
        cp=os.pathsep.join(map(str,[classes,*deps]))
        subprocess.run(list(map(str,[JDK/'javac.exe','--release','21','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',classes,*sources,*probes])),check=True,timeout=30)
        origin_args=[root/'piq-native-helper.jar',root/args.jar.name]if args.jar else []
        helper=capture([JDK/'java.exe','-cp',cp,'cn.piq.nativearcade.bridge.NativeFourPortHelperProbe',*origin_args])
        result={'ok':True,'production_compiled':not bool(args.jar),'helper_parser':json.loads(helper['stdout'].strip().splitlines()[-1]),'minecraft_started':False,'user_files_changed':False}
        if args.runtime:
            runtime=args.runtime.resolve();firmware=make(root/'invaders.zip')
            native=capture([JDK/'java.exe','-cp',cp,'cn.piq.nativearcade.bridge.NativeFourPortBridgeProbe',runtime,root/'invaders.zip',*([root/args.jar.name]if args.jar else [])],timeout=40)
            result.update(native_bridge=json.loads(native['stdout'].strip().splitlines()[-1]),runtime_sha256={n:sha(runtime/n)for n in ('piq-native-helper.jar','mame_libretro.dll','jna-5.14.0.jar')},firmware=firmware)
        if args.jar:
            result['parent_jar_sha256']=sha(args.jar)
            result['production_origin']='final-jar-only'
            result['staged_jars']={str(path.resolve()):sha(path)for path in(args.jar,args.runtime/'piq-native-helper.jar')}
        result['limits']=['Four helper input callbacks tested directly; original invaders diagnostic hardware exercises only its supported lines.','Not four-player commercial gameplay, Minecraft networking or user-instance testing.']
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8')as f:json.dump(result,f,ensure_ascii=False,indent=2)
    print(json.dumps(result,ensure_ascii=False))
if __name__=='__main__':main()
