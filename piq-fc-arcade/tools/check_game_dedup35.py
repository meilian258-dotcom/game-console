"""FC35 shared-game reuse: final-JAR-only filesystem, wire and compiled wiring QA.

Only listed test/probe sources are compiled. No production compilation, native
core, user ROM/BIOS/save, real socket, Minecraft world or installation is used.
"""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
import verify_retro_alpha19 as q

ROOT = Path(__file__).resolve().parents[1]
TESTS = [
    ('cabinet', 'CabinetGameBudgetTest'),
    ('cabinet', 'CabinetGameTransferTest'),
    ('cabinet', 'CabinetGameStoreSafetyTest'),
    ('cabinet', 'CabinetGameUploadPlanTest'),
    ('client/cabinet', 'CabinetGameClientPlanTest'),
]
PROBES = ['CabinetGameDedup35Runner', 'CabinetGameReuse35Probe', 'CabinetGameTransportProbe']


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest().upper()


def main():
    sys.stdout.reconfigure(encoding='utf-8'); sys.stderr.reconfigure(encoding='utf-8')
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--fc', type=Path, required=True)
    p.add_argument('--report', type=Path, required=True)
    a = p.parse_args(); fc = a.fc.resolve(strict=True); report = a.report.resolve()
    q.require(not report.exists(), 'Refuse to overwrite evidence')
    before, production = q.archive(fc)
    qa = ROOT / 'tools/qa'
    tests = [ROOT / 'src/test/java/cn/piq/fcarcade' / folder / (name + '.java') for folder, name in TESTS]
    probes = [qa / (name + '.java') for name in PROBES]
    inputs = [Path(__file__).resolve(), *tests, *probes]
    source_sha = {str(path.relative_to(ROOT)): sha(path) for path in inputs}
    expected = sum(len(re.findall(r'@Test\b', path.read_text(encoding='utf-8'))) for path in tests)
    q.require(expected >= 40, 'Missing FC35 behavior tests')
    junit = []
    for group, version in [('org.junit.platform', '1.13.4'), ('org.junit.jupiter', '5.13.4'), ('org.opentest4j', '1.3.0'), ('org.apiguardian', '1.1.2')]:
        junit.extend(path for path in (q.CACHE / group).rglob('*.jar') if version in path.parts and '-sources' not in path.name and '-javadoc' not in path.name)
    with tempfile.TemporaryDirectory(prefix='piq-game-reuse35-final-') as folder:
        work = Path(folder); classes = work / 'qa'; classes.mkdir(); empty = work / 'empty'; empty.mkdir()
        staged = work / 'fc.jar'; shutil.copyfile(fc, staged)
        resources = q.MC.parent / 'stripClient_1c8e7d85886c0a45d98a9d38d082e6a9f34fe0f3_resourcesOutput.jar'
        dependencies = list(dict.fromkeys([*junit, q.MC, resources.resolve(strict=True), *q.dependencies()]))
        cp = os.pathsep.join(map(str, [classes, staged, *dependencies]))
        cp_args = work / 'cp.args'; cp_args.write_text('-cp\n"' + cp.replace('\\', '/') + '"\n', encoding='utf-8')
        # Source files are copied to ASCII names for the native Windows javac argfile encoding.
        copied = []
        for source in [*tests, *probes]:
            target = work / source.name; shutil.copyfile(source, target); copied.append(target)
            q.require(sha(source) == sha(target), 'Exact QA source copy')
        q.run([q.JAVA / 'javac.exe', '@' + str(cp_args), '--release', '21', '-encoding', 'UTF-8', '-proc:none', '-sourcepath', empty, '-d', classes, *copied], work)
        def run(name, *args):
            return q.parse_last_json(q.run([q.JAVA / 'java.exe', '-Xmx512m', '-Djava.awt.headless=true', '@' + str(cp_args), name, *args], work, 90))
        unit = run('CabinetGameDedup35Runner', str(expected), *['cn.piq.fcarcade.' + folder.replace('/', '.') + '.' + name for folder, name in TESTS])
        behavior = run('cn.piq.fcarcade.cabinet.CabinetGameReuse35Probe', staged, work / 'owned-fixtures')
        transport = run('cn.piq.fcarcade.cabinet.CabinetGameTransportProbe', staged)
        compiled = sorted(path.relative_to(classes).as_posix() for path in classes.rglob('*.class'))
        allowed = {name for _, name in TESTS} | set(PROBES)
        q.require(all(Path(name).name[:-6].split('$')[0] in allowed for name in compiled), 'Unexpected compiled source')
        q.require(not set(compiled) & set(production), 'Production class shadowed by QA')
        q.require(before == sha(fc) == sha(staged), 'Final JAR changed')
        q.require(source_sha == {str(path.relative_to(ROOT)): sha(path) for path in inputs}, 'QA source changed during run')
    result = {
        'schema': 'piq-game-reuse35-final-1', 'ok': True, 'mode': 'final-jar-only',
        'production_compiled': False, 'jar': str(fc), 'sha256': before,
        'unit': unit, 'behavior_wire_wiring': behavior, 'transport': transport,
        'qa_source_sha256': source_sha, 'compiled_qa_classes': compiled,
        'minecraft_started': False, 'real_socket_opened': False, 'native_core_started': False,
        'user_rom_bios_save_used': False, 'installed': False,
        'limits': [
            'Actual final-JAR filesystem ledgers, client cache helpers, outer payload codecs and EmbeddedChannel completion are executed.',
            'Server authority/END and client mode/launch/cancellation integration are compiled-bytecode checks, not live player or permission-mod event execution.',
            'No Minecraft world, real multiplayer connection, host handover or user game compatibility playtest.'
        ]
    }
    report.parent.mkdir(parents=True, exist_ok=True)
    with report.open('x', encoding='utf-8') as output: json.dump(result, output, ensure_ascii=False, indent=2)
    print(json.dumps({'ok': True, 'report': str(report), 'unit': unit, 'behavior_wire_wiring': behavior, 'transport': transport}, ensure_ascii=False))


if __name__ == '__main__': main()
