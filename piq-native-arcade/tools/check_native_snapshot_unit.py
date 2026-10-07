"""Narrow actual JUnit tests for the production profile, opaque envelope, paths and shared process lease."""

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home, java_home

import argparse,hashlib,json,os,subprocess,sys,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JDK=(java_home() / 'bin')
CACHE=(gradle_home() / 'caches/modules-2/files-2.1')
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest().upper()
def main():
 sys.stdout.reconfigure(encoding='utf-8');p=argparse.ArgumentParser(description=__doc__);p.add_argument('--native',type=Path);p.add_argument('--report',type=Path,required=True);a=p.parse_args();assert not a.report.exists(),'Refuse overwrite'
 dependencies=[]
 for group,version in [('org.junit.jupiter','5.13.4'),('org.junit.platform','1.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:dependencies.extend((CACHE/group).glob('*/'+version+'/*/*.jar'))
 assert len(dependencies)==9,'Exact cached JUnit inventory'
 names=['NativeProcessSession','NativeRomStaging','NativeStepProtocol','BridgeProtocol','NativeInputPorts','NativeArcadeButtons','NativeSnapshotSession','NativeSnapshotWorkspace','NativeSnapshotState']
 sources=[ROOT/f'src/main/java/cn/piq/nativearcade/bridge/{n}.java'for n in names]+[ROOT/'src/main/java/cn/piq/nativearcade/NativeSnapshotProfile.java']
 tests=[ROOT/f'src/test/java/cn/piq/nativearcade/bridge/{n}.java'for n in ['NativeSnapshotStateTest','NativeSnapshotWorkspaceTest']]+[ROOT/'tools/qa/NativeSnapshotUnitRunner.java']
 inputs=[*dependencies,*tests,*([a.native]if a.native else sources),Path(__file__).resolve()];before={str(x.resolve()):sha(x)for x in inputs}
 with tempfile.TemporaryDirectory(prefix='piq-snapshot-unit-')as folder:
  classes=Path(folder)/'classes';classes.mkdir();cp=os.pathsep.join(map(str,[classes,*dependencies,*([a.native.resolve()]if a.native else [])]))
  built=subprocess.run(list(map(str,[JDK/'javac.exe','--release','21','-encoding','UTF-8','-proc:none','-implicit:none','-cp',cp,'-d',classes,*tests,*([]if a.native else sources)])),capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=40);assert built.returncode==0,built.stdout+built.stderr
  run=subprocess.run([str(JDK/'java.exe'),'-Xmx256m','-cp',cp,'NativeSnapshotUnitRunner'],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=40);assert run.returncode==0,run.stdout+run.stderr
  result=json.loads(next(x for x in reversed(run.stdout.splitlines())if x.startswith('{')))
 assert before=={str(x.resolve()):sha(x)for x in inputs},'Source/input fence changed'
 result.update(mode='final-jar-only'if a.native else'production-source-subset',production_compiled=not bool(a.native),input_sha256=before)
 if a.native:result['jars']={'native':{'path':str(a.native.resolve()),'sha256':sha(a.native)}}
 a.report.parent.mkdir(parents=True,exist_ok=True)
 with a.report.open('x',encoding='utf-8')as out:json.dump(result,out,ensure_ascii=False,indent=2)
 print(json.dumps({'ok':True,'report':str(a.report.resolve()),'tests':result['tests']},ensure_ascii=False))
if __name__=='__main__':main()
