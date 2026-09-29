"""Offline final-JAR real Screen ESC and native widget geometry, no Minecraft startup or GL."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import verify_retro_alpha19 as q
from freeze_fc_core_alpha19 import read_jar, digest, require, safe_path


def main():
    sys.stdout.reconfigure(encoding='utf-8'); sys.stderr.reconfigure(encoding='utf-8')
    p = argparse.ArgumentParser(description=__doc__); p.add_argument('--fc', type=Path, required=True); p.add_argument('--report', type=Path, required=True)
    a = p.parse_args(); fc = a.fc.resolve(strict=True); report = safe_path(a.report); require(not report.exists(), 'New report required')
    fc_sha = read_jar(fc)[0]; probe = Path(__file__).parent / 'qa/RuntimeScreen37Probe.java'; source_sha = digest(probe.read_bytes())
    with tempfile.TemporaryDirectory(prefix='piq-runtime37-screen-final-') as folder:
        temp = Path(folder); classes = temp / 'qa'; classes.mkdir(); empty = temp / 'empty'; empty.mkdir(); staged = temp / 'fc.jar'; shutil.copyfile(fc, staged)
        resources = q.MC.parent / 'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp = os.pathsep.join(map(str, [classes, staged, q.MC, resources, *q.dependencies()])); arg = temp / 'cp.args'
        arg.write_text('-cp\n"' + cp.replace('\\', '/') + '"\n', encoding='utf-8')
        q.run([q.JAVA/'javac.exe', '@'+str(arg), '-encoding', 'UTF-8', '--release', '21', '-proc:none', '-sourcepath', empty, '-d', classes, probe], temp)
        result = q.parse_last_json(q.run([q.JAVA/'java.exe', '-Xmx768m', '-Djava.awt.headless=true', '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED', '@'+str(arg), 'RuntimeScreen37Probe', staged, q.MC], temp))
        require(result.get('ok'), 'Actual Screen/widget probe')
        compiled = sorted(path.relative_to(classes).as_posix() for path in classes.rglob('*.class'))
        require(all(Path(path).stem.split('$')[0] == 'RuntimeScreen37Probe' for path in compiled), 'Only QA compiled')
        require(read_jar(staged)[0] == fc_sha == read_jar(fc)[0], 'JAR drift')
    require(digest(probe.read_bytes()) == source_sha, 'QA source drift')
    data = {'schema': 'piq-runtime37-screen-1', 'ok': True, 'mode': 'final-jar-only', 'production_compiled': False,
            'sha256': fc_sha, 'jar': str(fc), 'probe': result, 'qa_source_sha256': source_sha, 'compiled_qa_classes': compiled,
            'limits': ['Actual Minecraft Screen.keyPressed ESC dispatch invokes final production onClose; actual button constructors and onPress execute.',
                       'Screen fields are injected and Minecraft navigation/world-state getters are controlled QA substitutions; no Minecraft constructor, actual server, network or GL runs.',
                       'Widget rectangles are real but font rendering, visuals, ScreenEvent bus integration, asynchronous completion and human interaction are not validated here.',
                       'No installer worker launches in this probe. Active/connection/server gates reject before worker creation.'],
            'minecraft_started': False, 'gl_window_created': False, 'installed': False}
    report.parent.mkdir(parents=True, exist_ok=True)
    with report.open('x', encoding='utf-8') as stream: json.dump(data, stream, ensure_ascii=False, indent=2)
    print(json.dumps({'ok': True, 'report': str(report), 'assertions': result['assertions']}, ensure_ascii=False))


if __name__ == '__main__': main()
