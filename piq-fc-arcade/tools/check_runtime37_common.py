"""Compile only two fixture test classes and a launcher against a supplied final FC37 JAR."""
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

ROOT = Path(__file__).resolve().parents[1]


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest().upper()


def junit_dependencies():
    cache = Path('C:/Users/13498/.gradle/caches/modules-2/files-2.1')
    specifications = [
        ('org.junit.jupiter', 'junit-jupiter-api', '5.13.4'),
        ('org.junit.jupiter', 'junit-jupiter-engine', '5.13.4'),
        ('org.junit.platform', 'junit-platform-commons', '1.13.4'),
        ('org.junit.platform', 'junit-platform-engine', '1.13.4'),
        ('org.junit.platform', 'junit-platform-launcher', '1.13.4'),
        ('org.apiguardian', 'apiguardian-api', '1.1.2'),
        ('org.opentest4j', 'opentest4j', '1.3.0'),
    ]
    result = []
    for group, name, version in specifications:
        found = list((cache / group / name / version).glob('*/' + name + '-' + version + '.jar'))
        require(len(found) == 1, 'Exactly one cached JUnit dependency required: ' + name)
        result.extend(found)
    return result


def main():
    sys.stdout.reconfigure(encoding='utf-8'); sys.stderr.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fc', type=Path, required=True); parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args(); fc = args.fc.resolve(strict=True); report = safe_path(args.report)
    require(not report.exists(), 'New report required')
    sha = read_jar(fc)[0]
    sources = [ROOT / 'tools/qa/RuntimeInstaller37Probe.java',
               ROOT / 'src/test/java/cn/piq/fcarcade/runtime/RuntimeInstallerTest.java',
               ROOT / 'src/test/java/cn/piq/fcarcade/client/runtime/RuntimePanelLayoutTest.java']
    fence = {str(path): digest(path) for path in sources}
    dependencies = junit_dependencies()
    dependency_hashes = {str(path): digest(path) for path in dependencies}
    with tempfile.TemporaryDirectory(prefix='piq-runtime37-common-final-') as folder:
        temp = Path(folder); classes = temp / 'qa'; classes.mkdir(); empty = temp / 'empty'; empty.mkdir()
        staged = temp / 'fc.jar'; shutil.copyfile(fc, staged)
        cp = os.pathsep.join(map(str, [classes, staged, *dependencies]))
        arguments = temp / 'cp.args'; arguments.write_text('-cp\n"' + cp.replace('\\', '/') + '"\n', encoding='utf-8')
        q.run([q.JAVA/'javac.exe', '@'+str(arguments), '-encoding', 'UTF-8', '--release', '21', '-proc:none',
               '-sourcepath', empty, '-d', classes, *sources], temp)
        compiled = sorted(path.relative_to(classes).as_posix() for path in classes.rglob('*.class'))
        permitted = {'RuntimeInstaller37Probe', 'RuntimeInstallerTest', 'RuntimePanelLayoutTest'}
        require(all(Path(path).stem.split('$')[0] in permitted for path in compiled), 'Only QA classes compiled')
        result = q.parse_last_json(q.run([q.JAVA/'java.exe', '-Xmx256m', '-Djava.awt.headless=true', '@'+str(arguments),
                                         'RuntimeInstaller37Probe', staged], temp, timeout=300))
        require(result.get('ok') and result.get('production_origins_verified') == 5, 'Actual final JAR origins required')
        require(read_jar(staged)[0] == sha == read_jar(fc)[0], 'JAR changed during final verification')
    require(fence == {str(path): digest(path) for path in sources}, 'QA input drift')
    limits = [
        'Only generated inert byte fixtures in temporary directories; no user instance, ROM, BIOS or save data.',
        '28 real final-JAR installer fixtures plus 2 pure layout tests; not a running Minecraft client or GUI screenshot.',
        'An OS-account symlink privilege abort is reported explicitly; ZIP symlink/reparse metadata rejection still runs.',
        'Publication/rollback uses actual NTFS hardlinks and isSameFile. Other filesystems may not support installation.',
        'Directory metadata/NOFOLLOW checks are not an OS sandbox against malicious processes with equal local privileges.',
        'No native library is loaded and no emulator helper is started. Only the isolated Java QA runner executes.'
    ]
    data = {'schema': 'piq-runtime37-common-1', 'ok': True, 'mode': 'final-jar-only', 'production_compiled': False,
            'sha256': sha, 'jar': str(fc), 'tests': result, 'qa_source_sha256': fence,
            'qa_dependencies_sha256': dependency_hashes, 'compiled_qa_classes': compiled, 'limits': limits,
            'minecraft_started': False, 'installed': False, 'native_library_loaded': False}
    report.parent.mkdir(parents=True, exist_ok=True)
    with report.open('x', encoding='utf-8') as stream: json.dump(data, stream, ensure_ascii=False, indent=2)
    print(json.dumps({'ok': True, 'report': str(report), 'tests': result}, ensure_ascii=False))


if __name__ == '__main__':
    main()
