"""Read-only public-core interoperability check against frozen SFC alpha6 and FC alpha13 JARs."""
import argparse,hashlib,json,subprocess,tempfile,zipfile
from pathlib import Path
from datetime import datetime,timezone

ROOT=Path(__file__).resolve().parents[1]
SFC=ROOT.parent/'piq-sfc-arcade'
SFC_JAR=SFC/'build/libs/piq_sfc_arcade-0.2.0-alpha.6.jar'
DELIVERY=ROOT.parent/'制作Mod/03-街机模拟/PIQ-FC街机'
FC_JAR=DELIVERY/'piq_fc_arcade-0.31.0-alpha.13.jar'
JDK=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
EXPECTED_SFC='38FA46C5D283EAD9E1F6666D01398F495E2E1A3260A1517BE8EE959710963363'
EXPECTED_FC='681D1BE79DD87B67D64034FAE18CC022E942F6C6887E335A5CB70C05C87D2342'

def sha(data):return hashlib.sha256(data).hexdigest().upper()
def require(ok,message):
    if not ok:raise ValueError(message)

def check():
    require(sha(SFC_JAR.read_bytes())==EXPECTED_SFC,'Frozen SFC alpha6 JAR changed')
    require(sha(FC_JAR.read_bytes())==EXPECTED_FC,'Frozen FC alpha13 JAR changed')
    sources=[SFC/'src/test/java/cn/piq/sfcarcade/core'/n for n in
        ('SfcLegalTestRom.java','SfcWasmRuntimeSmoke.java','SfcCoreSelfTest.java')]
    sources += [ROOT/'tools/qa'/n for n in ('SfcReuseProbe.java','SfcTwoPortInputProbe.java')]
    with zipfile.ZipFile(SFC_JAR) as archive:
        require(not any(n.startswith('ai/tegmentum/wasmtime4j/') for n in archive.namelist()),'Duplicate Wasmtime bundled')
        wasm=archive.read('assets/piq_sfc_arcade/core/piq_sfc_wasm.wasm')
        require(wasm[:8]==b'\0asm\x01\0\0\0','WASM header invalid')
        classes={n:sha(archive.read(n)) for n in archive.namelist() if n.startswith('cn/piq/sfcarcade/core/') and n.endswith('.class')}
    report={'ok':False,'checked_at_utc':datetime.now(timezone.utc).isoformat(),
        'sfc_jar':str(SFC_JAR),'sfc_jar_sha256':EXPECTED_SFC,'fc_runtime_jar':str(FC_JAR),'fc_jar_sha256':EXPECTED_FC,
        'wasm_sha256':sha(wasm),'wasm_bytes':len(wasm),'core_class_hashes':classes,
        'qa_source_hashes':{str(p):sha(p.read_bytes()) for p in sources},'runs':{}}
    with tempfile.TemporaryDirectory(prefix='sfc-public-core-reuse-') as directory:
        cp=';'.join(map(str,(SFC_JAR,FC_JAR,Path(directory))))
        result=subprocess.run([str(JDK/'javac.exe'),'-encoding','UTF-8','-proc:none','-sourcepath',directory,
            '-cp',cp,'-d',directory,*map(str,sources)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
        require(result.returncode==0,'QA-only compilation failed: '+result.stderr)
        require(not (Path(directory)/'cn/piq/sfcarcade/core/wasm/WasmSfcCore.class').exists(),'Never compile production replacement')
        for name in ('SfcCoreSelfTest','SfcWasmRuntimeSmoke','SfcTwoPortInputProbe','SfcReuseProbe'):
            result=subprocess.run([str(JDK/'java.exe'),'-cp',cp,'cn.piq.sfcarcade.core.'+name],
                capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=45)
            require(result.returncode==0,name+' failed: '+result.stderr)
            out=result.stdout.strip()
            report['runs'][name]=json.loads(out) if out.startswith('{') else {'ok':True,'output':out}
    require(sha(SFC_JAR.read_bytes())==EXPECTED_SFC and sha(FC_JAR.read_bytes())==EXPECTED_FC,'A frozen JAR changed during probe')
    report['ok']=True
    report['limits']=[
        'Only original in-memory homebrew is used. No commercial ROM, enhancement-chip game, PAL/interlace game or Minecraft scene was validated.',
        'RTC is intentionally frozen to Unix epoch in the existing core; no real-time cartridge-clock support is claimed.',
        'loadState restores native state, but not the Java emulatedFrameNumber; a new session adapter must track its own frame index.',
        'The public wrapper limits ROM/audio/frame/state/SRAM blobs and validates WASM pointers; it does not configure Wasmtime fuel or a store memory limiter.',
        'All public-core calls should remain owned by a single emulation worker. Paired deterministic tests are not network multiplayer tests.',
        'The fixtures generate audio clock PCM (silent), not audible music; ROM-only empty SRAM roundtrip is checked, not battery-save gameplay.',
        'Existing SFC and FC production code, native modules and delivered JARs were not changed. Only temporary QA classes were compiled.']
    return report

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--report',type=Path,required=True);args=parser.parse_args()
    args.report.resolve().relative_to((DELIVERY/'SFC家用附属-核心复用验证').resolve())
    require(not args.report.exists(),'Never overwrite a prior report')
    report=check();args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8') as stream:json.dump(report,stream,ensure_ascii=False,indent=2);stream.write('\n')
    print(json.dumps({'ok':True,'runs':report['runs'],'report_sha256':sha(args.report.read_bytes()),'wasm_sha256':report['wasm_sha256']},ensure_ascii=False))
