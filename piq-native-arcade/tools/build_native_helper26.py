"""New numbered-button helper candidate; old helpers and DLLs are never changed."""
import argparse,hashlib,json,subprocess,tempfile,zipfile
from pathlib import Path
from build_native_helper import ROOT,JDK,JNA

def build(output):
    output=Path(output).resolve()
    if output.exists():raise FileExistsError(output)
    sources=[ROOT/'src/main/java/cn/piq/nativearcade/bridge'/(name+'.java')for name in ['BridgeProtocol','NativeInputPorts','NativeArcadeButtons']]
    sources.append(ROOT/'helper/src/main/java/cn/piq/nativearcade/bridge/NativeCoreWorker.java')
    with tempfile.TemporaryDirectory(prefix='piq-helper26-build-')as folder:
        tmp=Path(folder)
        subprocess.run(list(map(str,[JDK/'javac.exe','-encoding','UTF-8','--release','21','-cp',JNA,'-d',tmp,*sources])),check=True,timeout=30)
        output.parent.mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(output,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=9)as jar:
            for path in sorted(tmp.rglob('*.class')):
                member=zipfile.ZipInfo(path.relative_to(tmp).as_posix(),(2026,9,11,0,0,0));member.compress_type=zipfile.ZIP_DEFLATED;jar.writestr(member,path.read_bytes())
    return {'path':str(output),'sha256':hashlib.sha256(output.read_bytes()).hexdigest().upper(),'bytes':output.stat().st_size,'private_protocol':3,'numbered_buttons':[0,8,1,9,10,11],'source_sha256':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest().upper()for p in sources}}
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',required=True,type=Path);args=parser.parse_args();print(json.dumps(build(args.output),indent=2))
