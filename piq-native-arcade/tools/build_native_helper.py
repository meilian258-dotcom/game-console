"""Compile the private helper without Gradle or loading a native library in this Python process."""
from pathlib import Path
import argparse,subprocess,tempfile,hashlib,json
ROOT=Path(__file__).resolve().parents[1]
JDK=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
JNA=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1/net.java.dev.jna/jna/5.14.0/67bf3eaea4f0718cb376a181a629e5f88fa1c9dd/jna-5.14.0.jar')
def build(output):
    output=Path(output).resolve()
    if output.exists():raise FileExistsError(output)
    source=[ROOT/'src/main/java/cn/piq/nativearcade/bridge/BridgeProtocol.java',
            ROOT/'src/main/java/cn/piq/nativearcade/bridge/NativeInputPorts.java',
            ROOT/'helper/src/main/java/cn/piq/nativearcade/bridge/NativeCoreWorker.java']
    with tempfile.TemporaryDirectory(prefix='piq-helper-build-') as temp:
        subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','--release','21','-cp',str(JNA),'-d',temp,*map(str,source)],check=True,timeout=30)
        output.parent.mkdir(parents=True,exist_ok=True)
        subprocess.run([str(JDK/'jar.exe'),'--create','--file',str(output),'-C',temp,'.'],check=True,timeout=30)
    return {'file':str(output),'sha256':hashlib.sha256(output.read_bytes()).hexdigest().upper(),'bytes':output.stat().st_size}
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',required=True);args=parser.parse_args()
    print(json.dumps(build(args.output),indent=2))
