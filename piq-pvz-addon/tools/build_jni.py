"""Build a separate experimental JNI bridge; never overwrite the process host/core."""
import pathlib,subprocess,hashlib,json
ROOT=pathlib.Path(__file__).resolve().parents[1]
compiler=ROOT.parent/'outputs/libretro64/retroarch-poc/toolchain/llvm-mingw-20250910-ucrt-x86_64/bin/x86_64-w64-mingw32-clang++.exe'
jdk=pathlib.Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot')
target=ROOT/'src/main/resources/core/pvz/piq-pvz-jni.dll'
args=[str(compiler),'-O2','-std=c++20','-static','-shared','-Wl,--no-insert-timestamp','-I'+str(jdk/'include'),'-I'+str(jdk/'include/win32'),'-I'+str(ROOT/'vendor/PvZ-Portable/src/SexyAppFramework/platform/libretro'),str(ROOT/'native/pvz_jni.cpp'),'-o',str(target),'-lopengl32','-lgdi32','-luser32']
subprocess.run(args,check=True)
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest().upper()
receipt=dict(path=str(target),sha256=sha(target),bytes=target.stat().st_size,sourceSha=sha(ROOT/'native/pvz_jni.cpp'),compiler=str(compiler),args=args)
(ROOT/'build/jni-receipt.json').write_text(json.dumps(receipt,indent=2),'utf8');print(json.dumps(receipt,ensure_ascii=True))
