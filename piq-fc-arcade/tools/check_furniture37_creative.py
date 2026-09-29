"""Check the merged FC/furniture creative tab against one candidate JAR, compiling only QA."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
from freeze_fc_core_alpha19 import read_jar, digest, require, safe_path
import verify_retro_alpha19 as q


def main():
    sys.stdout.reconfigure(encoding='utf-8'); sys.stderr.reconfigure(encoding='utf-8')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--fc', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    args = parser.parse_args()
    fc = args.fc.resolve(strict=True); report = safe_path(args.report)
    require(not report.exists(), 'New report required'); sha = read_jar(fc)[0]
    probe = Path(__file__).parent / 'qa/FurnitureCreative37Probe.java'; probe_sha = digest(probe.read_bytes())
    with tempfile.TemporaryDirectory(prefix='piq-furniture37-creative-') as folder:
        tmp = Path(folder); classes = tmp / 'qa'; classes.mkdir(); empty = tmp / 'empty'; empty.mkdir()
        staged = tmp / 'fc.jar'; shutil.copyfile(fc, staged)
        resources = q.MC.parent / 'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp = os.pathsep.join(map(str, [classes, staged, q.MC, resources, *q.dependencies()]))
        cp_args = tmp / 'cp.args'; cp_args.write_text('-cp\n"' + cp.replace('\\', '/') + '"\n', encoding='utf-8')
        q.run([q.JAVA / 'javac.exe', '@' + str(cp_args), '-encoding', 'UTF-8', '--release', '21', '-proc:none', '-sourcepath', empty, '-d', classes, probe], tmp)
        result = q.parse_last_json(q.run([q.JAVA / 'java.exe', '-Xmx512m', '-Djava.awt.headless=true', '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED', '@' + str(cp_args), 'FurnitureCreative37Probe', staged], tmp))
        require(result.get('ok'), 'Actual creative callback')
        compiled = {x.relative_to(classes).as_posix() for x in classes.rglob('*.class')}
        require(all(Path(x).name.split('$')[0].split('.')[0] == 'FurnitureCreative37Probe' for x in compiled), 'Only QA compiled')
        require(sha == read_jar(fc)[0] == read_jar(staged)[0] and probe_sha == digest(probe.read_bytes()), 'Input drift')
    data = {'schema': 'piq-furniture37-creative-1', 'ok': True, 'mode': 'final-jar-only', 'production_compiled': False,
            'jar': str(fc), 'sha256': sha, 'creative': result, 'qa_source_sha256': probe_sha, 'compiled_qa_classes': sorted(compiled),
            'installed': False, 'minecraft_started': False, 'native_core_started': False}
    report.parent.mkdir(parents=True, exist_ok=True)
    with report.open('x', encoding='utf-8') as file: json.dump(data, file, ensure_ascii=False, indent=2)
    print(json.dumps({'ok': True, 'report': str(report), 'assertions': result['assertions']}, ensure_ascii=False))


if __name__ == '__main__': main()
