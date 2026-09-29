"""Compile only isolated Zapper bridge/probes and execute actual WASM, never a Minecraft client."""
import argparse,hashlib,json,os,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
def run(args):
    p=subprocess.run(list(map(str,args)),cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
    if p.returncode:raise AssertionError(p.stdout+'\n'+p.stderr)
    return p.stdout
def main():
    ap=argparse.ArgumentParser();ap.add_argument('--fc',required=True,type=Path);ap.add_argument('--report',required=True,type=Path);a=ap.parse_args()
    if a.report.exists():raise ValueError('Refusing to overwrite evidence')
    sources=[ROOT/'src/main/java/cn/piq/fcarcade/core/NesCore.java',ROOT/'src/main/java/cn/piq/fcarcade/core/wasm/ZapperWasmNesCore.java',ROOT/'tools/qa/ZapperCoreProbe.java']
    with tempfile.TemporaryDirectory(prefix='piq-zapper-core-') as folder:
        out=Path(folder);cp=os.pathsep.join(map(str,[out,ROOT/'src/main/resources',a.fc.resolve()]))
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-cp',cp,'-d',out,*sources])
        result=run([JAVA/'java.exe','-cp',cp,'cn.piq.fcarcade.core.wasm.ZapperCoreProbe'])
    report={'ok':True,'result':result,'minecraft_started':False,'source_sha256':{str(p.relative_to(ROOT)):hashlib.sha256(p.read_bytes()).hexdigest().upper()for p in sources},'module_sha256':hashlib.sha256((ROOT/'src/main/resources/core/nes_zapper_v1.wasm').read_bytes()).hexdigest().upper(),'legacy_module_sha256':hashlib.sha256((ROOT/'src/main/resources/core/nes_rust_wasm_bg.wasm').read_bytes()).hexdigest().upper()}
    a.report.parent.mkdir(parents=True,exist_ok=True)
    with a.report.open('x',encoding='utf-8')as f:json.dump(report,f,indent=2,ensure_ascii=False)
    print(json.dumps(report,indent=2,ensure_ascii=False))
if __name__=='__main__':main()
