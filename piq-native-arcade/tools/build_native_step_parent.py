"""Build a private pure-Java parent IPC prototype, not a MOD or production delivery."""
import argparse,hashlib,json,re,shutil,subprocess,sys,tempfile,zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
JDK=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
HELPER=ROOT/'build/native-step-prototype-v1/piq-native-step-helper.jar'
HELPER_SHA='AA515AB5E4981EFEA667FBDFFA839B13FE035A4C40F1A6B1F15B7E9711CF7E77'
BASE=ROOT.parent/'piq-fc-arcade/build/review-controls26-v2/piq_native_arcade-0.1.0-alpha.10.jar'
BASE_SHA='F4011CBDE8DC3F3F3FA44FD03077638421C7D3334B33A55AFFB0E78C740C2453'
PREFIX='cn/piq/nativearcade/bridge/'
NAMES=('NativeStepSession','NativeProcessSession','NativeRomStaging','NativeStepProtocol','BridgeProtocol','NativeInputPorts','NativeArcadeButtons')
IMMUTABLE=('BridgeProtocol','NativeInputPorts','NativeArcadeButtons','NativeStepProtocol')
sys.path.insert(0,str(ROOT.parent/'piq-fc-arcade/tools'))
from verify_device_ui_alpha18 import disassemble,methods

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest().upper()
def blob(b):return hashlib.sha256(b).hexdigest().upper()
def require(value,message):
    if not value:raise AssertionError(message)
def entries(path):
    with zipfile.ZipFile(path)as jar:
        names=jar.namelist();require(len(names)==len(set(names)),'Duplicate members');require(jar.testzip()is None,'CRC')
        return{n:jar.read(n)for n in names if not n.endswith('/')}
def stem(name):return name[len(PREFIX):].split('$')[0].removesuffix('.class')if name.startswith(PREFIX)else ''
def stable(raw):
    result={}
    for signature,body in methods(raw).items():
        # Debug tables are not emitted by javap -c. Pool identities are symbolic comments.
        key=re.sub(r'(lambda\$[^$]+\$)\d+',r'\1N',signature)
        body=re.sub(r'(lambda\$[^$]+\$)\d+',r'\1N',body)
        result.setdefault(key,[]).append(body)
    return{k:sorted(v)for k,v in result.items()}

def main():
    sys.stdout.reconfigure(encoding='utf-8')
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--report',type=Path,required=True);a=p.parse_args()
    output=a.output.resolve();report=a.report.resolve()
    require(not output.exists()and not report.exists(),'Exclusive new outputs required')
    sources=[ROOT/'src/main/java'/PREFIX/(n+'.java')for n in NAMES]
    guarded=[*sources,HELPER,BASE,Path(__file__).resolve()]
    before={str(p):sha(p)for p in guarded}
    require(before[str(HELPER)]==HELPER_SHA,'Pinned step helper changed');require(before[str(BASE)]==BASE_SHA,'Pinned legacy MOD changed')
    with tempfile.TemporaryDirectory(prefix='piq-step-parent-build-')as folder:
        tmp=Path(folder);classes=tmp/'classes';classes.mkdir();empty=tmp/'empty';empty.mkdir()
        command=[JDK/'javac.exe','-encoding','UTF-8','--release','21','-proc:none','-implicit:none','-sourcepath',empty,'-d',classes,*sources]
        compiled=subprocess.run(list(map(str,command)),capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=40)
        require(compiled.returncode==0,compiled.stdout+compiled.stderr)
        actual={p.relative_to(classes).as_posix():p.read_bytes()for p in classes.rglob('*.class')}
        require(all(n.endswith('.class')and stem(n)in NAMES for n in actual),'Unexpected compiled class')
        require(all(PREFIX+n+'.class'in actual for n in NAMES),'Missing selected class')
        helper=entries(HELPER);immutable={}
        for n in IMMUTABLE:
            expected={k for k in helper if k.endswith('.class')and stem(k)==n}
            found={k for k in actual if stem(k)==n}
            require(found==expected and bool(found),'Immutable class inventory changed: '+n)
            for k in found:
                require(actual[k]==helper[k],'Immutable bytecode changed: '+k)
                immutable[k]=blob(actual[k])
        old_ascii=tmp/'baseline.jar';shutil.copyfile(BASE,old_ascii);require(sha(old_ascii)==BASE_SHA,'Staged baseline mismatch')
        new_methods=stable(disassemble(classes,JDK/'javap.exe','cn.piq.nativearcade.bridge.NativeProcessSession'))
        old_methods=stable(disassemble(old_ascii,JDK/'javap.exe','cn.piq.nativearcade.bridge.NativeProcessSession'))
        changed=[k for k,v in old_methods.items()if new_methods.get(k)!=v]
        require(not changed,'Old NativeProcessSession methods changed: '+repr(changed))
        added=set(new_methods)-set(old_methods)
        expected_added={'static void acquireStepSlot() throws java.io.IOException;','static java.lang.Process launchStepChild(java.lang.ProcessBuilder) throws java.io.IOException;','static void releaseStepSlot(java.lang.Process);'}
        require(added==expected_added,'Unexpected added parent methods: '+repr(added))
        require(before=={str(p):sha(p)for p in guarded},'Build source/input fence changed')
        manifest=b'Manifest-Version: 1.0\r\nPIQ-Artifact-Kind: private-step-parent-prototype-not-a-mod\r\n\r\n'
        actual['META-INF/MANIFEST.MF']=manifest
        staged=tmp/'parent.jar'
        with zipfile.ZipFile(staged,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=9)as jar:
            for name,data in sorted(actual.items()):
                info=zipfile.ZipInfo(name,(2026,9,12,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;jar.writestr(info,data,compresslevel=9)
        require(entries(staged)==actual,'JAR roundtrip mismatch')
        require(not any(n.endswith(('.toml','.dll','.wasm','.zip'))or n.startswith('assets/')for n in actual),'Unexpected MOD/runtime/resource')
        require(before=={str(p):sha(p)for p in guarded},'Final source/input fence changed')
        output.parent.mkdir(parents=True,exist_ok=True)
        with output.open('xb')as out:out.write(staged.read_bytes())
        require(sha(output)==sha(staged),'Published new prototype mismatch')
    result={'ok':True,'artifact_kind':'private-step-parent-prototype-not-a-mod','production_delivery':False,'production_sources_compiled_for_private_prototype':True,
        'jar':{'path':str(output),'sha256':sha(output),'bytes':output.stat().st_size},'fixed_step_helper':{'path':str(HELPER),'sha256':HELPER_SHA,'modified':False},
        'baseline_native_mod':{'path':str(BASE),'sha256':BASE_SHA},'source_sha256':{str(p):before[str(p)]for p in sources},'input_fence_sha256':before,
        'source_fence_passed':True,'immutable_compiled_entries_equal_fixed_helper':immutable,'old_parent_methods_unchanged':len(old_methods),'added_package_private_parent_methods':sorted(added),
        'members_sha256':{n:blob(b)for n,b in actual.items()},'limitations':['Private non-MOD parent prototype; no registrars, resources, native DLL, ROM or BIOS are packaged.','Old parent symbolic instructions and immutable helper bytecode preserved; this is not a gameplay or MAME snapshot qualification.','No user-instance installation, production helper replacement, source edit or Gradle invocation.']}
    require(before=={str(p):sha(p)for p in guarded},'Postbuild input fence changed')
    report.parent.mkdir(parents=True,exist_ok=True)
    with report.open('x',encoding='utf-8')as out:json.dump(result,out,ensure_ascii=False,indent=2)
    print(json.dumps({'ok':True,'jar':result['jar'],'report':str(report),'old_parent_methods_unchanged':len(old_methods)},ensure_ascii=False))
if __name__=='__main__':main()
