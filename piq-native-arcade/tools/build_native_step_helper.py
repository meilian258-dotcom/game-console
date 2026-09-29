"""Build an independent step prototype; reuse the pinned old helper byte-for-byte."""
import argparse, hashlib, json, os, subprocess, tempfile, zipfile
from pathlib import Path
from build_native_helper import ROOT, JDK, JNA

OLD = ROOT.parent/'piq-fc-arcade/build/review-controls26-v2/piq-native-arcade/runtime/piq-native-helper.jar'
OLD_SHA = '20F6F3028D76DAEB01212D1808BE90E35BFB5429D1E06153B7D8B32DD73E943C'
JNA_SHA = '34ED1E1F27FA896BCA50DBC4E99CF3732967CEC387A7A0D5E3486C09673FE8C6'

def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest().upper()

def build(output):
    output = Path(output).resolve()
    if output.exists(): raise FileExistsError(output)
    sources = [ROOT/'src/main/java/cn/piq/nativearcade/bridge/NativeStepProtocol.java',
               ROOT/'helper/src/main/java/cn/piq/nativearcade/bridge/NativeStepWorker.java']
    before = {str(p): sha(p) for p in [*sources, OLD, JNA]}
    assert before[str(OLD)] == OLD_SHA and before[str(JNA)] == JNA_SHA
    with tempfile.TemporaryDirectory(prefix='piq-step-helper-build-') as folder:
        classes = Path(folder)
        subprocess.run(list(map(str, [JDK/'javac.exe','-encoding','UTF-8','--release','21',
            '-proc:none','-implicit:none','-cp',os.pathsep.join(map(str,[OLD,JNA])),
            '-d',classes,*sources])), check=True, timeout=40)
        assert all(sha(Path(p)) == digest for p,digest in before.items())
        with zipfile.ZipFile(OLD) as old: entries = {n:old.read(n) for n in old.namelist()}
        preserved = {n:hashlib.sha256(b).hexdigest().upper() for n,b in entries.items()}
        for path in classes.rglob('*.class'):
            name = path.relative_to(classes).as_posix()
            assert name.startswith('cn/piq/nativearcade/bridge/NativeStep') and name not in entries
            entries[name] = path.read_bytes()
        output.parent.mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(output,'x',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as jar:
            for name,data in sorted(entries.items()):
                member = zipfile.ZipInfo(name,(2026,9,12,0,0,0))
                member.compress_type=zipfile.ZIP_DEFLATED
                jar.writestr(member,data)
        with zipfile.ZipFile(output) as jar:
            assert jar.testzip() is None
            assert all(hashlib.sha256(jar.read(n)).hexdigest().upper()==h for n,h in preserved.items())
    assert all(sha(Path(p)) == digest for p,digest in before.items())
    return {'path':str(output),'sha256':sha(output),'bytes':output.stat().st_size,
        'kind':'private-step-prototype-not-a-mod','step_protocol':1,'legacy_protocol':3,
        'preserved_old_entries':preserved,'source_sha256':before,
        'limits':'No MAME local-sync qualification, DLL, ROM, BIOS, installation or registration.'}

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',required=True,type=Path)
    args=p.parse_args();print(json.dumps(build(args.output),ensure_ascii=False,indent=2))
