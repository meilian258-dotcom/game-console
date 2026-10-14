"""Apply real Sponge/MixinExtras to pinned Iris bytecode without launching Minecraft.

Run build.py first; this tool reuses its exact compiled classes and dependency classpath.
If that build excluded Iris, pass --iris with the read-only reviewed Iris JAR.
Pass the same --build-dir used by build.py. Outputs and Java
temporary files stay in a fresh iris-transform-* directory there, never a game instance.
Successful verification.json and stdout.txt contain hashes/results, not private paths.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import subprocess
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
SUCCESS = ("IrisMixinTransformProbe: real Sponge + MixinExtras transform applied; "
           "exactly one wrap/flush call; both original method bodies retained; "
           "zero Minecraft classes defined")
IRIS_CLASS = 'net/irisshaders/batchedentityrendering/impl/FullyBufferedMultiBufferSource.class'


def sha(file):
    with file.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java-home', required=True, type=Path)
    parser.add_argument('--build-dir', required=True, type=Path,
                        help='Completed build directory containing this run\'s javac.args')
    parser.add_argument('--iris', type=Path, help='Read-only Iris JAR when absent from last build classpath')
    args = parser.parse_args()
    build = args.build_dir.resolve()
    argfile = build / 'javac.args'
    if not argfile.is_file():
        raise SystemExit('Run build.py successfully before this probe')
    # build.py emits one quoted argument per line, with portable forward-slash paths.
    values = [line.strip().removeprefix('"').removesuffix('"') for line in argfile.read_text(encoding='utf-8').splitlines()]
    dependencies = values[values.index('-cp') + 1].split(os.pathsep)
    classes = Path(values[values.index('-d') + 1]).resolve()
    if not classes.is_relative_to(build):
        raise SystemExit('Selected javac.args points outside this build directory; inspect the build inputs')
    if args.iris:
        iris = args.iris.resolve(strict=True)
        with zipfile.ZipFile(iris) as archive:
            if IRIS_CLASS not in archive.namelist():
                raise SystemExit('The explicit Iris input has no reviewed buffer class')
        dependencies.insert(0, str(iris))
    # The build argfile uses '/', while an explicit Windows path may use '\\'.
    # Normalize equivalent inputs before enforcing exactly one Iris implementation.
    dependencies = list(dict.fromkeys(str(Path(value).resolve()) for value in dependencies))
    iris_inputs = []
    for value in dict.fromkeys(dependencies):
        file = Path(value)
        if file.suffix != '.jar' or not file.is_file():
            continue
        with zipfile.ZipFile(file) as archive:
            if IRIS_CLASS in archive.namelist():
                iris_inputs.append(file)
    if len(iris_inputs) != 1:
        raise SystemExit(f'Need exactly one Iris input, found {len(iris_inputs)}')
    expected_classes = {
        'adapter': 'cn/piq/replaycompat/mixin/CaptureIrisBufferSource.class',
        'plugin': 'cn/piq/replaycompat/mixin/CompatMixinPlugin.class',
        'contract': 'cn/piq/replaycompat/capture/IrisBufferContract.class',
    }
    for relative in expected_classes.values():
        if not (classes / relative).is_file():
            raise SystemExit('Last compiled classes do not include the Iris capture fix')
    out = Path(tempfile.mkdtemp(prefix='iris-transform-', dir=build))
    java_tmp = out / 'tmp'
    java_tmp.mkdir()
    environment = os.environ.copy()
    environment.update(TMP=str(java_tmp), TEMP=str(java_tmp), TMPDIR=str(java_tmp))
    for key in ('JAVA_TOOL_OPTIONS', 'JDK_JAVA_OPTIONS', '_JAVA_OPTIONS', 'CLASSPATH'):
        environment.pop(key, None)
    binary = '.exe' if os.name == 'nt' else ''
    java_home = args.java_home.resolve(strict=True)
    javac = java_home / 'bin' / ('javac' + binary)
    java = java_home / 'bin' / ('java' + binary)
    cp = os.pathsep.join(dict.fromkeys([str(classes), *dependencies]))
    compile_command = [str(javac), '-J-Dfile.encoding=UTF-8', '-J-Duser.language=en',
                       '-J-Djava.io.tmpdir=' + str(java_tmp), '--release', '21',
                       '-proc:none', '-encoding', 'UTF-8', '-cp', cp, '-d', str(out),
                       str(ROOT.parent / 'piq-fc-arcade/tools/qa/KeyboardMixinTransformProbe.java'),
                       str(ROOT / 'tools/IrisMixinTransformProbe.java')]
    resources = ROOT / 'tools/mixin-probe-resources'
    run_command = [str(java), '-ea', '-Dfile.encoding=UTF-8', '-Djava.io.tmpdir=' + str(java_tmp),
                   '-Djna.tmpdir=' + str(java_tmp),
                   '-XX:ErrorFile=' + str(java_tmp / 'hs_err_pid%p.log'),
                   '-cp', os.pathsep.join([str(resources), str(out), cp]), 'IrisMixinTransformProbe']
    for stage, command in [('compile', compile_command), ('transform', run_command)]:
        result = subprocess.run(command, cwd=out, env=environment, capture_output=True,
                                text=True, encoding='utf-8', errors='replace')
        if result.returncode:
            # Raw failure details remain local/excluded from release material.
            (out / f'{stage}-failure.log').write_text(result.stdout + result.stderr, encoding='utf-8')
            (out / 'verification.json').write_text(json.dumps({'ok': False, 'stage': stage, 'exit_code': result.returncode}, indent=2), encoding='utf-8')
            raise SystemExit(f'Iris transform probe failed during {stage}; local evidence: {out}')
    if SUCCESS not in result.stdout.splitlines():
        raise SystemExit(f'Probe returned without a success assertion; preserve and inspect {out}')
    (out / 'stdout.txt').write_text(SUCCESS + '\n', encoding='utf-8')
    verification = {
        'ok': True,
        'actual_sponge_and_mixinextras_transform': True,
        'original_iris_method_bodies_preserved': True,
        'capture_wrap_calls': 1,
        'capture_flush_calls': 1,
        'minecraft_classes_defined': 0,
        'minecraft_graphical_validation': 'not performed',
        'javac_args_sha256': sha(argfile),
        'iris_sha256': sha(iris_inputs[0]),
        'probe_source_sha256': sha(ROOT / 'tools/IrisMixinTransformProbe.java'),
        **{key + '_class_sha256': sha(classes / relative) for key, relative in expected_classes.items()},
    }
    (out / 'verification.json').write_text(json.dumps(verification, indent=2) + '\n', encoding='utf-8')
    print(SUCCESS)
    print(f'Evidence: {out}')


if __name__ == '__main__':
    main()
