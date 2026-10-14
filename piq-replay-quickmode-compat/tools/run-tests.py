"""Test latest compiled production classes, real Sable resources and optional private trace. No game writes."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import shlex
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def classpath_jar(path, entries):
    # Manifest URLs avoid Windows CreateProcess's 32k command limit and Unicode @file pitfalls.
    value = 'Class-Path: ' + ' '.join(Path(entry).resolve().as_uri() + ('/' if Path(entry).is_dir() else '') for entry in entries)
    lines = [value[:70]]
    value = value[70:]
    while value:
        lines.append(' ' + value[:69])
        value = value[69:]
    manifest = 'Manifest-Version: 1.0\r\n' + '\r\n'.join(lines) + '\r\n\r\n'
    with zipfile.ZipFile(path, 'w') as jar:
        jar.writestr('META-INF/MANIFEST.MF', manifest)


def contains_sable(path):
    try:
        with zipfile.ZipFile(path) as jar:
            return 'dev/ryanhcode/sable/Sable.class' in jar.namelist()
    except (OSError, zipfile.BadZipFile):
        return False


def prioritize_asm(dependencies):
    # The installation cache can contain old bundled ASM (even inside other artifacts).
    # Pick the newest available standalone modules and place them before all such bundles.
    selected = {}
    for path in dependencies:
        if '/org/ow2/asm/' not in path.as_posix():
            continue
        artifact, version = path.parent.parent.name, path.parent.name
        if artifact not in {'asm', 'asm-tree', 'asm-analysis', 'asm-commons', 'asm-util'}:
            continue
        if not re.fullmatch(r'\d+(?:\.\d+)*', version):
            continue
        key = tuple(map(int, version.split('.')))
        if artifact not in selected or key > selected[artifact][0]:
            selected[artifact] = (key, path)
    priority = [entry[1] for _, entry in sorted(selected.items())]
    return priority + [path for path in dependencies if path not in priority], priority


def nested_replay_dependencies(dependencies, run):
    # Replay packages OpenNBT as a nested mod JAR. A plain JVM does not unpack it.
    # Read it from the build's Replay dependency into this run's isolated output only.
    extracted = []
    for path in dependencies:
        try:
            with zipfile.ZipFile(path) as jar:
                if 'com/replaymod/replaystudio/replay/ReplayFile.class' not in jar.namelist():
                    continue
                candidates = [name for name in jar.namelist()
                              if re.fullmatch(r'META-INF/jars/opennbt-[^/]+\.jar', name)]
                if len(candidates) != 1:
                    raise SystemExit('Expected one nested OpenNBT dependency in compiled Replay input')
                entry = jar.getinfo(candidates[0])
                if entry.file_size > 32 * 1024 * 1024:
                    raise SystemExit('Unexpected oversized nested dependency')
                destination = run / Path(entry.filename).name
                destination.write_bytes(jar.read(entry))
                extracted.append(destination)
        except (OSError, zipfile.BadZipFile):
            continue
    return extracted


def prioritize_minecraft(dependencies):
    # The launcher cache also contains other Minecraft versions. The declared
    # NeoForge client JAR is a patch-only JAR and does not contain this packet.
    matches = [path for path in dependencies
               if re.fullmatch(r'client-1\.21\.1-[^-]+\.[^-]+-srg\.jar', path.name)]
    if len(matches) != 1:
        raise SystemExit('Expected exactly one Minecraft 1.21.1 mapped client in build dependencies')
    target = matches[0]
    retained = [path for path in dependencies if path != target]
    index = next((i for i, path in enumerate(retained) if path.name == 'neoforge-21.1.250-client.jar'), -1)
    retained.insert(index + 1, target)
    return retained, target


def main():
    sys.stdout.reconfigure(encoding='utf-8', errors='replace')
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--sable', required=True, type=Path, help='Read-only reviewed Sable JAR')
    parser.add_argument('--trace-json', type=Path, help='Optional existing private TraceQuickPlayers details, never printed')
    parser.add_argument('--recording-copy', type=Path, help='Optional existing diagnostic recording copy, requires --trace-json; never use a live recording')
    parser.add_argument('--extra-dependency', action='append', default=[], type=Path,
                        help='Read-only standalone runtime dependency not present in build classpath (repeatable)')
    parser.add_argument('--output', type=Path, help='Parent directory for a new isolated result directory')
    parser.add_argument('--qa-host', type=Path, default=ROOT / 'tools/qa/KeyboardMixinTransformProbe.java')
    args = parser.parse_args()
    if args.recording_copy and not args.trace_json:
        parser.error('--recording-copy requires --trace-json')
    result = json.loads((ROOT / 'build/build-result.json').read_text(encoding='utf-8'))
    production = Path(result['classes'])
    tokens = shlex.split((ROOT / 'build/javac.args').read_text(encoding='utf-8'), posix=True)
    dependencies = tokens[tokens.index('-cp') + 1].split(os.pathsep)
    if Path(tokens[tokens.index('-d') + 1]).resolve() != production.resolve():
        raise SystemExit('build-result classes disagree with javac.args; rebuild before testing')
    if hashlib.sha256(Path(result['artifact']).read_bytes()).hexdigest() != result['sha256']:
        raise SystemExit('Build artifact hash mismatch')
    production_sources = sorted((ROOT / 'src/main/java').rglob('*.java'))
    built_at = (ROOT / 'build/build-result.json').stat().st_mtime_ns
    if any(path.stat().st_mtime_ns > built_at for path in production_sources):
        raise SystemExit('Production sources changed after the latest build; rebuild before testing')
    production_classes = sorted(production.rglob('*.class'))
    if not production_classes:
        raise SystemExit('Latest compiled production classes are missing')
    with zipfile.ZipFile(result['artifact']) as artifact:
        for path in production_classes:
            if artifact.read(path.relative_to(production).as_posix()) != path.read_bytes():
                raise SystemExit('Production classes differ from the recorded artifact')
    if not args.sable.is_file() or not args.qa_host.is_file():
        raise SystemExit('Missing explicit Sable or QA-host input')
    destination = args.output or ROOT / 'build'
    destination.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix='behavior-', dir=destination)).resolve()
    classes = run / 'tests'; classes.mkdir()
    dependencies = [Path(value) for value in dependencies if not contains_sable(value)]
    for dependency in args.extra_dependency:
        if not dependency.is_file():
            raise SystemExit('Missing extra runtime dependency')
        dependencies.append(dependency)
    nested_dependencies = nested_replay_dependencies(dependencies, run)
    with zipfile.ZipFile(args.sable) as sable:
        companion = 'META-INF/jarjar/sable-companion-common-1.21.1-1.6.0.jar'
        info = sable.getinfo(companion)
        if info.file_size > 8 * 1024 * 1024:
            raise SystemExit('Unexpected oversized Sable companion dependency')
        companion_path = run / Path(companion).name
        companion_path.write_bytes(sable.read(info))
        nested_dependencies.append(companion_path)
    dependencies.extend(nested_dependencies)
    dependencies, minecraft = prioritize_minecraft(dependencies)
    dependencies, selected_asm = prioritize_asm(dependencies)
    with_sable = run / 'with-sable.jar'; without_sable = run / 'without-sable.jar'
    common = [production, ROOT / 'src/test/resources', *dependencies]
    classpath_jar(with_sable, [*common, args.sable])
    classpath_jar(without_sable, common)
    unsupported = run / 'unreviewed-sable-resources.jar'
    with zipfile.ZipFile(unsupported, 'w') as jar:
        jar.writestr('dev/ryanhcode/sable/Sable.class', b'not a class; resource-only gate fixture')
        prefix = 'dev/ryanhcode/sable/mixin/entity/entities_stick_sublevels/packet_mixin/'
        for name in ('ClientboundTeleportEntityPacketMixin', 'ClientboundMoveEntityPacketPosMixin', 'ClientboundMoveEntityPacketPosRotMixin'):
            jar.writestr(prefix + name + '.class', b'unreviewed resource fixture')
    unsupported_cp = run / 'with-unsupported.jar'
    classpath_jar(unsupported_cp, [*common, unsupported])
    # Keep the three wire mixins valid but invalidate the newly reviewed decoder semantics.
    # This proves that checking only packet serialization is not enough for the space proof.
    changed_space = run / 'unreviewed-space-resources.jar'
    with zipfile.ZipFile(args.sable) as source, zipfile.ZipFile(changed_space, 'w') as jar:
        for entry in source.infolist():
            data = source.read(entry)
            if entry.filename == 'dev/ryanhcode/sable/mixin/entity/entities_stick_sublevels/ClientPacketListenerMixin.class':
                data = b'unreviewed decoder resource fixture'
            jar.writestr(entry, data)
    changed_space_cp = run / 'with-unreviewed-space.jar'
    classpath_jar(changed_space_cp, [*common, changed_space])
    sources = sorted((ROOT / 'src/test/java').rglob('*.java')) + [args.qa_host]
    exe = '.exe' if os.name == 'nt' else ''
    javac = args.java_home / 'bin' / ('javac' + exe); java = args.java_home / 'bin' / ('java' + exe)
    compile_args = ['-proc:none', '--release', '21', '-encoding', 'UTF-8', '-cp', str(with_sable), '-d', str(classes), *map(str, sources)]
    argfile = run / 'tests.args'
    argfile.write_text('\n'.join('"' + value.replace('\\', '/').replace('"', '\\"') + '"' for value in compile_args), encoding='utf-8')
    subprocess.run([str(javac), '-J-Dfile.encoding=UTF-8', '-J-Duser.language=en', '@' + str(argfile)], check=True)
    tests = [
        ('cn.piq.replayquick.SableFlagTimelineTest', [], with_sable),
        ('cn.piq.replayquick.RecordedPlayerIdentitiesTest', [], with_sable),
        ('cn.piq.replayquick.RecordedDimensionTest', [], with_sable),
        ('cn.piq.replayquick.QuickSableStateBehaviorTest', [], with_sable),
        ('cn.piq.replayquick.SablePositionSpaceTest', [], with_sable),
        ('cn.piq.replayquick.QuickSableScopeTest', [], with_sable),
        ('cn.piq.replayquick.SableWireContractTest', ['true'], with_sable),
        ('cn.piq.replayquick.SableWireContractTest', ['false'], without_sable),
        ('cn.piq.replayquick.SableWireContractTest', ['unsupported'], unsupported_cp),
        ('cn.piq.replayquick.SableWireContractTest', ['unsupported'], changed_space_cp),
    ]
    if args.recording_copy:
        tests.append(('RecordedReplayIndexTest', [str(args.recording_copy.resolve()), str(args.trace_json.resolve())], with_sable))
    decoder_args = [str(args.trace_json.resolve())] if args.trace_json else []
    if args.recording_copy:
        decoder_args.append(str(args.recording_copy.resolve()))
    tests.append(('ProductionQuickDecoderProbe', decoder_args, with_sable))
    receipts = []
    for number, (name, test_args, cp) in enumerate(tests):
        process = subprocess.run([str(java), '-Xmx256m', '-Dfile.encoding=UTF-8', '-Dsun.stdout.encoding=UTF-8', '-Dsun.stderr.encoding=UTF-8', '-ea', '-cp', os.pathsep.join(map(str, [classes, cp])), name, *test_args],
                                 capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=90)
        output = process.stdout + process.stderr
        (run / f'{number}-{name}.log').write_text(output, encoding='utf-8')
        print(output, end='')
        private_input = name in {'ProductionQuickDecoderProbe', 'RecordedReplayIndexTest'}
        receipts.append({'test': name, 'arguments': ['explicit private diagnostic inputs supplied'] if private_input and test_args else [] if private_input else test_args, 'exit': process.returncode})
        if process.returncode:
            raise SystemExit(f'Behavior test failed: {name}; retained logs in {run}')
    summary = {'ok': True, 'artifact_sha256': result['sha256'], 'production_classes': production.relative_to(ROOT).as_posix(),
               'harness_asm': [path.name for path in selected_asm],
               'minecraft_client': {'name': minecraft.name, 'sha256': hashlib.sha256(minecraft.read_bytes()).hexdigest()},
               'nested_dependencies': [{'name': path.name, 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()}
                                       for path in nested_dependencies],
               'verified_production_class_count': len(production_classes),
               'production_source_sha256': {path.relative_to(ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest()
                                            for path in production_sources},
               'sable_sha256': hashlib.sha256(args.sable.read_bytes()).hexdigest(), 'tests': receipts,
               'no_game_started': True, 'actual_production_completion_and_scope': True,
               'real_sponge_and_complete_packet_decoder': True,
               'limitation': 'No graphical client, full production dispatch gates or gameplay acceptance. Test inputs populate private fields reflectively.'}
    (run / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(json.dumps({'ok': True, 'result_directory': str(run)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
