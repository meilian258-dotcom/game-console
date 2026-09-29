"""FC35 final-JAR FML/common compatibility with unchanged SFC20/Native12/GBA4, no game/core launch."""
import argparse
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile

from build_reuse35 import ROOT, BASE, PINS
from freeze_fc_core_alpha19 import read_jar, digest, require, safe_path
import verify_retro_alpha19 as q


def main():
    sys.stdout.reconfigure(encoding='utf-8'); sys.stderr.reconfigure(encoding='utf-8')
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--fc', required=True, type=Path); p.add_argument('--report', required=True, type=Path); a = p.parse_args()
    report = safe_path(a.report); require(not report.exists(), 'New report required')
    paths = {'fc': a.fc.resolve(strict=True)}
    paths.update({k: BASE / n for k, (n, _) in PINS.items() if k != 'fc'})
    fence = {k: read_jar(path)[0] for k, path in paths.items()}
    for k in ('native', 'sfc', 'gba'): require(fence[k] == PINS[k][1], 'Addon pin ' + k)
    probe = ROOT / 'piq-gba/tools/qa/GbaHandheldCommon3Probe.java'; probe_sha = digest(probe.read_bytes())
    with tempfile.TemporaryDirectory(prefix='piq-reuse35-addons-') as folder:
        tmp = Path(folder); classes = tmp / 'qa'; classes.mkdir(); empty = tmp / 'empty'; empty.mkdir(); staged = {}
        for kind, path in paths.items():
            staged[kind] = tmp / (kind + '.jar'); shutil.copyfile(path, staged[kind])
        resources = q.MC.parent / 'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        cp = os.pathsep.join(map(str, [classes, *staged.values(), q.MC, resources, *q.dependencies()]))
        args = tmp / 'cp.args'; args.write_text('-cp\n"' + cp.replace('\\', '/') + '"\n', encoding='utf-8')
        q.run([q.JAVA / 'javac.exe', '@' + str(args), '-encoding', 'UTF-8', '--release', '21', '-proc:none',
               '-sourcepath', empty, '-d', classes, probe], tmp)
        result = q.parse_last_json(q.run([q.JAVA / 'java.exe', '-Xmx512m', '-Djava.awt.headless=true',
            '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED', '@' + str(args), 'GbaHandheldCommon3Probe',
            staged['fc'], staged['gba'], '0.1.0-alpha.4', staged['sfc'], staged['native']], tmp))
        require(result.get('ok') and result['actual_fml_reader'] and result['client_and_native_load_attempts'] == 0
                and len(result['discovery']) == 4, 'Four-mod actual FML/common check')
        compiled = {x.relative_to(classes).as_posix() for x in classes.rglob('*.class')}
        production = set().union(*(set(read_jar(path)[2]) for path in staged.values()))
        require(not compiled & production and all(Path(x).name.split('$')[0].split('.')[0] == 'GbaHandheldCommon3Probe' for x in compiled), 'Only QA classes compiled')
        require(fence == {k: read_jar(path)[0] for k, path in paths.items()} == {k: read_jar(path)[0] for k, path in staged.items()}, 'Inputs changed')
    require(probe_sha == digest(probe.read_bytes()), 'Probe changed')
    data = {'schema': 'piq-reuse35-addons-1', 'ok': True, 'mode': 'final-jar-only', 'production_compiled': False,
        'jars': {k: {'path': str(paths[k]), 'sha256': v} for k, v in fence.items()}, 'common': result,
        'qa_source_sha256': {str(probe.relative_to(ROOT)): probe_sha}, 'compiled_qa_classes': sorted(compiled),
        'installed': False, 'minecraft_started': False, 'native_core_started': False,
        'limits': ['Actual FML archive discovery, dependency ranges, item registration/codec and common input ownership; not full ModLauncher startup or multiplayer playtest.']}
    report.parent.mkdir(parents=True, exist_ok=True)
    with report.open('x', encoding='utf-8') as f: json.dump(data, f, ensure_ascii=False, indent=2)
    print(json.dumps({'ok': True, 'report': str(report), 'assertions': result['assertions']}, ensure_ascii=False))


if __name__ == '__main__': main()
