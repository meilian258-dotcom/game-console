"""One bounded shared original core build/JVM; reuses verified production classes."""
import argparse,json,os
from pathlib import Path
from run_production_runtime_load import ROOT,HERE,digest,no_links,copy_verified,run

def main():
    p=argparse.ArgumentParser(description=__doc__)
    for key in ('previous','tool-pins','compiler','observer','output','jdk'):
        p.add_argument('--'+key,type=Path,required=True)
    p.add_argument('--observer-sha256',required=True)
    a=p.parse_args(); out=no_links(a.output)
    if os.name!='nt' or out.exists() or not str(out).isascii():raise ValueError('New ASCII Windows output required')
    previous=no_links(a.previous); old=json.loads((previous/'receipt.json').read_text())
    if not old.get('passed'):raise ValueError('Production classes were not validated')
    for name,sha in old['production_sources'].items():
        if digest(ROOT/name)!=sha:raise ValueError('Production source changed')
    pins=json.loads(no_links(a.tool_pins).read_text())
    def tools_ok():
        for name,sha in pins.items():
            if digest(no_links(Path(name)))!=sha.lower():raise ValueError('Tool pin mismatch')
    tools_ok()
    compiler=no_links(a.compiler)
    if str(compiler) not in pins:raise ValueError('Compiler absent from verified pins')
    out.mkdir(); env=dict(os.environ)
    for key in ('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS','CLASSPATH','CPATH','C_INCLUDE_PATH','CPLUS_INCLUDE_PATH','LIBRARY_PATH','COMPILER_PATH'):env.pop(key,None)
    receipt={'scope':'Original shared-libc++ mock, not MAME or Minecraft','passed':False,'runs':[]}
    def save():(out/'receipt.json').write_text(json.dumps(receipt,indent=2)+'\n')
    def execute(label,command,timeout=60):
        result=run(command,out,out/(label+'.log'),timeout,env);receipt['runs'].append(dict(label=label,**result));save()
        if result['exit_code']:raise ValueError(label+' failed; inspect private log')
    core=out/'shared-runtime-mock.dll'
    command=[str(compiler),'-std=c++20','-O2','-shared','-fno-emulated-tls','-stdlib=libc++','--rtlib=compiler-rt','--unwindlib=libunwind','-fuse-ld=lld','-Wl,--no-insert-timestamp',str(HERE/'SharedRuntimeCore.cpp'),'-o',str(core),'-lopengl32']
    execute('compile-core',command)
    execute('imports',[str(compiler.parent/'llvm-readobj.exe'),'--coff-imports',str(core)])
    imports=(out/'imports.log').read_text().lower()
    if 'name: libc++.dll' not in imports or any(word in imports for word in ('libwinpthread','libstdc++','msys-')):raise ValueError('Unexpected shared core linkage')
    execute('symbols',[str(compiler.parent/'llvm-nm.exe'),str(core)])
    if '__emutls_' in (out/'symbols.log').read_text():raise ValueError('Mock did not use native TLS')
    tools_ok()
    resources=out/'resources'; native=resources/'core/libretro-jni/windows-x64'; instance=out/'instance';instance.mkdir()
    for name,key in (('piq-libretro-jni.dll','bridge'),('libc++.dll','runtime')):
        copy_verified(previous/'normal/resources/core/libretro-jni/windows-x64'/name,native/name,old['inputs'][key]['sha256'])
    manifest=(previous/'normal/resources/core/libretro-jni/runtime.properties').read_bytes()
    (native.parent/'runtime.properties').write_bytes(manifest)
    copy_verified(core,resources/'core/shared-runtime-mock.dll',digest(core))
    observer=out/'observer.dll';copy_verified(no_links(a.observer),observer,a.observer_sha256)
    classes=out/'probe-classes';classes.mkdir(); production=previous/'classes'
    execute('compile-probe',[str(a.jdk/'bin/javac.exe'),'-encoding','UTF-8','-proc:none','-cp',str(production),'-d',str(classes),str(HERE/'RuntimeDependencyProbe.java'),str(HERE/'SharedRuntimeProductionProbe.java')])
    execute('public-java',[str(a.jdk/'bin/java.exe'),'-Xmx128m','-Xcheck:jni','-XX:ErrorFile=hs_err_pid%p.log','-cp',os.pathsep.join(map(str,(production,classes,resources))),'cn.piq.retro.libretro.jni.SharedRuntimeProductionProbe',str(instance),str(observer),digest(core),str(production)])
    if 'SHARED_PRODUCTION_OK cycles=3 frames=9 realCoreUnloads=3 runtimeStable=true workspaceClean=true' not in (out/'public-java.log').read_text():raise ValueError('Missing completed checks')
    if list(out.glob('hs_err_pid*.log')):raise ValueError('JVM crash evidence present')
    receipt.update(passed=True,core_sha256=digest(core),runtime_sha256=old['inputs']['runtime']['sha256'],native_tls=True,cycles=3,frames=9)
    save();print(json.dumps({'passed':True,'cycles':3,'frames':9,'scope':'shared mock only'}))

if __name__=='__main__':main()
