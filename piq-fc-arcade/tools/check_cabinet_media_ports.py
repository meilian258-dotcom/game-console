"""Bounded standalone Java21 tests/measurements; no Gradle, Minecraft, game directory or downloads."""

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home, java_home

import argparse, hashlib, json, os, subprocess, tempfile, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
HOME=ROOT.parent/'piq-sfc-home'
RETRO=ROOT.parent/'piq-retro-platform'
JAVA=(java_home() / 'bin')

def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest().upper()
def run(command,cwd):
    result=subprocess.run(list(map(str,command)),cwd=cwd,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
    if result.returncode:raise AssertionError(result.stdout+'\n'+result.stderr)
    return result.stdout
def last_json(text):return json.loads(next(line for line in reversed(text.splitlines()) if line.startswith('{')))

def main():
    sys.stdout.reconfigure(encoding='utf-8');parser=argparse.ArgumentParser();parser.add_argument('--report',type=Path,required=True);args=parser.parse_args()
    if args.report.exists():raise ValueError('Evidence report already exists')
    fc=ROOT.parent/'制作Mod/03-街机模拟/PIQ-FC街机/alpha20-compact-vanilla-ui/piq_fc_arcade-0.31.0-alpha.20.jar'
    sfc=ROOT.parent/'制作Mod/03-街机模拟/PIQ-SFC家用/0.1.0-alpha.8/piq_sfc-0.1.0-alpha.8.jar'
    cache=(gradle_home() / 'caches/modules-2/files-2.1');deps=[]
    for group,version in [('org.junit.platform','1.13.4'),('org.junit.jupiter','5.13.4'),('org.opentest4j','1.3.0'),('org.apiguardian','1.1.2')]:
        deps.extend(p for p in (cache/group).rglob('*.jar') if version in p.parts and '-sources' not in p.name and '-javadoc' not in p.name)
    production=[RETRO/'src/main/java/cn/piq/retro/api'/n for n in ['RetroEmulator.java','RetroFrame.java']]
    production += [ROOT/'src/main/java/cn/piq/fcarcade/cabinet'/n for n in ['CabinetEmulator.java','CabinetFrame.java','CabinetMediaCodec.java']]
    production += [HOME/'src/main/java/cn/piq/sfchome/client/SfcCoreLease.java']+[HOME/'src/main/java/cn/piq/sfchome/client/cabinet'/n for n in ['SfcCabinetInputs.java','SfcCabinetFrames.java','SfcCabinetRom.java','SfcCabinetSession.java']]
    tests=[ROOT/'src/test/java/cn/piq/fcarcade/cabinet'/n for n in ['CabinetMediaCodecTest.java','CabinetRetroAdapterTest.java']]
    tests += [RETRO/'src/test/java/cn/piq/retro/api/RetroMultiplayerCompatibilityTest.java']
    tests += [HOME/'src/test/java/cn/piq/sfchome/client/cabinet'/n for n in ['SfcCabinetPortReleaseTest.java','SfcCabinetInputsTest.java','SfcCabinetSessionTest.java','SfcCabinetSourceContractTest.java']]
    probes=[ROOT/'tools/qa'/n for n in ['CabinetMediaTestRunner.java','CabinetMediaCpuProbe.java']]
    probes += [HOME/'tools/qa/SfcCabinetActualCoreProbe.java',ROOT/'tools/qa/SfcTwoPortInputProbe.java',ROOT.parent/'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/core/SfcLegalTestRom.java']
    hashes={str(p.relative_to(ROOT.parent)):sha(p) for p in production+tests+probes+[Path(__file__).resolve()]}
    jars={str(fc):sha(fc),str(sfc):sha(sfc)}
    with tempfile.TemporaryDirectory(prefix='piq-cabinet-media-tests-') as directory:
        temp=Path(directory);classes=temp/'classes';classes.mkdir();empty=temp/'empty';empty.mkdir()
        cp=os.pathsep.join(map(str,[classes,sfc,fc,*deps]))
        run([JAVA/'javac.exe','-encoding','UTF-8','-proc:none','-sourcepath',empty,'-cp',cp,'-d',classes,*production,*tests,*probes],HOME)
        java=[JAVA/'java.exe','-Xmx1G','-Dfile.encoding=UTF-8','-Dstdout.encoding=UTF-8','-Dstderr.encoding=UTF-8','-cp',cp]
        unit_output=run([*java,'CabinetMediaTestRunner'],HOME)
        cpu=last_json(run([*java,'CabinetMediaCpuProbe'],HOME))
        core=last_json(run([*java,'cn.piq.sfchome.client.cabinet.SfcCabinetActualCoreProbe'],HOME))
    assert all(sha(ROOT.parent/path)==digest for path,digest in hashes.items())
    assert all(sha(Path(path))==digest for path,digest in jars.items())
    report={'passed':True,'mode':'explicit-production-source-and-frozen-core','unit_tests':last_json(unit_output),'unit_output':unit_output,'codec_cpu':cpu,'actual_sfc_core':core,'jars_read_only':jars,'source_sha256':hashes,
            'limits':['Synthetic CPU samples are not commercial game/network performance.','Actual SFC core checks use an original generated 65816 ROM; no Minecraft server or clients were launched.','No production core was recompiled; only selected adapters/interfaces/media codec were compiled.','No game instance, ROM library, installed mods or remote server was changed.']}
    args.report.parent.mkdir(parents=True,exist_ok=True)
    with args.report.open('x',encoding='utf-8') as f:json.dump(report,f,ensure_ascii=False,indent=2)
    print(json.dumps({k:v for k,v in report.items() if k!='source_sha256'},ensure_ascii=False,indent=2))
if __name__=='__main__':main()
