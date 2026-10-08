"""Bounded original-runtime mocks in fresh JVMs; never load an emulator core."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import time

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest().upper()

def malformed(source,kind):
    data=bytearray(source)
    if kind=='truncated':return bytes(data[:31])
    pe=struct.unpack_from('<I',data,0x3c)[0]
    optional=pe+24
    if kind=='wrong-machine':struct.pack_into('<H',data,pe+4,0x14c)
    if kind=='bad-import':
        count=struct.unpack_from('<H',data,pe+6)[0]
        section_table=optional+struct.unpack_from('<H',data,pe+20)[0]
        def raw(rva):
            for index in range(count):
                at=section_table+index*40
                address,size,pointer=struct.unpack_from('<III',data,at+12)
                if address<=rva<address+size:return pointer+rva-address
            raise ValueError('Fixture RVA not found')
        directory=struct.unpack_from('<I',data,optional+112+8)[0]
        name=struct.unpack_from('<I',data,raw(directory)+12)[0]
        offset=raw(name)
        data[offset:offset+9]=b'evil.dll\0'
    if kind=='bad-delay':
        count=struct.unpack_from('<H',data,pe+6)[0]
        table=optional+struct.unpack_from('<H',data,pe+20)[0]
        for index in range(count):
            at=table+index*40
            virtual,address,size,pointer=struct.unpack_from('<IIII',data,at+8)
            start=(virtual+7)&~7
            if size-start>=96:
                name=address+start+64
                struct.pack_into('<IIIIIIII',data,pointer+start,1,name,0,0,0,0,0,0)
                data[pointer+start+32:pointer+start+64]=bytes(32)
                data[pointer+start+64:pointer+start+73]=b'evil.dll\0'
                struct.pack_into('<II',data,optional+112+13*8,address+start,64)
                return bytes(data)
        raise ValueError('Fixture has no bounded delay-directory padding')
    return bytes(data)

def main():
    ap=argparse.ArgumentParser()
    for name in ('compiler','jdk','bridge','timeout-tool','output'):ap.add_argument('--'+name,required=True,type=Path)
    args=ap.parse_args();here=Path(__file__).resolve().parent;out=args.output.resolve()
    if out.exists():raise SystemExit('Output must be new; preserve earlier evidence')
    for path in (args.compiler,args.bridge,args.timeout_tool,args.jdk/'bin/java.exe',args.jdk/'bin/javac.exe'):
        if not path.is_file():raise SystemExit('Required tool/input missing')
    out.mkdir(parents=True);(out/'classes').mkdir();(out/'tmp').mkdir()
    env=os.environ.copy();env['TMP']=env['TEMP']=str(out/'tmp')
    env['PATH']=str(args.compiler.resolve().parent)+os.pathsep+str(Path(os.environ['SystemRoot'])/'System32')
    receipt={'status':'running','scope':'original-mock-JVMs-only','bridge_sha256':sha(args.bridge),'runs':[],
             'sources':{name:sha(here/name) for name in ('runtime_mock.cpp','runtime_observer.cpp','runtime_validation_test.cpp','RuntimeDependencyProbe.java','NativeLibretroBridge.java')}}
    def run(command,name,seconds,worker_env=env):
        began=time.monotonic();row={'name':name,'command':[str(x) for x in command]}
        with (out/(name+'.log')).open('xb') as log:
            child=subprocess.Popen([str(x) for x in command],cwd=out,env=worker_env,stdout=log,stderr=subprocess.STDOUT,
                                   creationflags=getattr(subprocess,'BELOW_NORMAL_PRIORITY_CLASS',0))
            row['pid']=child.pid
            try:row['exit_code']=child.wait(timeout=seconds)
            except BaseException:
                child.kill();child.wait();row['timed_out']=True;raise
            finally:
                row.update(child_reaped=child.poll() is not None,seconds=round(time.monotonic()-began,3));receipt['runs'].append(row)
                (out/'receipt.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8')
        if row['exit_code']:raise RuntimeError(name+' failed; see preserved log')
    try:
        common=[args.compiler,'-std=c++20','-O2','-static','-shared','-Wl,--no-insert-timestamp']
        run([args.timeout_tool,'--signal=TERM','--kill-after=2s','20s',args.compiler,'-std=c++20','-O2','-static',
             '-Wall','-Wextra','-Wl,--no-insert-timestamp','-I'+str(args.jdk/'include'),'-I'+str(args.jdk/'include/win32'),
             here/'runtime_validation_test.cpp','-o',out/'runtime-validation.exe','-lopengl32','-lgdi32','-luser32','-lbcrypt'],'compile-validation',24)
        run([out/'runtime-validation.exe'],'validation',10)
        for name,extra in (('good.dll',[]),('fail.dll',['-DPIQ_RUNTIME_REJECT_ATTACH=1'])):
            run([args.timeout_tool,'--signal=TERM','--kill-after=2s','20s',*common,*extra,here/'runtime_mock.cpp','-o',out/name],'compile-'+name,24)
        run([args.timeout_tool,'--signal=TERM','--kill-after=2s','20s',*common,
             '-I'+str(args.jdk/'include'),'-I'+str(args.jdk/'include/win32'),here/'runtime_observer.cpp','-o',out/'observer.dll'],'compile-observer',24)
        run([args.jdk/'bin/javac.exe','-encoding','UTF-8','-d',out/'classes',here/'NativeLibretroBridge.java',here/'RuntimeDependencyProbe.java'],'javac',30)
        good=(out/'good.dll').read_bytes()
        modes=('success','pair','concurrent','foreign','identity-change','second-failure','second-invalid',
               'wrong-sha','relative','unknown-name','truncated','wrong-machine','bad-import','bad-delay',
               'late-foreign','ready-open','failed-open')
        for mode in modes:
            work=out/mode;work.mkdir()
            cpp=work/'libc++.dll'
            if mode=='second-failure':shutil.copyfile(out/'fail.dll',cpp)
            elif mode in ('truncated','wrong-machine','bad-import','bad-delay'):cpp.write_bytes(malformed(good,mode))
            else:shutil.copyfile(out/'good.dll',cpp)
            shutil.copyfile(out/'good.dll',work/'libunwind.dll')
            if mode in ('foreign','late-foreign'):(work/'foreign').mkdir();shutil.copyfile(out/'good.dll',work/'foreign/libc++.dll')
            if mode in ('late-foreign','ready-open','failed-open'):
                shutil.copyfile(out/'good.dll',work/'core-entry.dll');(work/'game.bin').write_bytes(b'\x00')
            if mode=='unknown-name':shutil.copyfile(out/'good.dll',work/'other.dll')
            worker_env=env.copy();worker_env['PIQ_RUNTIME_TEST_MARKER']=str(work/'entry.marker')
            run([args.jdk/'bin/java.exe','-Xms16m','-Xmx96m','-Xcheck:jni','-cp',out/'classes',
                 'cn.piq.retro.libretro.jni.RuntimeDependencyProbe',args.bridge.resolve(),out/'observer.dll',work,mode],mode,10,worker_env)
        receipt['status']='passed-isolated-mock-tests'
    except BaseException as exc:
        receipt.update(status='failed',error_type=type(exc).__name__,error=str(exc));raise
    finally:
        receipt['bridge_unchanged']=sha(args.bridge)==receipt['bridge_sha256']
        (out/'receipt.json').write_text(json.dumps(receipt,indent=2),encoding='utf-8')
        print(json.dumps({k:v for k,v in receipt.items() if k not in ('runs','sources')}))

if __name__=='__main__':main()
