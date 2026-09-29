"""Compile/run only a timing probe against frozen SFC6, with the existing original test ROM. No production rebuild."""
import argparse,hashlib,json,os,subprocess,tempfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
JAVA=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
SFC=ROOT.parent/'制作Mod/03-街机模拟/PIQ-SFC街机/piq_sfc_arcade-0.2.0-alpha.6.jar'
FC=ROOT.parent/'制作Mod/03-街机模拟/PIQ-FC街机/alpha16-rom-browser/piq_fc_arcade-0.31.0-alpha.16.jar'
def run(cmd,seconds=60):
    p=subprocess.run(list(map(str,cmd)),cwd=ROOT,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=seconds)
    if p.returncode:raise AssertionError(p.stdout+'\n'+p.stderr)
    return p.stdout
def main():
    parser=argparse.ArgumentParser();parser.add_argument('--report',required=True,type=Path);args=parser.parse_args()
    if args.report.exists():raise ValueError('Do not replace existing timing evidence')
    before={str(p):hashlib.sha256(p.read_bytes()).hexdigest().upper() for p in [SFC,FC]}
    assert before[str(SFC)]=='38FA46C5D283EAD9E1F6666D01398F495E2E1A3260A1517BE8EE959710963363'
    fixture=ROOT.parent/'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/core/SfcLegalTestRom.java'
    with tempfile.TemporaryDirectory(prefix='sfc-startup-timing-') as folder:
        classes=Path(folder)/'classes';classes.mkdir();empty=Path(folder)/'empty';empty.mkdir()
        cp=os.pathsep.join(map(str,[classes,SFC,FC]))
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',classes,ROOT/'tools/qa/SfcStartupCoreProbe.java',fixture])
        actual=json.loads(run([JAVA/'java.exe','-cp',cp,'cn.piq.sfcarcade.core.SfcStartupCoreProbe'],120).strip())
    assert before=={str(p):hashlib.sha256(p.read_bytes()).hexdigest().upper() for p in [SFC,FC]}
    report={'passed':actual['passed'],'frozen_jars_sha256':before,'actual':actual,'limits':['Two sequential core instances in one fresh JVM; first instance includes cold native/class initialization.','Tiny original 32 KiB ROM only; not representative of every commercial cartridge, server transfer or Minecraft render load.','No emulator internals altered or skipped; stage measurements do not demonstrate removal of cold-start cost.','No audio device, server ready barrier, network download or texture upload timing included.']}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps(report,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
