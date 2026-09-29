"""Verify the fixed production offline pack using the final JAR, only in an owned temporary tree."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import verify_retro_alpha19 as q
from freeze_fc_core_alpha19 import read_jar, require, safe_path


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''): digest.update(block)
    return digest.hexdigest().upper()


def main():
    sys.stdout.reconfigure(encoding='utf-8'); sys.stderr.reconfigure(encoding='utf-8')
    p = argparse.ArgumentParser(description=__doc__)
    for name in ('fc', 'pack', 'report'): p.add_argument('--' + name, type=Path, required=True)
    a = p.parse_args(); fc = a.fc.resolve(strict=True); pack = a.pack.resolve(strict=True); report = safe_path(a.report)
    require(not report.exists(), 'New report required')
    fc_sha = read_jar(fc)[0]; pack_sha = sha(pack)
    require(pack_sha == '681FDAB15CCF7BD3739B74598D1724E637415E6EB60C505DEEF0EFDAED0617AA', 'Exact FC37 offline pack required')
    probe = Path(__file__).parent / 'qa/RuntimePackage37Probe.java'; source_sha = sha(probe)
    with tempfile.TemporaryDirectory(prefix='piq-runtime37-pack-final-') as folder:
        temp = Path(folder); game = temp / 'game-fixture'; (game / 'piq-runtime-packs').mkdir(parents=True)
        staged_pack = game / 'piq-runtime-packs/piq-runtime-pack-v1.zip'; shutil.copyfile(pack, staged_pack)
        staged_fc = temp / 'fc.jar'; shutil.copyfile(fc, staged_fc)
        classes = temp / 'qa'; classes.mkdir(); empty = temp / 'empty'; empty.mkdir()
        cp = os.pathsep.join(map(str, [classes, staged_fc])); arg = temp / 'cp.args'
        arg.write_text('-cp\n"' + cp.replace('\\', '/') + '"\n', encoding='utf-8')
        q.run([q.JAVA/'javac.exe', '@'+str(arg), '-encoding', 'UTF-8', '--release', '21', '-proc:none', '-sourcepath', empty, '-d', classes, probe], temp)
        result = q.parse_last_json(q.run([q.JAVA/'java.exe', '-Xmx128m', '@'+str(arg), 'RuntimePackage37Probe', staged_fc, game], temp, timeout=300))
        require(result.get('ok'), 'Actual pack installation probe')
        require(sha(staged_pack) == pack_sha == sha(pack) and read_jar(staged_fc)[0] == fc_sha == read_jar(fc)[0], 'Input drift')
        compiled = sorted(path.relative_to(classes).as_posix() for path in classes.rglob('*.class'))
        require(compiled == ['RuntimePackage37Probe.class'], 'Only probe compiled')
    require(sha(probe) == source_sha, 'QA source drift')
    data = {'schema': 'piq-runtime37-pack-1', 'ok': True, 'mode': 'final-jar-only', 'production_compiled': False,
            'sha256': fc_sha, 'jar': str(fc), 'pack': str(pack), 'pack_sha256': pack_sha, 'probe': result,
            'qa_source_sha256': source_sha, 'compiled_qa_classes': compiled,
            'limits': ['Real fixed runtime bytes are only installed and hash-checked in an owned disposable directory.',
                       'No DLL is loaded, no emulator helper launched, no Minecraft client started, and no user instance is touched.',
                       'Only known files created by this probe are removed/altered for repair and conflict tests.'],
            'installed_to_user_instance': False, 'native_library_loaded': False, 'minecraft_started': False}
    report.parent.mkdir(parents=True, exist_ok=True)
    with report.open('x', encoding='utf-8') as stream: json.dump(data, stream, ensure_ascii=False, indent=2)
    print(json.dumps({'ok': True, 'report': str(report), 'assertions': result['assertions']}, ensure_ascii=False))


if __name__ == '__main__': main()
