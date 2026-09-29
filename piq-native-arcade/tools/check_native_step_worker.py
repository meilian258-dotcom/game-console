"""New step source/IPC test only: reuse old helper callbacks, never load a MAME DLL or user ROM."""
import argparse,hashlib,json,os,subprocess,sys,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JDK=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
HELPER=ROOT.parent/'piq-fc-arcade/build/review-controls26-v2/piq-native-arcade/runtime/piq-native-helper.jar'
CACHE=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1')
JNA=CACHE/'net.java.dev.jna/jna/5.14.0/67bf3eaea4f0718cb376a181a629e5f88fa1c9dd/jna-5.14.0.jar'
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest().upper()
def run(command,cwd):
    result=subprocess.run(list(map(str,command)),cwd=cwd,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=40)
    if result.returncode:raise AssertionError(result.stdout+'\n'+result.stderr)
    return result.stdout
def main():
    sys.stdout.reconfigure(encoding='utf-8');p=argparse.ArgumentParser(description=__doc__);p.add_argument('--report',required=True,type=Path);a=p.parse_args()
    if a.report.exists():raise FileExistsError(a.report)
    assert sha(HELPER)=='20F6F3028D76DAEB01212D1808BE90E35BFB5429D1E06153B7D8B32DD73E943C'
    assert sha(JNA)=='34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6'
    files=[ROOT/'src/main/java/cn/piq/nativearcade/bridge/NativeStepProtocol.java',ROOT/'helper/src/main/java/cn/piq/nativearcade/bridge/NativeStepWorker.java',ROOT/'src/test/java/cn/piq/nativearcade/bridge/NativeStepProtocolTest.java',ROOT/'tools/qa/NativeStepWorkerProbe.java',ROOT.parent/'piq-fc-arcade/tools/qa/CabinetRoomTestRunner.java',Path(__file__).resolve()]
    before={str(x):sha(x)for x in files+[HELPER,JNA]}
    deps=[]
    for group in ('org.junit.jupiter','org.junit.platform','org.opentest4j','org.apiguardian'):
        deps.extend(x for x in (CACHE/group).rglob('*.jar')if not any(y in x.name for y in ('-sources','-javadoc')))
    with tempfile.TemporaryDirectory(prefix='piq-native-step-tests-')as folder:
        tmp=Path(folder);classes=tmp/'classes';classes.mkdir();cp=os.pathsep.join(map(str,[classes,HELPER,JNA,*deps]))
        build=run([JDK/'javac.exe','-encoding','UTF-8','--release','21','-proc:none','-cp',cp,'-d',classes,*files[:-1]],tmp)
        raw=run([JDK/'java.exe','-cp',cp,'cn.piq.nativearcade.bridge.NativeStepWorkerProbe',classes,HELPER],tmp)
        actual=json.loads(next(x for x in reversed(raw.splitlines())if x.startswith('{')))
        tests=json.loads(run([JDK/'java.exe','-cp',cp,'CabinetRoomTestRunner','cn.piq.nativearcade.bridge.NativeStepProtocolTest'],tmp).strip())
    assert all(sha(Path(name))==digest for name,digest in before.items())
    report={'ok':True,'mode':'production-source','production_compiled':True,'actual':actual,'protocol_tests':tests,'source_sha256':before,'compile_log':build,'limitations':['Step session and real old Engine callbacks tested with a callback-producing fake core, not MAME determinism.','No DLL, commercial ROM, game instance, Minecraft, runtime installation or old artifact modification.']}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as out:json.dump(report,out,ensure_ascii=False,indent=2)
    print(json.dumps({k:v for k,v in report.items()if k!='source_sha256'},ensure_ascii=False))
if __name__=='__main__':main()
