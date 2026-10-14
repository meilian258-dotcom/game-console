"""Apply the five production Mixins to supplied read-only game dependencies.

Run build.py successfully first. No recording, player data, Minecraft launch or
game-directory writes are involved. All outputs stay in this project's build/.
The optional --dependencies-args accepts a reviewed javac.args with a narrower
runtime dependency set; production target JARs still come from this project's
own build/javac.args and its build receipt.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import re
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
QA = ROOT / 'tools/qa'
REPLAY_TARGET = 'com/replaymod/replay/QuickReplaySender.class'
CONFIG_TARGET = 'net/minecraft/client/multiplayer/ClientConfigurationPacketListenerImpl.class'


def sha(path):
    with path.open('rb') as source:
        return hashlib.file_digest(source, 'sha256').hexdigest()


def arg_values(path):
    # build.py emits one JSON-compatible quoted argument per line.
    return [json.loads(line) for line in path.read_text(encoding='utf-8-sig').splitlines() if line.strip()]


def classpath(path):
    values = arg_values(path)
    try:
        value = values[values.index('-cp') + 1]
    except (ValueError, IndexError) as error:
        raise SystemExit('Dependency argument file has no -cp argument') from error
    result = list(dict.fromkeys(Path(item).resolve(strict=True) for item in value.split(os.pathsep)))
    if any(not item.is_file() or item.suffix != '.jar' for item in result):
        raise SystemExit('Only existing JAR dependencies are accepted')
    return result


def has_class(path, name):
    with zipfile.ZipFile(path) as archive:
        return name in archive.namelist()


def version_key(text):
    return tuple(int(value) for value in re.findall(r'\d+', text))


def prune_old_maven_versions(paths):
    """Do not let a lexically first old library shadow a newer declared version.

    Only recognizable Maven artifact/version/file layouts are grouped. Mod JARs
    and arbitrary supplied filenames are preserved, not guessed or renamed.
    """
    groups = {}
    keys = {}
    for path in paths:
        version = path.parent.name
        artifact = path.parent.parent.name
        if path.name.startswith(artifact + '-' + version) and re.match(r'^\d', version):
            key = path.parent.parent
            keys[path] = key
            groups.setdefault(key, set()).add(version)
    selected = {key: max(versions, key=lambda item: (version_key(item), item)) for key, versions in groups.items()}
    return [path for path in paths if path not in keys or path.parent.name == selected[keys[path]]]


def preferred_utilities(paths, sponge):
    # Put external ASM first even when another dependency bundles older ASM.
    names = ('asm', 'asm-tree', 'asm-commons', 'asm-analysis', 'asm-util',
             'gson', 'guava', 'fastutil', 'commons-lang3', 'slf4j-api',
             'log4j-api', 'log4j-core', 'log4j-slf4j2-impl')
    result = []
    for name in names:
        candidates = [p for p in paths if p.parent.parent.name == name and p.name.startswith(name + '-')]
        if candidates:
            result.extend(sorted(candidates, key=lambda p: version_key(p.parent.name), reverse=True))
    return [*result, sponge]


def java_argfile(path, values):
    path.write_text('\n'.join(json.dumps(str(value).replace('\\', '/'), ensure_ascii=False) for value in values), encoding='utf-8')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--dependencies-args', type=Path, help='Optional reviewed javac.args dependency classpath')
    args = parser.parse_args()
    receipt_path = ROOT / 'build/build-result.json'
    if not receipt_path.is_file():
        raise SystemExit('Run build.py successfully before this test')
    receipt = json.loads(receipt_path.read_text(encoding='utf-8'))
    artifact = Path(receipt['artifact']).resolve(strict=True)
    if sha(artifact) != receipt['sha256']:
        raise SystemExit('Production artifact does not match build receipt')
    own = classpath(ROOT / 'build/javac.args')
    supplied = classpath(args.dependencies_args.resolve(strict=True)) if args.dependencies_args else own
    replay = next((p for p in own if has_class(p, REPLAY_TARGET) and sha(p) == receipt['target_replay_sha256']), None)
    # build.py declares its exact target client and universal before other libraries.
    client = next((p for p in own if has_class(p, CONFIG_TARGET)), None)
    if replay is None or client is None or client.name != 'neoforge-21.1.250-client.jar':
        raise SystemExit('The build must target its recorded Replay JAR and NeoForge 21.1.250 client')
    universal = client.with_name(client.name.replace('-client.jar', '-universal.jar'))
    if universal not in own:
        raise SystemExit('The corresponding NeoForge universal JAR is absent from the build classpath')
    # Do not silently replace the declared host API with a newer Fabric wrapper:
    # 0.17.x and 0.15.x both advertise Mixin 0.8.7 but have different service APIs.
    sponge = next((p for p in supplied if p.parent.parent.name == 'sponge-mixin' and 'mixin.0.8.7' in p.name), None)
    if sponge is None:
        sponge = next((p for p in supplied if p.parent.parent.name == 'mixin' and p.parent.name == '0.8.7'), None)
    if sponge is None:
        raise SystemExit('A declared Sponge Mixin 0.8.7 dependency is required; use --dependencies-args with the reviewed instance classpath')
    pruned = prune_old_maven_versions(supplied)
    utilities = preferred_utilities(pruned, sponge)
    # Competing target implementations are excluded, rather than relying on chance.
    rest = [p for p in pruned if p in (client, universal, replay) or
            not has_class(p, CONFIG_TARGET) and not has_class(p, REPLAY_TARGET)
            and not has_class(p, 'org/spongepowered/asm/service/IMixinService.class')]
    dependencies = list(dict.fromkeys([*utilities, client, universal, replay, *rest]))
    out = Path(tempfile.mkdtemp(prefix='transform-', dir=ROOT / 'build'))
    exe = '.exe' if os.name == 'nt' else ''
    javac = args.java_home / 'bin' / ('javac' + exe)
    java = args.java_home / 'bin' / ('java' + exe)
    cp = os.pathsep.join(map(str, [artifact, *dependencies]))
    compile_args = ['--release', '21', '-proc:none', '-encoding', 'UTF-8', '-cp', cp, '-d', str(out),
                    str(QA / 'KeyboardMixinTransformProbe.java'), str(QA / 'QuickModeProductionTransformProbe.java')]
    compile_file = out / 'compile.args'
    java_argfile(compile_file, compile_args)
    run_file = out / 'run.args'
    run_values = ['-ea', '-Dfile.encoding=UTF-8', '-cp',
                  os.pathsep.join([str(QA / 'resources'), str(out), cp]),
                  'QuickModeProductionTransformProbe', str(out), str(receipt_path)]
    java_argfile(run_file, run_values)
    # Windows java-launcher @argfile decoding can use a legacy code page before
    # -Dfile.encoding is processed. Direct Unicode argv preserves non-ASCII paths.
    if os.name == 'nt' and sum(len(value) + 4 for value in run_values) > 30000:
        raise SystemExit('Runtime classpath is too long for safe Unicode argv; use --dependencies-args with a reviewed narrower classpath')
    for stage, command in [('compile', [str(javac), '-J-Dfile.encoding=UTF-8', '-J-Duser.language=en', '@' + str(compile_file)]),
                           ('transform', [str(java), *run_values])]:
        result = subprocess.run(command, cwd=out, capture_output=True, text=True, encoding='utf-8', errors='replace')
        if result.returncode:
            (out / f'{stage}-failure-private.log').write_text(result.stdout + result.stderr, encoding='utf-8')
            (out / 'runner-result.json').write_text(json.dumps({'ok': False, 'stage': stage, 'exit_code': result.returncode}, indent=2), encoding='utf-8')
            raise SystemExit(f'Transform test failed during {stage}; local evidence: {out}')
    verification_path = out / 'verification.json'
    verification = json.loads(verification_path.read_text(encoding='utf-8'))
    if not verification.get('ok') or not verification.get('allFiveProductionMixinsApplied'):
        raise SystemExit(f'Test did not produce a successful verification receipt: {out}')
    verification['runner'] = {
        'dependencies_override_used': args.dependencies_args is not None,
        'old_maven_entries_pruned': len(supplied) - len(pruned),
        'production_targets_from_own_build': True,
        'production_artifact_sha256': sha(artifact),
        'replay_sha256': sha(replay),
        'neoforge_client_sha256': sha(client),
        'sponge_sha256': sha(sponge),
        'probe_source_sha256': sha(QA / 'QuickModeProductionTransformProbe.java'),
        'host_source_sha256': sha(QA / 'KeyboardMixinTransformProbe.java'),
    }
    verification_path.write_text(json.dumps(verification, indent=2) + '\n', encoding='utf-8')
    print(f'PASS: {verification["assertions"]} assertions; five production Mixins applied; no Minecraft launch.')
    print(f'Evidence: {verification_path}')


if __name__ == '__main__':
    main()
