"""Exercise real player-media codecs/receiver/host failure using compiled JARs only.

Never compiles production, starts Minecraft, accesses user ROMs/worlds or opens sockets.
The host-failure cases use the existing original in-memory QA homebrew generator.
"""

import sys as _dev_sys
from pathlib import Path as _DevPath
_dev_sys.path.insert(0, str(_DevPath(__file__).resolve().parents[2] / "source-control"))
from dev_tool_paths import gradle_home

import argparse
import json
import os
from pathlib import Path
import shutil
import tempfile
import time
from run_sfc_playback_multiplayer_probe import ROOT, JAVA, MC, sha, run


def main():
    parser = argparse.ArgumentParser()
    for name in ('fc', 'sfc', 'core', 'report'):
        parser.add_argument('--' + name, type=Path, required=True)
    args = parser.parse_args()
    if args.report.exists():
        raise ValueError('Choose a new evidence path; existing evidence is never overwritten')
    jars = {name: getattr(args, name).resolve(strict=True) for name in ('fc', 'sfc', 'core')}
    hashes = {name: sha(path) for name, path in jars.items()}
    cache = (gradle_home() / 'caches/modules-2/files-2.1')
    dependencies = []
    manifest = json.loads((MC.parent.parent / 'artifacts/minecraft_1.21.1_version_manifest.json').read_text())
    for library in manifest['libraries']:
        parts = library['name'].split(':')
        if len(parts) == 3:
            dependencies.extend((cache / parts[0] / parts[1] / parts[2]).rglob(parts[1] + '-' + parts[2] + '.jar'))
    dependencies.extend(path for path in cache.rglob('*.jar')
                        if not any(tag in path.name for tag in ('-sources', '-javadoc', '-userdev')))
    probes = [ROOT / 'tools/qa/SfcPlayerMedia53Probe.java',
              ROOT.parent / 'piq-fc-arcade/tools/qa/SfcTwoPortInputProbe.java',
              ROOT.parent / 'piq-sfc-arcade/src/test/java/cn/piq/sfcarcade/core/SfcLegalTestRom.java']
    sources = probes + [Path(__file__).resolve(), ROOT / 'tools/run_sfc_playback_multiplayer_probe.py']
    source_hashes = {str(path.relative_to(ROOT.parent)): sha(path) for path in sources}
    started = time.monotonic()
    with tempfile.TemporaryDirectory(prefix='sfc-player-media53-') as folder:
        scratch = Path(folder)
        classes, empty = scratch / 'classes', scratch / 'empty-sourcepath'
        classes.mkdir()
        empty.mkdir()
        copies = {}
        for name, path in jars.items():
            copies[name] = scratch / (name + '.jar')
            shutil.copyfile(path, copies[name])
            assert sha(copies[name]) == hashes[name]
        resources = MC.parent / 'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        classpath = os.pathsep.join(map(str, [classes, MC, resources, copies['sfc'], copies['fc'], copies['core'], *dependencies]))
        argument = scratch / 'classpath.args'
        argument.write_text('-cp\n"' + classpath.replace('\\', '/') + '"\n', encoding='utf-8')
        run([JAVA / 'javac.exe', '@' + str(argument), '-encoding', 'UTF-8', '-proc:none',
             '-sourcepath', empty, '-d', classes, *probes], scratch)
        output = run([JAVA / 'java.exe', '-Xmx2G', '-Djava.awt.headless=true', '@' + str(argument),
                      'cn.piq.sfchome.client.SfcPlayerMedia53Probe', copies['sfc'], copies['fc'], copies['core']], scratch)
        actual = json.loads(next(line for line in reversed(output.splitlines()) if line.startswith('{"ok":')))
        assert actual['ok']
        assert all(sha(jars[name]) == hashes[name] and sha(copies[name]) == hashes[name] for name in jars)
        assert all(sha(path) == source_hashes[str(path.relative_to(ROOT.parent))] for path in sources)
    report = {'ok': True, 'mode': 'jar-only', 'production_compiled': False, 'actual': actual,
              'elapsed_seconds': round(time.monotonic() - started, 3),
              'jars': {name: {'path': str(path), 'sha256': hashes[name]} for name, path in jars.items()},
              'source_sha256': source_hashes,
              'classpath_order': ['QA classes', str(MC), str(resources), 'SFC JAR', 'FC JAR', 'core JAR', 'local Gradle library cache'],
              'limitations': ['Actual wire codecs, media-only receiver and self-authored homebrew host worker.',
                              'No Minecraft world/server, actual network transport, human interaction or commercial ROM.',
                              'Backup hook validates a recoverable core state but does not exercise real backup file writes.']}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    with args.report.open('x', encoding='utf-8') as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2)
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
