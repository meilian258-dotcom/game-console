"""Pinned Mesen r2 corresponding-source build. Python3 + LLVM-MinGW, no private paths."""
from pathlib import Path
import sys,zipfile,hashlib,re,subprocess,concurrent.futures,json,os
archive,tool,out=map(lambda p:Path(p).resolve(),sys.argv[1:4]);out.mkdir(parents=True,exist_ok=False)
assert hashlib.sha256(archive.read_bytes()).hexdigest()=='375a885c397685a5aad82bd14f68e1b30d8e78405471344629a6f2a04c8a9e04'
with zipfile.ZipFile(archive) as z:
    for i in z.infolist():assert (out/i.filename).resolve().is_relative_to(out)
    z.extractall(out)
src=next(p for p in out.iterdir() if p.is_dir());control=src/'Core/ControlManager.cpp';text=control.read_text(encoding='utf-8')
old='Stream(nesModel, expansionDevice, consoleType, types, hasFourScore, useNes101Hvc101Behavior, zapperDetectionRadius, _lagCounter, _pollCounter);'
assert text.count(old)==1
control.write_text(text.replace(old,old[:-2]+', _isLagging);'),encoding='utf-8')
# BaseMapper maps PRG RAM as read/write before InitMapper. VRC7 initializes its
# enable register to zero, but used to apply that register only on a write/load.
# Initialize the mapping too: restoring an untouched startup state must not
# silently change the 32 CPU-page access flags ($6000-$7fff) from 3 to 0.
vrc7=src/'Core/VRC7.h';text=vrc7.read_text(encoding='utf-8')
old='\t\tSelectPRGPage(3, -1);'
assert text.count(old)==1
vrc7.write_text(text.replace(old,old+'\n\t\tUpdatePrgRamAccess();'),encoding='utf-8')
# A mapped bank with NoAccess is not an unmapped bank. Restore its descriptor
# and access flags, instead of Remove*Mapping discarding the bank offset/type.
# MMC3 can keep WRAM disabled across many frames; waiting longer cannot fix it.
mapper=src/'Core/BaseMapper.cpp';text=mapper.read_text(encoding='utf-8')
for bus in ('prg','chr'):
    old=f'if(_{bus}MemoryAccess[i] != MemoryAccessType::NoAccess) {{'
    assert text.count(old)==1
    text=text.replace(old,f'if(_{bus}MemoryAccess[i] != MemoryAccessType::NoAccess || _{bus}MemoryOffset[i] >= 0) {{')
mapper.write_text(text,encoding='utf-8')
paths=[src/{'LIBRETRO':'Libretro','CORE':'Core','UTIL':'Utilities','SEVENZIP':'SevenZip'}[d]/n for d,n in re.findall(r'\$\((\w+)_DIR\)/([^\s\\]+)',(src/'Libretro/Makefile.common').read_text())]
env=os.environ.copy();env['PATH']=str(tool)+os.pathsep+env['PATH']
def one(pair):
    n,p=pair;obj=out/(str(n)+'.o');cpp=p.suffix=='.cpp'
    cmd=[str(tool/('x86_64-w64-mingw32-clang++.exe' if cpp else 'x86_64-w64-mingw32-clang.exe')),'-O2','-DLIBRETRO','-DNDEBUG','-c',str(p),'-o',str(obj)]+(['-std=c++11'] if cpp else [])
    r=subprocess.run(cmd,capture_output=True,env=env);(out/(str(n)+'.log')).write_bytes(r.stdout+r.stderr);assert r.returncode==0,p;return str(obj)
with concurrent.futures.ThreadPoolExecutor(max_workers=4) as pool:objects=list(pool.map(one,enumerate(paths)))
dll=out/'mesen_piq_jni_netplay_r2.dll';subprocess.run([str(tool/'x86_64-w64-mingw32-clang++.exe'),'-shared','-static','-s','-o',str(dll),*objects,'-lwinmm','-lshlwapi'],check=True,env=env)
(out/'build.json').write_text(json.dumps({'sha256':hashlib.sha256(dll.read_bytes()).hexdigest(),'objects':len(objects)},indent=2))
