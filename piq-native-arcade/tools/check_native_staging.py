"""Execute the production staging code and its JUnit assertions without a Gradle daemon."""
from pathlib import Path
import hashlib,json,os,subprocess,tempfile
ROOT=Path(__file__).resolve().parents[1]
JDK=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
CACHE=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1')
def check():
    deps=[]
    for group,artifact,version in [('org.junit.jupiter','junit-jupiter-api','5.13.4'),('org.junit.platform','junit-platform-commons','1.13.4'),('org.opentest4j','opentest4j','1.3.0'),('org.apiguardian','apiguardian-api','1.1.2')]:
        found=list((CACHE/group/artifact/version).rglob(artifact+'-'+version+'.jar'))
        if len(found)!=1:raise AssertionError(str((group,artifact,version,found)))
        deps+=found
    sources=[ROOT/'src/main/java/cn/piq/nativearcade/bridge/BridgeProtocol.java',ROOT/'src/main/java/cn/piq/nativearcade/bridge/NativeRomStaging.java',ROOT/'src/test/java/cn/piq/nativearcade/bridge/NativeRomStagingTest.java',ROOT/'tools/qa/NativeStagingProbe.java']
    with tempfile.TemporaryDirectory(prefix='piq-native-staging-check-') as td:
        temp=Path(td);classes=temp/'classes';classes.mkdir();cases=temp/'cases';cases.mkdir()
        cp=os.pathsep.join(map(str,[classes,*deps]))
        subprocess.run(list(map(str,[JDK/'javac.exe','-J-Duser.language=en','--release','21','-encoding','UTF-8','-cp',cp,'-d',classes,*sources])),check=True,timeout=30)
        result=subprocess.run(list(map(str,[JDK/'java.exe','-cp',cp,'cn.piq.nativearcade.bridge.NativeStagingProbe',cases])),check=True,capture_output=True,timeout=30)
        output=result.stdout.decode('utf-8');assert 'JUNIT_METHODS=6' in output
        return {'status':'passed','stdout':output,'sha256':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sources},'scope':'Actual production staging and six JUnit methods; opaque fixture bytes only, no commercial ROM/BIOS.'}
if __name__=='__main__':print(json.dumps(check(),ensure_ascii=True,indent=2))
