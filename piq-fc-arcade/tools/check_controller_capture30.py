"""Pure production receipt / two-lease sequence / gun keyboard route regression; no Minecraft world."""
import argparse, hashlib, json, os, shutil, subprocess, sys, tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
CACHE=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1')
PRODUCTION=['client/ControllerCapturePolicy','client/HomeInputSequences','home/HomeRuntimeAuthority','session/LockstepState','session/ControllerInputTransitions','session/ZapperInput','session/ZapperInputQueue']
TESTS=['client/ControllerCapturePolicyTest','client/HomeInputSequencesTest','home/HomeGunKeyboardFallbackTest','home/HomeGunController29Test']
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest().upper()
def run(command,cwd):
    result=subprocess.run(list(map(str,command)),cwd=cwd,capture_output=True,text=True,encoding='utf-8',errors='replace')
    if result.returncode:raise RuntimeError(result.stdout+'\n'+result.stderr)
    return result.stdout
def main():
    sys.stdout.reconfigure(encoding='utf-8');p=argparse.ArgumentParser();p.add_argument('--fc',type=Path);p.add_argument('--source',action='store_true');p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    if a.report.exists():raise ValueError('Choose a new immutable report path')
    if not a.source and not a.fc:raise ValueError('Final mode requires --fc')
    jars={}if a.fc is None else {'fc':{'path':str(a.fc.resolve()),'sha256':sha(a.fc)}}
    dependencies=[]
    for group in ('org.junit.jupiter','org.junit.platform','org.opentest4j','org.apiguardian'):
        dependencies.extend(path for path in (CACHE/group).rglob('*.jar')if not any(s in path.name for s in ('-sources','-javadoc')))
    test_paths=[ROOT/'src/test/java/cn/piq/fcarcade'/f'{name}.java'for name in TESTS]
    production=[ROOT/'src/main/java/cn/piq/fcarcade'/f'{name}.java'for name in PRODUCTION]if a.source else []
    runner=ROOT/'tools/qa/ControllerCapture30TestRunner.java'
    with tempfile.TemporaryDirectory(prefix='piq-capture30-')as folder:
        tmp=Path(folder);classes=tmp/'classes';classes.mkdir();empty=tmp/'empty';empty.mkdir();cp=[classes]
        if a.fc:shutil.copyfile(a.fc,tmp/'fc.jar');cp.append(tmp/'fc.jar')
        cp.extend(dependencies);arg=tmp/'cp.args';arg.write_text('-cp\n"'+os.pathsep.join(map(str,cp)).replace('\\','/')+'"\n',encoding='utf-8')
        run([JAVA/'javac.exe','@'+str(arg),'-encoding','UTF-8','-proc:none','-sourcepath',empty,'-d',classes,*production,*test_paths,runner],tmp)
        command=[JAVA/'java.exe','-Djava.awt.headless=true','@'+str(arg),'ControllerCapture30TestRunner']
        if not a.source:command.append(tmp/'fc.jar')
        output=run(command,tmp);actual=json.loads(next(line for line in reversed(output.splitlines())if line.startswith('{')))
        if a.fc and(sha(a.fc)!=jars['fc']['sha256']or sha(tmp/'fc.jar')!=jars['fc']['sha256']):raise AssertionError('Input JAR changed')
    report={'ok':True,'mode':'production-source'if a.source else 'final-jar-only','production_compiled':a.source,'jars':jars,'actual':actual,'source_sha256':{str(path.relative_to(ROOT)):sha(path)for path in [*production,*test_paths,runner,Path(__file__)]},'scope':'Real pure production receipts/identity/lease routing and FIFO transitions; no Minecraft world, player, actual permission callback, input device or network.'}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as stream:json.dump(report,stream,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
