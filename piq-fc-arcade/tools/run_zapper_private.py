"""Private read-only supplied-ROM smoke. Generated images must not be included in mod packages."""
import argparse,os,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1];JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
def run(cmd):
    p=subprocess.run(list(map(str,cmd)),cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
    if p.returncode:raise AssertionError(p.stdout+'\n'+p.stderr)
    return p.stdout
def main():
    p=argparse.ArgumentParser();p.add_argument('--fc',required=True,type=Path);p.add_argument('--rom',required=True,type=Path);p.add_argument('--output',required=True,type=Path);p.add_argument('--shoot',type=int,default=-1);p.add_argument('--x',type=int,default=128);p.add_argument('--y',type=int,default=100);p.add_argument('--offscreen',action='store_true');a=p.parse_args()
    if a.output.exists():raise ValueError('Private output must be a new directory')
    with tempfile.TemporaryDirectory(prefix='piq-private-zapper-')as folder:
        out=Path(folder);cp=os.pathsep.join(map(str,[out,ROOT/'src/main/resources',a.fc.resolve()]))
        src=[ROOT/'src/main/java/cn/piq/fcarcade/core/NesCore.java',ROOT/'src/main/java/cn/piq/fcarcade/core/wasm/ZapperWasmNesCore.java',ROOT/'tools/qa/ZapperPrivateRomProbe.java']
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-cp',cp,'-d',out,*src])
        print(run([JAVA/'java.exe','-cp',cp,'cn.piq.fcarcade.core.wasm.ZapperPrivateRomProbe',a.rom.resolve(),a.output.resolve(),a.shoot,a.x,a.y,str(a.offscreen).lower()]))
if __name__=='__main__':main()
