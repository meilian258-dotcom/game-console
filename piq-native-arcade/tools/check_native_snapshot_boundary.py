"""Real production NativeSnapshotCore logical-frame-zero cold restore; never modifies ROMs or frozen runtime."""
import argparse,hashlib,json,os,shutil,subprocess,sys,tempfile,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
FC=ROOT.parent/'piq-fc-arcade'
LAB=ROOT.parent/'piq-native-snapshot-lab'
JDK=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
PINNED={
 'piqneogeo_libretro.dll':(LAB/'candidates/rtc-audio-lua-v2/piqneogeo_libretro.dll','E8F435903332AC80468769604779295A6965046DC25D4706A583D14D91C35201'),
 'piq-snapshot-helper.jar':(LAB/'build/standalone/piq-snapshot-helper.jar','F175B7CB60B37A95E1F5B2ED5FB48CE066FC1B6AE1ECD8089B8E6F957EF76C97'),
 'jna-5.14.0.jar':(LAB/'build/standalone/jna-5.14.0.jar','34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6')}
ROMS={'kof97.zip':'804F892924D4650545D3EA2D19FB85670094DC46DB882FECAF3E03009E2C4B9F','mslug2.zip':'1A82D65E88050FDC75DBCEA180E48802D54C67A4748558FC4BD51C0103D56E4A','neogeo.zip':'E1FFD4AB180E2F6AA4A3AA4D2C6F991E19D8EF762BEC8B5A6283704A2EDC3BBC'}
def sha(path):
 d=hashlib.sha256()
 with path.open('rb')as inp:
  for b in iter(lambda:inp.read(131072),b''):d.update(b)
 return d.hexdigest().upper()
def identity(path):return {'sha256':sha(path),'bytes':path.stat().st_size,'mtime_ns':path.stat().st_mtime_ns}
def main():
 sys.stdout.reconfigure(encoding='utf-8');p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--fc',type=Path);p.add_argument('--native',type=Path);p.add_argument('--kof97',type=Path,required=True);p.add_argument('--mslug2',type=Path,required=True);p.add_argument('--bios',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
 assert bool(a.fc)==bool(a.native),'Provide both final JARs or neither';assert not a.report.exists(),'Refuse overwrite'
 names=['NativeProcessSession','NativeRomStaging','NativeStepProtocol','BridgeProtocol','NativeInputPorts','NativeArcadeButtons','NativeSnapshotSession','NativeSnapshotWorkspace','NativeSnapshotState']
 sources=[ROOT/f'src/main/java/cn/piq/nativearcade/bridge/{n}.java'for n in names]+[ROOT/'src/main/java/cn/piq/nativearcade/NativeSnapshotProfile.java',ROOT/'src/main/java/cn/piq/nativearcade/client/NativeSnapshotCore.java',FC/'src/main/java/cn/piq/fcarcade/cabinet/CabinetSyncCore.java',FC/'src/main/java/cn/piq/fcarcade/cabinet/CabinetFrame.java']
 probe=ROOT/'tools/qa/NativeSnapshotBoundaryProbe.java'
 inputs=[*(v[0]for v in PINNED.values()),a.kof97,a.mslug2,a.bios,probe,Path(__file__).resolve(),*([a.fc,a.native]if a.fc else sources)]
 before={str(x.resolve()):identity(x)for x in inputs}
 for path,digest in PINNED.values():assert sha(path)==digest,'Frozen runtime changed'
 for path in [a.kof97,a.mslug2,a.bios]:assert sha(path)==ROMS[path.name],'ROM/BIOS exact whitelist mismatch'
 started=time.monotonic()
 with tempfile.TemporaryDirectory(prefix='piq-snapshot-adapter-qa-')as folder:
  temp=Path(folder);classes=temp/'classes';classes.mkdir();roms=temp/'roms';roms.mkdir();runtime=temp/'runtime-snapshot-v1';runtime.mkdir()
  for name,(source,digest)in PINNED.items():shutil.copyfile(source,runtime/name);assert sha(runtime/name)==digest
  for path in [a.kof97,a.mslug2,a.bios]:shutil.copyfile(path,roms/path.name);assert sha(roms/path.name)==ROMS[path.name]
  cp=os.pathsep.join(map(str,[classes,*([a.fc.resolve(),a.native.resolve()]if a.fc else [])]))
  command=[JDK/'javac.exe','--release','21','-encoding','UTF-8','-proc:none','-implicit:none','-cp',cp,'-d',classes,*([]if a.fc else sources),probe]
  built=subprocess.run(list(map(str,command)),capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=50)
  assert built.returncode==0,built.stdout+built.stderr
  env=os.environ.copy();env['PIQ_QA_NATIVE_ORIGIN']=str(a.native.resolve()if a.native else classes);env['PIQ_QA_FC_ORIGIN']=str(a.fc.resolve()if a.fc else classes)
  executed=subprocess.run([str(JDK/'java.exe'),'-Xmx512m','-cp',cp,'cn.piq.nativearcade.qa.NativeSnapshotBoundaryProbe',str(runtime),str(roms/'kof97.zip'),str(roms/'mslug2.zip')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=450,env=env)
  print(executed.stdout,flush=True)
  if executed.stderr:print(executed.stderr[-12000:],file=sys.stderr,flush=True)
  lines=[x for x in executed.stdout.splitlines()if x.startswith('{')];assert lines,executed.stdout+executed.stderr
  result=json.loads(lines[-1]);result['process_exit_code']=executed.returncode
 assert before=={str(x.resolve()):identity(x)for x in inputs},'Originals/source fence changed'
 result.update(schema='piq-native-snapshot-boundary-1',mode='final-jar-only'if a.fc else'production-source-subset',production_compiled=not bool(a.fc),input_identity=before,originals_unchanged=True,elapsed_seconds=round(time.monotonic()-started,3),limitations=['Actual fixed native core in two independent parent/child JVM pairs; no Minecraft world, socket or user saves.','Only the two exact ROM+BIOS digests are qualified; not arbitrary MAME titles.','Full opaque state compared, including bootstrap frame zero; no normalization or ignored bytes.'])
 if a.fc:result['jars']={k:{'path':str(v.resolve()),'sha256':sha(v)}for k,v in [('fc',a.fc),('native',a.native)]}
 a.report.parent.mkdir(parents=True,exist_ok=True)
 with a.report.open('x',encoding='utf-8')as out:json.dump(result,out,ensure_ascii=False,indent=2)
 print(json.dumps({'ok':result['ok'],'report':str(a.report.resolve()),'assertions':result['assertions']},ensure_ascii=False))
 if not result['ok']or executed.returncode:raise SystemExit(2)
if __name__=='__main__':main()
