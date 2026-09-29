"""Compile a synthetic core and independent ABI caller; run only an isolated test JVM."""
import argparse, json, pathlib, subprocess, hashlib

def main():
    ap=argparse.ArgumentParser()
    for name in ('compiler','jdk','bridge','output'): ap.add_argument('--'+name,required=True,type=pathlib.Path)
    a=ap.parse_args(); here=pathlib.Path(__file__).resolve().parent; out=a.output.resolve()
    if out.exists(): raise SystemExit('Use a new output directory')
    out.mkdir(parents=True); classes=out/'classes'; classes.mkdir(); work=out/'work'; work.mkdir()
    for mode in (0,7,8,9,10,11,32,33,34,35): (work/('normal.bin' if mode==0 else f'mode{mode}.bin')).write_bytes(bytes([mode])+bytes(63))
    (work/'中文测试.bin').write_bytes(bytes(64))
    commands=[
      [str(a.compiler),'-std=c++20','-O2','-static','-shared','-Wl,--no-insert-timestamp',str(here/'mock_core.cpp'),'-o',str(out/'mock.dll'),'-lopengl32'],
      [str(a.jdk/'bin/javac.exe'),'-encoding','UTF-8','-d',str(classes),str(here/'NativeLibretroBridge.java')],
      [str(a.jdk/'bin/java.exe'),'-Xcheck:jni','-cp',str(classes),'cn.piq.retro.libretro.jni.NativeLibretroBridge',str(a.bridge),str(out/'mock.dll'),str(work)],
      [str(a.jdk/'bin/java.exe'),'-Xcheck:jni','-cp',str(classes),'cn.piq.retro.libretro.jni.NativeLibretroBridge',str(a.bridge),str(out/'mock.dll'),str(work),'startup-cleanup-failure']
    ]
    receipt={'bridgeSha256':hashlib.sha256(a.bridge.read_bytes()).hexdigest().upper(),'runs':[]}
    for i,cmd in enumerate(commands):
        run=subprocess.run(cmd,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,timeout=90)
        (out/f'step-{i}.log').write_text(run.stdout,encoding='utf-8')
        receipt['runs'].append({'command':cmd,'exitCode':run.returncode})
        (out/'receipt.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8')
        print(run.stdout)
        if run.returncode: raise SystemExit(run.returncode)

if __name__=='__main__': main()
