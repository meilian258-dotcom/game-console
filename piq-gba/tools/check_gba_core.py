"""Compile only the standalone GBA helper/probe; run approved core in child JVM."""
from pathlib import Path
import argparse,hashlib,json,shutil,subprocess,tempfile
from diagnostic_rom import create
ROOT=Path(__file__).resolve().parents[1]
JDK=Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')
JNA=Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1/net.java.dev.jna/jna/5.14.0/67bf3eaea4f0718cb376a181a629e5f88fa1c9dd/jna-5.14.0.jar')
CORE=ROOT/'runtime/incoming-mgba-20260911-v1/mgba_libretro.dll'
SHA='D1BA96BC1AF23997D5C8003A6F6F8BE7ACBA9D770D4D42D14557AAEB469FA16B'
def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest().upper()
def main():
    p=argparse.ArgumentParser();p.add_argument('--report',required=True);a=p.parse_args();report=Path(a.report).resolve()
    if not report.is_relative_to(ROOT) or report.exists():raise ValueError('New report in piq-gba required')
    if digest(CORE)!=SHA or digest(JNA)!='34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6':raise ValueError('Runtime hash mismatch')
    source_record=ROOT/'vendor/mgba-e31759b24e7a4e3899285ff720d7b573ac328ae7/source-verification.json'
    source=json.loads(source_record.read_text(encoding='utf-8'))
    if source.get('core_sha256')!=SHA or not source.get('ok'):raise ValueError('Source verification required before native execution')
    paths=sorted((ROOT/'helper/src/main/java').rglob('*.java'))+[ROOT/'src/main/java/cn/piq/gba/bridge/GbaProtocol.java',ROOT/'tools/qa/GbaCoreProbe.java']
    original={str(f.relative_to(ROOT)):digest(f) for f in paths}
    with tempfile.TemporaryDirectory(prefix='piq-gba-owned-') as temp:
        d=Path(temp);classes=d/'classes';classes.mkdir();dll=d/'mgba_libretro.dll';shutil.copyfile(CORE,dll)
        rom=d/'diagnostic.gba';rom.write_bytes(create());assert rom.stat().st_size==32768
        def run(cmd,timeout):
            result=subprocess.run(cmd,cwd=d,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=timeout)
            if result.returncode:raise RuntimeError(result.stdout+'\n'+result.stderr)
            return result
        run([str(JDK/'javac.exe'),'-encoding','UTF-8','--release','21','-cp',str(JNA),'-d',str(classes),*map(str,paths)],45)
        result=run([str(JDK/'java.exe'),'-Xms32m','-Xmx256m','-cp',str(classes)+';'+str(JNA),'cn.piq.gba.bridge.GbaCoreProbe',str(dll),str(rom),str(d)],60)
        probe=json.loads(next(line for line in reversed(result.stdout.splitlines()) if line.startswith('{"ok"')))
        if not probe.get('ok') or digest(CORE)!=SHA or original!={str(f.relative_to(ROOT)):digest(f) for f in paths}:raise ValueError('Probe/source changed')
        out={'schema':'piq-gba-core-prototype-1','ok':True,'core':{'path':str(CORE),'sha256':SHA},'source':source,'probe':probe,
             'diagnostic_rom_sha256':digest(rom),'helper_source_sha256':original,'minecraft_started':False,'user_rom_or_save_used':False,
             'native_execution':'Dedicated child JVM; bounded 60-second lifetime; exact process only; not an OS sandbox.',
             'not_a_mod_or_playable_item':True,'stderr':result.stderr[-4000:]}
    report.parent.mkdir(parents=True,exist_ok=True);report.write_text(json.dumps(out,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps(probe))
if __name__=='__main__':main()
