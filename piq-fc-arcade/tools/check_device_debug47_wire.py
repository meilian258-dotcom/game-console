"""Final-JAR-only debug-settings wire test; no world, game process, ROM or deployment."""
import argparse
import json
import os
from pathlib import Path
import shutil
import tempfile
import verify_retro_alpha19 as q

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--fc', type=Path, required=True)
    parser.add_argument('--report', type=Path, required=True)
    args = parser.parse_args()
    if args.report.exists():
        raise ValueError('Refuse existing evidence overwrite')
    jar = args.fc.resolve(strict=True)
    before, entries = q.archive(jar)
    source = ROOT / 'tools/qa/DeviceDebugWire47Probe.java'
    report = {'schema': 'device-debug47-final-wire-v1', 'ok': True,
              'fc': {'path': str(jar), 'sha256': before}, 'production_compiled': False,
              'test_source_sha256': {str(source.relative_to(ROOT)): q.digest(source.read_bytes())}}
    with tempfile.TemporaryDirectory(prefix='device-debug47-wire-') as directory:
        temp = Path(directory)
        tests, empty, copy = temp / 'tests', temp / 'empty', temp / 'fc.jar'
        tests.mkdir()
        empty.mkdir()
        shutil.copyfile(jar, copy)
        if q.digest(copy.read_bytes()) != before:
            raise AssertionError('JAR copy mismatch')
        resources = q.MC.parent / 'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        classpath = os.pathsep.join(map(str, [tests, copy, q.MC, resources, *q.dependencies()]))
        arg = temp / 'classpath.args'
        arg.write_text('-cp\n"' + classpath.replace('\\', '/') + '"\n', encoding='utf-8')
        report['compile_log'] = q.run([q.JAVA / 'javac.exe', '@' + str(arg), '-encoding', 'UTF-8', '-proc:none',
                                      '-sourcepath', empty, '-d', tests, source], temp)
        for path in tests.rglob('*.class'):
            if path.relative_to(tests).as_posix() in entries:
                raise AssertionError('Accidentally compiled production ' + str(path))
        report['run_log'] = q.run([q.JAVA / 'java.exe', '-Djava.awt.headless=true', '@' + str(arg),
                                  'DeviceDebugWire47Probe', copy], temp)
        report['actual'] = q.parse_last_json(report['run_log'])
        if not report['actual']['ok'] or report['actual']['production_origin'] != 'final-jar-only':
            raise AssertionError('Probe did not verify final origin')
        if q.digest(copy.read_bytes()) != before or q.digest(jar.read_bytes()) != before:
            raise AssertionError('Input JAR changed')
    report['limitations'] = ['No Minecraft world/player, real protection plugin, GUI or server socket was created.',
                             'Physical server authority is covered by source/policy tests, not this codec probe.']
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open('x', encoding='utf-8') as out:
        json.dump(report, out, ensure_ascii=False, indent=2)
    print(json.dumps({'ok': True, 'report': str(args.report), 'actual': report['actual']}, ensure_ascii=False))


if __name__ == '__main__':
    main()
