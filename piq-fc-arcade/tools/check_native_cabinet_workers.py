"""Final-JAR-only actual CabinetSyncWorker -> fixed NativeSnapshotCore, two parent/child JVM pairs."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT.parent / 'piq-native-arcade'
JDK = Path('C:/Program Files/Microsoft/jdk-21.0.11.10-hotspot/bin')


def regular(path):
    path = path.absolute()
    for at in [path, *path.parents]:
        stat = at.lstat()
        if at.is_symlink() or getattr(stat, 'st_file_attributes', 0) & 0x400:
            raise AssertionError(f'No linked input component: {at}')
    assert path.is_file(), f'Expected regular input: {path}'
    return path.resolve()


def main():
    sys.stdout.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ['fc', 'native', 'kof97', 'mslug2', 'bios', 'report']:
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    assert not args.report.exists(), 'Refuse report overwrite'
    for name in ['fc', 'native', 'kof97', 'mslug2', 'bios']:
        setattr(args, name, regular(getattr(args, name)))
    boundary_tool = NATIVE / 'tools/check_native_snapshot_boundary.py'
    spec = importlib.util.spec_from_file_location('native_snapshot_boundary_metadata', boundary_tool)
    boundary = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(boundary)
    probe = ROOT / 'tools/qa/NativeCabinetSyncWorkerProbe.java'
    inputs = [args.fc, args.native, args.kof97, args.mslug2, args.bios, probe,
              Path(__file__).resolve(), boundary_tool, *(item[0] for item in boundary.PINNED.values())]
    for path in inputs:
        regular(path)
    before = {str(path.resolve()): boundary.identity(path) for path in inputs}
    for path, digest in boundary.PINNED.values():
        assert boundary.sha(path) == digest, 'Fixed experimental runtime changed'
    for path in [args.kof97, args.mslug2, args.bios]:
        assert boundary.sha(path) == boundary.ROMS[path.name], 'ROM/BIOS identity mismatch'
    began = time.monotonic()
    result = None
    with tempfile.TemporaryDirectory(prefix='piq-cabinet-native-worker-qa-') as folder:
        temporary = Path(folder)
        classes = temporary / 'classes'
        roms = temporary / 'roms'
        runtime = temporary / 'runtime-snapshot-v1'
        for path in [classes, roms, runtime]:
            path.mkdir()
        for name, (path, digest) in boundary.PINNED.items():
            shutil.copyfile(path, runtime / name)
            assert boundary.sha(runtime / name) == digest
        for path in [args.kof97, args.mslug2, args.bios]:
            shutil.copyfile(path, roms / path.name)
            assert boundary.sha(roms / path.name) == boundary.ROMS[path.name]
        classpath = os.pathsep.join(map(str, [classes, args.fc, args.native]))
        # Exactly one QA source: neither production Java nor helper sources are compiled here.
        compile_run = subprocess.run([str(JDK / 'javac.exe'), '--release', '21', '-encoding', 'UTF-8',
                                      '-proc:none', '-implicit:none', '-cp', classpath, '-d', str(classes), str(probe)],
                                     capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=60)
        assert compile_run.returncode == 0, compile_run.stdout + compile_run.stderr
        env = os.environ.copy()
        env['PIQ_QA_FC_ORIGIN'] = str(args.fc)
        env['PIQ_QA_NATIVE_ORIGIN'] = str(args.native)
        run = subprocess.run([str(JDK / 'java.exe'), '-Djava.awt.headless=true', '-Xmx512m', '-cp', classpath,
                              'cn.piq.fcarcade.qa.NativeCabinetSyncWorkerProbe', str(runtime),
                              str(roms / args.kof97.name), str(roms / args.mslug2.name)],
                             capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=480, env=env)
        print(run.stdout, flush=True)
        if run.stderr:
            print(run.stderr[-12000:], file=sys.stderr, flush=True)
        lines = [line for line in run.stdout.splitlines() if line.startswith('{')]
        result = json.loads(lines[-1]) if lines else {'ok': False, 'error': (run.stderr + run.stdout)[-12000:]}
        result['process_exit_code'] = run.returncode
    unchanged = before == {str(path.resolve()): boundary.identity(path) for path in inputs}
    result.update(schema='piq-native-cabinet-worker-1', mode='final-jar-only', production_compiled=False,
                  originals_unchanged=unchanged, input_identity=before,
                  elapsed_seconds=round(time.monotonic() - began, 3),
                  jars={name: {'path': str(path), 'sha256': boundary.sha(path)}
                        for name, path in [('fc', args.fc), ('native', args.native)]},
                  limitations=[
                      'Actual final-JAR worker/factory/native core in two independent parent and child JVM pairs; no Minecraft world/socket/client UI.',
                      'QA wrapper hashes complete raw core video/PCM before droppable display buffering; complete opaque states are compared by the real worker every 300 frames.',
                      'The guest scheduling latch delays a real worker to test backlog admission; it does not alter native state, inputs, frames or media.',
                      'Only the two pinned ROM sets/BIOS/runtime are qualified; no arbitrary MAME game or real multiplayer network claim.',
                      'ROM/BIOS/media/state bytes are private temporary inputs, never copied to the report.'
                  ])
    result['ok'] = bool(result['ok'] and unchanged and run.returncode == 0)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open('x', encoding='utf-8') as output:
        json.dump(result, output, ensure_ascii=False, indent=2)
    print(json.dumps({'ok': result['ok'], 'report': str(args.report.resolve())}, ensure_ascii=False), flush=True)
    if not result['ok']:
        raise SystemExit(2)


if __name__ == '__main__':
    main()
